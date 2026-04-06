package com.nlp.rag.seek.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlp.rag.seek.model.DatabaseSchema;
import com.nlp.rag.seek.model.DatabaseTable;
import com.nlp.rag.seek.model.SchemaColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects whether a database schema uses abbreviated table/column names and,
 * if so, calls the LLM to produce a mapping from abbreviated → descriptive names.
 *
 * <h3>Pipeline (called once at schema-upload time)</h3>
 * <ol>
 *   <li><b>Step 1 — Detection:</b> Send all table names to the LLM and ask:
 *       "Are these table names abbreviated?"  LLM responds with JSON
 *       {@code {"abbreviated": true/false, "tables": {"sch_dtls":"school_details", ...}}}</li>
 *   <li><b>Step 2 — Column mapping:</b> If abbreviated, group tables by FK relationships
 *       and send each group's table + column names to the LLM to get descriptive
 *       column names for every column.</li>
 *   <li><b>Step 3 — Persist:</b> Write the full mapping to
 *       {@code {dbname}_mapper.json} via {@link AbbreviatedSchemaMapper}.</li>
 * </ol>
 *
 * <p>This service is intentionally decoupled from the main RAG pipeline.
 * It is only invoked when a schema is uploaded or a live DB is connected.
 * If the LLM determines the schema is NOT abbreviated, the mapper is saved
 * with {@code abbreviated=false} and all downstream code skips translation.</p>
 */
