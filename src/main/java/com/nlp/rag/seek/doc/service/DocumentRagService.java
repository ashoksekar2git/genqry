package com.nlp.rag.seek.doc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlp.rag.seek.doc.model.DocumentChunk;
import com.nlp.rag.seek.doc.model.DocumentQueryResponse;
import com.nlp.rag.seek.doc.model.DocumentUploadResponse;
import com.nlp.rag.seek.doc.model.NLDocQueryRequest;
import com.nlp.rag.seek.doc.service.DocumentVectorStoreService.ScoredDocChunk;
import com.nlp.rag.seek.service.UserDbService;
import com.nlp.rag.seek.service.UsernameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates the complete Document RAG pipeline.
 * Completely isolated from the SQL-RAG pipeline.
 *
 * Enhancements:
 *  - Loads prompt rules from BusinessRulesForPrompts.json (hot-reload via @PostConstruct)
 *  - In-memory response cache keyed by userName:question (skipped for hallucinated answers)
 *  - Hallucination detection: flags answers containing the "not found" sentinel phrase
 *  - Persists every LLM interaction to transcript_details table
 */
@Service
public class DocumentRagService {

    private static final Logger log = LoggerFactory.getLogger(DocumentRagService.class);

    private static final int    DEFAULT_TOP_K                  = 5;
    private static final String DOC_RULES_JSON                 = "supportingFiles/BusinessRulesForPrompts.json";
    /** Sentinel returned by the LLM (and by us) when no relevant content is found. */
    private static final String NOT_FOUND_SENTINEL             = "I could not find relevant information";

    @Value("${seek.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    @Autowired private DocumentTextExtractorService         extractorService;
    @Autowired private DocumentSlidingWindowChunkingService chunkingService;
    @Autowired private DocumentVectorStoreService           vectorStoreService;
    @Autowired private PdfStructuralExtractorService        pdfStructuralExtractorService;

    @Autowired(required = false)
    private UserDbService userDbService;

    /** Doc-specific ChatClient — system prompt forbids SQL, answers from context only. */
    @Autowired(required = false)
    @Qualifier("docChatClient")
    private ChatClient docChatClient;

    // ── Business-rules loaded from JSON ──────────────────────────────────────
    private String docSystemInstruction = "You are a helpful assistant that answers questions strictly from provided document excerpts.\n";
    private String docRulesText         = "";   // numbered list built from general_rules[]

    // ── Lightweight in-memory response cache: key = "userName::question" ─────
    private final Map<String, DocumentQueryResponse> responseCache = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // Startup: load business rules from JSON
    // =========================================================================

    @PostConstruct
    public void loadDocBusinessRules() {
        try {
            // Try filesystem first (written by BusinessRulesJsonExporter at startup)
            java.nio.file.Path fsPath = java.nio.file.Paths.get(
                    "src/main/resources/supportingFiles", "BusinessRulesForPrompts.json").toAbsolutePath();
            InputStream is;
            if (java.nio.file.Files.exists(fsPath)) {
                is = java.nio.file.Files.newInputStream(fsPath);
            } else {
                is = new ClassPathResource(DOC_RULES_JSON).getInputStream();
            }

            JsonNode root = objectMapper.readTree(is);
            is.close();

            if (root.isArray()) {
                // Flat array format from BusinessRulesJsonExporter — filter for DOCUMENT rules
                StringBuilder sb = new StringBuilder();
                for (JsonNode ruleNode : root) {
                    boolean enabled  = ruleNode.path("enabled").asBoolean(true);
                    if (!enabled) continue;

                    String ruleType = ruleNode.path("ruleType").asText("");
                    if (!"DOCUMENT".equals(ruleType)) continue;

                    int number      = ruleNode.path("number").asInt(0);
                    String ruleText = ruleNode.path("rule").asText("");
                    if (number != 0 && !ruleText.isBlank()) {
                        sb.append(number).append(". ").append(ruleText).append("\n");
                    }

                }
                docRulesText = sb.toString().stripTrailing();
            } else {
                // Legacy nested format (backward compatibility)
                if (root.has("system_instruction")) {
                    docSystemInstruction = root.get("system_instruction").asText(docSystemInstruction);
                }
                JsonNode generalRules = root.get("general_rules");
                if (generalRules != null && generalRules.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode ruleNode : generalRules) {
                        if (!ruleNode.path("enabled").asBoolean(true)) continue;
                        sb.append(ruleNode.get("number").asInt())
                          .append(". ")
                          .append(ruleNode.get("rule").asText())
                          .append("\n");
                    }
                    docRulesText = sb.toString().stripTrailing();
                }
            }

            log.info("Doc business rules loaded from {} — {} chars", DOC_RULES_JSON, docRulesText.length());

        } catch (Exception e) {
            log.warn("Could not load {} — using hardcoded defaults: {}", DOC_RULES_JSON, e.getMessage());
            // Fallback hardcoded rules keep the service operational if the file is missing
            docRulesText =
                    "1. Answer ONLY from the provided document excerpts.\n" +
                    "2. Do NOT generate SQL queries or any database commands.\n" +
                    "3. Do NOT fabricate or infer information beyond what is explicitly stated.\n" +
                    "4. If the answer is not present respond with: \"" + NOT_FOUND_SENTINEL + " in the provided document.\"\n" +
                    "5. Cite the excerpt number (e.g., \"[Excerpt 2]\") when referencing specific content.\n" +
                    "6. Be concise and factual.";
        }
    }

