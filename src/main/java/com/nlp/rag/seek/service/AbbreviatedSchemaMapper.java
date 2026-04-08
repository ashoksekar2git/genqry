package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads, writes, and queries the {@code {dbname}_mapper.json} file that maps
 * abbreviated table/column names to their descriptive equivalents.
 *
 * <h3>JSON structure</h3>
 * <pre>
 * {
 *   "databaseName": "mydb",
 *   "abbreviated": true,
 *   "tables": {
 *     "sch_dtls": {
 *       "descriptiveName": "school_details",
 *       "columns": {
 *         "sch_id": "school_id",
 *         "sch_nm": "school_name",
 *         "addr":   "address"
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>This class is a pure data-access layer — it does NOT call the LLM.
 * The {@code AbbreviatedSchemaDetectionService} populates the mapper and delegates
 * persistence to this class.</p>
 *
 * <p>Thread-safe: all reads/writes go through a {@link ConcurrentHashMap}.</p>
 */
@Service
public class AbbreviatedSchemaMapper {

    private static final Logger log = LoggerFactory.getLogger(AbbreviatedSchemaMapper.class);
    private static final ObjectMapper om = new ObjectMapper();

    @Autowired
    private MetadataDirectoryResolver dirResolver;

    /**
     * In-memory cache: databaseName (lower-case) → mapper data.
     * Populated on first load or after detection completes.
     */
    private final ConcurrentHashMap<String, MapperData> cache = new ConcurrentHashMap<>();

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns {@code true} if the given database has been detected as abbreviated.
     * Auto-loads from disk if not yet cached.
     */
    public boolean isAbbreviated(String databaseName) {
        MapperData data = getOrLoad(databaseName, null);
        return data != null && data.abbreviated;
    }

    /**
     * Ensures the mapper for the given database is loaded from disk into cache.
     * Call this on startup or when switching databases.
     *
     * @return true if a mapper was found and loaded, and the schema is abbreviated
     */
    public boolean ensureLoaded(String databaseName, String userName) {
        MapperData data = getOrLoad(databaseName, userName);
        return data != null && data.abbreviated;
    }

    /**
     * Looks up the descriptive table name for an abbreviated one.
     * Returns the original name if no mapping exists.
     */
    public String descriptiveTableName(String databaseName, String abbreviatedTable) {
        MapperData data = getMapper(databaseName);
        if (data == null || data.tables == null) return abbreviatedTable;
        TableMapping tm = data.tables.get(abbreviatedTable.toLowerCase());
        return tm != null ? tm.descriptiveName : abbreviatedTable;
    }

    /**
     * Looks up the descriptive column name for an abbreviated one within a table.
     * Returns the original name if no mapping exists.
     */
    public String descriptiveColumnName(String databaseName, String abbreviatedTable, String abbreviatedColumn) {
        MapperData data = getMapper(databaseName);
        if (data == null || data.tables == null) return abbreviatedColumn;
        TableMapping tm = data.tables.get(abbreviatedTable.toLowerCase());
        if (tm == null || tm.columns == null) return abbreviatedColumn;
        String desc = tm.columns.get(abbreviatedColumn.toLowerCase());
        return desc != null ? desc : abbreviatedColumn;
    }

    /**
     * Reverse lookup: given a descriptive table name, returns the abbreviated one.
     * Returns the input if no reverse mapping exists.
     */
    public String abbreviatedTableName(String databaseName, String descriptiveTable) {
        MapperData data = getMapper(databaseName);
        if (data == null || data.tables == null) return descriptiveTable;
        for (Map.Entry<String, TableMapping> e : data.tables.entrySet()) {
            if (e.getValue().descriptiveName.equalsIgnoreCase(descriptiveTable)) {
                return e.getKey();
            }
        }
        return descriptiveTable;
    }

    /**
     * Reverse lookup: given a descriptive column name in a descriptive table,
     * returns the abbreviated column name.
     */
    public String abbreviatedColumnName(String databaseName, String descriptiveTable, String descriptiveColumn) {
        MapperData data = getMapper(databaseName);
        if (data == null || data.tables == null) return descriptiveColumn;
        // Find the table by descriptive name
        for (Map.Entry<String, TableMapping> te : data.tables.entrySet()) {
            if (te.getValue().descriptiveName.equalsIgnoreCase(descriptiveTable)) {
                if (te.getValue().columns == null) return descriptiveColumn;
                for (Map.Entry<String, String> ce : te.getValue().columns.entrySet()) {
                    if (ce.getValue().equalsIgnoreCase(descriptiveColumn)) {
                        return ce.getKey();
                    }
                }
            }
        }
        return descriptiveColumn;
    }

    /**
     * Returns all table mappings for the given database, or an empty map.
     * Key = abbreviated table name (lower-case), Value = {@link TableMapping}.
     */
    public Map<String, TableMapping> getAllMappings(String databaseName) {
        MapperData data = getMapper(databaseName);
        if (data == null || data.tables == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(data.tables);
    }

    /**
     * Substitutes all abbreviated table and column names in an SQL string
     * with their descriptive equivalents.
     * Used before sending SQL context to the LLM.
     */
    public String substituteToDescriptive(String databaseName, String sql) {
        MapperData data = getMapper(databaseName);
        if (data == null || !data.abbreviated || data.tables == null) return sql;

        String result = sql;
        // Sort by length descending to avoid partial replacements (e.g. "sch" before "sch_dtls")
        List<Map.Entry<String, TableMapping>> sorted = new ArrayList<>(data.tables.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        for (Map.Entry<String, TableMapping> te : sorted) {
            String abbrevTable = te.getKey();
            String descTable = te.getValue().descriptiveName;

            // Replace table name (word-boundary safe)
            result = replaceWordBoundary(result, abbrevTable, descTable);

            // Replace column names within this table context
            if (te.getValue().columns != null) {
                List<Map.Entry<String, String>> colsSorted = new ArrayList<>(te.getValue().columns.entrySet());
                colsSorted.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
                for (Map.Entry<String, String> ce : colsSorted) {
                    result = replaceWordBoundary(result, ce.getKey(), ce.getValue());
                }
            }
        }
        return result;
    }

    /**
     * Substitutes all descriptive table and column names in an SQL string
     * back to their abbreviated originals.
     * Used after LLM generates SQL with descriptive names.
     */
    public String substituteToAbbreviated(String databaseName, String sql) {
        MapperData data = getMapper(databaseName);
        if (data == null || !data.abbreviated || data.tables == null) return sql;

        String result = sql;
        // Sort by descriptive name length descending
        List<Map.Entry<String, TableMapping>> sorted = new ArrayList<>(data.tables.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().descriptiveName.length(),
                a.getValue().descriptiveName.length()));

        for (Map.Entry<String, TableMapping> te : sorted) {
            String abbrevTable = te.getKey();
            String descTable = te.getValue().descriptiveName;

            // Replace descriptive table name back to abbreviated
            result = replaceWordBoundary(result, descTable, abbrevTable);

            // Replace descriptive column names back to abbreviated
            if (te.getValue().columns != null) {
                List<Map.Entry<String, String>> colsSorted = new ArrayList<>(te.getValue().columns.entrySet());
                colsSorted.sort((a, b) -> Integer.compare(b.getValue().length(), a.getValue().length()));
                for (Map.Entry<String, String> ce : colsSorted) {
                    result = replaceWordBoundary(result, ce.getValue(), ce.getKey());
                }
            }
        }
        return result;
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    /**
     * Saves the mapper data for a database to {@code {dbname}_mapper.json}
     * in the user's supportingFiles directory.
     */
    public void save(String databaseName, MapperData data, String userName) {
        Path dir = (userName != null && !userName.isBlank())
                ? dirResolver.resolveUserDir(userName)
                : dirResolver.getSupportingFilesDir();
        save(databaseName, data, dir);
    }

    /**
     * Saves the mapper data to a specific directory.
     */
    public void save(String databaseName, MapperData data, Path directory) {
        String filename = databaseName.toLowerCase().trim() + "_mapper.json";
        Path filePath = directory.resolve(filename);
        try {
            Files.createDirectories(directory);
            om.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), data);
            cache.put(databaseName.toLowerCase().trim(), data);
            log.info("Abbreviated schema mapper saved → {} ({} table mappings)",
                    filePath, data.tables != null ? data.tables.size() : 0);
        } catch (IOException e) {
            log.error("Failed to save mapper file '{}': {}", filePath, e.getMessage(), e);
        }
    }

    /**
     * Loads the mapper from disk for the given database.
     * Checks: user dir → supportingFiles root → returns null if not found.
     */
    public MapperData load(String databaseName, String userName) {
        String key = databaseName.toLowerCase().trim();
        MapperData cached = cache.get(key);
        if (cached != null) return cached;

        String filename = key + "_mapper.json";

        // Try user directory first
        if (userName != null && !userName.isBlank()) {
            Path userFile = dirResolver.resolveUserDir(userName).resolve(filename);
            MapperData loaded = loadFromFile(userFile);
            if (loaded != null) {
                cache.put(key, loaded);
                return loaded;
            }
        }

        // Try supportingFiles root
        Path rootFile = dirResolver.getSupportingFilesDir().resolve(filename);
        MapperData loaded = loadFromFile(rootFile);
        if (loaded != null) {
            cache.put(key, loaded);
            return loaded;
        }

        return null;
    }

    /**
     * Clears the in-memory cache for a specific database.
     */
    public void evict(String databaseName) {
        cache.remove(databaseName.toLowerCase().trim());
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private MapperData getMapper(String databaseName) {
        return getOrLoad(databaseName, null);
    }

    /**
     * Gets mapper from cache, auto-loading from disk if not cached.
     */
    private MapperData getOrLoad(String databaseName, String userName) {
        if (databaseName == null) return null;
        String key = databaseName.toLowerCase().trim();
        MapperData cached = cache.get(key);
        if (cached != null) return cached;

        // Try to load from disk
        MapperData loaded = load(databaseName, userName);
        return loaded; // load() already populates the cache
    }

    private MapperData loadFromFile(Path filePath) {
        if (!Files.exists(filePath)) return null;
        try {
            MapperData data = om.readValue(filePath.toFile(), MapperData.class);
            log.info("Loaded abbreviated schema mapper from '{}' — {} table mappings",
                    filePath, data.tables != null ? data.tables.size() : 0);
            return data;
        } catch (IOException e) {
            log.warn("Failed to read mapper file '{}': {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Case-insensitive word-boundary replacement.
     * Replaces occurrences of {@code target} that appear as whole words
     * (bounded by non-alphanumeric/underscore characters).
     */
    private String replaceWordBoundary(String input, String target, String replacement) {
        if (target == null || target.isBlank()) return input;
        String regex = "(?i)\\b" + java.util.regex.Pattern.quote(target) + "\\b";
        return input.replaceAll(regex, replacement);
    }

    // =========================================================================
    // Data classes
    // =========================================================================

    /**
     * Root data structure persisted to {@code {dbname}_mapper.json}.
     */
    public static class MapperData {
        public String databaseName;
        public boolean abbreviated;
        /** Key = abbreviated table name (lower-case). */
        public Map<String, TableMapping> tables;

        public MapperData() {}
        public MapperData(String databaseName, boolean abbreviated, Map<String, TableMapping> tables) {
            this.databaseName = databaseName;
            this.abbreviated = abbreviated;
            this.tables = tables;
        }
    }

    /**
     * Mapping for a single table: descriptive name + column mappings.
     */
    public static class TableMapping {
        public String descriptiveName;
        /** Key = abbreviated column name (lower-case), Value = descriptive column name. */
        public Map<String, String> columns;

        public TableMapping() {}
        public TableMapping(String descriptiveName, Map<String, String> columns) {
            this.descriptiveName = descriptiveName;
            this.columns = columns;
        }
    }
}

