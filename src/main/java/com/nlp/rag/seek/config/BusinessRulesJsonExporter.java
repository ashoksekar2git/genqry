package com.nlp.rag.seek.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nlp.rag.seek.model.BusinessRule;
import com.nlp.rag.seek.repository.BusinessRulesRepository;
import com.nlp.rag.seek.service.SQLGenerationService;
import com.nlp.rag.seek.service.SQLExplanationService;
import com.nlp.rag.seek.doc.service.DocumentRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Runs at startup AFTER DatabaseMigrationRunner (Order=2).
 * Reads all rules from the business_rules table and writes them
 * to supportingFiles/BusinessRulesForPrompts.json as a flat JSON array.
 *
 * After writing the file, it notifies the pipeline services to reload their
 * rules from the freshly-written JSON.  This is necessary because each
 * service's @PostConstruct runs during bean creation (before any
 * ApplicationRunner), so the file does not exist yet at that point.
 *
 * Format:
 * [
 *   { "number": 1, "enabled": true, "addedBy": "Root", "ruleType": "SQL_GENERAL", "rule": "..." },
 *   ...
 * ]
 *
 * This single file replaces both businessRulesForSQL.json and businessRulesForDocuments.json.
 * All pipeline services (SQLGenerationService, DocumentRagService, SQLExplanationService)
 * read from this file and filter by ruleType + enabled flag.
 */
@Component
@Order(2)
public class BusinessRulesJsonExporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BusinessRulesJsonExporter.class);

    public static final String FILE_NAME = "BusinessRulesForPrompts.json";

    @Autowired
    private BusinessRulesRepository rulesRepo;

    @Autowired(required = false)
    private SQLGenerationService sqlGenerationService;

    @Autowired(required = false)
    private SQLExplanationService sqlExplanationService;

    @Autowired(required = false)
    private DocumentRagService documentRagService;

    @Autowired
    private SecretStore secretStore;

    @Value("${genqry.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void run(ApplicationArguments args) {
        if (secretStore.isSecretsFreeMode() && !secretStore.isInitialized()) {
            log.info("BusinessRulesJsonExporter deferred — secretsfree mode, waiting for bootstrap");
            return;
        }
        try {
            exportRulesToJson();
            notifyServices();
        } catch (Exception e) {
            log.warn("BusinessRulesJsonExporter failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Public so it can be called after rule CRUD operations to regenerate the file.
     */
    public void exportRulesToJson() {
        try {
            List<BusinessRule> allRules = rulesRepo.findAll();
            if (allRules == null || allRules.isEmpty()) {
                log.warn("No business rules found in DB — skipping JSON export");
                return;
            }

            List<Map<String, Object>> jsonRules = new ArrayList<>();
            for (BusinessRule r : allRules) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("number", r.getRuleNumber());
                entry.put("enabled", r.isEnabled());
                entry.put("addedBy", r.getAddedBy() != null ? r.getAddedBy() : "Root");
                entry.put("ruleType", r.getRuleType());
                entry.put("category", r.getCategory());
                entry.put("rule", r.getRuleText());
                jsonRules.add(entry);
            }

            Path dir = Paths.get(supportingFilesDir).toAbsolutePath();
            Files.createDirectories(dir);
            Path filePath = dir.resolve(FILE_NAME);

            mapper.writeValue(filePath.toFile(), jsonRules);

            // Also write to classpath resources location for ClassPathResource access
            Path classpathDir = Paths.get("src/main/resources/supportingFiles").toAbsolutePath();
            if (Files.exists(classpathDir)) {
                Path classpathFile = classpathDir.resolve(FILE_NAME);
                mapper.writeValue(classpathFile.toFile(), jsonRules);
            }

            log.info("BusinessRulesForPrompts.json exported — {} rules → {}",
                    jsonRules.size(), filePath);

        } catch (Exception e) {
            log.error("Failed to export BusinessRulesForPrompts.json: {}", e.getMessage(), e);
        }
    }

    /**
     * Triggers a reload on all pipeline services so they pick up the DB-sourced
     * rules from the freshly-written BusinessRulesForPrompts.json.
     */
    private void notifyServices() {
        try {
            if (sqlGenerationService != null) {
                sqlGenerationService.loadBusinessRules();
                log.info("SQLGenerationService reloaded rules from BusinessRulesForPrompts.json");
            }
        } catch (Exception e) {
            log.warn("SQLGenerationService reload failed: {}", e.getMessage());
        }

        try {
            if (sqlExplanationService != null) {
                sqlExplanationService.loadExplanationRules();
                log.info("SQLExplanationService reloaded rules from BusinessRulesForPrompts.json");
            }
        } catch (Exception e) {
            log.warn("SQLExplanationService reload failed: {}", e.getMessage());
        }

        try {
            if (documentRagService != null) {
                documentRagService.loadDocBusinessRules();
                log.info("DocumentRagService reloaded rules from BusinessRulesForPrompts.json");
            }
        } catch (Exception e) {
            log.warn("DocumentRagService reload failed: {}", e.getMessage());
        }
    }
}

