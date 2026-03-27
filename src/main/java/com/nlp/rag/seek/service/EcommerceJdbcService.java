package com.nlp.rag.seek.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * EcommerceJdbcService — executes read-only queries against the secondary
 * (ecommerce) datasource for the Explore page.
 *
 * Uses @Qualifier("secondaryJdbcTemplate") so it NEVER touches the seek/primary DB.
 */
@Service
public class EcommerceJdbcService {

    private static final Logger log = LoggerFactory.getLogger(EcommerceJdbcService.class);
    private static final int QUERY_TIMEOUT_SECONDS = 15;

    private final JdbcTemplate jdbc;

    public EcommerceJdbcService(@Qualifier("secondaryJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =========================================================================
    // Table list
    // =========================================================================

    /**
     * Returns all user-visible table names in the ecommerce DB (public schema).
     */
    public List<String> listTables() {
        String sql = """
            SELECT table_name
              FROM information_schema.tables
             WHERE table_schema = 'public'
               AND table_type   = 'BASE TABLE'
             ORDER BY table_name
            """;
        try {
            return jdbc.queryForList(sql, String.class);
        } catch (Exception e) {
            log.error("listTables failed: {}", e.getMessage());
            throw e;
        }
    }

    // =========================================================================
    // Paginated table data
    // =========================================================================

    public static class PageResult {
        public final List<String>              columns;
        public final List<Map<String, Object>> rows;
        public final long                      totalRows;
        public final boolean                   hasNext;
        public final boolean                   hasPrev;
        public final int                       page;
        public final int                       size;

        public PageResult(List<String> columns, List<Map<String, Object>> rows,
                          long totalRows, boolean hasNext, boolean hasPrev,
                          int page, int size) {
            this.columns   = columns;
            this.rows      = rows;
            this.totalRows = totalRows;
            this.hasNext   = hasNext;
            this.hasPrev   = hasPrev;
            this.page      = page;
            this.size      = size;
        }
    }

    /**
     * Fetches paginated rows from the named table using the secondary datasource.
     *
     * @param tableName  sanitised table name (alphanumeric + underscore only)
     * @param page       zero-based page index
     * @param size       rows per page (capped at 100)
     */
    public PageResult fetchTableData(String tableName, int page, int size) {
        int safeSize   = Math.min(Math.max(size, 1), 100);
        int safeOffset = Math.max(page, 0) * safeSize;

        log.info("fetchTableData — table='{}' page={} size={}", tableName, page, safeSize);

        // total row count
        long totalRows = 0;
        try {
            Long cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM \"" + tableName + "\"", Long.class);
            if (cnt != null) totalRows = cnt;
        } catch (Exception e) {
            log.debug("COUNT(*) failed for '{}': {}", tableName, e.getMessage());
        }

        // data rows (fetch size+1 to detect hasNext)
        String dataSql = String.format(
                "SELECT * FROM \"%s\" LIMIT %d OFFSET %d",
                tableName, safeSize + 1, safeOffset);

        // Use a holder to collect columns + rows from inside the lambda
        final List<String>              colHolder  = new ArrayList<>();
        final List<Map<String, Object>> rowHolder  = new ArrayList<>();

        jdbc.execute((Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    dataSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    for (int i = 1; i <= colCount; i++) colHolder.add(meta.getColumnLabel(i));
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>(colCount);
                        for (int i = 1; i <= colCount; i++) {
                            Object v = rs.getObject(i);
                            // normalise JDBC types to JSON-safe values
                            if (v instanceof java.sql.Timestamp ts) v = ts.toInstant().toString();
                            else if (v instanceof java.sql.Date d)  v = d.toLocalDate().toString();
                            else if (v instanceof java.sql.Time t)  v = t.toString();
                            else if (v instanceof java.sql.Array a) {
                                try { v = Arrays.toString((Object[]) a.getArray()); }
                                catch (Exception ex) { v = a.toString(); }
                            }
                            row.put(colHolder.get(i - 1), v);
                        }
                        rowHolder.add(row);
                    }
                }
            }
            return null;
        });

        List<String>              columns = colHolder;
        List<Map<String, Object>> rows    = rowHolder;

        boolean hasNext = rows.size() > safeSize;
        if (hasNext) rows = rows.subList(0, safeSize);

        return new PageResult(columns, rows, totalRows, hasNext, page > 0, page, safeSize);
    }
}

