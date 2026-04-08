package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.SchemaChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * In-process semantic vector store backed by real OpenAI embeddings.
 * Falls back to keyword search when no valid API key is configured.
 *
 * The index is automatically persisted to disk after every index() call
 * and can be restored on startup via restoreFromDisk(), so chunking and
 * embeddings survive server restarts without re-calling the OpenAI API.
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorIndexPersistenceService persistenceService;

    @Value("${spring.ai.openai.api-key:NOT_SET}")
    private String openAiApiKey;

    /** In-memory index: embedded + metadata-enriched chunks */
    private final List<SchemaChunk> index = new ArrayList<>();

    /** Tracks which database is currently indexed — used for persistence. */
    private volatile String currentDatabaseName;

    /** Tracks which genQry user triggered the current index — used for persistence path. */
    private volatile String currentUserName;

    /** Returns true only when a real (non-placeholder) API key is configured */
    private boolean isApiKeyValid() {
        return openAiApiKey != null
                && !openAiApiKey.isBlank()
                && !openAiApiKey.equals("NOT_SET")
                && !openAiApiKey.startsWith("sk-placeholder")
                && openAiApiKey.startsWith("sk-");
    }

    // =========================================================================
    // Indexing
    // =========================================================================

    /**
     * Embed and store all chunks (no database name — used internally / legacy).
     * Persists the index under the last known database name.
     */
    public void index(List<SchemaChunk> chunks) {
        index(currentDatabaseName != null ? currentDatabaseName : "default", chunks);
    }

    /**
     * Embed, store, and persist all chunks for the given database (no user context).
     * Used by root ecommerce bootstrap.
     */
    public void index(String databaseName, List<SchemaChunk> chunks) {
        index(databaseName, chunks, null);
    }

    /**
     * Embed, store, and persist all chunks for the given database + user.
     * Clears the existing index first — safe to call on schema refresh.
     *
     * The resulting index is written to supportingFiles/{userName}/ when
     * userName is provided, otherwise to the root supportingFiles/ directory.
     */
    public void index(String databaseName, List<SchemaChunk> chunks, String userName) {
        this.currentDatabaseName = databaseName;
        this.currentUserName = userName;
        index.clear();
        int embedded = 0, skipped = 0;

        log.info("Indexing {} metadata-enriched chunks for database '{}'...", chunks.size(), databaseName);

        for (SchemaChunk chunk : chunks) {
            try {
                float[] vec = embed(chunk.getText());
                if (vec != null) {
                    chunk.setEmbedding(vec);
                    embedded++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.debug("Embedding skipped for chunk '{}': {}", chunk.getChunkId(), e.getMessage());
                skipped++;
            }
            index.add(chunk);
        }

        log.info("Indexed {} chunks ({} with embeddings, {} keyword-only) into vector store",
                index.size(), embedded, skipped);

        if (embedded > 0) {
            log.info("Semantic search ENABLED — embedding model active");
        } else {
            log.warn("Semantic search DISABLED — keyword fallback will be used for all queries");
        }

        // ── Persist to disk so the index survives restarts ────────────────────
        persistenceService.save(databaseName, new ArrayList<>(index), userName);
    }

    /**
     * Attempts to restore a previously persisted index from disk for the given
     * database name.  Returns true when the index was successfully restored and
     * has at least one chunk; false when no persisted file exists or it is empty.
     *
     * @param databaseName  logical database name (used to find the index file)
     */
    public boolean restoreFromDisk(String databaseName) {
        return restoreFromDisk(databaseName, null);
    }

    /**
     * Restores the persisted index, searching the user directory first.
     */
    public boolean restoreFromDisk(String databaseName, String userName) {
        List<SchemaChunk> restored = persistenceService.load(databaseName, userName);
        if (restored.isEmpty()) {
            log.info("No persisted vector index found for '{}' — fresh indexing required", databaseName);
            return false;
        }
        index.clear();
        index.addAll(restored);
        this.currentDatabaseName = databaseName;

        long withEmbedding = index.stream().filter(c -> c.getEmbedding() != null).count();
        log.info("Vector index restored from disk for '{}' — {} chunks ({} with embeddings, {} keyword-only)",
                databaseName, index.size(), withEmbedding, index.size() - withEmbedding);
        return true;
    }

    /**
     * Returns the name of the database currently loaded in the vector store.
     */
    public String getCurrentDatabaseName() {
        return currentDatabaseName;
    }

    // =========================================================================
    // Retrieval
    // =========================================================================

    /**
     * Find the top-k most semantically similar chunks to the NL query.
     *
     * Uses cosine similarity on embedding vectors when available;
     * falls back to keyword matching otherwise.
     *
     * For EAV tables, prioritizes EAV_ATTRIBUTE chunks that match attributes
     * mentioned in the query to avoid sending the entire massive EAV table schema.
     *
     * @param query NL query string
     * @param topK  max results to return
     */
    public List<ScoredChunk> search(String query, int topK) {
        if (index.isEmpty()) {
            log.warn("Vector store is empty — run indexing first");
            return Collections.emptyList();
        }

        // If no chunks have embeddings (no API key), go straight to keyword fallback
        boolean hasEmbeddings = index.stream().anyMatch(c -> c.getEmbedding() != null);
        if (!hasEmbeddings) {
            log.warn("No embeddings in index — using keyword fallback for query '{}'", query);
            return keywordFallback(query, topK);
        }

        float[] queryVec = embed(query);
        if (queryVec == null) {
            log.warn("Embedding unavailable for query '{}' — using keyword fallback", query);
            return keywordFallback(query, topK);
        }

        List<ScoredChunk> results = index.stream()
                .filter(c -> c.getEmbedding() != null)
                .map(c -> new ScoredChunk(c, cosine(queryVec, c.getEmbedding())))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        // EAV attribute filtering: boost scores for EAV_ATTRIBUTE chunks that match
        // attributes mentioned in the query
        results = applyEavAttributeFiltering(query, results);

        log.debug("Semantic search '{}' → top {} results:", query, results.size());
        results.forEach(s -> log.debug("  [score={:.3f}] {} | table={} col={}",
                s.score(),
                s.chunk().getChunkId(),
                s.chunk().getMetadata().getOrDefault("table_name", "?"),
                s.chunk().getMetadata().getOrDefault("column_name", "-")));

        return results;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Calls the embedding model; returns null if model unavailable or call fails. */
    private float[] embed(String text) {
        if (embeddingModel == null) return null;
        if (!isApiKeyValid()) return null;   // skip HTTP call — no valid key
        try {
            List<Double> doubles = embeddingModel.embed(text);
            float[] vec = new float[doubles.size()];
            for (int i = 0; i < doubles.size(); i++) vec[i] = doubles.get(i).floatValue();
            return vec;
        } catch (Exception e) {
            log.warn("Embedding call failed ({}); falling back to keyword search", e.getMessage());
            return null;
        }
    }

    /** Cosine similarity between two float vectors. */
    public static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Keyword-based fallback: scores each chunk by the fraction of query words
     * (len > 2) that appear in the chunk text OR its metadata values.
     *
     * PII placeholders like __PII_SSN_1__ are stripped before matching so
     * sanitized queries still find relevant schema chunks.
     */
    private List<ScoredChunk> keywordFallback(String query, int topK) {
        // Strip PII placeholders — they add no signal for schema matching
        String cleaned = query.replaceAll("__PII_[A-Z_]+_\\d+__", "").toLowerCase();
        String[] words  = cleaned.split("\\s+");

        return index.stream()
                .map(c -> {
                    String searchable = (c.getText() + " " +
                            String.join(" ", c.getMetadata().values()))
                            .toLowerCase();
                    long hits = Arrays.stream(words)
                            .filter(w -> w.length() > 2 && searchable.contains(w))
                            .count();
                    return new ScoredChunk(c, hits / (double) Math.max(1, words.length));
                })
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * Applies EAV attribute filtering to boost scores for EAV_ATTRIBUTE chunks
     * that match attributes mentioned in the query. This helps narrow down
     * the attr_id involved in the question to avoid sending the entire massive
     * EAV table schema to the LLM.
     */
    private List<ScoredChunk> applyEavAttributeFiltering(String query, List<ScoredChunk> results) {
        // Extract potential attribute names from the query
        Set<String> queryAttributes = extractQueryAttributes(query);
        if (queryAttributes.isEmpty()) {
            return results; // No attributes found in query, return as-is
        }

        log.debug("EAV filtering: query attributes detected: {}", queryAttributes);

        // Boost scores for EAV_ATTRIBUTE chunks that match query attributes
        List<ScoredChunk> filteredResults = new ArrayList<>();
        for (ScoredChunk sc : results) {
            SchemaChunk chunk = sc.chunk();
            double boostedScore = sc.score();

            if ("EAV_ATTRIBUTE".equals(chunk.getElementType())) {
                String eavAttrKey = chunk.getMetadata().get("eav_attribute_key");
                if (eavAttrKey != null && queryAttributes.contains(eavAttrKey.toLowerCase())) {
                    // Boost the score for matching EAV attributes
                    boostedScore = Math.min(1.0, boostedScore * 1.5); // Boost by 50%, cap at 1.0
                    log.debug("EAV attribute match: '{}' boosted from {:.3f} to {:.3f}",
                            eavAttrKey, sc.score(), boostedScore);
                }
            }

            filteredResults.add(new ScoredChunk(chunk, boostedScore));
        }

        // Re-sort by boosted scores
        filteredResults.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return filteredResults;
    }

    /**
     * Extracts potential attribute names from the query by looking for
     * common attribute-related keywords and patterns.
     */
    private Set<String> extractQueryAttributes(String query) {
        Set<String> attributes = new HashSet<>();
        String lowerQuery = query.toLowerCase();

        // Common attribute keywords that might indicate specific attributes
        String[] attrKeywords = {
            "salary", "hire_date", "phone", "email", "address", "department",
            "designation", "age", "dob", "gender", "price", "quantity", "status",
            "name", "value", "type", "code", "description", "date", "amount"
        };

        for (String keyword : attrKeywords) {
            if (lowerQuery.contains(keyword)) {
                attributes.add(keyword);
            }
        }

        // Look for patterns like "employee salary", "product price", etc.
        // This is a simple heuristic - could be enhanced with NLP
        String[] words = lowerQuery.split("\\s+");
        for (int i = 0; i < words.length - 1; i++) {
            if (words[i].matches("employee|product|customer|user|item") &&
                words[i+1].matches("salary|price|name|status|type|code|date")) {
                attributes.add(words[i+1]);
            }
        }

        return attributes;
    }

    public boolean isReady()           { return !index.isEmpty(); }
    public int indexSize()             { return index.size(); }
    public boolean isEmbeddingActive() { return index.stream().anyMatch(c -> c.getEmbedding() != null); }

    /**
     * Surgically replaces all chunks belonging to {@code tableName} with
     * {@code newChunks}, leaving every other table's chunks untouched.
     *
     * Thread-safety: the swap is done inside a synchronized block so an
     * in-flight search sees either the old chunks or the new ones — never a mix.
     *
     * @param tableName  the table whose chunks are being replaced (case-insensitive)
     * @param newChunks  fresh chunks produced after a metadata update
     */
    public synchronized void replaceChunksForTable(String tableName, List<SchemaChunk> newChunks) {
        // Remove all existing chunks for this table
        int removed = 0;
        java.util.Iterator<SchemaChunk> it = index.iterator();
        while (it.hasNext()) {
            SchemaChunk c = it.next();
            if (tableName.equalsIgnoreCase(c.getTableName())) {
                it.remove();
                removed++;
            }
        }
        // Add the new chunks
        index.addAll(newChunks);
        log.info("replaceChunksForTable('{}') — removed {} old, added {} new chunks (index total: {})",
                tableName, removed, newChunks.size(), index.size());
    }

    /**
     * Public accessor for embedding a single text string.
     * Used by SemanticCacheService to embed query strings for cache lookup/store.
     * Returns null if embedding model unavailable or API key invalid.
     */
    public float[] embedText(String text) { return embed(text); }

    // =========================================================================
    // Chunk Inspection — used by CacheAdminController / debug endpoints
    // =========================================================================

    /**
     * Returns an unmodifiable view of ALL indexed chunks.
     * Each chunk includes its text, metadata, chunkId, and whether it has an embedding.
     */
    public List<SchemaChunk> getAllChunks() {
        return Collections.unmodifiableList(index);
    }

    /**
     * Returns a single chunk by its chunkId (e.g. "employees#0", "employees.status#0").
     */
    public Optional<SchemaChunk> getChunkById(String chunkId) {
        return index.stream()
                .filter(c -> chunkId.equals(c.getChunkId()))
                .findFirst();
    }

    /**
     * Returns all chunks for a given table name.
     * Includes both TABLE-level and COLUMN-level chunks.
     */
    public List<SchemaChunk> getChunksByTable(String tableName) {
        return index.stream()
                .filter(c -> tableName.equalsIgnoreCase(c.getTableName()))
                .collect(Collectors.toList());
    }

    /**
     * Returns paginated chunks with optional filters.
     *
     * @param table       filter by table name (null = all tables)
     * @param elementType filter by "TABLE" or "COLUMN" (null = both)
     * @param page        0-based page index
     * @param pageSize    entries per page (max 200)
     */
    public Map<String, Object> listChunks(String table, String elementType, int page, int pageSize) {
        pageSize = Math.min(pageSize, 200);
        page     = Math.max(page, 0);

        List<SchemaChunk> filtered = index.stream()
                .filter(c -> table == null || table.equalsIgnoreCase(c.getTableName()))
                .filter(c -> elementType == null || elementType.equalsIgnoreCase(c.getElementType()))
                .collect(Collectors.toList());

        int total = filtered.size();
        int from  = page * pageSize;
        int to    = Math.min(from + pageSize, total);
        List<SchemaChunk> pageData = from < total ? filtered.subList(from, to) : Collections.emptyList();

        // Build safe view (no raw float[] embedding — too large for HTTP response)
        List<Map<String, Object>> views = pageData.stream().map(this::toView).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total",      total);
        result.put("page",       page);
        result.put("pageSize",   pageSize);
        result.put("totalPages", pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0);
        result.put("chunks",     views);
        return result;
    }

    /**
     * Returns overall index statistics: total chunks, per-table counts,
     * embedding coverage, element type breakdown.
     */
    public Map<String, Object> indexStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalChunks",      index.size());
        stats.put("embeddingActive",  isEmbeddingActive());
        stats.put("chunksWithVector", index.stream().filter(c -> c.getEmbedding() != null).count());
        stats.put("keywordOnly",      index.stream().filter(c -> c.getEmbedding() == null).count());

        // Per-table chunk count
        Map<String, Long> perTable = index.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getTableName() != null ? c.getTableName() : "UNKNOWN",
                        LinkedHashMap::new,
                        Collectors.counting()));
        stats.put("chunksByTable", perTable);

        // Element type breakdown
        Map<String, Long> perType = index.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getElementType() != null ? c.getElementType() : "UNKNOWN",
                        Collectors.counting()));
        stats.put("chunksByType", perType);

        return stats;
    }

    /**
     * Converts a SchemaChunk to an API-safe map — omits the raw float[] embedding vector.
     */
    public Map<String, Object> toView(SchemaChunk c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("chunkId",        c.getChunkId());
        v.put("tableName",      c.getTableName());
        v.put("columnName",     c.getColumnName());
        v.put("elementType",    c.getElementType());
        v.put("text",           c.getText());
        v.put("chunkIndex",     c.getChunkIndex());
        v.put("wordStart",      c.getWordStart());
        v.put("wordEnd",        c.getWordEnd());
        v.put("hasEmbedding",   c.getEmbedding() != null);
        v.put("embeddingDim",   c.getEmbedding() != null ? c.getEmbedding().length : 0);
        v.put("metadata",       c.getMetadata());
        return v;
    }

    // ── value type ────────────────────────────────────────────────────────────

    /**
     * A chunk paired with its similarity score.
     * Callers can access {@code chunk().getMetadata()} for structured schema
     * context to inject into the LLM prompt.
     */
    public record ScoredChunk(SchemaChunk chunk, double score) {}
}
