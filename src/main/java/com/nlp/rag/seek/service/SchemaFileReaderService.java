package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads {DBName}_schema_{timestamp}.json files produced by {@link DatabaseSchemaExportService}.
 *
 * Resolution order for finding the schema file:
 *  1. Latest filename registered in MetadataDirectoryResolver (from the current session)
 *  2. Most-recently-modified file matching {dbName}_schema_*.json in the workspace dir
 *  3. Most-recently-modified file matching {dbName}_schema_*.json in the supportingFiles dir
 *  4. Most-recently-modified file matching {dbName}_schema_*.json in the default metadata dir
 */
@Service
public class SchemaFileReaderService {

    private static final Logger log = LoggerFactory.getLogger(SchemaFileReaderService.class);

    /** Prefix pattern for schema files: {dbName}_schema_ */
    private static final String SCHEMA_PREFIX = "_schema_";

    @Autowired
    private MetadataDirectoryResolver dirResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns true when {databaseName}_Schema_1.0.0.json exists on disk.
     * Used by callers to decide whether to read the file or fall back to JDBC.
     */
    public boolean schemaFileExists(String databaseName) {
        return resolveSchemaFile(databaseName) != null;
    }

    /**
     * Returns all table names listed in the schema JSON file.
     * Returns empty Optional when the file does not exist.
     *
     * Response shape (passed through to controller):
     * {
     *   "databaseName" : "ecommerce",
     *   "schemaName"   : "public",
     *   "totalTables"  : 6,
     *   "tableNames"   : ["employees", "departments", ...]
     * }
     */
    public Optional<Map<String, Object>> getTableNames(String databaseName) {
        return loadRoot(databaseName).map(root -> {
            List<String> names = extractTableNames(root);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("databaseName", databaseName);
            result.put("schemaName",   root.getOrDefault("schema_name", "public"));
            result.put("totalTables",  names.size());
            result.put("tableNames",   names);
            return result;
        });
    }

    /**
     * Returns all column details for a specific table from the schema JSON file.
     * Returns empty Optional when the file does not exist or the table is not found.
     *
     * Response shape:
     * {
     *   "databaseName"  : "ecommerce",
     *   "schemaName"    : "public",
     *   "tableName"     : "employees",
     *   "description"   : "Stores employee records",
     *   "primaryKey"    : ["id"],
     *   "columnCount"   : 10,
     *   "columns"       : [
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
    public Optional<Map<String, Object>> getTableColumns(String databaseName, String tableName) {
        return loadRoot(databaseName).flatMap(root -> {
            Optional<Map<String, Object>> tableOpt = findTable(root, tableName);
            if (tableOpt.isEmpty()) {
                log.warn("Table '{}' not found in schema file for database '{}'",
                        tableName, databaseName);
                return Optional.empty();
            }

            Map<String, Object> table = tableOpt.get();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns =
                    (List<Map<String, Object>>) table.getOrDefault("columns", Collections.emptyList());

            @SuppressWarnings("unchecked")
            List<String> primaryKey =
                    (List<String>) table.getOrDefault("primary_key", Collections.emptyList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("databaseName",  databaseName);
            result.put("schemaName",    root.getOrDefault("schema_name", "public"));
            result.put("tableName",     table.get("table_name"));
            result.put("description",   table.getOrDefault("description", ""));
            result.put("primaryKey",    primaryKey);
            result.put("columnCount",   columns.size());
            result.put("columns",       columns);

            log.info("SchemaFileReaderService: returned {} columns for table '{}' from schema",
                    columns.size(), tableName);

            return Optional.of(result);
        });
    }

    /**
     * Returns full table metadata (including columns) for a single table.
     * Returns empty Optional when the file or table is not found.
     */
    public Optional<Map<String, Object>> getTableDetail(String databaseName, String tableName) {
        return getTableColumns(databaseName, tableName);
    }

    /**
     * Exposes the raw root map — used by SchemaExtractionService for EAV config merge.
     */
    public Optional<Map<String, Object>> getRawRoot(String databaseName) {
        return loadRoot(databaseName);
    }

