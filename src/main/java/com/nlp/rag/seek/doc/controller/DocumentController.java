package com.nlp.rag.seek.doc.controller;

import com.nlp.rag.seek.doc.model.DocumentQueryResponse;
import com.nlp.rag.seek.doc.model.DocumentUploadResponse;
import com.nlp.rag.seek.doc.service.DocumentRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the Document RAG module.
 *
 * Completely separate from the SQL-RAG pipeline (NL2SQLController).
 *
 * Endpoints
 * ─────────
 *   POST   /api/v1/documents/reindex?userName={userName}
 *     Re-processes every file already saved on disk for the user using the
 *     current extractor (new PDF grid-reconstructor).  No re-upload needed.
 *     Use after upgrading the PDF chunking strategy.
 *
 *   POST   /api/v1/documents/upload
 *     Multipart upload: saves the file under supportingFiles/{userName}/,
 *     detects type, extracts text, chunks with sliding-window, embeds + indexes.
 *     Params:
 *       - file      : the document file (MultipartFile)
 *       - userName  : the logged-in username (form field or query param)
 *
 *   POST   /api/v1/documents/query
 *     Ask a question grounded on the user's uploaded documents.
 *     Body: { "userName": "...", "question": "...", "docId": "..." (optional), "topK": 5 (optional) }
 *
 *   GET    /api/v1/documents/list?userName={userName}
 *     Lists all document IDs indexed for the user.
 *
 *   DELETE /api/v1/documents/{docId}?userName={userName}
 *     Removes a document from the user's index.
 */
@RestController
@RequestMapping("/api/v1/documents")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Documents", description = "Document upload, query (Document RAG), and re-index")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    @Autowired
    private DocumentRagService documentRagService;

    /** Maximum allowed file size in MB — configured via seek.upload.max-file-size-mb */
    @Value("${seek.upload.max-file-size-mb:1}")
    private long maxFileSizeMb;

    // =========================================================================
    // Upload
    // =========================================================================

    /**
     * POST /api/v1/documents/upload
     *
     * Accepts a multipart/form-data request with:
     *   - file      (required) — the document binary
     *   - userName  (required) — the authenticated username
     *
     * Returns a DocumentUploadResponse with:
     *   docId, fileName, docType, totalChunks, embeddedChunks, savedPath, message
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam("file")     MultipartFile file,
            @RequestParam("userName") String userName) {

        log.info("Document upload request: user='{}' file='{}' size={}B",
                userName, file.getOriginalFilename(), file.getSize());

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Uploaded file is empty"));
        }
        if (userName == null || userName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "userName is required"));
        }

        // ── Global file size enforcement ──────────────────────────────────────
        long maxBytes = maxFileSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            double sizeMb = file.getSize() / (1024.0 * 1024.0);
            log.warn("Document upload rejected — file '{}' is {:.2f} MB (limit: {} MB)",
                    file.getOriginalFilename(), sizeMb, maxFileSizeMb);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of(
                        "error",   String.format("File '%.0s' (%.2f MB) exceeds the %d MB upload limit.",
                                    file.getOriginalFilename(), sizeMb, maxFileSizeMb),
                        "maxMb",   maxFileSizeMb,
                        "fileMb",  String.format("%.2f", sizeMb)
                    ));
        }

        try {
            DocumentUploadResponse resp = documentRagService.upload(userName.trim(), file);
            HttpStatus status = (resp.getErrors() != null && !resp.getErrors().isEmpty())
                    ? HttpStatus.UNPROCESSABLE_ENTITY
                    : HttpStatus.OK;
            return ResponseEntity.status(status).body(resp);
        } catch (Exception e) {
            log.error("Document upload failed for user='{}': {}", userName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    // =========================================================================
    // Query
    // =========================================================================

    /**
     * POST /api/v1/documents/query
     *
     * Body:
     * {
     *   "userName": "ashok",
     *   "question": "What is the refund policy?",
     *   "docId":    "ashok_policy_guide"   (optional — search all docs if omitted)
     *   "topK":     5                       (optional, default 5)
     * }
     *
     * Returns a DocumentQueryResponse with:
     *   answer, confidenceLabel, confidenceScore, sourceChunks, fileName
     */
    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody Map<String, Object> body) {
        String userName = (String) body.get("userName");
        String question = (String) body.get("question");
        String docId    = (String) body.getOrDefault("docId", null);
        Integer topK    = body.get("topK") instanceof Number n ? n.intValue() : null;

        if (userName == null || userName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userName is required"));
        }
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        log.info("Document query: user='{}' docId='{}' question='{}'", userName, docId, question);

        try {
            DocumentQueryResponse resp = documentRagService.query(
                    userName.trim(), question.trim(), docId, topK);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Document query failed for user='{}': {}", userName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Query failed: " + e.getMessage()));
        }
    }

    // =========================================================================
    // Document management
    // =========================================================================

    /**
     * GET /api/v1/documents/list?userName={userName}
     *
     * Returns the list of document IDs currently indexed for the user.
     */
    @GetMapping("/list")
    public ResponseEntity<?> listDocuments(@RequestParam("userName") String userName) {
        if (userName == null || userName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userName is required"));
        }
        List<String> docs = documentRagService.listDocuments(userName.trim());
        return ResponseEntity.ok(Map.of("userName", userName, "documents", docs, "count", docs.size()));
    }

    /**
     * DELETE /api/v1/documents/{docId}?userName={userName}
     *
     * Removes the document from the user's vector index.
     * Does NOT delete the saved file from disk.
     */
    @DeleteMapping("/{docId}")
    public ResponseEntity<?> removeDocument(
            @PathVariable("docId")    String docId,
            @RequestParam("userName") String userName) {

        if (userName == null || userName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userName is required"));
        }
        documentRagService.removeDocument(userName.trim(), docId);
        return ResponseEntity.ok(Map.of(
                "message", "Document '" + docId + "' removed from index for user '" + userName + "'"));
    }

    /**
     * POST /api/v1/documents/reindex?userName={userName}
     *
     * Re-extracts and re-indexes every document already saved on disk for this user
     * using the current extraction strategy (PDF grid-reconstruction, sliding-window
     * for other types).  No file re-upload needed.
     *
     * Use this after the PDF extractor is upgraded to rebuild stale indexes.
     */
    @PostMapping("/reindex")
    public ResponseEntity<?> reindex(@RequestParam("userName") String userName) {
        if (userName == null || userName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userName is required"));
        }
        log.info("Re-index requested for user='{}'", userName);
        try {
            Map<String, Object> report = documentRagService.reindexAll(userName.trim());
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Re-index failed for user='{}': {}", userName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Re-index failed: " + e.getMessage()));
        }
    }
}
