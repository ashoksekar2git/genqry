package com.nlp.rag.seek.service;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Formats a generated SQL string depending on the client source.
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │  UI  (browser / web frontend)                                │
 * │  → Pretty-printed SQL with newlines and consistent indentation│
 * │                                                              │
 * │  SELECT e.first_name,                                        │
 * │         e.last_name,                                         │
 * │         d.department_name                                    │
 * │  FROM   employees e                                          │
 * │  JOIN   departments d ON e.dept_id = d.id                   │
 * │  WHERE  e.status = 'active'                                  │
 * │  ORDER BY e.last_name ASC;                                   │
 * └──────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │  API / CURL / BATCH  (programmatic client)                   │
 * │  → Single-line compact SQL, no extra whitespace              │
 * │                                                              │
 * │  SELECT e.first_name, e.last_name, d.department_name FROM   │
 * │  employees e JOIN departments d ON e.dept_id = d.id WHERE    │
 * │  e.status = 'active' ORDER BY e.last_name ASC;              │
 * └──────────────────────────────────────────────────────────────┘
 */
@Service
public class SQLFormatterService {

    // SQL clause keywords that start a new line in UI mode
    private static final List<String> CLAUSE_KEYWORDS = List.of(
            "SELECT", "FROM", "WHERE", "JOIN", "LEFT JOIN", "RIGHT JOIN",
            "INNER JOIN", "OUTER JOIN", "CROSS JOIN", "FULL JOIN",
            "FULL OUTER JOIN", "LEFT OUTER JOIN", "RIGHT OUTER JOIN",
            "ON", "AND", "OR", "GROUP BY", "ORDER BY", "HAVING",
            "LIMIT", "OFFSET", "UNION", "UNION ALL", "EXCEPT",
            "INTERSECT", "INSERT INTO", "VALUES", "UPDATE", "SET",
            "DELETE FROM", "WITH", "CASE", "WHEN", "THEN", "ELSE", "END"
    );

    // Indented sub-clauses (placed with extra indent under parent)
    private static final List<String> INDENTED_KEYWORDS = List.of(
            "AND", "OR", "ON", "WHEN", "THEN", "ELSE"
    );

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Format SQL based on the detected client source.
     *
     * @param sql    raw SQL string (may be null or blank)
     * @param source detected client source
     * @return formatted SQL string; returns input unchanged if null/blank
     */
    public String format(String sql, ClientSourceDetector.Source source) {
        if (sql == null || sql.isBlank()) return sql;
        return source.isUi() ? prettyPrint(sql) : compact(sql);
    }

    /**
     * Pretty-print SQL for UI display.
     * Each major SQL clause starts on a new line with consistent indentation.
     */
    public String prettyPrint(String sql) {
        if (sql == null || sql.isBlank()) return sql;

        // Normalise input: collapse all whitespace into single spaces
        String flat = compact(sql);

        // Build a case-insensitive clause pattern sorted longest-first
        // (so "LEFT OUTER JOIN" is matched before "LEFT JOIN")
        List<String> sortedKeywords = CLAUSE_KEYWORDS.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();

        // Inject newline markers before each keyword
        String marked = flat;
        for (String kw : sortedKeywords) {
            // Match keyword as a whole word, case-insensitive, not inside quotes
            String regex = "(?i)(?<!['\"`\\w])" + Pattern.quote(kw) + "(?!['\"`\\w])";
            marked = marked.replaceAll(regex, "\n" + kw);
        }

        // Split on the injected newlines and format each line
        StringBuilder sb = new StringBuilder();
        String[] lines = marked.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isBlank()) continue;

            // Determine indent: sub-clauses get an extra 2-space indent
            String upper = line.toUpperCase();
            boolean isIndented = INDENTED_KEYWORDS.stream()
                    .anyMatch(k -> upper.startsWith(k + " ") || upper.equals(k));

            String indent = isIndented ? "       " : "";   // 7 spaces aligns sub-clauses

            // Format SELECT column list: wrap at commas
            if (upper.startsWith("SELECT ")) {
                sb.append(formatSelectClause(line)).append("\n");
            } else {
                sb.append(indent).append(line).append("\n");
            }
        }

        return sb.toString().trim();
    }

    /**
     * Compact SQL for API clients — single line, normalised whitespace.
     */
    public String compact(String sql) {
        if (sql == null || sql.isBlank()) return sql;
        return WHITESPACE.matcher(sql.trim()).replaceAll(" ");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Format the SELECT clause so each column is on its own indented line.
     *
     * Input:  SELECT first_name, last_name, email, department_id
     * Output: SELECT first_name,
     *                last_name,
     *                email,
     *                department_id
     */
    private String formatSelectClause(String selectLine) {
        // Strip the leading SELECT keyword
        int selectEnd = selectLine.toUpperCase().indexOf("SELECT") + "SELECT".length();
        String rest = selectLine.substring(selectEnd).trim();

        // Split on commas that are NOT inside parentheses or string literals
        String[] columns = splitOnTopLevelCommas(rest);

        if (columns.length <= 1) {
            return selectLine; // single column — no wrapping needed
        }

        String colIndent = "       "; // 7 spaces to align under SELECT
        StringBuilder sb = new StringBuilder("SELECT ").append(columns[0].trim());
        for (int i = 1; i < columns.length; i++) {
            sb.append(",\n").append(colIndent).append(columns[i].trim());
        }
        return sb.toString();
    }

    /**
     * Splits a string on commas that are at depth 0 (not inside parentheses).
     */
    private String[] splitOnTopLevelCommas(String s) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts.toArray(new String[0]);
    }
}

