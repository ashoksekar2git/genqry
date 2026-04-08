package com.nlp.rag.seek.doc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nlp.rag.seek.doc.model.DocumentChunk;
import com.nlp.rag.seek.service.UsernameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Persists and restores the per-user document vector index to/from disk so
 * document RAG survives server restarts without re-embedding.
 *
 * File written:
 *   {supportingFilesDir}/{userName}/doc_vector_index_{userName}.json
 */
@Service
public class DocumentVectorIndexPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorIndexPersistenceService.class);

    private static final String INDEX_PREFIX = "doc_vector_index_";
    private static final String INDEX_SUFFIX = ".json";

    @Value("${genqry.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // =========================================================================
    // Save
    // =========================================================================

    public void save(String userName, List<DocumentChunk> chunks) {
        if (userName == null || userName.isBlank() || chunks == null) return;
        Path filePath = indexFilePath(userName);
        try {
            Files.createDirectories(filePath.getParent());

            List<Map<String, Object>> serializable = new ArrayList<>();
            for (DocumentChunk c : chunks) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("chunkId",    c.getChunkId());
                entry.put("docId",      c.getDocId());
                entry.put("fileName",   c.getFileName());
                entry.put("docType",    c.getDocType());
                entry.put("userName",   c.getUserName());
                entry.put("text",       c.getText());
                entry.put("chunkIndex",   c.getChunkIndex());
                entry.put("wordStart",    c.getWordStart());
                entry.put("wordEnd",      c.getWordEnd());
                entry.put("sectionType",  c.getSectionType());
                entry.put("sectionTitle", c.getSectionTitle());
                entry.put("pageNumber",   c.getPageNumber());
                entry.put("metadata",     c.getMetadata());

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
            log.info("Doc vector index persisted → {} ({} chunks)", filePath, chunks.size());
        } catch (IOException e) {
            log.warn("Failed to persist doc vector index for user '{}': {}", userName, e.getMessage());
        }
    }

    // =========================================================================
    // Load
    // =========================================================================

    @SuppressWarnings("unchecked")
    public List<DocumentChunk> load(String userName) {
        if (userName == null || userName.isBlank()) return Collections.emptyList();
        Path filePath = indexFilePath(userName);
        if (!Files.exists(filePath)) return Collections.emptyList();
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(filePath.toFile(), List.class);
            List<DocumentChunk> chunks = new ArrayList<>();
            for (Map<String, Object> entry : raw) {
                DocumentChunk c = new DocumentChunk();
                c.setChunkId   ((String) entry.get("chunkId"));
                c.setDocId     ((String) entry.get("docId"));
                c.setFileName  ((String) entry.get("fileName"));
                c.setDocType   ((String) entry.get("docType"));
                c.setUserName  ((String) entry.get("userName"));
                c.setText      ((String) entry.get("text"));
                c.setChunkIndex  (toInt(entry.get("chunkIndex")));
                c.setWordStart   (toInt(entry.get("wordStart")));
                c.setWordEnd     (toInt(entry.get("wordEnd")));
                c.setSectionType ((String) entry.getOrDefault("sectionType",  "UNKNOWN"));
                c.setSectionTitle((String) entry.getOrDefault("sectionTitle", ""));
                c.setPageNumber  (toInt(entry.get("pageNumber")));

                Object metaObj = entry.get("metadata");
                if (metaObj instanceof Map) {
                    Map<String, String> meta = new LinkedHashMap<>();
                    ((Map<?, ?>) metaObj).forEach((k, v) ->
                            meta.put(String.valueOf(k), v != null ? String.valueOf(v) : null));
                    c.setMetadata(meta);
                }
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
            log.info("Doc vector index loaded for user '{}' — {} chunks", userName, chunks.size());
            return chunks;
        } catch (Exception e) {
            log.warn("Failed to load doc vector index for user '{}': {}", userName, e.getMessage());
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    public Path indexFilePath(String userName) {
        String safe = UsernameUtil.sanitize(userName);
        return Paths.get(supportingFilesDir)
                .toAbsolutePath()
                .resolve(safe)
                .resolve(INDEX_PREFIX + safe + INDEX_SUFFIX);
    }

    private int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        return 0;
    }
}

