package com.nlp.rag.seek.model;

import java.util.List;

/**
 * Full response returned by the NL→SQL pipeline:
 * - generatedSQL  : the SQL that was produced
 * - sqlValid      : whether the SQL passed structural validation
 * - validationErrors : any rule violations found
 * - confidenceScore  : 0‒1 score from the confidence service
 * - confidenceDetails: per-factor breakdown
 * - englishExplanation : plain-English description of what the SQL does
 * - relevantTables / relevantColumns : schema elements retrieved from RAG
 * - retrievedChunks : the exact text chunks used (with overlap / stride info)
 * - error : pipeline-level error (null on success)
 */
public class SQLGenerationResponse {

    private String naturalLanguageQuery;
    private String generatedSQL;
    private boolean isHallucinatedResponse;            // whether the response is flagged as a potential hallucination by the confidence service
    // ── Validation ────────────────────────────────────────────────────────────
    private boolean sqlValid;
    private List<String> validationErrors;

    // ── Confidence ────────────────────────────────────────────────────────────
    private double confidenceScore;
    private String confidenceLabel;           // HIGH / MEDIUM / LOW
    private String confidenceDetails;

    // ── Explanation ───────────────────────────────────────────────────────────
    private String englishExplanation;

    // ── RAG context ──────────────────────────────────────────────────────────
    private List<String> relevantTables;
    private List<String> relevantColumns;
    private List<String> retrievedChunks;     // actual text chunks (with overlap)

    // ── Legacy / error ───────────────────────────────────────────────────────
    private String explanation;               // kept for backwards compat
    private String error;
    /**
     * Machine-readable error code set on pipeline failures.
     * Values:
     *   SCHEMA_MISMATCH  — Tier-1 (semantic) + Tier-2 (keyword) both returned no results
     *   null             — success or other unclassified error
     */
    private String errorCode;

    // ── Database info for frontend ────────────────────────────────────────────
    private String databaseName;              // database name for explore link

    // ── PII / PHI sanitization summary ───────────────────────────────────────
    private List<String> piiTokensFound;

    // ── Semantic cache metadata ───────────────────────────────────────────────
    private boolean cacheHit;
    private double  cacheSimilarityScore;
    private int     cacheHitCount;

    // ── Transcript tracking ───────────────────────────────────────────────────
    /**
     * DB primary key of the transcript_details row persisted for this interaction.
     * Returned to the frontend so it can later POST feedback referencing this ID.
     * -1 means the record was not persisted (DB unavailable or guest user without userId).
     */
    private int transcriptId = -1;

    /**
     * True when the first LLM call hallucinated a table/column name and a second
     * EAV-focused retry call was made to produce valid SQL.
     * Stored in the remarks column of transcript_details.
     */
    private boolean eavRetry = false;

    // ── SQL execution result (populated when showSampleResults=true) ─────────
    /**
     * Present when the caller set showSampleResults=true in the request.
     * Contains the live query result rows (capped at sampleLimit).
     * Null when execution was not requested or SQL was invalid.
     */
    private SQLExecutionResult executionResult;

    public SQLExecutionResult getExecutionResult()        { return executionResult; }
    public void setExecutionResult(SQLExecutionResult v)  { this.executionResult = v; }
    /**
     * Detected or declared client source: UI | API | CURL | BATCH
     * Populated by the controller based on HTTP headers or explicit request field.
     */
    private String clientSource;

    /**
     * SQL formatted for the detected client:
     *   UI  → pretty-printed with newlines + indentation (human-readable)
     *   API → compact single-line (machine-consumable, no extra whitespace)
     * Always present when generatedSQL is non-null.
     */
    private String formattedSQL;

    public SQLGenerationResponse() {}

    public boolean isHallucinatedResponse() {
        return isHallucinatedResponse;
    }

    public void setHallucinatedResponse(boolean hallucinatedResponse) {
        isHallucinatedResponse = hallucinatedResponse;
    }

    // getters
    public String getNaturalLanguageQuery()    { return naturalLanguageQuery; }
    public String getGeneratedSQL()            { return generatedSQL; }
    public boolean isSqlValid()                { return sqlValid; }
    public List<String> getValidationErrors()  { return validationErrors; }
    public double getConfidenceScore()         { return confidenceScore; }
    public String getConfidenceLabel()         { return confidenceLabel; }
    public String getConfidenceDetails()       { return confidenceDetails; }
    public String getEnglishExplanation()      { return englishExplanation; }
    public List<String> getRelevantTables()    { return relevantTables; }
    public List<String> getRelevantColumns()   { return relevantColumns; }
    public List<String> getRetrievedChunks()   { return retrievedChunks; }
    public String getExplanation()             { return explanation; }
    public String getError()                   { return error; }
    public String getErrorCode()               { return errorCode; }
    public List<String> getPiiTokensFound()    { return piiTokensFound; }
    public boolean isCacheHit()               { return cacheHit; }
    public double getCacheSimilarityScore()   { return cacheSimilarityScore; }
    public int getCacheHitCount()             { return cacheHitCount; }
    public String getClientSource()           { return clientSource; }
    public String getFormattedSQL()           { return formattedSQL; }
    public String getDatabaseName()           { return databaseName; }
    public int getTranscriptId()              { return transcriptId; }
    public boolean isEavRetry()               { return eavRetry; }

