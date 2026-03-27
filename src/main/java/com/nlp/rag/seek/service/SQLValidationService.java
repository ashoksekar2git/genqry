package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.DatabaseSchema;
import com.nlp.rag.seek.model.DatabaseTable;
import com.nlp.rag.seek.model.EavConfig;
import com.nlp.rag.seek.model.SchemaColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Validates a generated SQL string without executing it.
 *
 * Checks performed
 * ────────────────
 *  1.  Not empty / null
 *  2.  Starts with a recognised DML/DQL keyword
 *  3.  Balanced parentheses
 *  4.  No dangerous statements (DROP, TRUNCATE, CREATE, ALTER, GRANT, REVOKE)
 *  5.  Unknown table names → ERROR (sqlValid = false)
 *  6.  Column-level validation — every qualified column (alias.col) is checked
 *      against the columns of the table bound to that alias
 *  7.  JOIN correctness — detects independent-PK joins (e.g. e.id = a.id when
 *      no FK relationship exists) and flags them as errors
 *  8.  Hallucinated alias detection — aliases used in SELECT / WHERE / JOIN ON
 *      that were never defined in a FROM / JOIN clause
 *  9.  SELECT * warning
 *  10. Missing semicolon warning
 *  11. EAV-aware: warn when an EAV table is referenced but the attribute_name
 *      column is not used in a WHERE clause
 */
@Service
public class SQLValidationService {

    private static final Logger log = LoggerFactory.getLogger(SQLValidationService.class);

    // ── Patterns ──────────────────────────────────────────────────────────────

    private static final Pattern STARTS_WITH_DQL = Pattern.compile(
            "^\\s*(SELECT|INSERT|UPDATE|DELETE|WITH|CALL)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<Pattern> DANGER_PATTERNS = List.of(
            Pattern.compile("\\bDROP\\b",     Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bTRUNCATE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCREATE\\b",   Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bALTER\\b",    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bGRANT\\b",    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bREVOKE\\b",   Pattern.CASE_INSENSITIVE)
    );

    /**
     * Captures: FROM employees e  or  JOIN accounts a  or  JOIN "accounts" AS a
     * Group 1 = table name (may be quoted), Group 2 = alias (optional)
     */
    private static final Pattern FROM_JOIN_ALIAS = Pattern.compile(
            "(?:FROM|JOIN)\\s+[\"'`]?([\\w.]+)[\"'`]?(?:\\s+(?:AS\\s+)?([A-Za-z_][\\w]*))?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Qualified column reference: alias.column  or  alias."column"
     */
    private static final Pattern QUALIFIED_COL = Pattern.compile(
            "\\b([A-Za-z_][\\w]*)\\.[\"'`]?([A-Za-z_][\\w]*)[\"'`]?",
            Pattern.CASE_INSENSITIVE);

    /**
     * JOIN ON condition: alias1.col1 = alias2.col2
     */
    private static final Pattern JOIN_ON_CONDITION = Pattern.compile(
            "\\bON\\s+([A-Za-z_][\\w]*)\\.(\"?[\\w]+\"?)\\s*=\\s*([A-ZaZ_][\\w]*)\\.(\"?[\\w]+\"?)",
            Pattern.CASE_INSENSITIVE);

    /** SQL keywords that look like table aliases but aren't */
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "select","from","where","join","inner","left","right","full","outer","cross",
            "on","as","and","or","not","in","is","null","like","between","exists",
            "having","group","order","by","limit","offset","union","all","distinct",
            "case","when","then","else","end","true","false","asc","desc",
            "count","sum","avg","min","max","coalesce","concat","cast","convert",
            "current_date","current_timestamp","interval","extract","date_trunc",
            "with","insert","update","delete","set","values","into","returning"
    );

