package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.CacheEntry;
import com.nlp.rag.seek.model.CacheEntryView;
import com.nlp.rag.seek.model.SQLGenerationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Full Redis cache administration service.
 *
 * Provides all management operations beyond basic lookup/store:
 *
 *  LIST       — paginated listing of cache entries with metadata
 *  GET        — fetch a single entry by Redis key (full SQL response included)
 *  SEARCH     — find entries whose query contains a substring
 *  EVICT ONE  — delete a single key
 *  EVICT DB   — delete all keys for a database
 *  EVICT ALL  — flush entire cache namespace
 *  EVICT TIER — delete only NORM or VEC keys
 *  WARM UP    — pre-populate the cache with a list of known queries
 *  TTL UPDATE — extend / shorten TTL on existing keys
 *  RENAME KEY — update an entry's normalizedQuery and re-key it
 *  HEALTH     — connectivity, memory, and key counts
 *  METRICS    — hit-rate, top-queries by hitCount, tier breakdown
 */
@Service
public class CacheAdminService {

    private static final Logger log = LoggerFactory.getLogger(CacheAdminService.class);

    @Autowired(required = false)
    private RedisTemplate<String, CacheEntry> redisCacheTemplate;

    @Autowired private SemanticCacheService   semanticCacheService;
    @Autowired private QueryNormalizerService queryNormalizerService;
    @Autowired private SQLGenerationService   sqlGenerationService;

    @Value("${cache.semantic.key-prefix:genqry:cache:}")  private String keyPrefix;
    @Value("${cache.semantic.ttl-seconds:3600}")        private long   defaultTtl;
    @Value("${cache.semantic.max-scan:500}")            private int    maxScan;

    // =========================================================================
    // HEALTH
    // =========================================================================

    /**
     * Returns Redis connectivity, memory info, and key-count breakdown.
     */
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("timestamp", Instant.now().toString());

        if (redisCacheTemplate == null) {
            h.put("status", "UNCONFIGURED");
            h.put("message", "RedisTemplate not wired — add spring-boot-starter-data-redis");
            return h;
        }

