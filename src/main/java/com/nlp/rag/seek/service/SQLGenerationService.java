package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlp.rag.seek.model.DatabaseSchema;
import com.nlp.rag.seek.model.DatabaseTable;
import com.nlp.rag.seek.model.NaturalLanguageQueryRequest;
import com.nlp.rag.seek.model.PiiSanitizationResult;
import com.nlp.rag.seek.model.PiiToken;
import com.nlp.rag.seek.model.SchemaChunk;
import com.nlp.rag.seek.model.SchemaColumn;
import com.nlp.rag.seek.model.SQLGenerationResponse;
import com.nlp.rag.seek.service.ConfidenceScoreService.ConfidenceResult;
import com.nlp.rag.seek.service.SQLValidationService.ValidationResult;
import com.nlp.rag.seek.service.VectorStoreService.ScoredChunk;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
        import java.util.regex.*;
        import java.util.stream.Collectors;


/**
 * Orchestrates the full NL→SQL RAG pipeline:
 *
 *  1. Embed the NL query and retrieve top-k chunks (cosine similarity)
 *  2. Build a context-rich prompt (chunk texts + table/column details)
 *  3. Call the LLM to generate the SQL
 *  4. Validate the SQL structurally
 *  5. Compute a multi-factor confidence score
 *  6. Generate a plain-English explanation of the SQL
 *  7. Return a fully-populated SQLGenerationResponse
 */