@Service
public class AbbreviatedSchemaDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AbbreviatedSchemaDetectionService.class);
    private static final ObjectMapper om = new ObjectMapper();

    @Autowired(required = false)
    @Qualifier("sqlChatClient")
    private ChatClient chatClient;

    @Autowired
    private AbbreviatedSchemaMapper mapper;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Detects if the schema is abbreviated. If so, builds the full mapping
     * (tables + columns) and persists it.
     *
     * @param schema       the parsed DatabaseSchema
     * @param userName     SEEK user name (for per-user directory)
     * @return true if the schema was determined to be abbreviated
     */
    public boolean detectAndMap(DatabaseSchema schema, String userName) {
        if (schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
            log.warn("detectAndMap called with null/empty schema — skipping");
            return false;
        }
        String dbName = schema.getDatabaseName();
        log.info("▶ Abbreviated schema detection — db='{}' ({} tables)", dbName, schema.getTables().size());

        if (chatClient == null) {
            log.warn("ChatClient not available — cannot detect abbreviations (no OPENAI_API_KEY?)");
            return false;
        }

        // ── Step 1: Detection + table-level mapping ──────────────────────────
        List<String> tableNames = schema.getTables().stream()
                .map(DatabaseTable::getTableName)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        DetectionResult detection = callLlmForTableDetection(tableNames, dbName);
        if (detection == null) {
            log.warn("LLM detection call failed — treating schema as non-abbreviated");
            saveNonAbbreviated(dbName, userName);
            return false;
        }

        if (!detection.abbreviated) {
            log.info("◀ Schema '{}' is NOT abbreviated — no mapping needed", dbName);
            saveNonAbbreviated(dbName, userName);
            return false;
        }

        log.info("Schema '{}' IS abbreviated — {} table mapping(s) from LLM",
                dbName, detection.tableMappings.size());

        // ── Step 2: Column-level mapping (grouped by FK relationships) ───────
        Map<String, AbbreviatedSchemaMapper.TableMapping> fullMappings = new LinkedHashMap<>();

        // Group tables by FK relationships for context-aware column naming
        List<List<DatabaseTable>> groups = groupTablesByFk(schema);
        log.info("Grouped {} tables into {} FK-connected groups for column detection",
                tableNames.size(), groups.size());

        for (List<DatabaseTable> group : groups) {
            Map<String, Map<String, String>> columnMappings = callLlmForColumnMapping(
                    group, detection.tableMappings, dbName);

            log.info("LLM returned column mappings for {} table(s) in group: {}",
                    columnMappings.size(),
                    columnMappings.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue().size() + " cols")
                            .collect(Collectors.joining(", ")));

            for (DatabaseTable table : group) {
                String tblLower = table.getTableName().toLowerCase();
                String descriptiveTableName = detection.tableMappings
                        .getOrDefault(tblLower, table.getTableName());

                Map<String, String> colMap = columnMappings.getOrDefault(tblLower, new LinkedHashMap<>());

                // Only fill in columns the LLM missed — do NOT override LLM mappings
                if (table.getColumns() != null) {
                    for (SchemaColumn col : table.getColumns()) {
                        String colLower = col.getName().toLowerCase();
                        if (!colMap.containsKey(colLower)) {
                            // LLM didn't map this column — keep original
                            colMap.put(colLower, col.getName());
                            log.debug("Column '{}' in table '{}' not mapped by LLM — keeping original",
                                    col.getName(), table.getTableName());
                        }
                    }
                }

                log.info("Table '{}' → '{}' | column mappings: {}",
                        tblLower, descriptiveTableName, colMap);

                fullMappings.put(tblLower,
                        new AbbreviatedSchemaMapper.TableMapping(descriptiveTableName, colMap));
            }
        }

        // ── Step 3: Persist ──────────────────────────────────────────────────
        AbbreviatedSchemaMapper.MapperData data = new AbbreviatedSchemaMapper.MapperData(
                dbName, true, fullMappings);
        mapper.save(dbName, data, userName);

        log.info("◀ Abbreviated schema detection complete — db='{}', {} table mappings persisted",
                dbName, fullMappings.size());
        return true;
    }

    /**
     * Loads the mapper from disk if it exists.
     * Called on pipeline restart / schema reload to restore the cache.
     *
     * @return true if a mapper was loaded and the schema is abbreviated
     */
    public boolean loadExisting(String databaseName, String userName) {
        AbbreviatedSchemaMapper.MapperData data = mapper.load(databaseName, userName);
        return data != null && data.abbreviated;
    }

    // =========================================================================
    // LLM Calls
    // =========================================================================

    /**
     * Step 1: Ask LLM if table names are abbreviated and get descriptive names.
     */
    private DetectionResult callLlmForTableDetection(List<String> tableNames, String dbName) {
        String tableList = String.join(", ", tableNames);

        String prompt = """
                You are a database schema analyst. Examine these table names from a database called "%s":

                Table names: [%s]

                Determine if these table names are abbreviated/cryptic (e.g. "sch_dtls" instead of "school_details", \
                "emp" instead of "employees", "ord_itm" instead of "order_items") or descriptive/self-explanatory.

                Respond ONLY with valid JSON in this exact format:
                {
                  "abbreviated": true or false,
                  "tables": {
                    "abbreviated_name": "descriptive_name",
                    ...
                  }
                }

                Rules:
                1. If the table names are already descriptive (e.g. "students", "school_details", "order_items"), \
                set "abbreviated" to false and leave "tables" empty {}.
                2. If abbreviated, map EVERY table name to its most likely descriptive equivalent.
                3. Descriptive names should use snake_case (e.g. "school_details" not "SchoolDetails").
                4. Preserve original casing in the keys.
                5. Common abbreviations: dtls=details, emp=employee, dept=department, addr=address, \
                sch=school, std=student, tchr=teacher, acct=account, trx/txn=transaction, inv=invoice, \
                cat=category, prod=product, cust=customer, ord=order, itm=item, qty=quantity, amt=amount.
                6. Do NOT wrap the response in markdown code blocks.
                """.formatted(dbName, tableList);

        try {
            String response = chatClient.prompt(new Prompt(new UserMessage(prompt))).call().content();
            log.debug("LLM table detection response:\n{}", response);

            // Strip markdown code fences if present
            String cleaned = response.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```(?:json)?\\s*", "").replaceAll("```\\s*$", "").trim();
            }

            JsonNode root = om.readTree(cleaned);
            boolean abbreviated = root.path("abbreviated").asBoolean(false);
            Map<String, String> tableMappings = new LinkedHashMap<>();

            JsonNode tablesNode = root.path("tables");
            if (tablesNode.isObject()) {
                tablesNode.fields().forEachRemaining(entry ->
                        tableMappings.put(entry.getKey().toLowerCase(), entry.getValue().asText()));
            }

            return new DetectionResult(abbreviated, tableMappings);
        } catch (Exception e) {
            log.error("LLM table detection failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Step 2: For a group of FK-related tables, get descriptive column names.
     */
    private Map<String, Map<String, String>> callLlmForColumnMapping(
            List<DatabaseTable> tableGroup,
            Map<String, String> tableMappings,
            String dbName) {

        StringBuilder sb = new StringBuilder();
        sb.append("Database: ").append(dbName).append("\n\n");
        sb.append("The following tables have ABBREVIATED column names. ");
        sb.append("For EVERY column in EVERY table, provide the FULL DESCRIPTIVE column name.\n\n");

        for (DatabaseTable table : tableGroup) {
            String tblLower = table.getTableName().toLowerCase();
            String descriptive = tableMappings.getOrDefault(tblLower, table.getTableName());
            sb.append("Table: ").append(table.getTableName())
              .append(" (full name: ").append(descriptive).append(")\n");
            sb.append("  Columns:\n");
            if (table.getColumns() != null) {
                for (SchemaColumn col : table.getColumns()) {
                    sb.append("    - ").append(col.getName())
                      .append(" (").append(col.getDataType()).append(")");
                    if (col.isPrimaryKey()) sb.append(" [PRIMARY KEY]");
                    if (col.isForeignKey()) sb.append(" [FK → ").append(col.getForeignKeyReference()).append("]");
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        String prompt = sb + """

                TASK: Map each abbreviated column name to its full descriptive name.

                EXAMPLE:
                If table "sch_dtl" (full name: school_details) has columns: sch_id, sch_nm, sch_loc
                The correct mapping is:
                {
                  "sch_dtl": {
                    "sch_id": "school_id",
                    "sch_nm": "school_name",
                    "sch_loc": "school_location"
                  }
                }

                IMPORTANT RULES:
                1. EVERY column MUST be mapped to a full descriptive name.
                2. Do NOT map a column to itself (e.g. "sch_nm" → "sch_nm" is WRONG, should be "sch_nm" → "school_name").
                3. Use snake_case for all descriptive names.
                4. FK columns should use the full descriptive name (e.g. "sch_id" → "school_id", "dept_id" → "department_id").
                5. Common abbreviations: nm/nme=name, dt/dte=date, dsc/desc=description, \
                loc=location, sts=status, qty=quantity, amt=amount, addr=address, \
                dob=date_of_birth, fname/f_nm=first_name, lname/l_nm=last_name, \
                ph/phn=phone, em/eml=email, sal=salary, mgr=manager, \
                dept=department, sch=school, stu=student, crs=course, \
                crdt=credits, dtl=details, hd/head=head.
                6. Use the table key in LOWERCASE exactly as shown.
                7. Do NOT wrap the response in markdown code blocks.
                8. Respond ONLY with valid JSON, nothing else.

                Respond in this exact JSON format:
                {
                  "table_name_lowercase": {
                    "abbreviated_col_name": "full_descriptive_col_name",
                    ...
                  },
                  ...
                }
                """;

        try {
            log.info("Calling LLM for column mapping — {} table(s): [{}]",
                    tableGroup.size(),
                    tableGroup.stream().map(DatabaseTable::getTableName).collect(Collectors.joining(", ")));

            String response = chatClient.prompt(new Prompt(new UserMessage(prompt))).call().content();
            log.info("LLM column mapping raw response:\n{}", response);

            String cleaned = response.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```(?:json)?\\s*", "").replaceAll("```\\s*$", "").trim();
            }

            Map<String, Map<String, String>> result =
                    om.readValue(cleaned, new TypeReference<Map<String, Map<String, String>>>() {});

            // Validate: check if any column mapped to itself (LLM error)
            for (Map.Entry<String, Map<String, String>> tableEntry : result.entrySet()) {
                for (Map.Entry<String, String> colEntry : tableEntry.getValue().entrySet()) {
                    if (colEntry.getKey().equalsIgnoreCase(colEntry.getValue())) {
                        log.warn("LLM mapped column '{}' to itself in table '{}' — this indicates " +
                                "the LLM did not expand the abbreviation", colEntry.getKey(), tableEntry.getKey());
                    }
                }
            }

            return result;
        } catch (Exception e) {
            log.error("LLM column mapping failed for group [{}]: {}",
                    tableGroup.stream().map(DatabaseTable::getTableName).collect(Collectors.joining(",")),
                    e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    // =========================================================================
    // FK Grouping
    // =========================================================================

    /**
     * Groups tables into connected components based on FK relationships.
     * Tables that reference each other (directly or transitively) end up in the same group.
     * This gives the LLM FK context so it can infer consistent column names across related tables.
     */
    private List<List<DatabaseTable>> groupTablesByFk(DatabaseSchema schema) {
        if (schema.getTables() == null) return Collections.emptyList();

        // Build adjacency: table → set of related tables (via FK)
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        Map<String, DatabaseTable> byName = new LinkedHashMap<>();

        for (DatabaseTable t : schema.getTables()) {
            String tName = t.getTableName().toLowerCase();
            byName.put(tName, t);
            adj.computeIfAbsent(tName, k -> new LinkedHashSet<>());

            if (t.getColumns() != null) {
                for (SchemaColumn col : t.getColumns()) {
                    if (col.isForeignKey() && col.getForeignKeyReference() != null) {
                        String ref = col.getForeignKeyReference().toLowerCase().trim();
                        String refTable = ref.contains(".") ? ref.substring(0, ref.indexOf('.')) : ref;
                        if (!refTable.isBlank()) {
                            adj.computeIfAbsent(tName, k -> new LinkedHashSet<>()).add(refTable);
                            adj.computeIfAbsent(refTable, k -> new LinkedHashSet<>()).add(tName);
                        }
                    }
                }
            }
        }

        // BFS to find connected components
        Set<String> visited = new HashSet<>();
        List<List<DatabaseTable>> groups = new ArrayList<>();

        for (String tName : byName.keySet()) {
            if (visited.contains(tName)) continue;

            List<DatabaseTable> group = new ArrayList<>();
            Queue<String> queue = new LinkedList<>();
            queue.add(tName);
            visited.add(tName);

            while (!queue.isEmpty()) {
                String curr = queue.poll();
                DatabaseTable dt = byName.get(curr);
                if (dt != null) group.add(dt);

                Set<String> neighbors = adj.getOrDefault(curr, Collections.emptySet());
                for (String neighbor : neighbors) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }

            groups.add(group);
        }

        return groups;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void saveNonAbbreviated(String dbName, String userName) {
        AbbreviatedSchemaMapper.MapperData data = new AbbreviatedSchemaMapper.MapperData(
                dbName, false, Collections.emptyMap());
        mapper.save(dbName, data, userName);
    }

    /**
     * Internal result from the LLM table detection call.
     */
    private record DetectionResult(boolean abbreviated, Map<String, String> tableMappings) {}
}