    // =========================================================================
    // Upload + RAG init
    // =========================================================================

    /**
     * Saves {@code file} under supportingFiles/{userName}/, extracts text,
     * chunks it with the sliding-window strategy, embeds, and indexes.
     *
     * @param userName the logged-in username (from JWT / session)
     * @param file     the multipart upload
     * @return a populated DocumentUploadResponse
     */
    public DocumentUploadResponse upload(String userName, MultipartFile file) {
        DocumentUploadResponse resp = new DocumentUploadResponse();
        resp.setUploadedAt(Instant.now());
        resp.setUserName(userName);

        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "document";
        resp.setFileName(originalName);

        // ── 1. Save file ──────────────────────────────────────────────────────
        Path savedPath;
        try {
            savedPath = saveFile(userName, file, originalName);
            resp.setSavedPath(savedPath.toString());
            resp.setFileSizeBytes(Files.size(savedPath));
        } catch (IOException e) {
            log.error("Failed to save uploaded file '{}' for user '{}': {}", originalName, userName, e.getMessage());
            resp.setMessage("File save failed: " + e.getMessage());
            resp.setErrors(List.of(e.getMessage()));
            return resp;
        }

        // ── 2. Detect document type ───────────────────────────────────────────
        String docType = extractorService.detectDocType(savedPath);
        resp.setDocType(docType);
        log.info("Detected document type '{}' for file '{}'", docType, originalName);

        // ── 3. Extract & chunk ────────────────────────────────────────────────
        String docId = buildDocId(userName, originalName);
        resp.setDocId(docId);

        List<DocumentChunk> chunks;

        if ("PDF".equalsIgnoreCase(docType)) {
            // ── PDF: structural chunking via PDFBox font analysis ─────────────
            log.info("Using structural PDF extractor for '{}'", originalName);
            chunks = pdfStructuralExtractorService.extractChunks(savedPath, docId, originalName, userName);

            if (chunks.isEmpty()) {
                // Fallback: PDFBox returned nothing (scanned/image PDF) — try Tika plain text
                log.warn("Structural PDF extraction returned 0 chunks for '{}' — falling back to Tika+sliding-window", originalName);
                String text = extractorService.extractText(savedPath);
                if (text.isBlank()) {
                    resp.setMessage("Text extraction produced no content — PDF may be scanned/image-only.");
                    resp.setErrors(List.of("Empty text extraction"));
                    resp.setTotalChunks(0);
                    resp.setEmbeddedChunks(0);
                    return resp;
                }
                chunks = chunkingService.chunk(docId, originalName, docType, userName, text);
            }
        } else {
            // ── Non-PDF: existing sliding-window strategy via Tika ────────────
            String text = extractorService.extractText(savedPath);
            if (text.isBlank()) {
                resp.setMessage("Text extraction produced no content — document may be empty or image-only.");
                resp.setErrors(List.of("Empty text extraction"));
                resp.setTotalChunks(0);
                resp.setEmbeddedChunks(0);
                return resp;
            }
            chunks = chunkingService.chunk(docId, originalName, docType, userName, text);
        }

        resp.setTotalChunks(chunks.size());

        // ── 4. Embed + index ──────────────────────────────────────────────────
        vectorStoreService.index(userName, docId, chunks);

        long embedded = chunks.stream().filter(c -> c.getEmbedding() != null).count();
        resp.setEmbeddedChunks((int) embedded);
        resp.setEmbeddingActive(embedded > 0);

        resp.setMessage(String.format(
                "Document '%s' (%s) uploaded and indexed. %d chunks created, %d embedded.",
                originalName, docType, chunks.size(), embedded));

        log.info("Doc RAG init complete for user='{}' doc='{}': {}/{} chunks embedded",
                userName, docId, embedded, chunks.size());
        return resp;
    }

