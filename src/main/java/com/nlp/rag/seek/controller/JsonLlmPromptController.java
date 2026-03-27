package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.service.JsonLlmPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for processing JSON and text files as LLM prompts.
 *
 * Endpoints:
 *   POST /api/v1/llm/prompt/text       - Process a single text file
 *   POST /api/v1/llm/prompt/json       - Process a single JSON file
 *   POST /api/v1/llm/prompt/json-field - Extract and process a specific JSON field
 *   POST /api/v1/llm/prompt/multiple   - Process multiple JSON files
 *
 * All responses include the LLM response content and are logged to console.
 */
@RestController
@RequestMapping("/api/v1/llm/prompt")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "LLM Prompt", description = "Direct LLM prompt invocation from file")
public class JsonLlmPromptController {

    private static final Logger logger = LoggerFactory.getLogger(JsonLlmPromptController.class);

    @Autowired
    private JsonLlmPromptService jsonLlmPromptService;

    /**
     * POST /api/v1/llm/prompt/text
     *
     * Reads a text file and uses its content as a prompt to call the LLM.
     * The LLM response is logged to console and returned in the response.
     *
     * Request body:
     * {
     *   "filePath": "/path/to/file.txt"
     * }
     *
     * Response:
     * {
     *   "success": true,
     *   "filePath": "/path/to/file.txt",
     *   "fileType": "TEXT",
     *   "llmResponse": "LLM response content...",
     *   "message": "Text file processed successfully"
     * }
     */
    @PostMapping("/text")
    public ResponseEntity<Map<String, Object>> processTextFileAsPrompt(
            @RequestBody TextFileRequest request) {

        logger.info("Received request to process text file: {}", request.getFilePath());

        if (request.getFilePath() == null || request.getFilePath().isBlank()) {
            logger.warn("Invalid file path provided");
            return ResponseEntity.badRequest().body(createErrorResponse(
                    "Invalid file path provided"
            ));
        }

        try {
            String response = jsonLlmPromptService.processTextFileAsPrompt(request.getFilePath());

            logger.info("Text file processed successfully");
            return ResponseEntity.ok(createSuccessResponse(
                    request.getFilePath(),
                    "TEXT",
                    response,
                    "Text file processed successfully"
            ));

        } catch (Exception e) {
            logger.error("Error processing text file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    createErrorResponse("Error processing text file: " + e.getMessage())
            );
        }
    }

    /**
     * POST /api/v1/llm/prompt/json
     *
     * Reads a JSON file and uses its content as a prompt to call the LLM.
     * The LLM response is logged to console and returned in the response.
     *
     * Request body:
     * {
     *   "filePath": "/path/to/file.json",
     *   "prettify": false
     * }
     *
     * Response:
     * {
     *   "success": true,
     *   "filePath": "/path/to/file.json",
     *   "fileType": "JSON",
     *   "llmResponse": "LLM response content...",
     *   "message": "JSON file processed successfully"
     * }
     */
    @PostMapping("/json")
    public ResponseEntity<Map<String, Object>> processJsonFileAsPrompt(
            @RequestBody JsonFileRequest request) {

        logger.info("Received request to process JSON file: {}", request.getFilePath());

        if (request.getFilePath() == null || request.getFilePath().isBlank()) {
            logger.warn("Invalid file path provided");
            return ResponseEntity.badRequest().body(createErrorResponse(
                    "Invalid file path provided"
            ));
        }

        try {
            String response;

            if (request.isPrettify()) {
                logger.info("Processing JSON file with prettification enabled");
                response = jsonLlmPromptService.processJsonAsPromptPrettified(request.getFilePath());
            } else {
                logger.info("Processing JSON file as-is");
                response = jsonLlmPromptService.processJsonAsPrompt(request.getFilePath());
            }

            logger.info("JSON file processed successfully");
            return ResponseEntity.ok(createSuccessResponse(
                    request.getFilePath(),
                    "JSON",
                    response,
                    "JSON file processed successfully"
            ));

        } catch (Exception e) {
            logger.error("Error processing JSON file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    createErrorResponse("Error processing JSON file: " + e.getMessage())
            );
        }
    }

