package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.service.RAGInitializationService;
import com.nlp.rag.seek.service.SchemaExtractionService;
import com.nlp.rag.seek.service.SqlFileSchemaExtractorService;
import com.nlp.rag.seek.service.SqlFileSchemaExtractorService.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.*;

/**
 * REST controller for uploading a .sql DDL file, extracting its schema
 * and re-indexing the RAG vector store.
 *
 * Base path: /api/v1/admin/sql
 *
 * POST /parse  — validate + extract schema details (dry run)
 * POST /load   — validate + build DatabaseSchema + re-index RAG pipeline
 */
@RestController
@RequestMapping("/api/v1/admin/sql")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "SQL Schema", description = "SQL schema file upload and management")
public class SqlSchemaController {

    private static final Logger log = LoggerFactory.getLogger(SqlSchemaController.class);

    @Autowired
    private SqlFileSchemaExtractorService extractorService;

    @Autowired
    private RAGInitializationService ragInitializationService;

    @Autowired
    private SchemaExtractionService schemaExtractionService;

    /** Maximum allowed file size in MB — configured via genqry.upload.max-file-size-mb */
    @Value("${genqry.upload.max-file-size-mb:1}")
    private long maxFileSizeMb;

    // =========================================================================
    // POST /parse  — dry-run: validate + extract schema only, no H2 load
    // =========================================================================

    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> parse(
            @RequestPart("file") MultipartFile file) {

        log.info("POST /sql/parse — file='{}' size={} bytes",
                file.getOriginalFilename(), file.getSize());

        // ── Global file size enforcement ──────────────────────────────────────
        ResponseEntity<Map<String, Object>> sizeError = checkFileSize(file);
        if (sizeError != null) return sizeError;

        SqlSchemaResult result = extractorService.extractSchema(file);
        Map<String, Object> resp = buildResponse(result);

        if (!result.isValid()) {
            log.warn("SQL file validation failed: {}", result.getValidationErrors());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp);
        }

        log.info("SQL file parsed — {} table(s), {} column(s), {} index(es)",
                result.getTotalTables(), result.getTotalColumns(), result.getTotalIndexes());
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // POST /load  — validate + load into H2 + re-index RAG
    // =========================================================================

