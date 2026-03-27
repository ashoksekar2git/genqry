package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Extracts the database schema used by the RAG pipeline.
 *
 * Resolution order:
 *  1. PRIMARY datasource (PostgreSQL) — real schema via DatabaseMetaData
 *
 * The active datasource is resolved by {@link com.nlp.rag.seek.config.DataSourceConfig}.
 */
@Service
public class SchemaExtractionService {

    private static final Logger log = LoggerFactory.getLogger(SchemaExtractionService.class);

    /** The active datasource — PostgreSQL. */
    @Autowired
    @Qualifier("routingDataSource")
    private DataSource routingDataSource;

    /** Human-readable label for logging. */
    @Autowired
    @Qualifier("activeDataSourceName")
    private String activeDataSourceName;


    @Autowired
    private SchemaEnrichmentService enrichmentService;

    @Autowired
    private EavDetectionService eavDetectionService;

    @Autowired
    private SchemaFileReaderService schemaFileReaderService;

    // =========================================================================
    // Public API
    // =========================================================================
    /**
     * Returns the DatabaseSchema for the active datasource.
     * Tries to extract a live schema; falls back to the built-in sample when
     * the live DB has no user tables.
     *
     * Pipeline:  live JDBC extract → enrich abbreviations → EAV detection
     */
    public DatabaseSchema extractSchema() {
        log.info("Extracting schema from {} datasource", activeDataSourceName);
        try {
            DatabaseSchema live = extractLiveSchema(routingDataSource, activeDataSourceName);
            if (live == null || live.getTables().isEmpty()) {
                log.error("No user tables found in {} or extraction failed", activeDataSourceName);
                return null;
            }
            log.info("Live schema extracted from {}: {} table(s)", activeDataSourceName, live.getTables().size());
            // Merge descriptions AND EAV config authored in the JSON file into the live schema
            mergeDescriptionsFromJson(live);
            mergeEavConfigFromJson(live);
            DatabaseSchema enriched = enrichmentService.enrich(live);
            return eavDetectionService.detect(enriched);
        } catch (SQLException ex) {
            log.error("Schema extraction from {} failed: {}", activeDataSourceName, ex.getMessage());
            return null;
        }
    }


