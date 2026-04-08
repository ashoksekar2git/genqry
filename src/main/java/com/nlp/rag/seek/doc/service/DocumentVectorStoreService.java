package com.nlp.rag.seek.doc.service;

import com.nlp.rag.seek.doc.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-process semantic vector store for user-uploaded documents.
 *
 * Completely separate from the SQL-schema VectorStoreService.
 *
 * Each user's documents are indexed in their own namespace:
 *   store[userName] → List<DocumentChunk> (with embeddings)
 *
 * Falls back to keyword search when no valid OpenAI API key is configured.
 * The index is persisted to disk by DocumentVectorIndexPersistenceService
 * after every index() call so it survives server restarts.
 */
@Service
public class DocumentVectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorStoreService.class);

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired
    private DocumentVectorIndexPersistenceService persistenceService;

    @Value("${spring.ai.openai.api-key:NOT_SET}")
    private String openAiApiKey;

    /**
     * Weight for cosine similarity in hybrid scoring: 0.0 = keyword-only, 1.0 = semantic-only.
     * Default 0.6 gives semantic search the majority weight while keyword matching
     * ensures financial/numeric terms (e.g. "federal withheld") are never missed.
     */
    @Value("${genqry.doc-rag.hybrid-alpha:0.6}")
    private double hybridAlpha;

    /**
     * Per-user, per-document chunk index.
     * Key: userName → docId → List<DocumentChunk>
     */
    private final ConcurrentHashMap<String, Map<String, List<DocumentChunk>>> userDocIndex
            = new ConcurrentHashMap<>();

    // =========================================================================
    // Indexing
    // =========================================================================

    /**
     * Embeds and indexes all chunks for a document, then persists to disk.
     * Replaces any previously indexed version of the same docId for this user.
     *
     * @param userName the owning user
     * @param docId    stable document identifier
     * @param chunks   pre-chunked document text produced by DocumentSlidingWindowChunkingService
     */
    public void index(String userName, String docId, List<DocumentChunk> chunks) {
        int embedded = 0, skipped = 0;

        for (DocumentChunk chunk : chunks) {
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
        }

        // Store in memory under user → doc namespace
        userDocIndex
            .computeIfAbsent(userName, u -> new ConcurrentHashMap<>())
            .put(docId, new ArrayList<>(chunks));

        log.info("Document '{}' indexed for user '{}': {} chunks ({} embedded, {} keyword-only)",
                docId, userName, chunks.size(), embedded, skipped);

        if (embedded > 0) {
            log.info("Semantic search ENABLED for doc '{}' — embedding model active", docId);
        } else {
            log.warn("Semantic search DISABLED for doc '{}' — keyword fallback will be used", docId);
        }

        // Persist all docs for this user to disk
        persistenceService.save(userName, getAllChunksForUser(userName));
    }

    /**
     * Attempts to restore a previously persisted index from disk for the given user.
     * Returns true when chunks were successfully restored.
     */
    public boolean restoreFromDisk(String userName) {
        List<DocumentChunk> restored = persistenceService.load(userName);
        if (restored.isEmpty()) {
            log.debug("No persisted doc index found for user '{}'", userName);
            return false;
        }
        // Group by docId and load into memory
        Map<String, List<DocumentChunk>> byDoc = restored.stream()
                .collect(Collectors.groupingBy(DocumentChunk::getDocId));
        userDocIndex.put(userName, new ConcurrentHashMap<>(byDoc));
        log.info("Document index restored for user '{}' — {} docs, {} chunks total",
                userName, byDoc.size(), restored.size());
        return true;
    }

    // =========================================================================
    // Retrieval
    // =========================================================================

    /**
     * Hybrid search: fuses semantic (cosine) similarity with BM25-style keyword TF scoring.
     *
     * finalScore = hybridAlpha * cosineScore + (1 - hybridAlpha) * keywordScore
     *
     * This ensures that financially/numerically specific terms like "federal withheld"
     * are always surfaced even when the cosine similarity alone misses them
     * (e.g. when a table chunk contains numbers but low semantic overlap with the query).
     *
     * Falls back to keyword-only when no embeddings are available.
     */
    public List<ScoredDocChunk> search(String userName, String query, int topK, String docId) {
        List<DocumentChunk> searchable = getChunks(userName, docId);
        if (searchable.isEmpty()) {
            log.warn("No document chunks in store for user='{}' docId='{}'", userName, docId);
            return Collections.emptyList();
        }

        boolean hasEmbeddings = searchable.stream().anyMatch(c -> c.getEmbedding() != null);

        // ── Keyword scores (always computed, even when embeddings available) ──
        Map<String, Double> keywordScores = computeKeywordScores(searchable, query);

        if (!hasEmbeddings) {
            log.warn("No embeddings for user '{}' — using keyword-only search", userName);
            return searchable.stream()
                    .map(c -> new ScoredDocChunk(c, keywordScores.getOrDefault(c.getChunkId(), 0.0)))
                    .filter(s -> s.score() > 0)
                    .sorted(Comparator.comparingDouble(ScoredDocChunk::score).reversed())
                    .limit(topK)
                    .collect(Collectors.toList());
        }

        float[] queryVec = embed(query);
        if (queryVec == null) {
            log.warn("Embedding unavailable for query '{}' — using keyword-only search", query);
            return searchable.stream()
                    .map(c -> new ScoredDocChunk(c, keywordScores.getOrDefault(c.getChunkId(), 0.0)))
                    .filter(s -> s.score() > 0)
                    .sorted(Comparator.comparingDouble(ScoredDocChunk::score).reversed())
                    .limit(topK)
                    .collect(Collectors.toList());
        }

        // ── Hybrid: fuse cosine + keyword scores ──────────────────────────────
        List<ScoredDocChunk> results = searchable.stream()
                .filter(c -> c.getEmbedding() != null)
                .map(c -> {
                    double cosScore     = cosine(queryVec, c.getEmbedding());
                    double kwScore      = keywordScores.getOrDefault(c.getChunkId(), 0.0);
                    double hybridScore  = hybridAlpha * cosScore + (1.0 - hybridAlpha) * kwScore;
                    return new ScoredDocChunk(c, hybridScore);
                })
                .sorted(Comparator.comparingDouble(ScoredDocChunk::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        // Log the top scores to help diagnose retrieval misses
        if (log.isDebugEnabled()) {
            results.forEach(r -> log.debug(
                    "Hybrid chunk='{}' page={} score={:.4f} text='{}'",
                    r.chunk().getChunkId(),
                    r.chunk().getPageNumber(),
                    r.score(),
                    r.chunk().getText().substring(0, Math.min(80, r.chunk().getText().length()))));
        }

        log.info("Hybrid search: user='{}' topK={} retrieved {} chunks (alpha={})",
                userName, topK, results.size(), hybridAlpha);

        return results;
    }

    /**
     * BM25-inspired keyword TF scorer.
     *
     * For each chunk, counts how many distinct query tokens (len > 2) appear in the
     * chunk text (case-insensitive).  Normalises by query token count so scores
     * are in [0, 1].  Also checks the metadata values to catch section titles,
     * page annotations, etc.
     *
     * Numeric tokens (dollar amounts, percentages) are preserved and matched
     * verbatim so "1234" in a query matches "1234.56" in a chunk.
     */
    private Map<String, Double> computeKeywordScores(List<DocumentChunk> chunks, String query) {
        // Tokenise query — keep numeric tokens (important for financial docs)
        String[] qTokens = query.toLowerCase().split("\\s+");
        List<String> meaningful = Arrays.stream(qTokens)
                .filter(t -> t.length() > 2 || t.matches("\\d+\\.?\\d*"))
                .collect(Collectors.toList());

        if (meaningful.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Double> scores = new HashMap<>();
        for (DocumentChunk c : chunks) {
            String haystack = (c.getText() + " "
                    + (c.getSectionTitle() != null ? c.getSectionTitle() : "") + " "
                    + String.join(" ", c.getMetadata().values())).toLowerCase();

            long hits = meaningful.stream()
                    .filter(tok -> haystack.contains(tok))
                    .count();

            if (hits > 0) {
                scores.put(c.getChunkId(), hits / (double) meaningful.size());
            }
        }
        return scores;
    }

    // =========================================================================
    // Index management
    // =========================================================================

    /** Returns all document IDs indexed for a user. */
    public List<String> listDocIds(String userName) {
        Map<String, List<DocumentChunk>> docs = userDocIndex.get(userName);
        if (docs == null) return Collections.emptyList();
        return new ArrayList<>(docs.keySet());
    }

    /** Removes all chunks for a specific document from a user's index. */
    public void removeDocument(String userName, String docId) {
        Map<String, List<DocumentChunk>> docs = userDocIndex.get(userName);
        if (docs != null) {
            docs.remove(docId);
            persistenceService.save(userName, getAllChunksForUser(userName));
            log.info("Document '{}' removed from index for user '{}'", docId, userName);
        }
    }

    /** Returns true if a document is already indexed for this user. */
    public boolean isIndexed(String userName, String docId) {
        Map<String, List<DocumentChunk>> docs = userDocIndex.get(userName);
        return docs != null && docs.containsKey(docId);
    }

    /** Returns the total number of chunks indexed for a user across all documents. */
    public int totalChunks(String userName) {
        return getAllChunksForUser(userName).size();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<DocumentChunk> getChunks(String userName, String docId) {
        Map<String, List<DocumentChunk>> docs = userDocIndex.get(userName);
        if (docs == null) return Collections.emptyList();
        if (docId != null) {
            return docs.getOrDefault(docId, Collections.emptyList());
        }
        // All docs for this user
        return docs.values().stream().flatMap(Collection::stream).collect(Collectors.toList());
    }

    private List<DocumentChunk> getAllChunksForUser(String userName) {
        return getChunks(userName, null);
    }

    private float[] embed(String text) {
        if (embeddingModel == null) return null;
        if (!isApiKeyValid()) return null;
        try {
            List<Double> doubles = embeddingModel.embed(text);
            float[] vec = new float[doubles.size()];
            for (int i = 0; i < doubles.size(); i++) vec[i] = doubles.get(i).floatValue();
            return vec;
        } catch (Exception e) {
            log.warn("Embedding call failed: {}; falling back to keyword search", e.getMessage());
            return null;
        }
    }

    private boolean isApiKeyValid() {
        return openAiApiKey != null
                && !openAiApiKey.isBlank()
                && !openAiApiKey.equals("NOT_SET")
                && !openAiApiKey.startsWith("sk-placeholder")
                && openAiApiKey.startsWith("sk-");
    }


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

    // ── value type ────────────────────────────────────────────────────────────
    public record ScoredDocChunk(DocumentChunk chunk, double score) {}
}

