package com.nlp.rag.seek.model;

import java.time.Instant;

/**
 * A read-only, API-safe view of a {@link CacheEntry}.
 *
 * Never exposes:
 *  - queryEmbedding  (large float[], not useful to API consumers)
 *  - cachedResponse  (full SQL response — returned only on explicit GET by key)
 *
 * Used by the CacheAdminController list and inspect endpoints.
 */
public class CacheEntryView {

    private String  redisKey;
    private String  tier;              // NORM or VEC
    private String  databaseName;
    private String  originalQuery;
    private String  normalizedQuery;
    private String  generatedSQL;
    private boolean sqlValid;
    private double  confidenceScore;
    private String  confidenceLabel;
    private int     hitCount;
    private Instant createdAt;
    private long    ttlRemainingSeconds;   // -1 = no TTL, -2 = key expired
    private boolean hasEmbedding;

    private CacheEntryView() {}

    public static CacheEntryView of(String redisKey, CacheEntry e, long ttlSecs) {
        CacheEntryView v = new CacheEntryView();
        v.redisKey    = redisKey;
        v.tier        = redisKey.contains(":norm:") ? "NORM" : "VEC";
        v.databaseName      = e.getDatabaseName();
        v.originalQuery     = e.getOriginalQuery();
        v.normalizedQuery   = e.getNormalizedQuery();
        v.hitCount          = e.getHitCount();
        v.createdAt         = e.getCreatedAt();
        v.ttlRemainingSeconds = ttlSecs;
        v.hasEmbedding      = e.getQueryEmbedding() != null;

        if (e.getCachedResponse() != null) {
            SQLGenerationResponse r = e.getCachedResponse();
            v.generatedSQL    = r.getGeneratedSQL();
            v.sqlValid        = r.isSqlValid();
            v.confidenceScore = r.getConfidenceScore();
            v.confidenceLabel = r.getConfidenceLabel();
        }
        return v;
    }

    // ── getters ───────────────────────────────────────────────────────────────
    public String  getRedisKey()              { return redisKey; }
    public String  getTier()                  { return tier; }
    public String  getDatabaseName()          { return databaseName; }
    public String  getOriginalQuery()         { return originalQuery; }
    public String  getNormalizedQuery()       { return normalizedQuery; }
    public String  getGeneratedSQL()          { return generatedSQL; }
    public boolean isSqlValid()               { return sqlValid; }
    public double  getConfidenceScore()       { return confidenceScore; }
    public String  getConfidenceLabel()       { return confidenceLabel; }
    public int     getHitCount()              { return hitCount; }
    public Instant getCreatedAt()             { return createdAt; }
    public long    getTtlRemainingSeconds()   { return ttlRemainingSeconds; }
    public boolean isHasEmbedding()           { return hasEmbedding; }
}

