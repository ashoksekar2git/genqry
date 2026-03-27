package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.SQLExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Executes a validated SQL statement against the live (routing) DataSource
 * and returns a structured {@link SQLExecutionResult}.
 *
 * Safety rules enforced before execution:
 *  1. Only SELECT or WITH...SELECT statements are permitted.
 *     Any INSERT / UPDATE / DELETE / DROP / TRUNCATE / ALTER is rejected.
 *  2. Row count is capped at {@code sql.execution.row-limit} (default 50).
 *  3. Query timeout is set to {@code sql.execution.timeout-seconds} (default 10).
 *  4. All values are read as Objects — no type-casting that could throw.
 *
 * The service injects the routing JdbcTemplate (PostgreSQL).
 */
@Service
public class SQLExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SQLExecutionService.class);

    // Only allow read-only statements
    private static final Pattern ALLOWED_PATTERN = Pattern.compile(
            "^\\s*(SELECT|WITH)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Blocks dangerous keywords even inside WITH ... SELECT
    private static final Pattern FORBIDDEN_PATTERN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|CREATE|GRANT|REVOKE|EXEC|EXECUTE|CALL)\\b",
            Pattern.CASE_INSENSITIVE);

    @Qualifier("secondaryJdbcTemplate")
    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate routingJdbcTemplate;

    @Qualifier("secondaryDataSourceName")
    @org.springframework.beans.factory.annotation.Autowired
    private String activeDataSourceName;

    @org.springframework.beans.factory.annotation.Autowired
    private DynamicDataSourceRegistry dynamicDataSourceRegistry;

    @Value("${sql.execution.row-limit:50}")
    private int defaultRowLimit;

    @Value("${sql.execution.timeout-seconds:10}")
    private int queryTimeoutSeconds;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Execute {@code sql} and return up to {@code rowLimit} rows.
     *
     * @param sql      The SQL to execute (must be SELECT / WITH...SELECT)
     * @param rowLimit Max rows to return; 0 or negative → use configured default
     * @return SQLExecutionResult with columns + rows, or an error result
     */
    public SQLExecutionResult execute(String sql, int rowLimit) {
        int limit = (rowLimit > 0) ? rowLimit : defaultRowLimit;
        long start = System.currentTimeMillis();

        if (sql == null || sql.isBlank()) {
            return SQLExecutionResult.failure("SQL is empty", 0);
        }

        // ── Safety: only allow SELECT / WITH ─────────────────────────────────
        if (!ALLOWED_PATTERN.matcher(sql).matches()) {
            log.warn("SQL execution rejected — not a SELECT/WITH statement: {}",
                     sql.substring(0, Math.min(sql.length(), 120)));
            return SQLExecutionResult.failure(
                    "Only SELECT statements are permitted for execution.", 0);
        }

        if (FORBIDDEN_PATTERN.matcher(sql).find()) {
            log.warn("SQL execution rejected — forbidden keyword detected");
            return SQLExecutionResult.failure(
                    "SQL contains forbidden keyword (INSERT/UPDATE/DELETE/DROP etc.).", 0);
        }

        log.info("▶ SQL execution | limit={} | timeout={}s | db={}",
                 limit, queryTimeoutSeconds, activeDataSourceName);
        log.info("▶ Executing SQL against database:\n─────────────────────────────\n{}\n─────────────────────────────", sql);

        try {
            SQLExecutionResult result = routingJdbcTemplate.execute(
                    (Connection conn) -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                sql,
                                ResultSet.TYPE_FORWARD_ONLY,
                                ResultSet.CONCUR_READ_ONLY)) {

                            ps.setQueryTimeout(queryTimeoutSeconds);
                            ps.setMaxRows(limit + 1);

                            try (ResultSet rs = ps.executeQuery()) {
                                ResultSetMetaData meta = rs.getMetaData();
                                int colCount = meta.getColumnCount();

                                List<String> columns = new ArrayList<>(colCount);
                                for (int i = 1; i <= colCount; i++) {
                                    columns.add(meta.getColumnLabel(i));
                                }

                                List<Map<String, Object>> rows = new ArrayList<>();
                                while (rs.next() && rows.size() <= limit) {
                                    Map<String, Object> row = new LinkedHashMap<>(colCount);
                                    for (int i = 1; i <= colCount; i++) {
                                        Object value = rs.getObject(i);
                                        if (value instanceof java.sql.Timestamp ts) {
                                            value = ts.toInstant().toString();
                                        } else if (value instanceof java.sql.Date d) {
                                            value = d.toLocalDate().toString();
                                        } else if (value instanceof java.sql.Time t) {
                                            value = t.toString();
                                        } else if (value instanceof java.sql.Array arr) {
                                            value = Arrays.toString((Object[]) arr.getArray());
                                        }
                                        row.put(columns.get(i - 1), value);
                                    }
                                    rows.add(row);
                                }

                                boolean truncated = rows.size() > limit;
                                if (truncated) rows = rows.subList(0, limit);

                                long elapsed = System.currentTimeMillis() - start;
                                log.info("◀ SQL execution done | rows={} truncated={} | {}ms",
                                         rows.size(), truncated, elapsed);

                                return SQLExecutionResult.success(
                                        columns, rows, limit, truncated,
                                        elapsed, activeDataSourceName, sql);
                            }
                        }
                    });
            return result;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String msg = extractUserFriendlyError(e);
            log.error("SQL execution failed after {}ms: {}", elapsed, msg);
            log.error("Failed SQL was:\n{}", sql);
            return SQLExecutionResult.failure(msg, elapsed, sql);
        }
    }

    /**
     * Convenience overload using the configured default row limit.
     */
    public SQLExecutionResult execute(String sql) {
        return execute(sql, defaultRowLimit);
    }

    /**
     * Execute SQL against a specific database by name.
     * Uses the DynamicDataSourceRegistry if the database was connected via /connect,
     * otherwise falls back to the default secondary (ecommerce) datasource.
     */
    public SQLExecutionResult execute(String sql, int rowLimit, String databaseName) {
        // If a dynamic datasource is registered for this database, use it
        if (databaseName != null && dynamicDataSourceRegistry.isRegistered(databaseName)) {
            return executeWithDynamicConnection(sql, rowLimit, databaseName);
        }
        // Default: use the static secondary datasource (ecommerce)
        return execute(sql, rowLimit);
    }

    /**
     * Executes SQL using a dynamic JDBC connection from the registry.
     */
    private SQLExecutionResult executeWithDynamicConnection(String sql, int rowLimit, String databaseName) {
        int limit = (rowLimit > 0) ? rowLimit : defaultRowLimit;
        long start = System.currentTimeMillis();

        if (sql == null || sql.isBlank()) {
            return SQLExecutionResult.failure("SQL is empty", 0);
        }
        if (!ALLOWED_PATTERN.matcher(sql).matches()) {
            return SQLExecutionResult.failure("Only SELECT statements are permitted for execution.", 0);
        }
        if (FORBIDDEN_PATTERN.matcher(sql).find()) {
            return SQLExecutionResult.failure("SQL contains forbidden keyword (INSERT/UPDATE/DELETE/DROP etc.).", 0);
        }

        String dataSourceLabel = "DYNAMIC (" + databaseName + ")";
        log.info("▶ SQL execution (dynamic) | limit={} | timeout={}s | db={}",
                limit, queryTimeoutSeconds, dataSourceLabel);
        log.info("▶ Executing SQL against dynamic database:\n─────────────────────────────\n{}\n─────────────────────────────", sql);

        try (Connection conn = dynamicDataSourceRegistry.openConnection(databaseName)) {
            if (conn == null) {
                return SQLExecutionResult.failure(
                        "No dynamic connection available for database '" + databaseName + "'",
                        System.currentTimeMillis() - start);
            }

            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

                ps.setQueryTimeout(queryTimeoutSeconds);
                ps.setMaxRows(limit + 1);

                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();

                    List<String> columns = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(meta.getColumnLabel(i));
                    }

                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next() && rows.size() <= limit) {
                        Map<String, Object> row = new LinkedHashMap<>(colCount);
                        for (int i = 1; i <= colCount; i++) {
                            Object value = rs.getObject(i);
                            if (value instanceof java.sql.Timestamp ts) {
                                value = ts.toInstant().toString();
                            } else if (value instanceof java.sql.Date d) {
                                value = d.toLocalDate().toString();
                            } else if (value instanceof java.sql.Time t) {
                                value = t.toString();
                            } else if (value instanceof java.sql.Array arr) {
                                value = Arrays.toString((Object[]) arr.getArray());
                            }
                            row.put(columns.get(i - 1), value);
                        }
                        rows.add(row);
                    }

                    boolean truncated = rows.size() > limit;
                    if (truncated) rows = rows.subList(0, limit);

                    long elapsed = System.currentTimeMillis() - start;
                    log.info("◀ SQL execution done (dynamic) | rows={} truncated={} | {}ms",
                            rows.size(), truncated, elapsed);

                    return SQLExecutionResult.success(
                            columns, rows, limit, truncated,
                            elapsed, dataSourceLabel, sql);
                }
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String msg = extractUserFriendlyError(e);
            log.error("Dynamic SQL execution failed after {}ms: {}", elapsed, msg);
            return SQLExecutionResult.failure(msg, elapsed, sql);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractUserFriendlyError(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();

        String msg = cause.getMessage();
        if (msg == null) msg = e.getClass().getSimpleName();

        // Strip verbose JDBC driver noise
        if (msg.contains("ERROR:")) {
            int idx = msg.indexOf("ERROR:");
            msg = msg.substring(idx);
        }
        return msg;
    }
}