    /**
     * System catalog schemas that are valid SQL targets — never flag as hallucinated.
     * These are built-in schemas in PostgreSQL, MySQL, SQL Server, etc.
     */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "pg_toast",
            "mysql", "sys", "performance_schema",
            "public"
    );

    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {}

    // =========================================================================
    // Semantic intent concept groups — used for intent mismatch detection
    // =========================================================================

    /**
     * Maps a "requested concept" (from the NL prompt) to the column name keywords
     * that would legitimately satisfy it. If NONE of those keywords appear in the
     * generated SQL's SELECT list, it means the LLM silently omitted or substituted
     * the requested data.
     *
     * Format:  concept keywords (comma-separated, all lower-case)  →
     *          acceptable SQL column name fragments (lower-case)
     */
    private static final Map<List<String>, List<String>> INTENT_CONCEPT_MAP;
    static {
        Map<List<String>, List<String>> m = new LinkedHashMap<>();
        // Physical / postal address
        m.put(List.of("address","home address","personal address","postal address",
                       "mailing address","residential address","street","city","zip","zipcode",
                       "state","country","location"),
              List.of("address","street","city","zip","state","country","location","postal","residence"));
        // Phone / mobile
        m.put(List.of("phone","phone number","mobile","mobile number","cell","telephone"),
              List.of("phone","mobile","cell","telephone","contact_number","tel"));
        // Date of birth / age
        m.put(List.of("date of birth","dob","birth date","birthday","age"),
              List.of("dob","birth","age","born"));
        // SSN / national id
        m.put(List.of("ssn","social security","national id","national number","tax id","tin"),
              List.of("ssn","social","national","tax_id","tin","identity"));
        // Photo / profile picture
        m.put(List.of("photo","picture","profile picture","image","avatar"),
              List.of("photo","picture","image","avatar","profile_pic"));
        INTENT_CONCEPT_MAP = Collections.unmodifiableMap(m);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** Convenience overload — no NL query, skips semantic intent mismatch check. */
    public ValidationResult validate(String sql, DatabaseSchema schema) {
        return validate(sql, schema, null);
    }

    public ValidationResult validate(String sql, DatabaseSchema schema, String originalNlQuery) {
        List<String> errors   = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (sql == null || sql.isBlank()) {
            errors.add("SQL is empty or null.");
            return new ValidationResult(false, errors, warnings);
        }

        String trimmed = sql.trim();

        // 1 ── keyword check
        if (!STARTS_WITH_DQL.matcher(trimmed).find()) {
            errors.add("SQL does not start with a recognised keyword (SELECT/INSERT/UPDATE/DELETE/WITH).");
        }

        // 2 ── no dangerous DDL
        for (Pattern p : DANGER_PATTERNS) {
            if (p.matcher(trimmed).find()) {
                errors.add("Dangerous statement detected: " + p.pattern());
            }
        }

        // 3 ── balanced parentheses
        int depth = 0;
        for (char ch : trimmed.toCharArray()) {
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            if (depth < 0) { errors.add("Unmatched closing parenthesis."); break; }
        }
        if (depth > 0) errors.add("Unclosed parenthesis (depth=" + depth + ").");

        // ── Build schema lookup maps ─────────────────────────────────────────
        // knownTables  : lower(tableName) → DatabaseTable
        // aliasMap     : lower(alias)     → DatabaseTable  (populated from FROM/JOIN)
        Map<String, DatabaseTable> knownTables = new LinkedHashMap<>();
        if (schema != null && schema.getTables() != null) {
            for (DatabaseTable t : schema.getTables()) {
                if (t.getTableName() != null)
                    knownTables.put(t.getTableName().toLowerCase(), t);
            }
        }

        // ── Parse alias → table bindings from SQL ────────────────────────────
        Map<String, DatabaseTable> aliasMap  = new LinkedHashMap<>();  // alias  → table
        Map<String, String>        aliasToTableName = new LinkedHashMap<>(); // alias → raw table name
        Set<String> referencedTableNames = new LinkedHashSet<>();

        Matcher fjMatcher = FROM_JOIN_ALIAS.matcher(trimmed);
        while (fjMatcher.find()) {
            String rawTable = fjMatcher.group(1).replaceAll("[\"'`]", "");

            // Skip system catalog tables (information_schema.tables, pg_catalog.pg_class, etc.)
            if (rawTable.contains(".")) {
                String schemaPrefix = rawTable.substring(0, rawTable.indexOf('.')).toLowerCase();
                if (SYSTEM_SCHEMAS.contains(schemaPrefix)) {
                    // System catalog table — don't register as referenced, don't validate
                    String alias = fjMatcher.group(2);
                    if (alias != null && !alias.isBlank()) {
                        aliasToTableName.put(alias.toLowerCase(), rawTable);
                    }
                    continue;
                }
                // Non-system schema prefix — strip it (e.g. public.employees → employees)
                rawTable = rawTable.substring(rawTable.lastIndexOf('.') + 1);
            }

            String alias = fjMatcher.group(2);

            referencedTableNames.add(rawTable.toLowerCase());

            DatabaseTable dt = knownTables.get(rawTable.toLowerCase());
            // Map both the table name itself and any explicit alias
            if (dt != null) {
                aliasMap.put(rawTable.toLowerCase(), dt);
                aliasToTableName.put(rawTable.toLowerCase(), rawTable);
                if (alias != null && !alias.isBlank()) {
                    aliasMap.put(alias.toLowerCase(), dt);
                    aliasToTableName.put(alias.toLowerCase(), rawTable);
                }
            } else {
                // Unknown table — still register alias so alias-checks are accurate
                if (alias != null && !alias.isBlank()) {
                    aliasToTableName.put(alias.toLowerCase(), rawTable);
                }
            }
        }

        // 4 ── Unknown table check → ERROR (sqlValid = false)
        for (String ref : referencedTableNames) {
            if (!knownTables.containsKey(ref)) {
                errors.add("Unknown table '" + ref + "' referenced in SQL — not found in schema. "
                        + "LLM may have hallucinated this table name.");
            }
        }

        // 5 ── Hallucinated alias detection
        validateAliases(trimmed, aliasMap, aliasToTableName, knownTables, errors);

        // 6 ── Column-level validation
        if (!aliasMap.isEmpty()) {
            validateColumns(trimmed, aliasMap, errors, warnings);
        }

        // 7 ── JOIN correctness check
        validateJoins(trimmed, aliasMap, errors);

        // 8 ── SELECT * warning
        if (trimmed.toUpperCase().contains("SELECT *") || trimmed.toUpperCase().contains("SELECT\n*")) {
            warnings.add("SELECT * used – consider selecting specific columns for clarity.");
        }

        // 9 ── missing semicolon warning
        if (!trimmed.endsWith(";")) {
            warnings.add("SQL does not end with a semicolon.");
        }

        // 10 ── EAV-aware validation
        if (schema != null && schema.getTables() != null) {
            validateEavUsage(trimmed, referencedTableNames, schema, warnings);
        }

        // 11 ── Semantic intent mismatch check
        // Detects when the user asked for data (e.g. "personal address") that
        // does not exist as a column in ANY table in the schema, yet the LLM
        // silently returned a different column (e.g. "email") instead of
        // reporting that the data is unavailable.
        if (originalNlQuery != null && !originalNlQuery.isBlank() && schema != null) {
            validateSemanticIntent(trimmed, originalNlQuery, schema, referencedTableNames, errors);
        }

        boolean valid = errors.isEmpty();
        log.debug("SQL validation: valid={}, errors={}, warnings={}", valid, errors, warnings);
        return new ValidationResult(valid, errors, warnings);
    }

    // =========================================================================
    // Check 5: Hallucinated alias detection
    // =========================================================================

    /**
     * Scans every qualified reference (alias.column) in the SQL.
     * If the alias was never bound to a table in any FROM/JOIN clause,
     * it is flagged as a hallucinated alias.
     */
    private void validateAliases(String sql,
                                  Map<String, DatabaseTable> aliasMap,
                                  Map<String, String> aliasToTableName,
                                  Map<String, DatabaseTable> knownTables,
                                  List<String> errors) {
        // All defined aliases (both explicit aliases and bare table names used as identifiers)
        Set<String> definedAliases = new HashSet<>(aliasMap.keySet());
        definedAliases.addAll(aliasToTableName.keySet());

        Set<String> reported = new HashSet<>();
        Matcher m = QUALIFIED_COL.matcher(sql);
        while (m.find()) {
            String alias = m.group(1).toLowerCase();
            // Skip SQL keywords, function names, schema prefixes
            if (SQL_KEYWORDS.contains(alias)) continue;
            // Skip system catalog schema prefixes (information_schema, pg_catalog, etc.)
            if (SYSTEM_SCHEMAS.contains(alias)) continue;
            // Skip if it looks like a schema-qualified table name (public.employees)
            if (knownTables.containsKey(alias)) continue;

            if (!definedAliases.contains(alias) && !reported.contains(alias)) {
                errors.add("Hallucinated alias '" + m.group(1) + "' used in SQL "
                        + "('" + m.group(1) + "." + m.group(2) + "') but never defined "
                        + "in any FROM or JOIN clause. LLM invented this alias.");
                reported.add(alias);
            }
        }
    }

    // =========================================================================
    // Check 6: Column-level validation
    // =========================================================================

    /**
     * For every qualified column reference (alias.column) in the SQL:
     * - Resolve the alias to a DatabaseTable via aliasMap
     * - Verify the column name exists in that table's columns (case-insensitive)
     * - If it doesn't exist → error (hallucinated column)
     *
     * Unqualified column references (bare names) are skipped because they could
     * resolve to any table and produce too many false positives.
     */
    private void validateColumns(String sql,
                                  Map<String, DatabaseTable> aliasMap,
                                  List<String> errors,
                                  List<String> warnings) {
        Set<String> reported = new HashSet<>();
        Matcher m = QUALIFIED_COL.matcher(sql);

        while (m.find()) {
            String alias  = m.group(1).toLowerCase();
            String colRef = m.group(2).replaceAll("[\"'`]", "").toLowerCase();

            if (SQL_KEYWORDS.contains(alias)) continue;

            DatabaseTable table = aliasMap.get(alias);
            if (table == null) continue; // alias not resolved — handled by alias check

            if (table.getColumns() == null || table.getColumns().isEmpty()) continue;

            // Build the set of known column names for this table (lower-case)
            Set<String> knownCols = table.getColumns().stream()
                    .filter(c -> c.getName() != null)
                    .map(c -> c.getName().toLowerCase())
                    .collect(Collectors.toSet());

            // Also include human-readable / expanded names as valid references
            table.getColumns().stream()
                    .filter(c -> c.getHumanReadableName() != null)
                    .forEach(c -> knownCols.add(c.getHumanReadableName().toLowerCase().replace(" ", "_")));

            String key = alias + "." + colRef;
            if (!knownCols.contains(colRef) && !reported.contains(key)) {
                errors.add("Hallucinated column: '" + m.group(1) + "." + m.group(2)
                        + "' — column '" + m.group(2) + "' does not exist in table '"
                        + table.getTableName() + "'. "
                        + "Known columns: " + knownCols.stream().sorted().collect(Collectors.joining(", ")));
                reported.add(key);
            }
        }
    }

    // =========================================================================
    // Check 7: JOIN correctness
    // =========================================================================

    /**
     * Inspects every JOIN ON condition of the form:
     *   alias1.col1 = alias2.col2
     *
     * Flags as an error when:
     *   (a) Both col1 and col2 are primary keys on their respective tables
     *       AND there is no FK relationship between the two tables → independent-PK join
     *   (b) The ON condition references a column on the wrong side that doesn't
     *       exist in the declared table for that alias → impossible join
     *
     * This catches the classic hallucination:
     *   JOIN accounts a ON e.id = a.id
     * where employees.id and accounts.id are independent sequences.
     */
    private void validateJoins(String sql,
                                Map<String, DatabaseTable> aliasMap,
                                List<String> errors) {

        Matcher m = JOIN_ON_CONDITION.matcher(sql);
        while (m.find()) {
            String alias1 = m.group(1).toLowerCase();
            String col1   = m.group(2).replaceAll("[\"'`]", "").toLowerCase();
            String alias2 = m.group(3).toLowerCase();
            String col2   = m.group(4).replaceAll("[\"'`]", "").toLowerCase();

            DatabaseTable table1 = aliasMap.get(alias1);
            DatabaseTable table2 = aliasMap.get(alias2);

            if (table1 == null || table2 == null) continue;

            SchemaColumn schCol1 = findColumn(table1, col1);
            SchemaColumn schCol2 = findColumn(table2, col2);

            if (schCol1 == null || schCol2 == null) {
                // Column not found on one side — already caught by column validation
                continue;
            }

            // ── (a) Independent-PK join detection ────────────────────────────
            // Both columns are PKs on their own tables.
            // Check if any FK relationship exists between them.
            if (schCol1.isPrimaryKey() && schCol2.isPrimaryKey()) {
                boolean fkExists = hasForeignKeyRelationship(table1, col1, table2, col2)
                                || hasForeignKeyRelationship(table2, col2, table1, col1);

                if (!fkExists) {
                    errors.add("Incorrect JOIN: '"
                            + m.group(1) + "." + m.group(2) + " = "
                            + m.group(3) + "." + m.group(4) + "' — both '"
                            + col1 + "' on '" + table1.getTableName()
                            + "' and '" + col2 + "' on '" + table2.getTableName()
                            + "' are independent primary keys with no foreign-key relationship. "
                            + "This JOIN will produce incorrect results (cartesian match on sequence numbers). "
                            + buildJoinHint(table1, table2));
                }
            }

            // ── (b) FK exists but is being used the wrong way round ───────────
            // e.g. table1.col1 has FK → table2.someOtherCol, but col2 ≠ someOtherCol
            SchemaColumn fkCol = findForeignKeyColumn(table1, table2);
            if (fkCol != null && !fkCol.getName().equalsIgnoreCase(col1)) {
                // The correct FK column is different from what the LLM used
                errors.add("Suspicious JOIN on '" + table1.getTableName()
                        + "': expected FK column '" + fkCol.getName()
                        + "' → '" + table2.getTableName()
                        + "' but SQL joins on '" + col1 + "'. "
                        + "Possible hallucination — verify the join condition.");
            }
        }
    }

    // =========================================================================
    // JOIN helper utilities
    // =========================================================================

    /** Finds a column (case-insensitive) in a table; returns null if not found. */
    private SchemaColumn findColumn(DatabaseTable table, String colName) {
        if (table.getColumns() == null) return null;
        return table.getColumns().stream()
                .filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(colName))
                .findFirst().orElse(null);
    }

    /**
     * Returns true if {@code srcTable} has a column whose FK reference points to
     * {@code targetTable.targetCol}.
     */
    private boolean hasForeignKeyRelationship(DatabaseTable srcTable, String srcCol,
                                               DatabaseTable targetTable, String targetCol) {
        if (srcTable.getColumns() == null) return false;
        String targetTableLower = targetTable.getTableName().toLowerCase();
        for (SchemaColumn col : srcTable.getColumns()) {
            if (!col.getName().equalsIgnoreCase(srcCol)) continue;
            String fkRef = col.getForeignKeyReference();
            if (fkRef == null || fkRef.isBlank()) continue;
            // FK ref format: "tableName.columnName"
            String fkLower = fkRef.toLowerCase();
            if (fkLower.startsWith(targetTableLower + ".")) return true;
            if (fkLower.equals(targetTableLower)) return true;
        }
        return false;
    }

    /**
     * Finds any FK column in srcTable that references targetTable.
     * Returns null if no such column exists.
     */
    private SchemaColumn findForeignKeyColumn(DatabaseTable srcTable, DatabaseTable targetTable) {
        if (srcTable.getColumns() == null) return null;
        String targetLower = targetTable.getTableName().toLowerCase();
        return srcTable.getColumns().stream()
                .filter(c -> {
                    String fk = c.getForeignKeyReference();
                    return fk != null && fk.toLowerCase().startsWith(targetLower + ".");
                })
                .findFirst().orElse(null);
    }

    /**
     * Builds a human-readable hint about how to correctly join two tables.
     * Looks for FK columns pointing between them; suggests CONCAT() pattern
     * when the only link is a name-match column (account_holder_name etc.).
     */
    private String buildJoinHint(DatabaseTable t1, DatabaseTable t2) {
        // Look for FK from t1 → t2
        SchemaColumn fkCol = findForeignKeyColumn(t1, t2);
        if (fkCol != null) {
            String fkRef = fkCol.getForeignKeyReference();
            return "Hint: use '" + t1.getTableName() + "." + fkCol.getName()
                    + " = " + fkRef + "' instead.";
        }
        // Look for FK from t2 → t1
        fkCol = findForeignKeyColumn(t2, t1);
        if (fkCol != null) {
            String fkRef = fkCol.getForeignKeyReference();
            return "Hint: use '" + t2.getTableName() + "." + fkCol.getName()
                    + " = " + fkRef + "' instead.";
        }
        // Check business_context of either table for a join hint
        String ctx1 = t1.getBusinessContext();
        String ctx2 = t2.getBusinessContext();
        if (ctx1 != null && ctx1.toLowerCase().contains("join")) {
            return "Hint (from business context): " + ctx1;
        }
        if (ctx2 != null && ctx2.toLowerCase().contains("join")) {
            return "Hint (from business context): " + ctx2;
        }
        return "No foreign-key relationship found between '"
                + t1.getTableName() + "' and '" + t2.getTableName()
                + "'. Check schema for correct join column.";
    }

    // =========================================================================
    // Check 11: EAV-aware validation
    // =========================================================================

    private void validateEavUsage(String sql,
                                   Set<String> referencedTables,
                                   DatabaseSchema schema,
                                   List<String> warnings) {
        String sqlUpper = sql.toUpperCase();

        for (DatabaseTable table : schema.getTables()) {
            if (!table.isEavTable()) continue;
            String tableLower = table.getTableName().toLowerCase();
            if (!referencedTables.contains(tableLower)) continue;

            EavConfig eav = table.getEavConfig();
            if (eav == null) continue;

            String attrNameCol = eav.resolveAttributeNameColumn().toUpperCase();

            if (!sqlUpper.contains(attrNameCol)) {
                warnings.add(
                    "EAV table '" + table.getTableName() + "' is referenced, but the attribute "
                    + "name column '" + eav.resolveAttributeNameColumn() + "' does not appear in the SQL. "
                    + "Did the LLM use an attribute key as a column name? "
                    + "Expected pattern: WHERE " + eav.resolveAttributeNameColumn() + " = '<key>'");
            }

            if (eav.getKnownAttributes() != null) {
                for (var attr : eav.getKnownAttributes()) {
                    String key = attr.getAttributeKey().toUpperCase();
                    Pattern bareColPattern = Pattern.compile(
                        "\\bSELECT\\b[^;]*\\b" + Pattern.quote(key) + "\\b",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                    Pattern inStringPattern = Pattern.compile(
                        "'[^']*\\b" + Pattern.quote(attr.getAttributeKey()) + "\\b[^']*'",
                        Pattern.CASE_INSENSITIVE);
                    if (bareColPattern.matcher(sql).find()
                            && !inStringPattern.matcher(sql).find()) {
                        warnings.add(
                            "EAV attribute '" + attr.getAttributeKey() + "' from table '"
                            + table.getTableName() + "' appears to be used as a column name in SELECT. "
                            + "Correct pattern: SELECT "
                            + attr.resolveCastHint(eav.resolveAttributeValueColumn())
                            + " AS " + attr.getAttributeKey() + " ... WHERE "
                            + eav.resolveAttributeNameColumn() + " = '" + attr.getAttributeKey() + "'");
                    }
                }
            }
        }
    }

    // =========================================================================
    // Check 12: Semantic intent mismatch
    // =========================================================================

    /**
     * Detects when the user's NL query requested a concept (e.g. "personal address")
     * that does NOT exist as a column in ANY table of the schema, yet the LLM
     * silently returned a different column (e.g. "email") instead of reporting
     * that the data is unavailable.
     *
     * Example:
     *   User asks: "list all active employees and their personal address"
     *   Schema has: first_name, last_name, email, department, salary, status ...
     *   LLM returns: SELECT first_name, last_name, email ... WHERE status='ACTIVE'
     *   → Error: user asked for "address" but no address column exists in schema.
     *            email is NOT a substitute for personal/postal address.
     */
    private void validateSemanticIntent(String sql,
                                         String nlQuery,
                                         DatabaseSchema schema,
                                         Set<String> referencedTableNames,
                                         List<String> errors) {
        String nlLower = nlQuery.toLowerCase();

        for (Map.Entry<List<String>, List<String>> entry : INTENT_CONCEPT_MAP.entrySet()) {
            List<String> conceptKeywords   = entry.getKey();
            List<String> acceptableColumns = entry.getValue();

            // 1. Does the user's query mention this concept?
            String matchedConcept = null;
            for (String kw : conceptKeywords) {
                if (nlLower.contains(kw)) { matchedConcept = kw; break; }
            }
            if (matchedConcept == null) continue;

            // 2. Does ANY acceptable column exist anywhere in the schema?
            boolean existsInSchema = false;
            outer:
            for (com.nlp.rag.seek.model.DatabaseTable table : schema.getTables()) {
                if (table.getColumns() == null) continue;
                for (com.nlp.rag.seek.model.SchemaColumn col : table.getColumns()) {
                    String colLower = col.getName().toLowerCase();
                    for (String ac : acceptableColumns) {
                        if (colLower.contains(ac)) { existsInSchema = true; break outer; }
                    }
                }
            }
            if (existsInSchema) continue;  // data is somewhere in schema — not a mismatch

            // 3. Data does not exist — collect available columns for the error message
            List<String> availableCols = new ArrayList<>();
            for (com.nlp.rag.seek.model.DatabaseTable table : schema.getTables()) {
                if (!referencedTableNames.contains(table.getTableName().toLowerCase())) continue;
                if (table.getColumns() == null) continue;
                for (com.nlp.rag.seek.model.SchemaColumn col : table.getColumns()) {
                    availableCols.add(table.getTableName() + "." + col.getName());
                }
            }
            String availStr = availableCols.isEmpty() ? "no column details available"
                    : String.join(", ", availableCols);

            errors.add("Data not available in schema: user requested '" + matchedConcept
                    + "' but no column matching this concept exists in any table. "
                    + "The LLM substituted an unrelated column — email is NOT a personal/postal address. "
                    + "Available columns in referenced table(s): " + availStr);

            log.warn("Semantic intent mismatch — concept='{}' not found in schema. query='{}'",
                    matchedConcept, nlQuery);
        }
    }
}
