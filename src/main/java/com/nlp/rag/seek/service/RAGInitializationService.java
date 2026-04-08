package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.DatabaseSchema;
import com.nlp.rag.seek.model.DatabaseTable;
import com.nlp.rag.seek.model.SchemaChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Manages the RAG pipeline lifecycle.
 *
 * On startup (@PostConstruct) it attempts to restore the last-persisted vector
 * index from disk. If a saved index is found, chunking and embedding are
 * skipped entirely — the pipeline is ready in milliseconds. If no saved index
 * exists (first run, or index was deleted), it falls back to extracting the
 * live schema, chunking, and embedding as normal.
 *
 * Every time initializeFromParsedSql() or initializeRAGPipeline() runs, the
 * new index is automatically saved to disk by VectorStoreService so future
 * restarts are also instant.
 */
@Service
public class RAGInitializationService {

    private static final Logger log = LoggerFactory.getLogger(RAGInitializationService.class);

    /** Database name for the built-in ecommerce schema */
    private static final String ROOT_DB_NAME   = "ecommerce";
    /** Subdirectory under supportingFiles where the root schema is stored */
    private static final String ROOT_SUBDIR    = "root";
    /** Fixed filename for the root ecommerce schema */
    private static final String ROOT_SCHEMA_FILE = "root_ecommerce_DBSchema.json";