@Service
public class SQLGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SQLGenerationService.class);


    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```(?:sql)?\\s*\\n?([^`]+?)```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LEADING_SQL = Pattern.compile(
            "(?i)^\\s*(SELECT|WITH|INSERT|UPDATE|DELETE)\\b.*",
            Pattern.MULTILINE | Pattern.DOTALL);

    @Autowired private VectorStoreService       vectorStoreService;
    @Autowired private SQLValidationService     validationService;
    @Autowired private ConfidenceScoreService   confidenceService;
    @Autowired private SQLExplanationService    explanationService;
    @Autowired private SchemaExtractionService  schemaExtractionService;
    @Autowired private RAGInitializationService ragInitializationService;
    @Autowired private PiiSanitizationService   piiSanitizationService;
    @Autowired private SemanticCacheService     semanticCacheService;
    @Autowired private TranscriptService        transcriptService;
    @Autowired private DynamicDataSourceRegistry dynamicDataSourceRegistry;
    @Autowired private AbbreviatedSchemaChunkTranslator abbreviatedChunkTranslator;

    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("sqlChatClient")
    private ChatClient chatClient;

    // ── Business rules loaded from BusinessRulesForPrompts.json ─────────────
    private static final String BUSINESS_RULES_JSON = "supportingFiles/BusinessRulesForPrompts.json";
    private final ObjectMapper  objectMapper         = new ObjectMapper();

    /** "You are an expert SQL developer…" — first SQL_GENERAL rule (number 0) used as system instruction */
    private String systemInstruction = "You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query.";

    /** Numbered SQL_GENERAL rules built as a formatted string at startup */
    private String generalRulesText  = "";

    /** EAV section header line */
    private String eavRulesHeader    = "EAV Table Rules (apply when querying tables marked ⚠ EAV TABLE):";

    /** Numbered SQL_EAV rules built as a formatted string at startup */
    private String eavRulesText      = "";

    /** Numbered RETRY rules built as a formatted string at startup */
    private String retryRulesText    = "";

    @PostConstruct
    public void loadBusinessRules() {
        try {
            // String category = "A";
            // Try filesystem first (written by BusinessRulesJsonExporter at startup)
            java.nio.file.Path fsPath = java.nio.file.Paths.get(
                    "src/main/resources/supportingFiles", "BusinessRulesForPrompts.json").toAbsolutePath();
            InputStream is;
            if (java.nio.file.Files.exists(fsPath)) {
                is = java.nio.file.Files.newInputStream(fsPath);
            } else {
                is = new ClassPathResource(BUSINESS_RULES_JSON).getInputStream();
            }

            JsonNode root = objectMapper.readTree(is);
            is.close();

            if (root.isArray()) {
                // Flat array format from BusinessRulesJsonExporter
                StringBuilder generalSb = new StringBuilder();
                StringBuilder eavSb     = new StringBuilder();
                StringBuilder retrySb   = new StringBuilder();

                for (JsonNode ruleNode : root) {
                    boolean enabled  = ruleNode.path("enabled").asBoolean(true);
                    if (!enabled) continue;

                    String ruleType = ruleNode.path("ruleType").asText("");
                    int number      = ruleNode.path("number").asInt(0);
                    String ruleText = ruleNode.path("rule").asText("");
                    String ruleCategory = ruleNode.path("category").asText("");

                    switch (ruleType) {
                        case "SQL_GENERAL" -> {
                            if (number == 0 && !ruleText.isBlank()) {
                                systemInstruction = ruleText;
                            } else {
                               // if( ruleCategory.equalsIgnoreCase(category)) {//just to switch between rules category
                                    generalSb.append(number).append(". ").append(ruleText).append("\n");
                               // }
                            }
                        }
                        case "SQL_EAV" -> {
                            if (number == 0 && !ruleText.isBlank()) {
                               // eavRulesHeader = ruleText;
                            } else {
                                eavSb.append(number).append(". ").append(ruleText).append("\n");
                            }
                        }
                        case "RETRY" -> retrySb.append(number).append(". ").append(ruleText).append("\n");
                        default -> { /* DOCUMENT, EXPLANATION handled by their own services */ }
                    }
                }

                generalRulesText = generalSb.toString().stripTrailing();
                eavRulesText     = eavSb.toString().stripTrailing();
                retryRulesText   = retrySb.toString().stripTrailing();
            }

            log.info("Business rules loaded from {} — systemInstruction={} chars, generalRules={} chars, eavRules={} chars, retryRules={} chars",
                    BUSINESS_RULES_JSON,
                    systemInstruction.length(),
                    generalRulesText.length(),
                    eavRulesText.length(),
                    retryRulesText.length());

        } catch (Exception e) {
            log.warn("BusinessRulesForPrompts.json not available yet — using hardcoded defaults (will reload after DB export): {}",
                    e.getMessage());
            // Fallback defaults keep the service operational even if the file is missing
            generalRulesText =
                    "1. Use ONLY the tables and columns that appear in the schema context below.\n" +
                            "2. Write standard SQL compatible with PostgreSQL.\n" +
                            "3. Use explicit JOINs with ON clauses — never implicit comma joins.\n" +
                            "4. Add a WHERE clause whenever a filter is implied.\n" +
                            "5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.\n" +
                            "6. Add ORDER BY when a ranking or 'top N' is implied.\n" +
                            "7. Return ONLY the SQL statement — no prose, no markdown fences.\n" +
                            "8. End the statement with a semicolon.\n" +
                            "9. CASE-INSENSITIVE COMPARISONS: When comparing string/varchar/text values in WHERE clauses, always use ILIKE instead of = to handle case differences.";
            eavRulesText =
                    "1. For EAV tables: NEVER reference attribute names as SQL column names.\n" +
                            "2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.\n" +
                            "3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.\n" +
                            "4. For multiple attributes in one row, use conditional aggregation.\n" +
                            "5. Join to the entity table via the entity_id column shown in the context.\n" +
                            "6. EAV attributes retrieved for this query are listed in the schema context.\n" +
                            "7. When filtering EAV attribute values (string/varchar), always use ILIKE for case-insensitive matching instead of =.";
            retryRulesText =
                    "0. CORRECTION REQUIRED: Your previous SQL response hallucinated table or column names that do NOT exist in the database schema.";
        }
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Main entry point called by the controller — accepts the full request object
     * so all request fields are persisted to the transcript file.
     */
    public SQLGenerationResponse generateSQL(com.nlp.rag.seek.model.NaturalLanguageQueryRequest request) {
        String databaseName = request.getDatabaseName() != null ? request.getDatabaseName() : "ecommerce_db";
        int topK = request.getTopK() > 0 ? request.getTopK() : 5;
        return generateSQL(request, databaseName, topK);
    }

    /**
     * Legacy overload used by CacheAdminService and other callers that don't
     * have a full request object.  Transcript will capture what it can.
     */
    public SQLGenerationResponse generateSQL(String nlQuery,
                                             String databaseName,
                                             int topK) {
        com.nlp.rag.seek.model.NaturalLanguageQueryRequest syntheticReq =
                com.nlp.rag.seek.model.NaturalLanguageQueryRequest.builder()
                        .query(nlQuery)
                        .databaseName(databaseName)
                        .topK(topK)
                        .build();
        return generateSQL(syntheticReq, databaseName, topK);
    }

    /** Core implementation — receives the full request for transcript persistence. */
    private SQLGenerationResponse generateSQL(com.nlp.rag.seek.model.NaturalLanguageQueryRequest request,
                                              String databaseName,
                                              int topK) {
        String nlQuery = request.getQuery();
        long pipelineStartTime = System.currentTimeMillis();
        log.info("▶ NL→SQL pipeline | query='{}' | db='{}' | topK={}",
                nlQuery, databaseName, topK);

        if (nlQuery == null || nlQuery.isBlank()) {
            return error(nlQuery, "Query is empty.");
        }

        // ── Step 0: PII / PHI sanitization ───────────────────────────────────
        // Replace any sensitive values (SSN, email, phone, names, etc.) with
        // placeholder tokens before the query touches the vector store or LLM.
        PiiSanitizationResult piiResult = piiSanitizationService.sanitize(nlQuery);
        String sanitizedQuery = piiResult.getSanitizedQuery();
        List<PiiToken> piiTokens = piiResult.getTokens();
        if (piiResult.isPiiDetected()) {
            log.warn("PII detected in query — {} token(s) masked, sending sanitized query to pipeline",
                    piiTokens.size());
        }

        // ── Step 0b: Redis semantic cache lookup ──────────────────────────────
        // Before hitting the vector store or LLM, check if a semantically
        // similar query has already been answered and is cached in Redis.
        // Skip cache entirely when the client explicitly sets cacheEnabled=false.
        boolean cacheEnabled = request.isCacheEnabled();
        if (!cacheEnabled) {
            log.info("⏭ Cache SKIP — client requested cacheEnabled=false, forcing fresh LLM call");
        }
        if (cacheEnabled) {
            Optional<SQLGenerationResponse> cached =
                    semanticCacheService.lookup(sanitizedQuery, databaseName);
            if (cached.isPresent()) {
                SQLGenerationResponse hit = cached.get();
                // Restore PII values into the cached SQL too
                hit.setGeneratedSQL(piiSanitizationService.restore(hit.getGeneratedSQL(), piiTokens));
                hit.setNaturalLanguageQuery(nlQuery);          // restore original unmasked query
                hit.setPiiTokensFound(piiSanitizationService.summarize(piiTokens));
                log.info("◀ Cache HIT — returning cached response (similarity={}, hits={})",
                        String.format("%.4f", hit.getCacheSimilarityScore()), hit.getCacheHitCount());
                return hit;
            }
        }

        // ── Step 0c: ensure correct vector index is loaded for the requested DB ─
        // The VectorStoreService is a singleton with a single in-memory index.
        // If the requested databaseName differs from the currently loaded index,
        // restore the correct one from disk (user directory first, then root).
        String currentDb = vectorStoreService.getCurrentDatabaseName();
        if (currentDb == null || !currentDb.equalsIgnoreCase(databaseName)) {
            String userName = request.getUserName();
            log.info("Vector index switch required: current='{}' → requested='{}' (user='{}')",
                    currentDb, databaseName, userName);
            boolean restored = vectorStoreService.restoreFromDisk(databaseName, userName);
            if (!restored) {
                restored = vectorStoreService.restoreFromDisk(databaseName);
            }
            if (restored) {
                try {
                    DatabaseSchema reloadedSchema =
                            schemaExtractionService.buildActiveSchemaFromJson(databaseName);
                    if (reloadedSchema != null && !reloadedSchema.getTables().isEmpty()) {
                        ragInitializationService.setActiveSchema(reloadedSchema);
                        log.info("Schema and vector index switched to '{}' ({} tables, {} chunks)",
                                databaseName, reloadedSchema.getTables().size(),
                                vectorStoreService.indexSize());
                    }
                } catch (Exception e) {
                    log.warn("Could not reload schema JSON for '{}': {}", databaseName, e.getMessage());
                }
                // Load abbreviated mapper for the switched database
                try {
                    abbreviatedChunkTranslator.isAbbreviated(databaseName); // triggers auto-load from disk
                    if (abbreviatedChunkTranslator.isAbbreviated(databaseName)) {
                        log.info("Abbreviated schema mapper loaded for switched database '{}'", databaseName);
                    }
                } catch (Exception e) {
                    log.debug("No abbreviated mapper for '{}': {}", databaseName, e.getMessage());
                }
            } else {
                log.warn("No persisted vector index found for database '{}' (user='{}') "
                        + "— search will use currently loaded index '{}'",
                        databaseName, userName, currentDb);
            }
        }

        // ── Step 1: retrieve chunks using sanitized query ──────────────────────
        // Fetch a generous window so we have enough candidates for selection.
        long retrievalStartTime = System.currentTimeMillis();
        List<ScoredChunk> allRetrieved = vectorStoreService.search(sanitizedQuery, 15);

        // ── Step 1a: query-term table injection ──────────────────────────────
        // If the user's query explicitly mentions a known table name (e.g.
        // "products") but that table didn't appear in the vector search results,
        // force-include it.
        allRetrieved = injectTablesMatchingQueryTerms(sanitizedQuery, allRetrieved,
                ragInitializationService.getActiveSchema());

        // ── Step 1b: EAV injection pass ──────────────────────────────────────
        // Scan the query for EAV attribute synonyms and force-inject EAV table
        // chunks when a match is found.
        allRetrieved = expandWithEavTablesIfNeeded(sanitizedQuery, allRetrieved,
                ragInitializationService.getActiveSchema());

        // ── Step 1c: FK-expansion pass ───────────────────────────────────────
        // Walk FK columns in all retrieved/injected tables and inject referenced
        // tables not yet in the result set (single-hop).
        allRetrieved = expandWithFkRelatedTables(allRetrieved,
                ragInitializationService.getActiveSchema());

        // ── Step 1d: final chunk selection ───────────────────────────────────
        // 1. Sort all candidates by score (highest → lowest)
        // 2. Deduplicate by table name (keep highest score per table)
        // 3. Take top 5 unique table chunks
        // 4. If any of the 5 is EAV → ensure related EAV + entity tables are
        //    included (may expand beyond 5)
        // 5. If NO EAV table in top 5 → reduce to top 3 only
        List<ScoredChunk> scoredChunks = selectFinalChunks(allRetrieved,
                ragInitializationService.getActiveSchema());

        long retrievalDurationMs = System.currentTimeMillis() - retrievalStartTime;

        // ── Tier-1 + Tier-2 miss detection ────────────────────────────────────
        // A miss occurs when:
        //   (a) the index returned nothing at all, OR
        //   (b) every returned chunk has a near-zero score (no meaningful match)
        final double MIN_RELEVANCE_SCORE = 0.05;
        boolean allScoresBelowThreshold = !scoredChunks.isEmpty()
                && scoredChunks.stream().allMatch(s -> s.score() < MIN_RELEVANCE_SCORE);

        if (scoredChunks.isEmpty() || allScoresBelowThreshold) {
            log.warn("Tier-1 (semantic) and Tier-2 (keyword) both missed for query='{}' " +
                            "(results={}, belowThreshold={})",
                    nlQuery, scoredChunks.size(), allScoresBelowThreshold);
            long totalDuration = System.currentTimeMillis() - pipelineStartTime;
            transcriptService.appendErrorWithMetadata(request, databaseName,
                    "Tier-1 + Tier-2 miss: prompt did not match any indexed schema table or column.",
                    "TIER_MISS", retrievalDurationMs, 0, 0, totalDuration);
            return tierMissError(nlQuery);
        }

        // ── Step 1e: Abbreviated schema enrichment ─────────────────────────
        // If the schema uses abbreviated names (e.g. sch_dtl → school_details),
        // enrich chunk text with descriptive glossary alongside the originals.
        // The LLM is explicitly instructed to use the ORIGINAL abbreviated names
        // in the SQL, so no reverse translation is needed after the LLM response.
        boolean isAbbreviatedSchema = abbreviatedChunkTranslator.isAbbreviated(databaseName);
        List<ScoredChunk> promptChunks = isAbbreviatedSchema
                ? abbreviatedChunkTranslator.enrichChunksWithDescriptiveContext(databaseName, scoredChunks)
                : scoredChunks;
        if (isAbbreviatedSchema) {
            log.info("Abbreviated schema detected — chunks enriched with descriptive glossary for LLM");
        }

        // ── Step 2: extract metadata from retrieved chunks ────────────────────
        LinkedHashSet<String> tableSet  = new LinkedHashSet<>();
        LinkedHashSet<String> columnSet = new LinkedHashSet<>();
        List<String> chunkTexts = new ArrayList<>();

        for (ScoredChunk sc : scoredChunks) {
            SchemaChunk c = sc.chunk();
            chunkTexts.add(String.format("[score=%.3f | %s] %s",
                    sc.score(), c.getChunkId(), c.getText()));
            if (c.getTableName() != null) tableSet.add(c.getTableName());
            if (c.getColumnName() != null) columnSet.add(c.getColumnName());
        }

        List<String> tables  = new ArrayList<>(tableSet);
        List<String> columns = new ArrayList<>(columnSet);

        // ── Step 3: build prompt with sanitized query and call LLM ───────────
        // Use the schema that is currently indexed in the vector store — this is
        // the uploaded SQL schema (or live JDBC schema) set when RAG was last
        // initialised, NOT a fresh extraction which may fall back to the sample.
        DatabaseSchema schema = ragInitializationService.getActiveSchema();
        String schemaContext  = buildSchemaContext(promptChunks);
        log.info("Sending sanitized query to LLM with top-{} schema chunks", promptChunks.size());

        // ── Resolve database type for SQL dialect in prompt Rule 2 ────────────
        String databaseType = request.getDatabaseType();
        if ((databaseType == null || databaseType.isBlank()) && schema != null) {
            databaseType = schema.getDatabaseType();
        }
        if ((databaseType == null || databaseType.isBlank()
                || "UNKNOWN".equalsIgnoreCase(databaseType))) {
            String regType = dynamicDataSourceRegistry.getDatabaseType(databaseName);
            if (regType != null && !regType.isBlank()) databaseType = regType;
        }
        log.info("Database type for SQL dialect: '{}'", databaseType != null ? databaseType : "PostgreSQL (default)");

        long llmStartTime = System.currentTimeMillis();
        String rawLLMResponse = callLLM(sanitizedQuery, schemaContext, databaseName, scoredChunks, databaseType);
        long llmDurationMs = System.currentTimeMillis() - llmStartTime;

        if (rawLLMResponse == null) {
            String errMsg = "LLM unavailable (no OPENAI_API_KEY?). Set the environment variable and restart.";
            long totalDuration = System.currentTimeMillis() - pipelineStartTime;
            transcriptService.appendErrorWithMetadata(request, databaseName, errMsg,
                    "LLM_UNAVAILABLE", retrievalDurationMs, llmDurationMs, 0, totalDuration);
            return SQLGenerationResponse.builder()
                    .naturalLanguageQuery(nlQuery)
                    .error(errMsg)
                    .sqlValid(false)
                    .confidenceScore(0.0)
                    .confidenceLabel("LOW")
                    .relevantTables(tables)
                    .relevantColumns(columns)
                    .retrievedChunks(chunkTexts)
                    .piiTokensFound(piiSanitizationService.summarize(piiTokens))
                    .build();
        }

        // ── Step 4: extract clean SQL from LLM response ───────────────────────
        // First check whether the LLM signalled that the requested data does not
        // exist in the schema.  The prompt instructs it to respond with the
        // literal prefix "DATA_NOT_AVAILABLE:" when it cannot fulfil the request
        // because the schema has no matching column/table.
        String rawTrimmed = rawLLMResponse == null ? "" : rawLLMResponse.trim();
        if (rawTrimmed.toUpperCase().startsWith("DATA_NOT_AVAILABLE:")) {
            String reason = rawTrimmed.substring("DATA_NOT_AVAILABLE:".length()).trim();
            if (reason.isBlank()) reason = "The requested data is not available in the schema.";
            log.warn("LLM signalled DATA_NOT_AVAILABLE for query='{}': {}", nlQuery, reason);

            // ── EAV-aware retry on DATA_NOT_AVAILABLE ────────────────────────
            // If EAV chunks were included in the original prompt, the LLM may
            // have said DATA_NOT_AVAILABLE because a critical EAV table (e.g.
            // productattributevalues) or its entity table (e.g. products) was
            // missing from the context.  Retry with the FULL EAV table graph.
            boolean hadEavChunk = scoredChunks.stream()
                    .anyMatch(sc -> {
                        Map<String, String> m = sc.chunk().getMetadata();
                        return "true".equals(m.get("is_eav_table"))
                                || "EAV_ATTRIBUTE".equals(m.get("element_type"));
                    });

//            if (hadEavChunk) {
//                log.info("⟳ DATA_NOT_AVAILABLE with EAV chunk(s) in context — attempting EAV-focused retry");
//
//                SQLGenerationResponse eavRetryResp = attemptEavRetry(
//                        sanitizedQuery, nlQuery, databaseName, schema,
//                        piiTokens, tables, columns, chunkTexts, request);
//
//                if (eavRetryResp != null) {
//                    log.info("EAV retry on DATA_NOT_AVAILABLE succeeded — returning corrected SQL");
//                    return eavRetryResp;
//                }
//                log.warn("EAV retry on DATA_NOT_AVAILABLE did not produce valid SQL — returning error");
//            }

            String errMsg = "The requested data is not available in the selected "+databaseName+" database schema. " + reason;
            long totalDuration = System.currentTimeMillis() - pipelineStartTime;
            transcriptService.appendErrorWithMetadata(request, databaseName, errMsg,
                    "DATA_NOT_AVAILABLE", retrievalDurationMs, llmDurationMs, 0, totalDuration);
            return SQLGenerationResponse.builder()
                    .naturalLanguageQuery(nlQuery)
                    .error(errMsg)
                    .generatedSQL("")
                    .sqlValid(false)
                    .confidenceScore(0.0)
                    .confidenceLabel("LOW")
                    .validationErrors(List.of(errMsg))
                    .relevantTables(tables)
                    .relevantColumns(columns)
                    .retrievedChunks(chunkTexts)
                    .piiTokensFound(piiSanitizationService.summarize(piiTokens))
                    .explanation("The LLM determined that the requested information does not exist "
                            + "in the available schema. " + reason)
                    .build();
        }

        String sql = extractSQL(rawLLMResponse);
        log.info("◀ LLM generated SQL:\n══════════════════════════════\n{}\n══════════════════════════════", sql);


        // ── Step 4b: detect prose response (LLM returned text instead of SQL) ─
        // If the extracted "SQL" doesn't start with a recognised SQL keyword,
        // the LLM returned a prose explanation instead of actual SQL.
        // Treat this the same as DATA_NOT_AVAILABLE.
        if (sql != null && !sql.isBlank()) {
            String sqlUpper = sql.trim().toUpperCase();
            if (!sqlUpper.startsWith("SELECT") && !sqlUpper.startsWith("WITH")
                    && !sqlUpper.startsWith("INSERT") && !sqlUpper.startsWith("UPDATE")
                    && !sqlUpper.startsWith("DELETE")) {
                log.warn("LLM returned prose instead of SQL: {}", sql.substring(0, Math.min(sql.length(), 120)));
                String errMsg = "The selected database schema does not contain tables or columns "
                        + "matching your question. Please rephrase or check available tables.";
                long totalDuration = System.currentTimeMillis() - pipelineStartTime;
                transcriptService.appendErrorWithMetadata(request, databaseName, errMsg,
                        "LLM_PROSE_RESPONSE", retrievalDurationMs, llmDurationMs, 0, totalDuration);
                return SQLGenerationResponse.builder()
                        .naturalLanguageQuery(nlQuery)
                        .error(errMsg)
                        .generatedSQL(null)
                        .sqlValid(false)
                        .confidenceScore(0.0)
                        .confidenceLabel("LOW")
                        .validationErrors(List.of(errMsg))
                        .relevantTables(tables)
                        .relevantColumns(columns)
                        .retrievedChunks(chunkTexts)
                        .piiTokensFound(piiSanitizationService.summarize(piiTokens))
                        .databaseName(databaseName)
                        .explanation(sql)
                        .build();
            }
        }

        // ── Step 5: restore PII values back into the generated SQL ────────────
        // Replace every placeholder token with the original sensitive value so
        // the final SQL is executable (e.g. WHERE ssn = '__PII_SSN_1__' →  WHERE ssn = '123-45-6789')
        String restoredSql = piiSanitizationService.restore(sql, piiTokens);
        if (!restoredSql.equals(sql)) {
            log.info("PII values restored into generated SQL");
        }

        // ── Step 6: validate ──────────────────────────────────────────────────
        ValidationResult validation = validationService.validate(restoredSql, schema, nlQuery);
        List<String> allIssues = new ArrayList<>(validation.errors());
        allIssues.addAll(validation.warnings());

        // ── Step 6b: detect hallucination vs structural failure ─────────────
        boolean hasHallucinatedColumn = allIssues.stream()
                .anyMatch(err -> err.toLowerCase().contains("hallucinated column"));
        boolean hasHallucinatedTable = allIssues.stream()
                .anyMatch(err -> err.toLowerCase().contains("hallucinated") && err.toLowerCase().contains("table"));
        boolean isHallucination = hasHallucinatedColumn || hasHallucinatedTable;

        // ── Step 6c: non-hallucination validation failure — return error ────
        // If validation failed for structural reasons (not hallucination),
        // return the error immediately. Do NOT call explanation or confidence
        // services — they are only useful for valid SQL.
        if (!validation.valid() && !isHallucination) {
            log.warn("SQL validation failed (structural): {}", allIssues);
            String errMsg = "SQL validation failed. " + String.join("; ", allIssues);
            long totalDuration = System.currentTimeMillis() - pipelineStartTime;
            transcriptService.appendErrorWithMetadata(request, databaseName, errMsg,
                    "VALIDATION_FAILED", retrievalDurationMs, llmDurationMs, 0, totalDuration);
            return SQLGenerationResponse.builder()
                    .naturalLanguageQuery(nlQuery)
                    .generatedSQL(null)
                    .sqlValid(false)
                    .error(errMsg)
                    .validationErrors(allIssues)
                    .confidenceScore(0.0)
                    .confidenceLabel("LOW")
                    .relevantTables(tables)
                    .relevantColumns(columns)
                    .retrievedChunks(chunkTexts)
                    .piiTokensFound(piiSanitizationService.summarize(piiTokens))
                    .cacheHit(false)
                    .databaseName(databaseName)
                    .explanation("Explanation skipped — generated SQL did not pass validation.")
                    .build();
        }

        // ── Step 6d: hallucination retry — EAV-focused rebuild ─────────────
        if (!validation.valid() && isHallucination) {
            log.warn("SQL validation failed — hallucinated table/column detected.");

//            SQLGenerationResponse retryResponse = attemptEavRetry(
//                    sanitizedQuery, nlQuery, databaseName, schema,
//                    piiTokens, tables, columns, chunkTexts, request);
//
//            if (retryResponse != null) {
//                log.info("EAV retry succeeded — returning corrected SQL");
//                return retryResponse;
//            }
//
//            log.warn("EAV retry did not produce valid SQL — returning hallucination error");
            String userFriendlyError = buildHallucinationErrorMessage(nlQuery, tables, schema);
            SQLGenerationResponse errorResponse = SQLGenerationResponse.builder()
                    .naturalLanguageQuery(nlQuery)
                    .generatedSQL("")
                    .sqlValid(false)
                    .eavRetry(true)
                    .error(userFriendlyError)
                    .validationErrors(allIssues)
                    .confidenceScore(0.0)
                    .confidenceLabel("LOW")
                    .relevantTables(tables)
                    .relevantColumns(columns)
                    .retrievedChunks(chunkTexts)
                    .piiTokensFound(piiSanitizationService.summarize(piiTokens))
                    .cacheHit(false)
                    .databaseName(databaseName)
                    .explanation("The generated SQL references tables or columns that don't exist "
                            + "in the database schema. EAV retry also failed. " + userFriendlyError)
                    .build();
            errorResponse.setHallucinatedResponse(true);
            int errTranscriptId = transcriptService.append(request, errorResponse);
            errorResponse.setTranscriptId(errTranscriptId);
            return errorResponse;
        }

        // ── Step 7: confidence score ──────────────────────────────────────────
        // Only reached when validation passed — all failure paths returned above.
        ConfidenceResult conf = confidenceService.compute(
                scoredChunks, true, restoredSql, tables);

        // ── Step 8: plain-English explanation ────────────────────────────────
        String englishExplanation = explanationService.explain(restoredSql);

        log.info("◀ NL→SQL pipeline done | valid=true | confidence={} [{}] | piiTokens={}",
                String.format("%.3f", conf.score()), conf.label(),
                piiTokens.size());

        SQLGenerationResponse response = SQLGenerationResponse.builder()
                .naturalLanguageQuery(nlQuery)
                .generatedSQL(restoredSql)
                .sqlValid(true)
                .validationErrors(allIssues)
                .confidenceScore(conf.score())
                .confidenceLabel(conf.label())
                .confidenceDetails(conf.details())
                .englishExplanation(englishExplanation)
                .relevantTables(tables)
                .relevantColumns(columns)
                .retrievedChunks(chunkTexts)
                .piiTokensFound(piiSanitizationService.summarize(piiTokens))
                .cacheHit(false)
                .explanation(String.format(
                        "Used %d schema chunks (cosine similarity). Confidence: %s (%.0f%%).",
                        scoredChunks.size(),
                        conf.label(), conf.score() * 100))
                .build();

        // ── Step 9a: persist transcript ───────────────────────────────────────
        int transcriptId = transcriptService.append(request, response);
        response.setTranscriptId(transcriptId);

        // ── Step 9b: store in Redis semantic cache (only if SQL is valid and cache not bypassed) ─
        if (validation.valid() && cacheEnabled) {
            semanticCacheService.store(sanitizedQuery, response, databaseName);
        } else if (validation.valid() && !cacheEnabled) {
            log.info("⏭ Cache STORE skipped — client requested cacheEnabled=false");
        }

        return response;
    }

    // ── LLM call ──────────────────────────────────────────────────────────────

    private String callLLM(String nlQuery, String schemaContext, String dbName) {
        return callLLM(nlQuery, schemaContext, dbName, java.util.Collections.emptyList(), null);
    }

    private String callLLM(String nlQuery, String schemaContext, String dbName,
                           List<ScoredChunk> scoredChunks) {
        return callLLM(nlQuery, schemaContext, dbName, scoredChunks, null);
    }

    private String callLLM(String nlQuery, String schemaContext, String dbName,
                           List<ScoredChunk> scoredChunks, String databaseType) {
        if (chatClient == null) {
            log.warn("ChatClient not wired — no OPENAI_API_KEY set?");
            return null;
        }
        String prompt = buildPrompt(nlQuery, schemaContext, dbName, scoredChunks, databaseType);
        try {
            log.info("══════════════ PROMPT SENT TO LLM ══════════════\n{}\n══════════════ END OF PROMPT ══════════════", prompt);
            String response = chatClient.prompt(new Prompt(new UserMessage(prompt))).call().content();
            log.info("══════════════ LLM RESPONSE ══════════════\n{}\n══════════════ END OF LLM RESPONSE ══════════════", response);
            return response;
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Hallucination retry ───────────────────────────────────────────────────

    /**
     * Called when the first LLM response hallucinated a table or column name.
     *
     * Retry strategy — three passes to build the focused table set:
     *
     *  Pass A  EAV tables themselves  (isEavTable = true)
     *          e.g.  attributes, productattributevalues
     *
     *  Pass B  Entity tables referenced BY EAV table FKs  ← KEY FIX
     *          EAV table productattributevalues has FK productid → products.productid
     *          → products is the entity table and MUST be included
     *          Also reads the eav_entity_table metadata field on every EAV chunk.
     *
     *  Pass C  Tables whose own FK columns point TO any EAV table
     *          (reverse direction — bridge/join tables)
     *
     * All TABLE-level chunks for the union of A ∪ B ∪ C are sent to the LLM
     * with a correction preamble that tells it exactly what it did wrong.
     *
     * @return a fully-populated SQLGenerationResponse on success, or null
     */
//    private SQLGenerationResponse attemptEavRetry(
//            String sanitizedQuery,
//            String nlQuery,
//            String databaseName,
//            DatabaseSchema schema,
//            List<PiiToken> piiTokens,
//            List<String> originalTables,
//            List<String> originalColumns,
//            List<String> originalChunkTexts,
//            NaturalLanguageQueryRequest request) {
//
//        if (chatClient == null || schema == null) return null;
//
//        log.info("EAV retry: building EAV + entity focused prompt for query='{}'", nlQuery);
//
//        // ── Pass A: EAV tables (isEavTable = true) ────────────────────────────
//        Set<String> eavTableNames = new LinkedHashSet<>();
//        if (schema.getTables() != null) {
//            for (DatabaseTable t : schema.getTables()) {
//                if (t.isEavTable()) eavTableNames.add(t.getTableName().toLowerCase());
//            }
//        }
//
//        if (eavTableNames.isEmpty()) {
//            log.warn("EAV retry: no EAV tables in active schema — skipping retry");
//            return null;
//        }
//
//        Set<String> retryTableNames = new LinkedHashSet<>(eavTableNames);
//
//        // ── Pass B: Entity tables that EAV tables reference via FK ───────────
//        // e.g. productattributevalues.productid → products.productid
//        // Also read eav_entity_table from EAV chunk metadata (most direct signal)
//        if (schema.getTables() != null) {
//            for (DatabaseTable t : schema.getTables()) {
//                if (!t.isEavTable() || t.getColumns() == null) continue;
//                for (SchemaColumn col : t.getColumns()) {
//                    if (col.isForeignKey() && col.getForeignKeyReference() != null) {
//                        String ref = col.getForeignKeyReference().toLowerCase().trim();
//                        // ref is "tablename.columnname" or just "tablename"
//                        String refTable = ref.contains(".") ? ref.substring(0, ref.indexOf('.')) : ref;
//                        if (!refTable.isBlank() && !eavTableNames.contains(refTable)) {
//                            retryTableNames.add(refTable);
//                            log.info("EAV retry Pass B: entity table '{}' included " +
//                                            "(EAV table '{}' FK → '{}')",
//                                    refTable, t.getTableName(), col.getForeignKeyReference());
//                        }
//                    }
//                }
//            }
//        }
//
//        // Also harvest eav_entity_table from EAV TABLE-level chunk metadata
//        vectorStoreService.getAllChunks().stream()
//                .filter(c -> "TABLE".equals(c.getElementType())
//                        && c.getMetadata() != null
//                        && "true".equals(c.getMetadata().get("is_eav_table")))
//                .forEach(c -> {
//                    String entityTable = c.getMetadata().getOrDefault("eav_entity_table", "").toLowerCase().trim();
//                    if (!entityTable.isBlank() && !eavTableNames.contains(entityTable)) {
//                        retryTableNames.add(entityTable);
//                        log.info("EAV retry Pass B (metadata): entity table '{}' from eav_entity_table field", entityTable);
//                    }
//                });
//
//        // ── Pass C: Tables whose FK columns point TO any EAV table (bridges) ─
//        if (schema.getTables() != null) {
//            for (DatabaseTable t : schema.getTables()) {
//                if (t.getColumns() == null) continue;
//                for (SchemaColumn col : t.getColumns()) {
//                    if (col.isForeignKey() && col.getForeignKeyReference() != null) {
//                        String ref = col.getForeignKeyReference().toLowerCase().trim();
//                        String refTable = ref.contains(".") ? ref.substring(0, ref.indexOf('.')) : ref;
//                        if (eavTableNames.contains(refTable)) {
//                            retryTableNames.add(t.getTableName().toLowerCase());
//                        }
//                    }
//                }
//            }
//        }
//
//        log.info("EAV retry: final table set = {} (A=EAV, B=entity, C=bridge)", retryTableNames);
//
//        // ── Build focused ScoredChunk list (TABLE-level only) ─────────────────
//        List<ScoredChunk> retryChunks = vectorStoreService.getAllChunks().stream()
//                .filter(c -> c.getTableName() != null
//                        && retryTableNames.contains(c.getTableName().toLowerCase())
//                        && "TABLE".equals(c.getElementType()))
//                .map(c -> new ScoredChunk(c, 1.0))
//                .collect(Collectors.toList());
//
//        if (retryChunks.isEmpty()) {
//            log.warn("EAV retry: no TABLE-level chunks found for tables {} — skipping", retryTableNames);
//            return null;
//        }
//
//        // ── Build correction preamble ─────────────────────────────────────────
//        // Identify entity tables explicitly so the LLM knows what to SELECT from
//        Set<String> entityTableNames = new LinkedHashSet<>(retryTableNames);
//        entityTableNames.removeAll(eavTableNames);
//
//        // Use RETRY rules from BusinessRulesForPrompts.json
//        String correctionNote =
//                "\n⚠ CORRECTION REQUIRED:\n" +
//                        (!retryRulesText.isBlank() ? retryRulesText + "\n\n" :
//                                "Your previous SQL response hallucinated table or column names that do NOT exist " +
//                                        "in the database schema.\n\n") +
//                        "Tables available for this query:\n" +
//                        "  • Entity tables  (SELECT columns from these): " +
//                        String.join(", ", entityTableNames) + "\n" +
//                        "  • EAV tables     (filter via WHERE name/attribute_name = '<key>'): " +
//                        String.join(", ", eavTableNames) + "\n\n" +
//                        "EAV RULES (from SQL_EAV):\n" +
//                        (!eavRulesText.isBlank() ? eavRulesText + "\n" :
//                                "  1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.\n" +
//                                        "  2. Attribute names are stored as ROW VALUES in the attribute name column.\n" +
//                                        "  3. Attribute values are stored in the attribute value column — use CAST() for comparisons.\n" +
//                                        "  4. Join the entity table to the EAV data table on the entity ID column.\n" +
//                                        "  5. Join the EAV data table to the attributes table on the attribute ID column.\n") +
//                        "  Example for 'products with Color = White':\n" +
//                        "    SELECT p.* FROM products p\n" +
//                        "    JOIN productattributevalues pav ON pav.productid = p.productid\n" +
//                        "    JOIN attributes a ON a.attributeid = pav.attributeid\n" +
//                        "    WHERE a.name = 'Color' AND pav.value ILIKE 'White'\n\n";
//
//        // ── Call LLM with corrected context ───────────────────────────────────
//        String retrySchemaContext = correctionNote + buildSchemaContext(retryChunks);
//        log.info("EAV retry: calling LLM — {} chunks covering tables: {}", retryChunks.size(), retryTableNames);
//        String retryRaw = callLLM(sanitizedQuery, retrySchemaContext, databaseName, retryChunks);
//
//        if (retryRaw == null) {
//            log.warn("EAV retry: LLM returned null");
//            return null;
//        }
//
//        // ── Extract, restore PII, validate ────────────────────────────────────
//        String retrySql = extractSQL(retryRaw);
//        String restoredRetrySql = piiSanitizationService.restore(retrySql, piiTokens);
//
//        ValidationResult retryValidation = validationService.validate(restoredRetrySql, schema, nlQuery);
//        boolean retryHallucinated = retryValidation.errors().stream()
//                .anyMatch(e -> e.toLowerCase().contains("hallucinated"));
//
//        if (!retryValidation.valid() || retryHallucinated) {
//            log.warn("EAV retry: SQL still invalid after retry — errors: {}", retryValidation.errors());
//            return null;
//        }
//
//        log.info("EAV retry: valid SQL produced:\n{}", restoredRetrySql);
//
//        // ── Compute confidence, explanation, build response ───────────────────
//        List<String> retryTables = retryChunks.stream()
//                .map(sc -> sc.chunk().getTableName())
//                .filter(Objects::nonNull).distinct()
//                .collect(Collectors.toList());
//
//        ConfidenceResult conf = confidenceService.compute(
//                retryChunks, retryValidation.valid(), restoredRetrySql, retryTables);
//        String explanation = explanationService.explain(restoredRetrySql);
//
//        SQLGenerationResponse retryResponse = SQLGenerationResponse.builder()
//                .naturalLanguageQuery(nlQuery)
//                .generatedSQL(restoredRetrySql)
//                .sqlValid(true)
//                .eavRetry(true)
//                .validationErrors(retryValidation.warnings())
//                .confidenceScore(conf.score())
//                .confidenceLabel(conf.label())
//                .confidenceDetails(conf.details())
//                .englishExplanation(explanation)
//                .relevantTables(retryTables)
//                .relevantColumns(originalColumns)
//                .retrievedChunks(retryChunks.stream()
//                        .map(sc -> "[EAV-retry | " + sc.chunk().getChunkId() + "] " + sc.chunk().getText())
//                        .collect(Collectors.toList()))
//                .piiTokensFound(piiSanitizationService.summarize(piiTokens))
//                .cacheHit(false)
//                .databaseName(databaseName)
//                .explanation(String.format(
//                        "EAV-focused retry succeeded. Entity tables: %s. EAV tables: %s. " +
//                                "Confidence: %s (%.0f%%).",
//                        entityTableNames, eavTableNames, conf.label(), conf.score() * 100))
//                .build();
//        retryResponse.setHallucinatedResponse(false);
//
//        int retryTranscriptId = transcriptService.append(request, retryResponse);
//        retryResponse.setTranscriptId(retryTranscriptId);
//
//        return retryResponse;
//    }

    // ── prompt engineering ────────────────────────────────────────────────────

    /**
     * Final chunk selection — the single method that decides what goes to the LLM.
     *
     * Algorithm:
     *  1. Sort all candidate chunks by score (highest → lowest)
     *  2. Deduplicate by table name (keep only the highest-scored chunk per table)
     *  3. Take the top 5 unique-table chunks
     *  4. If ANY of the top 5 is an EAV table:
     *       → ensure all related EAV tables AND their FK-connected entity tables
     *         are included (may expand beyond 5)
     *  5. If NONE of the top 5 is an EAV table:
     *       → reduce to top 3 only (fewer chunks = less noise for non-EAV queries)
     *  6. Sort final list by score descending before returning
     */
    private List<ScoredChunk> selectFinalChunks(List<ScoredChunk> allCandidates,
                                                 DatabaseSchema schema) {

        if (allCandidates.isEmpty()) return allCandidates;

        // ── Step 1: sort by score descending ─────────────────────────────────
        List<ScoredChunk> sorted = new ArrayList<>(allCandidates);
        sorted.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());

        // ── Step 2: deduplicate by table name (keep highest score per table) ─
        Map<String, ScoredChunk> bestPerTable = new LinkedHashMap<>();
        for (ScoredChunk sc : sorted) {
            String tbl = sc.chunk().getTableName() != null
                    ? sc.chunk().getTableName().toLowerCase() : "__no_table__";
            bestPerTable.putIfAbsent(tbl, sc); // first occurrence = highest score
        }
        List<ScoredChunk> deduped = new ArrayList<>(bestPerTable.values());

        // ── Step 3: take top 5 unique-table chunks ───────────────────────────
        int initialCap = 5;
        List<ScoredChunk> top5 = deduped.size() > initialCap
                ? new ArrayList<>(deduped.subList(0, initialCap))
                : new ArrayList<>(deduped);

        // ── Step 4: check if any of the top 5 is an EAV table ───────────────
        boolean hasEav = top5.stream().anyMatch(sc ->
                "true".equals(sc.chunk().getMetadata().get("is_eav_table"))
                        || "EAV_ATTRIBUTE".equals(sc.chunk().getMetadata().get("element_type")));

        if (hasEav) {
            // ── EAV path: ensure ALL related EAV + entity tables are included ─
            log.info("EAV table detected in top-{} — expanding with related EAV + entity tables", top5.size());
            top5 = expandEavRelatedTables(top5, deduped, schema);
        } else {
            // ── Non-EAV path: reduce to top 3 only ──────────────────────────
            int reducedCap = 3;
            if (top5.size() > reducedCap) {
                top5 = new ArrayList<>(top5.subList(0, reducedCap));
            }
            log.info("No EAV table in top-{} — reduced to top-{} chunks", initialCap, top5.size());
        }

        // ── Step 5: sort final list by score descending ──────────────────────
        top5.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());

        log.info("Final chunk selection: {} candidates → {} deduped → {} sent to LLM (EAV={}). Tables: [{}]",
                allCandidates.size(), deduped.size(), top5.size(), hasEav,
                top5.stream()
                        .map(sc -> sc.chunk().getTableName() + "(" + String.format("%.3f", sc.score()) + ")")
                        .collect(Collectors.joining(", ")));

        return top5;
    }

    /**
     * Given the current top-5 chunks (which contain at least one EAV table),
     * ensures that ALL related EAV tables and their FK-connected entity tables
     * are included.
     *
     * For example, if top-5 contains "attributes" (EAV meta), this method
     * ensures "productattributevalues" (EAV data) and "products" (entity)
     * are also present — pulling them from the full deduped pool.
     */
    private List<ScoredChunk> expandEavRelatedTables(List<ScoredChunk> top5,
                                                      List<ScoredChunk> allDeduped,
                                                      DatabaseSchema schema) {

        // Tables already in top-5
        Set<String> presentTables = top5.stream()
                .map(sc -> sc.chunk().getTableName())
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Build lookup of all deduped chunks by table name
        Map<String, ScoredChunk> dedupedByTable = new LinkedHashMap<>();
        for (ScoredChunk sc : allDeduped) {
            String tbl = sc.chunk().getTableName();
            if (tbl != null) dedupedByTable.putIfAbsent(tbl.toLowerCase(), sc);
        }

        // Identify all EAV tables in top-5
        Set<String> eavTables = new LinkedHashSet<>();
        for (ScoredChunk sc : top5) {
            if ("true".equals(sc.chunk().getMetadata().get("is_eav_table"))
                    || "EAV_ATTRIBUTE".equals(sc.chunk().getMetadata().get("element_type"))) {
                String tbl = sc.chunk().getTableName();
                if (tbl != null) eavTables.add(tbl.toLowerCase());
            }
        }

        // Also find ALL EAV tables in the schema (not just ones in top-5)
        // because we may have the meta table (attributes) but not the data table
        // (productattributevalues), or vice versa
        Set<String> allSchemaEavTables = new LinkedHashSet<>();
        if (schema != null && schema.getTables() != null) {
            for (DatabaseTable t : schema.getTables()) {
                if (t.isEavTable() && t.getTableName() != null) {
                    allSchemaEavTables.add(t.getTableName().toLowerCase());
                }
            }
        }

        // Required tables = all EAV tables from schema + their FK-connected tables
        Set<String> required = new LinkedHashSet<>(eavTables);
        required.addAll(allSchemaEavTables); // include all EAV tables (meta + data)

        // Walk FK relationships of EAV tables to find entity tables
        if (schema != null && schema.getTables() != null) {
            for (DatabaseTable t : schema.getTables()) {
                if (!t.isEavTable() || t.getColumns() == null) continue;
                for (SchemaColumn col : t.getColumns()) {
                    if (col.isForeignKey() && col.getForeignKeyReference() != null) {
                        String ref = col.getForeignKeyReference().toLowerCase().trim();
                        String refTable = ref.contains(".")
                                ? ref.substring(0, ref.indexOf('.')) : ref;
                        if (!refTable.isBlank()) {
                            required.add(refTable);
                        }
                    }
                }
            }
        }

        // Also check EAV chunk metadata for eav_entity_table
        for (ScoredChunk sc : allDeduped) {
            Map<String, String> meta = sc.chunk().getMetadata();
            if ("true".equals(meta.get("is_eav_table"))) {
                String entityTable = meta.getOrDefault("eav_entity_table", "").toLowerCase().trim();
                if (!entityTable.isBlank()) required.add(entityTable);

                String metaTable = meta.getOrDefault("eav_attr_meta_table", "").toLowerCase().trim();
                if (!metaTable.isBlank()) required.add(metaTable);
            }
        }

        // Inject any required table that's missing from top-5
        List<ScoredChunk> expanded = new ArrayList<>(top5);
        for (String reqTable : required) {
            if (presentTables.contains(reqTable)) continue;

            // Try to find it in the deduped pool first
            ScoredChunk fromPool = dedupedByTable.get(reqTable);
            if (fromPool != null) {
                expanded.add(fromPool);
                presentTables.add(reqTable);
                log.info("EAV expansion: added '{}' [score={}] from candidate pool",
                        reqTable, String.format("%.3f", fromPool.score()));
            } else {
                // Fall back to vector store lookup
                List<SchemaChunk> tableChunks = vectorStoreService.getChunksByTable(reqTable);
                if (tableChunks.isEmpty()) {
                    tableChunks = vectorStoreService.getAllChunks().stream()
                            .filter(c -> reqTable.equalsIgnoreCase(c.getTableName()))
                            .collect(Collectors.toList());
                }
                if (!tableChunks.isEmpty()) {
                    SchemaChunk best = tableChunks.stream()
                            .filter(c -> "TABLE".equals(c.getElementType()))
                            .findFirst()
                            .orElse(tableChunks.get(0));
                    expanded.add(new ScoredChunk(best, 0.5));
                    presentTables.add(reqTable);
                    log.info("EAV expansion: injected '{}' from vector store (not in candidate pool)", reqTable);
                } else {
                    log.debug("EAV expansion: required table '{}' not found in any source", reqTable);
                }
            }
        }

        return expanded;
    }


    /**
     * Query-term table injection — Step 1a2.
     *
     * Scans the NL query for words that exactly match (or are the plural/singular
     * form of) a table name in the active schema.  If any such table is NOT
     * already in the chunk set, its best TABLE-level chunk is injected with
     * score = 0.4 (below real semantic hits but above FK-expansion 0.0).
     *
     * Example: "list all the products heavier than 1kg"
     *   → "products" matches table name "products"
     *   → products chunk injected if not already present
     */
    private List<ScoredChunk> injectTablesMatchingQueryTerms(
            String query, List<ScoredChunk> scoredChunks, DatabaseSchema schema) {

        if (schema == null || schema.getTables() == null) return scoredChunks;

        // Build lookup: all table names in the schema
        Map<String, DatabaseTable> tablesByName = new LinkedHashMap<>();
        for (DatabaseTable t : schema.getTables()) {
            if (t.getTableName() != null) {
                tablesByName.put(t.getTableName().toLowerCase(), t);
            }
        }

        // Tables already present in chunk set
        Set<String> presentTables = scoredChunks.stream()
                .map(sc -> sc.chunk().getTableName())
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        // Tokenize query into words
        String q = query.toLowerCase().replaceAll("[^a-z0-9_ ]", " ");
        String[] words = q.split("\\s+");

        Set<String> toInject = new LinkedHashSet<>();
        for (String word : words) {
            if (word.length() < 3) continue;
            // Exact match
            if (tablesByName.containsKey(word) && !presentTables.contains(word)) {
                toInject.add(word);
            }
            // Singular/plural: "products" → "product", "product" → "products"
            String singular = word.endsWith("s") ? word.substring(0, word.length() - 1) : null;
            String plural   = word + "s";
            if (singular != null && tablesByName.containsKey(singular) && !presentTables.contains(singular)) {
                toInject.add(singular);
            }
            if (tablesByName.containsKey(plural) && !presentTables.contains(plural)) {
                toInject.add(plural);
            }
        }

        if (toInject.isEmpty()) return scoredChunks;

        // Inject best TABLE-level chunk for each missing table
        List<ScoredChunk> expanded = new ArrayList<>(scoredChunks);
        for (String tblName : toInject) {
            List<SchemaChunk> chunks = vectorStoreService.getChunksByTable(tblName);
            if (chunks.isEmpty()) {
                // Try original casing from schema
                DatabaseTable dt = tablesByName.get(tblName);
                if (dt != null) {
                    chunks = vectorStoreService.getChunksByTable(dt.getTableName());
                }
            }
            if (chunks.isEmpty()) {
                chunks = vectorStoreService.getAllChunks().stream()
                        .filter(c -> tblName.equalsIgnoreCase(c.getTableName()))
                        .collect(Collectors.toList());
            }
            if (!chunks.isEmpty()) {
                SchemaChunk best = chunks.stream()
                        .filter(c -> "TABLE".equals(c.getElementType()))
                        .findFirst()
                        .orElse(chunks.get(0));
                expanded.add(new ScoredChunk(best, 0.4));
                log.info("Query-term injection: table '{}' mentioned in query but missing from search results — injected",
                        tblName);
            }
        }

        return expanded;
    }

    private String buildSchemaContext(List<ScoredChunk> scoredChunks) {
        // Step 1: Remove duplicate tables, keeping only the highest-scored chunk per table
        List<ScoredChunk> deduplicatedChunks = deduplicateChunksByTable(scoredChunks);

        StringBuilder sb = new StringBuilder();
        for (ScoredChunk sc : deduplicatedChunks) {
            SchemaChunk c = sc.chunk();
            Map<String, String> meta = c.getMetadata();

            String elementType = meta.getOrDefault("element_type", c.getElementType());

            sb.append("• ").append(c.getChunkId())
                    .append(" [score=").append(String.format("%.3f", sc.score())).append("]").append("\n");

            if ("EAV_ATTRIBUTE".equals(elementType)) {
                // ── EAV logical-attribute block ───────────────────────────────
                sb.append("  TABLE         : ").append(meta.getOrDefault("table_name", c.getTableName())).append("\n");
                sb.append("  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.\n");
                sb.append("  EAV ATTRIBUTE : ").append(meta.getOrDefault("eav_attribute_key", "")).append("\n");
                sb.append("  VALUE TYPE    : ").append(meta.getOrDefault("eav_data_type", "varchar")).append("\n");
                sb.append("  FILTER        : ").append(meta.getOrDefault("eav_filter_pattern", "")).append("\n");
                sb.append("  CAST HINT     : ").append(meta.getOrDefault("eav_cast_hint", meta.getOrDefault("eav_attr_value_column", "attribute_value"))).append("\n");
                String entityTable = meta.getOrDefault("eav_entity_table", "");
                String entityIdCol = meta.getOrDefault("eav_entity_id_column", "");
                if (!entityTable.isBlank()) {
                    sb.append("  JOIN HINT     : ").append(entityIdCol).append(" → ").append(entityTable).append("\n");
                }
                if (meta.containsKey("eav_synonyms") && !meta.get("eav_synonyms").isBlank()) {
                    sb.append("  SYNONYMS      : ").append(meta.get("eav_synonyms")).append("\n");
                }
                if (meta.containsKey("description") && !meta.get("description").isBlank()) {
                    sb.append("  DESC          : ").append(meta.get("description")).append("\n");
                }
                sb.append("  RULE          : NEVER use '").append(meta.getOrDefault("eav_attribute_key", "")).append("' as a column name.\n");
                sb.append("  TEXT          : ").append(c.getText()).append("\n\n");

            } else if ("TABLE".equals(elementType) && "true".equals(meta.get("is_eav_table"))) {
                // ── EAV table-level block ─────────────────────────────────────
                String tblName = meta.getOrDefault("table_name", c.getTableName());
                sb.append("  TABLE         : ").append(tblName).append("\n");
                sb.append("  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.\n");

                // Two-table EAV: attribute names live in a separate meta table
                String attrMetaTable    = meta.getOrDefault("eav_attr_meta_table", "");
                String attrMetaJoinCol  = meta.getOrDefault("eav_attr_meta_join_column", "");
                String attrMetaNameCol  = meta.getOrDefault("eav_attr_meta_name_column", "");
                boolean isTwoTableEav   = !attrMetaTable.isBlank() && !attrMetaJoinCol.isBlank();

                if (isTwoTableEav) {
                    // Attribute names are in a SEPARATE meta table — tell the LLM explicitly
                    sb.append("  ATTR NAME     : JOIN ").append(attrMetaTable)
                      .append(" ON ").append(tblName).append(".").append(attrMetaJoinCol)
                      .append(" = ").append(attrMetaTable).append(".").append(attrMetaJoinCol)
                      .append(" then use ").append(attrMetaTable).append(".")
                      .append(attrMetaNameCol.isBlank() ? "name" : attrMetaNameCol).append("\n");
                } else {
                    sb.append("  ATTR NAME COL : ").append(meta.getOrDefault("eav_attr_name_column", "attribute_name")).append("\n");
                }
                sb.append("  ATTR VALUE COL: ").append(meta.getOrDefault("eav_attr_value_column", "attribute_value")).append("\n");
                sb.append("  ENTITY ID COL : ").append(meta.getOrDefault("eav_entity_id_column", "")).append("\n");

                // Show column list so the LLM knows all available columns
                if (meta.containsKey("columns") && !meta.get("columns").isBlank())
                    sb.append("  COLUMNS : ").append(meta.get("columns")).append("\n");
                // Show FK relationships explicitly — critical for correct JOINs
                if (meta.containsKey("foreign_keys") && !meta.get("foreign_keys").isBlank())
                    sb.append("  FKs     : ").append(meta.get("foreign_keys")).append("\n");

                // Show entity table join hint
                String eavEntityTable = meta.getOrDefault("eav_entity_table", "");
                String eavEntityIdCol = meta.getOrDefault("eav_entity_id_column", "");
                if (!eavEntityTable.isBlank() && !eavEntityIdCol.isBlank()) {
                    sb.append("  JOIN TO ENTITY: ").append(eavEntityTable)
                      .append(" ON ").append(tblName).append(".").append(eavEntityIdCol)
                      .append(" = ").append(eavEntityTable).append(".").append(eavEntityIdCol).append("\n");
                }
                // Show attribute meta table join hint
                if (isTwoTableEav) {
                    sb.append("  JOIN TO ATTRS : ").append(attrMetaTable)
                      .append(" ON ").append(tblName).append(".").append(attrMetaJoinCol)
                      .append(" = ").append(attrMetaTable).append(".").append(attrMetaJoinCol).append("\n");
                }

                if (meta.containsKey("eav_known_attributes") && !meta.get("eav_known_attributes").isBlank()) {
                    sb.append("  KNOWN ATTRS   : ").append(meta.get("eav_known_attributes")).append("\n");
                }

                // RULE line: tell the LLM which column to filter on
                if (isTwoTableEav) {
                    sb.append("  RULE          : To filter by attribute name, JOIN to ")
                      .append(attrMetaTable).append(" and use WHERE ")
                      .append(attrMetaTable).append(".")
                      .append(attrMetaNameCol.isBlank() ? "name" : attrMetaNameCol)
                      .append(" = '<key>' — NEVER reference attribute names as column names.\n");
                } else {
                    sb.append("  RULE          : Use WHERE ").append(meta.getOrDefault("eav_attr_name_column", "attribute_name"))
                            .append(" = '<key>' — NEVER reference attribute names as column names.\n");
                }

                // Only include DESC if it's different from CONTEXT/business_context
                String descCtx = meta.getOrDefault("description", "").trim();
                String businessCtx = meta.getOrDefault("business_context", "").trim();
                if (!descCtx.isBlank() && !descCtx.equals(businessCtx)) {
                    sb.append("  DESC          : ").append(descCtx).append("\n");
                }

                sb.append("  TEXT          : ").append(c.getText()).append("\n\n");

            } else {
                // ── Normal TABLE / COLUMN block (existing logic) ──────────────
                sb.append("  TABLE   : ").append(meta.getOrDefault("table_name", c.getTableName())).append("\n");

                // ── Abbreviated schema: show descriptive name for context ────
                String descriptiveTable = meta.get("descriptive_table");
                if (descriptiveTable != null && !descriptiveTable.isBlank()) {
                    sb.append("  MEANING : ").append(descriptiveTable).append("\n");
                }

                if (meta.containsKey("column_name") && !meta.get("column_name").isBlank()) {
                    sb.append("  COLUMN  : ").append(meta.get("column_name")).append("\n");
                    String descriptiveCol = meta.get("descriptive_column");
                    if (descriptiveCol != null && !descriptiveCol.isBlank()) {
                        sb.append("  COL MEANING : ").append(descriptiveCol).append("\n");
                    }
                    sb.append("  TYPE    : ").append(meta.getOrDefault("data_type", "?")).append("\n");
                    if ("true".equals(meta.get("is_primary_key")))
                        sb.append("  ROLE    : PRIMARY KEY\n");
                    if ("true".equals(meta.get("is_foreign_key")))
                        sb.append("  FK REF  : ").append(meta.getOrDefault("foreign_key_reference", "")).append("\n");
                } else {
                    // table-level chunk: show column summary
                    if (meta.containsKey("columns") && !meta.get("columns").isBlank())
                        sb.append("  COLUMNS : ").append(meta.get("columns")).append("\n");
                    if (meta.containsKey("primary_keys") && !meta.get("primary_keys").isBlank())
                        sb.append("  PKs     : ").append(meta.get("primary_keys")).append("\n");
                    if (meta.containsKey("foreign_keys") && !meta.get("foreign_keys").isBlank())
                        sb.append("  FKs     : ").append(meta.get("foreign_keys")).append("\n");
                }

                // Only include DESC if it's different from CONTEXT/business_context
                String businessCtx = meta.getOrDefault("business_context", "").trim();
                String descCtx     = meta.getOrDefault("description", "").trim();
                String tableNameCtx = meta.getOrDefault("table_name", "").trim().toLowerCase();

                if (!businessCtx.isBlank()) {
                    sb.append("  CONTEXT : ").append(businessCtx).append("\n");
                } else if (!descCtx.isBlank() && isUserAuthoredDirective(descCtx, tableNameCtx)) {
                    // description contains user-authored guidance — surface it as CONTEXT
                    sb.append("  CONTEXT : ").append(descCtx).append("\n");
                }

                // Skip DESC if it matches CONTEXT
                if (!descCtx.isBlank() && !descCtx.equals(businessCtx)) {
                    sb.append("  DESC    : ").append(descCtx).append("\n");
                }

                // Raw semantic text for additional context
                sb.append("  TEXT    : ").append(c.getText()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * EAV injection pass — Step 1b (runs BEFORE FK expansion).
     *
     * Problem: queries like "all products heavier than 1kg" reference EAV
     * attribute VALUES, not schema column names.  The EAV table chunks
     * ("attributes", "productattributevalues") describe column names like
     * attributeid, name, value — NOT "weight" or "kg" — so their cosine
     * similarity is low and they fall outside topK.
     *
     * Fix: Two-stage check —
     *  Stage A  Query synonym map  — maps measurement/property words in the
     *           query to canonical EAV attribute name candidates:
     *           "heavier"/"heavy"/"weight"/"kg"/"gram" → "weight"
     *           "color"/"colour"/"red"/"blue"         → "color"
     *           "size"/"large"/"small"/"medium"       → "size"  etc.
     *
     *  Stage B  Index scan  — searches the actual EAV_ATTRIBUTE chunks in the
     *           vector store index for a name/description containing one of
     *           the candidate attribute names.  This avoids hardcoding the
     *           actual attribute values in the Java code.
     *
     * When a match is found, the best TABLE-level chunk for every EAV table
     * in the active schema is injected with score=0.5 (above FK-expansion 0.0
     * so it appears early in the prompt, below real hits so confidence is fair).
     */
    private List<ScoredChunk> expandWithEavTablesIfNeeded(String query,
                                                          List<ScoredChunk> scoredChunks,
                                                          DatabaseSchema schema) {
        if (schema == null || schema.getTables() == null) return scoredChunks;

        // ── Stage A: map query words → candidate EAV attribute names ─────────
        String q = query.toLowerCase();
        Set<String> candidateAttrNames = new LinkedHashSet<>();

        // Physical measurements
        if (q.matches(".*\\b(heav|weight|weigh|kg|gram|kilogram|lighter|light|mass).*"))
            candidateAttrNames.add("weight");
        if (q.matches(".*\\b(tall|height|high|cm|meter|metre|short|length|long|wide|width).*"))
            candidateAttrNames.addAll(List.of("height","length","width","size"));
        if (q.matches(".*\\b(color|colour|red|blue|green|black|white|yellow|orange|purple|pink|grey|gray|brown).*"))
            candidateAttrNames.add("color");
        if (q.matches(".*\\b(size|small|medium|large|xl|xxl|xs|dimension).*"))
            candidateAttrNames.add("size");
        if (q.matches(".*\\b(material|fabric|cotton|plastic|metal|steel|wood|leather|polyester).*"))
            candidateAttrNames.add("material");
        if (q.matches(".*\\b(brand|manufacturer|make|model|brand name).*"))
            candidateAttrNames.add("brand");
        if (q.matches(".*\\b(voltage|watt|ampere|power|capacity|battery|storage).*"))
            candidateAttrNames.addAll(List.of("voltage","capacity","power"));
        if (q.matches(".*\\b(age|warranty|guarantee|expire|expiry|shelf).*"))
            candidateAttrNames.addAll(List.of("warranty","age"));

        if (candidateAttrNames.isEmpty()) {
            return scoredChunks; // no measurement terms in query — skip EAV injection
        }

        // ── Stage B: scan indexed EAV_ATTRIBUTE / TABLE chunks for a name match
        boolean eavAttributeFound = vectorStoreService.getAllChunks().stream()
                .filter(c -> "EAV_ATTRIBUTE".equals(c.getElementType())
                        || ("true".equals(c.getMetadata().get("is_eav_table"))
                        && "TABLE".equals(c.getElementType())))
                .anyMatch(c -> {
                    String attrKey  = c.getMetadata().getOrDefault("eav_attribute_key", "").toLowerCase();
                    String descText = (c.getText() + " " + c.getMetadata().getOrDefault("description","")).toLowerCase();
                    return candidateAttrNames.stream()
                            .anyMatch(attr -> attrKey.contains(attr) || descText.contains(attr));
                });

        // Also accept: the attributes table chunk text itself mentions the candidate word
        // (e.g. attributes#0 text includes "Color, Size, Weight")
        if (!eavAttributeFound) {
            eavAttributeFound = vectorStoreService.getAllChunks().stream()
                    .filter(c -> "attributes".equalsIgnoreCase(c.getTableName())
                            && "TABLE".equals(c.getElementType()))
                    .anyMatch(c -> candidateAttrNames.stream()
                            .anyMatch(attr -> c.getText().toLowerCase().contains(attr)));
        }

        if (!eavAttributeFound) {
            log.debug("EAV injection: candidate attrs {} not found in EAV index — skipping", candidateAttrNames);
            return scoredChunks;
        }

        // ── Inject best TABLE chunk for every EAV-flagged table not yet present
        Set<String> presentTables = scoredChunks.stream()
                .map(sc -> sc.chunk().getTableName())
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        List<ScoredChunk> expanded = new ArrayList<>(scoredChunks);
        final Set<String> finalCandidateAttrNames = candidateAttrNames; // effectively final for lambda
        for (DatabaseTable t : schema.getTables()) {
            if (!t.isEavTable()) continue;
            String tblName = t.getTableName().toLowerCase();
            if (presentTables.contains(tblName)) continue;

            // Find the best TABLE-level chunk for this EAV table
            List<SchemaChunk> chunks = vectorStoreService.getChunksByTable(t.getTableName());
            Optional<SchemaChunk> best = chunks.stream()
                    .filter(c -> "TABLE".equals(c.getElementType()))
                    .findFirst();
            if (best.isEmpty()) best = chunks.stream().findFirst();

            best.ifPresent(chunk -> {
                // Score 0.5 — above FK-expansion (0.0) but below real semantic hits
                expanded.add(new ScoredChunk(chunk, 0.5));
                log.info("EAV-injection: force-injected EAV table '{}' for query terms {}",
                        t.getTableName(), finalCandidateAttrNames);
            });
        }

        return expanded;
    }

    /**
     * FK-expansion pass — Step 1c (runs AFTER EAV injection).
     * Walk FK columns in all retrieved/injected tables.  If a referenced table is NOT yet present
     * in the chunk list, find the best-scoring chunk for that table in the
     * vector-store index and append it with a synthetic score of 0.0 (so it
     * appears in the prompt but the confidence calculation is not inflated).
     *
     * Example:  query = "supplier name and phone for Dumbbell"
     *   Retrieved:  suppliers(0.62), productsuppliers(0.58)
     *   productsuppliers has FK → products  (not in top-k)
     *   → products best chunk injected automatically
     *
     * This is a single-hop expansion (direct FK references only).
     */
    private List<ScoredChunk> expandWithFkRelatedTables(List<ScoredChunk> scoredChunks,
                                                        DatabaseSchema schema) {
        if (schema == null || schema.getTables() == null || scoredChunks.isEmpty()) {
            return scoredChunks;
        }

        // Build a map of tableName → DatabaseTable for fast lookup
        Map<String, DatabaseTable> tableMap = new LinkedHashMap<>();
        for (DatabaseTable t : schema.getTables()) {
            if (t.getTableName() != null) {
                tableMap.put(t.getTableName().toLowerCase(), t);
            }
        }

        // Tables already present in the retrieved chunk set
        Set<String> presentTables = scoredChunks.stream()
                .map(sc -> sc.chunk().getTableName())
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // For each present table, walk its FK columns to find referenced tables
        Set<String> toAdd = new LinkedHashSet<>();
        for (String tbl : new ArrayList<>(presentTables)) {
            DatabaseTable dbTable = tableMap.get(tbl);
            if (dbTable == null || dbTable.getColumns() == null) continue;
            for (SchemaColumn col : dbTable.getColumns()) {
                if (col.isForeignKey() && col.getForeignKeyReference() != null) {
                    // foreignKeyReference is typically "referencedTable.referencedColumn"
                    String ref = col.getForeignKeyReference().toLowerCase();
                    String referencedTable = ref.contains(".") ? ref.substring(0, ref.indexOf('.')) : ref;
                    referencedTable = referencedTable.trim();
                    if (!referencedTable.isBlank() && !presentTables.contains(referencedTable)) {
                        toAdd.add(referencedTable);
                    }
                }
            }
        }

        if (toAdd.isEmpty()) return scoredChunks;

        // For each missing related table, find its best chunk in the vector store
        List<ScoredChunk> expanded = new ArrayList<>(scoredChunks);
        for (String missingTable : toAdd) {
            List<SchemaChunk> tableChunks = vectorStoreService.getChunksByTable(missingTable);
            if (tableChunks.isEmpty()) {
                // Try case-insensitive match across all chunks
                tableChunks = vectorStoreService.getAllChunks().stream()
                        .filter(c -> missingTable.equalsIgnoreCase(c.getTableName()))
                        .collect(Collectors.toList());
            }
            if (!tableChunks.isEmpty()) {
                // Prefer TABLE-level chunks; fall back to first available
                SchemaChunk best = tableChunks.stream()
                        .filter(c -> "TABLE".equals(c.getElementType()))
                        .findFirst()
                        .orElse(tableChunks.get(0));
                // Score 0.0 — present for context, not ranked
                expanded.add(new ScoredChunk(best, 0.0));
                log.info("FK-expansion: injected table '{}' (FK referenced by retrieved tables: {})",
                        missingTable, presentTables);
            } else {
                log.debug("FK-expansion: table '{}' referenced via FK but has no indexed chunks", missingTable);
            }
        }

        return expanded;
    }

    /**
     * Removes duplicate table chunks, keeping only the highest-scored chunk for each table.
     * For example: if both products#0 [0.294] and products#1 [0.255] are present,
     * only products#0 [0.294] is kept.
     */
    private List<ScoredChunk> deduplicateChunksByTable(List<ScoredChunk> scoredChunks) {
        if (scoredChunks == null || scoredChunks.isEmpty()) return scoredChunks;

        // Group chunks by table name, keeping the highest-scored one per table
        Map<String, ScoredChunk> tableChunks = new LinkedHashMap<>();

        for (ScoredChunk sc : scoredChunks) {
            String tableName = sc.chunk().getTableName();
            if (tableName == null) {
                // If no table name, always include the chunk
                String chunkId = sc.chunk().getChunkId();
                tableChunks.putIfAbsent(chunkId, sc);
            } else {
                // Keep only the highest-scored chunk for this table
                ScoredChunk existing = tableChunks.get(tableName);
                if (existing == null || sc.score() > existing.score()) {
                    tableChunks.put(tableName, sc);
                    log.debug("Dedup: Keeping {} [score={}] for table '{}'",
                            sc.chunk().getChunkId(), String.format("%.3f", sc.score()), tableName);
                } else {
                    log.debug("Dedup: Skipping {} [score={}] (lower than {} [score={}]) for table '{}'",
                            sc.chunk().getChunkId(), String.format("%.3f", sc.score()),
                            existing.chunk().getChunkId(), String.format("%.3f", existing.score()), tableName);
                }
            }
        }

        return new ArrayList<>(tableChunks.values());
    }

    /**
     * Returns true when a description string looks like a user-authored directive
     * rather than an auto-generated placeholder.
     *
     * Auto-generated placeholders produced by DatabaseSchemaExportService / SQL parser:
     *   "{tableName} table"              e.g. "Students table"
     *   "{columnName} ({dataType})"      e.g. "student_id (INTEGER)"
     *   "Table {tableName} from ..."     e.g. "Table Students from uploaded SQL file"
     *
     * A user-authored directive is anything else that is long enough to be
     * meaningful (more than ~20 characters) and doesn't match those patterns.
     */
    private boolean isUserAuthoredDirective(String description, String tableName) {
        if (description == null || description.isBlank()) return false;
        String lower = description.trim().toLowerCase();
        // Auto-generated pattern: "{tableName} table"
        if (lower.equals(tableName + " table")) return false;
        // Auto-generated pattern: "table {tableName} from ..."
        if (lower.startsWith("table " + tableName)) return false;
        // Auto-generated pattern: column placeholders "{name} ({type})"
        if (lower.matches("[a-z_0-9]+ \\([a-z0-9 ()]+\\)")) return false;
        // Must be at least 20 chars to be meaningful guidance
        return description.trim().length() >= 20;
    }

    /**
     * Returns true when at least one retrieved chunk belongs to an EAV table.
     * Used to decide whether to inject EAV-specific prompt rules.
     */
    private boolean hasEavChunks(List<ScoredChunk> scoredChunks) {
        return scoredChunks.stream().anyMatch(sc -> {
            Map<String, String> m = sc.chunk().getMetadata();
            return "EAV_ATTRIBUTE".equals(m.get("element_type"))
                    || "true".equals(m.get("is_eav_table"));
        });
    }

    /**
     * Collects EAV_ATTRIBUTE chunks from the results to build few-shot examples.
     */
    private List<Map<String, String>> collectEavAttributeMetas(List<ScoredChunk> scoredChunks) {
        List<Map<String, String>> result = new java.util.ArrayList<>();
        for (ScoredChunk sc : scoredChunks) {
            Map<String, String> m = sc.chunk().getMetadata();
            if ("EAV_ATTRIBUTE".equals(m.get("element_type"))) {
                result.add(m);
            }
        }
        return result;
    }

    private String buildPrompt(String nlQuery, String schemaContext, String dbName) {
        return buildPrompt(nlQuery, schemaContext, dbName, java.util.Collections.emptyList(), null);
    }

    private String buildPrompt(String nlQuery, String schemaContext, String dbName,
                               List<ScoredChunk> scoredChunks) {
        return buildPrompt(nlQuery, schemaContext, dbName, scoredChunks, null);
    }

    private String buildPrompt(String nlQuery, String schemaContext, String dbName,
                               List<ScoredChunk> scoredChunks, String databaseType) {
        boolean eavPresent = hasEavChunks(scoredChunks);
        List<Map<String, String>> eavAttrs = collectEavAttributeMetas(scoredChunks);

        String eavRules    = eavPresent ? buildEavRules(eavAttrs) : "";

        // ── Abbreviated schema instruction ───────────────────────────────────
        // If the schema uses abbreviated names, inject an explicit instruction
        // telling the LLM to use the original abbreviated names in the SQL.
        String abbreviationNotice = abbreviatedChunkTranslator.buildAbbreviationPromptInstruction(dbName);

        // ── Dynamically set the SQL dialect in Rule 2 ────────────────────────
        // Replace any occurrence of "compatible with PostgreSQL" or similar
        // with the actual database type from the request / schema.
        String resolvedRules = generalRulesText;
        if (databaseType != null && !databaseType.isBlank()) {
            String dialectLabel = resolveSqlDialectLabel(databaseType);
            // Replace Rule 2 dialect reference dynamically
            resolvedRules = resolvedRules.replaceAll(
                    "(?i)compatible with \\w+",
                    "compatible with " + dialectLabel);
        }

        return systemInstruction + "\n\n" +
                "RULES FOR SQL GENERATION:\n" + resolvedRules + "\n" +
                eavRules + "\n" +
                abbreviationNotice +
                "Database: " + dbName + "\n\n" +
                "--- RETRIEVED SCHEMA CHUNKS (ranked by semantic similarity) ---\n" +
                schemaContext + "\n" +
                "--- END OF SCHEMA CHUNKS ---\n" +
                "Natural-language question: " + nlQuery + "\n\n" +
                "SQL:\n";
    }

    /**
     * Maps the raw databaseType string to a proper SQL dialect label for the prompt.
     */
    private String resolveSqlDialectLabel(String databaseType) {
        if (databaseType == null) return "PostgreSQL";
        String lower = databaseType.toLowerCase().trim();
        if (lower.contains("mysql") || lower.contains("maria")) return "MySQL";
        if (lower.contains("postgres"))                          return "PostgreSQL";
        if (lower.contains("oracle"))                            return "Oracle";
        if (lower.contains("sqlserver") || lower.contains("mssql") || lower.contains("microsoft")) return "SQL Server";
        if (lower.contains("sqlite"))                            return "SQLite";
        return databaseType; // pass-through if unrecognized
    }


    /**
     * Builds EAV-specific SQL generation rules injected into the prompt
     * when EAV chunks are among the retrieved results.
     * Header and numbered rules are read from BusinessRulesForPrompts.json at startup.
     */
    private String buildEavRules(List<Map<String, String>> eavAttrs) {
        StringBuilder sb = new StringBuilder();
        //sb.append("\n").append(eavRulesHeader).append("\n");
        sb.append(eavRulesText).append("\n");

        // Append the dynamic EAV attribute list retrieved for this specific query
        if (!eavAttrs.isEmpty()) {
            // Find the next rule number after the last static EAV rule
            int nextNum = eavAttrs.isEmpty() ? 15 :
                    eavRulesText.lines()
                            .map(String::trim)
                            .filter(l -> l.matches("\\d+\\..*"))
                            .mapToInt(l -> Integer.parseInt(l.split("\\.")[0]))
                            .max().orElse(14) + 1;

            sb.append(nextNum).append(". EAV attributes retrieved for this query:\n");
            for (Map<String, String> m : eavAttrs) {
                sb.append("    - '").append(m.getOrDefault("eav_attribute_key", "?")).append("'")
                        .append(" in table '").append(m.getOrDefault("table_name", "?")).append("'")
                        .append(" → ").append(m.getOrDefault("eav_filter_pattern", ""))
                        .append(", read via ").append(m.getOrDefault("eav_cast_hint", "attribute_value"))
                        .append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Builds few-shot EAV query examples dynamically from the retrieved EAV attribute metadata.
ed against th      * Handles both single-table EAV (attribute_name column in same table) and
     * two-table EAV (attribute names in a separate meta table joined via FK).
     */
//    private String buildEavExamples(List<Map<String, String>> eavAttrs) {
//        if (eavAttrs.isEmpty()) return "";
//
//        StringBuilder sb = new StringBuilder();
//        sb.append("\n--- EAV QUERY PATTERNS (use these patterns for EAV tables) ---\n");
//
//        Map<String, String> first = eavAttrs.get(0);
//        String table     = first.getOrDefault("table_name", "eav_table");
//        String valueCol  = first.getOrDefault("eav_attr_value_column", "attribute_value");
//        String attrKey   = first.getOrDefault("eav_attribute_key", "attribute_key");
//        String castHint  = first.getOrDefault("eav_cast_hint", valueCol);
//        String entityId  = first.getOrDefault("eav_entity_id_column", "entity_id");
//        String entityTbl = first.getOrDefault("eav_entity_table", "");
//
//        // Detect two-table EAV pattern
//        String attrMetaTable   = first.getOrDefault("eav_attr_meta_table", "");
//        String attrMetaJoinCol = first.getOrDefault("eav_attr_meta_join_column", "");
//        String attrMetaNameCol = first.getOrDefault("eav_attr_meta_name_column", "name");
//        boolean isTwoTableEav  = !attrMetaTable.isBlank() && !attrMetaJoinCol.isBlank();
//
//        if (isTwoTableEav && !entityTbl.isBlank()) {
//            // Two-table EAV example: entity → EAV data → attributes meta
//            sb.append("Example — Query with entity + EAV data + attributes meta table:\n");
//            sb.append("  SELECT e.*, pav.").append(valueCol).append("\n");
//            sb.append("  FROM ").append(entityTbl).append(" e\n");
//            sb.append("  JOIN ").append(table).append(" pav ON pav.").append(entityId)
//              .append(" = e.").append(entityId).append("\n");
//            sb.append("  JOIN ").append(attrMetaTable).append(" a ON a.").append(attrMetaJoinCol)
//              .append(" = pav.").append(attrMetaJoinCol).append("\n");
//            sb.append("  WHERE a.").append(attrMetaNameCol).append(" = '").append(attrKey)
//              .append("' AND CAST(pav.").append(valueCol).append(" AS numeric) > <value>;\n\n");
//        } else {
//            // Single-table EAV example (original logic)
//            String attrCol = first.getOrDefault("eav_attr_name_column", "attribute_name");
//            sb.append("Example 1 — Single attribute:\n");
//            sb.append("  SELECT ").append(castHint).append(" AS ").append(attrKey).append("\n");
//            sb.append("  FROM ").append(table).append("\n");
//            sb.append("  WHERE ").append(attrCol).append(" = '").append(attrKey).append("'");
//            if (!entityTbl.isBlank()) {
//                sb.append(" AND ").append(entityId).append(" = <id>");
//            }
//            sb.append(";\n\n");
//
//            // Example 2: multi-attribute pivot (only if there are 2+ EAV attrs)
//            if (eavAttrs.size() >= 2) {
//                Map<String, String> second = eavAttrs.get(1);
//                String attrKey2  = second.getOrDefault("eav_attribute_key", "attr2");
//                String castHint2 = second.getOrDefault("eav_cast_hint", valueCol);
//
//                sb.append("Example 2 — Multiple attributes as columns (conditional aggregation):\n");
//                if (!entityTbl.isBlank()) {
//                    sb.append("  SELECT e.id,\n");
//                    sb.append("         MAX(CASE WHEN ea.").append(attrCol)
//                            .append(" = '").append(attrKey).append("' THEN ").append(castHint).append(" END) AS ").append(attrKey).append(",\n");
//                    sb.append("         MAX(CASE WHEN ea.").append(attrCol)
//                            .append(" = '").append(attrKey2).append("' THEN ").append(castHint2).append(" END) AS ").append(attrKey2).append("\n");
//                    sb.append("  FROM ").append(entityTbl).append(" e\n");
//                    sb.append("  JOIN ").append(table).append(" ea ON ea.").append(entityId).append(" = e.id\n");
//                    sb.append("  WHERE ea.").append(attrCol)
//                            .append(" IN ('").append(attrKey).append("', '").append(attrKey2).append("')\n");
//                    sb.append("  GROUP BY e.id;\n");
//                } else {
//                    sb.append("  SELECT ").append(entityId).append(",\n");
//                    sb.append("         MAX(CASE WHEN ").append(attrCol)
//                            .append(" = '").append(attrKey).append("' THEN ").append(castHint).append(" END) AS ").append(attrKey).append(",\n");
//                    sb.append("         MAX(CASE WHEN ").append(attrCol)
//                            .append(" = '").append(attrKey2).append("' THEN ").append(castHint2).append(" END) AS ").append(attrKey2).append("\n");
//                    sb.append("  FROM ").append(table).append("\n");
//                    sb.append("  GROUP BY ").append(entityId).append(";\n");
//                }
//            }
//        }
//
//        sb.append("--- END EAV PATTERNS ---\n");
//        return sb.toString();
//    }

    // ── SQL extraction ────────────────────────────────────────────────────────

    private String extractSQL(String response) {
        // try markdown code fence first
        Matcher cb = CODE_BLOCK.matcher(response);
        if (cb.find()) return cb.group(1).trim();

        // try to find first SELECT/WITH/INSERT/UPDATE/DELETE
        Matcher sm = LEADING_SQL.matcher(response);
        if (sm.find()) {
            String raw = response.substring(sm.start()).trim();
            int semi = raw.indexOf(';');
            if (semi >= 0) return raw.substring(0, semi + 1).trim();
            return raw.trim();
        }
        return response.trim();
    }


    // ── error helper ─────────────────────────────────────────────────────────

    private SQLGenerationResponse error(String query, String msg) {
        log.warn("Pipeline error: {}", msg);
        return SQLGenerationResponse.builder()
                .naturalLanguageQuery(query)
                .error(msg)
                .sqlValid(false)
                .confidenceScore(0.0)
                .confidenceLabel("LOW")
                .build();
    }

    /**
     * Builds a user-friendly error message when hallucinated columns are detected.
     * Directs user to explore the database to understand available data.
     */
    private String buildHallucinationErrorMessage(String nlQuery, List<String> relevantTables, DatabaseSchema schema) {
        StringBuilder sb = new StringBuilder();
        // Add database name if available from schema
        String dbName = null;
        if (schema != null && schema.getDatabaseName() != null && !schema.getDatabaseName().isBlank()) {
            dbName = schema.getDatabaseName();
        }
        sb.append("Its on us, may be we did not understand your request properly Or May be the generated SQL Hallucinated\n");
        sb.append("Please explore the selected database "); sb.append(dbName);
        sb.append(" Database and rephrase your question.\n");
        return sb.toString();
    }

    /**
     * Returns a structured error response specifically for Tier-1 + Tier-2 miss.
     * Sets errorCode = "SCHEMA_MISMATCH" so the UI can show a targeted message.
     */
    private SQLGenerationResponse tierMissError(String query) {
        String msg =
                "Your prompt does not match any table or column in the indexed schema. " +
                        "Please refine your prompt and try again.\n\n" +
                        "Tips:\n" +
                        "• Use table or column names from your schema " +
                        "(e.g. 'employees', 'salary', 'department')\n" +
                        "• Avoid very generic terms like 'data', 'info', 'records'\n" +
                        "• If you just uploaded a schema, make sure the RAG pipeline was re-indexed";

        log.warn("Tier-1 + Tier-2 MISS for query='{}' → returning SCHEMA_MISMATCH error", query);

        return SQLGenerationResponse.builder()
                .naturalLanguageQuery(query)
                .error(msg)
                .errorCode("SCHEMA_MISMATCH")
                .sqlValid(false)
                .confidenceScore(0.0)
                .confidenceLabel("LOW")
                .build();
    }
}
