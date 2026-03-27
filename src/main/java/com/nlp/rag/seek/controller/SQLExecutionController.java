package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.model.SQLExecutionResult;
import com.nlp.rag.seek.service.SQLExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for standalone SQL execution against the live datasource.
 *
 * Base path: /api/v1/execute
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │  POST /api/v1/execute                                        │
 * │    Execute any validated SELECT SQL and return rows          │
 * │                                                              │
 * │  Request body:                                               │
 * │    {                                                         │
 * │      "sql":      "SELECT * FROM departments;",              │
 * │      "rowLimit": 50          // optional, default 50         │
 * │    }                                                         │
 * │                                                              │
 * │  Response: SQLExecutionResult                                │
 * │    { columns, rows, rowCount, truncated,                     │
 * │      executionTimeMs, dataSource, success, error }           │
 * └──────────────────────────────────────────────────────────────┘
 *
 * This endpoint is also invoked internally by NL2SQLController when
 * the request includes "showSampleResults": true.
 */
@RestController
@RequestMapping("/api/v1/execute")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "SQL Execution", description = "Execute validated SQL against the live datasource")
public class SQLExecutionController {

    private static final Logger log = LoggerFactory.getLogger(SQLExecutionController.class);

    @Autowired
    private SQLExecutionService sqlExecutionService;

    /**
     * POST /api/v1/execute
     *
     * Execute a SELECT SQL and return up to rowLimit rows.
     *
     * Request body:
     * {
     *   "sql":      "SELECT dept_id, dept_name FROM departments LIMIT 10;",
     *   "rowLimit": 50
     * }
     */
    @Operation(summary = "Execute SQL", description = "Execute a SELECT query and return up to rowLimit rows")
    @PostMapping
    public ResponseEntity<SQLExecutionResult> execute(@RequestBody Map<String, Object> body) {
        String sql = (String) body.get("sql");
        if (sql == null || sql.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(SQLExecutionResult.failure("'sql' field is required", 0));
        }

        int rowLimit = 50;
        if (body.containsKey("rowLimit")) {
            try {
                rowLimit = Integer.parseInt(body.get("rowLimit").toString());
            } catch (NumberFormatException ignored) { /* use default */ }
        }

        log.info("POST /api/v1/execute | rowLimit={} | sql preview: {}",
                 rowLimit, sql.substring(0, Math.min(sql.length(), 120)));

        SQLExecutionResult result = sqlExecutionService.execute(sql, rowLimit);
        return ResponseEntity.ok(result);
    }
}