    // =========================================================================
    // Query — typed entry point for NLDocController
    // =========================================================================

    /**
     * Primary entry point called by NLDocController.
     * Accepts a typed NLDocQueryRequest and returns a DocumentQueryResponse.
     */
    public DocumentQueryResponse query(NLDocQueryRequest request) {
        // Resolve the effective docId:
        //   1. Explicit docId in the request takes priority
        //   2. documentName (raw filename from frontend) is converted to the internal docId
        //   3. null → search across all documents for this user (no filter)
        String resolvedDocId = request.getDocId();
        if ((resolvedDocId == null || resolvedDocId.isBlank())
                && request.getDocumentName() != null
                && !request.getDocumentName().isBlank()) {
            resolvedDocId = buildDocId(request.getUserName(), request.getDocumentName());
            log.info("Resolved documentName='{}' → docId='{}'",
                    request.getDocumentName(), resolvedDocId);
        }

        return query(request.getUserName(), request.getQuery(),
                     resolvedDocId, request.getTopK());
    }

    // =========================================================================
    // Query — core implementation
    // =========================================================================

    /**
     * Answers {@code question} grounded strictly on the user's uploaded document chunks.
     * Never generates SQL. Calls the doc-specific LLM client.
     */
    public DocumentQueryResponse query(String userName, String question,
                                       String docId, Integer topK) {
        DocumentQueryResponse resp = new DocumentQueryResponse();
        resp.setUserName(userName);
        resp.setDocId(docId);

        int k = (topK != null && topK > 0) ? topK : DEFAULT_TOP_K;

        log.info("seekDoc query: user='{}' docId='{}' (filter={}) topK={} question='{}'",
                userName,
                docId != null ? docId : "(all docs)",
                docId != null ? "ON" : "OFF",
                k, question);

        // ── 0. In-memory cache lookup ─────────────────────────────────────────
        String cacheKey = userName + "::" + question;
        DocumentQueryResponse cached = responseCache.get(cacheKey);
        if (cached != null) {
            log.info("Doc cache HIT for user='{}' question='{}'", userName, question);
            cached.setFromCache(true);
            return cached;
        }

        // ── 1. Restore index from disk if empty ───────────────────────────────
        if (vectorStoreService.totalChunks(userName) == 0) {
            log.info("Vector store empty for user='{}' — restoring from disk", userName);
            vectorStoreService.restoreFromDisk(userName);
        }

        // ── 2. Retrieve semantically relevant chunks ──────────────────────────
        // Expand the query with synonyms before embedding to improve recall for
        // financial/numeric terms that may not embed well verbatim.
        String expandedQuery = expandQuery(question);
        if (!expandedQuery.equals(question)) {
            log.info("Query expanded: original='{}' → expanded='{}'", question, expandedQuery);
        }

        List<ScoredDocChunk> hits = vectorStoreService.search(userName, expandedQuery, k, docId);

        // Log every retrieved chunk for retrieval diagnostics
        log.info("Retrieved {} chunks for query='{}' (expanded='{}')", hits.size(), question, expandedQuery);
        for (int i = 0; i < hits.size(); i++) {
            ScoredDocChunk h = hits.get(i);
            log.info("  Chunk[{}] id='{}' page={} sectionType={} score={:.4f} preview='{}'",
                    i + 1,
                    h.chunk().getChunkId(),
                    h.chunk().getPageNumber(),
                    h.chunk().getSectionType(),
                    h.score(),
                    h.chunk().getText().substring(0, Math.min(120, h.chunk().getText().length()))
                             .replace('\n', ' '));
        }

        if (hits.isEmpty()) {
            resp.setAnswer("No relevant content found in your uploaded documents for this question. "
                    + "Please make sure the document has been uploaded and indexed.");
            resp.setConfidenceScore(0.0);
            resp.setConfidenceLabel("LOW");
            resp.setSourceChunks(Collections.emptyList());
            log.warn("No chunks retrieved for user='{}' question='{}'", userName, question);
            persistTranscript(userName, question, resp, "FAILURE", "NO_CHUNKS_RETRIEVED");
            return resp;        }

        // ── 3. Build source excerpts ──────────────────────────────────────────
        // TABLE chunks are never truncated — they contain atomic financial data.
        // Other chunks are capped at 600 chars (increased from 400 to preserve more context).
        List<String> excerpts = hits.stream()
                .map(h -> {
                    String text = h.chunk().getText();
                    boolean isTable = "TABLE".equals(h.chunk().getSectionType());
                    int limit = isTable ? text.length() : 600;
                    return text.length() > limit ? text.substring(0, limit) + "…" : text;
                })
                .collect(Collectors.toList());
        resp.setSourceChunks(excerpts);

        // ── 4. Confidence from average cosine score ───────────────────────────
        double avgScore = hits.stream().mapToDouble(ScoredDocChunk::score).average().orElse(0.0);
        resp.setConfidenceScore(Math.round(avgScore * 10000.0) / 10000.0);
        resp.setConfidenceLabel(avgScore >= 0.75 ? "HIGH" : avgScore >= 0.45 ? "MEDIUM" : "LOW");

        resp.setFileName(hits.get(0).chunk().getFileName());

        // ── 5. Build prompt using rules from JSON ─────────────────────────────
        String contextBlock = buildContextBlock(hits);
        String prompt       = buildDocPrompt(question, contextBlock);

        log.info("══════════════ DOC PROMPT SENT TO LLM ══════════════\n{}\n══════════════ END OF DOC PROMPT ══════════════", prompt);

        // ── 6. Call LLM ───────────────────────────────────────────────────────
        if (docChatClient != null) {
            try {
                String answer = docChatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
                log.info("══════════════ DOC LLM RESPONSE ══════════════\n{}\n══════════════ END OF DOC LLM RESPONSE ══════════════", answer);
                resp.setAnswer(answer != null ? answer.trim() : "");
                log.info("LLM answered doc query for user='{}' ({} chars)", userName, resp.getAnswer().length());
            } catch (Exception e) {
                log.error("LLM call failed for doc query user='{}': {}", userName, e.getMessage());
                resp.setAnswer(buildFallbackAnswer(hits));
                resp.setError("LLM call failed — showing retrieved excerpts. Reason: " + e.getMessage());
            }
        } else {
            log.warn("docChatClient not configured — returning keyword-retrieved excerpts");
            resp.setAnswer(buildFallbackAnswer(hits));
            resp.setError("LLM not configured (no API key). Showing most relevant document excerpts.");
        }

        // ── 7. Hallucination detection ────────────────────────────────────────
        boolean hallucinated = detectHallucination(resp.getAnswer(), hits);
        resp.setHallucinated(hallucinated);
        if (hallucinated) {
            log.warn("Hallucination detected for user='{}' question='{}' — response NOT cached", userName, question);
        }

        // ── 8. Cache only valid (non-hallucinated) responses ─────────────────
        if (!hallucinated && resp.getError() == null) {
            responseCache.put(cacheKey, resp);
            log.info("Doc response cached for user='{}' question='{}'", userName, question);
        }

        // ── 9. Persist transcript to DB ───────────────────────────────────────
        String status   = hallucinated ? "HALLUCINATION" : (resp.getError() != null ? "FAILURE" : "SUCCESS");
        String remarks  = hallucinated ? "Answer not grounded in retrieved excerpts"
                        : resp.getError();
        int transcriptId = persistTranscript(userName, question, resp, status, remarks);
        resp.setTranscriptId(transcriptId);

        return resp;
    }

