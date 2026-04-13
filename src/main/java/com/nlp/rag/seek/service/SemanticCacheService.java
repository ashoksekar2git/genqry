package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.CacheEntry;
import com.nlp.rag.seek.model.SQLGenerationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Semantic Query Cache backed by Redis — TWO-TIER lookup strategy.
 *
 * ══════════════════════════════════════════════════════════════════
 *  TIER 1 — Deterministic Normalized Key  (O(1), no scanning)
 * ══════════════════════════════════════════════════════════════════
 *  QueryNormalizerService collapses synonyms so that:
 *
 *    "list all active employees"    ──┐
 *    "show all active employees"    ──┤──► normalize() ──► "get active employee"
 *    "display the active staff"     ──┘         │
 *                                               ▼
 *                                    SHA-256("get active employee")
 *                                               │
 *                                               ▼
 *                               genqry:cache:<db>:norm:<hash>   ← ONE key
 *
 *  Both queries produce the SAME Redis key → guaranteed HIT on second call.
 *  This tier requires NO embeddings and works even with sk-placeholder.
 *
 * ══════════════════════════════════════════════════════════════════
 *  TIER 2 — Cosine Similarity Scan  (requires real OpenAI key)
 * ══════════════════════════════════════════════════════════════════
 *  For queries that are semantically similar but don't normalize
 *  to the same canonical form:
 *
 *    "how many orders last quarter" vs "order count Q4"
 *
 *  → Embed query → scan genqry:cache:<db>:vec:* → cosine ≥ threshold
 *
 * ══════════════════════════════════════════════════════════════════
 *  STORE
 * ══════════════════════════════════════════════════════════════════
 *  Writes TWO keys per cached response:
 *    genqry:cache:<db>:norm:<hash>   → deterministic, always written
 *    genqry:cache:<db>:vec:<uuid>    → embedding-backed, only with real key
 *  Both carry the same CacheEntry payload with TTL.
 *
 *  Graceful degradation: every Redis call is wrapped in try/catch.
 *  Redis down → cache silently bypassed, pipeline continues normally.
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    @Autowired(required = false)
    private RedisTemplate<String, CacheEntry> redisCacheTemplate;

    @Autowired private VectorStoreService      vectorStoreService;
    @Autowired private QueryNormalizerService  queryNormalizerService;

    @Value("${cache.semantic.enabled:true}")         private boolean enabled;
    @Value("${cache.semantic.similarity-threshold:0.90}") private double similarityThreshold;
    @Value("${cache.semantic.ttl-seconds:3600}")     private long    ttlSeconds;
    @Value("${cache.semantic.key-prefix:genqry:cache:}") private String keyPrefix;
    @Value("${cache.semantic.max-scan:500}")         private int     maxScan;
    @Value("${spring.ai.openai.api-key:NOT_SET}")    private String  openAiApiKey;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Two-tier cache lookup.
     *
     * Tier 1 (always): normalized key O(1) direct Redis GET
     * Tier 2 (with real key): cosine similarity scan over vec:* keys
     *
     * @param sanitizedQuery NL query with PII already masked
     * @param databaseName   metadata filter — scopes search per DB
     */
    public Optional<SQLGenerationResponse> lookup(String sanitizedQuery, String databaseName) {
        if (!isRedisReady()) return Optional.empty();

        // ── TIER 1: Deterministic normalized key lookup ───────────────────────
        Optional<SQLGenerationResponse> tier1 = lookupByNormalizedKey(sanitizedQuery, databaseName);
        if (tier1.isPresent()) return tier1;

        // ── TIER 2: Vector similarity scan (only when embeddings available) ───
        if (!isApiKeyValid()) {
            log.debug("Tier-2 (vector) skipped — no valid OpenAI key");
            return Optional.empty();
        }
        return lookupBySimilarity(sanitizedQuery, databaseName);
    }

    /**
     * Store the response under both a normalized key (Tier 1) and a
     * vector key (Tier 2, only when embeddings available).
     */
    public void store(String sanitizedQuery, SQLGenerationResponse response, String databaseName) {
        if (!isRedisReady()) return;
        if (response == null || response.getError() != null) return;

        String normalized = queryNormalizerService.normalize(sanitizedQuery);
        float[] vec = isApiKeyValid() ? embed(sanitizedQuery) : null;

        CacheEntry entry = new CacheEntry(sanitizedQuery, normalized, response, vec, databaseName);

        // Tier-1 key: deterministic — always written
        String normKey = queryNormalizerService.toCacheKey(keyPrefix, databaseName, sanitizedQuery);
        writeToRedis(normKey, entry);
        log.info("📥 Cache STORE [tier-1/norm] key='{}' canonical='{}' query='{}'",
                normKey, normalized, truncate(sanitizedQuery, 60));

        // Tier-2 key: vector-backed — only when embedding available
        if (vec != null) {
            String vecKey = keyPrefix + databaseName + ":vec:" + UUID.randomUUID();
            writeToRedis(vecKey, entry);
            log.info("📥 Cache STORE [tier-2/vec] key='{}' query='{}'",
                    vecKey, truncate(sanitizedQuery, 60));
        }
    }

    public long evictByDatabase(String databaseName) {
        if (!isRedisReady()) return 0;
        try {
            Set<String> keys = redisCacheTemplate.keys(keyPrefix + databaseName + ":*");
            if (keys == null || keys.isEmpty()) return 0;
            Long count = redisCacheTemplate.delete(keys);
            log.info("🗑  Cache evicted {} entries for db='{}'", count, databaseName);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Cache eviction failed: {}", e.getMessage());
            return 0;
        }
    }

    public long evictAll() {
        if (!isRedisReady()) return 0;
        try {
            Set<String> keys = redisCacheTemplate.keys(keyPrefix + "*");
            if (keys == null || keys.isEmpty()) return 0;
            Long count = redisCacheTemplate.delete(keys);
            log.info("🗑  Cache evicted ALL {} entries", count);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Cache full eviction failed: {}", e.getMessage());
            return 0;
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled",             enabled);
        s.put("redisAvailable",      isRedisUp());
        s.put("similarityThreshold", similarityThreshold);
        s.put("ttlSeconds",          ttlSeconds);
        s.put("keyPrefix",           keyPrefix);
        s.put("tier1_normEnabled",   true);
        s.put("tier2_vecEnabled",    isApiKeyValid());
        if (isRedisUp()) {
            try {
                Set<String> all  = redisCacheTemplate.keys(keyPrefix + "*");
                Set<String> norm = redisCacheTemplate.keys(keyPrefix + "*:norm:*");
                Set<String> vec  = redisCacheTemplate.keys(keyPrefix + "*:vec:*");
                s.put("totalEntries",     all  != null ? all.size()  : 0);
                s.put("normKeyEntries",   norm != null ? norm.size() : 0);
                s.put("vecKeyEntries",    vec  != null ? vec.size()  : 0);
            } catch (Exception e) {
                s.put("totalEntries", "unavailable");
            }
        }
        // Show a sample normalization to make it easy to verify behavior
        s.put("sampleNorm_list",    queryNormalizerService.normalize("list all active employees"));
        s.put("sampleNorm_show",    queryNormalizerService.normalize("show all active employees"));
        s.put("sampleNorm_display", queryNormalizerService.normalize("display the active staff"));
        s.put("sampleNorm_find",    queryNormalizerService.normalize("find active employees"));
        return s;
    }

    // =========================================================================
    // Tier-1: Normalized key lookup
    // =========================================================================

    private Optional<SQLGenerationResponse> lookupByNormalizedKey(String query, String db) {
        try {
            String normKey = queryNormalizerService.toCacheKey(keyPrefix, db, query);
            CacheEntry entry = redisCacheTemplate.opsForValue().get(normKey);

            if (entry == null) {
                log.debug("Tier-1 MISS key='{}'  canonical='{}'",
                        normKey, queryNormalizerService.normalize(query));
                return Optional.empty();
            }

            // HIT
            entry.incrementHitCount();
            writeToRedis(normKey, entry);  // refresh TTL + save updated hitCount

            log.info("✅ Tier-1 HIT [norm-key] key='{}' canonical='{}' hits={}",
                    normKey, entry.getNormalizedQuery(), entry.getHitCount());

            return Optional.of(buildHitResponse(entry, 1.0, "TIER1_NORMALIZED_KEY"));

        } catch (Exception e) {
            log.warn("Tier-1 lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // Tier-2: Vector similarity scan
    // =========================================================================

    private Optional<SQLGenerationResponse> lookupBySimilarity(String query, String db) {
        try {
            float[] queryVec = embed(query);
            if (queryVec == null) return Optional.empty();

            Set<String> keys = redisCacheTemplate.keys(keyPrefix + db + ":vec:*");
            if (keys == null || keys.isEmpty()) {
                log.debug("Tier-2 MISS — no vector entries for db='{}'", db);
                return Optional.empty();
            }

            double bestScore = -1;
            String bestKey = null;
            CacheEntry bestEntry = null;
            int scanned = 0;

            for (String key : keys) {
                if (++scanned > maxScan) break;
                try {
                    CacheEntry e = redisCacheTemplate.opsForValue().get(key);
                    if (e == null || e.getQueryEmbedding() == null) continue;
                    double score = cosine(queryVec, e.getQueryEmbedding());
                    if (score > bestScore) { bestScore = score; bestKey = key; bestEntry = e; }
                } catch (Exception ex) {
                    log.debug("Skipping vec key '{}': {}", key, ex.getMessage());
                }
            }

            if (bestEntry != null && bestScore >= similarityThreshold) {

                // ── Filter-value guard ────────────────────────────────────────
                // Even when the vector similarity is above the threshold, reject
                // the hit if the named entity / filter values extracted from the
                // two queries differ.  This prevents a query like:
                //   "students enrolled in ARTS and Science for last 12 months"
                // from returning the cached SQL for:
                //   "students enrolled in ARTS for last 12 months"
                // because both queries share 90%+ of their embedding mass but
                // require completely different WHERE clauses.
                if (!filterValuesMatch(query, bestEntry.getOriginalQuery())) {
                    log.info("Tier-2 REJECTED [filter-mismatch] score={} ≥ {} but filter values differ" +
                             " | new='{}' cached='{}'",
                            String.format("%.4f", bestScore), similarityThreshold,
                            truncate(query, 60), truncate(bestEntry.getOriginalQuery(), 60));
                    return Optional.empty();
                }

                bestEntry.incrementHitCount();
                writeToRedis(bestKey, bestEntry);

                log.info("✅ Tier-2 HIT [vec-similarity] score={} ≥ {} key='{}' query='{}'",
                        String.format("%.4f", bestScore), similarityThreshold,
                        bestKey, truncate(bestEntry.getOriginalQuery(), 60));

                return Optional.of(buildHitResponse(bestEntry, bestScore, "TIER2_VECTOR_SIMILARITY"));
            }

            log.debug("Tier-2 MISS [bestScore={} < threshold={}] scanned={} vec keys",
                    bestScore < 0 ? "none" : String.format("%.4f", bestScore),
                    similarityThreshold, scanned);
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Tier-2 vector lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SQLGenerationResponse buildHitResponse(CacheEntry entry, double score, String tier) {
        SQLGenerationResponse r = entry.getCachedResponse();
        r.setCacheHit(true);
        r.setCacheSimilarityScore(score);
        r.setCacheHitCount(entry.getHitCount());
        r.setExplanation(String.format("Cache HIT [%s] similarity=%.4f hits=%d",
                tier, score, entry.getHitCount()));
        return r;
    }

    private void writeToRedis(String key, CacheEntry entry) {
        try {
            if (ttlSeconds > 0)
                redisCacheTemplate.opsForValue().set(key, entry, Duration.ofSeconds(ttlSeconds));
            else
                redisCacheTemplate.opsForValue().set(key, entry);
        } catch (Exception e) {
            log.warn("Redis write failed for key '{}': {}", key, e.getMessage());
        }
    }

    private boolean isRedisReady() {
        if (!enabled)                   { log.debug("Semantic cache disabled");        return false; }
        if (redisCacheTemplate == null) { log.debug("RedisTemplate not wired");        return false; }
        return true;
    }

    private boolean isRedisUp() {
        if (redisCacheTemplate == null) return false;
        try {
            redisCacheTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) { return false; }
    }

    @Autowired(required = false)
    private com.nlp.rag.seek.config.SecretStore secretStore;

    private boolean isApiKeyValid() {
        // Check SecretStore first (populated after bootstrap in secretsfree mode)
        String storeKey = secretStore != null ? secretStore.get(com.nlp.rag.seek.config.SecretStore.OPENAI_API_KEY) : null;
        if (storeKey != null && !storeKey.isBlank() && !"NOT_SET".equalsIgnoreCase(storeKey) && storeKey.startsWith("sk-")) {
            return true;
        }
        return openAiApiKey != null
                && !openAiApiKey.isBlank()
                && !openAiApiKey.equals("NOT_SET")
                && !openAiApiKey.startsWith("sk-placeholder")
                && openAiApiKey.startsWith("sk-");
    }

    private float[] embed(String text) { return vectorStoreService.embedText(text); }

    private double cosine(float[] a, float[] b) { return VectorStoreService.cosine(a, b); }

    private String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // =========================================================================
    // Filter-value guard — Fix 2
    // =========================================================================

    /**
     * Returns true when the two queries carry the same set of filter / entity
     * values, meaning it is safe to return the cached SQL for the new query.
     * Returns false when they differ — the cached SQL would use the wrong
     * WHERE clause values and must NOT be served.
     *
     * Extracted value categories:
     *  1. Single-quoted literals     'ARTS', 'Science', 'john@example.com'
     *  2. Double-quoted literals     "New York", "Q4"
     *  3. Capitalised proper nouns   ARTS, Science, HR, Engineering
     *     (contiguous UPPER-case words OR Title-Case words that are not
     *      common English stop words)
     *  4. Standalone integers / decimals  12, 2024, 3.14
     *  5. Ordinal / count words      both, either, all, only, single, multiple
     *     (signals cardinality change even when proper nouns are not present)
     *
     * Comparison is case-insensitive and order-independent (set equality).
     */
    boolean filterValuesMatch(String newQuery, String cachedQuery) {
        Set<String> newFilters    = extractFilterValues(newQuery);
        Set<String> cachedFilters = extractFilterValues(cachedQuery);

        boolean match = newFilters.equals(cachedFilters);

        log.debug("Filter-value check | new={} cached={} match={}",
                newFilters, cachedFilters, match);

        return match;
    }

    /**
     * Extracts all filter / entity values from a natural-language query.
     * All returned strings are lower-cased for case-insensitive comparison.
     */
    private Set<String> extractFilterValues(String query) {
        if (query == null || query.isBlank()) return Collections.emptySet();

        Set<String> values = new LinkedHashSet<>();

        // ── 1. Single-quoted literals: 'ARTS', 'Science' ─────────────────────
        java.util.regex.Matcher sq = java.util.regex.Pattern
                .compile("'([^']+)'")
                .matcher(query);
        while (sq.find()) values.add(sq.group(1).trim().toLowerCase());

        // ── 2. Double-quoted literals: "New York" ─────────────────────────────
        java.util.regex.Matcher dq = java.util.regex.Pattern
                .compile("\"([^\"]+)\"")
                .matcher(query);
        while (dq.find()) values.add(dq.group(1).trim().toLowerCase());

        // ── 3. Capitalised proper nouns ───────────────────────────────────────
        // Matches:
        //   - ALL-CAPS words of 2+ chars:  ARTS, HR, SQL
        //   - Title-Case words of 3+ chars that are NOT common stop words
        Set<String> stopWords = Set.of(
                "the", "and", "for", "from", "with", "that", "this", "who",
                "where", "when", "which", "what", "how", "are", "was", "were",
                "have", "has", "been", "give", "show", "list", "find", "get",
                "both", "either", "all", "only", "last", "past", "next",
                "months", "years", "days", "weeks", "department", "departments",
                "table", "column", "record", "records", "data", "name", "names",
                "student", "students", "employee", "employees", "course", "courses",
                "email", "enrolled", "enrollment", "full", "their", "its",
                "null", "true", "false", "select", "where", "join", "into"
        );

        String[] tokens = query.split("[\\s,;.!?()\\[\\]{}]+");
        for (String token : tokens) {
            if (token.isBlank()) continue;
            // ALL-CAPS token of length ≥ 2  (e.g. ARTS, HR, CS)
            if (token.matches("[A-Z]{2,}")) {
                values.add(token.toLowerCase());
            }
            // Title-Case token of length ≥ 3 that is not a stop word
            else if (token.matches("[A-Z][a-z]{2,}") && !stopWords.contains(token.toLowerCase())) {
                values.add(token.toLowerCase());
            }
        }

        // ── 4. Standalone integers / decimals ─────────────────────────────────
        // e.g. "last 12 months", "past 6 months", "year 2024"
        java.util.regex.Matcher nums = java.util.regex.Pattern
                .compile("\\b(\\d+(?:\\.\\d+)?)\\b")
                .matcher(query);
        while (nums.find()) values.add(nums.group(1));

        // ── 5. Cardinality / conjunction keywords ─────────────────────────────
        // "both ARTS and Science" vs "only ARTS" — the word "both" or "only"
        // changes the cardinality of the filter even if no extra proper noun
        // appears yet.  Capture these so the guard rejects the cache hit.
        Set<String> cardinalityWords = Set.of(
                "both", "either", "neither", "only", "single", "multiple",
                "all", "every", "each", "any", "no", "none"
        );
        String lowerQuery = query.toLowerCase();
        for (String cw : cardinalityWords) {
            // Match as a whole word
            if (java.util.regex.Pattern
                    .compile("\\b" + cw + "\\b")
                    .matcher(lowerQuery).find()) {
                values.add(cw);
            }
        }

        return values;
    }
}
