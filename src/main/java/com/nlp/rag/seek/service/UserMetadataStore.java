package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nlp.rag.seek.model.UserMetadataRequest;
import com.nlp.rag.seek.model.UserMetadataRequest.ColumnInfo;
import com.nlp.rag.seek.model.UserMetadataRequest.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Updates user-supplied table / column descriptions directly into the
 * {DBname}_schema_{timestamp}.json file.
 *
 * Directory resolution is delegated to {@link MetadataDirectoryResolver}
 * which searches supportingFiles/{userName}/ subdirectories and the
 * default metadata dir.
 */
@Service
public class UserMetadataStore {

    private static final Logger log = LoggerFactory.getLogger(UserMetadataStore.class);

    /** Prefix pattern for schema files: {dbName}_schema_ */
    private static final String SCHEMA_PREFIX = "_schema_";

    @Autowired
    private MetadataDirectoryResolver dirResolver;

    private final ObjectMapper objectMapper;

    public UserMetadataStore() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    // =========================================================================
    // Save — updates the {DBname}_schema_{timestamp}.json in-place
    // =========================================================================

    /**
     * Validates the request and updates the table section inside the
     * {databaseName}_Schema_1.0.0.json file found in the classpath metadata directory.
     *
     * <ul>
     *   <li>Sets {@code description} on the matching table entry.</li>
     *   <li>Sets {@code description} on each matching column entry.</li>
     * </ul>
     *
     * @param request  the validated POST body
     * @return         the path of the updated schema file (absolute)
     * @throws IllegalArgumentException when required fields are missing
     * @throws IOException              when the schema file cannot be read/written
     */
    @SuppressWarnings("unchecked")
    public Path save(UserMetadataRequest request) throws IOException {

        // ── Validate ──────────────────────────────────────────────────────────
        if (request.getTableMetadata() == null
                || request.getTableMetadata().getTableName() == null
                || request.getTableMetadata().getTableName().isBlank()) {
            throw new IllegalArgumentException(
                    "tableMetadata.tableName is required and must not be blank");
        }
        if (request.getDatabaseName() == null || request.getDatabaseName().isBlank()) {
            throw new IllegalArgumentException("databaseName is required and must not be blank");
        }

        TableInfo        tableInfo   = request.getTableMetadata();
        List<ColumnInfo> columnInfos = request.getColumnMetadata() != null
                                        ? request.getColumnMetadata()
                                        : Collections.emptyList();

        String databaseName = request.getDatabaseName().trim();
        String tableName    = tableInfo.getTableName().trim().toLowerCase();

        // ── Locate the most recent schema file ────────────────────────────────
        Path schemaFile = resolveSchemaFile(databaseName);
        if (schemaFile == null) {
            throw new IOException("Schema file not found for database '" + databaseName
                    + "'. Please connect to the database first via POST /api/v1/admin/database/connect.");
        }

        // ── Load schema JSON ──────────────────────────────────────────────────
        Map<String, Object> root = objectMapper.readValue(schemaFile.toFile(), Map.class);

        // ── Find the target table ─────────────────────────────────────────────
        Object tablesObj = root.get("tables");
        if (!(tablesObj instanceof List)) {
            throw new IOException("Schema file does not contain a 'tables' array: " + schemaFile);
        }
        List<Map<String, Object>> tables = (List<Map<String, Object>>) tablesObj;

        Map<String, Object> targetTable = tables.stream()
                .filter(t -> tableName.equalsIgnoreCase((String) t.get("table_name")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Table '" + tableName + "' not found in schema file for database '"
                                + databaseName + "'"));

        // ── Update table description ───────────────────────────────────────────
        if (tableInfo.getDescription() != null && !tableInfo.getDescription().isBlank()) {
            targetTable.put("description", tableInfo.getDescription().trim());
            log.info("Updated description for table '{}' in '{}'", tableName, schemaFile.getFileName());
        }

        // ── Update business context ───────────────────────────────────────────
        if (tableInfo.getBusinessContext() != null && !tableInfo.getBusinessContext().isBlank()) {
            targetTable.put("business_context", tableInfo.getBusinessContext().trim());
            log.info("Updated business_context for table '{}' in '{}'", tableName, schemaFile.getFileName());
        }

        // ── Update isEavTable flag ────────────────────────────────────────────
        if (tableInfo.getIsEavTable() != null) {
            targetTable.put("is_eav_table", tableInfo.getIsEavTable());
            log.info("Updated is_eav_table={} for table '{}' in '{}'", tableInfo.getIsEavTable(), tableName, schemaFile.getFileName());
        }

        // ── Update column descriptions ────────────────────────────────────────
        if (!columnInfos.isEmpty()) {
            Object columnsObj = targetTable.get("columns");
            if (columnsObj instanceof List) {
                List<Map<String, Object>> columns = (List<Map<String, Object>>) columnsObj;

                // Build a lookup map: columnName (lower) → ColumnInfo
                Map<String, ColumnInfo> colInfoMap = new LinkedHashMap<>();
                for (ColumnInfo ci : columnInfos) {
                    if (ci.getColumnName() != null && !ci.getColumnName().isBlank()) {
                        colInfoMap.put(ci.getColumnName().trim().toLowerCase(), ci);
                    }
                }

                int updatedCols = 0;
                for (Map<String, Object> col : columns) {
                    String colName = ((String) col.get("column_name")).toLowerCase();
                    ColumnInfo ci  = colInfoMap.get(colName);
                    if (ci != null && ci.getDescription() != null) {
                        col.put("description", ci.getDescription().trim());
                        updatedCols++;
                    }
                }
                log.info("Updated descriptions for {}/{} columns in table '{}' in '{}'",
                        updatedCols, columns.size(), tableName, schemaFile.getFileName());
            }
        }

        // ── Write updated JSON back to file ───────────────────────────────────
        objectMapper.writeValue(schemaFile.toFile(), root);
        log.info("Schema file updated: {}", schemaFile.toAbsolutePath());

        // ── Sync the update to supportingFiles dir as well ────────────────────
        syncToSupportingFiles(schemaFile);

        return schemaFile;
    }