    /**
     * Builds a fully-populated {@link DatabaseSchema} by reading the saved
     * {databaseName}_schema_*.json file directly — with ALL user-authored
     * fields (description, business_context, column descriptions) preserved.
     *
     * Used by RAGInitializationService.onStartup() after restoring the vector
     * index from disk, so that the in-memory activeSchema matches exactly what
     * was persisted — including any business_context values the user set via
     * POST /api/v1/admin/metadata/add.
     *
     * This method NEVER touches live JDBC, so it cannot accidentally overwrite
     * user-authored metadata with empty strings from a DB extraction.
     *
     * @param databaseName  logical database name used to locate the JSON file
     * @return              enriched DatabaseSchema, or null if no JSON file found
     */
    @SuppressWarnings("unchecked")
    public DatabaseSchema buildActiveSchemaFromJson(String databaseName) {
        log.info("Building activeSchema from JSON file for database '{}'", databaseName);

        Optional<Map<String, Object>> rootOpt = schemaFileReaderService.getRawRoot(databaseName);
        if (rootOpt.isEmpty()) {
            log.warn("No JSON schema file found for '{}' — cannot build activeSchema from JSON", databaseName);
            return null;
        }

        Map<String, Object> root = rootOpt.get();
        String dbType = (String) root.getOrDefault("database_type", "UNKNOWN");

        Object tablesObj = root.get("tables");
        if (!(tablesObj instanceof List)) {
            log.warn("Schema JSON for '{}' has no tables array", databaseName);
            return null;
        }

        List<DatabaseTable> tables = new ArrayList<>();
        for (Object t : (List<?>) tablesObj) {
            if (!(t instanceof Map)) continue;
            Map<String, Object> tableMap = (Map<String, Object>) t;

            String tableName      = (String) tableMap.getOrDefault("table_name", "");
            String description    = (String) tableMap.getOrDefault("description", "");
            // ── This is the critical field that was being lost ─────────────────
            String businessCtx    = (String) tableMap.getOrDefault("business_context", "");

            List<SchemaColumn> columns = new ArrayList<>();
            Object colsObj = tableMap.get("columns");
            if (colsObj instanceof List) {
                Set<String> pkSet = new LinkedHashSet<>();
                Object pkObj = tableMap.get("primary_key");
                if (pkObj instanceof List) {
                    for (Object pk : (List<?>) pkObj) {
                        if (pk instanceof String) pkSet.add((String) pk);
                    }
                }
                for (Object c : (List<?>) colsObj) {
                    if (!(c instanceof Map)) continue;
                    Map<String, Object> col = (Map<String, Object>) c;
                    String colName   = (String) col.getOrDefault("column_name", "");
                    String colType   = (String) col.getOrDefault("data_type", "");
                    String colDesc   = (String) col.getOrDefault("description", "");
                    boolean isPk     = Boolean.TRUE.equals(col.get("primary_key"))
                                       || pkSet.contains(colName);
                    Object fkObj     = col.get("foreign_key");
                    String fkRef     = fkObj instanceof String && !((String) fkObj).isBlank()
                                       ? (String) fkObj : null;
                    boolean isNullable = !Boolean.FALSE.equals(col.get("nullable"));

                    columns.add(SchemaColumn.builder()
                            .name(colName)
                            .dataType(colType)
                            .description(colDesc.isBlank() ? colName + " (" + colType + ")" : colDesc)
                            .primaryKey(isPk)
                            .foreignKey(fkRef != null)
                            .foreignKeyReference(fkRef)
                            .nullable(isNullable)
                            .build());
                }
            }

            tables.add(DatabaseTable.builder()
                    .tableName(tableName)
                    .description(description.isBlank() ? tableName + " table" : description)
                    .businessContext(businessCtx)   // ← user-authored value preserved
                    .columns(columns)
                    .build());
        }

        if (tables.isEmpty()) {
            log.warn("Schema JSON for '{}' produced no tables", databaseName);
            return null;
        }

        DatabaseSchema schema = DatabaseSchema.builder()
                .databaseName(databaseName)
                .databaseType(dbType)
                .tables(tables)
                .build();

        log.info("buildActiveSchemaFromJson: '{}' — {} table(s) loaded from JSON " +
                 "(business_context preserved for all tables)", databaseName, tables.size());

        // Apply enrichment (abbreviation expansion, aliases) but NO live JDBC
        DatabaseSchema enriched = enrichmentService.enrich(schema);
        return eavDetectionService.detect(enriched);
    }

