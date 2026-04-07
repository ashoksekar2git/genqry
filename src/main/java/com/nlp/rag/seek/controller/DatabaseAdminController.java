package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.model.DatabaseConnectRequest;
import com.nlp.rag.seek.service.AbbreviatedSchemaDetectionService;
import com.nlp.rag.seek.service.DatabaseSchemaExportService;
import com.nlp.rag.seek.service.DynamicDataSourceRegistry;
import com.nlp.rag.seek.service.EcommerceJdbcService;
import com.nlp.rag.seek.service.MetadataDirectoryResolver;
import com.nlp.rag.seek.service.RAGInitializationService;
import com.nlp.rag.seek.service.SchemaExtractionService;
import com.nlp.rag.seek.service.SQLExecutionService;
import com.nlp.rag.seek.service.SqlFileSchemaExtractorService;
import com.nlp.rag.seek.service.SqlFileSchemaExtractorService.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Paths;
import java.util.*;

/**
 * REST controller for on-demand database connection and schema export.
 *
 * Base path: /api/v1/admin/database
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │ POST /connect   Connect to a DB, extract schema, write JSON file     │
 * └──────────────────────────────────────────────────────────────────────┘
 */
@RestController
@RequestMapping("/api/v1/admin/database")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Database Admin", description = "Database connection, SQL file upload, schema export")
public class DatabaseAdminController {

    private static final Logger log = LoggerFactory.getLogger(DatabaseAdminController.class);

    @Autowired
    private DatabaseSchemaExportService schemaExportService;

    @Autowired
    private RAGInitializationService ragInitializationService;

    @Autowired
    private SqlFileSchemaExtractorService sqlExtractorService;

    @Autowired
    private SchemaExtractionService schemaExtractionService;

    @Autowired
    private SQLExecutionService sqlExecutionService;

    @Autowired
    private MetadataDirectoryResolver dirResolver;

    @Autowired
    private EcommerceJdbcService ecommerceJdbcService;

    @Autowired
    private DynamicDataSourceRegistry dynamicDataSourceRegistry;

    @Autowired
    private AbbreviatedSchemaDetectionService abbreviatedSchemaDetectionService;

    // Reads the default ecommerce DB config from application.properties
    @Value("${spring.datasource.secondary.url:jdbc:postgresql://localhost:5432/ecommerce}")
    private String primaryUrl;

    @Value("${spring.datasource.primary.username:postgres}")
    private String primaryUsername;

    @Value("${spring.datasource.primary.password:root}")
    private String primaryPassword;

