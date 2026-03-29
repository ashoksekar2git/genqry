package com.nlp.rag.seek.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request model for the NL→SQL pipeline.
 *
 * source — optional client hint:
 *   "UI"  → response SQL is pretty-printed with newlines and indentation
 *   "API" → response SQL is compact single-line (default when omitted)
 *
 * If source is null/blank the controller auto-detects it from HTTP headers
 * (Accept: text/html, Origin, Referer, X-Requested-With).
 */
@Schema(description = "Natural Language to SQL request payload")
public class NaturalLanguageQueryRequest {

    @Schema(description = "Natural language question", example = "List all products which cost more than 50 dollars")
    private String query;

    @Schema(description = "Target database name", example = "ecommerce")
    private String databaseName;

    @Schema(description = "Database type (PostgreSQL, MySQL, etc.)", example = "PostgreSQL")
    private String databaseType;

    @Schema(description = "Logged-in username", example = "AshokSekar")
    private String userName;

    @Schema(description = "Number of top-K chunks to retrieve", example = "5")
    private int    topK;

    /**
     * Optional client source hint.
     * Accepted values: "UI", "API", "CURL", "BATCH" (case-insensitive).
     * When null the controller detects automatically from HTTP headers.
     */
    @Schema(description = "Client source hint (UI|API|CURL|BATCH)", example = "UI")
    private String source;

    /**
     * When true the controller will execute the generated SQL against the live
     * datasource (after validation passes) and attach the result rows to the
     * response under {@code executionResult}.
     * Default: false.
     */
    @Schema(description = "Execute generated SQL and return sample rows", example = "false")
    private boolean showSampleResults;

    /**
     * Maximum number of rows to return when showSampleResults=true.
     * Must be between 1 and 200. Default: 50.
     */
    @Schema(description = "Max sample rows (1-200, used when showSampleResults=true)", example = "50")
    private int sampleLimit;

    /**
     * When false, the pipeline skips the Redis semantic cache for both lookup
     * and storage — the LLM is always called fresh.
     * Default: true.
     */
    @Schema(description = "Whether to use the semantic cache (set false to force fresh LLM call)", example = "true")
    private boolean cacheEnabled = true;

    public NaturalLanguageQueryRequest() {}

    public String  getQuery()              { return query; }
    public String  getDatabaseName()       { return databaseName; }
    public String  getDatabaseType()       { return databaseType; }
    public String  getUserName()           { return userName; }
    public int     getTopK()               { return topK; }
    public String  getSource()             { return source; }
    public boolean isShowSampleResults()   { return showSampleResults; }
    public int     getSampleLimit()        { return sampleLimit; }
    public boolean isCacheEnabled()        { return cacheEnabled; }

    public void setQuery(String query)                   { this.query = query; }
    public void setDatabaseName(String databaseName)     { this.databaseName = databaseName; }
    public void setDatabaseType(String databaseType)     { this.databaseType = databaseType; }
    public void setUserName(String userName)             { this.userName = userName; }
    public void setTopK(int topK)                        { this.topK = topK; }
    public void setSource(String source)                 { this.source = source; }
    public void setShowSampleResults(boolean v)          { this.showSampleResults = v; }
    public void setSampleLimit(int v)                    { this.sampleLimit = v; }
    public void setCacheEnabled(boolean v)               { this.cacheEnabled = v; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final NaturalLanguageQueryRequest r = new NaturalLanguageQueryRequest();
        public Builder query(String v)              { r.query = v;               return this; }
        public Builder databaseName(String v)       { r.databaseName = v;        return this; }
        public Builder databaseType(String v)       { r.databaseType = v;        return this; }
        public Builder userName(String v)           { r.userName = v;            return this; }
        public Builder topK(int v)                  { r.topK = v;                return this; }
        public Builder source(String v)             { r.source = v;              return this; }
        public Builder showSampleResults(boolean v) { r.showSampleResults = v;   return this; }
        public Builder sampleLimit(int v)           { r.sampleLimit = v;         return this; }
        public Builder cacheEnabled(boolean v)      { r.cacheEnabled = v;        return this; }
        public NaturalLanguageQueryRequest build()  { return r; }
    }
}


