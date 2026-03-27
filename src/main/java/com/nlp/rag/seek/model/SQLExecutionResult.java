package com.nlp.rag.seek.model;

import java.util.List;
import java.util.Map;

/**
 * Holds the result of executing a generated SQL statement against the live DB.
 *
 * Columns — ordered list of column names from the ResultSet metadata.
 * Rows    — each row is a LinkedHashMap preserving column order:
 *              { "dept_id": 1, "dept_name": "Engineering", ... }
 *
 * Execution is always capped at {@code rowLimit} rows (default 50) to prevent
 * runaway queries from flooding the UI. {@code truncated=true} signals that
 * more rows exist beyond the limit.
 *
 * Only SELECT / WITH ... SELECT statements are allowed.
 * Any other DML/DDL is rejected and {@code error} is populated.
 */
public class SQLExecutionResult {

    /** Ordered column names as returned by ResultSet metadata. */
    private List<String> columns;

    /** Data rows — each row maps column name → value (may be null). */
    private List<Map<String, Object>> rows;

    /** Number of rows actually returned (≤ rowLimit). */
    private int rowCount;

    /** Row limit that was applied. */
    private int rowLimit;

    /**
     * True when the underlying query produced more rows than {@code rowLimit}.
     * The UI should display a "showing first N of many" notice.
     */
    private boolean truncated;

    /** Wall-clock execution time in milliseconds. */
    private long executionTimeMs;

    /** Name of the datasource that ran the query: PRIMARY or SECONDARY. */
    private String dataSource;

    /** Non-null when the execution failed (SQL error, connection error, etc.) */
    private String error;

    /** Whether execution succeeded (error == null). */
    private boolean success;

    /**
     * The exact SQL string that was sent to the database driver.
     * Always populated (on both success and failure) so the UI can display
     * "Executed SQL:" next to the results or error message.
     */
    private String executedSql;

    public SQLExecutionResult() {}

    // ── static factories ─────────────────────────────────────────────────────

    public static SQLExecutionResult success(List<String> columns,
                                              List<Map<String, Object>> rows,
                                              int rowLimit,
                                              boolean truncated,
                                              long executionTimeMs,
                                              String dataSource,
                                              String executedSql) {
        SQLExecutionResult r = new SQLExecutionResult();
        r.columns         = columns;
        r.rows            = rows;
        r.rowCount        = rows.size();
        r.rowLimit        = rowLimit;
        r.truncated       = truncated;
        r.executionTimeMs = executionTimeMs;
        r.dataSource      = dataSource;
        r.success         = true;
        r.executedSql     = executedSql;
        return r;
    }

    /** Backwards-compatible overload without executedSql. */
    public static SQLExecutionResult success(List<String> columns,
                                              List<Map<String, Object>> rows,
                                              int rowLimit,
                                              boolean truncated,
                                              long executionTimeMs,
                                              String dataSource) {
        return success(columns, rows, rowLimit, truncated, executionTimeMs, dataSource, null);
    }

    public static SQLExecutionResult failure(String error, long executionTimeMs) {
        return failure(error, executionTimeMs, null);
    }

    public static SQLExecutionResult failure(String error, long executionTimeMs, String executedSql) {
        SQLExecutionResult r = new SQLExecutionResult();
        r.error           = error;
        r.executionTimeMs = executionTimeMs;
        r.success         = false;
        r.rowCount        = 0;
        r.executedSql     = executedSql;
        return r;
    }

    // ── getters / setters ─────────────────────────────────────────────────────
    public List<String>              getColumns()          { return columns; }
    public List<Map<String, Object>> getRows()             { return rows; }
    public int                       getRowCount()         { return rowCount; }
    public int                       getRowLimit()         { return rowLimit; }
    public boolean                   isTruncated()         { return truncated; }
    public long                      getExecutionTimeMs()  { return executionTimeMs; }
    public String                    getDataSource()       { return dataSource; }
    public String                    getError()            { return error; }
    public boolean                   isSuccess()           { return success; }
    public String                    getExecutedSql()      { return executedSql; }

    public void setColumns(List<String> v)                         { this.columns = v; }
    public void setRows(List<Map<String, Object>> v)               { this.rows = v; }
    public void setRowCount(int v)                                  { this.rowCount = v; }
    public void setRowLimit(int v)                                  { this.rowLimit = v; }
    public void setTruncated(boolean v)                             { this.truncated = v; }
    public void setExecutionTimeMs(long v)                          { this.executionTimeMs = v; }
    public void setDataSource(String v)                             { this.dataSource = v; }
    public void setError(String v)                                  { this.error = v; }
    public void setSuccess(boolean v)                               { this.success = v; }
    public void setExecutedSql(String v)                            { this.executedSql = v; }
}