        try {
            String pong = redisCacheTemplate.getConnectionFactory()
                    .getConnection().ping();
            h.put("status",      "UP");
            h.put("ping",        pong);

            // Key counts
            Set<String> allKeys  = safeKeys(keyPrefix + "*");
            Set<String> normKeys = safeKeys(keyPrefix + "*:norm:*");
            Set<String> vecKeys  = safeKeys(keyPrefix + "*:vec:*");

            h.put("totalKeys",    allKeys  != null ? allKeys.size()  : 0);
            h.put("normKeys",     normKeys != null ? normKeys.size() : 0);
            h.put("vecKeys",      vecKeys  != null ? vecKeys.size()  : 0);

            // Per-database breakdown
            Map<String, Long> perDb = new TreeMap<>();
            if (allKeys != null) {
                for (String k : allKeys) {
                    // key format: genqry:cache:<db>:norm|vec:<hash>
                    String[] parts = k.replaceFirst("^" + keyPrefix, "").split(":");
                    if (parts.length >= 1) perDb.merge(parts[0], 1L, Long::sum);
                }
            }
            h.put("keysByDatabase", perDb);

            // Redis server info (memory)
            try {
                Properties info = redisCacheTemplate.getConnectionFactory()
                        .getConnection().serverCommands().info("memory");
                if (info != null) {
                    h.put("usedMemory",        info.getProperty("used_memory_human", "N/A"));
                    h.put("maxMemory",         info.getProperty("maxmemory_human",   "N/A"));
                    h.put("memFragRatio",      info.getProperty("mem_fragmentation_ratio", "N/A"));
                }
            } catch (Exception e) {
                h.put("memoryInfo", "unavailable");
            }

        } catch (Exception e) {
            h.put("status",  "DOWN");
            h.put("error",   e.getMessage());
        }
        return h;
    }

    // =========================================================================
    // LIST  (paginated)
    // =========================================================================

    /**
     * Returns a paginated list of cache entries.
     *
     * @param db       optional database filter (null = all databases)
     * @param tier     optional tier filter: NORM, VEC, or null (= both)
     * @param page     0-based page index
     * @param pageSize entries per page (max 200)
     * @param sortBy   "hitCount" | "createdAt" | "key" (default: key)
     */
    public Map<String, Object> list(String db, String tier, int page, int pageSize, String sortBy) {
        pageSize = Math.min(pageSize, 200);
        page     = Math.max(page, 0);

        String pattern = buildPattern(db, tier);
        Set<String> keys = safeKeys(pattern);

        if (keys == null || keys.isEmpty()) {
            return pageResponse(Collections.emptyList(), 0, page, pageSize);
        }

        // Load entries (up to maxScan to avoid memory issues)
        List<CacheEntryView> views = new ArrayList<>();
        int loaded = 0;
        for (String key : keys) {
            if (++loaded > maxScan) break;
            try {
                CacheEntry e = redisCacheTemplate.opsForValue().get(key);
                if (e == null) continue;
                Long ttl = redisCacheTemplate.getExpire(key, TimeUnit.SECONDS);
                views.add(CacheEntryView.of(key, e, ttl != null ? ttl : -1L));
            } catch (Exception ex) {
                log.debug("Skipping key '{}': {}", key, ex.getMessage());
            }
        }

        // Sort
        Comparator<CacheEntryView> comp = switch (sortBy != null ? sortBy.toLowerCase() : "key") {
            case "hitcount" -> Comparator.comparingInt(CacheEntryView::getHitCount).reversed();
            case "createdat"-> Comparator.comparing(v -> v.getCreatedAt() != null
                    ? v.getCreatedAt() : Instant.EPOCH, Comparator.reverseOrder());
            case "confidence"->Comparator.comparingDouble(CacheEntryView::getConfidenceScore).reversed();
            default          -> Comparator.comparing(CacheEntryView::getRedisKey);
        };
        views.sort(comp);

        // Paginate
        int total = views.size();
        int from  = page * pageSize;
        int to    = Math.min(from + pageSize, total);
        List<CacheEntryView> page_ = from < total ? views.subList(from, to) : Collections.emptyList();

        return pageResponse(page_, total, page, pageSize);
    }

    // =========================================================================
    // GET (single entry by key)
    // =========================================================================

    /**
     * Returns the full detail of one cache entry including the generated SQL response.
     */
    public Optional<Map<String, Object>> getByKey(String redisKey) {
        if (!redisKey.startsWith(keyPrefix)) return Optional.empty();
        try {
            CacheEntry e = redisCacheTemplate.opsForValue().get(redisKey);
            if (e == null) return Optional.empty();

            Long ttl = redisCacheTemplate.getExpire(redisKey, TimeUnit.SECONDS);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("redisKey",         redisKey);
            detail.put("tier",             redisKey.contains(":norm:") ? "NORM" : "VEC");
            detail.put("databaseName",     e.getDatabaseName());
            detail.put("originalQuery",    e.getOriginalQuery());
            detail.put("normalizedQuery",  e.getNormalizedQuery());
            detail.put("hitCount",         e.getHitCount());
            detail.put("createdAt",        e.getCreatedAt());
            detail.put("ttlRemainingSeconds", ttl != null ? ttl : -1L);
            detail.put("hasEmbedding",     e.getQueryEmbedding() != null);
            detail.put("embeddingDimension",
                    e.getQueryEmbedding() != null ? e.getQueryEmbedding().length : 0);
            detail.put("cachedResponse",   e.getCachedResponse());
            return Optional.of(detail);
        } catch (Exception e) {
            log.warn("Error fetching key '{}': {}", redisKey, e.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // SEARCH
    // =========================================================================

    /**
     * Search entries whose originalQuery or normalizedQuery contains the substring.
     *
     * @param query substring to search for (case-insensitive)
     * @param db    optional database filter
     * @param limit max results
     */
    public List<CacheEntryView> search(String query, String db, int limit) {
        limit = Math.min(limit, 200);
        String needle = query.toLowerCase();
        String pattern = buildPattern(db, null);
        Set<String> keys = safeKeys(pattern);
        if (keys == null || keys.isEmpty()) return Collections.emptyList();

        List<CacheEntryView> results = new ArrayList<>();
        for (String key : keys) {
            if (results.size() >= limit) break;
            try {
                CacheEntry e = redisCacheTemplate.opsForValue().get(key);
                if (e == null) continue;
                boolean matches =
                        (e.getOriginalQuery()   != null && e.getOriginalQuery().toLowerCase().contains(needle)) ||
                        (e.getNormalizedQuery() != null && e.getNormalizedQuery().toLowerCase().contains(needle));
                if (matches) {
                    Long ttl = redisCacheTemplate.getExpire(key, TimeUnit.SECONDS);
                    results.add(CacheEntryView.of(key, e, ttl != null ? ttl : -1L));
                }
            } catch (Exception ex) {
                log.debug("Skipping key '{}': {}", key, ex.getMessage());
            }
        }
        results.sort(Comparator.comparingInt(CacheEntryView::getHitCount).reversed());
        return results;
    }

    // =========================================================================
    // EVICT
    // =========================================================================

    /** Evict a single key */
    public boolean evictOne(String redisKey) {
        if (!redisKey.startsWith(keyPrefix)) return false;
        try {
            Boolean deleted = redisCacheTemplate.delete(redisKey);
            log.info("🗑  Evict ONE key='{}'  deleted={}", redisKey, deleted);
            return Boolean.TRUE.equals(deleted);
        } catch (Exception e) {
            log.warn("Evict ONE failed for '{}': {}", redisKey, e.getMessage());
            return false;
        }
    }

    /** Evict all entries for a database */
    public long evictByDatabase(String db) {
        Set<String> keys = safeKeys(keyPrefix + db + ":*");
        return bulkDelete(keys, "db=" + db);
    }

    /** Evict all NORM-tier entries (keeps VEC entries) */
    public long evictTierNorm(String db) {
        String pattern = db != null ? keyPrefix + db + ":norm:*" : keyPrefix + "*:norm:*";
        return bulkDelete(safeKeys(pattern), "tier=NORM db=" + (db != null ? db : "ALL"));
    }

    /** Evict all VEC-tier entries (keeps NORM entries) */
    public long evictTierVec(String db) {
        String pattern = db != null ? keyPrefix + db + ":vec:*" : keyPrefix + "*:vec:*";
        return bulkDelete(safeKeys(pattern), "tier=VEC db=" + (db != null ? db : "ALL"));
    }

    /** Evict all entries across all databases */
    public long evictAll() {
        return bulkDelete(safeKeys(keyPrefix + "*"), "ALL");
    }

    /** Evict entries older than the given age in seconds */
    public long evictOlderThan(long ageSeconds) {
        Set<String> keys = safeKeys(keyPrefix + "*");
        if (keys == null || keys.isEmpty()) return 0;

        Instant cutoff = Instant.now().minusSeconds(ageSeconds);
        List<String> toDelete = new ArrayList<>();
        for (String key : keys) {
            try {
                CacheEntry e = redisCacheTemplate.opsForValue().get(key);
                if (e != null && e.getCreatedAt() != null && e.getCreatedAt().isBefore(cutoff))
                    toDelete.add(key);
            } catch (Exception ex) { /* skip */ }
        }
        return bulkDelete(new HashSet<>(toDelete), "age>" + ageSeconds + "s");
    }

    /** Evict entries with hitCount = 0 (never used) */
    public long evictZeroHit() {
        Set<String> keys = safeKeys(keyPrefix + "*");
        if (keys == null || keys.isEmpty()) return 0;

        List<String> toDelete = new ArrayList<>();
        for (String key : keys) {
            try {
                CacheEntry e = redisCacheTemplate.opsForValue().get(key);
                if (e != null && e.getHitCount() == 0) toDelete.add(key);
            } catch (Exception ex) { /* skip */ }
        }
        return bulkDelete(new HashSet<>(toDelete), "hitCount=0");
    }

    // =========================================================================
    // TTL MANAGEMENT
    // =========================================================================

    /**
     * Set a new TTL on one or more keys matching a pattern.
     *
     * @param pattern  Redis key pattern (must start with keyPrefix)
     * @param ttlSecs  new TTL in seconds (0 = persist forever)
     * @return number of keys updated
     */
    public long updateTtl(String pattern, long ttlSecs) {
        if (!pattern.startsWith(keyPrefix)) return 0;
        Set<String> keys = safeKeys(pattern);
        if (keys == null || keys.isEmpty()) return 0;

        long updated = 0;
        for (String key : keys) {
            try {
                if (ttlSecs > 0) {
                    redisCacheTemplate.expire(key, Duration.ofSeconds(ttlSecs));
                } else {
                    redisCacheTemplate.persist(key);
                }
                updated++;
            } catch (Exception e) {
                log.debug("TTL update failed for '{}': {}", key, e.getMessage());
            }
        }
        log.info("⏱  TTL updated to {}s on {} keys (pattern='{}')", ttlSecs, updated, pattern);
        return updated;
    }

    /** Inspect TTL remaining for a single key */
    public Map<String, Object> getTtl(String redisKey) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", redisKey);
        try {
            Long ttl = redisCacheTemplate.getExpire(redisKey, TimeUnit.SECONDS);
            r.put("ttlSeconds",  ttl);
            r.put("persistent",  ttl != null && ttl == -1);
            r.put("expired",     ttl != null && ttl == -2);
            r.put("expiresAt",   ttl != null && ttl > 0
                    ? Instant.now().plusSeconds(ttl).toString() : null);
        } catch (Exception e) {
            r.put("error", e.getMessage());
        }
        return r;
    }

    // =========================================================================
    // WARM-UP
    // =========================================================================

    /**
     * Pre-populate the cache by running a list of known queries through
     * the full NL→SQL pipeline. Results are stored in Redis automatically
     * by SQLGenerationService.
     *
     * @param queries      list of NL queries to warm up
     * @param databaseName target database
     * @param topK         RAG top-k to use
     * @return per-query status map
     */
    public List<Map<String, Object>> warmUp(List<String> queries, String databaseName, int topK) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String query : queries) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("query", query);
            status.put("normalizedQuery", queryNormalizerService.normalize(query));
            try {
                SQLGenerationResponse resp = sqlGenerationService.generateSQL(query, databaseName, topK);
                status.put("status",        resp.getError() == null ? "OK" : "ERROR");
                status.put("cacheHit",      resp.isCacheHit());
                status.put("generatedSQL",  resp.getGeneratedSQL());
                status.put("confidence",    resp.getConfidenceLabel());
                status.put("error",         resp.getError());
            } catch (Exception e) {
                status.put("status", "EXCEPTION");
                status.put("error",  e.getMessage());
            }
            results.add(status);
        }
        log.info("🔥 Warm-up complete: {}/{} queries cached",
                results.stream().filter(r -> "OK".equals(r.get("status"))).count(), queries.size());
        return results;
    }

    // =========================================================================
    // METRICS
    // =========================================================================

    /**
     * Returns aggregated metrics: total entries, hit-rate info,
     * top queries by hitCount, and tier breakdown.
     */
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());

        Set<String> allKeys = safeKeys(keyPrefix + "*");
        if (allKeys == null || allKeys.isEmpty()) {
            m.put("totalEntries", 0);
            return m;
        }

        int total = 0, totalHits = 0, zeroHits = 0, normCount = 0, vecCount = 0;
        int maxHits = 0;
        List<Map<String, Object>> topEntries = new ArrayList<>();

        for (String key : allKeys) {
            if (++total > maxScan) break;
            try {
                CacheEntry e = redisCacheTemplate.opsForValue().get(key);
                if (e == null) continue;
                int h = e.getHitCount();
                totalHits += h;
                if (h == 0) zeroHits++;
                if (h > maxHits) maxHits = h;
                if (key.contains(":norm:")) normCount++; else vecCount++;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key",            key);
                entry.put("db",             e.getDatabaseName());
                entry.put("tier",           key.contains(":norm:") ? "NORM" : "VEC");
                entry.put("normalizedQuery",e.getNormalizedQuery());
                entry.put("hitCount",       h);
                entry.put("createdAt",      e.getCreatedAt());
                topEntries.add(entry);
            } catch (Exception ex) { /* skip */ }
        }

        topEntries.sort((a, b) ->
                Integer.compare((int) b.get("hitCount"), (int) a.get("hitCount")));

        m.put("totalEntries",    total);
        m.put("normKeyEntries",  normCount);
        m.put("vecKeyEntries",   vecCount);
        m.put("totalHits",       totalHits);
        m.put("avgHitsPerEntry", total > 0 ? String.format("%.2f", (double) totalHits / total) : "0.00");
        m.put("zeroHitEntries",  zeroHits);
        m.put("maxHitsSingleEntry", maxHits);
        m.put("top10ByHitCount", topEntries.subList(0, Math.min(10, topEntries.size())));
        return m;
    }

    // =========================================================================
    // INSPECT NORMALIZATION
    // =========================================================================

    /**
     * Computes the canonical form and Redis key for a set of queries
     * WITHOUT reading or writing to Redis. Pure normalization check.
     */
    public Map<String, Object> normalizeCheck(List<String> queries, String db) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> uniqueKeys = new LinkedHashSet<>();
        Set<String> uniqueCanonicals = new LinkedHashSet<>();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String q : queries) {
            String canonical = queryNormalizerService.normalize(q);
            String key       = queryNormalizerService.toCacheKey(keyPrefix, db != null ? db : "?", q);
            uniqueKeys.add(key);
            uniqueCanonicals.add(canonical);

            // Check if already cached
            boolean isCached = false;
            try {
                isCached = Boolean.TRUE.equals(redisCacheTemplate.hasKey(key));
            } catch (Exception e) { /* Redis may be down */ }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("query",         q);
            row.put("canonical",     canonical);
            row.put("redisKey",      key);
            row.put("cachedInRedis", isCached);
            rows.add(row);
        }
        result.put("queries",        rows);
        result.put("sameKey",        uniqueKeys.size() == 1);
        result.put("uniqueKeyCount", uniqueKeys.size());
        result.put("uniqueCanonicals", uniqueCanonicals);
        return result;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String buildPattern(String db, String tier) {
        String dbPart   = (db   != null && !db.isBlank())   ? db   : "*";
        String tierPart = (tier != null && !tier.isBlank())
                ? (tier.equalsIgnoreCase("NORM") ? "norm" : "vec")
                : "*";
        return keyPrefix + dbPart + ":" + tierPart + ":*";
    }

    private long bulkDelete(Set<String> keys, String scope) {
        if (keys == null || keys.isEmpty()) return 0;
        try {
            Long count = redisCacheTemplate.delete(keys);
            log.info("🗑  Bulk evict: {} entries ({}) deleted", count, scope);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Bulk delete failed ({}): {}", scope, e.getMessage());
            return 0;
        }
    }

    private Set<String> safeKeys(String pattern) {
        if (redisCacheTemplate == null) return null;
        try {
            return redisCacheTemplate.keys(pattern);
        } catch (Exception e) {
            log.warn("Redis keys({}) failed: {}", pattern, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> pageResponse(List<CacheEntryView> data, int total,
                                              int page, int pageSize) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("data",       data);
        r.put("total",      total);
        r.put("page",       page);
        r.put("pageSize",   pageSize);
        r.put("totalPages", pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0);
        r.put("hasNext",    (page + 1) * pageSize < total);
        r.put("hasPrev",    page > 0);
        return r;
    }
}

