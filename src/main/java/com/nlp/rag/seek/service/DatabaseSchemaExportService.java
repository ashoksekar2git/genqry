package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nlp.rag.seek.model.DatabaseConnectRequest;
import com.nlp.rag.seek.model.DatabaseTable;
import com.nlp.rag.seek.model.SchemaColumn;
import com.nlp.rag.seek.model.EavConfig;
import com.nlp.rag.seek.model.EavKnownAttribute;
import com.nlp.rag.seek.service.SqlFileSchemaExtractorService.SqlSchemaResult;
import com.nlp.rag.seek.service.SqlFileSchemaExtractorService.TableExtract;
import com.nlp.rag.seek.service.SqlFileSchemaExtractorService.ColumnExtract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Connects to any user-supplied database, extracts the full schema and
 * writes it to a versioned JSON file with timestamp.
 *
 * Filename format: {DBName}_schema_{yyyyMMdd_HHmmss}.json
 *   e.g.  ecommerce_schema_20260307_153045.json
 *
 * Each write produces one file in the user's directory:
 *   supportingFiles/{userName}/{DBName}_schema_{timestamp}.json
 *
 * Target directory is resolved via {@link MetadataDirectoryResolver}:
 *  • seekUserName in the request → supportingFiles/{seekUserName}/
 *  • no seekUserName             → use the shared default metadata dir.
 */