    /**
     * Converts a {@link SqlFileSchemaExtractorService.SqlSchemaResult} — produced
     * by parsing an uploaded .sql DDL file — directly into a fully enriched
     * {@link DatabaseSchema} ready for RAG chunking and indexing.
     *
     * This method NEVER opens a JDBC connection or reads from the file system.
     * It is the only correct method to call from the /upload-sql endpoint so
     * that the pipeline cannot time out waiting for a live PostgreSQL database.
     *
     * Pipeline applied:
     *   SqlSchemaResult → DatabaseSchema → SchemaEnrichmentService → EavDetectionService
     *
     * @param databaseName  logical name for the schema (from the request param)
     * @param parseResult   the validated + parsed SQL result
     * @return              enriched, EAV-detected DatabaseSchema
     */
    public DatabaseSchema buildSchemaFromSqlResult(
            String databaseName,
            SqlFileSchemaExtractorService.SqlSchemaResult parseResult) {

        log.info("Building DatabaseSchema from parsed SQL result — db='{}', tables={}",
                databaseName, parseResult.getTotalTables());

        List<DatabaseTable> tables = new ArrayList<>();

        for (SqlFileSchemaExtractorService.TableExtract t : parseResult.getTables()) {

            // ── Columns ───────────────────────────────────────────────────────
            List<SchemaColumn> schemaCols = new ArrayList<>();
            Set<String> pkSet = new HashSet<>(t.getPrimaryKeyColumns());

            for (SqlFileSchemaExtractorService.ColumnExtract c : t.getColumns()) {

                // Prefer COMMENT ON comment, then auto-generate description
                String desc = (c.getComment() != null && !c.getComment().isBlank())
                        ? c.getComment()
                        : c.getColumnName() + " (" + c.getDataType() + ")";

                schemaCols.add(SchemaColumn.builder()
                        .name(c.getColumnName())
                        .dataType(c.getDataType())
                        .description(desc)
                        .primaryKey(c.isPrimaryKey() || pkSet.contains(c.getColumnName()))
                        .foreignKey(c.isForeignKey())
                        .foreignKeyReference(c.getForeignKeyReference())
                        .nullable(c.isNullable())
                        .build());
            }

            // ── Table description ─────────────────────────────────────────────
            String tableDesc = (t.getComment() != null && !t.getComment().isBlank())
                    ? t.getComment()
                    : t.getTableName() + " table";

            tables.add(DatabaseTable.builder()
                    .tableName(t.getTableName())
                    .description(tableDesc)
                    .businessContext("")
                    .columns(schemaCols)
                    .build());

            log.debug("  SQL → table '{}' ({} columns, PKs={})",
                    t.getTableName(), schemaCols.size(), t.getPrimaryKeyColumns());
        }

        DatabaseSchema schema = DatabaseSchema.builder()
                .databaseName(databaseName)
                .databaseType("SQL_FILE")
                .description("Schema parsed from uploaded SQL file: "
                        + parseResult.getFileName())
                .tables(tables)
                .build();

        log.info("DatabaseSchema built from SQL result: '{}' — {} table(s)",
                databaseName, tables.size());

        // ── Merge user-authored descriptions and business_context from JSON ────
        // This is the same step the live JDBC path performs (extractSchema()).
        // Without it, any table/column descriptions AND business_context values
        // written via POST /api/v1/admin/metadata/add are never applied to the
        // SQL-file-sourced schema, so the LLM never sees the custom CONTEXT lines.
        mergeDescriptionsFromJson(schema);
        mergeEavConfigFromJson(schema);

        // Apply the same enrichment pipeline as the live JDBC path
        DatabaseSchema enriched = enrichmentService.enrich(schema);
        return eavDetectionService.detect(enriched);
    }


    // =========================================================================
    // Live schema extraction via DatabaseMetaData
    // =========================================================================

    private DatabaseSchema extractLiveSchema(DataSource ds, String dsName) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String dbProduct = meta.getDatabaseProductName();
            String catalog   = conn.getCatalog();
            String rawSchema = conn.getSchema();

            log.debug("Connected to '{}' (catalog={}, schema={})", dbProduct, catalog, rawSchema);

            // PostgreSQL: getTables() only works reliably with null catalog + explicit schema.
            boolean isPostgres = dbProduct != null && dbProduct.toLowerCase().contains("postgresql");
            String searchCatalog = isPostgres ? null : catalog;
            String searchSchema  = isPostgres ? "public"
                                   : (rawSchema != null ? rawSchema : null);

            log.debug("Schema search params — catalog={}, schema={}", searchCatalog, searchSchema);

            List<DatabaseTable> tables = new ArrayList<>();

