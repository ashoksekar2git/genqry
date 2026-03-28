package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Expands abbreviated table / column names into full English words
 * so the embedding model can place them correctly in semantic space.
 *
 * Dictionaries are loaded at startup from two JSON files under
 * src/main/resources/supportingFiles/:
 *
 *   abbreviations.json  →  DICT        (abbr token → full word)
 *   semantic.json       →  ALIAS_GROUPS (canonical word → synonym list)
 *
 * Any entries in application.properties
 *   seek.schema.abbreviations.<abbr>=<expansion>
 * are merged in after the JSON load, so they take precedence.
 *
 * No code change is needed to add new abbreviations or synonyms —
 * just edit the JSON files and restart the application.
 */
@Service
public class AbbreviationExpanderService {

    private static final Logger log = LoggerFactory.getLogger(AbbreviationExpanderService.class);

    private static final String ABBREVIATIONS_JSON = "supportingFiles/abbreviations.json";
    private static final String SEMANTIC_JSON       = "supportingFiles/semantic.json";

    // Instance maps — populated from JSON at @PostConstruct, NOT static
    private final Map<String, String>       DICT         = new LinkedHashMap<>();
    private final Map<String, List<String>> ALIAS_GROUPS = new LinkedHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Injected from application.properties:
    // seek.schema.abbreviations.shpmnt=shipment
    @Value("#{${seek.schema.abbreviations:{}}}")
    private Map<String, String> customAbbreviations = new HashMap<>();

    // =========================================================================
    // Startup — load JSON files, then merge application.properties overrides
    // =========================================================================

    @PostConstruct
    public void loadDictionaries() {
        loadAbbreviations();
        loadSemanticAliases();
        mergeCustomAbbreviations();
    }

    // ── Load DICT from abbreviations.json ────────────────────────────────────

    private void loadAbbreviations() {
        try (InputStream is = new ClassPathResource(ABBREVIATIONS_JSON).getInputStream()) {
            JsonNode root = objectMapper.readTree(is);

            int count = 0;
            // Iterate every field in the root object
            Iterator<Map.Entry<String, JsonNode>> sections = root.fields();
            while (sections.hasNext()) {
                Map.Entry<String, JsonNode> section = sections.next();
                String sectionKey = section.getKey();

                // Skip metadata / comment fields
                if (sectionKey.startsWith("_")) continue;

                JsonNode sectionNode = section.getValue();
                if (!sectionNode.isObject()) continue;

                Iterator<Map.Entry<String, JsonNode>> entries = sectionNode.fields();
                while (entries.hasNext()) {
                    Map.Entry<String, JsonNode> entry = entries.next();
                    String key   = entry.getKey();
                    String value = entry.getValue().asText();

                    // Skip comment fields inside sections
                    if (key.startsWith("_")) continue;

                    DICT.put(key.toLowerCase(), value);
                    count++;
                }
            }

            log.info("Abbreviations loaded from {} — {} entries across all sections",
                    ABBREVIATIONS_JSON, count);

        } catch (Exception e) {
            log.error("Failed to load abbreviations from {} — DICT will be empty: {}",
                    ABBREVIATIONS_JSON, e.getMessage());
        }
    }

    // ── Load ALIAS_GROUPS from semantic.json ──────────────────────────────────

    @SuppressWarnings("unchecked")
    private void loadSemanticAliases() {
        try (InputStream is = new ClassPathResource(SEMANTIC_JSON).getInputStream()) {
            JsonNode root = objectMapper.readTree(is);

            JsonNode aliasGroupsNode = root.get("alias_groups");
            if (aliasGroupsNode == null || !aliasGroupsNode.isObject()) {
                log.warn("semantic.json has no 'alias_groups' object — ALIAS_GROUPS will be empty");
                return;
            }

            int count = 0;
            Iterator<Map.Entry<String, JsonNode>> groups = aliasGroupsNode.fields();
            while (groups.hasNext()) {
                Map.Entry<String, JsonNode> group = groups.next();
                String canonicalWord = group.getKey();

                // Skip comment fields
                if (canonicalWord.startsWith("_")) continue;

                JsonNode groupNode = group.getValue();
                JsonNode synonymsNode = groupNode.get("synonyms");
                if (synonymsNode == null || !synonymsNode.isArray()) continue;

                List<String> synonyms = new ArrayList<>();
                synonymsNode.forEach(s -> synonyms.add(s.asText()));

                ALIAS_GROUPS.put(canonicalWord.toLowerCase(), synonyms);
                count++;
               // log.debug("  Alias group '{}' → {} synonym(s)", canonicalWord, synonyms.size());
            }

            log.info("Semantic alias groups loaded from {} — {} groups",
                    SEMANTIC_JSON, count);

        } catch (Exception e) {
            log.error("Failed to load semantic aliases from {} — ALIAS_GROUPS will be empty: {}",
                    SEMANTIC_JSON, e.getMessage());
        }
    }

