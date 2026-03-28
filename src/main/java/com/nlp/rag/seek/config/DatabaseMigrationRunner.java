package com.nlp.rag.seek.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs DDL migrations against the seek database at startup (order=1, first thing).
 * Uses IF NOT EXISTS / IF NOT EXISTS so it is fully idempotent.
 *
 * <p>In <b>cloud mode</b> (when {@link SecretStore} is not yet initialized),
 * the automatic startup run is skipped. Call {@link #runMigrations()} manually
 * from the bootstrap flow after DB credentials become available.</p>
 */
@Component
@Order(1)
public class DatabaseMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);

    private final JdbcTemplate jdbc;
    private final SecretStore secretStore;

    public DatabaseMigrationRunner(@Qualifier("primaryJdbcTemplate") JdbcTemplate jdbc,
                                    SecretStore secretStore) {
        this.jdbc = jdbc;
        this.secretStore = secretStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (secretStore.isCloudMode() && !secretStore.isInitialized()) {
            log.info("DB migrations deferred — cloud mode, waiting for bootstrap");
            return;
        }
        runMigrations();
    }

    /**
     * Public entry point so the bootstrap service can trigger migrations
     * after the datasource becomes available.
     */
    public void runMigrations() {
        log.info("Running seek DB migrations…");
        try {
            runAll();
            log.info("seek DB migrations complete ✅");
        } catch (Exception e) {
            log.warn("DB migration warning (non-fatal): {}", e.getMessage());
        }
    }

    private void runAll() {
        // ── users: add columns if missing ────────────────────────────────────
        execSilent("ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name      VARCHAR(200)");
        execSilent("ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at     TIMESTAMPTZ");
        execSilent("ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_ip     INET");
        execSilent("ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_device VARCHAR(200)");

        // Fix TIME → TIMESTAMPTZ columns (safe USING clause handles NULLs)
        alterColumnToTimestamptz("token_expires_at");
        alterColumnToTimestamptz("registered_at");
        alterColumnToTimestamptz("verified_at");
        alterColumnToTimestamptz("updated_at");
        alterColumnToTimestamptz("created_at");

        // ── webauthn_credentials: add columns if missing ──────────────────────
        execSilent("ALTER TABLE webauthn_credentials ADD COLUMN IF NOT EXISTS friendly_name           VARCHAR(200)");
        execSilent("ALTER TABLE webauthn_credentials ADD COLUMN IF NOT EXISTS device_type             VARCHAR(50)");
        execSilent("ALTER TABLE webauthn_credentials ADD COLUMN IF NOT EXISTS transports              TEXT");
        execSilent("ALTER TABLE webauthn_credentials ADD COLUMN IF NOT EXISTS last_used_at            TIMESTAMPTZ");
        execSilent("ALTER TABLE webauthn_credentials ADD COLUMN IF NOT EXISTS attestation_object      TEXT");
        execSilent("ALTER TABLE webauthn_credentials ADD COLUMN IF NOT EXISTS client_data_json        TEXT");
        execSilent("ALTER TABLE webauthn_credentials ADD COLUMN IF NOT EXISTS backed_up               BOOLEAN DEFAULT FALSE");
        execSilent("ALTER TABLE webauthn_credentials ADD COLUMN IF NOT EXISTS registered_at           TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
        execSilent("ALTER TABLE webauthn_credentials ADD COLUMN IF NOT EXISTS last_authenticator_data TEXT");
        execSilent("ALTER TABLE webauthn_credentials ADD COLUMN IF NOT EXISTS credential_id_b64       TEXT");
        execSilent("CREATE UNIQUE INDEX IF NOT EXISTS idx_webauthn_cred_id_b64 ON webauthn_credentials(credential_id_b64) WHERE credential_id_b64 IS NOT NULL");

        // ── schema_details ────────────────────────────────────────────────────
        exec("""
            CREATE TABLE IF NOT EXISTS schema_details (
              id          SERIAL       PRIMARY KEY,
              user_id     INT          REFERENCES users(id) ON DELETE CASCADE,
              db_name     VARCHAR(200) NOT NULL,
              schema_json JSONB        NOT NULL,
              file_path   TEXT,
              created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        execSilent("CREATE INDEX IF NOT EXISTS idx_schema_details_user_id ON schema_details(user_id)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_schema_details_db_name ON schema_details(db_name)");

        // ── transcript_details ────────────────────────────────────────────────
        exec("""
            CREATE TABLE IF NOT EXISTS transcript_details (
              id              SERIAL      PRIMARY KEY,
              user_id         INT         REFERENCES users(id) ON DELETE SET NULL,
              session_id      VARCHAR(255),
              user_prompt     TEXT        NOT NULL,
              generated_sql   TEXT,
              explanation     TEXT,
              is_cached       BOOLEAN     DEFAULT FALSE,
              execution_ms    INT,
              transcript_json JSONB,
              created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        execSilent("CREATE INDEX IF NOT EXISTS idx_transcript_user_id ON transcript_details(user_id)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_transcript_session  ON transcript_details(session_id)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_transcript_created  ON transcript_details(created_at)");

        // ── business_rules ────────────────────────────────────────────────────
        exec("""
            CREATE TABLE IF NOT EXISTS business_rules (
              id          SERIAL       PRIMARY KEY,
              user_id     INT          REFERENCES users(id) ON DELETE SET NULL,
              rule_type   VARCHAR(20)  NOT NULL
                              CHECK (rule_type IN ('SQL_GENERAL','SQL_EAV','RETRY', 'EXPLANATION', 'DOCUMENT', 'EAV_RETRY')),
              rule_number INT          NOT NULL,
              rule_text   TEXT         NOT NULL,
              added_by    VARCHAR(100) DEFAULT 'admin',
              category    VARCHAR(255),
              enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
              created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at  TIMESTAMPTZ           DEFAULT CURRENT_TIMESTAMP
            )
            """);
        execSilent("CREATE INDEX IF NOT EXISTS idx_business_rules_type_enabled ON business_rules(rule_type, enabled)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_business_rules_user_id      ON business_rules(user_id)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_business_rules_category  ON business_rules(category)");
        seedBusinessRules();

        // ── Ensure DATA_NOT_AVAILABLE rule exists for existing databases ───────
        //ensureDataNotAvailableRule();
    }

    /**
     * Inserts the DATA_NOT_AVAILABLE SQL_GENERAL rule if it does not already exist.
     * This covers databases that were seeded before this rule was added.
     * Idempotent — safe to call on every startup.
     */
//    private void ensureDataNotAvailableRule() {
//        String ruleText = "If the user's question refers to a concept, entity, or table that does NOT exist "
//                + "anywhere in the provided schema context, do NOT guess or map it to the closest table. "
//                + "Instead respond with exactly: DATA_NOT_AVAILABLE: The database schema does not contain "
//                + "a table or concept matching '<user term>'. Available tables are: <list the table names "
//                + "from the schema context>.";
//        try {
//            Integer exists = jdbc.queryForObject(
//                    "SELECT COUNT(*) FROM business_rules WHERE rule_type = 'SQL_GENERAL' AND rule_text LIKE '%DATA_NOT_AVAILABLE%'",
//                    Integer.class);
//            if (exists != null && exists > 0) {
//                log.debug("DATA_NOT_AVAILABLE rule already exists — skipping");
//                return;
//            }
//            // Find the next rule_number for SQL_GENERAL
//            Integer maxNum = jdbc.queryForObject(
//                    "SELECT COALESCE(MAX(rule_number), -1) FROM business_rules WHERE rule_type = 'SQL_GENERAL'",
//                    Integer.class);
//            int nextNum = (maxNum != null ? maxNum : -1) + 1;
//            jdbc.update(
//                    "INSERT INTO business_rules (user_id, rule_type, rule_number, rule_text, added_by, enabled) "
//                            + "VALUES (1000, 'SQL_GENERAL', ?, ?, 'Root', TRUE)",
//                    nextNum, ruleText);
//            log.info("Inserted DATA_NOT_AVAILABLE rule as SQL_GENERAL #{}", nextNum);
//        } catch (Exception e) {
//            log.debug("ensureDataNotAvailableRule skipped: {}", e.getMessage());
//        }
//    }

    // ── business rules seed ───────────────────────────────────────────────────

    /**
     * Inserts default rules into the business_rules table if it is empty.
     * Safe to call on every startup — skips entirely when rows already exist.
     */
    private void seedBusinessRules() {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM business_rules", Integer.class);
            if (count != null && count > 0) {
                log.debug("business_rules table already seeded ({} rows) — skipping", count);
                return;
            }
        } catch (Exception e) {
            log.debug("Could not count business_rules rows: {}", e.getMessage());
            return;
        }

        log.info("Seeding business_rules table with default rules…");


        Object[][] rulesHeaderA = {
                {0,  "You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query.."},
                {1,  "Use ONLY the tables and columns that appear in the schema context below."},
                {2,  "Write standard SQL compatible with PostgreSQL."},
                {3,  "Use explicit JOINs with ON clauses — never implicit comma joins."},
                {4,  "Add a WHERE clause whenever a filter is implied."},
                {5,  "Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate."},
                {6,  "Add ORDER BY when a ranking or 'top N' is implied."},
                {7,  "Return ONLY the SQL statement — no prose, no markdown fences."},
                {8,  "CASE-INSENSITIVE COMPARISONS: When comparing string/varchar/text values in WHERE clauses, always use ILIKE instead of = to handle case differences. For example: WHERE column ILIKE 'value' instead of WHERE column = 'value'. This applies to all string filters including EAV attribute values."},
                {9,  "CRITICAL JOIN RULE — if two tables does not have foreign key relationship then do not join them"},
                {10,  "End the sql statement with a semicolon."}
        };



        for (Object[] r : rulesHeaderA) {
            execSilent(String.format(
                    "INSERT INTO business_rules (user_id,rule_type, rule_number, rule_text, added_by, category, enabled) " +
                            "VALUES (1000, 'SQL_GENERAL', %d, '%s', 'Root', 'A', TRUE)",
                    r[0], ((String) r[1]).replace("'", "''")));
        }

//        Object[][] rulesHeaderB = {
//                {0,  "You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query.."},
//                {1,  "DIALECT: Write standard SQL compatible with PostgreSQL."},
//                {2,  "SCHEMA FIDELITY: Use ONLY the tables and columns that appear in the schema context below. ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question. If the user's question cannot be answered using these tables and columns, follow Rule 9."},
//                {3,  "JOIN SYNTAX: Use explicit JOINs with ON clauses — never implicit comma joins."},
//                {4,  "JOIN CONSTRAINT: Only JOIN tables that have a direct or transitive foreign key relationship as defined in the schema context. If the user's question requires data from two tables that have no foreign key path between them (direct or through intermediate tables), do NOT fabricate a join — instead follow Rule 9.\n"},
//                {5,  "FILTERING: Add a WHERE clause whenever a filter is implied by the user's question."},
//                {6,  "AGGREGATION: Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) with correct GROUP BY clauses when appropriate."},
//                {7,  "ORDERING: Add ORDER BY when a ranking, sorting, or 'top N' result is implied."},
//                {8,  "CASE-INSENSITIVE COMPARISONS: When comparing string/varchar/text values in WHERE clauses, always use ILIKE instead of = to handle case differences. For example: WHERE column ILIKE 'value' instead of WHERE column = 'value'. This applies to all string filters including EAV attribute values.\n"},
//                {9, "DATA NOT AVAILABLE: If the user's question refers to a concept, entity, or table that does NOT exist anywhere in the provided schema context, OR if answering the question would require joining tables with no foreign key path between them, do NOT guess or map it to the closest match. Instead respond with exactly:\n" +
//                        "   DATA_NOT_AVAILABLE: The database schema does not contain a table or concept matching '<user term>'. Available tables are: <list the table names from the schema context>.\n"},
//                {10,  "RESPONSE FORMAT: Return ONLY the SQL statement — no prose, no markdown fences, no explanation. The sole exception is Rule 9: when the schema cannot answer the question, return the DATA_NOT_AVAILABLE message instead of SQL.\n"},
//                {11,  "SEMICOLON: End the SQL statement with a semicolon."}
//        };
//
//        for (Object[] r : rulesHeaderB) {
//            execSilent(String.format(
//                    "INSERT INTO business_rules (user_id,rule_type, rule_number, rule_text, added_by, category, enabled) " +
//                            "VALUES (1000, 'SQL_GENERAL', %d, '%s', 'Root', 'B', TRUE)",
//                    r[0], ((String) r[1]).replace("'", "''")));
//        }

        // SQL_GENERAL
//        Object[][] generalRules = {
//                {0,  "Follow the below rules for EAV tables:"},
//            {1,  "NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names."},
//            {2,  "Use WHERE <attr_name_col> = '<key>' to select a specific attribute."},
//            {3,  "Use CAST(<attr_value_col> AS <type>) when reading numeric or date values."},
//            {4,  "For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>"},
//            {5,  "Join to the entity table via the entity_id column shown in the context."},
//            {6,  "EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly."},
//            {7,  "Attribute names are stored as ROW VALUES in the attribute name column."},
//            {8, "Join the entity table to the EAV data table on the entity ID column."},
//            {9,  "Join the EAV data table to the attributes table on the attribute ID column."}
//        };
//        for (Object[] r : generalRules) {
//            execSilent(String.format(
//                "INSERT INTO business_rules (user_id,rule_type, rule_number, rule_text, added_by,  category, enabled) " +
//                "VALUES (1000, 'SQL_EAV', %d, '%s', 'Root', 'A' TRUE)",
//                r[0], ((String) r[1]).replace("'", "''")));
//        }

        // SQL_EAV
//        Object[][] eavRules = {
//            {0, "CORRECTION REQUIRED: Your previous SQL response hallucinated table or column names that do NOT exist in the database schema so follow these additional rules for EAV tables:"}
//        };
//        for (Object[] r : eavRules) {
//            execSilent(String.format(
//                "INSERT INTO business_rules (user_id,rule_type, rule_number, rule_text, added_by,  category, enabled) " +
//                "VALUES (1000, 'RETRY', %d, '%s', 'Root','A', TRUE)",
//                r[0], ((String) r[1]).replace("'", "''")));
//        }

        // DOCUMENT
        Object[][] docRules = {
            {0, "Answer ONLY using the information present in the provided excerpts. Do not use any external knowledge."},
            {1, "If the answer is genuinely not present anywhere in the excerpts, respond with exactly: \"I could not find relevant information in the provided document."},
            {2, "Cite the excerpt number (e.g. [Excerpt 2]) and file name when referencing specific content"},
            {3, "Summarize and synthesize across multiple excerpts when needed."},
            {4, "Maintain a professional, concise, and objective tone."},
            {5, "Be concise and factual. Report exact dates, dollar amounts as they appear in the excerpts."}
        };
        for (Object[] r : docRules) {
            execSilent(String.format(
                "INSERT INTO business_rules (user_id,rule_type, rule_number, rule_text, added_by,  category, enabled) " +
                "VALUES (1000, 'DOCUMENT', %d, '%s', 'Root', 'A' TRUE)",
                r[0], ((String) r[1]).replace("'", "''")));
        }

        // EAV_RETRY
        Object[][] retryRules = {
            {0, "You are a helpful data analyst. Given the SQL query below, write a concise,\n" +
                    "plain-English explanation (2–4 sentences) describing:"},
            {1, "Which data is being retrieved or modified."},
            {2, "What filters or conditions apply."},
            {3, "How the results are ordered or grouped (if any)."},
            {4, "The business purpose this query is likely serving."},
            {5, "Do NOT include the SQL in your answer. Use simple language a non-technical\n" +
                    "business user would understand."}
        };
        for (Object[] r : retryRules) {
            execSilent(String.format(
                "INSERT INTO business_rules (user_id,rule_type, rule_number, rule_text, added_by,  category, enabled) " +
                "VALUES (1000, 'EXPLANATION', %d, '%s', 'Root', 'A', TRUE)",
                r[0], ((String) r[1]).replace("'", "''")));
        }

//        log.info("business_rules seeded: {} SQL_GENERAL, {} SQL_EAV, {} DOCUMENT, {} EAV_RETRY",
//                generalRules.length, eavRules.length, docRules.length, retryRules.length);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void exec(String sql) {
        jdbc.execute(sql.trim());
    }

    /** Execute without throwing — used for ADD COLUMN IF NOT EXISTS which may warn on re-run. */
    private void execSilent(String sql) {
        try { jdbc.execute(sql.trim()); }
        catch (Exception e) { log.debug("Migration step (skipped): {}", e.getMessage()); }
    }

    /**
     * Safely converts a TIME WITH TIME ZONE column to TIMESTAMPTZ.
     * Skips if the column is already TIMESTAMPTZ or doesn't exist.
     */
    private void alterColumnToTimestamptz(String column) {
        try {
            String dataType = jdbc.queryForObject(
                """
                SELECT data_type FROM information_schema.columns
                 WHERE table_schema='public' AND table_name='users' AND column_name=?
                """,
                String.class, column);
            if (dataType == null) return;
            if (dataType.contains("timestamp")) return; // already correct
            // TIME → TIMESTAMPTZ
            execSilent("ALTER TABLE users ALTER COLUMN " + column +
                " TYPE TIMESTAMPTZ USING (CURRENT_DATE + " + column + ")");
        } catch (Exception e) {
            log.debug("alterColumnToTimestamptz '{}': {}", column, e.getMessage());
        }
    }
}

