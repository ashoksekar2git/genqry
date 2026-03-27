package com.nlp.rag.seek.model;

import java.util.List;

/**
 * Result of a PII sanitization pass on a user's natural-language query.
 *
 * Contains:
 *  - {@code sanitizedQuery}  : the query with all PII replaced by placeholders
 *  - {@code tokens}          : ordered list of every PII token that was found
 *  - {@code piiDetected}     : convenience flag — true when at least one token was found
 */
public class PiiSanitizationResult {

    /** The original query with all PII values replaced by placeholder tokens */
    private String sanitizedQuery;

    /** Every PII token detected, in the order they appear in the original query */
    private List<PiiToken> tokens;

    public PiiSanitizationResult(String sanitizedQuery, List<PiiToken> tokens) {
        this.sanitizedQuery = sanitizedQuery;
        this.tokens         = tokens;
    }

    public String          getSanitizedQuery() { return sanitizedQuery; }
    public List<PiiToken>  getTokens()         { return tokens; }
    public boolean         isPiiDetected()     { return tokens != null && !tokens.isEmpty(); }

    public void setSanitizedQuery(String v) { this.sanitizedQuery = v; }
    public void setTokens(List<PiiToken> v) { this.tokens = v; }
}

