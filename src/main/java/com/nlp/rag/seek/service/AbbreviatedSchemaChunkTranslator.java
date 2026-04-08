package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.SchemaChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Enriches schema chunks with descriptive names for abbreviated schemas.
 *
 * <h3>Strategy (Enrichment, NOT Substitution)</h3>
 * <p>Instead of replacing abbreviated names with descriptive ones (which would
 * cause the LLM to generate SQL with descriptive names and trigger false
 * hallucination errors), this service <b>enriches</b> chunks by adding descriptive
 * context alongside the original abbreviated names.</p>
 *
 * <p>The LLM prompt includes an explicit instruction:
 * <em>"The schema uses abbreviated names. Use ONLY the original abbreviated
 * table/column names in the SQL."</em></p>
 *
 * <p>This way:</p>
 * <ul>
 *   <li>The LLM understands what "sch_dtl" means (school_details)</li>
 *   <li>The LLM generates SQL using "sch_dtl", "sch_nm" etc. — matching the real DB</li>
 *   <li>No post-LLM reverse translation needed</li>
 *   <li>Validation sees original table/column names — no hallucination false positives</li>
 * </ul>
 */
@Service
public class AbbreviatedSchemaChunkTranslator {

    private static final Logger log = LoggerFactory.getLogger(AbbreviatedSchemaChunkTranslator.class);

    @Autowired
    private AbbreviatedSchemaMapper mapper;

    // =========================================================================
    // Pre-LLM: Enrich chunks with descriptive context
    // =========================================================================

    /**
     * Enriches chunks with descriptive names alongside abbreviated originals.
     * Returns a NEW list with cloned chunks — the originals are not modified.
     *
     * <p>For each chunk, appends a glossary like:</p>
     * <pre>
     * --- ABBREVIATION GLOSSARY ---
     * TABLE: sch_dtl (means: school_details)
     * COLUMNS: sch_id = school_id, sch_nm = school_name, sch_loc = school_location
     * Use ONLY the abbreviated names (left side) in SQL.
     * --- END GLOSSARY ---
     * </pre>
     *
     * <p>The original chunk text, table_name, column_name metadata remain
     * in their abbreviated form so the LLM uses them directly in SQL.</p>
     *
     * @param databaseName  the current database name
     * @param scoredChunks  the scored chunks selected for the prompt
     * @return enriched chunks (or the originals if not abbreviated)
     */
    public List<VectorStoreService.ScoredChunk> enrichChunksWithDescriptiveContext(
            String databaseName, List<VectorStoreService.ScoredChunk> scoredChunks) {

        if (!mapper.isAbbreviated(databaseName)) {
            return scoredChunks; // not abbreviated — pass-through
        }

        log.info("Enriching {} chunk(s) with descriptive context for abbreviated db='{}'",
                scoredChunks.size(), databaseName);

        Map<String, AbbreviatedSchemaMapper.TableMapping> allMappings = mapper.getAllMappings(databaseName);
        List<VectorStoreService.ScoredChunk> enriched = new ArrayList<>(scoredChunks.size());

        for (VectorStoreService.ScoredChunk sc : scoredChunks) {
            SchemaChunk original = sc.chunk();
            SchemaChunk clone = cloneChunk(original);

            String tableName = clone.getTableName();
            if (tableName == null) {
                enriched.add(new VectorStoreService.ScoredChunk(clone, sc.score()));
                continue;
            }

            AbbreviatedSchemaMapper.TableMapping tm = allMappings.get(tableName.toLowerCase());
            if (tm == null) {
                enriched.add(new VectorStoreService.ScoredChunk(clone, sc.score()));
                continue;
            }

            // Build the descriptive enrichment text
            StringBuilder enrichment = new StringBuilder();
            enrichment.append("\n--- ABBREVIATION GLOSSARY ---\n");
            enrichment.append("TABLE: ").append(tableName)
                       .append(" (means: ").append(tm.descriptiveName).append(")\n");

            if (tm.columns != null && !tm.columns.isEmpty()) {
                enrichment.append("COLUMNS: ");
                List<String> colMappings = new ArrayList<>();
                for (Map.Entry<String, String> ce : tm.columns.entrySet()) {
                    if (!ce.getKey().equalsIgnoreCase(ce.getValue())) {
                        colMappings.add(ce.getKey() + " = " + ce.getValue());
                    }
                }
                if (!colMappings.isEmpty()) {
                    enrichment.append(String.join(", ", colMappings));
                }
                enrichment.append("\n");
            }
            enrichment.append("⚠ Use ONLY the abbreviated names (left side) in SQL.\n");
            enrichment.append("--- END GLOSSARY ---");

            // Append enrichment to the chunk text
            clone.setText(clone.getText() + enrichment);

            // Add descriptive info to metadata for buildSchemaContext
            Map<String, String> meta = clone.getMetadata();
            if (meta != null) {
                meta.put("abbreviated_table", tableName);
                meta.put("descriptive_table", tm.descriptiveName);

                // Enrich column metadata if present
                String colName = meta.get("column_name");
                if (colName != null && tm.columns != null) {
                    String descCol = tm.columns.get(colName.toLowerCase());
                    if (descCol != null && !descCol.equalsIgnoreCase(colName)) {
                        meta.put("descriptive_column", descCol);
                    }
                }
            }

            enriched.add(new VectorStoreService.ScoredChunk(clone, sc.score()));
        }

        return enriched;
    }

