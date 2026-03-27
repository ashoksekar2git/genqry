package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.DatabaseSchema;
import com.nlp.rag.seek.model.DatabaseTable;
import com.nlp.rag.seek.model.EavConfig;
import com.nlp.rag.seek.model.EavKnownAttribute;
import com.nlp.rag.seek.model.SchemaChunk;
import com.nlp.rag.seek.model.SchemaColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts raw schema text into overlapping word-level chunks with rich metadata.
 *
 * Strategy
 * ────────
 *  chunkSize : target number of words per chunk          (default 80)
 *  stride    : how many words to advance each iteration  (default 40)
 *  overlap   : chunkSize – stride = words shared         (default 40)
 *
 * Each chunk carries a {@code metadata} map containing:
 *  - table_name, column_name, element_type
 *  - data_type, is_primary_key, is_foreign_key, foreign_key_ref
 *  - description, business_context
 *
 * This metadata is attached to the vector in Pinecone and surfaced in
 * search results so the LLM prompt can reference exact schema identifiers
 * even when the user's query uses vague synonyms (e.g. "top clients" → customers).
 */
@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    @Value("${rag.chunking.chunk-size:80}")
    private int chunkSize;

    @Value("${rag.chunking.stride:40}")
    private int stride;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Produce all overlapping, metadata-enriched chunks for a complete schema.
     *
     * For each table we produce:
     *   1. One (or more) TABLE-level chunks embedding the full table context.
     *   2. One (or more) COLUMN-level chunks per column embedding column semantics.
     *
     * Every chunk carries a metadata map so retrieval results include
     * structured schema details ready for prompt injection.
     */
    public List<SchemaChunk> chunkSchema(DatabaseSchema schema) {
        List<SchemaChunk> all = new ArrayList<>();
        if (schema == null || schema.getTables() == null) return all;

        for (DatabaseTable table : schema.getTables()) {

            // ── TABLE-level chunk(s) ─────────────────────────────────────────
            Map<String, String> tableMeta = buildTableMetadata(table, schema);
            all.addAll(chunkText(
                    table.toEmbeddingText(),
                    table.getTableName(),
                    null,
                    "TABLE",
                    tableMeta
            ));

            // ── COLUMN-level chunk(s) ────────────────────────────────────────
            if (table.getColumns() != null) {
                for (SchemaColumn col : table.getColumns()) {
                    Map<String, String> colMeta = buildColumnMetadata(col, table, schema);
                    all.addAll(chunkText(
                            col.toEmbeddingText(),
                            table.getTableName(),
                            col.getName(),
                            "COLUMN",
                            colMeta
                    ));
                }
            }

            // ── EAV_ATTRIBUTE chunk(s) — one per known logical attribute ─────
            if (table.isEavTable() && table.getEavConfig() != null) {
                EavConfig eav = table.getEavConfig();
                if (eav.getKnownAttributes() != null && !eav.getKnownAttributes().isEmpty()) {
                    for (EavKnownAttribute attr : eav.getKnownAttributes()) {
                        Map<String, String> eavMeta = buildEavAttributeMetadata(attr, table, schema, eav);
                        String eavText = attr.toEmbeddingText(
                                table.getTableName(),
                                eav.resolveEntityIdColumn(),
                                eav.resolveAttributeNameColumn(),
                                eav.resolveAttributeValueColumn(),
                                eav.getEntityTable());
                        all.addAll(chunkText(
                                eavText,
                                table.getTableName(),
                                attr.getAttributeKey(),   // use attribute key as "column name" for ID
                                "EAV_ATTRIBUTE",
                                eavMeta
                        ));
                    }
                    log.debug("EAV table '{}' — {} EAV_ATTRIBUTE chunk set(s) added",
                            table.getTableName(), eav.getKnownAttributes().size());
                } else {
                    log.debug("EAV table '{}' has no known_attributes catalogued — only TABLE/COLUMN chunks produced",
                            table.getTableName());
                }
            }
        }

        log.info("Chunking complete: {} metadata-enriched chunks from schema '{}' " +
                 "(chunkSize={}, stride={}, overlap={})",
                all.size(), schema.getDatabaseName(), chunkSize, stride, chunkSize - stride);
        return all;
    }

    // =========================================================================
    // Metadata builders
    // =========================================================================

    private Map<String, String> buildTableMetadata(DatabaseTable table, DatabaseSchema schema) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("table_name",      table.getTableName());
        m.put("element_type",    "TABLE");
        m.put("database_name",   schema.getDatabaseName());
        m.put("database_type",   schema.getDatabaseType());
        m.put("description",     nvl(table.getDescription()));
        m.put("business_context",nvl(table.getBusinessContext()));
        m.put("is_eav_table",    String.valueOf(table.isEavTable()));

        // EAV structural metadata on the TABLE chunk
        if (table.isEavTable() && table.getEavConfig() != null) {
            EavConfig eav = table.getEavConfig();
            m.put("eav_entity_id_column",   nvl(eav.resolveEntityIdColumn()));
            m.put("eav_attr_name_column",   nvl(eav.resolveAttributeNameColumn()));
            m.put("eav_attr_value_column",  nvl(eav.resolveAttributeValueColumn()));
            m.put("eav_entity_table",       nvl(eav.getEntityTable()));
            // Two-table EAV: attribute names live in a separate meta table
            if (eav.getAttributeMetaTable() != null) {
                m.put("eav_attr_meta_table",       nvl(eav.getAttributeMetaTable()));
                m.put("eav_attr_meta_join_column", nvl(eav.getAttributeMetaJoinColumn()));
                m.put("eav_attr_meta_name_column", nvl(eav.getAttributeMetaNameColumn()));
            }
            if (eav.getKnownAttributes() != null && !eav.getKnownAttributes().isEmpty()) {
                String knownKeys = eav.getKnownAttributes().stream()
                        .map(a -> a.getAttributeKey() + ":" + nvl(a.getDataType()))
                        .collect(java.util.stream.Collectors.joining(", "));
                m.put("eav_known_attributes", knownKeys);
            }
        }

        // list column names so the metadata alone describes the table shape
        if (table.getColumns() != null) {
            StringJoiner cols = new StringJoiner(", ");
            StringJoiner pks  = new StringJoiner(", ");
            StringJoiner fks  = new StringJoiner(", ");
            for (SchemaColumn c : table.getColumns()) {
                cols.add(c.getName() + ":" + c.getDataType());
                if (c.isPrimaryKey()) pks.add(c.getName());
                if (c.isForeignKey()) fks.add(c.getName() + "->" + c.getForeignKeyReference());
            }
            m.put("columns",         cols.toString());
            m.put("primary_keys",    pks.toString());
            m.put("foreign_keys",    fks.toString());
            m.put("column_count",    String.valueOf(table.getColumns().size()));
        }
        return m;
    }

    private Map<String, String> buildColumnMetadata(SchemaColumn col,
                                                     DatabaseTable table,
                                                     DatabaseSchema schema) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("table_name",           table.getTableName());
        m.put("column_name",          col.getName());
        m.put("element_type",         "COLUMN");
        m.put("database_name",        schema.getDatabaseName());
        m.put("data_type",            nvl(col.getDataType()));
        m.put("description",          nvl(col.getDescription()));
        m.put("is_primary_key",       String.valueOf(col.isPrimaryKey()));
        m.put("is_foreign_key",       String.valueOf(col.isForeignKey()));
        m.put("foreign_key_reference",nvl(col.getForeignKeyReference()));
        m.put("nullable",             String.valueOf(col.isNullable()));
        m.put("business_context",     nvl(table.getBusinessContext()));
        m.put("is_eav_table",         String.valueOf(table.isEavTable()));
        // readable form: "customer_id" → "customer id"
        m.put("readable_name",
              col.getName().replace("_", " ")
                           .replaceAll("([a-z])([A-Z])", "$1 $2")
                           .toLowerCase());
        return m;
    }

    /**
     * Builds the metadata map for an EAV_ATTRIBUTE chunk.
     * Contains all information the LLM needs to write a correct EAV query:
     * filter pattern, cast hint, join details, synonyms.
     */
    private Map<String, String> buildEavAttributeMetadata(EavKnownAttribute attr,
                                                           DatabaseTable table,
                                                           DatabaseSchema schema,
                                                           EavConfig eav) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("table_name",             table.getTableName());
        m.put("element_type",           "EAV_ATTRIBUTE");
        m.put("database_name",          schema.getDatabaseName());
        m.put("is_eav_table",           "true");
        m.put("eav_attribute_key",      attr.getAttributeKey());
        m.put("eav_data_type",          nvl(attr.getDataType()));
        m.put("eav_cast_hint",          nvl(attr.resolveCastHint(eav.resolveAttributeValueColumn())));
        m.put("eav_filter_pattern",
              "WHERE " + eav.resolveAttributeNameColumn() + " = '" + attr.getAttributeKey() + "'");
        m.put("eav_attr_name_column",   eav.resolveAttributeNameColumn());
        m.put("eav_attr_value_column",  eav.resolveAttributeValueColumn());
        m.put("eav_entity_id_column",   eav.resolveEntityIdColumn());
        m.put("eav_entity_table",       nvl(eav.getEntityTable()));
        m.put("description",            nvl(attr.getDescription()));
        if (attr.getSynonyms() != null && !attr.getSynonyms().isEmpty()) {
            m.put("eav_synonyms",       String.join(", ", attr.getSynonyms()));
        }
        return m;
    }

    // =========================================================================
    // Core windowed chunker
    // =========================================================================

    /**
     * Split a text into overlapping word-window chunks, attaching the given
     * metadata map to every chunk produced.
     *
     * @param text        raw semantic text to chunk
     * @param tableName   owning table  (for chunkId + metadata)
     * @param columnName  owning column – null for TABLE chunks
     * @param elementType "TABLE" | "COLUMN"
     * @param metadata    pre-built metadata map
     */
    public List<SchemaChunk> chunkText(String text,
                                       String tableName,
                                       String columnName,
                                       String elementType,
                                       Map<String, String> metadata) {
        List<SchemaChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        String[] words          = text.trim().split("\\s+");
        int totalWords          = words.length;
        int effectiveChunkSize  = Math.min(chunkSize, totalWords);
        int effectiveStride     = Math.min(stride, effectiveChunkSize);

        int start    = 0;
        int chunkIdx = 0;

        while (start < totalWords) {
            int end              = Math.min(start + effectiveChunkSize, totalWords);
            List<String> window  = Arrays.asList(Arrays.copyOfRange(words, start, end));
            String chunkText     = String.join(" ", window);
            String id            = buildId(tableName, columnName, chunkIdx);

            // Enrich metadata with positional context
            Map<String, String> chunkMeta = new LinkedHashMap<>(metadata);
            chunkMeta.put("chunk_index",  String.valueOf(chunkIdx));
            chunkMeta.put("word_start",   String.valueOf(start));
            chunkMeta.put("word_end",     String.valueOf(end));
            chunkMeta.put("total_words",  String.valueOf(totalWords));
            chunkMeta.put("chunk_id",     id);
            // overlap info
            if (chunkIdx > 0)
                chunkMeta.put("overlap_words", String.valueOf(effectiveChunkSize - effectiveStride));

            SchemaChunk chunk = SchemaChunk.builder()
                    .chunkId(id)
                    .tableName(tableName)
                    .columnName(columnName)
                    .elementType(elementType)
                    .text(chunkText)
                    .chunkIndex(chunkIdx)
                    .wordStart(start)
                    .wordEnd(end)
                    .tokens(window)
                    .metadata(chunkMeta)
                    .build();

            chunks.add(chunk);

            if (end == totalWords) break;
            start += effectiveStride;
            chunkIdx++;
        }

//        log.debug("Element '{}' → {} chunk(s) [{} words, chunkSize={}, stride={}]",
//                id(tableName, columnName), chunks.size(), totalWords, effectiveChunkSize, effectiveStride);
        return chunks;
    }

    /** Backwards-compat overload without metadata (empty map). */
    public List<SchemaChunk> chunkText(String text, String tableName,
                                       String columnName, String elementType) {
        return chunkText(text, tableName, columnName, elementType, new LinkedHashMap<>());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String buildId(String table, String column, int idx) {
        String base = column != null ? table + "." + column : table;
        return base + "#" + idx;
    }

    private String id(String table, String column) {
        return column != null ? table + "." + column : table;
    }

    private String nvl(String s) { return s != null ? s : ""; }

    public int getChunkSize() { return chunkSize; }
    public int getStride()    { return stride; }
    public int getOverlap()   { return chunkSize - stride; }
}