    /**
     * Scans {databaseName}_Schema_1.0.0.json and returns a summary of which
     * tables are missing a description (null, blank, or still the default
     * "{tableName} table" placeholder generated by JDBC extraction).
     *
     * Response map keys:
     *   databaseName              – echoed back
     *   totalTables               – total tables in file
     *   tablesWithDescription     – count with a real description
     *   tablesMissingDescription  – count missing description
     *   missingTables             – List<String> of table names missing description
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> checkMissingDescriptions(String databaseName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("databaseName", databaseName);

        Optional<Map<String, Object>> rootOpt = loadRoot(databaseName);
        if (rootOpt.isEmpty()) {
            result.put("totalTables", 0);
            result.put("tablesWithDescription", 0);
            result.put("tablesMissingDescription", 0);
            result.put("missingTables", Collections.emptyList());
            result.put("error", "Schema file not found for database '" + databaseName
                    + "'. Please connect to the database first.");
            return result;
        }

        Map<String, Object> root = rootOpt.get();
        Object tablesObj = root.get("tables");
        if (!(tablesObj instanceof List)) {
            result.put("totalTables", 0);
            result.put("tablesWithDescription", 0);
            result.put("tablesMissingDescription", 0);
            result.put("missingTables", Collections.emptyList());
            return result;
        }

        List<Map<String, Object>> tables = (List<Map<String, Object>>) tablesObj;
        List<String> missingTables = new ArrayList<>();

        for (Map<String, Object> table : tables) {
            String tableName    = (String) table.get("table_name");
            String description  = (String) table.get("description");
            if (isMissingDescription(tableName, description)) {
                missingTables.add(tableName);
            }
        }

        int total   = tables.size();
        int missing = missingTables.size();
        int present = total - missing;

        result.put("totalTables",               total);
        result.put("tablesWithDescription",     present);
        result.put("tablesMissingDescription",  missing);
        result.put("missingTables",             missingTables);
        return result;
    }

    /**
     * Returns true when the description is considered missing:
     *  - null or blank
     *  - still the auto-generated placeholder: "{tableName} table"
     */
    private boolean isMissingDescription(String tableName, String description) {
        if (description == null || description.isBlank()) return true;
        // auto-generated placeholder produced by DatabaseSchemaExportService
        if (tableName != null && description.trim().equalsIgnoreCase(tableName.trim() + " table")) return true;
        return false;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Loads and parses the JSON file for the given database.
     * Returns empty Optional when the file does not exist or cannot be parsed.
     */
    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> loadRoot(String databaseName) {
        Path file = resolveSchemaFile(databaseName);
        if (file == null) {
            log.debug("No schema file found for database: {}", databaseName);
            return Optional.empty();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(file.toFile(), Map.class);
            log.debug("Loaded schema file: {}", file.getFileName());
            return Optional.of(root);
        } catch (IOException e) {
            log.error("Failed to parse schema file '{}': {}", file.toAbsolutePath(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolves the schema file for the given database using multi-step fallback:
     * 1. Special case: root_ecommerce_DBSchema.json for ecommerce database
     * 2. Latest filename registered in MetadataDirectoryResolver (current session)
     * 3. Most-recently-modified {dbName}_schema_*.json in any user subdirectory
     *    under supportingFiles/
     * 4. Most-recently-modified {dbName}_schema_*.json in root supportingFiles dir
     * 5. Most-recently-modified {dbName}_schema_*.json in default metadata dir
     */
    private Path resolveSchemaFile(String databaseName) {
        // Special case: Check for root_ecommerce_DBSchema.json if requesting ecommerce db
        if ("ecommerce".equalsIgnoreCase(databaseName)) {
            Path rootEcommercePath = dirResolver.getSupportingFilesDir()
                    .resolve("root")
                    .resolve("root_ecommerce_DBSchema.json");
            if (Files.exists(rootEcommercePath)) {
                log.debug("Using root ecommerce schema file: {}", rootEcommercePath);
                return rootEcommercePath;
            }
        }

        // Step 1: Use registered filename (fastest — exact file known)
        String registeredFilename = dirResolver.getLatestSchemaFilename(databaseName);
        if (registeredFilename != null) {
            // Scan all user subdirectories under supportingFiles
            Path found = findInUserSubdirs(registeredFilename);
            if (found != null) return found;
            // Check root supportingFiles dir
            Path supportPath = dirResolver.getSupportingFilesDir().resolve(registeredFilename);
            if (Files.exists(supportPath)) return supportPath;
            // Check default metadata dir
            Path defaultPath = dirResolver.getDefaultDir().resolve(registeredFilename);
            if (Files.exists(defaultPath)) return defaultPath;
        }

        // Step 2: Glob in all user subdirectories under supportingFiles
        Path supportDir = dirResolver.getSupportingFilesDir();
        try {
            if (Files.exists(supportDir)) {
                Path best = null;
                java.nio.file.attribute.FileTime bestTime = null;
                try (var dirs = Files.list(supportDir)) {
                    List<Path> subdirs = dirs.filter(Files::isDirectory)
                            .collect(java.util.stream.Collectors.toList());
                    for (Path sub : subdirs) {
                        Path found = findLatestSchemaFile(databaseName, sub);
                        if (found != null) {
                            java.nio.file.attribute.FileTime ft = Files.getLastModifiedTime(found);
                            if (best == null || ft.compareTo(bestTime) > 0) {
                                best = found;
                                bestTime = ft;
                            }
                        }
                    }
                }
                if (best != null) return best;
            }
        } catch (IOException e) {
            log.warn("Error scanning supportingFiles subdirs: {}", e.getMessage());
        }

        // Step 3: Glob in root supportingFiles dir
        Path found = findLatestSchemaFile(databaseName, supportDir);
        if (found != null) return found;

        // Step 4: Glob in default metadata dir
        Path defaultDir = dirResolver.getDefaultDir();
        found = findLatestSchemaFile(databaseName, defaultDir);
        if (found != null) return found;

        return null;
    }

    /**
     * Looks for a specific filename in all user subdirectories under supportingFiles/.
     */
    private Path findInUserSubdirs(String filename) {
        Path supportDir = dirResolver.getSupportingFilesDir();
        if (!Files.exists(supportDir)) return null;
        try (var dirs = Files.list(supportDir)) {
            return dirs.filter(Files::isDirectory)
                    .map(d -> d.resolve(filename))
                    .filter(Files::exists)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Scans {@code dir} for files matching {databaseName}_schema_*.json
     * and returns the most recently modified one.
     */
    private Path findLatestSchemaFile(String databaseName, Path dir) {
        if (dir == null || !Files.exists(dir)) return null;
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

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> findTable(Map<String, Object> root, String tableName) {
        Object tablesObj = root.get("tables");
        if (!(tablesObj instanceof List)) return Optional.empty();

        List<Map<String, Object>> tables = (List<Map<String, Object>>) tablesObj;
        return tables.stream()
                .filter(t -> tableName.equalsIgnoreCase((String) t.get("table_name")))
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTableNames(Map<String, Object> root) {
        Object tablesObj = root.get("tables");
        if (!(tablesObj instanceof List)) return Collections.emptyList();

        List<Map<String, Object>> tables = (List<Map<String, Object>>) tablesObj;
        List<String> names = new ArrayList<>();
        for (Map<String, Object> t : tables) {
            Object name = t.get("table_name");
            if (name instanceof String) names.add((String) name);
        }
        return names;
    }
}
