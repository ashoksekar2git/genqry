package com.nlp.rag.seek.doc.controller;

import com.nlp.rag.seek.doc.model.DocumentQueryResponse;
import com.nlp.rag.seek.doc.model.NLDocQueryRequest;
import com.nlp.rag.seek.doc.service.DocumentRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;

/**
 * REST controller for document-grounded natural language queries.
 *
 * Serves: POST /api/v1/seekDoc
 *
 * Flow:
 *  1. Receive the user's natural language question + userName (+ optional docId / topK)
 *  2. Assume the document RAG has already been initialised (chunks in vector store)
 *  3. Retrieve the top-k semantically relevant chunks from the per-user vector store
 *  4. Build a document-grounded prompt (context excerpts + question, NO SQL instruction)
 *  5. Call the LLM (docChatClient) to generate a plain-text answer
 *  6. Return the answer, confidence, and source excerpts to the frontend
 *
 * This is completely separate from the SQL-RAG NL2SQLController.
 * The LLM is explicitly instructed NOT to generate SQL.
 *
 * Request body:
 * {
 *   "userName" : "ashok",
 *   "question" : "What is the return policy?",
 *   "docId"    : "ashok_policy_guide",   // optional — searches all docs if omitted
 *   "topK"     : 5                        // optional — default 5
 * }
 *
 * Response: DocumentQueryResponse
 * {
 *   "answer"          : "According to the document, the return policy is ...",
 *   "confidenceLabel" : "HIGH",
 *   "confidenceScore" : 0.8712,
 *   "fileName"        : "policy_guide.pdf",
 *   "sourceChunks"    : ["...excerpt 1...", "...excerpt 2..."],
 *   "error"           : null
 * }
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Document Q&A", description = "Natural language document question answering")
public class NLDocController {

    private static final Logger log = LoggerFactory.getLogger(NLDocController.class);

    @Autowired
    private DocumentRagService documentRagService;

    // =========================================================================
    // POST /api/v1/seekDoc
    // =========================================================================

    /**
     * Main document Q&A endpoint called by the frontend.
     *
     * Retrieves semantically relevant chunks from the pre-initialised document
     * vector store, builds a grounded prompt, and calls the LLM for a
     * plain-text answer. No SQL is generated at any stage.
     */
    @PostMapping("/seekDoc")
    public ResponseEntity<?> seekDoc(@RequestBody NLDocQueryRequest request) {

        // ── Validate ──────────────────────────────────────────────────────────
        if (request.getUserName() == null || request.getUserName().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "userName is required"));
        }
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "question is required"));
        }

        log.info("seekDoc request: user='{}' documentName='{}' docId='{}' topK={} query='{}'",
                request.getUserName(),
                request.getDocumentName() != null ? request.getDocumentName() : "(none)",
                request.getDocId()        != null ? request.getDocId()        : "(none)",
                request.getTopK()         != null ? request.getTopK()         : 3,
                request.getQuery());

        try {
            DocumentQueryResponse response = documentRagService.query(request);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("seekDoc failed for user='{}': {}", request.getUserName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Document query failed: " + e.getMessage()));
        }
    }

    // =========================================================================
    // GET /api/v1/seekDoc/health
    // =========================================================================

    @GetMapping("/seekDoc/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status",  "UP",
                "service", "Document RAG — NLDocController"
        ));
    }
}
