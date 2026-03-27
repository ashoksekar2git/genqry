package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.PiiSanitizationResult;
import com.nlp.rag.seek.model.PiiToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * PII / PHI Sanitization Service.
 *
 * SANITIZE phase (before LLM call)
 * ─────────────────────────────────
 * Scans the user's natural-language query for the following sensitive data types
 * and replaces every match with a deterministic placeholder token:
 *
 *   Category     │ Examples detected
 *   ─────────────┼──────────────────────────────────────────────────────
 *   SSN          │ 123-45-6789  or  123456789
 *   EMAIL        │ user@example.com
 *   PHONE        │ (555) 123-4567 / 555-123-4567 / +1-555-123-4567
 *   CREDIT_CARD  │ 4111 1111 1111 1111 / 4111-1111-1111-1111
 *   DOB          │ 01/15/1990 / 1990-01-15 / January 15, 1990
 *   IP_ADDRESS   │ 192.168.1.1
 *   PASSPORT     │ A12345678
 *   ZIP_CODE     │ 12345 / 12345-6789
 *   ADDRESS      │ 123 Main Street, Springfield, IL
 *   FIRST_NAME   │ first_name = 'John'  /  firstname: 'John'
 *   LAST_NAME    │ last_name = 'Doe'    /  lastname: 'Doe'
 *   FULL_NAME    │ name = 'John Doe'    /  customer_name = 'Alice Smith'
 *   PASSWORD     │ password = 'secret' /  pwd = "abc123"
 *   SECRET       │ secret = 'xyz' / token = 'abc' / api_key = '...'
 *   PATH         │ /home/user/file.txt  or  C:\Users\file
 *   MRN          │ mrn = '12345678' (Medical Record Number)
 *
 * Placeholder format:  __PII_<CATEGORY>_<seq>__
 * e.g.  __PII_SSN_1__,  __PII_EMAIL_2__,  __PII_PHONE_3__
 *
 * RESTORE phase (after LLM call)
 * ────────────────────────────────
 * Every placeholder found in the generated SQL is replaced back with the
 * original sensitive value so the final SQL is executable.
 */
@Service
public class PiiSanitizationService {

    private static final Logger log = LoggerFactory.getLogger(PiiSanitizationService.class);

    // =========================================================================
    // Regex patterns (ordered: more specific → more general)
    // =========================================================================

