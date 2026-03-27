package com.nlp.rag.seek.service;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Validates and parses an uploaded .sql DDL file, then extracts schema details.
 *
 * Validation uses {@link CCJSqlParserUtil} (JSQLParser) to parse every
 * semicolon-delimited statement and report precise per-statement errors.
 * Only files where ALL statements pass JSQLParser proceed to schema extraction.
 *
 * Extracted schema details:
 *   - Table names
 *   - Columns (name, data type, nullable, default value)
 *   - Primary keys  (inline and table-level CONSTRAINT)
 *   - Foreign keys  (inline REFERENCES and table-level CONSTRAINT)
 *   - Unique constraints
 *   - Indexes (CREATE INDEX / CREATE UNIQUE INDEX)
 *   - Table-level comments (COMMENT ON TABLE)
 *   - Column-level comments (COMMENT ON COLUMN)
 *
 * Supports PostgreSQL, MySQL, and standard ANSI SQL DDL syntax.
 */
@Service
public class SqlFileSchemaExtractorService {

    private static final Logger log = LoggerFactory.getLogger(SqlFileSchemaExtractorService.class);


    // ── Regex patterns ────────────────────────────────────────────────────────

    // CREATE TABLE [IF NOT EXISTS] [schema.]tableName (
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:[\\w`\"]+\\.)?([\\w`\"]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    // PRIMARY KEY (col1, col2, ...)  — table-level constraint
    private static final Pattern TABLE_PK = Pattern.compile(
            "(?:CONSTRAINT\\s+[\\w`\"]+\\s+)?PRIMARY\\s+KEY\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    // UNIQUE (col1, col2, ...) or CONSTRAINT name UNIQUE (...)
    private static final Pattern TABLE_UNIQUE = Pattern.compile(
            "(?:CONSTRAINT\\s+[\\w`\"]+\\s+)?UNIQUE\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    // FOREIGN KEY (fk_col) REFERENCES ref_table(ref_col)
    private static final Pattern TABLE_FK = Pattern.compile(
            "(?:CONSTRAINT\\s+([\\w`\"]+)\\s+)?FOREIGN\\s+KEY\\s*\\(([^)]+)\\)\\s+REFERENCES\\s+(?:[\\w`\"]+\\.)?([\\w`\"]+)\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    // Inline REFERENCES ref_table(ref_col) on a column definition
    private static final Pattern INLINE_FK = Pattern.compile(
            "REFERENCES\\s+(?:[\\w`\"]+\\.)?([\\w`\"]+)\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    // CREATE [UNIQUE] INDEX name ON table(col, ...)
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w`\"]+)\\s+ON\\s+(?:[\\w`\"]+\\.)?([\\w`\"]+)\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    // COMMENT ON TABLE tbl IS 'text'
    private static final Pattern COMMENT_TABLE = Pattern.compile(
            "COMMENT\\s+ON\\s+TABLE\\s+(?:[\\w`\"]+\\.)?([\\w`\"]+)\\s+IS\\s+'([^']*)'",
            Pattern.CASE_INSENSITIVE);

    // COMMENT ON COLUMN tbl.col IS 'text'
    private static final Pattern COMMENT_COLUMN = Pattern.compile(
            "COMMENT\\s+ON\\s+COLUMN\\s+(?:[\\w`\"]+\\.)?([\\w`\"]+)\\.([\\w`\"]+)\\s+IS\\s+'([^']*)'",
            Pattern.CASE_INSENSITIVE);

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Convenience overload — reads the file bytes internally.
     * Used by POST /sql/parse (dry-run) where the caller has not pre-read the bytes.
     */
    public SqlSchemaResult extractSchema(MultipartFile file) {
        // ── File-level validation first (before touching the stream) ──────────
        List<String> fileErrors = validateFile(file);
        if (!fileErrors.isEmpty()) {
            SqlSchemaResult r = new SqlSchemaResult();
            r.setValid(false);
            r.setValidationErrors(fileErrors);
            return r;
        }

        // ── Read bytes ────────────────────────────────────────────────────────
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            SqlSchemaResult r = new SqlSchemaResult();
            r.setValid(false);
            r.setValidationErrors(Collections.singletonList(
                    "Failed to read file content: " + e.getMessage()));
            return r;
        }

        return extractSchema(
                new String(bytes, StandardCharsets.UTF_8),
                file.getOriginalFilename(),
                file.getSize());
    }

    /**
     * Primary entry point — validates and extracts schema from raw SQL text.
     * The controller reads {@code file.getBytes()} once and calls this so the
     * MultipartFile InputStream is never consumed twice.
     *
     * @param rawSql    full SQL text of the uploaded file
     * @param fileName  original filename  (used for validation + response)
     * @param fileSize  file size in bytes (used for size validation)
     */
    public SqlSchemaResult extractSchema(String rawSql, String fileName, long fileSize) {
        SqlSchemaResult result = new SqlSchemaResult();

        // ── Step 1: File-level validation ─────────────────────────────────────
        List<String> fileErrors = validateFileMeta(fileName, fileSize);
        result.setValidationErrors(new ArrayList<>(fileErrors));
        if (!fileErrors.isEmpty()) {
            result.setValid(false);
            return result;
        }

        // ── Step 2: Strip SQL comments ────────────────────────────────────────
        String cleaned;
        try {
            cleaned = stripSqlComments(rawSql);
        } catch (Exception e) {
            result.setValid(false);
            result.getValidationErrors().add("Failed to process SQL content: " + e.getMessage());
            return result;
        }

        // ── Step 3: JSQLParser validation ─────────────────────────────────────
        List<String> syntaxErrors = validateSyntax(cleaned);
        result.getValidationErrors().addAll(syntaxErrors);
        if (!syntaxErrors.isEmpty()) {
            result.setValid(false);
            return result;
        }

        result.setValid(true);
        result.setFileName(fileName);
        result.setFileSizeBytes(fileSize);

        // ── Step 4: Extract tables ─────────────────────────────────────────────
        try {
            List<TableExtract> tables = extractTables(cleaned);
            applyComments(cleaned, tables);
            List<IndexExtract> indexes = extractIndexes(cleaned);

            result.setTables(tables);
            result.setIndexes(indexes);
            result.setTotalTables(tables.size());
            result.setTotalColumns(tables.stream().mapToInt(t -> t.getColumns().size()).sum());
            result.setTotalIndexes(indexes.size());

            log.info("SQL file '{}' parsed: {} table(s), {} column(s), {} index(es)",
                    fileName, tables.size(), result.getTotalColumns(), indexes.size());
        } catch (Exception e) {
            log.error("Schema extraction failed for '{}': {}", fileName, e.getMessage(), e);
            result.setValid(false);
            result.getValidationErrors().add("Schema extraction failed: " + e.getMessage());
        }

        return result;
    }

    // =========================================================================
    // Validation
    // =========================================================================

    /** Validates only file name and size — never touches the InputStream. */
    private List<String> validateFileMeta(String fileName, long fileSize) {
        List<String> errors = new ArrayList<>();

        if (fileName == null || fileName.isBlank()) {
            errors.add("File name is missing.");
            return errors;
        }
        if (!fileName.toLowerCase().endsWith(".sql")) {
            errors.add("Invalid file type: only .sql files are accepted (got '" + fileName + "').");
        }
        long maxBytes = 10L * 1024 * 1024; // 10 MB
        if (fileSize > maxBytes) {
            errors.add("File too large: maximum allowed size is 10 MB (got "
                    + (fileSize / 1024) + " KB).");
        }
        return errors;
    }

    /** Validates a MultipartFile before the InputStream is touched. */
    private List<String> validateFile(MultipartFile file) {
        List<String> errors = new ArrayList<>();
        if (file == null || file.isEmpty()) {
            errors.add("File is empty or missing.");
            return errors;
        }
        errors.addAll(validateFileMeta(file.getOriginalFilename(), file.getSize()));
        return errors;
    }

    /**
     * Validates the SQL using JSQLParser {@link CCJSqlParserUtil}.
     *
     * Strategy:
     *  1. Strip COMMENT ON … statements (JSQLParser doesn't support them — not an error).
     *  2. Strip ON DELETE / ON UPDATE referential actions (RESTRICT, CASCADE, SET NULL,
     *     SET DEFAULT, NO ACTION) which are valid PostgreSQL DDL but not supported by
     *     JSQLParser — removing them does not change structural correctness for parsing.
     *  3. Parse each semicolon-delimited statement individually so we can report
     *     which exact statement failed and why.
     *  4. Ensure at least one CREATE TABLE statement is present.
     *  5. Any parse failure → added to error list as
     *     "Statement N: <first 80 chars>… → <parser message>"
     *
     * Returns an empty list when all statements are valid.
     */
    private static final Pattern ON_DELETE_UPDATE = Pattern.compile(
            "\\bON\\s+(DELETE|UPDATE)\\s+(RESTRICT|CASCADE|SET\\s+NULL|SET\\s+DEFAULT|NO\\s+ACTION)\\b",
            Pattern.CASE_INSENSITIVE);

    private List<String> validateSyntax(String sql) {
        List<String> errors = new ArrayList<>();

        // ── Pre-filter: strip COMMENT ON … ; lines (not supported by JSQLParser) ──
        String filtered = Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !s.toUpperCase().startsWith("COMMENT"))
                .collect(Collectors.joining("; "))
                .trim();

        if (filtered.isEmpty()) {
            errors.add("No executable SQL statements found in the file.");
            return errors;
        }

        // ── Parse each statement with JSQLParser ──────────────────────────────
        boolean hasCreateTable = false;
        String[] stmts = filtered.split(";");
        int stmtNum = 0;

        for (String raw : stmts) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            stmtNum++;

            // Strip ON DELETE/UPDATE referential actions — valid PostgreSQL DDL but
            // unsupported by JSQLParser (e.g. ON DELETE RESTRICT, ON UPDATE CASCADE)
            String sForParsing = ON_DELETE_UPDATE.matcher(s).replaceAll("");

            try {
                Statement parsed = CCJSqlParserUtil.parse(sForParsing);

                if (parsed instanceof CreateTable) {
                    hasCreateTable = true;
                    log.debug("JSQLParser ✔ Statement {}: CREATE TABLE '{}'",
                            stmtNum, ((CreateTable) parsed).getTable().getName());
                } else if (parsed instanceof CreateIndex) {
                    log.debug("JSQLParser ✔ Statement {}: CREATE INDEX", stmtNum);
                } else {
                    log.debug("JSQLParser ✔ Statement {}: {}", stmtNum,
                            parsed.getClass().getSimpleName());
                }

            } catch (Exception e) {
                String preview = s.length() > 80 ? s.substring(0, 80) + "…" : s;
                String msg = "Statement " + stmtNum + " [" + preview + "] → "
                        + e.getMessage().replaceAll("\\s+", " ").trim();
                log.warn("JSQLParser ✘ {}", msg);
                errors.add(msg);
            }
        }

        if (!hasCreateTable && errors.isEmpty()) {
            errors.add("No CREATE TABLE statement found in the file. "
                    + "The SQL file must contain at least one CREATE TABLE.");
        }

        if (errors.isEmpty()) {
            log.info("JSQLParser validation passed — {} statement(s) OK", stmtNum);
        } else {
            log.warn("JSQLParser validation found {} error(s) in {} statement(s)",
                    errors.size(), stmtNum);
        }

        return errors;
    }

    // =========================================================================
    // Table extraction
    // =========================================================================

    private List<TableExtract> extractTables(String sql) {
        List<TableExtract> tables = new ArrayList<>();
        Matcher ctMatcher = CREATE_TABLE.matcher(sql);

        while (ctMatcher.find()) {
            String tableName = unquote(ctMatcher.group(1));
            int bodyStart    = ctMatcher.end();

            // Find the matching closing paren for this CREATE TABLE block
            String body = extractParenBody(sql, bodyStart - 1);
            if (body == null) {
                log.warn("Could not find body for table '{}'", tableName);
                continue;
            }

            TableExtract table = new TableExtract();
            table.setTableName(tableName);

            // Split body into comma-separated clauses (respecting nested parens)
            List<String> clauses = splitClauses(body);

            Set<String> pkCols     = new LinkedHashSet<>();
            Set<String> uniqueCols = new LinkedHashSet<>();
            List<ForeignKeyExtract> fks = new ArrayList<>();

            List<ColumnExtract> columns = new ArrayList<>();

            for (String clause : clauses) {
                String trimmed = clause.trim();
                if (trimmed.isEmpty()) continue;

                String upper = trimmed.toUpperCase();

                // ── Table-level PRIMARY KEY ────────────────────────────────────
                // Must distinguish table-level "PRIMARY KEY (col1, col2)" from
                // inline column-level "col_name TYPE PRIMARY KEY".
                // Table-level: starts with optional CONSTRAINT + PRIMARY KEY, or
                // the clause is ONLY a PK constraint (no column name + type before it).
                // Inline: a column definition that CONTAINS "PRIMARY KEY" but does
                // NOT have PRIMARY KEY followed by "(" — it's just a modifier.
                if (isTableLevelPrimaryKey(trimmed, upper)) {
                    Matcher m = TABLE_PK.matcher(trimmed);
                    if (m.find()) {
                        for (String col : m.group(1).split(",")) {
                            pkCols.add(unquote(col.trim()));
                        }
                    }
                    continue;
                }

                // ── Table-level UNIQUE ─────────────────────────────────────────
                // Same logic: only match table-level UNIQUE (col, ...) constraints,
                // not inline "col_name TYPE UNIQUE".
                if (isTableLevelUnique(trimmed, upper)) {
                    Matcher m = TABLE_UNIQUE.matcher(trimmed);
                    if (m.find()) {
                        for (String col : m.group(1).split(",")) {
                            uniqueCols.add(unquote(col.trim()));
                        }
                    }
                    continue;
                }

                // ── Table-level FOREIGN KEY ────────────────────────────────────
                if (upper.contains("FOREIGN KEY")) {
                    Matcher m = TABLE_FK.matcher(trimmed);
                    if (m.find()) {
                        String constraintName = m.group(1) != null ? unquote(m.group(1)) : null;
                        String fkColsStr = m.group(2);
                        String refTable  = unquote(m.group(3));
                        String refCols   = m.group(4);
                        for (String fkCol : fkColsStr.split(",")) {
                            ForeignKeyExtract fk = new ForeignKeyExtract();
                            fk.setConstraintName(constraintName);
                            fk.setColumn(unquote(fkCol.trim()));
                            fk.setReferencedTable(refTable);
                            fk.setReferencedColumn(unquote(refCols.trim().split(",")[0]));
                            fks.add(fk);
                        }
                    }
                    continue;
                }

                // ── Column definition ──────────────────────────────────────────
                ColumnExtract col = parseColumnDefinition(trimmed);
                if (col != null) {
                    columns.add(col);
                }
            }

            // Apply table-level PKs to columns
            for (ColumnExtract col : columns) {
                if (pkCols.contains(col.getColumnName())) col.setPrimaryKey(true);
                if (uniqueCols.contains(col.getColumnName())) col.setUnique(true);
            }

            // Apply table-level FKs to columns
            for (ForeignKeyExtract fk : fks) {
                for (ColumnExtract col : columns) {
                    if (col.getColumnName().equalsIgnoreCase(fk.getColumn())) {
                        col.setForeignKey(true);
                        col.setForeignKeyReference(fk.getReferencedTable() + "." + fk.getReferencedColumn());
                        col.setFkConstraintName(fk.getConstraintName());
                    }
                }
            }

            table.setColumns(columns);
            table.setPrimaryKeyColumns(new ArrayList<>(pkCols.isEmpty()
                    ? columns.stream().filter(ColumnExtract::isPrimaryKey)
                             .map(ColumnExtract::getColumnName).collect(Collectors.toList())
                    : pkCols));
            table.setForeignKeys(fks);
            table.setUniqueColumns(new ArrayList<>(uniqueCols));
            tables.add(table);
        }

        return tables;
    }

    /**
     * Returns true if the clause is a TABLE-LEVEL PRIMARY KEY constraint,
     * NOT an inline column definition that happens to contain "PRIMARY KEY".
     *
     * Table-level:  "PRIMARY KEY (col1, col2)"  or  "CONSTRAINT pk_name PRIMARY KEY (col1)"
     * Inline:       "school_id SERIAL PRIMARY KEY"  (no parentheses after PRIMARY KEY)
     *
     * The key distinction: table-level has "PRIMARY KEY" followed by "(" with column list.
     * Inline column defs have "PRIMARY KEY" as a trailing modifier with NO parens after it.
     */
    private boolean isTableLevelPrimaryKey(String clause, String upper) {
        // If it starts with PRIMARY KEY or CONSTRAINT ... PRIMARY KEY → table-level
        if (upper.matches("^\\s*(?:CONSTRAINT\\s+\\S+\\s+)?PRIMARY\\s+KEY\\s*\\(.*")) {
            return true;
        }
        // If it contains PRIMARY KEY but the clause STARTS with an identifier + type,
        // it's an inline column definition (e.g., "school_id SERIAL PRIMARY KEY")
        // Check: does PRIMARY KEY appear followed by "(" ? If yes → table-level. If no → inline.
        Matcher pkWithParens = Pattern.compile(
                "PRIMARY\\s+KEY\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(clause);
        if (pkWithParens.find()) {
            // Could be table-level embedded after other text — but if the first token
            // looks like a column name + type, it's actually a column definition.
            // Safe heuristic: if it STARTS with an identifier that is NOT "PRIMARY"
            // or "CONSTRAINT", it's a column definition, not a table constraint.
            String firstToken = upper.trim().split("\\s+")[0];
            if (!firstToken.equals("PRIMARY") && !firstToken.equals("CONSTRAINT")) {
                return false;  // column definition like "id INT PRIMARY KEY(??)" — unusual but treat as column
            }
            return true;
        }
        return false;
    }

    /**
     * Returns true if the clause is a TABLE-LEVEL UNIQUE constraint,
     * NOT an inline column definition with "UNIQUE" modifier.
     *
     * Table-level:  "UNIQUE (email)"  or  "CONSTRAINT uq_name UNIQUE (col1, col2)"
     * Inline:       "email VARCHAR(255) UNIQUE NOT NULL"
     */
    private boolean isTableLevelUnique(String clause, String upper) {
        // Starts with UNIQUE ( or CONSTRAINT ... UNIQUE (
        if (upper.matches("^\\s*(?:CONSTRAINT\\s+\\S+\\s+)?UNIQUE\\s*\\(.*")) {
            return true;
        }
        return false;
    }

    // =========================================================================
    // Column definition parser
    // =========================================================================

    private ColumnExtract parseColumnDefinition(String clause) {
        String work = clause.trim();

        // Skip lines that are only constraint keywords
        String upper = work.toUpperCase();
        if (upper.startsWith("CONSTRAINT") || upper.startsWith("CHECK")
                || upper.startsWith("INDEX") || upper.startsWith("KEY ")) {
            return null;
        }

        // Split into tokens
        String[] tokens = work.split("\\s+", 3);
        if (tokens.length < 2) return null;

        String colName = unquote(tokens[0]);
        // Column name should be an identifier
        if (!colName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) return null;

        // Extract data type (everything up to a constraint keyword)
        String rest = tokens[1] + (tokens.length > 2 ? " " + tokens[2] : "");
        String dataType = extractDataType(rest);

        ColumnExtract col = new ColumnExtract();
        col.setColumnName(colName);
        col.setDataType(dataType);

        // Nullable — NOT NULL present?
        col.setNullable(!upper.contains("NOT NULL"));

        // Default value
        Pattern defPat = Pattern.compile(
                "DEFAULT\\s+([^,\\s]+(?:\\s*::[^,\\s]+)?)", Pattern.CASE_INSENSITIVE);
        Matcher defMatcher = defPat.matcher(work);
        if (defMatcher.find()) {
            col.setDefaultValue(defMatcher.group(1).trim().replaceAll("'", ""));
        }

        // Inline PRIMARY KEY
        if (upper.contains("PRIMARY KEY")) col.setPrimaryKey(true);

        // Inline UNIQUE
        if (upper.contains("UNIQUE") && !upper.contains("FOREIGN")) col.setUnique(true);

        // Inline AUTO_INCREMENT / SERIAL / GENERATED
        if (upper.contains("AUTO_INCREMENT") || upper.contains("SERIAL")
                || upper.contains("GENERATED") || upper.contains("IDENTITY")) {
            col.setAutoIncrement(true);
        }

        // Inline REFERENCES
        Matcher fkMatcher = INLINE_FK.matcher(work);
        if (fkMatcher.find()) {
            col.setForeignKey(true);
            col.setForeignKeyReference(unquote(fkMatcher.group(1)) + "." + unquote(fkMatcher.group(2).trim().split(",")[0]));
        }

        return col;
    }

    private String extractDataType(String rest) {
        // Match type name optionally followed by (precision,scale)
        Pattern typePat = Pattern.compile(
                "^(\\w+(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*\\))?)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = typePat.matcher(rest.trim());
        return m.find() ? m.group(1).trim().toUpperCase() : rest.trim().split("\\s+")[0].toUpperCase();
    }

    // =========================================================================
    // Index extraction
    // =========================================================================

    private List<IndexExtract> extractIndexes(String sql) {
        List<IndexExtract> indexes = new ArrayList<>();
        Matcher m = CREATE_INDEX.matcher(sql);
        while (m.find()) {
            IndexExtract idx = new IndexExtract();
            idx.setUnique(m.group(1) != null);
            idx.setIndexName(unquote(m.group(2)));
            idx.setTableName(unquote(m.group(3)));
            List<String> cols = Arrays.stream(m.group(4).split(","))
                    .map(String::trim).map(this::unquote).collect(Collectors.toList());
            idx.setColumns(cols);
            indexes.add(idx);
        }
        return indexes;
    }

    // =========================================================================
    // COMMENT ON TABLE / COLUMN overlay
    // =========================================================================

    private void applyComments(String sql, List<TableExtract> tables) {
        Map<String, TableExtract> tableMap = new LinkedHashMap<>();
        for (TableExtract t : tables) tableMap.put(t.getTableName().toLowerCase(), t);

        Matcher tm = COMMENT_TABLE.matcher(sql);
        while (tm.find()) {
            String tblName = unquote(tm.group(1)).toLowerCase();
            String comment = tm.group(2);
            TableExtract t = tableMap.get(tblName);
            if (t != null) t.setComment(comment);
        }

        Matcher cm = COMMENT_COLUMN.matcher(sql);
        while (cm.find()) {
            String tblName = unquote(cm.group(1)).toLowerCase();
            String colName = unquote(cm.group(2)).toLowerCase();
            String comment = cm.group(3);
            TableExtract t = tableMap.get(tblName);
            if (t == null) continue;
            t.getColumns().stream()
                    .filter(c -> c.getColumnName().equalsIgnoreCase(colName))
                    .findFirst()
                    .ifPresent(c -> c.setComment(comment));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Strips -- single-line comments and /* block comments *\/ from SQL. */
    private String stripSqlComments(String sql) {
        // Block comments
        sql = sql.replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        // Single-line comments
        sql = sql.replaceAll("--[^\n]*", " ");
        return sql;
    }

    /**
     * Extracts the content between the opening '(' at {@code startIdx} and its
     * matching closing ')'.  Returns just the inner content (without the parens).
     */
    private String extractParenBody(String sql, int startIdx) {
        int depth = 0;
        int begin = -1;
        for (int i = startIdx; i < sql.length(); i++) {
            if (sql.charAt(i) == '(') {
                depth++;
                if (depth == 1) begin = i + 1;
            } else if (sql.charAt(i) == ')') {
                depth--;
                if (depth == 0) return sql.substring(begin, i);
            }
        }
        return null;
    }

    /**
     * Splits a CREATE TABLE body into top-level comma-separated clauses,
     * respecting nested parentheses (e.g. type precision, CHECK(...)).
     */
    private List<String> splitClauses(String body) {
        List<String> clauses = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                clauses.add(body.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = body.substring(start).trim();
        if (!last.isEmpty()) clauses.add(last);
        return clauses;
    }

    /** Removes surrounding backticks, double-quotes, or square brackets. */
    private String unquote(String s) {
        if (s == null) return null;
        return s.trim()
                .replaceAll("^[`\"\\[]", "")
                .replaceAll("[`\"\\]]$", "");
    }

    // =========================================================================
    // Result & inner model classes
    // =========================================================================

    public static class SqlSchemaResult {
        private boolean valid;
        private String  fileName;
        private long    fileSizeBytes;
        private int     totalTables;
        private int     totalColumns;
        private int     totalIndexes;
        private List<String>         validationErrors = new ArrayList<>();
        private List<TableExtract>   tables           = new ArrayList<>();
        private List<IndexExtract>   indexes          = new ArrayList<>();

        public boolean isValid()                       { return valid; }
        public String  getFileName()                   { return fileName; }
        public long    getFileSizeBytes()              { return fileSizeBytes; }
        public int     getTotalTables()                { return totalTables; }
        public int     getTotalColumns()               { return totalColumns; }
        public int     getTotalIndexes()               { return totalIndexes; }
        public List<String>       getValidationErrors(){ return validationErrors; }
        public List<TableExtract> getTables()          { return tables; }
        public List<IndexExtract> getIndexes()         { return indexes; }

        public void setValid(boolean v)                        { this.valid = v; }
        public void setFileName(String v)                      { this.fileName = v; }
        public void setFileSizeBytes(long v)                   { this.fileSizeBytes = v; }
        public void setTotalTables(int v)                      { this.totalTables = v; }
        public void setTotalColumns(int v)                     { this.totalColumns = v; }
        public void setTotalIndexes(int v)                     { this.totalIndexes = v; }
        public void setValidationErrors(List<String> v)        { this.validationErrors = v; }
        public void setTables(List<TableExtract> v)            { this.tables = v; }
        public void setIndexes(List<IndexExtract> v)           { this.indexes = v; }
    }

    public static class TableExtract {
        private String              tableName;
        private String              comment;
        private List<String>        primaryKeyColumns = new ArrayList<>();
        private List<String>        uniqueColumns     = new ArrayList<>();
        private List<ColumnExtract> columns           = new ArrayList<>();
        private List<ForeignKeyExtract> foreignKeys   = new ArrayList<>();

        public String              getTableName()         { return tableName; }
        public String              getComment()           { return comment; }
        public List<String>        getPrimaryKeyColumns() { return primaryKeyColumns; }
        public List<String>        getUniqueColumns()     { return uniqueColumns; }
        public List<ColumnExtract> getColumns()           { return columns; }
        public List<ForeignKeyExtract> getForeignKeys()   { return foreignKeys; }

        public void setTableName(String v)                        { this.tableName = v; }
        public void setComment(String v)                          { this.comment = v; }
        public void setPrimaryKeyColumns(List<String> v)          { this.primaryKeyColumns = v; }
        public void setUniqueColumns(List<String> v)              { this.uniqueColumns = v; }
        public void setColumns(List<ColumnExtract> v)             { this.columns = v; }
        public void setForeignKeys(List<ForeignKeyExtract> v)     { this.foreignKeys = v; }
    }

    public static class ColumnExtract {
        private String  columnName;
        private String  dataType;
        private boolean nullable        = true;
        private boolean primaryKey;
        private boolean foreignKey;
        private boolean unique;
        private boolean autoIncrement;
        private String  defaultValue;
        private String  foreignKeyReference;
        private String  fkConstraintName;
        private String  comment;

        public String  getColumnName()          { return columnName; }
        public String  getDataType()            { return dataType; }
        public boolean isNullable()             { return nullable; }
        public boolean isPrimaryKey()           { return primaryKey; }
        public boolean isForeignKey()           { return foreignKey; }
        public boolean isUnique()               { return unique; }
        public boolean isAutoIncrement()        { return autoIncrement; }
        public String  getDefaultValue()        { return defaultValue; }
        public String  getForeignKeyReference() { return foreignKeyReference; }
        public String  getFkConstraintName()    { return fkConstraintName; }
        public String  getComment()             { return comment; }

        public void setColumnName(String v)          { this.columnName = v; }
        public void setDataType(String v)            { this.dataType = v; }
        public void setNullable(boolean v)           { this.nullable = v; }
        public void setPrimaryKey(boolean v)         { this.primaryKey = v; }
        public void setForeignKey(boolean v)         { this.foreignKey = v; }
        public void setUnique(boolean v)             { this.unique = v; }
        public void setAutoIncrement(boolean v)      { this.autoIncrement = v; }
        public void setDefaultValue(String v)        { this.defaultValue = v; }
        public void setForeignKeyReference(String v) { this.foreignKeyReference = v; }
        public void setFkConstraintName(String v)    { this.fkConstraintName = v; }
        public void setComment(String v)             { this.comment = v; }
    }

    public static class ForeignKeyExtract {
        private String constraintName;
        private String column;
        private String referencedTable;
        private String referencedColumn;

        public String getConstraintName()   { return constraintName; }
        public String getColumn()           { return column; }
        public String getReferencedTable()  { return referencedTable; }
        public String getReferencedColumn() { return referencedColumn; }

        public void setConstraintName(String v)   { this.constraintName = v; }
        public void setColumn(String v)           { this.column = v; }
        public void setReferencedTable(String v)  { this.referencedTable = v; }
        public void setReferencedColumn(String v) { this.referencedColumn = v; }
    }

    public static class IndexExtract {
        private String       indexName;
        private String       tableName;
        private boolean      unique;
        private List<String> columns = new ArrayList<>();

        public String       getIndexName() { return indexName; }
        public String       getTableName() { return tableName; }
        public boolean      isUnique()     { return unique; }
        public List<String> getColumns()   { return columns; }

        public void setIndexName(String v)       { this.indexName = v; }
        public void setTableName(String v)       { this.tableName = v; }
        public void setUnique(boolean v)         { this.unique = v; }
        public void setColumns(List<String> v)   { this.columns = v; }
    }

}