    // =========================================================================
    // Re-index — reprocess existing files on disk with current extractor
    // =========================================================================

    /**
     * Re-indexes all documents already saved on disk for {@code userName}.
     * Scans the supportingFiles/{userName}/ directory, re-extracts and re-chunks
     * every file using the current extractor (including the new PDF grid-extractor),
     * then replaces the existing vector index.
     *
     * Called via POST /api/v1/documents/reindex?userName=...
     * Use this after upgrading the PDF extraction strategy without re-uploading files.
     *
     * @return map of fileName → chunk count (or error message)
     */
    public Map<String, Object> reindexAll(String userName) {
        Map<String, Object> report = new LinkedHashMap<>();
        String safeUser = UsernameUtil.sanitize(userName);
        Path userDir = Paths.get(supportingFilesDir).toAbsolutePath().resolve(safeUser);

        if (!Files.exists(userDir)) {
            report.put("error", "No directory found for user: " + userName);
            return report;
        }

        List<String> processed = new ArrayList<>();
        List<String> failed    = new ArrayList<>();

        try (var stream = Files.list(userDir)) {
            List<Path> files = stream
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> !p.getFileName().toString().startsWith("doc_vector_index_"))
                    .filter(p -> !p.getFileName().toString().endsWith(".json"))
                    .collect(Collectors.toList());

            for (Path filePath : files) {
                String originalName = filePath.getFileName().toString();
                String docType = extractorService.detectDocType(filePath);
                String docId   = buildDocId(userName, originalName);

                try {
                    List<DocumentChunk> chunks;
                    if ("PDF".equalsIgnoreCase(docType)) {
                        log.info("Re-indexing PDF '{}' for user '{}' using grid-extractor", originalName, userName);
                        chunks = pdfStructuralExtractorService.extractChunks(filePath, docId, originalName, userName);
                        if (chunks.isEmpty()) {
                            log.warn("Grid-extractor returned 0 chunks for '{}' — falling back to Tika", originalName);
                            String text = extractorService.extractText(filePath);
                            chunks = text.isBlank() ? List.of()
                                    : chunkingService.chunk(docId, originalName, docType, userName, text);
                        }
                    } else {
                        String text = extractorService.extractText(filePath);
                        chunks = text.isBlank() ? List.of()
                                : chunkingService.chunk(docId, originalName, docType, userName, text);
                    }

                    if (chunks.isEmpty()) {
                        failed.add(originalName + " (0 chunks extracted)");
                        continue;
                    }

                    vectorStoreService.index(userName, docId, chunks);
                    long embedded = chunks.stream().filter(c -> c.getEmbedding() != null).count();
                    processed.add(originalName + " (" + chunks.size() + " chunks, " + embedded + " embedded)");
                    log.info("Re-indexed '{}' for '{}': {} chunks", originalName, userName, chunks.size());

                } catch (Exception e) {
                    log.error("Re-index failed for '{}': {}", originalName, e.getMessage(), e);
                    failed.add(originalName + " (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            report.put("error", "Failed to list directory: " + e.getMessage());
            return report;
        }

        report.put("userName",  userName);
        report.put("processed", processed);
        report.put("failed",    failed);
        report.put("totalProcessed", processed.size());
        report.put("totalFailed",    failed.size());
        return report;
    }

    // =========================================================================
    // Document management
    // =========================================================================

    /** Lists all document IDs indexed for a user. */
    public List<String> listDocuments(String userName) {
        return vectorStoreService.listDocIds(userName);
    }

    /** Removes a document from the index (does not delete the saved file). */
    public void removeDocument(String userName, String docId) {
        vectorStoreService.removeDocument(userName, docId);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Expands a user query with domain-specific synonyms to improve embedding recall.
     *
     * Problem: queries like "how much was withheld for federal" may not embed close
     * to PDF chunks that say "Federal income tax withheld: $1,234" because:
     *   - The chunk uses table/list format with numbers
     *   - The query uses conversational phrasing
     *
     * By appending canonical tax/financial synonyms to the query string before
     * embedding, the query vector moves closer to document chunks that use those terms.
     */
    private String expandQuery(String question) {
        if (question == null || question.isBlank()) return question;
        String lower = question.toLowerCase();
        StringBuilder expansion = new StringBuilder(question);

        // Tax / withholding terminology
        if (containsAny(lower, "federal", "fed tax", "federal tax")) {
            appendIfAbsent(expansion, lower, "federal income tax withheld wages box 2");
        }
        if (containsAny(lower, "withheld", "withholding", "withhold")) {
            appendIfAbsent(expansion, lower, "tax withheld deducted amount");
        }
        if (containsAny(lower, "state tax", "state withheld", "state income")) {
            appendIfAbsent(expansion, lower, "state income tax withheld box 17");
        }
        if (containsAny(lower, "social security", "ss tax", "fica")) {
            appendIfAbsent(expansion, lower, "social security tax withheld box 4");
        }
        if (containsAny(lower, "medicare", "med tax")) {
            appendIfAbsent(expansion, lower, "medicare tax withheld box 6");
        }
        if (containsAny(lower, "wage", "salary", "earning", "income", "gross")) {
            appendIfAbsent(expansion, lower, "wages tips compensation box 1");
        }
        if (containsAny(lower, "w-2", "w2", "1099", "tax form")) {
            appendIfAbsent(expansion, lower, "tax year employer employee wages withheld");
        }
        // Generic financial terms
        if (containsAny(lower, "total", "amount", "how much", "balance")) {
            appendIfAbsent(expansion, lower, "total amount sum dollar value");
        }

        return expansion.toString();
    }

    private boolean containsAny(String text, String... terms) {
        for (String t : terms) if (text.contains(t)) return true;
        return false;
    }

    private void appendIfAbsent(StringBuilder sb, String lowerOriginal, String extra) {
        // Only append terms not already in the original query
        for (String word : extra.split("\\s+")) {
            if (!lowerOriginal.contains(word) && word.length() > 2) {
                sb.append(' ').append(word);
            }
        }
    }

    /**
     * Builds the numbered context block injected into the LLM prompt.
     * Includes structural metadata (section title, type, page) when available.
     * Cleans up garbled financial text (concatenated numbers from PDF table extraction)
     * so the LLM can parse amounts correctly.
     */
    private String buildContextBlock(List<ScoredDocChunk> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            DocumentChunk c = hits.get(i).chunk();
            sb.append("[Excerpt ").append(i + 1).append("] ");
            sb.append("(file: ").append(c.getFileName());

            // Include structural metadata if it was populated by the PDF extractor
            if (c.getPageNumber() > 0) {
                sb.append(", page ").append(c.getPageNumber());
            }
            if (c.getSectionTitle() != null && !c.getSectionTitle().isBlank()) {
                sb.append(", section: \"").append(c.getSectionTitle()).append('"');
            }
            if (c.getSectionType() != null && !"UNKNOWN".equals(c.getSectionType())) {
                sb.append(", type: ").append(c.getSectionType());
            } else {
                sb.append(", chunk ").append(c.getChunkIndex());
            }
            sb.append(")\n").append(normalizeChunkText(c.getText())).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Normalizes chunk text for LLM readability, fixing concatenated numbers that
     * PDF extraction produces when table cells have no spacing between them.
     *
     * The broken pattern was: ([0-9,\.])([0-9])  — this split INSIDE valid numbers like
     *   "1,049.81" → "1, 049. 81"  and  "313,368.69" → "313, 368. 69"  ← WRONG
     *
     * Root cause of concatenation in PDF tables:
     *   ".81313,368.69"  = end of one number (.81) + start of next (313,...)
     *   ".69265,795.44"  = end of one number (.69) + start of next (265,...)
     *   ".810.00"        = end of one number (.81) + start of next (0.00)
     *   "44-10,049.81"   = end of one number (.44) + start of negative (-10,...)
     *
     * Fix: financial amounts always end with exactly 2 decimal digits (cents).
     * So the ONLY valid split point is: a 2-digit decimal fraction immediately
     * followed by another digit — i.e.  \.\d{2}(?=\d)
     *
     * Examples (all valid numbers stay intact):
     *   "0.00 -10,049.81313,368.69265,795.44 37,523.44"
     *   → "0.00 -10,049.81 313,368.69 265,795.44 37,523.44"
     *
     *   "313,368.69265,795.44 -10,049.810.00 37,523.44"
     *   → "313,368.69 265,795.44 -10,049.81 0.00 37,523.44"
     *
     *   "1,049.81" → "1,049.81"  (unchanged — no concatenation)
     *   "-10,049.81" → "-10,049.81"  (unchanged)
     */
    private String normalizeChunkText(String text) {
        if (text == null || text.isBlank()) return text;

        // Step 1: split concatenated financial numbers at 2-digit decimal boundaries.
        // Pattern: \.  followed by exactly 2 digits,  immediately followed by another digit
        // e.g. ".81313" → ".81 313",  ".69265" → ".69 265",  ".810" → ".81 0"
        String result = text.replaceAll("(\\.\\d{2})(?=\\d)", "$1 ");

        // Step 2: split where a digit is immediately followed by a minus sign + digit,
        // which means a negative number starts right after the previous number.
        // e.g. "37,523.44-10,049.81" → "37,523.44 -10,049.81"
        result = result.replaceAll("(\\d)(?=-\\d)", "$1 ");

        return result;
    }

    /**
     * Builds the document-grounded prompt.
     * Rules are loaded from BusinessRulesForPrompts.json at startup —
     * no hardcoded rules in Java code.
     */
    private String buildDocPrompt(String question, String contextBlock) {
        return docSystemInstruction + "\nRules:\n" + docRulesText + "\n\n" +
               "=== Document Excerpts ===\n" + contextBlock + "\n\n" +
               "=== Question ===\n" + question + "\n\n" +
               "=== Answer ===";
    }

    /**
     * Detects hallucination by checking:
     *  1. The answer contains the "not found" sentinel phrase (LLM admitted it couldn't find it)
     *  2. The answer is blank or very short (< 20 chars) — likely a non-answer
     *  3. None of the retrieved chunk tokens appear in the answer
     *     — checks both long words (> 4 chars) AND numeric tokens (digits/amounts)
     *       so financial answers like "$37,523.44" are never falsely flagged.
     *
     * NOTE: Rule 1 (sentinel check) is intentionally the only hard "hallucination".
     * Rules 2 & 3 only log a warning but do NOT flag as hallucinated — a short or
     * numeric answer from a financial document is often perfectly valid.
     */
    private boolean detectHallucination(String answer, List<ScoredDocChunk> hits) {
        if (answer == null || answer.isBlank()) {
            log.warn("Hallucination check: answer is blank");
            return true;
        }

        // Rule 1 (HARD): LLM returned the "not found" sentinel — true hallucination
        if (answer.contains(NOT_FOUND_SENTINEL)) {
            log.warn("Hallucination: answer contains sentinel '{}'", NOT_FOUND_SENTINEL);
            return true;
        }

        // Rule 2 (SOFT — log only, do NOT mark as hallucinated):
        // Very short answers can be valid for financial docs ("$37,523.44" is 10 chars)
        if (answer.trim().length() < 10) {
            log.warn("Possible hallucination: answer very short ({} chars) — still returning to user",
                    answer.trim().length());
            // Do NOT return true here — return the answer as-is
        }

        // Rule 3 (SOFT — log only, do NOT mark as hallucinated):
        // Check lexical overlap including numeric tokens (important for financial data).
        // Split on whitespace only (not \W+) so "37,523.44" stays intact as a token.
        String answerLower = answer.toLowerCase();
        boolean anyOverlap = hits.stream().anyMatch(h -> {
            String[] tokens = h.chunk().getText().toLowerCase().split("\\s+");
            return Arrays.stream(tokens)
                    .filter(t -> {
                        // Accept long words (>4 chars) OR numeric-looking tokens (contain digits)
                        return t.length() > 4 || t.matches(".*\\d.*");
                    })
                    .anyMatch(t -> {
                        // Strip punctuation wrappers for comparison (e.g. "$37,523.44" → "37,523.44")
                        String stripped = t.replaceAll("[^0-9a-z.,]", "");
                        return !stripped.isEmpty() && answerLower.contains(stripped);
                    });
        });

        if (!anyOverlap) {
            log.warn("Low overlap: answer tokens do not overlap with chunk tokens — " +
                     "may be paraphrased or numeric-only. Returning answer to user without hallucination flag.");
            // SOFT: do NOT return true — let the answer through.
            // Financial docs frequently answer with numbers not literally present as words.
        }

        return false;   // Only Rule 1 (sentinel) causes a hard hallucination block
    }

    /**
     * Persists the doc query/response as a transcript_details record.
     * Packs userPrompt + answer + confidenceLabel into transcript_json.
     * Returns the inserted transcript DB id, or -1 on failure.
     */
    private int persistTranscript(String userName, String question,
                                   DocumentQueryResponse resp,
                                   String status, String remarks) {
        if (userDbService == null) return -1;
        try {
            Integer userId = null;
            Map<String, Object> userRow = userDbService.findUserByUsername(userName);
            if (userRow != null && userRow.get("id") != null) {
                userId = ((Number) userRow.get("id")).intValue();
            }

            String explanation = String.format(
                    "Doc RAG | confidence=%s (%.4f) | chunks=%d | hallucinated=%b | cached=%b",
                    resp.getConfidenceLabel(), resp.getConfidenceScore(),
                    resp.getSourceChunks() != null ? resp.getSourceChunks().size() : 0,
                    resp.isHallucinated(), resp.isFromCache());

            String dataSource = resp.getFileName() != null ? resp.getFileName() : resp.getDocId();

            int transcriptId = userDbService.saveTranscriptEnhanced(
                    userId,
                    question,
                    resp.getAnswer(),
                    explanation,
                    status,
                    remarks,
                    resp.getConfidenceScore(),
                    resp.isHallucinated() ? "HALLUCINATION" : null,
                    dataSource);

            log.info("Doc transcript persisted | id={} | user='{}' | status={}", transcriptId, userName, status);

            // Sync user's transcripts.json after DB write
            if (userName != null && !userName.isBlank()) {
                userDbService.exportUserTranscriptsToJson(userName);
            }

            return transcriptId;

        } catch (Exception e) {
            log.warn("Doc transcript persistence failed for user='{}': {}", userName, e.getMessage());
            return -1;
        }
    }

    /** Fallback when LLM is unavailable — surfaces the top retrieved excerpts. */
    private String buildFallbackAnswer(List<ScoredDocChunk> hits) {
        return "Most relevant content found in the document:\n\n" +
                hits.stream()
                    .limit(3)
                    .map(h -> "• [" + h.chunk().getFileName() + " / chunk "
                            + h.chunk().getChunkIndex() + "]\n  "
                            + h.chunk().getText().substring(
                                    0, Math.min(300, h.chunk().getText().length())))
                    .collect(Collectors.joining("\n\n"));
    }

    private Path saveFile(String userName, MultipartFile file, String originalName) throws IOException {
        String safeUser = UsernameUtil.sanitize(userName);
        Path dir = Paths.get(supportingFilesDir).toAbsolutePath().resolve(safeUser);
        Files.createDirectories(dir);
        Path target = dir.resolve(sanitizeFileName(originalName));
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Saved uploaded file → {}", target);
        return target;
    }

    private String buildDocId(String userName, String fileName) {
        String base = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        return (userName + "_" + base).toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
