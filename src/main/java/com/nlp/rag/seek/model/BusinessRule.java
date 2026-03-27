package com.nlp.rag.seek.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Represents a single business rule stored in the {@code business_rules} table.
 *
 * Rule types:
 *   SQL_GENERAL  — injected into every SQL generation prompt
 *   SQL_EAV      — injected when EAV table chunks are retrieved
 *   DOCUMENT     — injected into Document RAG prompts
 *   EAV_RETRY    — injected into the correction preamble on hallucination retry
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusinessRule {

    public enum RuleType { SQL_GENERAL, SQL_EAV, DOCUMENT, EAV_RETRY }

    private Integer id;
    private Integer userId;          // null = global / system rule
    private String  ruleType;        // SQL_GENERAL | SQL_EAV | DOCUMENT | EAV_RETRY
    private int     ruleNumber;      // ordering within the type group
    private String  ruleText;        // the actual instruction sent to the LLM
    private String  addedBy;         // "admin" | username
    private String  category;      // DB name (SQL) or document filename (DOC), null = global
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    public BusinessRule() {}

    // ── getters ───────────────────────────────────────────────────────────────
    public Integer getId()         { return id; }
    public Integer getUserId()     { return userId; }
    public String  getRuleType()   { return ruleType; }
    public int     getRuleNumber() { return ruleNumber; }
    public String  getRuleText()   { return ruleText; }
    public String  getAddedBy()    { return addedBy; }
    public String  getCategory() { return category; }
    public boolean isEnabled()     { return enabled; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }

    // ── setters ───────────────────────────────────────────────────────────────
    public void setId(Integer v)         { this.id = v; }
    public void setUserId(Integer v)     { this.userId = v; }
    public void setRuleType(String v)    { this.ruleType = v; }
    public void setRuleNumber(int v)     { this.ruleNumber = v; }
    public void setRuleText(String v)    { this.ruleText = v; }
    public void setAddedBy(String v)     { this.addedBy = v; }
    public void setCategory(String v)  { this.category = v; }
    public void setEnabled(boolean v)    { this.enabled = v; }
    public void setCreatedAt(Instant v)  { this.createdAt = v; }
    public void setUpdatedAt(Instant v)  { this.updatedAt = v; }
}

