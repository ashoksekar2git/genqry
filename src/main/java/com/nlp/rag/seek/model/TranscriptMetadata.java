package com.nlp.rag.seek.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Comprehensive metadata for an LLM request/response transaction.
 * Used to persist detailed tracking information to the database.
 */
public class TranscriptMetadata {
    // Status and error tracking
    private String status;                      // SUCCESS, FAILURE, VALIDATION_ERROR, CACHED
    private String remarks;                     // Detailed notes: failure reason, retry info, etc.
    private String errorCode;                   // Machine-readable error type

    // LLM request/response tracking
    private JsonNode llmRequestJson;            // Raw LLM API request
    private JsonNode llmResponseJson;           // Raw LLM API response
    private String llmModel;                    // Model name used
    private String llmProvider;                 // e.g., "openai", "anthropic", etc.

    // Performance metrics (milliseconds)
    private Long retrievalDurationMs;           // Time to retrieve schema chunks
    private Long llmDurationMs;                 // Time to call LLM
    private Long validationDurationMs;          // Time to validate SQL
    private Long totalDurationMs;               // Total pipeline execution time

    // Quality metrics
    private Double confidenceScore;             // LLM confidence (0-1)
    private String confidenceLabel;             // HIGH, MEDIUM, LOW
    private Integer tokensUsed;                 // Token consumption from LLM
    private Integer tokenLimit;                 // Token limit if applicable

    // Context
    private String databaseName;                // Database being queried
    private String sessionId;                   // User session
    private String userId;                      // User ID if authenticated
    private Boolean isCached;                   // Whether result came from cache

    // User feedback
    private String userFeedback;                // User's feedback text
    private String feedbackType;                // POSITIVE | NEGATIVE | NEUTRAL
    private Boolean feedbackIsPositive;         // Aggregated sentiment (true/false/null)

    // ── Constructors ──────────────────────────────────────────────────────────
    public TranscriptMetadata() {}

    public TranscriptMetadata(String status, String remarks, String errorCode) {
        this.status = status;
        this.remarks = remarks;
        this.errorCode = errorCode;
    }

    // ── Getters and Setters ───────────────────────────────────────────────────
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }

    // Keep legacy alias so any existing callers still compile
    /** @deprecated use {@link #getRemarks()} */
    @Deprecated
    public String getFailureReason() { return remarks; }
    /** @deprecated use {@link #setRemarks(String)} */
    @Deprecated
    public void setFailureReason(String v) { this.remarks = v; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public JsonNode getLlmRequestJson() { return llmRequestJson; }
    public void setLlmRequestJson(JsonNode llmRequestJson) { this.llmRequestJson = llmRequestJson; }

    public JsonNode getLlmResponseJson() { return llmResponseJson; }
    public void setLlmResponseJson(JsonNode llmResponseJson) { this.llmResponseJson = llmResponseJson; }

    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

    public String getLlmProvider() { return llmProvider; }
    public void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }

    public Long getRetrievalDurationMs() { return retrievalDurationMs; }
    public void setRetrievalDurationMs(Long retrievalDurationMs) { this.retrievalDurationMs = retrievalDurationMs; }

    public Long getLlmDurationMs() { return llmDurationMs; }
    public void setLlmDurationMs(Long llmDurationMs) { this.llmDurationMs = llmDurationMs; }

    public Long getValidationDurationMs() { return validationDurationMs; }
    public void setValidationDurationMs(Long validationDurationMs) { this.validationDurationMs = validationDurationMs; }

    public Long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(Long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public String getConfidenceLabel() { return confidenceLabel; }
    public void setConfidenceLabel(String confidenceLabel) { this.confidenceLabel = confidenceLabel; }

    public Integer getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(Integer tokensUsed) { this.tokensUsed = tokensUsed; }

    public Integer getTokenLimit() { return tokenLimit; }
    public void setTokenLimit(Integer tokenLimit) { this.tokenLimit = tokenLimit; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Boolean getIsCached() { return isCached; }
    public void setIsCached(Boolean isCached) { this.isCached = isCached; }

    public String getUserFeedback() { return userFeedback; }
    public void setUserFeedback(String userFeedback) { this.userFeedback = userFeedback; }

    public String getFeedbackType() { return feedbackType; }
    public void setFeedbackType(String feedbackType) { this.feedbackType = feedbackType; }

    public Boolean getFeedbackIsPositive() { return feedbackIsPositive; }
    public void setFeedbackIsPositive(Boolean feedbackIsPositive) { this.feedbackIsPositive = feedbackIsPositive; }
}
