package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.DatabaseSchema;
import com.nlp.rag.seek.model.DatabaseTable;
import com.nlp.rag.seek.model.SchemaColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Enriches a DatabaseSchema whose table / column names are abbreviated
 * so that the embedding model and keyword fallback can understand them.
 *
 * ══════════════════════════════════════════════════════════════════════
 *  WHY THIS IS NEEDED
 * ══════════════════════════════════════════════════════════════════════
 *  OpenAI text-embedding-3-small was trained on English text.
 *  When it sees "emp_dtls" it produces a near-zero-signal vector.
 *  When it sees "employee details — stores all employee personal and
 *  job information" it produces a rich vector near "employees",
 *  "staff", "workforce", "HR records".
 *
 *  This service bridges that gap WITHOUT touching the database or
 *  requiring DB COMMENTS to be set manually.
 *
 * ══════════════════════════════════════════════════════════════════════
 *  WHAT IT DOES
 * ══════════════════════════════════════════════════════════════════════
 *
 *  For each TABLE:
 *    1. Expands name:          "emp_dtls" → "employee details"
 *    2. Sets description:      "Employee details — stores employee personal
 *                               and job information"     (if blank/generic)
 *    3. Sets businessContext:  "employee details / emp_dtls"
 *    4. Sets semanticAliases:  ["staff details", "worker details",
 *                               "personnel details", ...]
 *
 *  For each COLUMN:
 *    1. Expands name:    "f_nm"   → "first name"
 *                        "sal"    → "salary"
 *                        "dob"    → "date of birth"
 *    2. Sets description: "first name — the employee's first name"
 *                          (if blank/generic)
 *    3. Sets semanticAliases: ["given name", "forename"]
 *
 * ══════════════════════════════════════════════════════════════════════
 *  WHEN IS ENRICHMENT APPLIED?
 * ══════════════════════════════════════════════════════════════════════
 *  Controlled by: seek.schema.enrichment.enabled=true  (default)
 *  Strategy:      seek.schema.enrichment.strategy=AUTO (default)
 *
 *  Strategy options:
 *   AUTO   — only enrich tables/columns that look abbreviated
 *   ALWAYS — enrich every table/column regardless
 *   NEVER  — skip enrichment entirely (raw names used as-is)
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ENRICHMENT DOES NOT MODIFY THE DATABASE
 * ══════════════════════════════════════════════════════════════════════
 *  The original table name / column name in the DatabaseTable /
 *  SchemaColumn objects is NEVER changed. The enrichment only adds
 *  humanReadableName, expandedDescription, businessContext and
 *  semanticAliases fields — used only during toEmbeddingText().
 */
