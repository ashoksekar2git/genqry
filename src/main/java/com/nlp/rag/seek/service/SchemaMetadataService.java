package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.DatabaseSchema;
import com.nlp.rag.seek.model.DatabaseTable;
import com.nlp.rag.seek.model.SchemaColumn;
import com.nlp.rag.seek.model.TableMetadata;
import com.nlp.rag.seek.model.TableMetadata.ColumnMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides table + column metadata to the UI schema browser.
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ENDPOINTS SERVED
 * ══════════════════════════════════════════════════════════════════════
 *
 *  GET /api/v1/admin/metadata/databases/{databaseName}/tables
 *    → Returns ALL table names (String[]) for the database — no paging.
 *
 *  GET /api/v1/admin/metadata/databases/{databaseName}/tables/detail
 *    → Returns paginated full TableMetadata list (with columns).
 *      Query params: page (0-based), pageSize (default 10, max 10)
 *      10-table cap per page applies HERE only — loading full column
 *      details for hundreds of tables at once is expensive.
 *
 *  GET /api/v1/admin/metadata/databases/{databaseName}/tables/{tableName}
 *    → Returns full TableMetadata for one table (all columns)
 *
 *  GET /api/v1/admin/metadata/databases/{databaseName}/tables/{tableName}/columns
 *    → Returns String[] of column names for a single table
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ENRICHMENT
 * ══════════════════════════════════════════════════════════════════════
 *  Uses the already-enriched DatabaseSchema (from SchemaExtractionService)
 *  so humanReadableName + description are already populated for
 *  abbreviated tables/columns by SchemaEnrichmentService.
 *  No extra DB calls are made — the schema is read from the in-memory
 *  enriched schema produced at startup.
 */
