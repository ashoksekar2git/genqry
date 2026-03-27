package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Service for reading JSON files and using their content as prompts to call the LLM.
 * Logs the LLM response to the console.
 */
@Service
public class JsonLlmPromptService {

    private static final Logger log = LoggerFactory.getLogger(JsonLlmPromptService.class);

    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("sqlChatClient")
    private ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Reads a JSON file and uses its content as a prompt to call the LLM.
     *
     * @param filePath the path to the JSON file
     * @return the LLM response content
     */
    public String processJsonAsPrompt(String filePath) {
        log.info("Processing JSON file: {}", filePath);

        try {
            // Read and parse JSON file
            String jsonContent = readJsonFile(filePath);
            if (jsonContent == null || jsonContent.isBlank()) {
                log.warn("JSON file is empty or null: {}", filePath);
                return "Error: JSON file is empty";
            }

            log.debug("JSON content read successfully ({} characters)", jsonContent.length());

            // Call LLM with JSON content as prompt
            String llmResponse = callLlmWithPrompt(jsonContent);

            // Log response to console
            log.info("========================================");
            log.info("JSON File: {}", filePath);
            log.info("----------------------------------------");
            log.info("Prompt (JSON Content):");
            log.info("{}", jsonContent);
            log.info("----------------------------------------");
            log.info("LLM Response:");
            log.info("{}", llmResponse);
            log.info("========================================");

            return llmResponse;

        } catch (IOException e) {
            log.error("Failed to read JSON file: {}", filePath, e);
            return "Error: Failed to read JSON file - " + e.getMessage();
        } catch (Exception e) {
            log.error("Error processing JSON file: {}", filePath, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Reads a JSON file and uses its prettified content as a prompt to call the LLM.
     * This method formats the JSON for better readability in logs.
     *
     * @param filePath the path to the JSON file
     * @return the LLM response content
     */
    public String processJsonAsPromptPrettified(String filePath) {
        log.info("Processing JSON file (prettified): {}", filePath);

        try {
            // Read and parse JSON file
            String jsonContent = readJsonFileAndPrettify(filePath);
            if (jsonContent == null || jsonContent.isBlank()) {
                log.warn("JSON file is empty or null: {}", filePath);
                return "Error: JSON file is empty";
            }

            log.debug("Prettified JSON content read successfully ({} characters)", jsonContent.length());

            // Call LLM with JSON content as prompt
            String llmResponse = callLlmWithPrompt(jsonContent);

            // Log response to console
            log.info("========================================");
            log.info("JSON File: {}", filePath);
            log.info("----------------------------------------");
            log.info("Prompt (Prettified JSON Content):");
            log.info("{}", jsonContent);
            log.info("----------------------------------------");
            log.info("LLM Response:");
            log.info("{}", llmResponse);
            log.info("========================================");

            return llmResponse;

        } catch (IOException e) {
            log.error("Failed to read JSON file: {}", filePath, e);
            return "Error: Failed to read JSON file - " + e.getMessage();
        } catch (Exception e) {
            log.error("Error processing JSON file: {}", filePath, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Reads a JSON file and uses specific fields as the prompt.
     *
     * @param filePath the path to the JSON file
     * @param fieldName the name of the field to extract as the prompt
     * @return the LLM response content
     */
    public String processJsonFieldAsPrompt(String filePath, String fieldName) {
        log.info("Processing JSON field '{}' from file: {}", fieldName, filePath);

        try {
            // Read and parse JSON file
            String jsonContent = readJsonFile(filePath);
            if (jsonContent == null || jsonContent.isBlank()) {
                log.warn("JSON file is empty or null: {}", filePath);
                return "Error: JSON file is empty";
            }

            // Extract field value
            JsonNode jsonNode = objectMapper.readTree(jsonContent);
            if (!jsonNode.has(fieldName)) {
                log.warn("Field '{}' not found in JSON file: {}", fieldName, filePath);
                return "Error: Field '" + fieldName + "' not found in JSON";
            }

            String fieldValue = jsonNode.get(fieldName).asText();
            if (fieldValue == null || fieldValue.isBlank()) {
                log.warn("Field '{}' is empty in JSON file: {}", fieldName, filePath);
                return "Error: Field '" + fieldName + "' is empty";
            }

            log.debug("Field '{}' extracted successfully ({} characters)", fieldName, fieldValue.length());

            // Call LLM with field value as prompt
            String llmResponse = callLlmWithPrompt(fieldValue);

            // Log response to console
            log.info("========================================");
            log.info("JSON File: {}", filePath);
            log.info("Field Name: {}", fieldName);
            log.info("----------------------------------------");
            log.info("Prompt (Field Value):");
            log.info("{}", fieldValue);
            log.info("----------------------------------------");
            log.info("LLM Response:");
            log.info("{}", llmResponse);
            log.info("========================================");

            return llmResponse;

        } catch (IOException e) {
            log.error("Failed to read JSON file: {}", filePath, e);
            return "Error: Failed to read JSON file - " + e.getMessage();
        } catch (Exception e) {
            log.error("Error processing JSON field: {}", fieldName, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Reads a text file and uses its content as a prompt to call the LLM.
     *
     * @param filePath the path to the text file
     * @return the LLM response content
     */
    public String processTextFileAsPrompt(String filePath) {
        log.info("Processing text file: {}", filePath);

        try {
            // Read text file
            String textContent = readTextFile(filePath);
            if (textContent == null || textContent.isBlank()) {
                log.warn("Text file is empty or null: {}", filePath);
                return "Error: Text file is empty";
            }

            log.debug("Text content read successfully ({} characters)", textContent.length());

            // Call LLM with text content as prompt
            String llmResponse = callLlmWithPrompt(textContent);

            // Log response to console
            log.info("========================================");
            log.info("Text File: {}", filePath);
            log.info("----------------------------------------");
            log.info("Prompt (Text File Content):");
            log.info("{}", textContent);
            log.info("----------------------------------------");
            log.info("LLM Response:");
            log.info("{}", llmResponse);
            log.info("========================================");

            return llmResponse;

        } catch (IOException e) {
            log.error("Failed to read text file: {}", filePath, e);
            return "Error: Failed to read text file - " + e.getMessage();
        } catch (Exception e) {
            log.error("Error processing text file: {}", filePath, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Calls the LLM with the given prompt and returns the response.
     *
     * @param prompt the prompt to send to the LLM
     * @return the LLM response content
     */
    private String callLlmWithPrompt(String prompt) {
        if (chatClient == null) {
            log.warn("ChatClient is not available. LLM call cannot be made.");
            return "Error: ChatClient is not configured";
        }

        try {
            log.debug("Calling LLM with prompt ({} characters)", prompt.length());
            String response = chatClient.prompt(new Prompt(new UserMessage(prompt))).call().content();
            log.debug("LLM response received successfully ({} characters)", response.length());
            return response.trim();
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            return "Error: LLM call failed - " + e.getMessage();
        }
    }

    /**
     * Reads the contents of a JSON file as a string.
     *
     * @param filePath the path to the JSON file
     * @return the file contents as a string
     * @throws IOException if the file cannot be read
     */
    private String readJsonFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            log.error("JSON file not found: {}", filePath);
            throw new IOException("File not found: " + filePath);
        }

        if (!Files.isReadable(path)) {
            log.error("JSON file is not readable: {}", filePath);
            throw new IOException("File is not readable: " + filePath);
        }

        String content = Files.readString(path);
        log.debug("JSON file read successfully: {} ({} bytes)", filePath, content.length());
        return content;
    }

    /**
     * Reads a JSON file and returns it in prettified format.
     *
     * @param filePath the path to the JSON file
     * @return the prettified JSON content
     * @throws IOException if the file cannot be read
     */
    private String readJsonFileAndPrettify(String filePath) throws IOException {
        String jsonContent = readJsonFile(filePath);
        JsonNode jsonNode = objectMapper.readTree(jsonContent);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
    }

    /**
     * Reads the contents of a text file as a string.
     *
     * @param filePath the path to the text file
     * @return the file contents as a string
     * @throws IOException if the file cannot be read
     */
    private String readTextFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            log.error("Text file not found: {}", filePath);
            throw new IOException("File not found: " + filePath);
        }

        if (!Files.isReadable(path)) {
            log.error("Text file is not readable: {}", filePath);
            throw new IOException("File is not readable: " + filePath);
        }

        String content = Files.readString(path);
        log.debug("Text file read successfully: {} ({} bytes)", filePath, content.length());
        return content;
    }

    /**
     * Processes multiple JSON files and returns aggregated results.
     *
     * @param filePaths list of file paths to process
     * @return a string containing all responses
     */
    public String processMultipleJsonFiles(java.util.List<String> filePaths) {
        StringBuilder allResponses = new StringBuilder();
        allResponses.append("\n");
        allResponses.append("=".repeat(50)).append("\n");
        allResponses.append("Processing ").append(filePaths.size()).append(" JSON files\n");
        allResponses.append("=".repeat(50)).append("\n");

        for (int i = 0; i < filePaths.size(); i++) {
            String filePath = filePaths.get(i);
            log.info("[{}/{}] Processing: {}", i + 1, filePaths.size(), filePath);

            String response = processJsonAsPrompt(filePath);
            allResponses.append("\n[File ").append(i + 1).append("]: ").append(filePath).append("\n");
            allResponses.append("Response: ").append(response).append("\n");
            allResponses.append("-".repeat(50)).append("\n");
        }

        allResponses.append("=".repeat(50)).append("\n");
        log.info("All JSON files processed successfully");
        return allResponses.toString();
    }

}

