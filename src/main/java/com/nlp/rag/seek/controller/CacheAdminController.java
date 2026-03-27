package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.model.CacheEntryView;
import com.nlp.rag.seek.service.CacheAdminService;
import com.nlp.rag.seek.service.QueryNormalizerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.*;

/**
 * REST controller for all Redis semantic-cache administration.
 *
 * Base path: /api/v1/admin/cache
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │ HEALTH & INFO                                                │
 * │  GET  /health          Redis ping + memory + key counts      │
 * │  GET  /stats           Aggregated metrics (hits, tiers, ...)  │
 * │                                                              │
 * │ LISTING & SEARCH                                             │
 * │  GET  /entries         Paginated list of all cache entries    │
 * │  GET  /entries/{key}   Full detail for one entry             │
 * │  GET  /search          Search entries by query substring     │
 * │                                                              │
 * │ EVICTION                                                     │
 * │  DELETE /entries/{key}              Evict one key            │
 * │  DELETE /evict?db=X                 Evict by database        │
 * │  DELETE /evict?tier=NORM            Evict by tier            │
 * │  DELETE /evict?tier=VEC             Evict by tier            │
 * │  DELETE /evict?db=X&tier=NORM       Evict by db + tier       │
 * │  DELETE /evict/all                  Flush all                │
 * │  DELETE /evict/zero-hit             Evict never-used entries  │
 * │  DELETE /evict/older-than?seconds=N Evict by age             │
 * │                                                              │
 * │ TTL MANAGEMENT                                               │
 * │  GET    /ttl/{key}                  TTL remaining            │
 * │  PATCH  /ttl?pattern=X&seconds=N   Update TTL               │
 * │                                                              │
 * │ WARM-UP                                                      │
 * │  POST   /warmup                    Pre-populate cache        │
 * │                                                              │
 * │ NORMALIZATION                                                │
 * │  POST   /normalize                 Check canonical forms     │
 * └──────────────────────────────────────────────────────────────┘
 */
@RestController
@RequestMapping("/api/v1/admin/cache")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Cache Admin", description = "Semantic query cache inspection and management")
public class CacheAdminController {

    private static final Logger log = LoggerFactory.getLogger(CacheAdminController.class);

    @Autowired private CacheAdminService    cacheAdminService;
    @Autowired private QueryNormalizerService queryNormalizerService;

    // =========================================================================
    // HEALTH & INFO
    // =========================================================================

    /**
     * GET /api/v1/admin/cache/health
     *
     * Redis ping, memory stats, and key-count breakdown.
     *
     * Response example:
     * {
     *   "status": "UP",
     *   "ping": "PONG",
     *   "totalKeys": 24,
     *   "normKeys": 12,
     *   "vecKeys": 12,
     *   "keysByDatabase": { "ecommerce": 20, "production": 4 },
     *   "usedMemory": "2.45M",
     *   "maxMemory": "0B"
     * }
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(cacheAdminService.health());
    }

    /**
     * GET /api/v1/admin/cache/stats
     *
     * Aggregated metrics: hit counts, tier breakdown, top-10 most-hit entries.
     *
     * Response example:
     * {
     *   "totalEntries": 24,
     *   "normKeyEntries": 12,
     *   "vecKeyEntries": 12,
     *   "totalHits": 87,
     *   "avgHitsPerEntry": "3.63",
     *   "zeroHitEntries": 3,
     *   "maxHitsSingleEntry": 21,
     *   "top10ByHitCount": [...]
     * }
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(cacheAdminService.metrics());
    }

    // =========================================================================
    // LISTING & SEARCH
    // =========================================================================

    /**
     * GET /api/v1/admin/cache/entries
     *
     * Paginated list of cache entries with metadata.
     *
     * Query params:
     *   db       — filter by database name (optional)
     *   tier     — NORM | VEC (optional, default = both)
     *   page     — 0-based page number (default 0)
     *   size     — page size (default 20, max 200)
     *   sortBy   — hitCount | createdAt | confidence | key (default: key)
     *
     * Response: paginated CacheEntryView list (no raw embeddings, no full SQL response)
     */
    @GetMapping("/entries")
    public ResponseEntity<Map<String, Object>> listEntries(
            @RequestParam(required = false)                String  db,
            @RequestParam(required = false)                String  tier,
            @RequestParam(defaultValue = "0")              int     page,
            @RequestParam(name = "size", defaultValue = "20") int  size,
            @RequestParam(defaultValue = "key")            String  sortBy) {

        return ResponseEntity.ok(
                cacheAdminService.list(db, tier, page, size, sortBy));
    }