    /**
     * Builds the abbreviated-schema instruction block that is prepended to the
     * LLM prompt when the schema uses abbreviated names.
     *
     * <p>This is the key instruction that tells the LLM to use the ORIGINAL
     * abbreviated names in the SQL, NOT the descriptive equivalents.</p>
     *
     * @param databaseName  the current database
     * @return instruction text, or empty string if not abbreviated
     */
    public String buildAbbreviationPromptInstruction(String databaseName) {
        if (!mapper.isAbbreviated(databaseName)) return "";

        Map<String, AbbreviatedSchemaMapper.TableMapping> allMappings = mapper.getAllMappings(databaseName);
        if (allMappings.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n⚠ ABBREVIATED SCHEMA NOTICE:\n");
        sb.append("This database uses abbreviated table and column names. ");
        sb.append("Each chunk includes a GLOSSARY mapping abbreviated names to their full meaning.\n");
        sb.append("CRITICAL RULE: You MUST use the ORIGINAL ABBREVIATED names (e.g. '");

        // Show a quick example from the first table
        Map.Entry<String, AbbreviatedSchemaMapper.TableMapping> first = allMappings.entrySet().iterator().next();
        sb.append(first.getKey()).append("' NOT '").append(first.getValue().descriptiveName).append("'");
        sb.append(") in the SQL query. The descriptive names are provided ONLY for your understanding.\n");

        sb.append("TABLE NAME MAPPING:\n");
        for (Map.Entry<String, AbbreviatedSchemaMapper.TableMapping> e : allMappings.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue().descriptiveName).append("\n");
        }

        sb.append("Remember: SQL must use abbreviated names from the LEFT side of each mapping.\n\n");
        return sb.toString();
    }

    // =========================================================================
    // Query check
    // =========================================================================

    /**
     * Returns true if the given database has an abbreviated schema mapping.
     */
    public boolean isAbbreviated(String databaseName) {
        return mapper.isAbbreviated(databaseName);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Deep-clones a SchemaChunk so we don't mutate the original in the vector store.
     */
    private SchemaChunk cloneChunk(SchemaChunk original) {
        SchemaChunk clone = new SchemaChunk();
        clone.setChunkId(original.getChunkId());
        clone.setTableName(original.getTableName());
        clone.setColumnName(original.getColumnName());
        clone.setElementType(original.getElementType());
        clone.setText(original.getText());
        clone.setChunkIndex(original.getChunkIndex());
        clone.setWordStart(original.getWordStart());
        clone.setWordEnd(original.getWordEnd());
        clone.setEmbedding(original.getEmbedding());
        clone.setTokens(original.getTokens());
        if (original.getMetadata() != null) {
            clone.setMetadata(new LinkedHashMap<>(original.getMetadata()));
        }
        return clone;
    }
}
