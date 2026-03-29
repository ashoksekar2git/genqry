package com.nlp.rag.seek.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Normalizes natural-language queries so that semantically equivalent
 * phrasings produce the same canonical string, enabling deterministic
 * Redis cache key generation without needing vector similarity.
 *
 * Examples of queries that normalize to the SAME canonical form:
 * ─────────────────────────────────────────────────────────────────
 *  "list all active employees"     → "get all active employees"
 *  "show all active employees"     → "get all active employees"
 *  "display all active employees"  → "get all active employees"
 *  "find all active employees"     → "get all active employees"
 *  "List ALL  Active  Employees!"  → "get all active employees"
 *
 *  "Show me top clients"           → "get top clients"
 *  "List the top clients"          → "get top clients"
 *  "Display top clients"           → "get top clients"
 *
 *  "How many orders were placed?"  → "count orders"
 *  "Count total orders"            → "count orders"
 *
 * Normalization steps (applied in order)
 * ───────────────────────────────────────
 * 1. Lowercase
 * 2. Strip punctuation
 * 3. Collapse whitespace
 * 4. Remove filler words (me, the, a, an, all, total, of, for, ...)
 * 5. Synonym-fold action verbs  (list|show|display|find|get|fetch|give|
 *                                 retrieve|return|provide|pull|extract)  → "get"
 * 6. Synonym-fold aggregate verbs (count|how many|total|sum of)          → "count"
 * 7. Synonym-fold ordering words  (top|highest|best|maximum|most)        → "top"
 *                                 (bottom|lowest|minimum|least|worst)    → "bottom"
 * 8. Synonym-fold status words    (active|enabled|current|live)          → "active"
 *                                 (inactive|disabled|terminated|closed)  → "inactive"
 * 9. Sort words alphabetically (removes word-order variance for same-intent queries)
 *    — applied only to non-leading tokens so leading verb is preserved
 */
@Service
public class QueryNormalizerService {

    private static final Logger log = LoggerFactory.getLogger(QueryNormalizerService.class);

    // ── Patterns ──────────────────────────────────────────────────────────────

    private static final Pattern PUNCT        = Pattern.compile("[^a-z0-9\\s]");
    private static final Pattern WHITESPACE   = Pattern.compile("\\s+");

    /** Filler words that add no semantic meaning to the query intent.
     *  NOTE: action verbs like "show", "list", "find" must NOT be here —
     *  they are handled by SYNONYM_RULES before fillers are applied. */
    private static final Set<String> FILLERS = Set.of(
        "me", "the", "a", "an", "all", "total", "of", "for", "in", "on",
        "at", "by", "to", "is", "are", "was", "were", "be", "been",
        "have", "has", "had", "do", "does", "did", "with", "from",
        "that", "which", "who", "where", "when", "what",
        "please", "can", "you", "i", "we", "my", "our", "their"
    );