            // Fetch all TABLE-type objects (excludes system views, sequences, etc.)
            try (ResultSet rs = meta.getTables(searchCatalog, searchSchema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String tableName    = rs.getString("TABLE_NAME");
                    String tableRemarks = rs.getString("REMARKS");

                    // Skip internal / system tables
                    if (isSystemTable(tableName)) continue;

                    List<DatabaseColumn> columns = extractColumns(meta, searchCatalog, searchSchema, tableName);
                    Set<String>         pkCols   = extractPrimaryKeys(meta, searchCatalog, searchSchema, tableName);
                    Map<String, String> fkMap    = extractForeignKeys(meta, searchCatalog, searchSchema, tableName);

                    List<SchemaColumn> schemaCols = new ArrayList<>();
                    for (DatabaseColumn dc : columns) {
                        schemaCols.add(SchemaColumn.builder()
                                .name(dc.name())
                                .dataType(dc.dataType())
                                .description(dc.remarks() != null && !dc.remarks().isBlank()
                                        ? dc.remarks() : dc.name() + " (" + dc.dataType() + ")")
                                .primaryKey(pkCols.contains(dc.name()))
                                .foreignKey(fkMap.containsKey(dc.name()))
                                .foreignKeyReference(fkMap.get(dc.name()))
                                .nullable(dc.nullable())
                                .build());
                    }

                    tables.add(DatabaseTable.builder()
                            .tableName(tableName)
                            .description(tableRemarks != null && !tableRemarks.isBlank()
                                    ? tableRemarks : tableName + " table")
                            .businessContext("")
                            .columns(schemaCols)
                            .build());

                    log.info("  → Table '{}' extracted ({} columns)", tableName, schemaCols.size());
                }
            }

            // Strip query params (?key=val&...) then extract the last path segment as DB name
            String dbName = isPostgres
                    ? meta.getURL()
                          .replaceAll("\\?.*$", "")          // drop ?connectTimeout=3&...
                          .replaceAll(".*/([\\w-]+)$", "$1") // keep last path segment
                    : (catalog != null ? catalog : dsName);