    /**
     * Upload a .sql DDL file.  The service:
     *  1. Validates the file (type, size, syntax)
     *  2. Parses schema details (tables, columns, PKs, FKs, indexes, comments)
     *  3. Executes the DDL against the H2 in-memory database
     *  4. Re-initialises the RAG pipeline using the H2 schema
     *     so the LLM generates SQL against the uploaded schema
     *
     * Returns HTTP 400 on validation failure.
     * Returns HTTP 200 with full extracted schema + RAG index stats on success.
     */
    @PostMapping(value = "/load", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> load(
            @RequestPart("file") MultipartFile file) {

        log.info("POST /sql/load — file='{}' size={} bytes",
                file.getOriginalFilename(), file.getSize());

        // ── Global file size enforcement ──────────────────────────────────────
        ResponseEntity<Map<String, Object>> sizeError = checkFileSize(file);
        if (sizeError != null) return sizeError;

        // Read bytes up-front — MultipartFile InputStream can only be consumed once
        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "Failed to read file: " + e.getMessage());
        }
        String rawSql = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);

        // ── Step 1: Parse & validate (pass raw SQL — no stream re-read needed) ─
        SqlSchemaResult result = extractorService.extractSchema(
                rawSql, file.getOriginalFilename(), file.getSize());
        if (!result.isValid()) {
            log.warn("SQL file validation failed: {}", result.getValidationErrors());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(buildResponse(result));
        }

        // ── Step 2: Build DatabaseSchema directly from parsed SQL result ────────
        com.nlp.rag.seek.model.DatabaseSchema schema;
        try {
            schema = schemaExtractionService.buildSchemaFromSqlResult(
                    result.getFileName() != null
                            ? result.getFileName().replaceFirst("\\.sql$", "") : "uploaded",
                    result);
            log.info("DatabaseSchema built from SQL — {} table(s)", schema.getTables().size());
        } catch (Exception e) {
            log.error("Schema build from SQL result failed: {}", e.getMessage(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Schema build failed: " + e.getMessage());
        }

        // ── Step 3: Re-index RAG pipeline from parsed schema ─────────────────
        try {
            ragInitializationService.initializeFromParsedSql(schema);
            log.info("RAG pipeline re-indexed from uploaded SQL schema");
        } catch (Exception e) {
            log.error("RAG re-index failed: {}", e.getMessage(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "RAG re-index failed: " + e.getMessage());
        }

        // ── Step 4: Build response ────────────────────────────────────────────
        Map<String, Object> resp = buildResponse(result);
        resp.put("ragReIndexed", true);
        resp.put("message",
                "SQL schema parsed and RAG pipeline re-indexed with "
                + result.getTotalTables() + " table(s). "
                + "The LLM will now generate SQL against this schema.");

        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // Response builder
    // =========================================================================

    private Map<String, Object> buildResponse(SqlSchemaResult result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("valid",            result.isValid());
        resp.put("validationErrors", result.getValidationErrors());

        if (!result.isValid()) return resp;

        resp.put("fileName",      result.getFileName());
        resp.put("fileSizeBytes", result.getFileSizeBytes());
        resp.put("totalTables",   result.getTotalTables());
        resp.put("totalColumns",  result.getTotalColumns());
        resp.put("totalIndexes",  result.getTotalIndexes());

        List<Map<String, Object>> tablesList = new ArrayList<>();
        for (TableExtract table : result.getTables()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tableName",         table.getTableName());
            t.put("comment",           table.getComment());
            t.put("primaryKeyColumns", table.getPrimaryKeyColumns());
            t.put("uniqueColumns",     table.getUniqueColumns());

            List<Map<String, Object>> fkList = new ArrayList<>();
            for (ForeignKeyExtract fk : table.getForeignKeys()) {
                Map<String, Object> fkMap = new LinkedHashMap<>();
                fkMap.put("constraintName",   fk.getConstraintName());
                fkMap.put("column",           fk.getColumn());
                fkMap.put("referencedTable",  fk.getReferencedTable());
                fkMap.put("referencedColumn", fk.getReferencedColumn());
                fkList.add(fkMap);
            }
            t.put("foreignKeys", fkList);

            List<Map<String, Object>> colsList = new ArrayList<>();
            for (ColumnExtract col : table.getColumns()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("columnName",          col.getColumnName());
                c.put("dataType",            col.getDataType());
                c.put("nullable",            col.isNullable());
                c.put("primaryKey",          col.isPrimaryKey());
                c.put("foreignKey",          col.isForeignKey());
                c.put("unique",              col.isUnique());
                c.put("autoIncrement",       col.isAutoIncrement());
                c.put("defaultValue",        col.getDefaultValue());
                c.put("foreignKeyReference", col.getForeignKeyReference());
                c.put("fkConstraintName",    col.getFkConstraintName());
                c.put("comment",             col.getComment());
                colsList.add(c);
            }
            t.put("columns",     colsList);
            t.put("columnCount", colsList.size());
            tablesList.add(t);
        }
        resp.put("tables", tablesList);

        List<Map<String, Object>> idxList = new ArrayList<>();
        for (IndexExtract idx : result.getIndexes()) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("indexName", idx.getIndexName());
            i.put("tableName", idx.getTableName());
            i.put("unique",    idx.isUnique());
            i.put("columns",   idx.getColumns());
            idxList.add(i);
        }
        resp.put("indexes", idxList);

        return resp;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid",   false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns a 413 response if the file exceeds the configured limit, otherwise null. */
    private ResponseEntity<Map<String, Object>> checkFileSize(MultipartFile file) {
        long maxBytes = maxFileSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            double sizeMb = file.getSize() / (1024.0 * 1024.0);
            log.warn("Upload rejected — '{}' is {:.2f} MB (limit: {} MB)",
                    file.getOriginalFilename(), sizeMb, maxFileSizeMb);
            return ResponseEntity.status(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of(
                        "valid",   false,
                        "error",   String.format("File '%.0s' (%.2f MB) exceeds the %d MB upload limit.",
                                    file.getOriginalFilename(), sizeMb, maxFileSizeMb),
                        "maxMb",   maxFileSizeMb,
                        "fileMb",  String.format("%.2f", sizeMb)
                    ));
        }
        return null;
    }
}
