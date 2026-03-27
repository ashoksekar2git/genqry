package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.model.UserMetadataRequest;
import com.nlp.rag.seek.service.MetadataAwareChunkUpdateService;
import com.nlp.rag.seek.service.SchemaFileReaderService;
import com.nlp.rag.seek.service.SchemaMetadataService;
import com.nlp.rag.seek.service.UserMetadataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.*;

/**
 * REST controller exposing schema metadata to the UI schema browser.
 *
 * Base path: /api/v1/admin/metadata
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ ENDPOINT                                              RETURNS           │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ GET /databases/{db}/tables          ALL table names (String[])          │
 * │ GET /databases/{db}/tables/detail   Paginated full (max 10/page)        │
 * │ GET /databases/{db}/tables/{table}  Single table full detail            │
 * │ GET /databases/{db}/tables/{table}/columns  Column names (String[])     │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * /tables        — returns ALL table names, no paging, no query params needed.
 * /tables/detail — returns full TableMetadata with columns, paginated max 10
 *                  per page (?page=0&pageSize=10) because column payloads are large.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata")
@Tag(name = "Schema Metadata", description = "Browse database schema tables and columns")
public class SchemaMetadataController {

    private static final Logger log = LoggerFactory.getLogger(SchemaMetadataController.class);

    @Autowired
    private SchemaMetadataService schemaMetadataService;

    @Autowired
    private SchemaFileReaderService schemaFileReaderService;

    @Autowired
    private UserMetadataStore userMetadataStore;

    @Autowired
    private MetadataAwareChunkUpdateService chunkUpdateService;

    // =========================================================================
    // GET /databases/{databaseName}/description-check
    // Scans schema JSON for tables with blank/missing description
    // =========================================================================

    /**
     * GET /api/v1/admin/metadata/databases/{databaseName}/description-check
     *
     * Reads the {databaseName}_Schema_1.0.0.json from the workspace directory
     * and returns which tables are missing a description.
     *
     * Response:
     * {
     *   "databaseName":           "ecommerce",
     *   "totalTables":            15,
     *   "tablesWithDescription":  8,
     *   "tablesMissingDescription": 7,
     *   "missingTables":          ["accounts", "users", ...]
     * }
     */
    @GetMapping("/databases/{databaseName}/description-check")
    public ResponseEntity<Map<String, Object>> checkDescriptions(
            @PathVariable String databaseName) {

        log.info("GET /metadata/databases/{}/description-check", databaseName);

        Map<String, Object> result = schemaFileReaderService.checkMissingDescriptions(databaseName);
        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // GET /databases/{databaseName}/tables
    // Returns String[] — table names only, paginated (max 10 per page)
    // =========================================================================

    /**
     * GET /api/v1/admin/metadata/databases/{databaseName}/tables
     *
     * Returns ALL table names for the given database.
     *
     * Resolution order:
     *  1. Reads {databaseName}_Schema_1.0.0.json from the metadata directory
     *     (written by POST /api/v1/admin/database/connect)
     *  2. Falls back to the live JDBC schema extraction when the file
     *     does not exist.
     *
     * Example response:
     * {
     *   "databaseName": "ecommerce",
     *   "schemaName":   "public",
     *   "totalTables":  6,
     *   "tableNames": ["employees", "departments", "orders", ...]
     * }
     */
    @GetMapping("/databases/{databaseName}/tables")
    public ResponseEntity<Map<String, Object>> getTableNames(
            @PathVariable String databaseName) {

        log.info("GET /metadata/databases/{}/tables", databaseName);

        // ── 1. Try schema JSON file first ──────────────────────────────────────
        Optional<Map<String, Object>> fromFile = schemaFileReaderService.getTableNames(databaseName);
        if (fromFile.isPresent()) {
            log.info("Serving table names for '{}' from schema file", databaseName);
            return ResponseEntity.ok(fromFile.get());
        }

        // ── 2. Fall back to live JDBC schema ───────────────────────────────────
        log.info("Schema file not found for '{}' — falling back to JDBC schema", databaseName);
        return ResponseEntity.ok(schemaMetadataService.getTableNames(databaseName));
    }

    // =========================================================================
    // GET /databases/{databaseName}/tables/detail
    // Returns full TableMetadata list (with columns), paginated (max 10)
    // =========================================================================

    /**
     * GET /api/v1/admin/metadata/databases/{databaseName}/tables/detail
     *
     * Returns paginated list of full TableMetadata objects each with columns.
     *
     * Example response:
     * {
     *   "databaseName": "ecommerce",
     *   "totalTables":  12,
     *   "page":         0,
     *   "pageSize":     10,
     *   "totalPages":   2,
     *   "hasNext":      true,
     *   "hasPrevious":  false,
     *   "tables": [
     *     {
     *       "tableName":         "emp_dtls",
     *       "humanReadableName": "employee details",
     *       "description":       "Employee details — stores employee records...",
     *       "businessContext":   "employee details (emp_dtls)",
     *       "columnCount":       6,
     *       "columns": [
     *         {
     *           "columnName":         "f_nm",
     *           "humanReadableName":  "first name",
     *           "dataType":           "VARCHAR",
     *           "description":        "First name — the first name of the employee details.",
     *           "primaryKey":         false,
     *           "foreignKey":         false,
     *           "foreignKeyReference": null,
     *           "nullable":           false
     *         },
     *         ...
     *       ]
     *     },
     *     ...
     *   ]
     * }
     */
    @GetMapping("/databases/{databaseName}/tables/detail")
    public ResponseEntity<Map<String, Object>> getTablesWithColumns(
            @PathVariable String databaseName,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int pageSize) {

        log.info("GET /metadata/databases/{}/tables/detail page={} pageSize={}",
                databaseName, page, pageSize);

        Map<String, Object> result = schemaMetadataService.getTablesPage(
                databaseName, page, pageSize);

        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // GET /databases/{databaseName}/tables/{tableName}
    // Returns full metadata for a single table (all columns)
    // =========================================================================

    /**
     * GET /api/v1/admin/metadata/databases/{databaseName}/tables/{tableName}
     *
     * Returns full details for a single table.
     *
     * Resolution order:
     *  1. Reads {databaseName}_Schema_1.0.0.json — returns the table entry
     *     exactly as stored (table_name, description, primary_key, columns[])
     *  2. Falls back to live JDBC schema when the file does not exist.
     *  3. Returns 404 when the table is not found in either source.
     */
    @GetMapping("/databases/{databaseName}/tables/{tableName}")
    public ResponseEntity<?> getTableDetail(
            @PathVariable String databaseName,
            @PathVariable String tableName) {

        log.info("GET /metadata/databases/{}/tables/{}", databaseName, tableName);

        // ── 1. Try schema JSON file first ──────────────────────────────────────
        Optional<Map<String, Object>> fromFile =
                schemaFileReaderService.getTableDetail(databaseName, tableName);
        if (fromFile.isPresent()) {
            log.info("Serving table detail for '{}.{}' from schema file", databaseName, tableName);
            return ResponseEntity.ok(fromFile.get());
        }

        // ── 2. Fall back to live JDBC schema ───────────────────────────────────
        log.info("Schema file not found for '{}' — falling back to JDBC schema", databaseName);
        return schemaMetadataService.getTableDetail(databaseName, tableName)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // =========================================================================
    // GET /databases/{databaseName}/tables/{tableName}/columns
    // Returns column names as String[]
    // =========================================================================

    /**
     * GET /api/v1/admin/metadata/databases/{databaseName}/tables/{tableName}/columns
     *
     * Returns full column details for the given table.
     *
     * Resolution order:
     *  1. Reads {databaseName}_Schema_1.0.0.json from the metadata directory.
     *     Returns the full columns[] array exactly as stored in the JSON —
     *     column_name, data_type, column_comment, nullable, default_value,
     *     auto_increment, primary_key, foreign_key, description, values[].
     *  2. Falls back to live JDBC when the file does not exist — returns
     *     column names only (String[]).
     *  3. Returns 404 when the table is not found in either source.
     *
     * Example response (from JSON file):
     * {
     *   "databaseName"  : "ecommerce",
     *   "schemaName"    : "public",
     *   "tableName"     : "employees",
     *   "description"   : "Stores employee records",
     *   "primaryKey"    : ["id"],
     *   "columnCount"   : 10,
     *   "columns": [
     *     {
     *       "column_name"    : "id",
     *       "data_type"      : "integer",
     *       "column_comment" : "id field",
     *       "nullable"       : false,
     *       "default_value"  : null,
     *       "auto_increment" : false,
     *       "primary_key"    : true,
     *       "foreign_key"    : null,
     *       "description"    : "",
     *       "values"         : []
     *     },
     *     ...
     *   ]
     * }
     */
    @GetMapping("/databases/{databaseName}/tables/{tableName}/columns")
    public ResponseEntity<?> getColumnNames(
            @PathVariable String databaseName,
            @PathVariable String tableName) {

        log.info("GET /metadata/databases/{}/tables/{}/columns", databaseName, tableName);

        // ── 1. Try schema JSON file first — returns FULL column objects ────────
        Optional<Map<String, Object>> fromFile =
                schemaFileReaderService.getTableColumns(databaseName, tableName);
        if (fromFile.isPresent()) {
            log.info("Serving column details for '{}.{}' from schema file", databaseName, tableName);
            return ResponseEntity.ok(fromFile.get());
        }

        // ── 2. Fall back to live JDBC — returns column names only ──────────────
        log.info("Schema file not found for '{}' — falling back to JDBC column names", databaseName);
        return schemaMetadataService.getColumnNames(databaseName, tableName)
                .<ResponseEntity<?>>map(cols -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("databaseName", databaseName);
                    resp.put("tableName",    tableName);
                    resp.put("columnCount",  cols.size());
                    resp.put("columnNames",  cols);
                    return ResponseEntity.ok(resp);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // =========================================================================
    // POST /add
    // Saves user-supplied table + column descriptions to JSON file
    // =========================================================================

    /**
     * POST /api/v1/admin/metadata/add
     *
     * Updates the table section inside the {databaseName}_Schema_1.0.0.json file
     * found in the classpath metadata directory.
     *
     * - Sets "description" on the matching table entry.
     * - Sets "description" on each matching column entry (matched by column_name).
     *
     * The schema file must already exist (created by POST /api/v1/admin/database/connect).
     *
     * Request body:
     * {
     *   "databaseName": "ecommerce",
     *   "tableMetadata": {
     *     "tableName":   "employees",
     *     "description": "This table is used for storing employees personal details...",
     *     "isEavTable":  true
     *   },
     *   "columnMetadata": [
     *     { "columnName": "id",         "description": "employee id primary key" },
     *     { "columnName": "first_name", "description": "employee first name" },
     *     { "columnName": "last_name",  "description": "employee last name" },
     *     ...
     *   ]
     * }
     *
     * Success response (200 OK):
     * {
     *   "status":     "updated",
     *   "databaseName": "ecommerce",
     *   "tableName":  "employees",
     *   "schemaFile": "ecommerce_Schema_1.0.0.json",
     *   "updatedIn":  "/absolute/path/to/ecommerce_Schema_1.0.0.json",
     *   "columns":    10
     * }
     *
     * Error response (400 Bad Request):
     * { "status": "error", "message": "tableMetadata.tableName is required..." }
     */
    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addMetadata(
            @RequestBody UserMetadataRequest request) {

        log.info("POST /metadata/add — table='{}' db='{}'",
                request.getTableMetadata() != null
                        ? request.getTableMetadata().getTableName() : "null",
                request.getDatabaseName());

        try {
            java.nio.file.Path updatedFile = userMetadataStore.save(request);

            String tableName    = request.getTableMetadata().getTableName().trim().toLowerCase();
            String databaseName = request.getDatabaseName().trim();
            int    colCount     = request.getColumnMetadata() != null
                                  ? request.getColumnMetadata().size() : 0;

            // ── Surgically update only the impacted chunks in the vector index ──
            // Build a columnName→description map from the request
            java.util.Map<String, String> columnUpdates = new java.util.LinkedHashMap<>();
            if (request.getColumnMetadata() != null) {
                for (UserMetadataRequest.ColumnInfo ci : request.getColumnMetadata()) {
                    if (ci.getColumnName() != null && ci.getDescription() != null) {
                        columnUpdates.put(ci.getColumnName().trim().toLowerCase(),
                                          ci.getDescription().trim());
                    }
                }
            }
            String tableDesc      = request.getTableMetadata().getDescription();
            String businessContext = request.getTableMetadata().getBusinessContext();
            Boolean isEavTable     = request.getTableMetadata().getIsEavTable();

            // Run the chunk update asynchronously so the HTTP response is not delayed
            new Thread(() -> {
                try {
                    chunkUpdateService.updateChunksForTable(tableName, tableDesc, businessContext, isEavTable, columnUpdates);
                } catch (Exception ex) {
                    log.error("Background chunk update failed for table '{}': {}", tableName, ex.getMessage(), ex);
                }
            }, "chunk-update-" + tableName).start();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status",           "updated");
            resp.put("databaseName",     databaseName);
            resp.put("tableName",        tableName);
            resp.put("schemaFile",       updatedFile.getFileName().toString());
            resp.put("updatedIn",        updatedFile.toAbsolutePath().toString());
            resp.put("columns",          colCount);
            resp.put("vectorIndexUpdate","in-progress");

            log.info("Schema file updated for table '{}' → {}, chunk re-index started",
                    tableName, updatedFile.toAbsolutePath());
            return ResponseEntity.ok(resp);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid metadata request: {}", e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status",  "error");
            err.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(err);

        } catch (Exception e) {
            log.error("Failed to update schema metadata: {}", e.getMessage(), e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status",  "error");
            err.put("message", "Failed to update schema file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // =========================================================================
    // GET /saved
    // Lists all saved metadata files + GET /saved/{tableName} reads one back
    // =========================================================================

    /**
     * GET /api/v1/admin/metadata/saved
     *
     * Lists all table names that have a saved metadata file.
     *
     * Response:
     * {
     *   "savedMetadataTables": ["employees", "departments", "orders"],
     *   "count": 3,
     *   "saveDirectory": "/absolute/path/to/metadata"
     * }
     */
    @GetMapping("/saved")
    public ResponseEntity<Map<String, Object>> listSaved() {
        List<String> tables = userMetadataStore.listSavedTables();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("savedMetadataTables", tables);
        resp.put("count",              tables.size());
        resp.put("saveDirectory",      userMetadataStore.getSaveDirectory());
        return ResponseEntity.ok(resp);
    }

    /**
     * GET /api/v1/admin/metadata/saved/{tableName}
     *
     * Reads back the saved {tableName}_metadata.json and returns it.
     * Returns 404 when no file exists for the given table.
     *
     * Response (employees):
     * {
     *   "databaseName": "ecommerce",
     *   "tableName":    "employees",
     *   "description":  "This table stores employee personal details...",
     *   "savedAt":      "2026-02-28T21:00:00Z",
     *   "columns": [
     *     { "columnName": "id",         "description": "employee id primary key" },
     *     { "columnName": "first_name", "description": "employee first name" },
     *     ...
     *   ]
     * }
     */
    @GetMapping("/saved/{tableName}")
    public ResponseEntity<?> getSaved(@PathVariable String tableName) {
        log.info("GET /metadata/saved/{}", tableName);
        return userMetadataStore.load(tableName)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

