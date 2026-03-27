package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.service.UsernameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GET /api/v1/workSpace?username={username}&usertype={usertype}
 *
 * Called by the query page on load to populate the data-source dropdown.
 *
 * Rules:
 *  - usertype = Temp (guest)  → fileList: ["ecommerce"]
 *  - other usertype           → scan supportingFiles/{username}/
 *      • files found          → return those file names (documents only, no internals)
 *                               always prepend "ecommerce"
 *      • directory missing    → create it, return ["ecommerce"]
 *      • directory empty      → return ["ecommerce"]
 */
@RestController
@RequestMapping("/api/v1/workSpace")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Workspace", description = "User workspace file listing")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    @Value("${seek.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    @Operation(summary = "Get workspace files", description = "Returns the user's database schemas, documents, and optionally transcript files")
    @GetMapping("")
    public ResponseEntity<Map<String, Object>> getWorkspace(
            @RequestParam("username") String username,
            @RequestParam(value = "usertype", defaultValue = "") String usertype,
            @RequestParam(value = "includeTranscripts", defaultValue = "false") boolean includeTranscripts) {

        log.info("GET /api/v1/workSpace — username='{}' usertype='{}' includeTranscripts={}",
                username, usertype, includeTranscripts);

        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "username is required"));
        }

        String trimmedUser = UsernameUtil.sanitize(username.trim());
        boolean isGuest    = "Temp".equalsIgnoreCase(usertype.trim());

        List<Map<String, String>> fileList;

        // Both guest (Temp) and registered users: scan their directory for uploaded files
        fileList = resolveFileList(trimmedUser);
        log.info("Workspace: user='{}' usertype='{}' isGuest={} → fileList={}",
                trimmedUser, usertype, isGuest, fileList.size());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("username",  trimmedUser);
        resp.put("userType",  usertype);
        resp.put("isGuest",   isGuest);
        resp.put("fileList",  fileList);
        resp.put("fileCount", fileList.size());

        // Only include transcript files when explicitly requested (dashboard page)
        if (includeTranscripts) {
            List<Map<String, String>> transcriptList = resolveTranscriptList(trimmedUser);
            resp.put("transcriptList", transcriptList);
            log.info("Workspace: included {} transcript files for user '{}'",
                    transcriptList.size(), trimmedUser);
        }

        return ResponseEntity.ok(resp);
    }

    /** Pattern to match schema files: &lt;DBName&gt;_schema_&lt;timestamp&gt;.json */
    private static final Pattern SCHEMA_FILE_PATTERN =
            Pattern.compile("^(.+?)_schema_\\d{8}_\\d{6}\\.json$", Pattern.CASE_INSENSITIVE);

    /** Known document file extensions */
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".doc", ".xlsx", ".xls", ".csv",
            ".txt", ".rtf", ".pptx", ".ppt", ".odt", ".ods", ".odp");

    /**
     * Scans supportingFiles/{username}/ and returns user-visible files as
     * structured objects so the frontend knows how to handle each entry.
     *
     * Each item in the returned list is a Map with:
     *   { "type": "database",  "name": "&lt;DBName&gt;" }        — for schema JSON files
     *   { "type": "document",  "name": "&lt;documentName&gt;" }  — for uploaded docs
     *
     * "ecommerce" is always the first entry (default SQL DB).
     * Internal system files (vector indices, transcripts, profiles) are excluded.
     *
     * If the directory does not exist it is created and only ["ecommerce"] is returned.
     */
    private List<Map<String, String>> resolveFileList(String username) {
        Path base    = Paths.get(supportingFilesDir).toAbsolutePath();
        Path userDir = findUserDirectory(base, username);

        List<Map<String, String>> fileList = new ArrayList<>();
        // Always first — represents the default SQL DB query option
        fileList.add(Map.of("type", "database", "name", "ecommerce"));

        if (userDir == null) {
            // Directory doesn't exist — create it
            Path newDir = base.resolve(username);
            try {
                Files.createDirectories(newDir);
                log.info("Workspace: created missing user directory '{}'", newDir);
            } catch (IOException e) {
                log.warn("Workspace: could not create directory for user '{}': {}",
                        username, e.getMessage());
            }
            return fileList;   // only ecommerce
        }

        // Directory exists — scan ALL entries (files and subdirectories)
        try (var stream = Files.list(userDir)) {
            stream.sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(
                                     b.getFileName().toString()))
                  .forEach(path -> {
                      String name = path.getFileName().toString();

                      // Skip internal system files
                      if (isInternalSystemFile(name)) return;

                      if (Files.isRegularFile(path)) {
                          // Check if it's a schema file: <DBName>_schema_<timestamp>.json
                          Matcher schemaMatcher = SCHEMA_FILE_PATTERN.matcher(name);
                          if (schemaMatcher.matches()) {
                              String dbName = schemaMatcher.group(1);
                              fileList.add(Map.of("type", "database", "name", dbName));
                          } else if (isDocumentFile(name)) {
                              // Regular document (pdf, docx, xlsx, txt, etc.)
                              fileList.add(Map.of("type", "document", "name", name));
                          }
                          // JSON files that are not schema files and not documents are ignored
                      } else if (Files.isDirectory(path)) {
                          // Sub-directories are not exposed to the frontend currently
                          log.debug("Workspace: skipping sub-directory '{}'", name);
                      }
                  });

        } catch (IOException e) {
            log.warn("Workspace: error listing files for user '{}': {}", username, e.getMessage());
        }

        return fileList;
    }

    /**
     * Scans supportingFiles/{username}/ for transcript files.
     * Returns a list of maps with name, type, and uploadedAt.
     *
     * Includes:
     *   - transcripts.json           (the consolidated export from DB)
     *   - transcripts_MM_dd_yy.json  (daily transcript logs)
     */
    private List<Map<String, String>> resolveTranscriptList(String username) {
        Path base    = Paths.get(supportingFilesDir).toAbsolutePath();
        Path userDir = findUserDirectory(base, username);
        List<Map<String, String>> transcripts = new ArrayList<>();

        if (userDir == null || !Files.isDirectory(userDir)) {
            return transcripts;
        }

        try (var stream = Files.list(userDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String lower = p.getFileName().toString().toLowerCase();
                      return lower.equals("transcripts.json")
                              || (lower.startsWith("transcripts_") && lower.endsWith(".json"));
                  })
                  .sorted((a, b) -> {
                      try {
                          return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                      } catch (IOException e) {
                          return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                      }
                  })
                  .forEach(path -> {
                      String name = path.getFileName().toString();
                      Map<String, String> entry = new LinkedHashMap<>();
                      entry.put("name", name);
                      entry.put("type", "transcript");
                      try {
                          entry.put("uploadedAt", Files.getLastModifiedTime(path).toString());
                      } catch (IOException ignored) {}
                      transcripts.add(entry);
                  });
        } catch (IOException e) {
            log.warn("Workspace: error listing transcript files for '{}': {}", username, e.getMessage());
        }

        return transcripts;
    }

    /**
     * Returns true if the file is a user-visible document based on its extension.
     */
    private boolean isDocumentFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return DOCUMENT_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /**
     * Finds the user directory under base using case-insensitive matching.
     * Returns the exact Path on disk, or null if no match found.
     */
    private Path findUserDirectory(Path base, String username) {
        // Exact match first (fast path)
        Path exact = base.resolve(username);
        if (Files.isDirectory(exact)) return exact;

        // Case-insensitive fallback scan
        if (!Files.isDirectory(base)) return null;
        try (var stream = Files.list(base)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(username))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Workspace: error scanning base dir for user '{}': {}", username, e.getMessage());
            return null;
        }
    }

    @DeleteMapping("")
    @Operation(summary = "Delete a document from workspace",
               description = "Deletes an uploaded document file from the user's supportingFiles directory")
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @RequestParam("username") String username,
            @RequestParam(value = "usertype", defaultValue = "") String usertype,
            @RequestParam("docName") String docName) {

        log.info("DELETE /api/v1/workSpace — username='{}' usertype='{}' docName='{}'",
                username, usertype, docName);

        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "username is required"));
        }
        if (docName == null || docName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "docName is required"));
        }

        String safeUser = UsernameUtil.sanitize(username.trim());
        String safeDoc  = Paths.get(docName.trim()).getFileName().toString(); // prevent path traversal

        Path base    = Paths.get(supportingFilesDir).toAbsolutePath();
        Path userDir = findUserDirectory(base, safeUser);

        if (userDir == null || !Files.isDirectory(userDir)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false,
                                 "message", "User directory not found for '" + safeUser + "'"));
        }

        Path targetFile = userDir.resolve(safeDoc);

        if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false,
                                 "message", "Document '" + safeDoc + "' not found in workspace"));
        }

        try {
            Files.delete(targetFile);
            log.info("Deleted document '{}' from user '{}' workspace: {}", safeDoc, safeUser, targetFile);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success",  true);
            resp.put("username", safeUser);
            resp.put("docName",  safeDoc);
            resp.put("message",  "Document '" + safeDoc + "' deleted successfully");
            return ResponseEntity.ok(resp);

        } catch (IOException e) {
            log.error("Failed to delete document '{}' for user '{}': {}",
                    safeDoc, safeUser, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false,
                                 "message", "Failed to delete document: " + e.getMessage()));
        }
    }

    /**
     * Returns true for internal system files that must NOT appear in the
     * frontend dropdown:
     *   doc_vector_index_*.json   — vector store index
     *   *_dbschema.json           — DB schema snapshots
     *   *_gmail_*.json etc.       — registration profile JSONs
     *   Any JSON ending in 8-digit date (mmddyyyy) — profile files
     */
    private boolean isInternalSystemFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        if (lower.equals("transcripts.json"))                                  return true;
        if (lower.startsWith("transcripts_") && lower.endsWith(".json"))      return true;
        if (lower.startsWith("doc_vector_index_") && lower.endsWith(".json")) return true;
        if (lower.startsWith("vector_index_") && lower.endsWith(".json"))     return true;
        if (lower.endsWith("_dbschema.json"))                                  return true;
        if (lower.endsWith(".json") && (
                lower.contains("_gmail_")   || lower.contains("_yahoo_")  ||
                lower.contains("_outlook_") || lower.contains("_hotmail_") ||
                lower.contains("_ecommerce_") || lower.contains("_seek_"))) return true;
        // Profile JSON pattern: <email>_<mmddyyyy>.json  (8-digit date suffix)
        if (lower.endsWith(".json")) {
            String withoutExt = lower.substring(0, lower.length() - 5);
            if (withoutExt.matches(".*_\\d{8}$")) return true;
        }
        return false;
    }
}