            return DatabaseSchema.builder()
                    .databaseName(dbName)
                    .databaseType(dbProduct)
                    .description("Live schema extracted from " + dsName)
                    .tables(tables)
                    .build();
        }
    }

    private List<DatabaseColumn> extractColumns(DatabaseMetaData meta,
                                                 String catalog, String schema,
                                                 String tableName) throws SQLException {
        List<DatabaseColumn> cols = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, tableName, "%")) {
            while (rs.next()) {
                cols.add(new DatabaseColumn(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getString("REMARKS"),
                        "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"))
                ));
            }
        }
        return cols;
    }

    private Set<String> extractPrimaryKeys(DatabaseMetaData meta,
                                            String catalog, String schema,
                                            String tableName) throws SQLException {
        Set<String> pks = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, tableName)) {
            while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
        }
        return pks;
    }

    private Map<String, String> extractForeignKeys(DatabaseMetaData meta,
                                                    String catalog, String schema,
                                                    String tableName) throws SQLException {
        Map<String, String> fks = new LinkedHashMap<>();
        try (ResultSet rs = meta.getImportedKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                String fkCol    = rs.getString("FKCOLUMN_NAME");
                String pkTable  = rs.getString("PKTABLE_NAME");
                String pkCol    = rs.getString("PKCOLUMN_NAME");
                fks.put(fkCol, pkTable + "." + pkCol);
            }
        }
        return fks;
    }

    /** Returns true for known system / internal table name patterns. */
    private boolean isSystemTable(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase();
        return lower.startsWith("pg_")
                || lower.startsWith("information_schema")
                || lower.startsWith("sys_")
                || lower.equals("dual")
                || lower.startsWith("flyway_")
                || lower.startsWith("schemacrawler");
    }

    // =========================================================================
    // Description merge — reads from {DBName}_Schema_1.0.0.json
    // =========================================================================

    /**
     * For each table in the live schema, copies the user-authored
     * {@code description} fields (table-level and column-level) from the
     * corresponding JSON file into the {@link DatabaseSchema} object.
     *
     * This ensures that descriptions added via POST /admin/metadata/add
     * are included in the chunks sent to the LLM — both at startup and
     * when /reindex is called from the UI.
     */
    @SuppressWarnings("unchecked")
    private void mergeDescriptionsFromJson(DatabaseSchema schema) {
        try {
            Optional<Map<String, Object>> jsonRoot =
                    schemaFileReaderService.getRawRoot(schema.getDatabaseName());
            if (jsonRoot.isEmpty()) {
                log.debug("No JSON schema file found for '{}' — skipping description merge",
                        schema.getDatabaseName());
                return;
            }

            Object tablesObj = jsonRoot.get().get("tables");
            if (!(tablesObj instanceof List)) return;

            // Build lookup: lower table_name → JSON table entry
            Map<String, Map<String, Object>> jsonTableMap = new LinkedHashMap<>();
            for (Object t : (List<?>) tablesObj) {
                if (t instanceof Map) {
                    Map<String, Object> tm = (Map<String, Object>) t;
                    String name = (String) tm.get("table_name");
                    if (name != null) jsonTableMap.put(name.toLowerCase(), tm);
                }
            }

            int tablesUpdated = 0;
            int colsUpdated   = 0;

            for (DatabaseTable table : schema.getTables()) {
                Map<String, Object> jsonTable =
                        jsonTableMap.get(table.getTableName().toLowerCase());
                if (jsonTable == null) continue;

                // ── Table-level description ────────────────────────────────────
                String jsonTableDesc = (String) jsonTable.get("description");
                if (jsonTableDesc != null && !jsonTableDesc.isBlank()) {
                    table.setDescription(jsonTableDesc);
                    tablesUpdated++;
                }

                // ── Table-level business_context ───────────────────────────────
                // This is what gets injected as CONTEXT : in the LLM prompt and
                // embedded into the chunk text via DatabaseTable.toEmbeddingText().
                // Without merging this field, user-authored context set via
                // POST /api/v1/admin/metadata/add is never seen by the LLM.
                String jsonBusinessCtx = (String) jsonTable.get("business_context");
                if (jsonBusinessCtx != null && !jsonBusinessCtx.isBlank()) {
                    table.setBusinessContext(jsonBusinessCtx);
                    log.debug("  business_context merged for table '{}': {}",
                            table.getTableName(), jsonBusinessCtx);
                }

                // ── Column-level descriptions ──────────────────────────────────
                Object columnsObj = jsonTable.get("columns");
                if (!(columnsObj instanceof List) || table.getColumns() == null) continue;

                // Build lookup: lower column_name → JSON column entry
                Map<String, Map<String, Object>> jsonColMap = new LinkedHashMap<>();
                for (Object c : (List<?>) columnsObj) {
                    if (c instanceof Map) {
                        Map<String, Object> cm = (Map<String, Object>) c;
                        String colName = (String) cm.get("column_name");
                        if (colName != null) jsonColMap.put(colName.toLowerCase(), cm);
                    }
                }

                for (SchemaColumn col : table.getColumns()) {
                    Map<String, Object> jsonCol = jsonColMap.get(col.getName().toLowerCase());
                    if (jsonCol == null) continue;
                    String jsonColDesc = (String) jsonCol.get("description");
                    if (jsonColDesc != null && !jsonColDesc.isBlank()) {
                        col.setDescription(jsonColDesc);
                        colsUpdated++;
                    }
                }
            }

            if (tablesUpdated > 0 || colsUpdated > 0) {
                log.info("Description merge from JSON complete — {} table(s), {} column(s) updated",
                        tablesUpdated, colsUpdated);
            } else {
                log.debug("Description merge: no descriptions found in JSON for '{}'",
                        schema.getDatabaseName());
            }
        } catch (Exception e) {
            log.warn("Description merge from JSON failed (non-fatal): {}", e.getMessage());
        }
    }

    // =========================================================================
    // EAV config merge — reads from {DBName}_Schema_1.0.0.json
    // =========================================================================

    /**
     * For each table in the live schema that has {@code is_eav_table: true}
     * in the corresponding JSON file, copies the flag and the full
     * {@code eav_config} block onto the {@link DatabaseTable} object.
     *
     * This allows users to author EAV configuration manually in the JSON
     * (via the /admin/metadata/add endpoint) and have it propagate into the
     * RAG pipeline without requiring a full schema re-extraction.
     */
    @SuppressWarnings("unchecked")
    private void mergeEavConfigFromJson(DatabaseSchema schema) {
        try {
            Optional<Map<String, Object>> jsonRoot =
                    schemaFileReaderService.getRawRoot(schema.getDatabaseName());
            if (jsonRoot.isEmpty()) return;

            Object tablesObj = jsonRoot.get().get("tables");
            if (!(tablesObj instanceof List)) return;

            // Build a lookup map: lower table_name → JSON table entry
            Map<String, Map<String, Object>> jsonTableMap = new LinkedHashMap<>();
            for (Object t : (List<?>) tablesObj) {
                if (t instanceof Map) {
                    Map<String, Object> tm = (Map<String, Object>) t;
                    String name = (String) tm.get("table_name");
                    if (name != null) jsonTableMap.put(name.toLowerCase(), tm);
                }
            }

            int merged = 0;
            for (DatabaseTable table : schema.getTables()) {
                Map<String, Object> jsonTable =
                        jsonTableMap.get(table.getTableName().toLowerCase());
                if (jsonTable == null) continue;

                // Copy is_eav_table flag
                Object isEav = jsonTable.get("is_eav_table");
                if (Boolean.TRUE.equals(isEav)) {
                    table.setEavTable(true);

                    // Parse eav_config block into EavConfig model
                    Object eavConfigObj = jsonTable.get("eav_config");
                    if (eavConfigObj instanceof Map) {
                        Map<String, Object> ecMap = (Map<String, Object>) eavConfigObj;
                        EavConfig eavConfig = new EavConfig();
                        eavConfig.setEntityIdColumn((String) ecMap.get("entity_id_column"));
                        eavConfig.setAttributeNameColumn((String) ecMap.get("attribute_name_column"));
                        eavConfig.setAttributeValueColumn((String) ecMap.get("attribute_value_column"));
                        eavConfig.setValueTypeColumn((String) ecMap.get("value_type_column"));
                        eavConfig.setEntityTable((String) ecMap.get("entity_table"));

                        // Parse known_attributes array
                        Object knownAttrsObj = ecMap.get("known_attributes");
                        if (knownAttrsObj instanceof List) {
                            List<EavKnownAttribute> knownAttrs = new ArrayList<>();
                            for (Object ka : (List<?>) knownAttrsObj) {
                                if (ka instanceof Map) {
                                    Map<String, Object> kaMap = (Map<String, Object>) ka;
                                    EavKnownAttribute attr = new EavKnownAttribute();
                                    attr.setAttributeKey((String) kaMap.get("attribute_key"));
                                    attr.setDataType((String) kaMap.get("data_type"));
                                    attr.setCastHint((String) kaMap.get("cast_hint"));
                                    attr.setDescription((String) kaMap.get("description"));
                                    Object syns = kaMap.get("synonyms");
                                    if (syns instanceof List) {
                                        List<String> synList = new ArrayList<>();
                                        for (Object s : (List<?>) syns) {
                                            if (s instanceof String) synList.add((String) s);
                                        }
                                        attr.setSynonyms(synList);
                                    }
                                    knownAttrs.add(attr);
                                }
                            }
                            eavConfig.setKnownAttributes(knownAttrs);
                        }
                        table.setEavConfig(eavConfig);
                    }
                    merged++;
                    log.info("  EAV config merged from JSON for table '{}'", table.getTableName());
                }
            }
            if (merged > 0) {
                log.info("EAV config merge complete — {} table(s) configured from JSON", merged);
            }
        } catch (Exception e) {
            log.warn("EAV config merge from JSON failed (non-fatal): {}", e.getMessage());
        }
    }

    // ── Tiny record to hold raw column metadata ────────────────────────────
    private record DatabaseColumn(String name, String dataType, String remarks, boolean nullable) {}
}

