package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.DatabaseTable;
import com.nlp.rag.seek.model.DatabaseSchema;
import com.nlp.rag.seek.model.EavConfig;
import com.nlp.rag.seek.model.EavKnownAttribute;
import com.nlp.rag.seek.model.SchemaColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Detects EAV (Entity–Attribute–Value) / Vertical tables and attaches
 * {@link EavConfig} metadata to them.
 *
 * ══════════════════════════════════════════════════════════════════════
 *  DETECTION STRATEGY
 * ══════════════════════════════════════════════════════════════════════
 *
 *  Step 1 — JSON flag:  "is_eav_table": true  (explicit, highest priority)
 *  Step 2 — Structural heuristics on column names:
 *
 *    Signal 1: Has an entity-id column (matches ENTITY_ID_PATTERN)
 *    Signal 2: Has an attribute-name column (matches ATTR_NAME_PATTERN)
 *    Signal 3: Has an attribute-value column (matches ATTR_VALUE_PATTERN)
 *    Signal 4: Low column count (≤ MAX_EAV_COLUMN_COUNT)
 *    Signal 5: Table / column name patterns (e.g. *_attributes, *_properties)
 *
 *    3 of 5 signals → classified as EAV.
 *
 *  Step 3 — EavConfig population:
 *    Identifies which column plays which EAV role (entity_id, attr_name, attr_value).
 *    Loads known_attributes from the schema JSON when present.
 *    Auto-generates synonyms for common attribute keys.
 */
@Service
public class EavDetectionService {

    private static final Logger log = LoggerFactory.getLogger(EavDetectionService.class);

    /** Max columns for a table to be structurally eligible as EAV */
    private static final int MAX_EAV_COLUMN_COUNT = 8;

    // ── Column-name patterns for EAV role detection ───────────────────────────

    private static final Pattern ENTITY_ID_PATTERN = Pattern.compile(
            "(?i)^.*(entity|object|record|subject|owner|parent).*id.*$" +
            "|^.*id_(entity|object|record).*$" +
            "|^.*(attributeid|attribute_id|attributesid).*$");

    private static final Pattern ATTR_NAME_PATTERN = Pattern.compile(
            "(?i)^.*(attribute|attr|key|property|prop|param|field|name|code|attributes).*$");

    private static final Pattern ATTR_VALUE_PATTERN = Pattern.compile(
            "(?i)^.*(value|valueid|val|unit|datatype|value_int|valueint|valuedecimal|value_decimal|" +
            "valuebool|value_boolean|valuedate|value_date|values|data|content|amount|result|data_type|" +
            "attribute_code|entity_type_id|eav_attribute|frontend_input|backend_type|value_id|product_id).*$");

    // ── Table-name patterns that strongly suggest EAV ─────────────────────────

    private static final Pattern EAV_TABLE_NAME_PATTERN = Pattern.compile(
            "(?i)^.*(attribute|attributes|Attributes|Attribute|Attribute_Values|AttributeValues|AttributeVal|attribute_val|properties|property|metadata|params|" +
            "param|config|settings|custom_fields|key_value|kv_store|eav|vertical).*$");

    // ── Well-known synonym mappings for common EAV attribute keys ─────────────