@Service
public class DatabaseSchemaExportService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaExportService.class);

    /** Timestamp formatter: yyyyMMdd_HHmmss */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    /** Prefix before the timestamp, e.g. ecommerce_schema_20260307_153045.json */
    private static final String SCHEMA_PREFIX = "_schema_";

    @Autowired
    private MetadataDirectoryResolver dirResolver;

    @Autowired(required = false)
    private EavDetectionService eavDetectionService;

    private final ObjectMapper objectMapper;

    public DatabaseSchemaExportService() {
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Generates a timestamp-based schema filename for the given database.
     * e.g. ecommerce_schema_20260307_153045.json
     */
    public static String buildSchemaFilename(String databaseName, Instant ts) {
        return databaseName.trim() + SCHEMA_PREFIX + TS_FMT.format(ts) + ".json";
    }

    /**
     * 1. Opens a short-lived JDBC connection with supplied credentials
     * 2. Extracts full schema (tables → columns → PKs → FKs → comments)
     * 3. Writes {DBName}_schema_{timestamp}.json to:
     *      • supportingFiles/{seekUserName}/  — when seekUserName is present
     *      • default metadata directory       — otherwise
     * 4. Returns ordered list of table names found
     */
    public List<String> connectExtractAndSave(DatabaseConnectRequest req)
            throws SQLException, IOException {

        validateRequest(req);

        // ── Resolve where to write the JSON file ──────────────────────────────
        // Always write to supportingFiles/{user}/ — never to metadata/
        Path targetDir;
        if (req.hasSeekUserName()) {
            targetDir = dirResolver.resolveUserDir(req.getSeekUserName());
            log.info("seekUserName='{}' — schema JSON will be written to: {}",
                    req.getSeekUserName(), targetDir);
        } else {
            targetDir = dirResolver.resolveUserDir("root");
            log.info("No seekUserName in request — using root user dir: {}", targetDir);
        }

        log.info("Connecting to {} as '{}'", req.toJdbcUrl(), req.getUsername());

        try (Connection conn = openConnection(req)) {
            DatabaseMetaData meta = conn.getMetaData();

            boolean isPostgres = isPostgres(meta);
            String  catalog    = isPostgres ? null     : conn.getCatalog();
            String  schemaName = isPostgres ? "public" : conn.getSchema();

            log.info("Connected to '{}' ({}). Extracting schema '{}'",
                    req.getDatabaseName(), meta.getDatabaseProductName(), schemaName);

            // ── Generate a single timestamp for this extraction ────────────────
            Instant now = Instant.now();
            String fileName = buildSchemaFilename(req.getDatabaseName(), now);

            // ── Load existing JSON to preserve user-managed EAV config ─────────
            Map<String, Map<String, Object>> existingTables =
                    loadExistingTableMap(req.getDatabaseName(), targetDir);

            // ── Extract all user tables ────────────────────────────────────────
            List<Map<String, Object>> tables =
                    extractTables(meta, catalog, schemaName, conn, existingTables);

            log.info("Extracted {} tables from '{}'", tables.size(), req.getDatabaseName());

            // ── Build root payload ─────────────────────────────────────────────
            Map<String, Object> payload = buildPayload(req, meta, schemaName, tables, now);

            // ── Write JSON file to the resolved target directory ───────────────
            Path file = writeFileWithName(fileName, payload, targetDir);
            log.info("Schema written → {}", file.toAbsolutePath());


            // ── Register the filename so readers can locate it ────────────────
            dirResolver.registerLatestSchemaFilename(req.getDatabaseName(), fileName);

            List<String> tableNames = new ArrayList<>();
            for (Map<String, Object> t : tables) tableNames.add((String) t.get("table_name"));
            return tableNames;
        }
    }

    /**
     * Connects to the database identified by {@code jdbcUrl} / {@code username} /
     * {@code password}, extracts the full schema and writes it to the FIXED path
     * {@code destFile}.
     *
     * Used exclusively by the startup bootstrap to create
     * supportingFiles/root/root_ecommerce_dbschema.json when it does not yet exist.
     *
     * Performs EAV table detection DURING extraction for early classification.
     *
     * @param databaseName  logical name (ecommerce)
     * @param jdbcUrl       full JDBC URL
     * @param username      DB username
     * @param password      DB password
     * @param destFile      absolute path where the JSON will be written
     * @return              number of tables extracted
     */
    public int extractAndWriteRootSchema(String databaseName,
                                         String jdbcUrl,
                                         String username,
                                         String password,
                                         Path   destFile) throws SQLException, IOException {

        log.info("Root schema bootstrap — connecting to '{}' as '{}'", jdbcUrl, username);

        Properties props = new Properties();
        props.setProperty("user",         username != null ? username : "");
        props.setProperty("password",     password != null ? password : "");
        props.setProperty("loginTimeout", "5");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            DatabaseMetaData meta = conn.getMetaData();

            boolean isPostgres = isPostgres(meta);
            String  catalog    = isPostgres ? null     : conn.getCatalog();
            String  schemaName = isPostgres ? "public" : conn.getSchema();

            log.info("Connected to '{}' ({}). Extracting schema '{}'",
                    databaseName, meta.getDatabaseProductName(), schemaName);

            Instant now = Instant.now();

            // Extract tables WITH EAV detection during the extraction process
            List<Map<String, Object>> tables =
                    extractTables(meta, catalog, schemaName, conn, Collections.emptyMap());

            log.info("Root schema bootstrap — extracted {} tables from '{}'",
                    tables.size(), databaseName);

            // Build payload
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("database_name",        databaseName);
            payload.put("database_type",        "PostgreSQL");
            payload.put("schema_version",       "1.0.0");
            payload.put("jdbc_product_name",    meta.getDatabaseProductName());
            payload.put("jdbc_product_version", meta.getDatabaseProductVersion());
            payload.put("extracted_at",         now.toString());
            payload.put("total_tables",         tables.size());
            payload.put("schema_name",          schemaName != null ? schemaName : "public");
            payload.put("tables",               tables);

            // Write to the fixed destination
            Files.createDirectories(destFile.getParent());
            objectMapper.writeValue(destFile.toFile(), payload);
            log.info("Root ecommerce schema written → {}", destFile.toAbsolutePath());

            return tables.size();
        }
    }

    /**
     * Builds the standard {databaseName}_schema_{timestamp}.json from a parsed SQL file
     * and writes it to supportingFiles/{userName}/ (or the default metadata directory
     * when {@code userName} is null/blank).
     *
     * Registers the resolved directory in MetadataDirectoryResolver.
     *
     * @param databaseName  logical DB name (used in filename and JSON)
     * @param parseResult   result from SqlFileSchemaExtractorService.extractSchema()
     * @param userName      the genQry application userName; null → default metadata dir
     * @return              absolute path of the written JSON file
     */
    public String buildAndWriteSchemaFromSql(String databaseName,
                                              SqlSchemaResult parseResult,
                                              String userName) throws IOException {
        // ── Resolve + create target directory ─────────────────────────────────
        // Always write to supportingFiles/{user}/ — never to metadata/
        Path targetDir;
        if (userName != null && !userName.isBlank()) {
            targetDir = dirResolver.resolveUserDir(userName);
            log.info("Schema from SQL file will be written to user dir: {}", targetDir);
        } else {
            targetDir = dirResolver.resolveUserDir("root");
            log.info("No userName supplied — using root user dir: {}", targetDir);
        }

        // ── Generate timestamp-based filename ─────────────────────────────────
        Instant now = Instant.now();
        String fileName = buildSchemaFilename(databaseName, now);

        // ── Build the tables array matching the standard JSON structure ────────
        List<Map<String, Object>> tables = new ArrayList<>();
        for (TableExtract t : parseResult.getTables()) {
            // Columns
            List<Map<String, Object>> cols = new ArrayList<>();
            for (ColumnExtract c : t.getColumns()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("column_name",    c.getColumnName());
                col.put("data_type",      c.getDataType());
                col.put("column_comment", c.getComment() != null ? c.getComment() : "");
                col.put("nullable",       c.isNullable());
                col.put("default_value",  c.getDefaultValue());
                col.put("auto_increment", c.isAutoIncrement());
                col.put("primary_key",    c.isPrimaryKey());
                col.put("foreign_key",    c.getForeignKeyReference());
                col.put("description",    "");
                col.put("values",         new ArrayList<>());
                cols.add(col);
            }

            List<String> pkList = t.getPrimaryKeyColumns();

            Map<String, Object> tableMap = new LinkedHashMap<>();
            tableMap.put("table_name",       t.getTableName());
            tableMap.put("description",      t.getComment() != null ? t.getComment() : "");
            tableMap.put("business_context", "");
            tableMap.put("primary_key",      pkList);
            tableMap.put("columns",          cols);

            // Perform EAV detection during extraction
            boolean isEav = performEavDetectionDuringExtraction(
                    t.getTableName(),
                    t.getComment(),
                    cols
            );
            tableMap.put("is_eav_table",     isEav);
            tables.add(tableMap);
        }

        // ── Build root payload (same structure as connectExtractAndSave) ───────
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("database_name",    databaseName);
        payload.put("database_type",    "SQL_FILE");
        payload.put("schema_version",   "1.0.0");
        payload.put("source_file",      parseResult.getFileName());
        payload.put("extracted_at",     now.toString());
        payload.put("total_tables",     tables.size());
        payload.put("tables",           tables);

        // ── Write JSON file ────────────────────────────────────────────────────
        Path outFile = writeFileWithName(fileName, payload, targetDir);
        log.info("Schema JSON from SQL file written → {}", outFile.toAbsolutePath());


        // ── Register the filename so readers can locate it ────────────────────
        dirResolver.registerLatestSchemaFilename(databaseName, fileName);

        return outFile.toAbsolutePath().toString();
    }

    /**
     * Returns the absolute path of the schema file for the given database.
     * Searches user subdirectories under supportingFiles/ first, then
     * root supportingFiles/, then the default metadata directory as fallback.
     */
    public String getSchemaFilePath(String databaseName) {
        String filename = dirResolver.getLatestSchemaFilename(databaseName);

        // 1. If we know the exact filename, search for it in user subdirs
        if (filename != null) {
            Path supportDir = dirResolver.getSupportingFilesDir();
            // Search user subdirectories
            try (var dirs = java.nio.file.Files.list(supportDir)) {
                var match = dirs.filter(java.nio.file.Files::isDirectory)
                        .map(d -> d.resolve(filename))
                        .filter(java.nio.file.Files::exists)
                        .findFirst();
                if (match.isPresent()) return match.get().toAbsolutePath().toString();
            } catch (Exception ignored) {}
            // Root supportingFiles
            Path rootPath = supportDir.resolve(filename);
            if (java.nio.file.Files.exists(rootPath)) return rootPath.toAbsolutePath().toString();
        }

        // 2. Glob search in user subdirs
        Path supportDir = dirResolver.getSupportingFilesDir();
        try (var dirs = java.nio.file.Files.list(supportDir)) {
            var subdirs = dirs.filter(java.nio.file.Files::isDirectory)
                    .collect(java.util.stream.Collectors.toList());
            for (Path sub : subdirs) {
                Path found = findLatestSchemaFile(databaseName, sub);
                if (found != null) return found.toAbsolutePath().toString();
            }
        } catch (Exception ignored) {}

        // 3. Fallback: default metadata dir
        Path dir = dirResolver.getDefaultDir();
        Path found = findLatestSchemaFile(databaseName, dir);
        return found != null ? found.toAbsolutePath().toString()
                : dir.resolve(databaseName.trim() + "_schema_.json").toAbsolutePath().toString();
    }

    /**
     * Returns the absolute path of the schema file for the given database
     * written to an explicitly supplied target directory.
     */
    public String getSchemaFilePath(String databaseName, Path targetDir) {
        String filename = dirResolver.getLatestSchemaFilename(databaseName);
        if (filename != null) {
            return targetDir.resolve(filename).toAbsolutePath().toString();
        }
        Path found = findLatestSchemaFile(databaseName, targetDir);
        return found != null ? found.toAbsolutePath().toString()
                : targetDir.resolve(databaseName.trim() + "_schema_.json").toAbsolutePath().toString();
    }

    // =========================================================================
    // JDBC connection
    // =========================================================================

    private Connection openConnection(DatabaseConnectRequest req) throws SQLException {
        try {
            Class.forName(req.toDriverClassName());
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver not found: " + req.toDriverClassName(), e);
        }

        String jdbcUrl = req.toJdbcUrl();
        Properties props = new Properties();
        props.setProperty("user",     req.getUsername());
        props.setProperty("password", req.getPassword());

        String dbType = req.getDatabaseType() != null ? req.getDatabaseType().toLowerCase() : "";
        if (dbType.contains("mysql")) {
            // MySQL connection timeout via property (milliseconds)
            props.setProperty("connectTimeout", "5000");
            props.setProperty("socketTimeout",  "30000");
        } else {
            // PostgreSQL uses loginTimeout (in seconds)
            props.setProperty("loginTimeout", "5");
        }

        log.info("Opening JDBC connection: url='{}' user='{}'", jdbcUrl, req.getUsername());
        return DriverManager.getConnection(jdbcUrl, props);
    }

    // =========================================================================
    // Schema extraction — performs EAV detection during extraction process
    // Checks: table name patterns, description keywords, column names for EAV signals
    // =========================================================================

    private List<Map<String, Object>> extractTables(DatabaseMetaData meta,
                                                      String catalog,
                                                      String schemaFilter,
                                                      Connection conn,
                                                      Map<String, Map<String, Object>> existingTables)
            throws SQLException {
        List<Map<String, Object>> tables = new ArrayList<>();

        try (ResultSet rs = meta.getTables(catalog, schemaFilter, "%",
                new String[]{"TABLE"})) {

            while (rs.next()) {
                String tableName    = rs.getString("TABLE_NAME");
                String tableComment = rs.getString("REMARKS");

                if (isSystemTable(tableName)) continue;

                List<String> pkList = extractPrimaryKeyList(meta, catalog, schemaFilter, tableName);
                Set<String>  pkSet  = new LinkedHashSet<>(pkList);
                Map<String, String> fkMap = extractForeignKeys(meta, catalog, schemaFilter, tableName);
                List<Map<String, Object>> columns = extractColumns(
                        meta, catalog, schemaFilter, tableName, pkSet, fkMap, conn);

                Map<String, Object> tableMap = new LinkedHashMap<>();
                tableMap.put("table_name",       tableName);
                tableMap.put("description",      nvl(tableComment));
                tableMap.put("business_context", "");
                tableMap.put("primary_key",      pkList);
                tableMap.put("columns",          columns);

                // ── EAV detection during extraction ────────────────────────────────
                boolean isEavTable = performEavDetectionDuringExtraction(tableName, tableComment, columns);
                tableMap.put("is_eav_table",     isEavTable);

                if (existingTables.containsKey(tableName.toLowerCase())) {
                    Map<String, Object> existing = existingTables.get(tableName.toLowerCase());
                    // Preserve existing business_context if user already authored one
                    Object existingCtx = existing.get("business_context");
                    if (existingCtx instanceof String && !((String) existingCtx).isBlank()) {
                        tableMap.put("business_context", existingCtx);
                    }
                    if (Boolean.TRUE.equals(existing.get("is_eav_table"))) {
                        tableMap.put("is_eav_table", true);
                        if (existing.containsKey("eav_config")) {
                            tableMap.put("eav_config", existing.get("eav_config"));
                        }
                    }
                }

                tables.add(tableMap);
                String eavStatus = isEavTable ? "[EAV]" : "[Entity]";
                log.debug("  Table '{}' {} — {} cols, PKs={}, FKs={}",
                        tableName, eavStatus, columns.size(), pkList, fkMap.keySet());
            }
        }
        return tables;
    }

    private List<Map<String, Object>> extractColumns(DatabaseMetaData meta,
                                                      String catalog,
                                                      String schema,
                                                      String tableName,
                                                      Set<String> pkSet,
                                                      Map<String, String> fkMap,
                                                      Connection conn) throws SQLException {
        List<Map<String, Object>> cols = new ArrayList<>();
        Map<String, String> pgComments = fetchPgColumnComments(conn, tableName);

        try (ResultSet rs = meta.getColumns(catalog, schema, tableName, "%")) {
            while (rs.next()) {
                String colName   = rs.getString("COLUMN_NAME");
                String typeName  = resolveDataTypeName(rs);
                String nullable  = rs.getString("IS_NULLABLE");
                String defVal    = rs.getString("COLUMN_DEF");
                String remarks   = rs.getString("REMARKS");
                String autoInc   = rs.getString("IS_AUTOINCREMENT");

                String comment = pgComments.getOrDefault(colName,
                        remarks != null && !remarks.isBlank() ? remarks : colName + " field");

                String fkRef = fkMap.get(colName);

                Map<String, Object> col = new LinkedHashMap<>();
                col.put("column_name",    colName);
                col.put("data_type",      typeName);
                col.put("column_comment", comment);
                col.put("nullable",       "YES".equalsIgnoreCase(nullable));
                col.put("default_value",  defVal);
                col.put("auto_increment", "YES".equalsIgnoreCase(autoInc));
                col.put("primary_key",    pkSet.contains(colName));
                col.put("foreign_key",    fkRef);
                col.put("description",    "");
                col.put("values",         new ArrayList<>());

                cols.add(col);
            }
        }
        return cols;
    }

    private String resolveDataTypeName(ResultSet rs) throws SQLException {
        String typeName = rs.getString("TYPE_NAME");
        if (typeName == null) return "unknown";
        switch (typeName.toLowerCase()) {
            case "int2":         return "smallint";
            case "int4":         return "integer";
            case "int8":         return "bigint";
            case "float4":       return "real";
            case "float8":       return "double precision";
            case "bool":         return "boolean";
            case "bpchar":       return "character";
            case "varchar":      return "character varying";
            case "timestamptz":  return "timestamp with time zone";
            case "timetz":       return "time with time zone";
            default:             return typeName;
        }
    }

    private Map<String, String> fetchPgColumnComments(Connection conn, String tableName) {
        Map<String, String> comments = new LinkedHashMap<>();
        String sql =
            "SELECT a.attname AS column_name, " +
            "       d.description AS comment " +
            "FROM   pg_class c " +
            "JOIN   pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 " +
            "LEFT JOIN pg_description d ON d.objoid = c.oid AND d.objsubid = a.attnum " +
            "WHERE  c.relname = ? " +
            "  AND  c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col     = rs.getString("column_name");
                    String comment = rs.getString("comment");
                    if (comment != null && !comment.isBlank()) {
                        comments.put(col, comment);
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("pg_catalog column comment query skipped for '{}': {}", tableName, e.getMessage());
        }
        return comments;
    }

    private String fetchPgTableComment(Connection conn, String tableName) {
        String sql =
            "SELECT obj_description(c.oid) AS comment " +
            "FROM   pg_class c " +
            "JOIN   pg_namespace n ON n.oid = c.relnamespace " +
            "WHERE  c.relname = ? AND n.nspname = 'public'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String comment = rs.getString("comment");
                    return comment != null ? comment : "";
                }
            }
        } catch (SQLException e) {
            log.debug("pg_catalog table comment query skipped for '{}': {}", tableName, e.getMessage());
        }
        return "";
    }

    private List<String> extractPrimaryKeyList(DatabaseMetaData meta,
                                                String catalog, String schema,
                                                String tableName) throws SQLException {
        Map<Short, String> ordered = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private Map<String, String> extractForeignKeys(DatabaseMetaData meta,
                                                    String catalog, String schema,
                                                    String tableName) throws SQLException {
        Map<String, String> fks = new LinkedHashMap<>();
        try (ResultSet rs = meta.getImportedKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                fks.put(rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_NAME") + "." + rs.getString("PKCOLUMN_NAME"));
            }
        }
        return fks;
    }

    // =========================================================================
    // Payload builder
    // =========================================================================

    private Map<String, Object> buildPayload(DatabaseConnectRequest req,
                                              DatabaseMetaData meta,
                                              String schemaName,
                                              List<Map<String, Object>> tables,
                                              Instant now)
            throws SQLException {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("database_name",          req.getDatabaseName());
        payload.put("database_type",          req.getDatabaseType());
        payload.put("host",                   req.getHost());
        payload.put("port",                   req.getPort());
        payload.put("schema_version",         "1.0.0");
        payload.put("jdbc_product_name",      meta.getDatabaseProductName());
        payload.put("jdbc_product_version",   meta.getDatabaseProductVersion());
        payload.put("extracted_at",           now.toString());
        payload.put("total_tables",           tables.size());
        payload.put("schema_name",            schemaName);
        payload.put("tables",                 tables);
        return payload;
    }

    // =========================================================================
    // EAV Detection during extraction
    // =========================================================================

    /**
     * Performs comprehensive EAV detection during the extraction phase.
     * Checks:
     * 1. Table/column name patterns (e.g., *_attributes, *_properties)
     * 2. Table description keywords (eav, attribute, metadata, vertical, key-value)
     * 3. Column name patterns (attribute_id, attribute_name, attribute_value, etc.)
     * 4. Column count (low column count ≤ 8 is EAV indicator)
     * 5. Foreign key presence (EAV tables typically have entity FKs)
     *
     * Returns true if 3+ signals detect EAV table, false otherwise.
     */
    private boolean performEavDetectionDuringExtraction(String tableName,
                                                        String tableDescription,
                                                        List<Map<String, Object>> columns) {
        int signals = 0;

        String tableNameLower = tableName.toLowerCase();
        String descLower = tableDescription != null ? tableDescription.toLowerCase() : "";

        // Signal 1: Check table name pattern for EAV indicators
        if (isEavTableNamePattern(tableNameLower)) {
            signals++;
            log.debug("  [Signal 1] Table '{}' matches EAV name pattern", tableName);
        }

        // Signal 2: Check table description for EAV keywords
        if (containsEavKeywords(descLower)) {
            signals++;
            log.debug("  [Signal 2] Table '{}' description contains EAV keywords", tableName);
        }

        // Signal 3: Check for low column count (EAV tables typically have 3-8 columns)
        if (columns != null && columns.size() <= 8 && columns.size() >= 3) {
            signals++;
            log.debug("  [Signal 3] Table '{}' has low column count ({})", tableName, columns.size());
        }

        // Signal 4 & 5: Check column names for EAV role patterns
        int columnSignals = countEavColumnSignals(columns);
        if (columnSignals >= 2) {
            signals += columnSignals;
            log.debug("  [Signal 4-5] Table '{}' detected {} EAV column patterns", tableName, columnSignals);
        }

        boolean isEav = signals >= 3;
        if (isEav) {
            log.info("✓ Table '{}' classified as EAV (signals: {}/5)", tableName, signals);
        }
        return isEav;
    }

    /**
     * Checks if table name matches common EAV table naming patterns:
     * *_attributes, *_properties, *_metadata, *_params, key_value, *_settings, etc.
     */
    private boolean isEavTableNamePattern(String tableNameLower) {
        return tableNameLower.contains("attribute") ||
               tableNameLower.contains("properties") ||
               tableNameLower.contains("metadata") ||
               tableNameLower.contains("param") ||
               tableNameLower.contains("key_value") ||
               tableNameLower.contains("kv_store") ||
               tableNameLower.contains("setting") ||
               tableNameLower.contains("custom_field") ||
               tableNameLower.contains("eav") ||
               tableNameLower.contains("vertical") ||
               tableNameLower.endsWith("_config") ||
               tableNameLower.endsWith("_props");
    }

    /**
     * Checks if table description contains EAV/metadata keywords
     */
    private boolean containsEavKeywords(String descLower) {
        return descLower.contains("eav") ||
               descLower.contains("entity-attribute-value") ||
               descLower.contains("key-value") ||
               descLower.contains("key value") ||
               descLower.contains("vertical table") ||
               descLower.contains("dynamic properties") ||
               descLower.contains("flexible schema") ||
               descLower.contains("extension table") ||
               (descLower.contains("metadata") && descLower.length() < 200);
    }

    /**
     * Counts how many EAV column patterns are found in the table.
     * Looks for: entity_id/entity_key, attribute_name/attribute_code/attribute_id,
     *            attribute_value/value/val/unit, data_type/value_type
     */
    private int countEavColumnSignals(List<Map<String, Object>> columns) {
        if (columns == null || columns.isEmpty()) return 0;

        int signals = 0;
        boolean hasEntityId = false;
        boolean hasAttributeName = false;
        boolean hasAttributeValue = false;
        boolean hasValueType = false;

        for (Map<String, Object> col : columns) {
            Object colNameObj = col.get("column_name");
            if (colNameObj == null) continue;

            String colNameLower = colNameObj.toString().toLowerCase();

            // Entity ID column patterns
            if (!hasEntityId && isEntityIdColumn(colNameLower)) {
                hasEntityId = true;
            }

            // Attribute name/code column patterns
            if (!hasAttributeName && isAttributeNameColumn(colNameLower)) {
                hasAttributeName = true;
            }

            // Attribute value column patterns
            if (!hasAttributeValue && isAttributeValueColumn(colNameLower)) {
                hasAttributeValue = true;
            }

            // Value type column patterns
            if (!hasValueType && isValueTypeColumn(colNameLower)) {
                hasValueType = true;
            }
        }

        if (hasEntityId) signals++;
        if (hasAttributeName) signals++;
        if (hasAttributeValue) signals++;
        if (hasValueType) signals++;

        return signals;
    }

    private boolean isEntityIdColumn(String colNameLower) {
        return colNameLower.contains("entity_id") ||
               colNameLower.contains("entityid") ||
               colNameLower.contains("entity_key") ||
               colNameLower.contains("owner_id") ||
               colNameLower.contains("object_id") ||
               colNameLower.contains("record_id") ||
               colNameLower.contains("parent_id") ||
               colNameLower.contains("attribute_id") ||
               colNameLower.contains("attributeid");
    }

    private boolean isAttributeNameColumn(String colNameLower) {
        return colNameLower.contains("attribute_name") ||
               colNameLower.contains("attributename") ||
               colNameLower.contains("attribute_code") ||
               colNameLower.contains("attributecode") ||
               colNameLower.contains("attr_name") ||
               colNameLower.contains("attr_code") ||
               colNameLower.contains("attributes") ||
               colNameLower.contains("property_name") ||
               colNameLower.contains("key") ||
               colNameLower.contains("param_name");
    }

    private boolean isAttributeValueColumn(String colNameLower) {
        return colNameLower.contains("attribute_value") ||
               colNameLower.contains("attributevalue") ||
               colNameLower.contains("value") ||
               colNameLower.contains("val") ||
               colNameLower.contains("unit") ||
               colNameLower.contains("values") ||
               colNameLower.contains("data") ||
               colNameLower.contains("content") ||
               colNameLower.contains("amount") ||
               colNameLower.contains("result");
    }

    private boolean isValueTypeColumn(String colNameLower) {
        return colNameLower.contains("value_type") ||
               colNameLower.contains("valuetype") ||
               colNameLower.contains("data_type") ||
               colNameLower.contains("datatype") ||
               colNameLower.contains("dtype") ||
               colNameLower.contains("type");
    }

    /**
     * Converts an EavConfig object to a Map for JSON serialization.
     */
    private Map<String, Object> convertEavConfigToMap(EavConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("entity_id_column", config.getEntityIdColumn());
        map.put("attribute_name_column", config.getAttributeNameColumn());
        map.put("attribute_value_column", config.getAttributeValueColumn());
        map.put("value_type_column", config.getValueTypeColumn());
        map.put("entity_table", config.getEntityTable());

        List<Map<String, Object>> knownAttrs = new ArrayList<>();
        if (config.getKnownAttributes() != null) {
            for (EavKnownAttribute attr : config.getKnownAttributes()) {
                Map<String, Object> attrMap = new LinkedHashMap<>();
                attrMap.put("attribute_key", attr.getAttributeKey());
                attrMap.put("data_type", attr.getDataType());
                attrMap.put("description", attr.getDescription());
                attrMap.put("cast_hint", attr.getCastHint());
                knownAttrs.add(attrMap);
            }
        }
        map.put("known_attributes", knownAttrs);
        return map;
    }

    // =========================================================================
    // File writer
    // =========================================================================

    /**
     * Writes the payload to {fileName} inside {targetDir}.
     */
    private Path writeFileWithName(String fileName, Map<String, Object> payload,
                                    Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(fileName);
        objectMapper.writeValue(target.toFile(), payload);
        return target;
    }


    // =========================================================================
    // Helpers
    // =========================================================================

    private void validateRequest(DatabaseConnectRequest req) {
        if (req.getDatabaseName() == null || req.getDatabaseName().isBlank())
            throw new IllegalArgumentException("databaseName is required");
        if (req.getHost() == null || req.getHost().isBlank())
            throw new IllegalArgumentException("host is required");
        if (req.getPort() <= 0)
            throw new IllegalArgumentException("port must be a positive integer");
        if (req.getUsername() == null || req.getUsername().isBlank())
            throw new IllegalArgumentException("username is required");
    }

    private boolean isPostgres(DatabaseMetaData meta) throws SQLException {
        String p = meta.getDatabaseProductName();
        return p != null && p.toLowerCase().contains("postgresql");
    }

    private boolean isSystemTable(String name) {
        if (name == null) return true;
        String l = name.toLowerCase();
        return l.startsWith("pg_") || l.startsWith("information_schema")
                || l.startsWith("sys_") || l.equals("dual")
                || l.startsWith("flyway_") || l.startsWith("schemacrawler");
    }

    private String nvl(String s) { return s != null ? s : ""; }

    /**
     * Searches the given directory for schema files matching the pattern
     * {databaseName}_schema_*.json and returns the most recently modified one.
     */
    private Path findLatestSchemaFile(String databaseName, Path dir) {
        if (!Files.exists(dir)) return null;
        String prefix = databaseName.trim().toLowerCase() + SCHEMA_PREFIX;
        try {
            return Files.list(dir)
                    .filter(p -> p.getFileName().toString().toLowerCase().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparing(p -> {
                        try { return Files.getLastModifiedTime(p); }
                        catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                    }))
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Error scanning dir '{}' for schema files: {}", dir, e.getMessage());
            return null;
        }
    }

    /**
     * Loads the existing schema JSON file for a database (if it exists) and
     * returns a map from lower-case table name → table entry.
     * Used to preserve user-managed EAV config across re-extractions.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadExistingTableMap(String databaseName,
                                                                   Path targetDir) {
        // Try the most recent file in targetDir first, then supportingFiles dir
        Path file = findLatestSchemaFile(databaseName, targetDir);
        if (file == null) {
            file = findLatestSchemaFile(databaseName, dirResolver.getSupportingFilesDir());
        }
        if (file == null || !Files.exists(file)) return Collections.emptyMap();
        try {
            Map<String, Object> root = objectMapper.readValue(file.toFile(), Map.class);
            Object tablesObj = root.get("tables");
            if (!(tablesObj instanceof List)) return Collections.emptyMap();
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (Object t : (List<?>) tablesObj) {
                if (t instanceof Map) {
                    Map<String, Object> tableMap = (Map<String, Object>) t;
                    Object name = tableMap.get("table_name");
                    if (name instanceof String) {
                        result.put(((String) name).toLowerCase(), tableMap);
                    }
                }
            }
            log.debug("Loaded {} existing table entries from '{}' for EAV config preservation",
                    result.size(), file.getFileName());
            return result;
        } catch (Exception e) {
            log.warn("Could not load existing schema file for EAV config merge: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