    // =========================================================================
    // Load — reads the table section back from the schema file
    // =========================================================================

    /**
     * Reads the table entry from {databaseName}_Schema_1.0.0.json and returns
     * a summary map containing the table description and column descriptions.
     * Returns empty Optional when the schema file does not exist or the table
     * is not found.
     *
     * @param tableName  looks up in all known schema files (by scanning the dir)
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> load(String tableName) {
        if (tableName == null || tableName.isBlank()) return Optional.empty();

        // Search across all schema files in known directories (including user subdirs)
        List<Path> dirs = new ArrayList<>();
        Path supportDir = dirResolver.getSupportingFilesDir();
        // Add all user subdirectories under supportingFiles/
        if (supportDir != null && Files.exists(supportDir)) {
            try (var stream = Files.list(supportDir)) {
                stream.filter(Files::isDirectory).forEach(dirs::add);
            } catch (IOException ignored) {}
            dirs.add(supportDir);  // root supportingFiles/ itself
        }
        dirs.add(dirResolver.getDefaultDir());  // fallback: metadata/

        for (Path dir : dirs) {
            if (!Files.exists(dir)) continue;
            List<Path> schemaFiles = listSchemaFiles(dir);
            for (Path schemaFile : schemaFiles) {
                try {
                    Map<String, Object> root = objectMapper.readValue(schemaFile.toFile(), Map.class);
                    Object tablesObj = root.get("tables");
                    if (!(tablesObj instanceof List)) continue;

                    List<Map<String, Object>> tableList = (List<Map<String, Object>>) tablesObj;
                    Optional<Map<String, Object>> tableOpt = tableList.stream()
                            .filter(t -> tableName.equalsIgnoreCase((String) t.get("table_name")))
                            .findFirst();

                    if (tableOpt.isPresent()) {
                        Map<String, Object> table = tableOpt.get();
                        String databaseName = (String) root.getOrDefault("database_name", "");

                        // Build a summary response
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("databaseName", databaseName);
                        result.put("tableName",    table.get("table_name"));
                        result.put("description",  table.getOrDefault("description", ""));

                        Object columnsObj = table.get("columns");
                        List<Map<String, Object>> colSummary = new ArrayList<>();
                        if (columnsObj instanceof List) {
                            List<Map<String, Object>> cols = (List<Map<String, Object>>) columnsObj;
                            for (Map<String, Object> col : cols) {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("columnName",  col.get("column_name"));
                                entry.put("description", col.getOrDefault("description", ""));
                                colSummary.add(entry);
                            }
                        }
                        result.put("columns", colSummary);
                        return Optional.of(result);
                    }
                } catch (IOException e) {
                    log.error("Failed to read schema file '{}': {}", schemaFile, e.getMessage());
                }
            }
        }

        log.debug("Table '{}' not found in any schema file", tableName);
        return Optional.empty();
    }

    /**
     * Lists all table names found across all {DBname}_schema_*.json files
     * in the metadata and supportingFiles directories.
     */
    @SuppressWarnings("unchecked")
    public List<String> listSavedTables() {
        Set<String> tables = new LinkedHashSet<>();

        List<Path> dirs = new ArrayList<>();
        Path supportDir = dirResolver.getSupportingFilesDir();
        // Add all user subdirectories under supportingFiles/
        if (supportDir != null && Files.exists(supportDir)) {
            try (var stream = Files.list(supportDir)) {
                stream.filter(Files::isDirectory).forEach(dirs::add);
            } catch (IOException ignored) {}
            dirs.add(supportDir);  // root supportingFiles/ itself
        }
        dirs.add(dirResolver.getDefaultDir());  // fallback: metadata/

        for (Path dir : dirs) {
            if (!Files.exists(dir)) continue;
            for (Path schemaFile : listSchemaFiles(dir)) {
                try {
                    Map<String, Object> root = objectMapper.readValue(schemaFile.toFile(), Map.class);
                    Object tablesObj = root.get("tables");
                    if (!(tablesObj instanceof List)) continue;
                    List<Map<String, Object>> tableList = (List<Map<String, Object>>) tablesObj;
                    for (Map<String, Object> t : tableList) {
                        Object name = t.get("table_name");
                        if (name instanceof String) tables.add((String) name);
                    }
                } catch (IOException e) {
                    log.error("Failed to read schema file '{}': {}", schemaFile, e.getMessage());
                }
            }
        }

        List<String> sorted = new ArrayList<>(tables);
        Collections.sort(sorted);
        return sorted;
    }