    private static final Map<String, List<String>> BUILT_IN_SYNONYMS = new LinkedHashMap<>();
    static {
        BUILT_IN_SYNONYMS.put("salary",       Arrays.asList("compensation", "pay", "wage", "earnings", "income", "remuneration"));
        BUILT_IN_SYNONYMS.put("hire_date",     Arrays.asList("start date", "joining date", "employment date", "onboarding date"));
        BUILT_IN_SYNONYMS.put("phone",         Arrays.asList("mobile", "contact number", "telephone", "cell"));
        BUILT_IN_SYNONYMS.put("phone_number",  Arrays.asList("mobile", "contact number", "telephone", "cell"));
        BUILT_IN_SYNONYMS.put("email",         Arrays.asList("email address", "mail", "e-mail"));
        BUILT_IN_SYNONYMS.put("address",       Arrays.asList("location", "residence", "mailing address", "postal address"));
        BUILT_IN_SYNONYMS.put("department",    Arrays.asList("dept", "division", "unit", "team"));
        BUILT_IN_SYNONYMS.put("designation",   Arrays.asList("title", "job title", "position", "role", "post"));
        BUILT_IN_SYNONYMS.put("age",           Arrays.asList("years old", "how old"));
        BUILT_IN_SYNONYMS.put("dob",           Arrays.asList("date of birth", "birthday", "birth date"));
        BUILT_IN_SYNONYMS.put("date_of_birth", Arrays.asList("birthday", "birth date", "dob"));
        BUILT_IN_SYNONYMS.put("gender",        Arrays.asList("sex", "male", "female"));
        BUILT_IN_SYNONYMS.put("price",         Arrays.asList("cost", "rate", "amount", "charge"));
        BUILT_IN_SYNONYMS.put("quantity",      Arrays.asList("count", "amount", "number", "qty"));
        BUILT_IN_SYNONYMS.put("status",        Arrays.asList("state", "condition", "active", "inactive"));
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Scans every table in the schema and marks EAV tables by attaching
     * the {@link EavConfig} metadata.
     * Operates in-place on the schema object.
     *
     * Call this after SchemaEnrichmentService.enrich() and before ChunkingService.
     */
    public DatabaseSchema detect(DatabaseSchema schema) {
        if (schema == null || schema.getTables() == null) return schema;
        int eavCount = 0;
        for (DatabaseTable table : schema.getTables()) {
            if (detectAndConfigure(table)) {
                eavCount++;
            }
        }
        if (eavCount > 0) {
            log.info("EAV detection complete — {} EAV/vertical table(s) identified in schema '{}'",
                    eavCount, schema.getDatabaseName());
        } else {
            log.debug("EAV detection complete — no EAV tables found in schema '{}'",
                    schema.getDatabaseName());
        }
        return schema;
    }

    /**
     * Detects whether a single table is EAV and attaches EavConfig if so.
     * Returns true when the table was classified as EAV.
     */
    public boolean detectAndConfigure(DatabaseTable table) {
        // Already flagged via JSON ("is_eav_table": true) — just build config
        if (table.isEavTable()) {
            if (table.getEavConfig() == null) {
                table.setEavConfig(inferEavConfig(table));
            }
            enrichKnownAttributeSynonyms(table.getEavConfig());
            log.info("Table '{}' — EAV flag set explicitly; config resolved", table.getTableName());
            return true;
        }

        // Structural heuristic detection
        int signals = 0;
        List<SchemaColumn> cols = table.getColumns();
        if (cols == null || cols.isEmpty()) return false;

        String tableNameLower = table.getTableName().toLowerCase();

        // Signal 1 — table name pattern
        if (EAV_TABLE_NAME_PATTERN.matcher(tableNameLower).matches()) signals++;

        // Signal 2 — low column count
        if (cols.size() <= MAX_EAV_COLUMN_COUNT) signals++;

        String entityIdCol   = null;
        String attrNameCol   = null;
        String attrValueCol  = null;

        for (SchemaColumn col : cols) {
            String n = col.getName().toLowerCase();
            if (entityIdCol == null && ENTITY_ID_PATTERN.matcher(n).matches()) {
                entityIdCol = col.getName();
                signals++;  // Signal 3 — entity-id column found
            } else if (attrNameCol == null && ATTR_NAME_PATTERN.matcher(n).matches()
                    && !col.isPrimaryKey()) {
                attrNameCol = col.getName();
                signals++;  // Signal 4 — attribute-name column found
            } else if (attrValueCol == null && ATTR_VALUE_PATTERN.matcher(n).matches()
                    && !col.isPrimaryKey()) {
                attrValueCol = col.getName();
                signals++;  // Signal 5 — attribute-value column found
            }
        }

        // Description keywords boost
        String desc = table.getDescription();
        if (desc != null) {
            String dl = desc.toLowerCase();
            if (dl.contains("eav") || dl.contains("key-value") || dl.contains("key value")
                    || dl.contains("attribute") || dl.contains("dynamic properties")
                    || dl.contains("vertical") || dl.contains("metadata")) {
                signals++;
            }
        }

        if (signals >= 3) {
            table.setEavTable(true);
            EavConfig config = buildEavConfig(table, entityIdCol, attrNameCol, attrValueCol);
            table.setEavConfig(config);
            enrichKnownAttributeSynonyms(config);
            log.info("Table '{}' identified as EAV (signals={}) — entityId='{}', attrName='{}', attrValue='{}'",
                    table.getTableName(), signals, config.resolveEntityIdColumn(),
                    config.resolveAttributeNameColumn(), config.resolveAttributeValueColumn());
            return true;
        }
        return false;
    }

    // =========================================================================
    // EavConfig builders
    // =========================================================================

    /**
     * Builds an EavConfig from detected column roles.
     * Falls back to defaults when a specific column wasn't detected.
     *
     * IMPORTANT: For EAV data tables (e.g. productattributevalues), the
     * "entity_id" column is the FK that links to the ENTITY table (products),
     * NOT the FK that links to the EAV meta/attributes table.  The detection
     * patterns may pick 'attributeid' as entity_id because it matches the
     * pattern — but attributeid usually points to the EAV ATTRIBUTES table,
     * not the entity.  We correct this by checking whether the detected
     * entityIdCol FK-references a table whose name looks like an attributes/
     * EAV table.  If so, we search for a different FK column that points to
     * a non-EAV entity table.
     */
    private EavConfig buildEavConfig(DatabaseTable table,
                                      String entityIdCol,
                                      String attrNameCol,
                                      String attrValueCol) {
        // If entity-id wasn't matched by pattern, try any FK column
        if (entityIdCol == null && table.getColumns() != null) {
            entityIdCol = table.getColumns().stream()
                    .filter(SchemaColumn::isForeignKey)
                    .map(SchemaColumn::getName)
                    .findFirst()
                    .orElse(null);
        }

        // ── Correct mis-detected entity_id that actually points to the
        //    EAV attributes/meta table rather than the real entity table.
        //    e.g. attributeid → attributes (meta table), but we need
        //         productid  → products   (entity table)
        String entityTable = null;
        if (entityIdCol != null && table.getColumns() != null) {
            final String eidCol = entityIdCol;
            SchemaColumn eidSchemaCol = table.getColumns().stream()
                    .filter(c -> c.getName().equalsIgnoreCase(eidCol))
                    .findFirst().orElse(null);

            String eidFkTarget = null;
            if (eidSchemaCol != null && eidSchemaCol.getForeignKeyReference() != null) {
                String fkRef = eidSchemaCol.getForeignKeyReference();
                eidFkTarget = fkRef.contains(".") ? fkRef.substring(0, fkRef.indexOf('.')) : fkRef;
            }

            // Check if the detected entityIdCol's FK target looks like an EAV/attributes table
            boolean pointsToEavMeta = eidFkTarget != null
                    && EAV_TABLE_NAME_PATTERN.matcher(eidFkTarget.toLowerCase()).matches();

            if (pointsToEavMeta) {
                // The detected entityIdCol (e.g. attributeid) points to the EAV meta table.
                // Search for a DIFFERENT FK column that points to a non-EAV entity table.
                log.info("EAV config correction: '{}' in table '{}' FK-references EAV meta table '{}' " +
                         "— searching for the real entity FK column",
                        entityIdCol, table.getTableName(), eidFkTarget);

                for (SchemaColumn col : table.getColumns()) {
                    if (!col.isForeignKey() || col.getForeignKeyReference() == null) continue;
                    if (col.getName().equalsIgnoreCase(entityIdCol)) continue; // skip the one we already checked
                    if (col.getName().equalsIgnoreCase(attrNameCol)) continue;  // skip attr name col
                    if (col.getName().equalsIgnoreCase(attrValueCol)) continue; // skip attr value col

                    String ref = col.getForeignKeyReference();
                    String refTable = ref.contains(".") ? ref.substring(0, ref.indexOf('.')) : ref;

                    // Is this FK pointing to a non-EAV table?
                    if (!EAV_TABLE_NAME_PATTERN.matcher(refTable.toLowerCase()).matches()) {
                        log.info("EAV config correction: using '{}' → '{}' as entity_id (instead of '{}' → '{}')",
                                col.getName(), refTable, entityIdCol, eidFkTarget);
                        entityIdCol = col.getName();
                        entityTable = refTable;
                        break;
                    }
                }

                // If no non-EAV FK was found, keep the original and use its target
                if (entityTable == null) {
                    entityTable = eidFkTarget;
                }
            } else {
                entityTable = eidFkTarget;
            }
        }

        // Look for a value_type / data_type column
        String valueTypeCol = findColumnByPattern(table,
                Pattern.compile("(?i)^.*(value_type|data_type|type|dtype).*$"));

        // ── Detect two-table EAV pattern ──────────────────────────────────────
        // When the EAV data table has NO attribute name column of its own but
        // has an FK to an attributes/meta table, the attribute names live there.
        // e.g. productattributevalues.attributeid → attributes (meta table with "name" column)
        String attrMetaTable      = null;
        String attrMetaJoinCol    = null;
        String attrMetaNameCol    = null;

        if (attrNameCol == null && table.getColumns() != null) {
            // Find an FK column pointing to an EAV meta/attributes table
            for (SchemaColumn col : table.getColumns()) {
                if (!col.isForeignKey() || col.getForeignKeyReference() == null) continue;
                if (col.getName().equalsIgnoreCase(entityIdCol)) continue; // skip entity FK

                String ref = col.getForeignKeyReference();
                String refTable = ref.contains(".") ? ref.substring(0, ref.indexOf('.')) : ref;

                if (EAV_TABLE_NAME_PATTERN.matcher(refTable.toLowerCase()).matches()) {
                    attrMetaTable   = refTable;
                    attrMetaJoinCol = col.getName();
                    // The name column in the meta table is typically "name" or "attribute_name"
                    attrMetaNameCol = "name";  // default — most common
                    log.info("Two-table EAV detected: '{}'.{} → meta table '{}' " +
                             "(attribute names via {}.{})",
                            table.getTableName(), attrMetaJoinCol, attrMetaTable,
                            attrMetaTable, attrMetaNameCol);
                    break;
                }
            }
        }

        return EavConfig.builder()
                .entityIdColumn(entityIdCol)
                .attributeNameColumn(attrNameCol)
                .attributeValueColumn(attrValueCol)
                .valueTypeColumn(valueTypeCol)
                .entityTable(entityTable)
                .attributeMetaTable(attrMetaTable)
                .attributeMetaJoinColumn(attrMetaJoinCol)
                .attributeMetaNameColumn(attrMetaNameCol)
                .knownAttributes(new ArrayList<>())
                .build();
    }

    /**
     * When the table was explicitly flagged (is_eav_table=true) but has no
     * eav_config, infer as much as possible from the column structure.
     */
    private EavConfig inferEavConfig(DatabaseTable table) {
        String entityIdCol  = findColumnByPattern(table, ENTITY_ID_PATTERN);
        String attrNameCol  = findColumnByPattern(table, ATTR_NAME_PATTERN);
        String attrValueCol = findColumnByPattern(table, ATTR_VALUE_PATTERN);
        return buildEavConfig(table, entityIdCol, attrNameCol, attrValueCol);
    }

    // =========================================================================
    // Known-attribute synonym enrichment
    // =========================================================================

    /**
     * Enriches known_attributes with auto-generated synonyms from the
     * built-in synonym dictionary when synonyms aren't already provided.
     */
    private void enrichKnownAttributeSynonyms(EavConfig config) {
        if (config == null || config.getKnownAttributes() == null) return;
        for (EavKnownAttribute attr : config.getKnownAttributes()) {
            if (attr.getSynonyms() == null || attr.getSynonyms().isEmpty()) {
                String key = attr.getAttributeKey().toLowerCase();
                List<String> syns = BUILT_IN_SYNONYMS.get(key);
                if (syns != null) {
                    attr.setSynonyms(new ArrayList<>(syns));
                }
            }
            // Auto-derive castHint when not set
            if ((attr.getCastHint() == null || attr.getCastHint().isBlank())
                    && config.resolveAttributeValueColumn() != null) {
                attr.setCastHint(attr.resolveCastHint(config.resolveAttributeValueColumn()));
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String findColumnByPattern(DatabaseTable table, Pattern pattern) {
        if (table.getColumns() == null) return null;
        return table.getColumns().stream()
                .filter(c -> pattern.matcher(c.getName().toLowerCase()).matches())
                .map(SchemaColumn::getName)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns all known attribute keys for a table as a readable summary.
     * Used in log messages and description enrichment.
     */
    public String summarizeKnownAttributes(EavConfig config) {
        if (config == null || config.getKnownAttributes() == null
                || config.getKnownAttributes().isEmpty()) {
            return "(none catalogued)";
        }
        return config.getKnownAttributes().stream()
                .map(a -> a.getAttributeKey() + ":" + (a.getDataType() != null ? a.getDataType() : "?"))
                .collect(Collectors.joining(", "));
    }
}