    /**
     * GET /api/v1/admin/cache/entries/{key}
     *
     * Full detail for a single cache entry by Redis key.
     * Includes the complete cached SQL generation response.
     *
     * Path variable:
     *   key — URL-encoded Redis key, e.g. seek:cache:ecommerce:norm:a3f8c2b1d4e67890
     */
    @GetMapping("/entries/{key}")
    public ResponseEntity<Map<String, Object>> getEntry(
            @PathVariable String key) {

        return cacheAdminService.getByKey(key)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/admin/cache/search?q=employees&db=ecommerce&limit=20
     *
     * Full-text search across originalQuery and normalizedQuery fields.
     *
     * Query params:
     *   q     — search substring (required)
     *   db    — optional database filter
     *   limit — max results (default 20, max 200)
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String q,
            @RequestParam(required = false) String db,
            @RequestParam(defaultValue = "20") int limit) {

        if (q == null || q.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "q parameter is required"));

        List<CacheEntryView> results = cacheAdminService.search(q, db, limit);
        return ResponseEntity.ok(Map.of(
                "query",   q,
                "db",      db != null ? db : "ALL",
                "count",   results.size(),
                "results", results
        ));
    }

    // =========================================================================
    // EVICTION
    // =========================================================================

    /**
     * DELETE /api/v1/admin/cache/entries/{key}
     *
     * Evict a single cache entry by Redis key.
     */
    @DeleteMapping("/entries/{key}")
    public ResponseEntity<Map<String, Object>> evictOne(@PathVariable String key) {
        boolean deleted = cacheAdminService.evictOne(key);
        if (!deleted)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "deleted", true,
                "key",     key,
                "message", "Cache entry evicted"
        ));
    }

    /**
     * DELETE /api/v1/admin/cache/evict
     *
     * Evict entries by database and/or tier filter.
     *
     * Query params (all optional — omitting all evicts nothing; be explicit):
     *   db   — database name
     *   tier — NORM | VEC
     *
     * Examples:
     *   DELETE /evict?db=ecommerce              → evict all ecommerce entries
     *   DELETE /evict?tier=VEC                → evict all VEC-tier entries
     *   DELETE /evict?db=ecommerce&tier=NORM    → evict ecommerce NORM entries only
     */
    @DeleteMapping("/evict")
    public ResponseEntity<Map<String, Object>> evict(
            @RequestParam(required = false) String db,
            @RequestParam(required = false) String tier) {

        if (db == null && tier == null)
            return ResponseEntity.badRequest().body(Map.of(
                    "error",   "Provide at least one of: db, tier",
                    "hint",    "To flush everything use DELETE /evict/all"
            ));

        long count;
        String scope;

        if (db != null && tier != null) {
            count = "NORM".equalsIgnoreCase(tier)
                    ? cacheAdminService.evictTierNorm(db)
                    : cacheAdminService.evictTierVec(db);
            scope = "db=" + db + " tier=" + tier.toUpperCase();
        } else if (db != null) {
            count = cacheAdminService.evictByDatabase(db);
            scope = "db=" + db;
        } else {
            count = "NORM".equalsIgnoreCase(tier)
                    ? cacheAdminService.evictTierNorm(null)
                    : cacheAdminService.evictTierVec(null);
            scope = "tier=" + tier.toUpperCase();
        }

        return ResponseEntity.ok(evictResponse(count, scope));
    }

    /**
     * DELETE /api/v1/admin/cache/evict/all
     *
     * ⚠️  Flush the ENTIRE cache namespace. Use with caution.
     * Requires confirmation header:  X-Confirm-Flush: yes
     */
    @DeleteMapping("/evict/all")
    public ResponseEntity<Map<String, Object>> evictAll(
            @RequestHeader(value = "X-Confirm-Flush", required = false) String confirm) {

        if (!"yes".equalsIgnoreCase(confirm))
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(Map.of(
                    "error",   "Full flush requires confirmation header",
                    "header",  "X-Confirm-Flush: yes",
                    "example", "curl -X DELETE .../evict/all -H 'X-Confirm-Flush: yes'"
            ));

        long count = cacheAdminService.evictAll();
        log.warn("⚠️  FULL CACHE FLUSH — {} entries deleted", count);
        return ResponseEntity.ok(evictResponse(count, "ALL"));
    }

    /**
     * DELETE /api/v1/admin/cache/evict/zero-hit
     *
     * Evict all entries that have never been served as a cache hit (hitCount = 0).
     * Useful periodic cleanup to remove stale entries.
     */
    @DeleteMapping("/evict/zero-hit")
    public ResponseEntity<Map<String, Object>> evictZeroHit() {
        long count = cacheAdminService.evictZeroHit();
        return ResponseEntity.ok(evictResponse(count, "hitCount=0"));
    }

    /**
     * DELETE /api/v1/admin/cache/evict/older-than?seconds=86400
     *
     * Evict all entries created more than N seconds ago.
     *
     * Query params:
     *   seconds — age threshold in seconds (required, e.g. 86400 = 1 day)
     */
    @DeleteMapping("/evict/older-than")
    public ResponseEntity<?> evictOlderThan(
            @RequestParam long seconds) {

        if (seconds <= 0)
            return ResponseEntity.badRequest().body(Map.of("error", "seconds must be > 0"));

        long count = cacheAdminService.evictOlderThan(seconds);
        return ResponseEntity.ok(evictResponse(count, "age>" + seconds + "s"));
    }

    // =========================================================================
    // TTL MANAGEMENT
    // =========================================================================

    /**
     * GET /api/v1/admin/cache/ttl/{key}
     *
     * Inspect the TTL remaining on a specific Redis key.
     *
     * Response:
     * { "key": "...", "ttlSeconds": 2847, "persistent": false,
     *   "expired": false, "expiresAt": "2026-02-25T23:00:00Z" }
     */
    @GetMapping("/ttl/{key}")
    public ResponseEntity<Map<String, Object>> getTtl(@PathVariable String key) {
        return ResponseEntity.ok(cacheAdminService.getTtl(key));
    }

    /**
     * PATCH /api/v1/admin/cache/ttl
     *
     * Update TTL on all keys matching a pattern.
     *
     * Request body:
     * { "pattern": "seek:cache:ecommerce:*", "seconds": 7200 }
     *
     * Set seconds=0 to make entries persistent (no expiry).
     */
    @PatchMapping("/ttl")
    public ResponseEntity<?> updateTtl(@RequestBody Map<String, Object> body) {
        String pattern = (String) body.get("pattern");
        Object secsObj = body.get("seconds");

        if (pattern == null || secsObj == null)
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Request body must contain 'pattern' and 'seconds'"));

        long seconds;
        try { seconds = Long.parseLong(secsObj.toString()); }
        catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "seconds must be a number"));
        }

        long updated = cacheAdminService.updateTtl(pattern, seconds);
        return ResponseEntity.ok(Map.of(
                "pattern",     pattern,
                "newTtl",      seconds > 0 ? seconds + "s" : "PERSISTENT",
                "updatedKeys", updated
        ));
    }

    // =========================================================================
    // WARM-UP
    // =========================================================================

    /**
     * POST /api/v1/admin/cache/warmup
     *
     * Pre-populate the cache by running a list of known queries through
     * the full NL→SQL pipeline. Each query that succeeds is stored in Redis.
     *
     * Request body:
     * {
     *   "queries": ["list active employees", "show top customers"],
     *   "databaseName": "ecommerce",
     *   "topK": 5
     * }
     *
     * Response: per-query status (status, cacheHit, generatedSQL, confidence)
     */
    @PostMapping("/warmup")
    public ResponseEntity<?> warmUp(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) body.get("queries");
        String dbName = (String) body.getOrDefault("databaseName", "ecommerce");
        int topK = body.containsKey("topK")
                ? Integer.parseInt(body.get("topK").toString()) : 5;

        if (queries == null || queries.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "'queries' list is required"));

        log.info("🔥 Cache warm-up request: {} queries for db='{}'", queries.size(), dbName);
        List<Map<String, Object>> results = cacheAdminService.warmUp(queries, dbName, topK);

        long ok      = results.stream().filter(r -> "OK".equals(r.get("status"))).count();
        long errors  = results.size() - ok;

        return ResponseEntity.ok(Map.of(
                "totalQueries", queries.size(),
                "cached",       ok,
                "errors",       errors,
                "databaseName", dbName,
                "results",      results
        ));
    }

    // =========================================================================
    // NORMALIZATION CHECK
    // =========================================================================

    /**
     * POST /api/v1/admin/cache/normalize
     *
     * Check which canonical form a set of queries normalizes to,
     * whether they share the same Redis key, and whether they are already cached.
     *
     * Request body:
     * {
     *   "queries": ["list all active employees", "show all active employees"],
     *   "db": "ecommerce"
     * }
     *
     * Response:
     * {
     *   "queries": [
     *     { "query": "list all active employees",
     *       "canonical": "get active employee",
     *       "redisKey": "seek:cache:ecommerce:norm:a3f8c2b1d4e67890",
     *       "cachedInRedis": true },
     *     ...
     *   ],
     *   "sameKey":         true,
     *   "uniqueKeyCount":  1,
     *   "uniqueCanonicals":["get active employee"]
     * }
     */
    @PostMapping("/normalize")
    public ResponseEntity<?> normalizeCheck(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) body.get("queries");
        String db = (String) body.get("db");

        if (queries == null || queries.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "'queries' list is required"));

        return ResponseEntity.ok(cacheAdminService.normalizeCheck(queries, db));
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private Map<String, Object> evictResponse(long count, String scope) {
        return Map.of(
                "evicted", count,
                "scope",   scope,
                "message", "Evicted " + count + " cache entries (" + scope + ")"
        );
    }
}
