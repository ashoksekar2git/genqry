package com.nlp.rag.seek.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlp.rag.seek.service.UsernameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * REST controller for reading NL→SQL query history from the user's
 * transcripts.json file located at supportingFiles/{userName}/transcripts.json.
 *
 * Base path: /api/v1/query-history
 *
 * GET /api/v1/query-history?userName=AshokSekar&page=0&size=10
 *
 * The transcripts.json file is:
 *   - Written on user login (exported from transcript_details DB table)
 *   - Kept in sync every time a new transcript is persisted to the DB
 */
@RestController
@RequestMapping("/api/v1/query-history")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Query History", description = "User transcript / query history")
public class QueryHistoryController {

    private static final Logger log = LoggerFactory.getLogger(QueryHistoryController.class);

    @Value("${genqry.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // GET /api/v1/query-history?userName=AshokSekar&page=0&size=10
    // =========================================================================

    /**
     * Returns paginated query history for the given user.
     *
     * Reads from supportingFiles/{userName}/transcripts.json.
     *
     * Query parameters:
     *   userName (required) — the logged-in user's username
     *   page     (optional) — zero-based page number (default 0)
     *   size     (optional) — entries per page (default 10)
     *
     * Response:
     * {
     *   "userName":     "AshokSekar",
     *   "page":         0,
     *   "size":         10,
     *   "totalEntries": 42,
     *   "totalPages":   5,
     *   "entries":      [ ... ]
     * }
     */
    @Operation(summary = "Get query history", description = "Returns paginated NL2SQL query history for the user")
    @GetMapping
    public ResponseEntity<Map<String, Object>> getQueryHistory(
            @RequestParam(value = "userName", required = false) String userName,
            @RequestParam(value = "page",     defaultValue = "0")  int page,
            @RequestParam(value = "size",     defaultValue = "10") int size) {

        log.info("GET /api/v1/query-history — userName='{}' page={} size={}", userName, page, size);

        if (userName == null || userName.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "userName query parameter is required"));
        }

        String safeUser = UsernameUtil.sanitize(userName);
        Path jsonFile = Paths.get(supportingFilesDir).toAbsolutePath()
                .resolve(safeUser)
                .resolve("transcripts.json");

        List<Map<String, Object>> allEntries = readTranscriptsFromFile(jsonFile);

        // Paginate
        int totalEntries = allEntries.size();
        int totalPages   = (int) Math.ceil((double) totalEntries / size);
        int fromIndex    = Math.min(page * size, totalEntries);
        int toIndex      = Math.min(fromIndex + size, totalEntries);
        List<Map<String, Object>> pageEntries = allEntries.subList(fromIndex, toIndex);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("userName",     userName);
        resp.put("page",         page);
        resp.put("size",         size);
        resp.put("totalEntries", totalEntries);
        resp.put("totalPages",   totalPages);
        resp.put("entries",      pageEntries);

        if (totalEntries == 0) {
            resp.put("message", "No query history found for " + userName);
        }

        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Reads and parses the transcripts.json file into a list of maps.
     * Returns an empty list if the file doesn't exist or can't be parsed.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readTranscriptsFromFile(Path jsonFile) {
        if (!Files.exists(jsonFile)) {
            log.info("Transcript file not found: {}", jsonFile);
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(jsonFile.toFile());
            if (!root.isArray()) {
                log.warn("transcripts.json is not a JSON array: {}", jsonFile);
                return Collections.emptyList();
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            for (JsonNode node : root) {
                entries.add(objectMapper.convertValue(node, Map.class));
            }
            log.debug("Loaded {} transcript entries from {}", entries.size(), jsonFile);
            return entries;
        } catch (Exception e) {
            log.error("Failed to read transcripts.json at '{}': {}", jsonFile, e.getMessage());
            return Collections.emptyList();
        }
    }
}
