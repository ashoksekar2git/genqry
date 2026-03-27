package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Surgically updates the vector index when user-supplied metadata (table or
 * column descriptions) is saved via POST /api/v1/admin/metadata/add.
 *
 * Instead of re-running the entire RAG pipeline, this service:
 *
 *  1. Reads the updated table from the currently active schema.
 *  2. Applies the new descriptions from the request directly onto the
 *     in-memory DatabaseTable / SchemaColumn objects.
 *  3. Re-chunks ONLY the affected table (TABLE-level + all its COLUMN-level
 *     chunks + EAV_ATTRIBUTE chunks if applicable).
 *  4. Re-embeds the new chunks.
 *  5. Replaces the old chunks for that table in VectorStoreService without
 *     disturbing any other table's chunks.
 *  6. Persists the updated index to disk so the change survives restarts.
 *
 * This keeps query latency near-zero for all tables not touched by the edit,
 * while ensuring the LLM immediately sees the new descriptions on the next
 * NL→SQL request.
 */
@Service
public class MetadataAwareChunkUpdateService {

    private static final Logger log = LoggerFactory.getLogger(MetadataAwareChunkUpdateService.class);

    @Autowired private RAGInitializationService    ragInitializationService;
    @Autowired private ChunkingService             chunkingService;
    @Autowired private VectorStoreService          vectorStoreService;
    @Autowired private VectorIndexPersistenceService persistenceService;
    @Autowired private SchemaEnrichmentService     enrichmentService;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Called after a successful metadata save.  Finds the affected table in the
     * active schema, patches description, businessContext, isEavTable flag and column descriptions
     * in-place, re-chunks that table only, re-embeds the new chunks and splices
     * them into the live vector index.
     *
     * @param tableName        the table whose metadata changed
     * @param tableDescription new table description     (null = unchanged)
     * @param businessContext  new table business context (null = unchanged)
     * @param isEavTable       new EAV table flag         (null = unchanged)
     * @param columnUpdates    map of columnName → new description (empty = no column changes)
     */
    public void updateChunksForTable(String tableName,
                                     String tableDescription,
                                     String businessContext,
                                     Boolean isEavTable,
                                     Map<String, String> columnUpdates) {

        log.info("▶ Metadata-aware chunk update — table='{}' descChanged={} contextChanged={} columnChanges={}",
                tableName,
                tableDescription != null && !tableDescription.isBlank(),
                businessContext  != null && !businessContext.isBlank(),
                columnUpdates.size());

        // ── 1. Get the active schema ──────────────────────────────────────────
        DatabaseSchema schema = ragInitializationService.getActiveSchema();
        if (schema == null || schema.getTables() == null) {
            log.warn("No active schema in RAG pipeline — skipping chunk update for '{}'", tableName);
            return;
        }

        // ── 2. Find the matching DatabaseTable ────────────────────────────────
        DatabaseTable targetTable = schema.getTables().stream()
                .filter(t -> tableName.equalsIgnoreCase(t.getTableName()))
                .findFirst()
                .orElse(null);

        if (targetTable == null) {
            log.warn("Table '{}' not found in active schema ({}) — skipping chunk update",
                    tableName, schema.getDatabaseName());
            return;
        }

        // ── 3. Patch fields in-place on the in-memory schema object ──────────
        boolean changed = false;

        if (tableDescription != null && !tableDescription.isBlank()) {
            targetTable.setDescription(tableDescription.trim());
            log.debug("  Table description updated in-memory: '{}'", tableDescription.trim());
            changed = true;
        }

        // ── Business context is the key field for CONTEXT : in the LLM prompt ─
        // It feeds into DatabaseTable.toEmbeddingText() which is the text that
        // gets embedded and later injected as  CONTEXT : ...  in the prompt.
        if (businessContext != null && !businessContext.isBlank()) {
            targetTable.setBusinessContext(businessContext.trim());
            log.info("  Table businessContext updated in-memory for '{}': '{}'",
                    tableName, businessContext.trim());
            changed = true;
        }

        if (isEavTable != null) {
            targetTable.setEavTable(isEavTable);
            log.info("  Table isEavTable flag updated in-memory for '{}': '{}'",
                    tableName, isEavTable);
            changed = true;
        }

        if (!columnUpdates.isEmpty() && targetTable.getColumns() != null) {
            for (SchemaColumn col : targetTable.getColumns()) {
                String newDesc = columnUpdates.get(col.getName().toLowerCase());
                if (newDesc != null && !newDesc.isBlank()) {
                    col.setDescription(newDesc.trim());
                    log.debug("  Column '{}' description updated in-memory: '{}'",
                            col.getName(), newDesc.trim());
                    changed = true;
                }
            }
        }

        if (!changed) {
            log.info("No changes detected for '{}' — chunk update skipped", tableName);
            return;
        }

        // ── 4. Re-enrich the patched table (expand abbreviations / aliases) ───
        //      We run enrichment on a mini single-table schema so the abbreviation
        //      expander and alias injector can work their usual logic.
        DatabaseSchema singleTableSchema = singleTableSchema(schema, targetTable);
        DatabaseSchema enriched = enrichmentService.enrich(singleTableSchema);
        DatabaseTable enrichedTable = enriched.getTables().get(0);

        // ── 5. Re-chunk only this table ───────────────────────────────────────
        List<SchemaChunk> newChunks = rechunkTable(enrichedTable, schema);
        log.info("Re-chunked table '{}' → {} new chunks", tableName, newChunks.size());

        // ── 6. Re-embed the new chunks ────────────────────────────────────────
        int embeddedCount = reembed(newChunks);
        log.info("Re-embedded {}/{} chunks for table '{}'",
                embeddedCount, newChunks.size(), tableName);

        // ── 7. Splice new chunks into the live vector index ───────────────────
        vectorStoreService.replaceChunksForTable(tableName, newChunks);
        log.info("Vector index updated — replaced all chunks for table '{}' ({} chunks)",
                tableName, newChunks.size());

        // ── 8. Persist updated index to disk ─────────────────────────────────
        String dbName = vectorStoreService.getCurrentDatabaseName();
        if (dbName != null) {
            persistenceService.save(dbName, vectorStoreService.getAllChunks());
            log.info("Updated vector index persisted to disk for database '{}'", dbName);
        }

        log.info("◀ Metadata-aware chunk update complete for table '{}'", tableName);
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Builds a single-table DatabaseSchema wrapper so SchemaEnrichmentService
     * can run its normal logic (abbreviation expansion, alias injection) on just
     * this one table without touching the rest of the schema.
     */
    private DatabaseSchema singleTableSchema(DatabaseSchema parent, DatabaseTable table) {
        DatabaseSchema mini = new DatabaseSchema();
        mini.setDatabaseName(parent.getDatabaseName());
        mini.setDatabaseType(parent.getDatabaseType());
        mini.setTables(java.util.Collections.singletonList(table));
        return mini;
    }

    /**
     * Re-chunks a single enriched table (TABLE + COLUMN + EAV_ATTRIBUTE chunks).
     * Uses the same ChunkingService logic as the full pipeline so chunk format
     * and metadata shape are identical.
     */
    private List<SchemaChunk> rechunkTable(DatabaseTable table, DatabaseSchema schema) {
        // Build a DatabaseSchema wrapper that ChunkingService can iterate over
        DatabaseSchema wrapper = singleTableSchema(schema, table);
        return chunkingService.chunkSchema(wrapper);
    }

    /**
     * Calls VectorStoreService.embedText() for each chunk and sets the resulting
     * float[] embedding on the chunk object.
     *
     * Falls back gracefully — chunks without embeddings will be picked up by the
     * keyword fallback during search, so a missing API key is never fatal.
     *
     * @return number of chunks that were successfully embedded
     */
    private int reembed(List<SchemaChunk> chunks) {
        int count = 0;
        for (SchemaChunk chunk : chunks) {
            try {
                float[] vec = vectorStoreService.embedText(chunk.getText());
                if (vec != null) {
                    chunk.setEmbedding(vec);
                    count++;
                }
            } catch (Exception e) {
                log.debug("Embedding failed for chunk '{}': {}", chunk.getChunkId(), e.getMessage());
            }
        }
        return count;
    }
}