@Service
public class SchemaMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SchemaMetadataService.class);

    /**
     * Page size cap applied ONLY to the /tables/detail endpoint.
     * The /tables (names only) endpoint always returns ALL table names.
     */
    public static final int MAX_DETAIL_PAGE_SIZE = 10;

    @Autowired
    private SchemaExtractionService schemaExtractionService;


    // =========================================================================
    // Table names — ALL tables, no paging
    // =========================================================================

    /**
     * Returns ALL table names for the given database as a flat String[].
     * No pagination — the full list is returned in a single call.
     *
     * Resolution order:
     *  1. Try to load schema JSON for the requested database
     *  2. Fall back to live JDBC extraction
     *
     * Response shape:
     * {
     *   "databaseName": "ecommerce",
     *   "totalTables":  42,
     *   "tableNames":   ["employees", "departments", "orders", ...]
     * }
     */
    public Map<String, Object> getTableNames(String databaseName) {
        // ── Try to load the requested database's schema from JSON first ──────
        DatabaseSchema schema = schemaExtractionService.buildActiveSchemaFromJson(databaseName);

        if (schema == null || schema.getTables().isEmpty()) {
            log.warn("Could not load schema from JSON for '{}' — falling back to live extraction",
                    databaseName);
            // Fall back to live extraction (uses active datasource)
            schema = schemaExtractionService.extractSchema();
        }

        String resolvedDb = schema.getDatabaseName();


        List<String> names = schema.getTables().stream()
                .map(DatabaseTable::getTableName)
                .collect(Collectors.toList());

        log.info("Returning all {} table names for db='{}'", names.size(), resolvedDb);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("databaseName", resolvedDb);
        result.put("totalTables",  names.size());
        result.put("tableNames",   names);
        return result;
    }

    // =========================================================================
    // Table detail — paginated (max MAX_DETAIL_PAGE_SIZE per page)
    // =========================================================================

    /**
     * Returns a paginated envelope of full TableMetadata objects (with columns).
     * The 10-table cap applies here because returning columns for hundreds of
     * tables in one HTTP response would be very large.
     *
     * Resolution order:
     *  1. Try to load schema JSON for the requested database
     *  2. Fall back to live JDBC extraction
     *
     * @param databaseName  database name from the URL path variable
     * @param page          0-based page index
     * @param pageSize      entries per page — capped at MAX_DETAIL_PAGE_SIZE=10
     */
    public Map<String, Object> getTablesPage(String databaseName, int page, int pageSize) {

        int effectivePageSize = Math.min(Math.max(1, pageSize), MAX_DETAIL_PAGE_SIZE);
        int effectivePage     = Math.max(0, page);

        // ── Try to load the requested database's schema from JSON first ──────
        DatabaseSchema schema = schemaExtractionService.buildActiveSchemaFromJson(databaseName);

        if (schema == null || schema.getTables().isEmpty()) {
            log.warn("Could not load schema from JSON for '{}' — falling back to live extraction",
                    databaseName);
            // Fall back to live extraction (uses active datasource)
            schema = schemaExtractionService.extractSchema();
        }

        List<DatabaseTable> all = schema.getTables();
        String resolvedDb       = schema.getDatabaseName();


        int total      = all.size();
        int totalPages = (int) Math.ceil((double) total / effectivePageSize);
        int from       = effectivePage * effectivePageSize;
        int to         = Math.min(from + effectivePageSize, total);

        List<TableMetadata> pageData = from < total
                ? all.subList(from, to).stream()
                     .map(this::toTableMetadata)
                     .collect(Collectors.toList())
                : Collections.emptyList();

        log.info("Detail page: db='{}' page={}/{} pageSize={} tables=[{}-{}] of {}",
                resolvedDb, effectivePage, totalPages - 1, effectivePageSize, from, to, total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("databaseName", resolvedDb);
        result.put("totalTables",  total);
        result.put("page",         effectivePage);
        result.put("pageSize",     effectivePageSize);
        result.put("totalPages",   totalPages);
        result.put("hasNext",      effectivePage < totalPages - 1);
        result.put("hasPrevious",  effectivePage > 0);
        result.put("tables",       pageData);
        return result;
    }

    // =========================================================================
    // Single table detail
    // =========================================================================

    /**
     * Returns full TableMetadata (all columns) for a single table.
     *
     * Resolution order:
     *  1. Try to load schema JSON for the requested database
     *  2. Fall back to live JDBC extraction
     *
     * Returns empty Optional when the table is not found.
     */
    public Optional<TableMetadata> getTableDetail(String databaseName, String tableName) {
        // ── Try to load the requested database's schema from JSON first ──────
        DatabaseSchema schema = schemaExtractionService.buildActiveSchemaFromJson(databaseName);

        if (schema == null || schema.getTables().isEmpty()) {
            log.warn("Could not load schema from JSON for '{}' — falling back to live extraction",
                    databaseName);
            // Fall back to live extraction (uses active datasource)
            schema = schemaExtractionService.extractSchema();
        }

        return schema.getTables().stream()
                .filter(t -> t.getTableName().equalsIgnoreCase(tableName))
                .findFirst()
                .map(this::toTableMetadata);
    }

    // =========================================================================
    // Column listing for a single table
    // =========================================================================

    /**
     * Returns column names only (String[]) for a single table.
     *
     * Resolution order:
     *  1. Try to load schema JSON for the requested database
     *  2. Fall back to live JDBC extraction
     */
    public Optional<List<String>> getColumnNames(String databaseName, String tableName) {
        // ── Try to load the requested database's schema from JSON first ──────
        DatabaseSchema schema = schemaExtractionService.buildActiveSchemaFromJson(databaseName);

        if (schema == null || schema.getTables().isEmpty()) {
            log.warn("Could not load schema from JSON for '{}' — falling back to live extraction",
                    databaseName);
            // Fall back to live extraction (uses active datasource)
            schema = schemaExtractionService.extractSchema();
        }

        return schema.getTables().stream()
                .filter(t -> t.getTableName().equalsIgnoreCase(tableName))
                .findFirst()
                .map(t -> t.getColumns() == null
                        ? Collections.emptyList()
                        : t.getColumns().stream()
                                .map(SchemaColumn::getName)
                                .collect(Collectors.toList()));
    }


    // =========================================================================
    // Conversion helpers
    // =========================================================================

    /**
     * Converts an enriched DatabaseTable → lightweight TableMetadata for the API.
     */
    private TableMetadata toTableMetadata(DatabaseTable t) {
        List<ColumnMetadata> cols = t.getColumns() == null
                ? Collections.emptyList()
                : t.getColumns().stream()
                        .map(this::toColumnMetadata)
                        .collect(Collectors.toList());

        return TableMetadata.builder()
                .tableName(t.getTableName())
                .humanReadableName(t.getHumanReadableName())  // null if not abbreviated
                .description(t.getDescription())
                .businessContext(t.getBusinessContext())
                .columnCount(cols.size())
                .columns(cols)
                .build();
    }

    /**
     * Converts an enriched SchemaColumn → lightweight ColumnMetadata for the API.
     */
    private ColumnMetadata toColumnMetadata(SchemaColumn c) {
        return ColumnMetadata.builder()
                .columnName(c.getName())
                .humanReadableName(c.getHumanReadableName())  // null if not abbreviated
                .dataType(c.getDataType())
                .description(c.getDescription())
                .primaryKey(c.isPrimaryKey())
                .foreignKey(c.isForeignKey())
                .foreignKeyReference(c.getForeignKeyReference())
                .nullable(c.isNullable())
                .build();
    }
}

