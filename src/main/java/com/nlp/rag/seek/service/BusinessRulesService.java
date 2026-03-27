package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.BusinessRule;
import com.nlp.rag.seek.repository.BusinessRulesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer for the business_rules table.
 *
 * Provides typed accessors for each rule category used by the LLM pipelines:
 *   SQL_GENERAL  — general SQL generation rules
 *   SQL_EAV      — rules for EAV table queries
 *   DOCUMENT     — rules for Document RAG prompts
 *   EAV_RETRY    — correction preamble rules for hallucination retry
 */
@Service
public class BusinessRulesService {

    private static final Logger log = LoggerFactory.getLogger(BusinessRulesService.class);

    @Autowired
    private BusinessRulesRepository repo;

    // ── Read helpers used by pipeline services ────────────────────────────────

    /** Returns enabled SQL_GENERAL rules as a numbered text block. */
    public String getGeneralRulesText() {
        return buildNumberedText(repo.findEnabledByType("SQL_GENERAL"));
    }

    /** Returns enabled SQL_EAV rules as a numbered text block. */
    public String getEavRulesText() {
        return buildNumberedText(repo.findEnabledByType("SQL_EAV"));
    }

    /** Returns enabled DOCUMENT rules as a numbered text block. */
    public String getDocumentRulesText() {
        return buildNumberedText(repo.findEnabledByType("DOCUMENT"));
    }

    /** Returns enabled EAV_RETRY rules as a numbered text block. */
    public String getEavRetryRulesText() {
        return buildNumberedText(repo.findEnabledByType("EAV_RETRY"));
    }

    /** Returns raw list of enabled rules for a given type. */
    public List<BusinessRule> getEnabledRules(String ruleType) {
        return repo.findEnabledByType(ruleType);
    }

    /** Returns all rules (enabled + disabled) for a given type. */
    public List<BusinessRule> getAllRulesByType(String ruleType) {
        return repo.findAllByType(ruleType);
    }

    /** Returns all rules across all types. */
    public List<BusinessRule> getAllRules() {
        return repo.findAll();
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    /**
     * Returns the next rule_number for the given ruleType (max + 1).
     */
    public int getNextRuleNumber(String ruleType) {
        return repo.getMaxRuleNumber(ruleType) + 1;
    }

    /**
     * Adds a new rule. Returns the generated id, or -1 on failure.
     * Validates ruleType before inserting.
     */
    public int addRule(BusinessRule rule) {
        validateRuleType(rule.getRuleType());
        if (rule.getRuleText() == null || rule.getRuleText().isBlank()) {
            throw new IllegalArgumentException("rule_text must not be blank");
        }
        return repo.insert(rule);
    }

    /**
     * Updates an existing rule. Returns the updated rule, or null if not found.
     */
    public BusinessRule updateRule(BusinessRule rule) {
        if (rule.getId() == null) throw new IllegalArgumentException("id is required for update");
        boolean updated = repo.update(rule);
        if (!updated) {
            log.warn("BusinessRule update: no row found for id={}", rule.getId());
            return null;
        }
        return repo.findById(rule.getId());
    }

    /**
     * Enables or disables a rule by id.
     * Returns the updated rule, or null if not found.
     */
    public BusinessRule setEnabled(int id, boolean enabled) {
        boolean updated = repo.setEnabled(id, enabled);
        if (!updated) {
            log.warn("BusinessRule setEnabled: no row found for id={}", id);
            return null;
        }
        return repo.findById(id);
    }

    /**
     * Deletes a rule by id. Returns true if deleted.
     */
    public boolean deleteRule(int id) {
        return repo.delete(id);
    }

    /** Returns a single rule by id, or null. */
    public BusinessRule getById(int id) {
        return repo.findById(id);
    }

    /**
     * Returns all rules added by the given user PLUS all core rules (added_by = 'Root').
     * Results are ordered by rule_type then rule_number.
     */
    public List<BusinessRule> getRulesForUser(String username) {
        return repo.findByAddedByOrCore(username);
    }

    /**
     * Returns all rules owned by the given userId PLUS all core/Root rules.
     * Results are ordered by rule_type then rule_number.
     */
    public List<BusinessRule> getRulesForUserId(int userId) {
        return repo.findByUserIdOrCore(userId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds a numbered text block from a list of rules:
     *   "1. Rule text here\n2. Another rule\n..."
     */
    private String buildNumberedText(List<BusinessRule> rules) {
        if (rules == null || rules.isEmpty()) return "";
        return rules.stream()
                .map(r -> r.getRuleNumber() + ". " + r.getRuleText())
                .collect(Collectors.joining("\n"));
    }

    private void validateRuleType(String ruleType) {
        if (ruleType == null || ruleType.isBlank()) {
            throw new IllegalArgumentException("rule_type is required");
        }
        switch (ruleType) {
            case "SQL_GENERAL", "SQL_EAV", "DOCUMENT", "EAV_RETRY", "RETRY", "EXPLANATION" -> { /* valid */ }
            default -> throw new IllegalArgumentException(
                    "Invalid rule_type '" + ruleType +
                    "'. Must be one of: SQL_GENERAL, SQL_EAV, DOCUMENT, EAV_RETRY, RETRY, EXPLANATION");
        }
    }
}