@Service
public class SchemaEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(SchemaEnrichmentService.class);

    @Autowired
    private AbbreviationExpanderService expander;

    @Value("${seek.schema.enrichment.enabled:true}")
    private boolean enabled;

    @Value("${seek.schema.enrichment.strategy:AUTO}")
    private String strategy;   // AUTO | ALWAYS | NEVER

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Enrich all tables and their columns in the schema.
     * Returns the same schema object with enriched metadata — table/column
     * names in the DB are never modified.
     */
    public DatabaseSchema enrich(DatabaseSchema schema) {
        if (schema == null) return null;
        if ("NEVER".equalsIgnoreCase(strategy) || !enabled) {
            log.info("Schema enrichment DISABLED (strategy={}, enabled={})", strategy, enabled);
            return schema;
        }

        int tableEnriched = 0, colEnriched = 0;

        for (DatabaseTable table : schema.getTables()) {
            boolean doEnrich = "ALWAYS".equalsIgnoreCase(strategy)
                    || expander.isAbbreviated(table.getTableName());

            if (doEnrich) {
                enrichTable(table, schema.getDatabaseName());
                tableEnriched++;
            }

            // Always enrich columns — column names are almost always abbreviated
            if (table.getColumns() != null) {
                for (SchemaColumn col : table.getColumns()) {
                    boolean enrichCol = "ALWAYS".equalsIgnoreCase(strategy)
                            || expander.isAbbreviated(col.getName());
                    if (enrichCol) {
                        enrichColumn(col, table);
                        colEnriched++;
                    }
                }
            }
        }

        log.info("Schema enrichment complete — {} tables enriched, {} columns enriched (strategy={})",
                tableEnriched, colEnriched, strategy);
        return schema;
    }

    // =========================================================================
    // Table enrichment
    // =========================================================================

    private void enrichTable(DatabaseTable table, String dbName) {
        String original = table.getTableName();
        String expanded = expander.expand(original);
        List<String> aliases = expander.aliases(expanded);

        log.debug("Table enrichment: '{}' → expanded='{}' aliases={}",
                original, expanded, aliases);

        // Set humanReadableName (new field carrying the expanded form)
        table.setHumanReadableName(expanded);

        // Enrich description only when it is the generic auto-generated fallback
        String currentDesc = table.getDescription();
        boolean descIsGeneric = currentDesc == null
                || currentDesc.isBlank()
                || currentDesc.equalsIgnoreCase(original + " table")
                || currentDesc.equalsIgnoreCase(original);

        if (descIsGeneric) {
            // Build a rich description from the expanded name
            String richDesc = buildTableDescription(original, expanded, aliases);
            table.setDescription(richDesc);
            log.debug("  description enriched: '{}' → '{}'", currentDesc, richDesc);
        }

        // Always enrich businessContext with expanded name + original name
        String ctx = expanded + " (" + original + ")";
        if (aliases != null && !aliases.isEmpty()) {
            ctx += " — also known as: " + String.join(", ", aliases.subList(0, Math.min(3, aliases.size())));
        }
        table.setBusinessContext(ctx);

        // Store all aliases for toEmbeddingText()
        table.setSemanticAliases(aliases);
    }

    // =========================================================================
    // Column enrichment
    // =========================================================================

    private void enrichColumn(SchemaColumn col, DatabaseTable table) {
        String original = col.getName();
        String expanded = expander.expand(original);
        List<String> aliases = expander.aliases(expanded);

        log.debug("  Column enrichment: '{}.{}' → '{}'", table.getTableName(), original, expanded);

        // Set humanReadableName
        col.setHumanReadableName(expanded);

        // Enrich description only when it is the generic auto-generated fallback
        String currentDesc = col.getDescription();
        boolean descIsGeneric = currentDesc == null
                || currentDesc.isBlank()
                || currentDesc.equalsIgnoreCase(original + " (" + col.getDataType() + ")")
                || currentDesc.equalsIgnoreCase(original);

        if (descIsGeneric) {
            String tableExpanded = expander.expand(table.getTableName());
            String richDesc = buildColumnDescription(original, expanded, tableExpanded, aliases);
            col.setDescription(richDesc);
        }

        // Store aliases for toEmbeddingText()
        col.setSemanticAliases(aliases);
    }

    // =========================================================================
    // Description builders
    // =========================================================================

    private String buildTableDescription(String original, String expanded, List<String> aliases) {
        StringBuilder sb = new StringBuilder();
        sb.append(capitalize(expanded));
        sb.append(" — stores ").append(expanded).append(" records");
        if (!aliases.isEmpty()) {
            sb.append(" (also referred to as: ");
            sb.append(aliases.stream().limit(3).collect(Collectors.joining(", ")));
            sb.append(")");
        }
        sb.append(". Original table name: ").append(original).append(".");
        return sb.toString();
    }

    private String buildColumnDescription(String original, String expanded,
                                           String tableExpanded, List<String> aliases) {
        StringBuilder sb = new StringBuilder();
        sb.append(capitalize(expanded));
        sb.append(" — the ").append(expanded).append(" of the ").append(tableExpanded);
        if (!aliases.isEmpty()) {
            sb.append(". Also known as: ");
            sb.append(aliases.stream().limit(2).collect(Collectors.joining(", ")));
        }
        sb.append(". Original column name: ").append(original).append(".");
        return sb.toString();
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