    private static final List<PiiPattern> PATTERNS = List.of(

        // ── Credit card (before phone/SSN to avoid partial matches) ──────────
        new PiiPattern("CREDIT_CARD",
            Pattern.compile(
                "\\b(?:4[0-9]{3}|5[1-5][0-9]{2}|3[47][0-9]{2}|6(?:011|5[0-9]{2}))" +
                "[\\s\\-]?[0-9]{4}[\\s\\-]?[0-9]{4}[\\s\\-]?[0-9]{4}\\b")),

        // ── SSN ───────────────────────────────────────────────────────────────
        new PiiPattern("SSN",
            Pattern.compile("\\b(?!000|666|9\\d{2})\\d{3}[\\-\\s]?(?!00)\\d{2}[\\-\\s]?(?!0000)\\d{4}\\b")),

        // ── Email ─────────────────────────────────────────────────────────────
        new PiiPattern("EMAIL",
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")),

        // ── Phone / mobile ────────────────────────────────────────────────────
        new PiiPattern("PHONE",
            Pattern.compile(
                "\\+?(?:\\d{1,3}[\\-\\s.])?(?:\\(?\\d{3}\\)?[\\-\\s.]?)\\d{3}[\\-\\s.]\\d{4}\\b")),

        // ── Date of birth (various formats) ───────────────────────────────────
        new PiiPattern("DOB",
            Pattern.compile(
                "\\b(?:(?:0?[1-9]|1[0-2])[/\\-](?:0?[1-9]|[12]\\d|3[01])[/\\-](?:19|20)\\d{2}" + // MM/DD/YYYY
                "|(?:19|20)\\d{2}[/\\-](?:0?[1-9]|1[0-2])[/\\-](?:0?[1-9]|[12]\\d|3[01])" +      // YYYY-MM-DD
                "|(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?" +
                  "|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)" +
                  "\\s+\\d{1,2},?\\s+(?:19|20)\\d{2})\\b",
                Pattern.CASE_INSENSITIVE)),

        // ── IP Address ────────────────────────────────────────────────────────
        new PiiPattern("IP_ADDRESS",
            Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b")),

        // ── Passport number ───────────────────────────────────────────────────
        new PiiPattern("PASSPORT",
            Pattern.compile("\\b[A-Z]{1,2}[0-9]{6,9}\\b")),

        // ── ZIP code ──────────────────────────────────────────────────────────
        new PiiPattern("ZIP_CODE",
            Pattern.compile("\\b\\d{5}(?:-\\d{4})?\\b")),

        // ── Password / secret / token / api_key ───────────────────────────────
        new PiiPattern("PASSWORD",
            Pattern.compile(
                "(?i)(?:password|passwd|pwd|pass)\\s*[=:]\\s*['\"]?([^'\"\\s,;)]+)['\"]?",
                Pattern.CASE_INSENSITIVE)),

        new PiiPattern("SECRET",
            Pattern.compile(
                "(?i)(?:secret|token|api[_\\-]?key|auth[_\\-]?key|access[_\\-]?key|private[_\\-]?key)" +
                "\\s*[=:]\\s*['\"]?([^'\"\\s,;)]{4,})['\"]?",
                Pattern.CASE_INSENSITIVE)),

        // ── File paths ────────────────────────────────────────────────────────
        new PiiPattern("PATH",
            Pattern.compile(
                "(?:[A-Za-z]:[/\\\\](?:[^\\\\/:*?\"<>|\\r\\n]+[/\\\\])*[^\\\\/:*?\"<>|\\r\\n]+" + // Windows C:\...
                "|/(?:[^/\\s\"']+/)+[^/\\s\"']*)")),                                               // Unix /path/to/file

        // ── MRN (Medical Record Number) ───────────────────────────────────────
        new PiiPattern("MRN",
            Pattern.compile("(?i)(?:mrn|medical[_\\-]?record(?:[_\\-]?number)?)\\s*[=:]\\s*['\"]?(\\d{6,10})['\"]?")),

        // ── Full name in SQL-like context ─────────────────────────────────────
        new PiiPattern("FULL_NAME",
            Pattern.compile(
                "(?i)(?:full[_\\-]?name|customer[_\\-]?name|employee[_\\-]?name|name)" +
                "\\s*[=:]\\s*['\"]([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)['\"]")),

        // ── First name in SQL-like context ────────────────────────────────────
        new PiiPattern("FIRST_NAME",
            Pattern.compile(
                "(?i)(?:first[_\\-]?name|fname|given[_\\-]?name)" +
                "\\s*[=:]\\s*['\"]([A-Z][a-z]{1,30})['\"]")),

        // ── Last name in SQL-like context ─────────────────────────────────────
        new PiiPattern("LAST_NAME",
            Pattern.compile(
                "(?i)(?:last[_\\-]?name|lname|surname|family[_\\-]?name)" +
                "\\s*[=:]\\s*['\"]([A-Z][a-z]{1,30})['\"]")),

        // ── Street address ────────────────────────────────────────────────────
        new PiiPattern("ADDRESS",
            Pattern.compile(
                "(?i)(?:address|addr|street|city|residence)\\s*[=:]\\s*['\"]([^'\"]{5,80})['\"]"))
    );

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Scans {@code query} for PII/PHI, replaces every match with a placeholder,
     * and returns both the sanitized query and the token list needed to restore.
     *
     * Example
     * ───────
     * Input  : "Find employee where ssn = '123-45-6789' and email = 'a@b.com'"
     * Output sanitized: "Find employee where ssn = '__PII_SSN_1__' and email = '__PII_EMAIL_2__'"
     * tokens : [{SSN, "123-45-6789", "__PII_SSN_1__"}, {EMAIL, "a@b.com", "__PII_EMAIL_2__"}]
     */
    public PiiSanitizationResult sanitize(String query) {
        if (query == null || query.isBlank()) {
            return new PiiSanitizationResult(query, Collections.emptyList());
        }

        // Collect all matches across all patterns, sorted by start position
        List<PiiToken> allTokens  = new ArrayList<>();
        Set<String>    alreadySeen = new HashSet<>();  // avoid double-masking same span
        int            seq         = 1;

        for (PiiPattern pp : PATTERNS) {
            Matcher m = pp.pattern().matcher(query);
            while (m.find()) {
                // For patterns with a capture group use group(1), else full match
                int    start    = m.start();
                int    end      = m.end();
                String rawValue = m.groupCount() >= 1 && m.group(1) != null
                                  ? m.group(1) : m.group(0);
                String spanKey  = start + ":" + end;

                if (alreadySeen.contains(spanKey)) continue;

                String placeholder = "__PII_" + pp.category() + "_" + seq + "__";
                allTokens.add(new PiiToken(pp.category(), rawValue, placeholder, start, end));
                alreadySeen.add(spanKey);
                seq++;
            }
        }

        if (allTokens.isEmpty()) {
            log.debug("PII scan: no PII/PHI detected in query");
            return new PiiSanitizationResult(query, Collections.emptyList());
        }

        // Sort by start position so we can replace right-to-left (preserves indices)
        allTokens.sort(Comparator.comparingInt(PiiToken::getStartIndex));

        // Build sanitized string by replacing spans right-to-left
        StringBuilder sb = new StringBuilder(query);
        List<PiiToken> sorted = new ArrayList<>(allTokens);
        ListIterator<PiiToken> it = sorted.listIterator(sorted.size());
        while (it.hasPrevious()) {
            PiiToken t = it.previous();
            // For patterns where the match wraps the sensitive part in quotes,
            // only replace the value portion, not the surrounding syntax.
            // We replace the whole match group(0) with the same string but
            // the sensitive sub-part swapped for the placeholder.
            String original = query.substring(t.getStartIndex(), t.getEndIndex());
            String replaced = original.replace(t.getOriginalValue(), t.getPlaceholder());
            sb.replace(t.getStartIndex(), t.getEndIndex(), replaced);
        }

        String sanitized = sb.toString();

        log.warn("PII DETECTED — {} token(s) masked before LLM call:", allTokens.size());
        allTokens.forEach(t -> log.warn("  → [{}] '{}' → '{}'",
                t.getCategory(), maskForLog(t.getOriginalValue()), t.getPlaceholder()));
        log.debug("Sanitized query: {}", sanitized);

        return new PiiSanitizationResult(sanitized, allTokens);
    }

    /**
     * Replaces every placeholder token in {@code sql} with its original PII value.
     *
     * Called after the LLM returns the generated SQL so the final query
     * contains the real sensitive values the user intended.
     *
     * Example
     * ───────
     * SQL from LLM : "SELECT * FROM employees WHERE ssn = '__PII_SSN_1__';"
     * After restore: "SELECT * FROM employees WHERE ssn = '123-45-6789';"
     */
    public String restore(String sql, List<PiiToken> tokens) {
        if (sql == null || tokens == null || tokens.isEmpty()) return sql;

        String restored = sql;
        for (PiiToken t : tokens) {
            restored = restored.replace(t.getPlaceholder(), t.getOriginalValue());
        }

        if (!restored.equals(sql)) {
            log.info("PII RESTORED — {} token(s) substituted back into SQL", tokens.size());
            tokens.forEach(t -> log.debug("  ← [{}] '{}' restored", t.getCategory(), maskForLog(t.getOriginalValue())));
        }
        return restored;
    }

    /**
     * Returns a list of category labels for detected tokens — safe to include
     * in API responses (no actual values exposed).
     */
    public List<String> summarize(List<PiiToken> tokens) {
        if (tokens == null) return Collections.emptyList();
        return tokens.stream()
                .map(t -> t.getCategory() + " detected and masked")
                .toList();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Partially masks a value for safe logging — never logs raw PII */
    private String maskForLog(String v) {
        if (v == null || v.length() <= 2) return "***";
        return v.charAt(0) + "*".repeat(Math.min(v.length() - 2, 6)) + v.charAt(v.length() - 1);
    }

    /** Pairs a category label with its compiled regex */
    private record PiiPattern(String category, Pattern pattern) {}
}

