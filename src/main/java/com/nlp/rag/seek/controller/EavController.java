package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.service.SchemaFileReaderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.*;

/**
 * Controller for EAV (Entity-Attribute-Value) table management.
 *
 * Base path: /api/v1/admin/eav-tables
 */
@RestController
@RequestMapping("/api/v1/admin/eav-tables")
@Tag(name = "EAV", description = "EAV table detection and attribute configuration")
public class EavController {

    private static final Logger log = LoggerFactory.getLogger(EavController.class);

    @Autowired
    private SchemaFileReaderService schemaFileReaderService;

    /**
     * GET /api/v1/admin/eav-tables
     *
     * Scans the schema file for the given database and returns all tables
     * that are marked as EAV tables (is_eav_table: true).
     *
     * Query parameters:
     * - databaseName: The database name to scan
     *
     * Response:
     * {
     *   "databaseName": "ecommerce",
     *   "eavTables": ["product_attributes", "user_metadata"],
     *   "relatedTables": ["products", "users"],
     *   "count": 2
     * }
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getEavTables(
            @RequestParam String databaseName) {

        log.info("GET /eav-tables — scanning database '{}'", databaseName);

        try {
            Map<String, Object> result = findEavTablesAndRelated(databaseName);

            List<String> eavTableNames = (List<String>) result.get("eavTables");
            List<String> relatedTableNames = (List<String>) result.get("relatedTables");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("databaseName", databaseName);
            response.put("eavTables", eavTableNames);
            response.put("relatedTables", relatedTableNames);
            response.put("count", eavTableNames.size());

            log.info("Found {} EAV tables in database '{}': {}", eavTableNames.size(), databaseName, eavTableNames);
            log.info("Found {} related tables: {}", relatedTableNames.size(), relatedTableNames);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to scan EAV tables for database '{}': {}", databaseName, e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Failed to scan EAV tables: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Scans the schema file for the given database and extracts table names
     * that have is_eav_table set to true, along with their related tables.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findEavTablesAndRelated(String databaseName) {
        List<String> eavTables = new ArrayList<>();
        Set<String> relatedTables = new HashSet<>();

        // Get the raw schema root
        Optional<Map<String, Object>> rootOpt = schemaFileReaderService.getRawRoot(databaseName);

        if (rootOpt.isPresent()) {
            Map<String, Object> root = rootOpt.get();
            Object tablesObj = root.get("tables");

            if (tablesObj instanceof List) {
                List<Map<String, Object>> tables = (List<Map<String, Object>>) tablesObj;

                for (Map<String, Object> table : tables) {
                    Object tableNameObj = table.get("table_name");
                    Object isEavObj = table.get("is_eav_table");

                    if (tableNameObj instanceof String && Boolean.TRUE.equals(isEavObj)) {
                        eavTables.add((String) tableNameObj);

                        // Find related tables from foreign key relationships
                        Object columnsObj = table.get("columns");
                        if (columnsObj instanceof List) {
                            List<Map<String, Object>> columns = (List<Map<String, Object>>) columnsObj;
                            for (Map<String, Object> column : columns) {
                                Object fkObj = column.get("foreign_key");
                                if (fkObj instanceof String && !((String) fkObj).isEmpty()) {
                                    String fkRef = (String) fkObj;
                                    // Extract table name from foreign key reference (e.g., "products.id" -> "products")
                                    if (fkRef.contains(".")) {
                                        String relatedTable = fkRef.substring(0, fkRef.indexOf('.'));
                                        relatedTables.add(relatedTable);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eavTables", eavTables);
        result.put("relatedTables", new ArrayList<>(relatedTables));
        return result;
    }
}
