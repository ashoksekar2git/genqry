package com.nlp.rag.seek.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for the directory where {DBName}_schema_timestamp.json
 * files are read from and written to.
 *
 * ══════════════════════════════════════════════════════════════════════
 *  RESOLUTION ORDER (per user)
 * ══════════════════════════════════════════════════════════════════════
 *  1. Per-user directory  — supportingFiles/{userName}/
 *  2. genqry.metadata.save-dir property  — global external override.
 *  3. ClassPathResource("metadata")    — default (resolves to
 *     target/classes/metadata when running via spring-boot:run).
 *
 * All three services that touch schema files (DatabaseSchemaExportService,
 * UserMetadataStore, SchemaFileReaderService) inject this bean instead of
 * each independently resolving their own directory.
 */
@Component
public class MetadataDirectoryResolver {

    private static final Logger log = LoggerFactory.getLogger(MetadataDirectoryResolver.class);

    private static final String METADATA_SUBDIR = "metadata";

    @Value("${genqry.metadata.save-dir:}")
    private String externalSaveDir;

    @Value("${genqry.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    /** Default directory — used when no per-user directory is applicable. */
    private Path defaultDir;

    /** Resolved path to the supportingFiles directory. */
    private Path supportingFilesPath;

    /**
     * Tracks the latest schema filename written per database.
     * Key  = lower-case database name
     * Value = filename (e.g. ecommerce_schema_20260307_153045.json)
     */
    private final Map<String, String> latestSchemaFilename = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (externalSaveDir != null && !externalSaveDir.isBlank()) {
            defaultDir = Paths.get(externalSaveDir);
        } else {
            try {
                ClassPathResource res = new ClassPathResource(METADATA_SUBDIR);
                defaultDir = res.exists()
                        ? res.getFile().toPath()
                        : Paths.get("src", "main", "resources", METADATA_SUBDIR);
            } catch (IOException e) {
                defaultDir = Paths.get("src", "main", "resources", METADATA_SUBDIR);
            }
        }
        try {
            Files.createDirectories(defaultDir);
        } catch (IOException e) {
            log.error("Could not create default metadata directory '{}': {}",
                    defaultDir.toAbsolutePath(), e.getMessage());
        }

        // Resolve supportingFiles directory
        supportingFilesPath = Paths.get(supportingFilesDir).toAbsolutePath();
        try {
            Files.createDirectories(supportingFilesPath);
        } catch (IOException e) {
            log.error("Could not create supportingFiles directory '{}': {}",
                    supportingFilesPath, e.getMessage());
        }

        log.info("MetadataDirectoryResolver ready — default dir: {}, supportingFiles: {}",
                defaultDir.toAbsolutePath(), supportingFilesPath);
    }

    // =========================================================================
    // Per-user directory resolution
    // =========================================================================

    /**
     * Resolves the per-user directory: supportingFiles/{sanitizedUserName}/
     * Creates the directory if it does not exist.
     *
     * @param userName  the genQry application user name (will be sanitised)
     * @return absolute path to the user's directory
     */
    public Path resolveUserDir(String userName) {
        String safe = UsernameUtil.sanitize(userName);
        Path userDir = supportingFilesPath.resolve(safe);
        try {
            Files.createDirectories(userDir);
        } catch (IOException e) {
            log.error("Could not create user directory '{}': {}", userDir, e.getMessage());
        }
        return userDir;
    }

    // =========================================================================
    // Directory resolution (fallback for root / bootstrap)
    // =========================================================================

    /**
     * Returns the default directory.
     * Used by the root ecommerce bootstrap where no user context exists.
     */
    public Path resolveDir(String databaseName) {
        return defaultDir;
    }

    /**
     * Returns the full path to the schema JSON file for a given database
     * in the default directory.
     */
    public Path resolveSchemaFile(String databaseName, String fileSuffix) {
        return defaultDir.resolve(databaseName.trim() + fileSuffix);
    }

    /**
     * Returns the default directory (no per-user context).
     */
    public Path getDefaultDir() {
        return defaultDir;
    }


    /**
     * Registers the latest schema filename written for a specific database.
     * Called by DatabaseSchemaExportService after writing the schema file.
     *
     * @param databaseName  the database name (case-insensitive)
     * @param filename      just the filename (e.g. ecommerce_schema_20260307_153045.json)
     */
    public void registerLatestSchemaFilename(String databaseName, String filename) {
        latestSchemaFilename.put(databaseName.trim().toLowerCase(), filename);
        log.info("Latest schema filename registered — database='{}' → file='{}'",
                databaseName, filename);
    }

    /**
     * Returns the latest schema filename registered for the given database,
     * or null when no schema has been written yet this session.
     */
    public String getLatestSchemaFilename(String databaseName) {
        if (databaseName == null) return null;
        return latestSchemaFilename.get(databaseName.trim().toLowerCase());
    }

    /**
     * Returns the path to the supportingFiles directory.
     * Schema files are also copied here so they are always available
     * alongside BusinessRulesForPrompts.json, semantic.json, etc.
     */
    public Path getSupportingFilesDir() {
        return supportingFilesPath;
    }
}