    @Value("${seek.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    // =========================================================================
    // GET /ecommerce/tables
    // Returns table list from the root ecommerce schema JSON (no RAG trigger)
    // =========================================================================

    /**
     * GET /api/v1/admin/database/ecommerce/tables
     *
     * Returns the list of table names from the live ecommerce DB (secondary datasource).
     * Falls back to the root_ecommerce_DBSchema.json if the DB is unreachable.
     */
    @GetMapping("/ecommerce/tables")
    public ResponseEntity<Map<String, Object>> getEcommerceTables() {
        log.info("GET /database/ecommerce/tables — querying secondary (ecommerce) datasource");
        try {
            List<String> tableNames = ecommerceJdbcService.listTables();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status",       "success");
            resp.put("source",       "live");
            resp.put("databaseName", "ecommerce");
            resp.put("totalTables",  tableNames.size());
            resp.put("tableNames",   tableNames);
            return ResponseEntity.ok(resp);
        } catch (Exception liveEx) {
            log.warn("Live ecommerce DB unavailable ({}), falling back to JSON schema", liveEx.getMessage());
            // Fallback: read from cached schema JSON
            try {
                java.nio.file.Path schemaFile = java.nio.file.Paths.get(supportingFilesDir)
                        .toAbsolutePath().resolve("root").resolve("root_ecommerce_DBSchema.json");
                if (!java.nio.file.Files.exists(schemaFile)) {
                    return error(HttpStatus.SERVICE_UNAVAILABLE,
                            "Ecommerce DB unreachable and no cached schema found: " + liveEx.getMessage());
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> root = mapper.readValue(schemaFile.toFile(), Map.class);
                List<String> tableNames = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tables = (List<Map<String, Object>>) root.get("tables");
                if (tables != null) {
                    for (Map<String, Object> t : tables) {
                        Object name = t.get("table_name");
                        if (name instanceof String) tableNames.add((String) name);
                    }
                }
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("status",       "success");
                resp.put("source",       "cached_json");
                resp.put("databaseName", root.getOrDefault("database_name", "ecommerce"));
                resp.put("totalTables",  tableNames.size());
                resp.put("tableNames",   tableNames);
                resp.put("warning",      "Ecommerce DB unreachable — showing cached schema");
                return ResponseEntity.ok(resp);
            } catch (Exception fallbackEx) {
                log.error("Fallback also failed: {}", fallbackEx.getMessage());
                return error(HttpStatus.SERVICE_UNAVAILABLE,
                        "Ecommerce DB unreachable: " + liveEx.getMessage());
            }
        }
    }

    // =========================================================================
    // GET /table-data
    // Fetches paginated rows from a table using SELECT SQL execution
    // =========================================================================

    /**
     * GET /api/v1/admin/database/table-data?table={name}&page={0}&size={10}&db={ecommerce}
     *
     * Fetches paginated rows from a table in the ecommerce DB (secondary datasource).
     * Returns { table, columns, rows, totalRows, page, size, hasNext, hasPrev }.
     *
     * The optional {@code db} parameter is accepted but currently always routes to the
     * secondary (ecommerce) datasource — it is reserved for future multi-DB support.
     */
    @GetMapping("/table-data")
    public ResponseEntity<Map<String, Object>> getTableData(
            @RequestParam("table")                             String tableName,
            @RequestParam(value = "page", defaultValue = "0") int    page,
            @RequestParam(value = "size", defaultValue = "10") int   size,
            @RequestParam(value = "db",   defaultValue = "ecommerce") String db) {

        if (tableName == null || tableName.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "table parameter is required");
        }
        // Sanitise: only allow alphanumeric + underscore to prevent SQL injection
        if (!tableName.matches("[A-Za-z0-9_]+")) {
            return error(HttpStatus.BAD_REQUEST,
                    "Invalid table name '" + tableName + "' — only alphanumeric and underscore allowed");
        }

        int safeSize = Math.min(Math.max(size, 1), 100);
        log.info("GET /database/table-data — table='{}' page={} size={} db='{}'",
                tableName, page, safeSize, db);

        try {
            EcommerceJdbcService.PageResult result =
                    ecommerceJdbcService.fetchTableData(tableName, page, safeSize);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("table",     tableName);
            resp.put("database",  db);
            resp.put("columns",   result.columns);
            resp.put("rows",      result.rows);
            resp.put("page",      result.page);
            resp.put("size",      result.size);
            resp.put("totalRows", result.totalRows);
            resp.put("hasNext",   result.hasNext);
            resp.put("hasPrev",   result.hasPrev);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("table-data failed for '{}': {}", tableName, e.getMessage(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to fetch data from table '" + tableName + "': " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /mock/init
    // Use the default ecommerce DB to initialise the RAG pipeline
    // =========================================================================

    /**
     * POST /api/v1/admin/database/mock/init
     *
     * Initialises the RAG pipeline using the default ecommerce PostgreSQL database
     * configured in application.properties — no request body required.
     *
     * Pipeline:
     *  1. Extract live schema from the configured PostgreSQL DB (ecommerce)
     *     via JDBC DatabaseMetaData (tables, columns, PKs, FKs)
     *  2. Enrich with abbreviation expansion + EAV detection
     *  3. Chunk every table + column with overlap/stride
     *  4. Embed chunks + store in in-memory vector store
     *
     * Returns:
     *  200 OK   → RAG initialised, lists tables found
     *  503      → PostgreSQL unreachable
     *  500      → unexpected error
     */
    @PostMapping("/mock/init")
    public ResponseEntity<Map<String, Object>> mockInit() {
        log.info("POST /database/mock/init — initialising RAG from default ecommerce DB");

        // ── Step 1: Extract schema from the live PostgreSQL DB ────────────────
        com.nlp.rag.seek.model.DatabaseSchema schema;
        try {
            schema = schemaExtractionService.extractSchema();
            if (schema == null || schema.getTables().isEmpty()) {
                log.warn("No tables found in ecommerce DB — RAG not initialised");
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("status",  "warning");
                resp.put("message", "Connected to ecommerce but no tables were found. "
                        + "Please ensure the database has tables before initialising RAG.");
                resp.put("ragInitialised", false);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(resp);
            }
            log.info("Schema extracted from ecommerce DB: {} table(s)", schema.getTables().size());
        } catch (Exception e) {
            log.error("Failed to extract schema from ecommerce DB: {}", e.getMessage(), e);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status",  "error");
            resp.put("message", "Could not connect to the ecommerce PostgreSQL database. "
                    + "Please ensure PostgreSQL is running and reachable. "
                    + "Error: " + e.getMessage());
            resp.put("ragInitialised", false);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(resp);
        }

        // ── Step 2: Initialise RAG pipeline with extracted schema ─────────────
        try {
            ragInitializationService.initializeFromParsedSql(schema);
            log.info("RAG pipeline initialised from ecommerce DB — {} table(s)", schema.getTables().size());
        } catch (Exception e) {
            log.error("RAG initialisation failed: {}", e.getMessage(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Schema extracted but RAG initialisation failed: " + e.getMessage());
        }

        // ── Step 3: Build response ────────────────────────────────────────────
        List<String> tableNames = schema.getTables().stream()
                .map(com.nlp.rag.seek.model.DatabaseTable::getTableName)
                .collect(java.util.stream.Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",        "success");
        resp.put("ragInitialised", true);
        resp.put("database",      schema.getDatabaseName());
        resp.put("totalTables",   tableNames.size());
        resp.put("tableNames",    tableNames);
        resp.put("message",       "RAG pipeline successfully initialised from ecommerce DB with "
                + tableNames.size() + " table(s). Ready to accept queries.");
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // POST /schema/upload-sql
    // Accept a .sql DDL file → validate → write DBName_Schema_1.0.0.json → RAG
    // =========================================================================

    /**
     * POST /api/v1/admin/database/schema/upload-sql
     *
     * Accepts a multipart/form-data request with:
     *   file          — the .sql DDL file
     *   databaseName  — logical name used in the JSON filename  (form param)
     *   databaseType  — database type, e.g. "postgresql", "mysql" (form param, optional, defaults to "SQL_FILE")
     *   userName      — the logged-in SEEK user name             (form param)
     *
     * Pipeline:
     *  1. Validate the .sql file (extension, size, syntax, balanced parens)
     *  2. Parse schema: tables, columns, data types, PKs, FKs, UNIQUE, indexes, COMMENT ON
     *  3. Write {databaseName}_Schema_1.0.0.json to supportingFiles/{userName}/
     *  4. Re-initialise the RAG pipeline (re-chunk + re-embed + re-index)
     *
     * curl -X POST http://localhost:9095/api/v1/admin/database/schema/upload-sql \
     *      -F "file=@schema.sql" \
     *      -F "databaseName=mydb" \
     *      -F "databaseType=postgresql" \
     *      -F "userName=AshokSekar"
     */
    @PostMapping(value = "/schema/upload-sql", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadSql(
            @RequestPart("file")                          MultipartFile file,
            @RequestParam("databaseName")                 String databaseName,
            @RequestParam(value = "databaseType", required = false, defaultValue = "SQL_FILE") String databaseType,
            @RequestParam(value = "userName", required = false) String userName) {

        log.info("POST /database/schema/upload-sql — file='{}' databaseName='{}' databaseType='{}' userName='{}'",
                file.getOriginalFilename(), databaseName, databaseType,
                userName != null ? userName : "(default)");

        // ── Step 1: Read bytes ONCE — MultipartFile InputStream is single-use ─
        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "Failed to read uploaded file: " + e.getMessage());
        }
        String rawSql = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);

        // ── Step 2: Validate + parse (pass raw SQL — no stream re-read needed) ─
        SqlSchemaResult parseResult = sqlExtractorService.extractSchema(
                rawSql, file.getOriginalFilename(), file.getSize());
        if (!parseResult.isValid()) {
            log.warn("SQL file validation failed: {}", parseResult.getValidationErrors());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("valid",            false);
            resp.put("validationErrors", parseResult.getValidationErrors());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp);
        }

        // ── Step 3: Write {databaseName}_Schema_1.0.0.json to user dir ──────
        String savedTo;
        try {
            savedTo = schemaExportService.buildAndWriteSchemaFromSql(
                    databaseName, parseResult, userName, databaseType);
            log.info("Schema JSON written → {}", savedTo);
        } catch (Exception e) {
            log.error("Failed to write schema JSON: {}", e.getMessage(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to write schema JSON: " + e.getMessage());
        }

        // ── Step 4: Build DatabaseSchema from the parsed SQL result ───────────
        // Deliberately does NOT call initializeRAGPipeline() which tries to
        // connect to PostgreSQL and would time out if PG is unreachable.
        // The schema is built entirely from the already-parsed SqlSchemaResult —
        // zero JDBC, zero live-database connection.
        com.nlp.rag.seek.model.DatabaseSchema schema;
        try {
            schema = schemaExtractionService.buildSchemaFromSqlResult(databaseName, parseResult, databaseType);
            log.info("DatabaseSchema built from uploaded SQL: '{}' — {} table(s)",
                    databaseName, schema.getTables().size());
        } catch (Exception e) {
            log.error("Schema build from SQL result failed: {}", e.getMessage(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Schema JSON saved but schema build failed: " + e.getMessage());
        }

        // ── Step 5: Index RAG pipeline with the parsed schema ─────────────────
        try {
            ragInitializationService.initializeFromParsedSql(schema, userName);
            log.info("RAG pipeline indexed from uploaded SQL schema '{}'", databaseName);
        } catch (Exception e) {
            log.error("RAG index failed: {}", e.getMessage(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Schema saved but RAG indexing failed: " + e.getMessage());
        }

        // ── Step 6: Detect abbreviated schema and build mapper ───────────────
        boolean isAbbreviated = false;
        try {
            isAbbreviated = abbreviatedSchemaDetectionService.detectAndMap(schema, userName);
            if (isAbbreviated) {
                log.info("Schema '{}' detected as ABBREVIATED — mapper file created", databaseName);
            } else {
                log.info("Schema '{}' is descriptive — no abbreviation mapping needed", databaseName);
            }
        } catch (Exception e) {
            log.warn("Abbreviated schema detection failed (non-fatal): {}", e.getMessage());
            // Non-fatal — the pipeline still works without abbreviation mapping
        }

        // ── Step 7: Response ──────────────────────────────────────────────────
        String schemaFileNameOnly = savedTo != null
                ? Paths.get(savedTo).getFileName().toString()
                : databaseName + "_schema.json";
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",        "success");
        resp.put("valid",         true);
        resp.put("databaseName",  databaseName);
        resp.put("schemaFile",    schemaFileNameOnly);
        resp.put("savedTo",       savedTo);
        if (userName != null && !userName.isBlank()) {
            resp.put("userName", userName);
        }
        resp.put("totalTables",   parseResult.getTotalTables());
        resp.put("totalColumns",  parseResult.getTotalColumns());
        resp.put("totalIndexes",  parseResult.getTotalIndexes());
        resp.put("ragReIndexed",  true);
        resp.put("isAbbreviated", isAbbreviated);
        resp.put("tableNames",
                parseResult.getTables().stream()
                        .map(TableExtract::getTableName)
                        .collect(java.util.stream.Collectors.toList()));
        resp.put("message",
                "SQL file validated, schema JSON written to " + savedTo
                + " and RAG pipeline re-indexed with "
                + parseResult.getTotalTables() + " table(s).");
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // POST /reindex
    // Re-runs the RAG pipeline — re-reads JSON, re-chunks, re-embeds, re-indexes
    // =========================================================================

    /**
     * POST /api/v1/admin/database/reindex
     *
     * Re-reads the schema JSON file (which may now have user-added descriptions),
     * re-chunks every table/column, re-embeds, and replaces the in-memory
     * vector store. Call this after adding or updating table/column descriptions
     * so the LLM sees the latest metadata when generating SQL.
     *
     * Response:
     * { "status": "success", "message": "RAG pipeline re-indexed" }
     */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        log.info("POST /database/reindex — re-running RAG pipeline");
        try {
            ragInitializationService.initializeRAGPipeline();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status",  "success");
            resp.put("message", "RAG pipeline re-indexed with latest schema descriptions");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Reindex failed: {}", e.getMessage(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Reindex failed: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/admin/database/connect
     *
     * Connects to the supplied database, extracts the full schema
     * (tables, columns, data types, column sizes, constraints, indexes),
     * writes it to {databaseName}_schema_0.0.1.json under the user's
     * supportingFiles directory, and returns the list of table names found.
     *
     * Request:
     * {
     *   "databaseName": "ecommerce",
     *   "host":         "db.invinciblebots.com",
     *   "port":         5432,
     *   "username":     "postgres",
     *   "password":     "root",
     *   "databaseType": "PostgreSQL",
     *   "seekUserName": "AshokSekar"
     * }
     *
     * Success response (200 OK):
     * {
     *   "status":       "success",
     *   "databaseName": "ecommerce",
     *   "databaseType": "PostgreSQL",
     *   "totalTables":  6,
     *   "schemaFile":   "ecommerce_Schema_1.0.0.json",
     *   "savedTo":      ".../supportingFiles/AshokSekar/ecommerce_Schema_1.0.0.json",
     *   "tableNames":   ["employees", "departments", ...]
     * }
     *
     * Error responses:
     *   400 — missing / invalid request fields
     *   503 — cannot connect to the supplied database
     *   500 — unexpected error during extraction or file write
     */
    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(
            @RequestBody DatabaseConnectRequest request) {

        log.info("POST /database/connect — db='{}' host='{}:{}' type='{}' seekUserName='{}'",
                request.getDatabaseName(), request.getHost(),
                request.getPort(), request.getDatabaseType(),
                request.hasSeekUserName() ? request.getSeekUserName() : "(default)");

        try {
            // ── Extract schema and save JSON file ──────────────────────────────
            List<String> tableNames = schemaExportService.connectExtractAndSave(request);

            // ── Register dynamic datasource for SQL execution ──────────────────
            dynamicDataSourceRegistry.register(request);
            log.info("Dynamic datasource registered for '{}'", request.getDatabaseName());

            // Resolve the actual path where the file was saved
            String savedTo = request.hasSeekUserName()
                    ? schemaExportService.getSchemaFilePath(
                            request.getDatabaseName(),
                            dirResolver.resolveUserDir(request.getSeekUserName()))
                    : schemaExportService.getSchemaFilePath(request.getDatabaseName());
            String schemaFileName = savedTo != null
                    ? Paths.get(savedTo).getFileName().toString()
                    : request.getDatabaseName().trim() + "_schema.json";

            // ── Build DatabaseSchema from saved JSON and initialize RAG ────────
            // This creates the vector_index_{databaseName}.json so future NL2SQL
            // queries pick up chunks from the correct database, not the default.
            boolean ragIndexed = false;
            try {
                com.nlp.rag.seek.model.DatabaseSchema schema =
                        schemaExtractionService.buildActiveSchemaFromJson(request.getDatabaseName());
                if (schema != null && !schema.getTables().isEmpty()) {
                    String userName = request.hasSeekUserName() ? request.getSeekUserName() : null;
                    ragInitializationService.initializeFromParsedSql(schema, userName);
                    ragIndexed = true;
                    log.info("RAG pipeline indexed for '{}' — {} tables, {} chunks",
                            request.getDatabaseName(), schema.getTables().size(),
                            ragInitializationService.getActiveSchema() != null
                                    ? schema.getTables().size() : 0);
                }
            } catch (Exception e) {
                log.warn("Schema saved but RAG indexing failed for '{}': {}",
                        request.getDatabaseName(), e.getMessage());
            }

            // ── Detect abbreviated schema ───────────────────────────────────
            boolean isAbbreviated = false;
            if (ragIndexed) {
                try {
                    com.nlp.rag.seek.model.DatabaseSchema activeSchema =
                            ragInitializationService.getActiveSchema();
                    String userName = request.hasSeekUserName() ? request.getSeekUserName() : null;
                    isAbbreviated = abbreviatedSchemaDetectionService.detectAndMap(activeSchema, userName);
                    log.info("Connect: abbreviated detection for '{}' → {}",
                            request.getDatabaseName(), isAbbreviated);
                } catch (Exception e) {
                    log.warn("Abbreviated detection failed (non-fatal): {}", e.getMessage());
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status",       "success");
            resp.put("databaseName", request.getDatabaseName());
            resp.put("databaseType", request.getDatabaseType());
            resp.put("totalTables",  tableNames.size());
            resp.put("schemaFile",   schemaFileName);
            resp.put("savedTo",      savedTo);
            resp.put("ragIndexed",   ragIndexed);
            resp.put("isAbbreviated", isAbbreviated);
            if (request.hasSeekUserName()) {
                resp.put("userName", request.getSeekUserName());
            }
            resp.put("tableNames",   tableNames);

            log.info("Schema export complete: {} tables saved to {}, ragIndexed={}",
                    tableNames.size(), savedTo, ragIndexed);
            return ResponseEntity.ok(resp);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid connect request: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());

        } catch (java.sql.SQLException e) {
            log.error("Database connection failed for '{}' type='{}' url='{}': {}",
                    request.getDatabaseName(), request.getDatabaseType(),
                    request.toJdbcUrl(), e.getMessage());
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cannot connect to database '" + request.getDatabaseName()
                    + "' (" + request.getDatabaseType() + " @ " + request.getHost()
                    + ":" + request.getPort() + "): " + e.getMessage());

        } catch (Exception e) {
            log.error("Unexpected error during schema export: {}", e.getMessage(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Schema export failed: " + e.getMessage());
        }
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",  "error");
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}