    /**
     * Returns the absolute path of the metadata directory — useful for diagnostics.
     */
    public String getSaveDirectory() {
        return dirResolver.getDefaultDir().toAbsolutePath().toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Resolves the most-recent schema file for a database using multi-step fallback:
     * 1. Latest registered filename in MetadataDirectoryResolver
     * 2. Glob scan in all user subdirectories under supportingFiles/
     * 3. Glob scan in root supportingFiles dir
     * 4. Glob scan in default metadata dir
     */
    private Path resolveSchemaFile(String databaseName) {
        String registeredFilename = dirResolver.getLatestSchemaFilename(databaseName);
        if (registeredFilename != null) {
            // Search user subdirectories
            Path found = findInUserSubdirs(registeredFilename);
            if (found != null) return found;
            Path supportPath = dirResolver.getSupportingFilesDir().resolve(registeredFilename);
            if (Files.exists(supportPath)) return supportPath;
            Path defaultPath = dirResolver.getDefaultDir().resolve(registeredFilename);
            if (Files.exists(defaultPath)) return defaultPath;
        }

        // Scan all user subdirectories for the latest schema file
        Path supportDir = dirResolver.getSupportingFilesDir();
        Path best = findLatestSchemaFileInSubdirs(databaseName, supportDir);
        if (best != null) return best;

        // Root supportingFiles dir
        Path found = findLatestSchemaFile(databaseName, supportDir);
        if (found != null) return found;

        // Default metadata dir
        Path defaultDir = dirResolver.getDefaultDir();
        found = findLatestSchemaFile(databaseName, defaultDir);
        if (found != null) return found;

        return null;
    }

    /** Looks for a specific filename in all user subdirectories under supportingFiles/. */
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

    /** Finds the latest schema file across all user subdirs under supportDir. */
    private Path findLatestSchemaFileInSubdirs(String databaseName, Path supportDir) {
        if (!Files.exists(supportDir)) return null;
        try {
            Path best = null;
            java.nio.file.attribute.FileTime bestTime = null;
            try (var dirs = Files.list(supportDir)) {
                var subdirs = dirs.filter(Files::isDirectory)
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
            return best;
        } catch (IOException e) {
            return null;
        }
    }

    /** Returns all schema files (matching *_schema_*.json) in the given directory. */
    private List<Path> listSchemaFiles(Path dir) {
        if (!Files.exists(dir)) return Collections.emptyList();
        try {
            List<Path> files = new ArrayList<>();
            Files.list(dir).forEach(p -> {
                String name = p.getFileName().toString().toLowerCase();
                if (name.contains(SCHEMA_PREFIX) && name.endsWith(".json")) {
                    files.add(p);
                }
            });
            return files;
        } catch (IOException e) {
            log.warn("Error listing schema files in '{}': {}", dir, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Finds the most recently modified schema file for a database in a directory. */
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

    /** No-op: schema files are already under supportingFiles/{userName}/. */
    private void syncToSupportingFiles(Path schemaFile) {
        // Files are now written directly into supportingFiles/{userName}/
        // No cross-directory copy needed.
        log.debug("Schema file already in user directory → {}", schemaFile);
    }
}