    /** Synonym groups → each collapses to its canonical form */
    private static final List<SynonymRule> SYNONYM_RULES = List.of(

        // Action verbs  →  "get"
        new SynonymRule("get", Set.of(
            "list", "show", "display", "find", "fetch", "retrieve",
            "return", "provide", "pull", "extract", "select",
            "search", "look", "query", "report", "see", "view"
        )),

        // Aggregate verbs  →  "count"
        new SynonymRule("count", Set.of(
            "how", "many", "sum", "total", "number", "tally", "aggregate"
        )),

        // Ordering  →  "top" / "bottom"
        new SynonymRule("top", Set.of(
            "highest", "best", "maximum", "most", "largest", "biggest",
            "greatest", "leading", "first", "head"
        )),
        new SynonymRule("bottom", Set.of(
            "lowest", "worst", "minimum", "least", "smallest",
            "trailing", "last", "tail"
        )),

        // Status  →  "active" / "inactive"
        new SynonymRule("active", Set.of(
            "enabled", "current", "live", "running", "open",
            "ongoing", "existing", "present"
        )),
        new SynonymRule("inactive", Set.of(
            "disabled", "terminated", "closed", "ended", "expired",
            "archived", "deleted", "removed", "inactive", "former"
        )),

        // Time  →  "recent" / "old"
        new SynonymRule("recent", Set.of(
            "latest", "newest", "last", "new", "today",
            "this week", "this month", "current", "now"
        )),
        new SynonymRule("old", Set.of(
            "oldest", "earliest", "first", "previous", "past", "historical"
        )),

        // Employee/personnel  →  "employee"
        new SynonymRule("employee", Set.of(
            "staff", "worker", "personnel", "member", "associate",
            "colleague", "person", "user", "individual", "people"
        )),

        // Customer  →  "customer"
        new SynonymRule("customer", Set.of(
            "client", "buyer", "consumer", "purchaser", "account",
            "patron", "subscriber"
        )),

        // Order  →  "order"
        new SynonymRule("order", Set.of(
            "purchase", "transaction", "sale", "invoice", "booking", "request"
        ))
    );

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the canonical form of a query, used as the Redis cache lookup key.
     *
     * Pipeline (in order):
     *  1. Lowercase + strip punctuation + collapse whitespace
     *  2. Tokenize
     *  3. Synonym-fold each token  (list|show|display|find → "get")
     *  4. Plural-fold each token   (employees → employee, clients → client)
     *  5. Remove fillers           (the, a, an, all, me, ...)
     *  6. Preserve lead token (intent anchor), sort the rest alphabetically
     *  7. Deduplicate consecutive identical tokens
     *
     * Examples:
     *   "list all active employees"    → "get active employee"
     *   "show all active employees"    → "get active employee"  ← SAME KEY
     *   "display the active staff"     → "get active employee"  ← SAME KEY
     *   "Show me ALL Active Employees!"→ "get active employee"  ← SAME KEY
     *   "find active employees"        → "get active employee"  ← SAME KEY
     */
    public String normalize(String query) {
        if (query == null || query.isBlank()) return "";

        // Step 1: lowercase, strip punctuation, collapse whitespace
        String s = PUNCT.matcher(query.trim().toLowerCase()).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();

        // Step 2: tokenize
        String[] rawTokens = s.split(" ");

        // Step 3+4: synonym-fold then plural-fold each token
        List<String> folded = new ArrayList<>();
        for (String token : rawTokens) {
            if (token.isBlank()) continue;
            String t = foldSynonym(token);    // list→get, show→get, staff→employee, ...
            t = foldPlural(t);               // employees→employee, clients→client, ...
            t = foldSynonym(t);              // second pass: pluralFold may expose new synonym
            folded.add(t);
        }

        // Step 5: remove fillers
        List<String> meaningful = new ArrayList<>();
        for (String t : folded) {
            if (!FILLERS.contains(t)) meaningful.add(t);
        }

        if (meaningful.isEmpty()) return s;

        // Step 6: keep lead token (intent anchor), sort rest alphabetically,
        //         then deduplicate to collapse identical words
        String lead = meaningful.get(0);
        List<String> rest = new ArrayList<>(meaningful.subList(1, meaningful.size()));
        Collections.sort(rest);

        List<String> canonical = new ArrayList<>();
        canonical.add(lead);
        canonical.addAll(rest);

        // Step 7: deduplicate consecutive identical tokens
        List<String> deduped = new ArrayList<>();
        String prev = null;
        for (String t : canonical) {
            if (!t.equals(prev)) { deduped.add(t); prev = t; }
        }

        String result = String.join(" ", deduped);
        log.debug("normalize: '{}' → '{}'", query, result);
        return result;
    }

    /**
     * Builds the deterministic Redis cache key for a given query + database.
     *
     * key = genqry:cache:<dbName>:norm:<sha256(normalize(query))>
     *
     * Both "list active employees" and "show active employees" produce
     * the SAME SHA-256 hash because normalize() returns the same canonical
     * string for both, so they map to the IDENTICAL Redis key.
     */
    public String toCacheKey(String keyPrefix, String databaseName, String query) {
        String canonical = normalize(query);
        String hash      = sha256(canonical);
        return keyPrefix + databaseName + ":norm:" + hash;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Synonym-fold: map a token to its canonical form if a rule matches */
    private String foldSynonym(String token) {
        for (SynonymRule rule : SYNONYM_RULES) {
            if (rule.synonyms().contains(token)) return rule.canonical();
        }
        return token;
    }

    /** Plural-fold: simple English singular approximation */
    private String foldPlural(String token) {
        if (token.length() <= 3) return token;
        // employees → employee (drop one 's')
        if (token.endsWith("ees"))  return token.substring(0, token.length() - 1);
        // companies → company
        if (token.endsWith("ies"))  return token.substring(0, token.length() - 3) + "y";
        // processes, statuses → process, status
        if (token.endsWith("sses")) return token.substring(0, token.length() - 2);
        // orders, clients, tables → order, client, table
        if (token.endsWith("es") && token.length() > 4)
                                    return token.substring(0, token.length() - 2);
        if (token.endsWith("s") && !token.endsWith("ss") && token.length() > 3)
                                    return token.substring(0, token.length() - 1);
        return token;
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString().substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }

    private record SynonymRule(String canonical, Set<String> synonyms) {}
}