    @Autowired private SchemaExtractionService     schemaExtractionService;
    @Autowired private ChunkingService             chunkingService;
    @Autowired private VectorStoreService          vectorStoreService;
    @Autowired private VectorIndexPersistenceService persistenceService;
    @Autowired private DatabaseSchemaExportService schemaExportService;
    @Autowired private MetadataDirectoryResolver   dirResolver;
    @Autowired private com.nlp.rag.seek.config.SecretStore secretStore;

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Value("${genqry.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    // ── Ecommerce DB connection config (from application.properties) ──────────
    @Value("${spring.datasource.secondary.url:jdbc:postgresql://localhost:5432/ecommerce}")
    private String ecommerceJdbcUrl;

    @Value("${spring.datasource.primary.username:postgres}")
    private String ecommerceUsername;

    @Value("${spring.datasource.primary.password:root}")
    private String ecommercePassword;

    /** The last schema that was used to initialise the RAG pipeline. */
    private volatile DatabaseSchema activeSchema;

    // =========================================================================
    // Startup: restore persisted index if available
    // =========================================================================

    /**
     * On every startup, scan the supportingFiles directory for any persisted
     * vector index files. If one is found, restore it directly — no JDBC,
     * no embedding API call, no schema extraction needed.
     *
     * Falls back to schema extraction + fresh indexing only when no saved
     * index is present (first-ever run).
     */
    @PostConstruct
    public void onStartup() {
        log.info("═══ RAG pipeline startup ═══");

        // In secretsfree mode, defer RAG init until bootstrap provides DB credentials
        if (secretStore.isSecretsFreeMode() && !secretStore.isInitialized()) {
            log.info("RAG init deferred — secretsfree mode, waiting for bootstrap");
            log.info("═══ RAG pipeline standby (secretsfree) ═══");
            return;
        }

        // ── Step 1: Root ecommerce schema bootstrap ───────────────────────────
        // Check if supportingFiles/root/root_ecommerce_DBSchema.json exists.
        // • If NOT → connect to the ecommerce PostgreSQL DB and extract the live
        //            schema; write it to the root JSON file; chunk + embed + index.
        //            Falls back to the built-in sample schema when the DB is
        //            unreachable (e.g. local dev without PostgreSQL).
        // • If YES → read the existing JSON and initialise RAG directly
        //            (no JDBC, no re-extraction needed).
        try {
            Path rootDir    = Paths.get(supportingFilesDir).toAbsolutePath().resolve(ROOT_SUBDIR);
            Path schemaFile = rootDir.resolve(ROOT_SCHEMA_FILE);

            if (!Files.exists(schemaFile)) {
                log.info("Root schema not found at '{}' — extracting from ecommerce DB…", schemaFile);

                DatabaseSchema ecommerceSchema = null;

                    // ── 1a. Try to connect to the real ecommerce PostgreSQL DB ─────
                try {
                    // Strip connect/socket timeout params to keep URL clean for DriverManager
                    String jdbcUrl = ecommerceJdbcUrl.replaceAll("\\?.*$", "")
                            + "?connectTimeout=5&socketTimeout=15";
                    Files.createDirectories(rootDir);
                    int tableCount = schemaExportService.extractAndWriteRootSchema(
                            ROOT_DB_NAME, jdbcUrl, ecommerceUsername, ecommercePassword, schemaFile);
                    log.info("Root ecommerce schema extracted from DB: {} tables → {}",
                            tableCount, schemaFile);

                    // Register so SchemaFileReaderService can locate the root JSON
                    dirResolver.registerLatestSchemaFilename(ROOT_DB_NAME, ROOT_SCHEMA_FILE);

                    // Build the in-memory schema from the freshly written JSON
                    ecommerceSchema = schemaExtractionService.buildActiveSchemaFromJson(ROOT_DB_NAME);

                } catch (Exception dbEx) {
                    log.warn("Could not connect to ecommerce DB ({}): {} — falling back to built-in sample schema",
                            ecommerceJdbcUrl, dbEx.getMessage());

                    // ── 1b. Fallback: use built-in sample schema ──────────────
                    //ecommerceSchema = schemaExtractionService.createSampleSchema();
                    log.info("Built ecommerce sample schema: {} tables", ecommerceSchema.getTables().size());

                    // Write the sample as root_ecommerce_DBSchema.json so next
                    // restart uses the file instead of hitting the DB again
                    Files.createDirectories(rootDir);
                    writeRootSchemaJson(ecommerceSchema, schemaFile);
                    log.info("Root ecommerce sample schema written → {}", schemaFile);
                }



                // ── 1c. Chunk + embed + index ─────────────────────────────────
                List<SchemaChunk> chunks = chunkingService.chunkSchema(ecommerceSchema);
                log.info("Ecommerce schema chunked: {} chunk(s)", chunks.size());
                indexChunks(ROOT_DB_NAME, chunks);
                activeSchema = ecommerceSchema;

                log.info("═══ RAG pipeline ready (ecommerce bootstrap) ═══");
                return;

            } else {
                log.info("Root schema exists at '{}' — initialising RAG from file…", schemaFile);

                // Register so SchemaFileReaderService can locate the root JSON
                dirResolver.registerLatestSchemaFilename(ROOT_DB_NAME, ROOT_SCHEMA_FILE);

                // ── Root file already exists → load and initialise RAG ────────
                DatabaseSchema ecommerceSchema =
                        schemaExtractionService.buildActiveSchemaFromJson(ROOT_DB_NAME);

                if (ecommerceSchema != null && !ecommerceSchema.getTables().isEmpty()) {
                    List<SchemaChunk> chunks = chunkingService.chunkSchema(ecommerceSchema);
                    log.info("Ecommerce schema loaded from file: {} tables, {} chunks",
                            ecommerceSchema.getTables().size(), chunks.size());
                    indexChunks(ROOT_DB_NAME, chunks);
                    activeSchema = ecommerceSchema;
                    log.info("═══ RAG pipeline ready (ecommerce from file) ═══");
                    return;
                } else {
                    log.warn("Could not build schema from root file — will try persisted index or standby.");
                }
            }
        } catch (Exception e) {
            log.error("Root ecommerce bootstrap failed: {} — continuing with normal startup",
                    e.getMessage(), e);
        }

        // ── Step 2: Restore any user-uploaded schema's persisted vector index ─
        String savedDb = findPersistedDatabaseName();
        if (savedDb != null) {
            boolean restored = vectorStoreService.restoreFromDisk(savedDb);
            if (restored) {
                try {
                    DatabaseSchema jsonSchema =
                            schemaExtractionService.buildActiveSchemaFromJson(savedDb);
                    if (jsonSchema != null) {
                        activeSchema = jsonSchema;
                        log.info("Active schema loaded from JSON for '{}' ({} tables)",
                                savedDb, activeSchema.getTables().size());
                    } else {
                        log.warn("Could not build schema from JSON for '{}' — activeSchema will be null",
                                savedDb);
                    }
                    log.info("═══ RAG pipeline ready (restored from disk: '{}', {} chunks) ═══",
                            savedDb, vectorStoreService.indexSize());
                } catch (Exception e) {
                    log.warn("Could not rebuild activeSchema after index restore: {}",
                            e.getMessage());
                    log.info("═══ RAG pipeline ready (restored from disk: '{}', {} chunks) ═══",
                            savedDb, vectorStoreService.indexSize());
                }
                return;
            }
        }

        log.info("No persisted vector index found — RAG pipeline not auto-initialized.");
        log.info("Call POST /api/v1/admin/database/schema/upload-sql or " +
                 "POST /api/v1/admin/database/connect to initialize.");
        log.info("═══ RAG pipeline standby ═══");
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the schema that is currently indexed in the vector store.
     * Falls back to a live extraction if the pipeline has not been initialised yet.
     */
    public DatabaseSchema getActiveSchema() {
        if (activeSchema != null) return activeSchema;
        return schemaExtractionService.extractSchema();
    }

    /**
     * Replaces the active schema — called when the vector index is switched
     * to a different database at query time.
     */
    public void setActiveSchema(DatabaseSchema schema) {
        this.activeSchema = schema;
        log.info("Active schema updated to '{}' ({} tables)",
                schema.getDatabaseName(), schema.getTables().size());
    }

    public void initializeRAGPipeline() {
        log.info("═══ RAG pipeline initialisation ═══");
        log.info("Chunking strategy — chunkSize={}, stride={}, overlap={}",
                chunkingService.getChunkSize(),
                chunkingService.getStride(),
                chunkingService.getOverlap());

        List<SchemaChunk> chunks;
        String databaseName;
        try {
            DatabaseSchema schema = schemaExtractionService.extractSchema();
            databaseName = schema.getDatabaseName();
            log.info("Schema '{}' loaded ({} tables)", databaseName, schema.getTables().size());
            chunks = chunkingService.chunkSchema(schema);
            log.info("Produced {} metadata-enriched chunks", chunks.size());
            activeSchema = schema;
            indexChunks(databaseName, chunks);
        } catch (Exception e) {
            log.error("Schema extraction / chunking failed: {} — using built-in sample", e.getMessage());
            //DatabaseSchema sample = schemaExtractionService.createSampleSchema();
            //databaseName = sample.getDatabaseName();
            //chunks = chunkingService.chunkSchema(sample);
           // activeSchema = sample;
        }
        log.info("═══ RAG pipeline ready ═══");
    }

    /**
     * Initialises the RAG pipeline from a {@link DatabaseSchema} that was built
     * directly from an uploaded .sql file or live JDBC extraction.
     * Also persists the resulting index to disk for restart survival.
     */
    public void initializeFromParsedSql(DatabaseSchema schema) {
        initializeFromParsedSql(schema, null);
    }

    /**
     * Initialises the RAG pipeline from a {@link DatabaseSchema}, persisting
     * the vector index under supportingFiles/{userName}/ when userName is supplied.
     */
    public void initializeFromParsedSql(DatabaseSchema schema, String userName) {
        log.info("═══ RAG pipeline initialisation from schema '{}' (user='{}') ═══",
                schema.getDatabaseName(), userName != null ? userName : "root");
        log.info("{} table(s), chunking strategy: chunkSize={}, stride={}, overlap={}",
                schema.getTables().size(),
                chunkingService.getChunkSize(),
                chunkingService.getStride(),
                chunkingService.getOverlap());

        List<SchemaChunk> chunks;
        String databaseName = schema.getDatabaseName();
        try {
            chunks = chunkingService.chunkSchema(schema);
            log.info("Produced {} chunks from schema '{}'", chunks.size(), databaseName);
            activeSchema = schema;
            indexChunks(databaseName, chunks, userName);
        } catch (Exception e) {
            log.error("Chunking failed for schema '{}': {} — using sample",
                    databaseName, e.getMessage());
        }
        log.info("═══ RAG pipeline ready ('{}') ═══", databaseName);
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private void indexChunks(String databaseName, List<SchemaChunk> chunks) {
        indexChunks(databaseName, chunks, null);
    }

    private void indexChunks(String databaseName, List<SchemaChunk> chunks, String userName) {
        try {
            // index(databaseName, chunks, userName) embeds + saves to disk automatically
            vectorStoreService.index(databaseName, chunks, userName);
            log.info("Vector store ready — {} entries indexed ({} mode) user='{}'",
                    vectorStoreService.indexSize(),
                    vectorStoreService.isEmbeddingActive() ? "SEMANTIC" : "KEYWORD-FALLBACK",
                    userName != null ? userName : "root");
        } catch (Exception e) {
            log.error("Embedding/indexing error: {} — attempting keyword-only index", e.getMessage());
            chunks.forEach(c -> c.setEmbedding(null));
            vectorStoreService.index(databaseName, chunks, userName);
            log.warn("Vector store ready in KEYWORD-ONLY mode — {} entries", vectorStoreService.indexSize());
        }
    }

    // =========================================================================
    // Root schema JSON writer
    // =========================================================================

    /**
     * Serialises a {@link DatabaseSchema} into the standard schema JSON format
     * and writes it to {@code dest}.
     *
     * Also reads descriptions from any existing metadata JSON files under
     * the default metadata directory (e.g. employees_metadata.json) and
     * merges them into the written file so user-authored descriptions are
     * not lost.
     *
     * Output structure:
     * {
     *   "database_name": "ecommerce",
     *   "database_type": "SAMPLE",
     *   "schema_version": "1.0.0",
     *   "extracted_at": "...",
     *   "total_tables": N,
     *   "tables": [ { "table_name", "description", "business_context",
     *                 "primary_key", "columns": [...] } ]
     * }
     */
    private void writeRootSchemaJson(DatabaseSchema schema, Path dest) throws IOException {

        // ── Build tables array ────────────────────────────────────────────────
        List<Map<String, Object>> tables = new ArrayList<>();
        for (DatabaseTable table : schema.getTables()) {
            // columns
            List<Map<String, Object>> cols = new ArrayList<>();
            if (table.getColumns() != null) {
                for (com.nlp.rag.seek.model.SchemaColumn col : table.getColumns()) {
                    Map<String, Object> colMap = new LinkedHashMap<>();
                    colMap.put("column_name",    col.getName());
                    colMap.put("data_type",      col.getDataType() != null ? col.getDataType() : "");
                    colMap.put("column_comment", col.getDescription() != null ? col.getDescription() : "");
                    colMap.put("nullable",       col.isNullable());
                    colMap.put("default_value",  null);
                    colMap.put("auto_increment", false);
                    colMap.put("primary_key",    col.isPrimaryKey());
                    colMap.put("foreign_key",    col.getForeignKeyReference() != null ? col.getForeignKeyReference() : "");
                    colMap.put("description",    col.getDescription() != null ? col.getDescription() : "");
                    colMap.put("values",         new ArrayList<>());
                    cols.add(colMap);
                }
            }

            // primary key list
            List<String> pkList = new ArrayList<>();
            if (table.getColumns() != null) {
                table.getColumns().stream()
                     .filter(com.nlp.rag.seek.model.SchemaColumn::isPrimaryKey)
                     .forEach(c -> pkList.add(c.getName()));
            }

            Map<String, Object> tableMap = new LinkedHashMap<>();
            tableMap.put("table_name",       table.getTableName());
            tableMap.put("description",      table.getDescription()    != null ? table.getDescription()    : "");
            tableMap.put("business_context", table.getBusinessContext() != null ? table.getBusinessContext() : "");
            tableMap.put("primary_key",      pkList);
            tableMap.put("columns",          cols);
            tables.add(tableMap);
        }

        // ── Merge descriptions from any existing *_metadata.json files ────────
        mergeMetadataDescriptions(tables, schema.getDatabaseName());

        // ── Build root payload ────────────────────────────────────────────────
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("database_name",  schema.getDatabaseName());
        payload.put("database_type",  "SAMPLE");
        payload.put("schema_version", "1.0.0");
        payload.put("extracted_at",   Instant.now().toString());
        payload.put("total_tables",   tables.size());
        payload.put("tables",         tables);

        // ── Write ─────────────────────────────────────────────────────────────
        Files.createDirectories(dest.getParent());
        objectMapper.writeValue(dest.toFile(), payload);
    }

    /**
     * Reads all *_metadata.json files from the default metadata classpath directory
     * (target/classes/metadata/) and merges their table/column descriptions into
     * the tables list that will be written to root_ecommerce_DBSchema.json.
     */
    @SuppressWarnings("unchecked")
    private void mergeMetadataDescriptions(List<Map<String, Object>> tables, String dbName) {
        try {
            // Try classpath metadata directory first, then src/main/resources/metadata
            List<Path> searchDirs = List.of(
                Paths.get("target/classes/metadata").toAbsolutePath(),
                Paths.get("src/main/resources/metadata").toAbsolutePath()
            );

            for (Path metaDir : searchDirs) {
                if (!Files.exists(metaDir)) continue;

                try (var stream = Files.list(metaDir)) {
                    List<Path> metaFiles = stream
                            .filter(p -> p.getFileName().toString().endsWith("_metadata.json"))
                            .collect(java.util.stream.Collectors.toList());

                    for (Path mf : metaFiles) {
                        try {
                            Map<String, Object> meta = objectMapper.readValue(mf.toFile(), Map.class);
                            String metaTable = (String) meta.get("tableName");
                            if (metaTable == null) continue;

                            String tableDesc = (String) meta.get("description");
                            List<?> metaCols = (List<?>) meta.get("columns");

                            // Find matching table in our list
                            for (Map<String, Object> t : tables) {
                                if (!metaTable.equalsIgnoreCase((String) t.get("table_name"))) continue;

                                // Merge table description if present
                                if (tableDesc != null && !tableDesc.isBlank()) {
                                    t.put("description", tableDesc);
                                    log.debug("Merged table description from '{}' → table '{}'",
                                            mf.getFileName(), metaTable);
                                }

                                // Merge column descriptions
                                if (metaCols != null) {
                                    List<Map<String, Object>> schemaCols =
                                            (List<Map<String, Object>>) t.get("columns");
                                    if (schemaCols == null) continue;

                                    for (Object mc : metaCols) {
                                        if (!(mc instanceof Map)) continue;
                                        Map<String, Object> metaCol = (Map<String, Object>) mc;
                                        String colName = (String) metaCol.get("columnName");
                                        String colDesc = (String) metaCol.get("description");
                                        if (colName == null || colDesc == null) continue;

                                        for (Map<String, Object> sc : schemaCols) {
                                            if (colName.equalsIgnoreCase((String) sc.get("column_name"))) {
                                                sc.put("description", colDesc);
                                                break;
                                            }
                                        }
                                    }
                                }
                                break;
                            }
                        } catch (Exception ex) {
                            log.debug("Could not read metadata file '{}': {}", mf, ex.getMessage());
                        }
                    }
                }
                break; // use first directory that exists
            }
        } catch (Exception e) {
            log.debug("mergeMetadataDescriptions non-fatal: {}", e.getMessage());
        }
    }

    /**
     * Scans the supportingFiles directory (including user subdirectories) for
     * persisted vector index files (vector_index_*.json) and returns the database
     * name of the most recently modified one, or null when none exist.
     */
    private String findPersistedDatabaseName() {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(supportingFilesDir).toAbsolutePath();
            if (!java.nio.file.Files.exists(dir)) return null;

            // Collect all vector_index_*.json files from root AND all subdirectories
            List<java.nio.file.Path> candidates = new ArrayList<>();

            // Root supportingFiles/
            try (var stream = java.nio.file.Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().startsWith("vector_index_")
                              && p.getFileName().toString().endsWith(".json"))
                      .forEach(candidates::add);
            }

            // User subdirectories
            try (var stream = java.nio.file.Files.list(dir)) {
                stream.filter(java.nio.file.Files::isDirectory).forEach(subDir -> {
                    try (var subStream = java.nio.file.Files.list(subDir)) {
                        subStream.filter(p -> p.getFileName().toString().startsWith("vector_index_")
                                          && p.getFileName().toString().endsWith(".json"))
                                 .forEach(candidates::add);
                    } catch (Exception ignored) {}
                });
            }

            if (candidates.isEmpty()) return null;

            java.util.Optional<java.nio.file.Path> latest = candidates.stream()
                    .max(java.util.Comparator.comparing(p -> {
                        try { return java.nio.file.Files.getLastModifiedTime(p); }
                        catch (Exception e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                    }));

            if (latest.isEmpty()) return null;

            // Extract database name from filename: vector_index_{dbName}.json
            String filename = latest.get().getFileName().toString();
            String dbName = filename.substring("vector_index_".length(),
                    filename.length() - ".json".length());
            log.info("Found persisted vector index for database '{}' at {}", dbName, latest.get());
            return dbName;

        } catch (Exception e) {
            log.debug("Could not scan for persisted vector index: {}", e.getMessage());
            return null;
        }
    }
}