    // setters
    public void setNaturalLanguageQuery(String v)   { this.naturalLanguageQuery = v; }
    public void setGeneratedSQL(String v)            { this.generatedSQL = v; }
    public void setSqlValid(boolean v)               { this.sqlValid = v; }
    public void setValidationErrors(List<String> v)  { this.validationErrors = v; }
    public void setConfidenceScore(double v)         { this.confidenceScore = v; }
    public void setConfidenceLabel(String v)         { this.confidenceLabel = v; }
    public void setConfidenceDetails(String v)       { this.confidenceDetails = v; }
    public void setEnglishExplanation(String v)      { this.englishExplanation = v; }
    public void setRelevantTables(List<String> v)    { this.relevantTables = v; }
    public void setRelevantColumns(List<String> v)   { this.relevantColumns = v; }
    public void setRetrievedChunks(List<String> v)   { this.retrievedChunks = v; }
    public void setExplanation(String v)             { this.explanation = v; }
    public void setError(String v)                   { this.error = v; }
    public void setErrorCode(String v)               { this.errorCode = v; }
    public void setPiiTokensFound(List<String> v)    { this.piiTokensFound = v; }
    public void setCacheHit(boolean v)               { this.cacheHit = v; }
    public void setCacheSimilarityScore(double v)    { this.cacheSimilarityScore = v; }
    public void setCacheHitCount(int v)              { this.cacheHitCount = v; }
    public void setClientSource(String v)            { this.clientSource = v; }
    public void setFormattedSQL(String v)            { this.formattedSQL = v; }
    public void setDatabaseName(String v)            { this.databaseName = v; }
    public void setTranscriptId(int v)               { this.transcriptId = v; }
    public void setEavRetry(boolean v)               { this.eavRetry = v; }

    // builder
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final SQLGenerationResponse r = new SQLGenerationResponse();
        public Builder naturalLanguageQuery(String v)   { r.naturalLanguageQuery = v; return this; }
        public Builder generatedSQL(String v)            { r.generatedSQL = v;         return this; }
        public Builder sqlValid(boolean v)               { r.sqlValid = v;             return this; }
        public Builder validationErrors(List<String> v)  { r.validationErrors = v;     return this; }
        public Builder confidenceScore(double v)         { r.confidenceScore = v;      return this; }
        public Builder confidenceLabel(String v)         { r.confidenceLabel = v;      return this; }
        public Builder confidenceDetails(String v)       { r.confidenceDetails = v;    return this; }
        public Builder englishExplanation(String v)      { r.englishExplanation = v;   return this; }
        public Builder relevantTables(List<String> v)    { r.relevantTables = v;       return this; }
        public Builder relevantColumns(List<String> v)   { r.relevantColumns = v;      return this; }
        public Builder retrievedChunks(List<String> v)   { r.retrievedChunks = v;      return this; }
        public Builder explanation(String v)             { r.explanation = v;          return this; }
        public Builder error(String v)                   { r.error = v;               return this; }
        public Builder errorCode(String v)               { r.errorCode = v;            return this; }
        public Builder piiTokensFound(List<String> v)    { r.piiTokensFound = v;       return this; }
        public Builder cacheHit(boolean v)               { r.cacheHit = v;             return this; }
        public Builder cacheSimilarityScore(double v)    { r.cacheSimilarityScore = v; return this; }
        public Builder cacheHitCount(int v)              { r.cacheHitCount = v;        return this; }
        public Builder clientSource(String v)            { r.clientSource = v;         return this; }
        public Builder formattedSQL(String v)            { r.formattedSQL = v;         return this; }
        public Builder databaseName(String v)            { r.databaseName = v;         return this; }
        public Builder transcriptId(int v)               { r.transcriptId = v;         return this; }
        public Builder eavRetry(boolean v)               { r.eavRetry = v;             return this; }
        public Builder executionResult(SQLExecutionResult v) { r.executionResult = v;  return this; }
        public SQLGenerationResponse build()             { return r; }
    }
}
