package com.nlp.rag.seek.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * A single entry stored in the Redis semantic cache.
 *
 * Layout in Redis:
 *   key   →  seek:cache:<uuid>          (String key)
 *   value →  JSON-serialised CacheEntry (via RedisTemplate<String,CacheEntry>)
 *   TTL   →  configured via cache.semantic.ttl-seconds
 *
 * The {@code queryEmbedding} float array is persisted alongside the
 * generated SQL so that cosine similarity can be computed on retrieval
 * without calling the embedding model again.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CacheEntry {

    /** The original (sanitized) natural-language query that was cached */
    private String originalQuery;

    /** The canonical normalized form of the query (used as deterministic key) */
    private String normalizedQuery;

    /** The generated SQL result stored in cache */
    private SQLGenerationResponse cachedResponse;

    /**
     * The embedding vector of {@code originalQuery}.
     * Stored as a float array so cosine similarity can be re-computed
     * during a cache lookup without hitting the embedding API.
     */
    private float[] queryEmbedding;

    /** Database name context — used as a metadata filter during lookup */
    private String databaseName;

    /** When this entry was created (ISO-8601) */
    private Instant createdAt;

    /** How many times this entry has been returned as a cache hit */
    private int hitCount;

    public CacheEntry() {}

    public CacheEntry(String originalQuery, String normalizedQuery,
                      SQLGenerationResponse cachedResponse,
                      float[] queryEmbedding, String databaseName) {
        this.originalQuery   = originalQuery;
        this.normalizedQuery = normalizedQuery;
        this.cachedResponse  = cachedResponse;
        this.queryEmbedding  = queryEmbedding;
        this.databaseName    = databaseName;
        this.createdAt       = Instant.now();
        this.hitCount        = 0;
    }

    // ── getters / setters ─────────────────────────────────────────────────────
    public String getOriginalQuery()                      { return originalQuery; }
    public String getNormalizedQuery()                    { return normalizedQuery; }
    public SQLGenerationResponse getCachedResponse()      { return cachedResponse; }
    public float[] getQueryEmbedding()                    { return queryEmbedding; }
    public String getDatabaseName()                       { return databaseName; }
    public Instant getCreatedAt()                         { return createdAt; }
    public int getHitCount()                              { return hitCount; }

    public void setOriginalQuery(String v)                { this.originalQuery = v; }
    public void setNormalizedQuery(String v)              { this.normalizedQuery = v; }
    public void setCachedResponse(SQLGenerationResponse v){ this.cachedResponse = v; }
    public void setQueryEmbedding(float[] v)              { this.queryEmbedding = v; }
    public void setDatabaseName(String v)                 { this.databaseName = v; }
    public void setCreatedAt(Instant v)                   { this.createdAt = v; }
    public void setHitCount(int v)                        { this.hitCount = v; }

    public void incrementHitCount()                       { this.hitCount++; }
}