    // ── Merge application.properties custom abbreviations ────────────────────

    private void mergeCustomAbbreviations() {
        if (customAbbreviations == null || customAbbreviations.isEmpty()) return;
        customAbbreviations.forEach((k, v) -> {
            DICT.put(k.toLowerCase(), v);
            log.info("Custom abbreviation loaded from application.properties: {} → {}", k, v);
        });
    }

    // =========================================================================
    // Public API  (unchanged — no callers need to be modified)
    // =========================================================================

    /**
     * Expands an abbreviated identifier into a human-readable English phrase.
     *
     * Examples:
     *   "emp_dtls"      →  "employee details"
     *   "cust_ord_dtls" →  "customer order details"
     *   "f_nm"          →  "first name"
     *   "sal"           →  "salary"
     *   "dob"           →  "date of birth"
     *   "employees"     →  "employees"   (no-op — already descriptive)
     */
    public String expand(String identifier) {
        if (identifier == null || identifier.isBlank()) return identifier;

        List<String> tokens = tokenize(identifier);
        List<String> expanded = tokens.stream()
                .map(t -> DICT.getOrDefault(t.toLowerCase(), t))
                .collect(Collectors.toList());

        return String.join(" ", expanded);
    }

    /**
     * Returns semantic alias phrases for the expanded name.
     * Used to broaden the embedding text coverage.
     *
     * Example: expand("emp_dtls") = "employee details"
     *   → aliases: ["staff details", "worker details", "personnel details", ...]
     */
    public List<String> aliases(String expandedName) {
        if (expandedName == null) return Collections.emptyList();
        String lower = expandedName.toLowerCase();
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : ALIAS_GROUPS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                for (String alias : entry.getValue()) {
                    result.add(lower.replace(entry.getKey(), alias));
                }
                break; // one alias group per name is enough
            }
        }
        return result;
    }

    /**
     * Returns true when an identifier looks abbreviated.
     * Heuristic: any token after splitting is ≤ 4 chars OR exists in the dict.
     */
    public boolean isAbbreviated(String identifier) {
        if (identifier == null) return false;
        List<String> tokens = tokenize(identifier);
        return tokens.stream().anyMatch(t ->
                t.length() <= 4 || DICT.containsKey(t.toLowerCase()));
    }

    /**
     * Adds a custom abbreviation at runtime.
     * Also used by tests and admin endpoints to inject domain-specific terms.
     */
    public void addAbbreviation(String abbr, String expansion) {
        DICT.put(abbr.toLowerCase(), expansion);
        log.debug("Custom abbreviation registered at runtime: {} → {}", abbr, expansion);
    }

    // =========================================================================
    // Tokenizer — handles snake_case, camelCase, UPPER_CASE, mixed
    // =========================================================================

    /**
     * Splits an identifier into lowercase tokens.
     *
     * "emp_dtls"  → ["emp", "dtls"]
     * "empDtls"   → ["emp", "dtls"]
     * "EMP_DTLS"  → ["emp", "dtls"]
     * "firstName" → ["first", "name"]
     * "DOB"       → ["dob"]
     * "f_nm"      → ["f", "nm"]
     */
    public List<String> tokenize(String identifier) {
        if (identifier == null || identifier.isBlank()) return Collections.emptyList();

        String[] underscoreParts = identifier.split("_");
        List<String> tokens = new ArrayList<>();
        for (String part : underscoreParts) {
            if (part.isBlank()) continue;
            String[] camel = part.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
            for (String c : camel) {
                if (!c.isBlank()) tokens.add(c.toLowerCase());
            }
        }
        return tokens;
    }
}

