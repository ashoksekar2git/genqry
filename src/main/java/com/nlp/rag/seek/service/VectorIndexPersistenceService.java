package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nlp.rag.seek.model.SchemaChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Persists and restores the in-memory vector index to/from disk so that
 * the RAG pipeline survives server restarts without re-embedding everything.
 *
 * File location:
 *   • With userName: supportingFiles/{userName}/vector_index_{databaseName}.json
 *   • Without userName (root bootstrap): supportingFiles/vector_index_{databaseName}.json
 *
 * Stored format (JSON array of chunk objects):
 * [
 *   {
 *     "chunkId"     : "Students#0",
 *     "tableName"   : "Students",
 *     "columnName"  : null,
 *     "elementType" : "TABLE",
 *     "text"        : "Table: Students ...",
 *     "chunkIndex"  : 0,
 *     "wordStart"   : 0,
 *     "wordEnd"     : 80,
 *     "embedding"   : [0.123, -0.456, ...],   ← float array (null when keyword-only)
 *     "metadata"    : { "table_name": "Students", ... }
 *   },
 *   ...
 * ]
 *
 * Embeddings are stored as plain JSON float arrays so they can be loaded back
 * without any re-embedding API call.
 */
@Service
public class VectorIndexPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexPersistenceService.class);

    private static final String INDEX_PREFIX = "vector_index_";
    private static final String INDEX_SUFFIX = ".json";

    @Value("${seek.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // =========================================================================
    // Save
    // =========================================================================

    /** Save to supportingFiles/{userName}/ when userName is available. */
    public void save(String databaseName, List<SchemaChunk> chunks, String userName) {
        if (databaseName == null || databaseName.isBlank() || chunks == null || chunks.isEmpty()) {
            log.debug("Nothing to persist — skipping vector index save");
            return;
        }

        Path filePath = indexFilePath(databaseName, userName);
        writeChunks(filePath, databaseName, chunks);
    }

    /** Save to root supportingFiles/ (no user context — bootstrap). */
    public void save(String databaseName, List<SchemaChunk> chunks) {
        save(databaseName, chunks, null);
    }

    private void writeChunks(Path filePath, String databaseName, List<SchemaChunk> chunks) {
        try {
            Files.createDirectories(filePath.getParent());

            List<Map<String, Object>> serializable = new ArrayList<>();
            for (SchemaChunk c : chunks) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("chunkId",     c.getChunkId());
                entry.put("tableName",   c.getTableName());
                entry.put("columnName",  c.getColumnName());
                entry.put("elementType", c.getElementType());
                entry.put("text",        c.getText());
                entry.put("chunkIndex",  c.getChunkIndex());
                entry.put("wordStart",   c.getWordStart());
                entry.put("wordEnd",     c.getWordEnd());
                entry.put("metadata",    c.getMetadata());

                // Store embedding as plain float array (null → omit)
                if (c.getEmbedding() != null) {
                    float[] emb = c.getEmbedding();
                    List<Float> embList = new ArrayList<>(emb.length);
                    for (float v : emb) embList.add(v);
                    entry.put("embedding", embList);
                } else {
                    entry.put("embedding", null);
                }

                serializable.add(entry);
            }

            objectMapper.writeValue(filePath.toFile(), serializable);
            log.info("Vector index persisted → {} ({} chunks)", filePath.toAbsolutePath(), chunks.size());

        } catch (IOException e) {
            log.warn("Failed to persist vector index for '{}': {}", databaseName, e.getMessage());
        }
    }

    // =========================================================================
    // Load
    // =========================================================================

    /**
     * Load from supportingFiles/{userName}/ first, then fallback to root supportingFiles/.
     */
    @SuppressWarnings("unchecked")
    public List<SchemaChunk> load(String databaseName, String userName) {
        if (databaseName == null || databaseName.isBlank()) return Collections.emptyList();

        // Try user-specific dir first
        if (userName != null && !userName.isBlank()) {
            Path userPath = indexFilePath(databaseName, userName);
            if (Files.exists(userPath)) {
                return readChunks(userPath, databaseName);
            }
        }
        // Fallback to root supportingFiles/
        Path rootPath = indexFilePath(databaseName);
        if (Files.exists(rootPath)) {
            return readChunks(rootPath, databaseName);
        }

        log.debug("No persisted vector index found for '{}'", databaseName);
        return Collections.emptyList();
    }

    /** Load from root supportingFiles/ only (bootstrap). */
    @SuppressWarnings("unchecked")
    public List<SchemaChunk> load(String databaseName) {
        return load(databaseName, null);
    }

    @SuppressWarnings("unchecked")
    private List<SchemaChunk> readChunks(Path filePath, String databaseName) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(filePath.toFile(), List.class);
            List<SchemaChunk> chunks = new ArrayList<>();

            for (Map<String, Object> entry : raw) {
                SchemaChunk c = new SchemaChunk();
                c.setChunkId    ((String)  entry.get("chunkId"));
                c.setTableName  ((String)  entry.get("tableName"));
                c.setColumnName ((String)  entry.get("columnName"));
                c.setElementType((String)  entry.get("elementType"));
                c.setText       ((String)  entry.get("text"));
                c.setChunkIndex (toInt(entry.get("chunkIndex")));
                c.setWordStart  (toInt(entry.get("wordStart")));
                c.setWordEnd    (toInt(entry.get("wordEnd")));

                Object metaObj = entry.get("metadata");
                if (metaObj instanceof Map) {
                    Map<String, String> meta = new LinkedHashMap<>();
                    ((Map<?, ?>) metaObj).forEach((k, v) ->
                            meta.put(String.valueOf(k), v != null ? String.valueOf(v) : null));
                    c.setMetadata(meta);
                }

                // Restore float[] embedding from JSON List<Float/Double>
                Object embObj = entry.get("embedding");
                if (embObj instanceof List) {
                    List<?> embList = (List<?>) embObj;
                    float[] emb = new float[embList.size()];
                    for (int i = 0; i < embList.size(); i++) {
                        Object val = embList.get(i);
                        emb[i] = val instanceof Number ? ((Number) val).floatValue() : 0f;
                    }
                    c.setEmbedding(emb);
                }

                chunks.add(c);
            }

            log.info("Vector index restored from disk — {} chunks for '{}' from {}",
                    chunks.size(), databaseName, filePath);
            return chunks;

        } catch (Exception e) {
            log.warn("Failed to load persisted vector index for '{}': {}", databaseName, e.getMessage());
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns true if a persisted index file exists for the given database. */
    public boolean exists(String databaseName) {
        if (databaseName == null || databaseName.isBlank()) return false;
        return Files.exists(indexFilePath(databaseName));
    }

    /** Deletes the persisted index file for the given database (e.g. after re-indexing). */
    public void delete(String databaseName) {
        if (databaseName == null || databaseName.isBlank()) return;
        Path p = indexFilePath(databaseName);
        try {
            Files.deleteIfExists(p);
            log.info("Persisted vector index deleted: {}", p);
        } catch (IOException e) {
            log.warn("Could not delete persisted vector index '{}': {}", p, e.getMessage());
        }
    }

    /** Returns the path in root supportingFiles/ (no user context). */
    public Path indexFilePath(String databaseName) {
        return Paths.get(supportingFilesDir)
                .toAbsolutePath()
                .resolve(INDEX_PREFIX + databaseName.trim().toLowerCase() + INDEX_SUFFIX);
    }

    /** Returns the path in supportingFiles/{userName}/ when userName is provided. */
    public Path indexFilePath(String databaseName, String userName) {
        if (userName != null && !userName.isBlank()) {
            String safe = UsernameUtil.sanitize(userName);
            return Paths.get(supportingFilesDir)
                    .toAbsolutePath()
                    .resolve(safe)
                    .resolve(INDEX_PREFIX + databaseName.trim().toLowerCase() + INDEX_SUFFIX);
        }
        return indexFilePath(databaseName);
    }

    private int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        return 0;
    }
}

