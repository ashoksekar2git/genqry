package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * Sends the generated SQL to the LLM and asks it to explain what the
 * query does in plain English — suitable for a business user.
 *
 * EXPLANATION rules are loaded from BusinessRulesForPrompts.json at startup.
 * If the ChatClient is unavailable (no API key) a rule-based fallback
 * summary is returned instead.
 */
@Service
public class SQLExplanationService {

    private static final Logger log = LoggerFactory.getLogger(SQLExplanationService.class);

    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("sqlChatClient")
    private ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Explanation rules loaded from BusinessRulesForPrompts.json (EXPLANATION type) */
    private String explanationRulesText = "";

    /** Default fallback prompt template used when no EXPLANATION rules are found in the JSON */
//    private static final String DEFAULT_PROMPT_TEMPLATE = """
//            You are a helpful data analyst. Given the SQL query below, write a concise,
//            plain-English explanation (2–4 sentences) describing:
//              1. Which data is being retrieved or modified.
//              2. What filters or conditions apply.
//              3. How the results are ordered or grouped (if any).
//              4. The business purpose this query is likely serving.
//
//            Do NOT include the SQL in your answer. Use simple language a non-technical
//            business user would understand.
//
//            SQL:
//            %s
//            """;

    @PostConstruct
    public void loadExplanationRules() {
        try {
            // Try filesystem first (written by BusinessRulesJsonExporter at startup)
            java.nio.file.Path fsPath = java.nio.file.Paths.get(
                    "src/main/resources/supportingFiles", "BusinessRulesForPrompts.json").toAbsolutePath();
            InputStream is;
            if (java.nio.file.Files.exists(fsPath)) {
                is = java.nio.file.Files.newInputStream(fsPath);
            } else {
                is = new ClassPathResource("supportingFiles/BusinessRulesForPrompts.json").getInputStream();
            }

            JsonNode root = objectMapper.readTree(is);
            is.close();

            if (root.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode ruleNode : root) {
                    boolean enabled = ruleNode.path("enabled").asBoolean(true);
                    if (!enabled) continue;

                    String ruleType = ruleNode.path("ruleType").asText("");
                    if (!"EXPLANATION".equals(ruleType)) continue;

                    int number = ruleNode.path("number").asInt(0);
                    String ruleText = ruleNode.path("rule").asText("");
                    sb.append(number).append(". ").append(ruleText).append("\n");
                }
                explanationRulesText = sb.toString().stripTrailing();
            }

            log.info("Explanation rules loaded from BusinessRulesForPrompts.json — {} chars",
                    explanationRulesText.length());

        } catch (Exception e) {
            log.warn("Could not load EXPLANATION rules from BusinessRulesForPrompts.json — using defaults: {}",
                    e.getMessage());
            explanationRulesText = "";
        }
    }

    /**
     * Generate a plain-English explanation for the given SQL.
     *
     * @param sql the generated SQL string
     * @return plain-English explanation, never null
     */
    public String explain(String sql) {
        if (sql == null || sql.isBlank()) {
            return "No SQL was generated so no explanation is available.";
        }

        if (chatClient != null && !explanationRulesText.isBlank()) {
            try {
                String prompt = explanationRulesText + "\n\n" +
                        "Do NOT include the SQL in your answer. Use simple language a non-technical\n" +
                        "business user would understand.\n\n" +
                        "SQL:\n" + sql;

                log.info("══════════════ EXPLANATION PROMPT SENT TO LLM ══════════════\n{}\n══════════════ END OF EXPLANATION PROMPT ══════════════", prompt);
                String answer = chatClient.prompt(new Prompt(new UserMessage(prompt))).call().content();
                log.info("══════════════ EXPLANATION LLM RESPONSE ══════════════\n{}\n══════════════ END OF EXPLANATION LLM RESPONSE ══════════════", answer);
                return answer.trim();
            } catch (Exception e) {
                log.warn("LLM explanation call failed ({}); using rule-based fallback.", e.getMessage());
            }
        } else if (chatClient == null) {
            log.warn("ChatClient not available — using rule-based fallback for explanation");
        } else {
            log.warn("No EXPLANATION rules loaded from BusinessRulesForPrompts.json — using rule-based fallback");
        }

        // ── rule-based fallback ───────────────────────────────────────────────
        return buildFallbackExplanation(sql);
    }

    // ── fallback ──────────────────────────────────────────────────────────────

    private String buildFallbackExplanation(String sql) {
        String upper = sql.toUpperCase();
        StringBuilder sb = new StringBuilder();

        // operation
        if (upper.startsWith("SELECT"))      sb.append("This query retrieves data");
        else if (upper.startsWith("INSERT"))  sb.append("This query inserts data");
        else if (upper.startsWith("UPDATE"))  sb.append("This query updates data");
        else if (upper.startsWith("DELETE"))  sb.append("This query deletes data");
        else if (upper.startsWith("WITH"))    sb.append("This query uses a common table expression (CTE) to retrieve data");
        else sb.append("This query operates on the database");

        // tables
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:FROM|JOIN)\\s+([\\w.\"]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        java.util.List<String> tables = new java.util.ArrayList<>();
        while (m.find()) tables.add(m.group(1));
        if (!tables.isEmpty()) sb.append(" from the '").append(String.join("', '", tables)).append("' table(s)");

        // WHERE
        if (upper.contains("WHERE")) sb.append(", applying filter conditions");

        // GROUP BY
        if (upper.contains("GROUP BY")) sb.append(", grouped by one or more columns");

        // ORDER BY
        if (upper.contains("ORDER BY")) sb.append(", sorted in a specific order");

        // LIMIT
        if (upper.contains("LIMIT")) sb.append(", with a row limit applied");

        sb.append(".");
        return sb.toString();
    }
}

