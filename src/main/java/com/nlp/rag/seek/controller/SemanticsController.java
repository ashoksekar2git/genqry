package com.nlp.rag.seek.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * REST controller that exposes the semantic alias groups defined in
 * supportingFiles/semantic.json to the UI.
 *
 * GET  /api/v1/admin/semantics
 *   Returns all alias groups wrapped in a structured response.
 *
 * POST /api/v1/admin/semantics/entry
 *   Adds a new alias group or updates an existing one in semantic.json.
 *   Payload:
 *   {
 *     "keyword":  "employee",          // required – the canonical key
 *     "addedBy":  "user",              // optional, defaults to "user"
 *     "comment":  "...",               // optional
 *     "synonyms": ["staff","worker"]   // required – list of synonym strings
 *   }
 */
@RestController
@RequestMapping("/api/v1/admin/semantics")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Semantics", description = "Semantic alias groups for table/column name expansion")
public class SemanticsController {

    private static final Logger log = LoggerFactory.getLogger(SemanticsController.class);
    private static final String CLASSPATH_JSON = "supportingFiles/semantic.json";

    @Value("${seek.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // =========================================================================
    // GET /api/v1/admin/semantics
    // =========================================================================

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSemantics() {
        log.info("GET /api/v1/admin/semantics");
        try {
            // Prefer the on-disk file so POST updates are visible immediately
            Path filePath = resolveFilePath();
            JsonNode root;
            if (filePath != null && Files.exists(filePath)) {
                log.debug("Reading semantic.json from disk: {}", filePath);
                root = objectMapper.readTree(filePath.toFile());
            } else {
                // Fallback to classpath (first-boot / packaged jar)
                log.debug("Reading semantic.json from classpath");
                try (InputStream is = new ClassPathResource(CLASSPATH_JSON).getInputStream()) {
                    root = objectMapper.readTree(is);
                }
            }
            return ResponseEntity.ok(buildResponse(root));
        } catch (Exception e) {
            log.error("Failed to read {}: {}", CLASSPATH_JSON, e.getMessage(), e);
            return errorResponse("Failed to read semantic.json", e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/admin/semantics/entry
    // Payload: { "keyword":"...", "addedBy":"...", "comment":"...", "synonyms":["..."] }
    // Behaviour: update alias group if keyword already exists, add if not found.
    // =========================================================================

    @PostMapping("/entry")
    public ResponseEntity<Map<String, Object>> upsertEntry(
            @RequestBody Map<String, Object> payload) {

        // ── 1. Validate payload ───────────────────────────────────────────────
        String keyword = Objects.toString(payload.get("keyword"), "").trim().toLowerCase();
        String addedBy = Objects.toString(payload.getOrDefault("addedBy", "user"), "").trim();
        String comment = Objects.toString(payload.getOrDefault("comment", ""), "").trim();

        if (keyword.isBlank()) {
            return errorResponse("'keyword' is required and cannot be blank", null);
        }

        Object synonymsRaw = payload.get("synonyms");
        if (!(synonymsRaw instanceof List) || ((List<?>) synonymsRaw).isEmpty()) {
            return errorResponse("'synonyms' must be a non-empty list of strings", null);
        }
        @SuppressWarnings("unchecked")
        List<String> synonymList = (List<String>) synonymsRaw;

        log.info("POST /api/v1/admin/semantics/entry — keyword={} addedBy={} synonyms={}",
                keyword, addedBy, synonymList);

        // ── 2. Resolve on-disk path ───────────────────────────────────────────
        Path filePath = resolveFilePath();
        if (filePath == null || !Files.exists(filePath)) {
            return errorResponse("semantic.json not found on disk at: " + filePath, null);
        }

        try {
            // ── 3. Read current JSON ──────────────────────────────────────────
            ObjectNode root = (ObjectNode) objectMapper.readTree(filePath.toFile());

            // ── 4. Locate alias_groups object ─────────────────────────────────
            if (!root.has("alias_groups") || !root.get("alias_groups").isObject()) {
                root.putObject("alias_groups");
            }
            ObjectNode aliasGroups = (ObjectNode) root.get("alias_groups");

            // ── 5. Build the entry node ───────────────────────────────────────
            ObjectNode entryNode = objectMapper.createObjectNode();
            entryNode.put("addedBy", addedBy.isBlank() ? "user" : addedBy);
            if (!comment.isBlank()) {
                entryNode.put("_comment", comment);
            }
            ArrayNode synonymsNode = entryNode.putArray("synonyms");
            synonymList.stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .forEach(synonymsNode::add);

            // ── 6. Insert or replace ──────────────────────────────────────────
            boolean isUpdate = aliasGroups.has(keyword);
            aliasGroups.set(keyword, entryNode);
            log.info("{} alias group '{}' in semantic.json", isUpdate ? "Updated" : "Added", keyword);

            // ── 7. Write back to disk ─────────────────────────────────────────
            objectMapper.writeValue(filePath.toFile(), root);
            log.info("semantic.json written → {}", filePath);

            // ── 8. Return updated full response ──────────────────────────────
            Map<String, Object> response = buildResponse(root);
            response.put("upserted", Map.of(
                    "keyword", keyword,
                    "action",  isUpdate ? "updated" : "added"
            ));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to upsert entry in {}: {}", filePath, e.getMessage(), e);
            return errorResponse("Failed to update semantic.json", e.getMessage());
        }
    }

    // =========================================================================
    // DELETE /api/v1/admin/semantics/entry/{keyword}
    // Removes the alias group with the given keyword from semantic.json.
    // =========================================================================

    @DeleteMapping("/entry/{keyword}")
    public ResponseEntity<Map<String, Object>> deleteEntry(@PathVariable String keyword) {
        String normalizedKeyword = keyword.trim().toLowerCase();
        log.info("DELETE /api/v1/admin/semantics/entry/{}", normalizedKeyword);

        if (normalizedKeyword.isBlank()) {
            return errorResponse("'keyword' path variable is required and cannot be blank", null);
        }

        // ── 1. Resolve on-disk path ───────────────────────────────────────────
        Path filePath = resolveFilePath();
        if (filePath == null || !Files.exists(filePath)) {
            return errorResponse("semantic.json not found on disk at: " + filePath, null);
        }

        try {
            // ── 2. Read current JSON ──────────────────────────────────────────
            ObjectNode root = (ObjectNode) objectMapper.readTree(filePath.toFile());

            // ── 3. Locate alias_groups ────────────────────────────────────────
            if (!root.has("alias_groups") || !root.get("alias_groups").isObject()) {
                return errorResponse("alias_groups not found in semantic.json", null);
            }
            ObjectNode aliasGroups = (ObjectNode) root.get("alias_groups");

            // ── 4. Check keyword exists ───────────────────────────────────────
            if (!aliasGroups.has(normalizedKeyword)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error",   "Keyword '" + normalizedKeyword + "' not found in semantic.json");
                body.put("source",  CLASSPATH_JSON);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
            }

            // ── 5. Remove the entry ───────────────────────────────────────────
            aliasGroups.remove(normalizedKeyword);
            log.info("Removed alias group '{}' from semantic.json", normalizedKeyword);

            // ── 6. Write back to disk ─────────────────────────────────────────
            objectMapper.writeValue(filePath.toFile(), root);
            log.info("semantic.json written → {}", filePath);

            // ── 7. Return updated full response ──────────────────────────────
            Map<String, Object> response = buildResponse(root);
            response.put("deleted", Map.of("keyword", normalizedKeyword, "action", "deleted"));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to delete entry '{}' from {}: {}", normalizedKeyword, filePath, e.getMessage(), e);
            return errorResponse("Failed to update semantic.json", e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Resolve the real filesystem path of semantic.json */
    private Path resolveFilePath() {
        // 1. Try configured directory (relative to working dir)
        Path relative = Paths.get(supportingFilesDir, "semantic.json");
        if (Files.exists(relative)) return relative.toAbsolutePath();

        // 2. Try absolute supportingFilesDir
        Path absolute = Paths.get(supportingFilesDir).resolve("semantic.json");
        if (Files.exists(absolute)) return absolute;

        // 3. Try classpath resource URL → file path (works in dev mode)
        try {
            java.net.URL url = new ClassPathResource(CLASSPATH_JSON).getURL();
            Path p = Paths.get(url.toURI());
            if (Files.exists(p)) return p;
        } catch (Exception ignored) {}

        return relative.toAbsolutePath(); // best-guess fallback
    }

    /** Build the structured response from the parsed semantic.json root node */
    private Map<String, Object> buildResponse(JsonNode root) {
        String description = root.path("_description").asText("");

        List<Map<String, Object>> aliasGroups = new ArrayList<>();
        int totalSynonyms = 0;

        JsonNode groups = root.path("alias_groups");
        if (groups.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = groups.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String keyword  = entry.getKey();
                JsonNode group  = entry.getValue();

                List<String> synonyms = new ArrayList<>();
                JsonNode synonymsNode = group.path("synonyms");
                if (synonymsNode.isArray()) {
                    for (JsonNode s : synonymsNode) {
                        synonyms.add(s.asText());
                    }
                }
                totalSynonyms += synonyms.size();

                Map<String, Object> groupMap = new LinkedHashMap<>();
                groupMap.put("keyword",  keyword);
                groupMap.put("addedBy",  group.path("addedBy").asText("admin"));
                groupMap.put("comment",  group.path("_comment").asText(""));
                groupMap.put("synonyms", synonyms);
                aliasGroups.add(groupMap);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalGroups",   aliasGroups.size());
        summary.put("totalSynonyms", totalSynonyms);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source",      CLASSPATH_JSON);
        response.put("description", description);
        response.put("aliasGroups", aliasGroups);
        response.put("summary",     summary);
        return response;
    }

    private ResponseEntity<Map<String, Object>> errorResponse(String error, String details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error",  error);
        if (details != null) body.put("details", details);
        body.put("source", CLASSPATH_JSON);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
