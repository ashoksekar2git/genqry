package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nlp.rag.seek.model.NaturalLanguageQueryRequest;
import com.nlp.rag.seek.model.SQLGenerationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Appends every NL→SQL interaction (user prompt + LLM response) to a
 * daily transcript file:
 *
 *   supportingFiles/transcripts_MM_dd_yy.json
 *
 * File structure:
 * [
 *   {
 *     "timestamp":       "2026-03-07T14:23:01",
 *     "userPrompt":      "List all students in grade 10",
 *     "generatedSQL":    "SELECT * FROM Students WHERE grade = 10;",
 *     "sqlValid":        true,
 *     "confidenceLabel": "HIGH",
 *     "confidenceScore": 0.92,
 *     "validationErrors": [],
 *     "explanation":     "...",
 *     "error":           null
 *   },
 *   ...
 * ]
 *
 * If the file already exists for today, the new entry is appended to the
 * existing JSON array.  A new file is created automatically each day.
 */
@Service
public class TranscriptService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptService.class);

    private static final DateTimeFormatter FILE_DATE_FMT  = DateTimeFormatter.ofPattern("MM_dd_yy");
    private static final DateTimeFormatter TS_FMT         = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String            FILE_PREFIX    = "transcripts_";
    private static final String            FILE_SUFFIX    = ".json";

    @Value("${seek.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    @Autowired(required = false)
    private UserDbService userDbService;

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // =========================================================================
    // Write
    // =========================================================================

    /**
     * Appends one transcript entry to today's file using the full request and
     * response objects so every detail the LLM saw and produced is captured.
     *
     * Entry shape:
     * {
     *   "timestamp": "2026-03-08T10:23:01",
     *   "request": {
     *     "userPrompt":        "show all department names",
     *     "databaseName":      "ecommerce",
     *     "topK":              5,
     *     "source":            "UI",
     *     "showSampleResults": false
     *   },
     *   "response": {
     *     "generatedSQL":      "SELECT department_name FROM Departments;",
     *     "sqlValid":          true,
     *     "confidenceLabel":   "HIGH",
     *     "confidenceScore":   0.92,
     *     "confidenceDetails": "...",
     *     "englishExplanation":"...",
     *     "validationErrors":  [],
     *     "relevantTables":    ["Departments"],
     *     "relevantColumns":   ["department_name"],
     *     "piiTokensFound":    [],
     *     "cacheHit":          false,
     *     "error":             ""
     *   }
     * }
     *
     * @param request   the original NL query request from the UI
     * @param response  the fully-populated pipeline response
     * @return          the transcript_details DB row ID (-1 if not persisted)
     */
    public int append(NaturalLanguageQueryRequest request, SQLGenerationResponse response) {
        String reqUserName = request != null ? request.getUserName() : null;
        Path filePath = resolveFilePath(reqUserName);
        int dbTranscriptId = -1;
        try {
            ArrayNode transcripts;
            if (Files.exists(filePath)) {
                transcripts = (ArrayNode) objectMapper.readTree(filePath.toFile());
            } else {
                transcripts = objectMapper.createArrayNode();
                Files.createDirectories(filePath.getParent());
            }

            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("timestamp", LocalDateTime.now().format(TS_FMT));

            // ── request block ─────────────────────────────────────────────────
            ObjectNode req = entry.putObject("request");
            req.put("userPrompt",        request != null && request.getQuery() != null
                    ? request.getQuery() : "");
            req.put("databaseName",      request != null && request.getDatabaseName() != null
                    ? request.getDatabaseName() : "");
            req.put("topK",              request != null ? request.getTopK() : 0);
            req.put("source",            request != null && request.getSource() != null
                    ? request.getSource() : "");
            req.put("showSampleResults", request != null && request.isShowSampleResults());

            // ── response block ────────────────────────────────────────────────
            ObjectNode resp = entry.putObject("response");
            resp.put("generatedSQL",       nvl(response.getGeneratedSQL()));
            resp.put("sqlValid",           response.isSqlValid());
            resp.put("confidenceLabel",    nvl(response.getConfidenceLabel()));
            resp.put("confidenceScore",    response.getConfidenceScore());
            resp.put("confidenceDetails",  nvl(response.getConfidenceDetails()));
            resp.put("englishExplanation", nvl(response.getEnglishExplanation()));

            ArrayNode validationErrors = resp.putArray("validationErrors");
            if (response.getValidationErrors() != null) {
                response.getValidationErrors().forEach(validationErrors::add);
            }

            ArrayNode relevantTables = resp.putArray("relevantTables");
            if (response.getRelevantTables() != null) {
                response.getRelevantTables().forEach(relevantTables::add);
            }

            ArrayNode relevantColumns = resp.putArray("relevantColumns");
            if (response.getRelevantColumns() != null) {
                response.getRelevantColumns().forEach(relevantColumns::add);
            }

            ArrayNode piiTokens = resp.putArray("piiTokensFound");
            if (response.getPiiTokensFound() != null) {
                response.getPiiTokensFound().forEach(piiTokens::add);
            }

            resp.put("cacheHit",           response.isCacheHit());
            resp.put("error",              nvl(response.getError()));
            resp.put("status",             response.isSqlValid() ? "SUCCESS" : "VALIDATION_ERROR");

            transcripts.add(entry);
            objectMapper.writeValue(filePath.toFile(), transcripts);
            log.info("Transcript appended → {} ({} entries total)", filePath, transcripts.size());

            // Also persist to DB transcript_details table with enhanced tracking
            if (userDbService != null) {
                try {
                    String userPrompt = request != null ? request.getQuery() : "";
                    String dbName     = request != null ? request.getDatabaseName() : null;

                    // Look up user ID from DB users table using userName from the request
                    Integer resolvedUserId = null;
                    if (reqUserName != null && !reqUserName.isBlank()) {
                        Map<String, Object> userRow = userDbService.findUserByUsername(reqUserName);
                        if (userRow != null && userRow.get("id") != null) {
                            resolvedUserId = ((Number) userRow.get("id")).intValue();
                        }
                    }
                    log.info("request userName='{}' resolved to userId={}", reqUserName, resolvedUserId);
                    String status  = response.isSqlValid() ? "SUCCESS" : "FAILURE";

                    // Build remarks: failure cause + EAV retry flag
                    String remarks;
                    if (response.isEavRetry()) {
                        // Retry succeeded — record that it required an EAV-focused retry
                        remarks = "EAV_RETRY: First LLM call hallucinated table/column names. "
                                + "EAV-focused retry succeeded and produced valid SQL.";
                    } else if (!response.isSqlValid()) {
                        remarks = response.isHallucinatedResponse() ? "HALLUCINATION" : response.getError();
                    } else {
                        remarks = null;
                    }

                    dbTranscriptId = userDbService.saveTranscriptEnhanced(
                            resolvedUserId,
                            userPrompt,
                            response.getGeneratedSQL(),
                            response.getEnglishExplanation(),
                            status,
                            remarks,
                            response.getConfidenceScore(),
                            response.isSqlValid() ? null : "VALIDATION_ERROR",
                            dbName);
                    log.info("Transcript persisted to DB with id={}", dbTranscriptId);

                    // Sync user's transcripts.json after DB write
                    if (reqUserName != null && !reqUserName.isBlank()) {
                        userDbService.exportUserTranscriptsToJson(reqUserName);
                    }
                } catch (Exception dbEx) {
                    log.debug("transcript_details DB write skipped: {}", dbEx.getMessage());
                }
            }

        } catch (IOException e) {
            log.error("Failed to write transcript to {}: {}", filePath, e.getMessage(), e);
        }
        return dbTranscriptId;
    }

    /**
     * Lightweight overload for error-path calls (Tier miss, LLM unavailable)
     * where no full response object exists yet.
     */
    public void appendError(NaturalLanguageQueryRequest request, String errorMessage) {
        appendErrorWithMetadata(request, null, errorMessage, null, 0, 0, 0, 0);
    }

    /**
     * Enhanced error logging with performance metrics and error code tracking.
     * Used to record failures in the database with detailed diagnostic information.
     */
    public void appendErrorWithMetadata(NaturalLanguageQueryRequest request,
                                        String databaseName,
                                        String errorMessage,
                                        String errorCode,
                                        long retrievalDurationMs,
                                        long llmDurationMs,
                                        long validationDurationMs,
                                        long totalDurationMs) {
        String reqUserName = request != null ? request.getUserName() : null;
        Path filePath = resolveFilePath(reqUserName);
        try {
            ArrayNode transcripts;
            if (Files.exists(filePath)) {
                transcripts = (ArrayNode) objectMapper.readTree(filePath.toFile());
            } else {
                transcripts = objectMapper.createArrayNode();
                Files.createDirectories(filePath.getParent());
            }

            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("timestamp", LocalDateTime.now().format(TS_FMT));

            ObjectNode req = entry.putObject("request");
            req.put("userPrompt",        request != null && request.getQuery() != null
                    ? request.getQuery() : "");
            req.put("databaseName",      request != null && request.getDatabaseName() != null
                    ? request.getDatabaseName() : "");
            req.put("topK",              request != null ? request.getTopK() : 0);
            req.put("source",            request != null && request.getSource() != null
                    ? request.getSource() : "");
            req.put("showSampleResults", request != null && request.isShowSampleResults());

            ObjectNode resp = entry.putObject("response");
            resp.put("generatedSQL",       "");
            resp.put("sqlValid",           false);
            resp.put("confidenceLabel",    "LOW");
            resp.put("confidenceScore",    0.0);
            resp.put("confidenceDetails",  "");
            resp.put("englishExplanation", "");
            resp.putArray("validationErrors");
            resp.putArray("relevantTables");
            resp.putArray("relevantColumns");
            resp.putArray("piiTokensFound");
            resp.put("cacheHit",           false);
            resp.put("error",              errorMessage != null ? errorMessage : "");
            resp.put("status",             "FAILURE");
            resp.put("errorCode",          errorCode != null ? errorCode : "UNKNOWN");

            transcripts.add(entry);
            objectMapper.writeValue(filePath.toFile(), transcripts);

            log.info("Error transcript appended → {}", filePath);

            // Persist to database with error details
            if (userDbService != null) {
                try {
                    String userPrompt = request != null ? request.getQuery() : "";
                    String dbName = databaseName != null ? databaseName :
                                   (request != null ? request.getDatabaseName() : null);

                    // Look up user ID from DB users table using userName from the request
                    Integer resolvedUserId = null;
                    if (reqUserName != null && !reqUserName.isBlank()) {
                        Map<String, Object> userRow = userDbService.findUserByUsername(reqUserName);
                        if (userRow != null && userRow.get("id") != null) {
                            resolvedUserId = ((Number) userRow.get("id")).intValue();
                        }
                    }

                    userDbService.saveTranscriptEnhanced(
                            resolvedUserId,
                            userPrompt, "", "",
                            "FAILURE", errorMessage,   // errorMessage stored as remarks
                            0.0, errorCode, dbName);

                    // Sync user's transcripts.json after DB write
                    if (reqUserName != null && !reqUserName.isBlank()) {
                        userDbService.exportUserTranscriptsToJson(reqUserName);
                    }
                } catch (Exception dbEx) {
                    log.debug("transcript_details DB write skipped for error: {}", dbEx.getMessage());
                }
            }

        } catch (IOException e) {
            log.error("Failed to write error transcript to {}: {}", filePath, e.getMessage(), e);
        }
    }

    private String nvl(String s) { return s != null ? s : ""; }

    // =========================================================================
    // Read — query history
    // =========================================================================

    /**
     * Reads all transcript entries from today's file, using this resolution order:
     *
     *  1. supportingFiles directory (application default)
     *  2. Classpath supportingFiles (dev-mode fallback)
     *
     * Returns an empty list when no transcript file is found — never throws.
     *
     * @param date          the date to read (null = today)
     */
    public List<Map<String, Object>> readQueryHistory(LocalDate date) {

        // ── 1. Try supportingFiles directory ──────────────────────────────────
        Path supportDir = Paths.get(supportingFilesDir).toAbsolutePath();
        List<Path> found = findTranscriptFiles(supportDir);
        if (!found.isEmpty()) {
            log.info("Reading query history from supportingFiles: {} ({} file(s))", supportDir, found.size());
            return mergeTranscriptFiles(found);
        }

        // ── 3. Try classpath (dev mode) ───────────────────────────────────────
        try {
            java.net.URL url = new ClassPathResource("supportingFiles").getURL();
            Path classpathDir = Paths.get(url.toURI());
            List<Path> classpathFiles = findTranscriptFiles(classpathDir);
            if (!classpathFiles.isEmpty()) {
                log.info("Reading query history from classpath supportingFiles ({} file(s))", classpathFiles.size());
                return mergeTranscriptFiles(classpathFiles);
            }
        } catch (Exception ignored) {}

        log.info("No transcript files found in any location — returning empty history");
        return Collections.emptyList();
    }

    /**
     * Returns a list of all dates (MM_dd_yy) for which transcript files exist
     * in the supportingFiles directory.
     */
    public List<String> listAvailableDates() {
        Set<String> dates = new LinkedHashSet<>();


        // Collect from supportingFiles
        collectDatesFromDir(Paths.get(supportingFilesDir).toAbsolutePath(), dates);

        List<String> sorted = new ArrayList<>(dates);
        Collections.sort(sorted, Collections.reverseOrder()); // most recent first
        return sorted;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns the resolved path for today's transcript file (for writing).
     *  If userName is provided, writes to supportingFiles/{userName}/ directory. */
    Path resolveFilePath(String userName) {
        String fileName = FILE_PREFIX + LocalDate.now().format(FILE_DATE_FMT) + FILE_SUFFIX;

        // If userName is available, write to user's directory
        if (userName != null && !userName.isBlank()) {
            String safe = UsernameUtil.sanitize(userName);
            Path userDir = Paths.get(supportingFilesDir).toAbsolutePath().resolve(safe);
            try { Files.createDirectories(userDir); } catch (IOException ignored) {}
            return userDir.resolve(fileName);
        }

        // Fallback: root supportingFiles directory
        return Paths.get(supportingFilesDir).toAbsolutePath().resolve(fileName);
    }

    /** Legacy overload — writes to root supportingFiles/ directory. */
    Path resolveFilePath() {
        return resolveFilePath(null);
    }

    /** Parses a transcript JSON file into a list of entry maps. Returns empty list on error. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTranscriptFile(Path filePath) {
        try {
            JsonNode root = objectMapper.readTree(filePath.toFile());
            if (!root.isArray()) {
                log.warn("Transcript file is not a JSON array: {}", filePath);
                return Collections.emptyList();
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            for (JsonNode node : root) {
                entries.add(objectMapper.convertValue(node, Map.class));
            }
            log.debug("Loaded {} transcript entries from {}", entries.size(), filePath.getFileName());
            return entries;
        } catch (IOException e) {
            log.error("Failed to parse transcript file '{}': {}", filePath, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Finds all files in {@code dir} whose name starts with "transcripts" and
     * ends with ".json", sorted by last-modified time descending (newest first).
     */
    private List<Path> findTranscriptFiles(Path dir) {
        if (!Files.exists(dir)) return Collections.emptyList();
        try {
            List<Path> files = new ArrayList<>();
            Files.list(dir).filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.startsWith(FILE_PREFIX.toLowerCase()) && name.endsWith(FILE_SUFFIX);
            }).forEach(files::add);

            // Sort newest-modified first so most recent queries appear at the top after merge
            files.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) {
                    return 0;
                }
            });
            return files;
        } catch (IOException e) {
            log.warn("Could not list transcript files in '{}': {}", dir, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Reads all entries from every file in the list and returns them merged into
     * a single list, sorted by timestamp descending (most recent first).
     */
    private List<Map<String, Object>> mergeTranscriptFiles(List<Path> files) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Path f : files) {
            all.addAll(parseTranscriptFile(f));
        }
        // Sort by timestamp descending so UI sees most recent queries first
        all.sort((a, b) -> {
            String ta = (String) a.getOrDefault("timestamp", "");
            String tb = (String) b.getOrDefault("timestamp", "");
            return tb.compareTo(ta);
        });
        return all;
    }

    /** Scans a directory for transcript files and adds their date strings to the set. */
    private void collectDatesFromDir(Path dir, Set<String> dates) {
        if (!Files.exists(dir)) return;
        try {
            Files.list(dir)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.startsWith(FILE_PREFIX.toLowerCase()) && name.endsWith(FILE_SUFFIX);
                })
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    // Extract date string: transcripts_03_08_26.json → 03_08_26
                    String dateStr = name.substring(FILE_PREFIX.length(),
                            name.length() - FILE_SUFFIX.length());
                    dates.add(dateStr);
                });
        } catch (IOException e) {
            log.warn("Could not list transcript files in '{}': {}", dir, e.getMessage());
        }
    }
}