    /**
     * POST /api/v1/llm/prompt/json-field
     *
     * Extracts a specific field from a JSON file and uses it as a prompt to call the LLM.
     * The LLM response is logged to console and returned in the response.
     *
     * Request body:
     * {
     *   "filePath": "/path/to/file.json",
     *   "fieldName": "prompt_text"
     * }
     *
     * Response:
     * {
     *   "success": true,
     *   "filePath": "/path/to/file.json",
     *   "fieldName": "prompt_text",
     *   "fileType": "JSON_FIELD",
     *   "llmResponse": "LLM response content...",
     *   "message": "JSON field processed successfully"
     * }
     */
    @PostMapping("/json-field")
    public ResponseEntity<Map<String, Object>> processJsonFieldAsPrompt(
            @RequestBody JsonFieldRequest request) {

        logger.info("Received request to process JSON field '{}' from file: {}",
                request.getFieldName(), request.getFilePath());

        if (request.getFilePath() == null || request.getFilePath().isBlank()) {
            logger.warn("Invalid file path provided");
            return ResponseEntity.badRequest().body(createErrorResponse(
                    "Invalid file path provided"
            ));
        }

        if (request.getFieldName() == null || request.getFieldName().isBlank()) {
            logger.warn("Invalid field name provided");
            return ResponseEntity.badRequest().body(createErrorResponse(
                    "Invalid field name provided"
            ));
        }

        try {
            String response = jsonLlmPromptService.processJsonFieldAsPrompt(
                    request.getFilePath(),
                    request.getFieldName()
            );

            logger.info("JSON field processed successfully");

            Map<String, Object> responseMap = createSuccessResponse(
                    request.getFilePath(),
                    "JSON_FIELD",
                    response,
                    "JSON field processed successfully"
            );
            responseMap.put("fieldName", request.getFieldName());

            return ResponseEntity.ok(responseMap);

        } catch (Exception e) {
            logger.error("Error processing JSON field: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    createErrorResponse("Error processing JSON field: " + e.getMessage())
            );
        }
    }

    /**
     * POST /api/v1/llm/prompt/multiple
     *
     * Processes multiple JSON files and returns aggregated results.
     * Each file's content is used as a separate prompt to the LLM.
     * All responses are logged to console.
     *
     * Request body:
     * {
     *   "filePaths": ["/path/to/file1.json", "/path/to/file2.json"]
     * }
     *
     * Response:
     * {
     *   "success": true,
     *   "fileCount": 2,
     *   "filePaths": ["/path/to/file1.json", "/path/to/file2.json"],
     *   "llmResponse": "Aggregated responses...",
     *   "message": "Multiple JSON files processed successfully"
     * }
     */
    @PostMapping("/multiple")
    public ResponseEntity<Map<String, Object>> processMultipleJsonFiles(
            @RequestBody MultipleFilesRequest request) {

        logger.info("Received request to process {} JSON files", request.getFilePaths().size());

        if (request.getFilePaths() == null || request.getFilePaths().isEmpty()) {
            logger.warn("No file paths provided");
            return ResponseEntity.badRequest().body(createErrorResponse(
                    "No file paths provided"
            ));
        }

        try {
            String response = jsonLlmPromptService.processMultipleJsonFiles(request.getFilePaths());

            logger.info("Multiple JSON files processed successfully");

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("success", true);
            responseMap.put("fileCount", request.getFilePaths().size());
            responseMap.put("filePaths", request.getFilePaths());
            responseMap.put("fileType", "MULTIPLE_JSON");
            responseMap.put("llmResponse", response);
            responseMap.put("message", "Multiple JSON files processed successfully");

            return ResponseEntity.ok(responseMap);

        } catch (Exception e) {
            logger.error("Error processing multiple JSON files: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    createErrorResponse("Error processing multiple JSON files: " + e.getMessage())
            );
        }
    }

    // ── Helper Methods ─────────────────────────────────────────────────────────

    /**
     * Creates a success response map.
     */
    private Map<String, Object> createSuccessResponse(String filePath, String fileType,
                                                       String llmResponse, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("filePath", filePath);
        response.put("fileType", fileType);
        response.put("llmResponse", llmResponse);
        response.put("message", message);
        return response;
    }

    /**
     * Creates an error response map.
     */
    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }

    // ── Request DTOs ───────────────────────────────────────────────────────────

    /**
     * Request body for text file processing.
     */
    public static class TextFileRequest {
        private String filePath;

        public TextFileRequest() {}

        public TextFileRequest(String filePath) {
            this.filePath = filePath;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }
    }

    /**
     * Request body for JSON file processing.
     */
    public static class JsonFileRequest {
        private String filePath;
        private boolean prettify;

        public JsonFileRequest() {}

        public JsonFileRequest(String filePath, boolean prettify) {
            this.filePath = filePath;
            this.prettify = prettify;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public boolean isPrettify() {
            return prettify;
        }

        public void setPrettify(boolean prettify) {
            this.prettify = prettify;
        }
    }

    /**
     * Request body for JSON field extraction and processing.
     */
    public static class JsonFieldRequest {
        private String filePath;
        private String fieldName;

        public JsonFieldRequest() {}

        public JsonFieldRequest(String filePath, String fieldName) {
            this.filePath = filePath;
            this.fieldName = fieldName;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public String getFieldName() {
            return fieldName;
        }

        public void setFieldName(String fieldName) {
            this.fieldName = fieldName;
        }
    }

    /**
     * Request body for processing multiple JSON files.
     */
    public static class MultipleFilesRequest {
        private List<String> filePaths;

        public MultipleFilesRequest() {}

        public MultipleFilesRequest(List<String> filePaths) {
            this.filePaths = filePaths;
        }

        public List<String> getFilePaths() {
            return filePaths;
        }

        public void setFilePaths(List<String> filePaths) {
            this.filePaths = filePaths;
        }
    }
}
