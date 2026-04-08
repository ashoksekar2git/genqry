package com.nlp.rag.seek.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JDBC repository for the genQry database user tables:
 *   users, webauthn_credentials, user_activity_logs,
 *   schema_details, transcript_details
 *
 * Uses the primary (genQry) datasource exclusively.
 */
@Repository
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserRepository(@Qualifier("primaryJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =========================================================================
    // users table
    // =========================================================================

    /**
     * Inserts a new user row. Returns the generated id, or -1 on failure.
     *
     * @param userName        sanitised username or ip_timestamp for temp users
     * @param email           email address (unique)
     * @param passwordHash    Fernet-encrypted password bytes (null for guest/passkey-only)
     * @param userType        "Registered" | "Temp" | "Passkey"
     * @param accountStatus   "Active" | "Pending" | "Locked"
     * @param verificationToken  UUID token for email verification (null for Temp)
     * @param tokenExpiresAt  token expiry (null for Temp)
     * @param selectedDb      ignored — kept for call-site compatibility only
     * @param displayName     full display name
     * @return generated user id
     */
    public int insertUser(String userName, String email, byte[] passwordHash,
                          String userType, String accountStatus,
                          String verificationToken, Instant tokenExpiresAt,
                          String selectedDb, String displayName) {
        String sql = """
            INSERT INTO users
              (user_name, email, password_hash, user_type, account_status,
               verification_token, token_expires_at, display_name,
               is_verified, created_at, updated_at)
            VALUES (?,?,?,?,?, ?,?,?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (email) DO NOTHING
            RETURNING id
            """;
        try {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, userName);
                ps.setString(2, email);
                ps.setBytes(3, passwordHash);
                ps.setString(4, userType);
                ps.setString(5, accountStatus);
                ps.setString(6, verificationToken);
                ps.setTimestamp(7, tokenExpiresAt != null ? Timestamp.from(tokenExpiresAt) : null);
                ps.setString(8, displayName);
                ps.setBoolean(9, "Temp".equals(userType));
                return ps;
            }, kh);
            Number key = kh.getKey();
            int id = key != null ? key.intValue() : -1;
            log.info("User inserted: id={} username='{}' email='{}' type='{}'",
                    id, userName, email, userType);
            return id;
        } catch (Exception e) {
            log.error("Failed to insert user '{}': {}", email, e.getMessage());
            return -1;
        }
    }

    /**
     * Looks up a user by email. Returns null if not found.
     */
    public Map<String, Object> findByEmail(String email) {
        try {
            return jdbc.queryForMap(
                "SELECT * FROM users WHERE LOWER(email) = LOWER(?)", email);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.error("findByEmail error for '{}': {}", email, e.getMessage());
            return null;
        }
    }

    /**
     * Looks up an existing Temp/guest user whose user_name exactly matches the
     * sanitised IP address (e.g. "192_168_1_1").
     * Returns the row, or null if no previous guest session exists.
     */
    public Map<String, Object> findGuestByIp(String safeIp) {
        try {
            return jdbc.queryForMap(
                "SELECT * FROM users WHERE user_type = 'Temp' AND user_name = ? LIMIT 1",
                safeIp);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.warn("findGuestByIp error for safeIp='{}': {}", safeIp, e.getMessage());
            return null;
        }
    }

    /**
     * Looks up a user by username. Returns null if not found.
     */
    public Map<String, Object> findByUsername(String username) {
        try {
            return jdbc.queryForMap(
                "SELECT * FROM users WHERE LOWER(user_name) = LOWER(?)", username);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.error("findByUsername error for '{}': {}", username, e.getMessage());
            return null;
        }
    }

    /**
     * Looks up a user by user ID. Returns null if not found.
     */
    public Map<String, Object> findById(int userId) {
        try {
            return jdbc.queryForMap(
                "SELECT * FROM users WHERE id = ?", userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.error("findById error for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Marks a user as verified by their verification token.
     * Returns the user id, or -1 if token not found / expired.
     */
    public int verifyToken(String token) {
        try {
            return jdbc.queryForObject(
                """
                UPDATE users
                   SET is_verified    = TRUE,
                       account_status = 'Active',
                       verified_at    = CURRENT_TIMESTAMP,
                       updated_at     = CURRENT_TIMESTAMP
                 WHERE verification_token = ?
                   AND token_expires_at  > CURRENT_TIMESTAMP
                   AND is_verified       = FALSE
                RETURNING id
                """,
                Integer.class, token);
        } catch (EmptyResultDataAccessException e) {
            return -1;
        } catch (Exception e) {
            log.error("verifyToken error: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Saves a password reset token for a user.
     * Returns true if the token was saved successfully.
     */
    public boolean savePasswordResetToken(int userId, String resetToken, java.time.Instant expiresAt) {
        try {
            int rowsUpdated = jdbc.update(
                """
                UPDATE users
                   SET password_reset_token      = ?,
                       password_reset_expires_at = ?,
                       updated_at                = CURRENT_TIMESTAMP
                 WHERE id = ?
                """,
                resetToken,
                java.sql.Timestamp.from(expiresAt),
                userId);
            return rowsUpdated > 0;
        } catch (Exception e) {
            log.error("savePasswordResetToken error for userId={}: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * Looks up a user by their password reset token if it has not expired.
     * Returns the user row map, or null if not found / expired.
     */
    public Map<String, Object> findByResetToken(String token) {
        try {
            return jdbc.queryForMap(
                """
                SELECT * FROM users
                 WHERE password_reset_token     = ?
                   AND password_reset_expires_at > CURRENT_TIMESTAMP
                """,
                token);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.error("findByResetToken error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Updates the user's password_hash and clears the reset token fields,
     * setting updated_at to now.
     * Returns true if a row was updated.
     */
    public boolean resetPasswordByToken(String token, byte[] newPasswordHash) {
        try {
            int rows = jdbc.update(
                """
                UPDATE users
                   SET password_hash             = ?,
                       password_reset_token      = NULL,
                       password_reset_expires_at = NULL,
                       updated_at                = CURRENT_TIMESTAMP
                 WHERE password_reset_token      = ?
                   AND password_reset_expires_at > CURRENT_TIMESTAMP
                """,
                newPasswordHash, token);
            return rows > 0;
        } catch (Exception e) {
            log.error("resetPasswordByToken error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Updates last_login_at, last_login_ip, last_login_device for a user.
     */
    public void updateLastLogin(int userId, String ipAddress, String deviceInfo) {
        try {
            jdbc.update("""
                UPDATE users
                   SET last_login_at     = CURRENT_TIMESTAMP,
                       last_login_ip     = ?::inet,
                       last_login_device = ?,
                       updated_at        = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, ipAddress, deviceInfo, userId);
        } catch (Exception e) {
            log.warn("updateLastLogin failed for userId={}: {}", userId, e.getMessage());
        }
    }

    // =========================================================================
    // webauthn_credentials table
    // =========================================================================

    /**
     * Legacy insert — delegates to insertFullPasskeyCredential with minimal fields.
     * Kept for backward compatibility with existing callers.
     */
    public int insertWebAuthnCredential(int userId, byte[] credentialId,
                                         byte[] publicKey, String aaguid,
                                         String deviceType, String transports,
                                         String friendlyName) {
        String credIdB64 = credentialId != null
                ? java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId) : null;
        String pkB64 = publicKey != null
                ? java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey) : null;
        return insertFullPasskeyCredential(userId, credIdB64, pkB64,
                null, null, aaguid, deviceType, transports, false,
                friendlyName, null, null, null);
    }

    /**
     * Upserts a complete passkey credential record using credential_id_b64 as the
     * unique key. All fields from the WebAuthn registration/authentication response
     * are stored, matching the JSON structure in the user's profile file.
     *
     * @param userId                  users.id
     * @param credentialIdB64         base64url credential id  (unique key)
     * @param publicKeyB64            base64url encoded public key
     * @param attestationObject       base64url attestationObject from browser
     * @param clientDataJSON          base64url clientDataJSON from browser
     * @param aaguid                  AAGUID string
     * @param deviceType              "singleDevice" | "multiDevice"
     * @param transports              comma-separated transports e.g. "internal"
     * @param backedUp                whether credential is cloud-backed
     * @param friendlyName            human-readable device name
     * @param registeredAt            ISO-8601 instant string (null → now)
     * @param lastUsedAt              ISO-8601 instant string (null → now)
     * @param lastAuthenticatorData   base64url authenticatorData for counter tracking
     * @return generated/existing webauthn_credentials.id, or -1 on failure
     */
    public int insertFullPasskeyCredential(int userId,
                                            String credentialIdB64, String publicKeyB64,
                                            String attestationObject, String clientDataJSON,
                                            String aaguid, String deviceType,
                                            String transports, boolean backedUp,
                                            String friendlyName, String registeredAt,
                                            String lastUsedAt, String lastAuthenticatorData) {
        // Try to delete existing credential first (if exists), then insert fresh
        try {
            if (credentialIdB64 != null && !credentialIdB64.isBlank()) {
                jdbc.update("DELETE FROM webauthn_credentials WHERE credential_id_b64 = ?",
                    credentialIdB64);
            }
        } catch (Exception e) {
            log.debug("Delete before insert failed (may not exist): {}", e.getMessage());
        }

        String sql = """
            INSERT INTO webauthn_credentials
              (user_id, credential_id, public_key, sign_count,
               credential_id_b64, aaguid, device_type, transports, backed_up,
               attestation_object, client_data_json, friendly_name,
               registered_at, last_used_at, last_authenticator_data, created_at, updated_at)
            VALUES (?,?,?,0,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            RETURNING id
            """;
        try {
            byte[] credBytes = credentialIdB64 != null ? safeBase64Decode(credentialIdB64) : new byte[0];
            byte[] pkBytes   = publicKeyB64 != null && !publicKeyB64.isBlank()
                    ? safeBase64Decode(publicKeyB64) : new byte[0];
            Instant regAt = parseInstantOrNow(registeredAt);
            Instant useAt = parseInstantOrNow(lastUsedAt);

            log.info("Inserting passkey: userId={} credentialIdB64='{}' aaguid='{}' deviceType='{}'",
                    userId, credentialIdB64, aaguid, deviceType);

            List<Integer> ids = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(sql);
                ps.setInt(1,    userId);
                ps.setBytes(2,  credBytes);
                ps.setBytes(3,  pkBytes);
                ps.setString(4, credentialIdB64);
                ps.setString(5, aaguid);
                ps.setString(6, deviceType);
                ps.setString(7, transports);
                ps.setBoolean(8, backedUp);
                ps.setString(9,  attestationObject);
                ps.setString(10, clientDataJSON);
                ps.setString(11, friendlyName);
                ps.setTimestamp(12, Timestamp.from(regAt));
                ps.setTimestamp(13, Timestamp.from(useAt));
                ps.setString(14, lastAuthenticatorData);
                return ps;
            }, (rs, rowNum) -> rs.getInt("id"));

            if (!ids.isEmpty()) {
                int credId = ids.get(0);
                log.info("✓ WebAuthn credential inserted: userId={} credId={} credentialIdB64='{}' deviceType='{}'",
                        userId, credId, credentialIdB64, deviceType);
                return credId;
            } else {
                log.warn("✗ Insert returned no rows for userId={}", userId);
                return -1;
            }
        } catch (Exception e) {
            log.error("✗ insertFullPasskeyCredential failed for userId={}: {}",
                    userId, e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Updates sign_count, last_used_at, and last_authenticator_data for a credential
     * identified by its base64url credential id string.
     */
    public void updateCredentialUsed(String credentialIdB64, int newCount,
                                      String lastAuthenticatorData) {
        try {
            jdbc.update("""
                UPDATE webauthn_credentials
                   SET sign_count              = ?,
                       last_used_at            = CURRENT_TIMESTAMP,
                       last_authenticator_data = ?
                 WHERE credential_id_b64 = ?
                """, newCount, lastAuthenticatorData, credentialIdB64);
        } catch (Exception e) {
            log.warn("updateCredentialUsed failed for '{}': {}", credentialIdB64, e.getMessage());
        }
    }

    /**
     * Legacy sign-count update by raw bytes — kept for backward compatibility.
     */
    public void updateCredentialSignCount(byte[] credentialId, int newCount) {
        try {
            jdbc.update("""
                UPDATE webauthn_credentials
                   SET sign_count   = ?,
                       last_used_at = CURRENT_TIMESTAMP
                 WHERE credential_id = ?
                """, newCount, credentialId);
        } catch (Exception e) {
            log.warn("updateCredentialSignCount failed: {}", e.getMessage());
        }
    }

    /**
     * Looks up a single passkey credential by its base64url credential id.
     * Returns null if not found.
     */
    public Map<String, Object> findPasskeyByCredentialId(String credentialIdB64) {
        try {
            return jdbc.queryForMap(
                "SELECT * FROM webauthn_credentials WHERE credential_id_b64 = ?",
                credentialIdB64);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.warn("findPasskeyByCredentialId error for '{}': {}", credentialIdB64, e.getMessage());
            return null;
        }
    }

    /**
     * Returns all passkey credentials for a user ordered by registered_at DESC.
     */
    public List<Map<String, Object>> findPasskeysByUserId(int userId) {
        try {
            return jdbc.queryForList(
                "SELECT * FROM webauthn_credentials WHERE user_id = ? ORDER BY registered_at DESC NULLS LAST",
                userId);
        } catch (Exception e) {
            log.warn("findPasskeysByUserId error for userId={}: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // user_activity_logs table
    // =========================================================================

    /**
     * Inserts a user activity log row. Pass null userId for guest/unauthenticated requests.
     */
    public int insertActivityLog(Integer userId, String ipAddress,
                                  String deviceType, String os,
                                  String browser, String userAgent,
                                  String sessionId,
                                  Object schemaDetails,
                                  Object transcriptsDetails) {
        String sql = """
            INSERT INTO user_activity_logs
              (user_id, ip_address, device_type, operating_system,
               browser, user_agent_string, session_id,
               schema_details, transcripts_details,
               last_sign_in, created_at)
            VALUES (?, ?::inet, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING log_id
            """;
        try {
            KeyHolder kh = new GeneratedKeyHolder();
            String schemaJson = schemaDetails != null
                    ? mapper.writeValueAsString(schemaDetails) : null;
            String transcriptJson = transcriptsDetails != null
                    ? mapper.writeValueAsString(transcriptsDetails) : null;

            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                if (userId != null) ps.setInt(1, userId); else ps.setNull(1, java.sql.Types.INTEGER);
                ps.setString(2, ipAddress != null ? ipAddress : "0.0.0.0");
                ps.setString(3, deviceType);
                ps.setString(4, os);
                ps.setString(5, browser);
                ps.setString(6, userAgent);
                ps.setString(7, sessionId);
                ps.setString(8, schemaJson);
                ps.setString(9, transcriptJson);
                return ps;
            }, kh);
            Number key = kh.getKey();
            return key != null ? key.intValue() : -1;
        } catch (Exception e) {
            log.warn("insertActivityLog failed: {}", e.getMessage());
            return -1;
        }
    }

    // =========================================================================
    // schema_details table
    // =========================================================================

    /**
     * Upserts a schema record for a user+dbName combination.
     * If one already exists it is updated; otherwise inserted.
     */
    public int upsertSchemaDetails(Integer userId, String dbName,
                                    String schemaJson, String filePath) {
        String sql = """
            INSERT INTO schema_details (user_id, db_name, schema_json, file_path, created_at, updated_at)
            VALUES (?, ?, ?::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            RETURNING id
            """;
        // Try update first
        try {
            int updated = jdbc.update("""
                UPDATE schema_details
                   SET schema_json = ?::jsonb,
                       file_path   = ?,
                       updated_at  = CURRENT_TIMESTAMP
                 WHERE (user_id = ? OR (user_id IS NULL AND ? IS NULL))
                   AND db_name = ?
                """, schemaJson, filePath, userId, userId, dbName);
            if (updated > 0) {
                log.debug("schema_details updated for userId={} db='{}'", userId, dbName);
                // return existing id
                try {
                    return jdbc.queryForObject(
                        "SELECT id FROM schema_details WHERE db_name=? ORDER BY updated_at DESC LIMIT 1",
                        Integer.class, dbName);
                } catch (Exception ex) { return 0; }
            }
        } catch (Exception e) {
            log.debug("schema_details update attempt: {}", e.getMessage());
        }
        // Insert
        try {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                if (userId != null) ps.setInt(1, userId); else ps.setNull(1, java.sql.Types.INTEGER);
                ps.setString(2, dbName);
                ps.setString(3, schemaJson);
                ps.setString(4, filePath);
                return ps;
            }, kh);
            Number key = kh.getKey();
            return key != null ? key.intValue() : -1;
        } catch (Exception e) {
            log.error("upsertSchemaDetails failed: {}", e.getMessage());
            return -1;
        }
    }

    // =========================================================================
    // transcript_details table
    // =========================================================================

    /**
     * Inserts a transcript record for a user's NL2SQL interaction.
     * Legacy overload — delegates to enhanced version.
     */
    public int insertTranscript(Integer userId, String userPrompt, String generatedSql,
                                 String explanation, String status, String remarks,
                                 Double confidenceScore, String errorCode, String dataSource) {
        return insertTranscriptEnhanced(userId, userPrompt, generatedSql, explanation,
                status, remarks, confidenceScore, errorCode, dataSource);
    }

    /**
     * Inserts a transcript record matching the ACTUAL transcript_details table schema:
     *   id, user_id, transcript_json, created_at, status,
     *   remarks, user_feedback, feedback_type,
     *   confidence_score, error_code, data_source, updated_at
     *
     * user_prompt, generated_sql and explanation are stored inside transcript_json (jsonb).
     * dataSource = database name for SQL queries, document file name for document RAG queries.
     */
    public int insertTranscriptEnhanced(
            Integer userId,
            String userPrompt, String generatedSql,
            String explanation,
            String status, String remarks,
            Double confidenceScore, String errorCode, String dataSource) {

        // Build the transcript_json payload
        String transcriptJson;
        try {
            transcriptJson = mapper.writeValueAsString(Map.of(
                    "userPrompt",    userPrompt    != null ? userPrompt    : "",
                    "generatedSql",  generatedSql  != null ? generatedSql  : "",
                    "explanation",   explanation   != null ? explanation   : "",
                    "remarks",       remarks       != null ? remarks       : ""
            ));
        } catch (Exception e) {
            transcriptJson = "{}";
        }

        String sql = """
            INSERT INTO transcript_details
              (user_id, transcript_json, status, remarks,
               confidence_score, error_code, data_source,
               created_at, updated_at)
            VALUES (?, ?::jsonb, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """;
        try {
            final String finalTranscriptJson = transcriptJson;
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                int idx = 1;
                if (userId != null) ps.setInt(idx++, userId); else { ps.setNull(idx++, java.sql.Types.INTEGER); }
                ps.setString(idx++, finalTranscriptJson);
                ps.setString(idx++, status != null ? status : "SUCCESS");
                ps.setString(idx++, remarks);
                if (confidenceScore != null) ps.setDouble(idx++, confidenceScore);
                else { ps.setNull(idx++, java.sql.Types.DOUBLE); }
                ps.setString(idx++, errorCode);
                ps.setString(idx++, dataSource);
                return ps;
            }, kh);
            Number key = kh.getKey();
            int newId = key != null ? key.intValue() : -1;
            log.info("Transcript inserted | id={} | userId={} | status={} | dataSource={}", newId, userId, status, dataSource);
            return newId;
        } catch (Exception e) {
            log.warn("insertTranscriptEnhanced failed: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Updates user_feedback and feedback_type in transcript_details for the given transcriptId.
     * Returns the transcriptId on success, or -1 if no row was found or the update failed.
     */
    public int updateTranscriptWithFeedback(int transcriptId, String userFeedback, String feedbackType) {
        String sql = """
            UPDATE transcript_details
            SET user_feedback = ?, feedback_type = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;
        try {
            int rowsAffected = jdbc.update(sql, userFeedback, feedbackType, transcriptId);
            if (rowsAffected == 0) {
                log.warn("updateTranscriptWithFeedback: no row found for transcriptId={}", transcriptId);
                return -1;
            }
            log.info("Transcript feedback updated | id={} | feedbackType={}", transcriptId, feedbackType);
            return transcriptId;
        } catch (Exception e) {
            log.warn("updateTranscriptWithFeedback failed for id={}: {}", transcriptId, e.getMessage());
            return -1;
        }
    }

    // =========================================================================
    // Read transcripts
    // =========================================================================

    /**
     * Returns all transcript_details rows for the given userId,
     * ordered by created_at descending (newest first).
     * Each row is returned as a Map with all available columns.
     */
    public List<Map<String, Object>> findTranscriptsByUserId(int userId) {
        try {
            return jdbc.queryForList("""
                SELECT id, user_id, transcript_json, created_at, status,
                       remarks, user_feedback, feedback_type,
                       confidence_score, error_code, data_source, updated_at
                  FROM transcript_details
                 WHERE user_id = ?
                 ORDER BY created_at DESC
                """, userId);
        } catch (Exception e) {
            log.warn("findTranscriptsByUserId failed for userId={}: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // Admin — list all users
    // =========================================================================

    /**
     * Returns all non-Root users ordered by id ascending.
     * Excludes user_type='Root' since Root users manage the system and
     * should not appear in the Manage Users list.
     * Selects only the columns safe to expose to the admin UI (no password_hash).
     */
    public List<Map<String, Object>> findAllUsers() {
        try {
            return jdbc.queryForList("""
                SELECT id, user_name, email, user_type, account_status,
                       display_name, is_verified, last_login_at,
                       last_login_ip, last_login_device,
                       registered_at, created_at, updated_at
                  FROM users
                 WHERE user_type != 'Root'
                 ORDER BY id ASC
                """);
        } catch (Exception e) {
            log.warn("findAllUsers failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns true if the genQry DB is reachable and the users table exists. */
    public boolean isAvailable() {
        try {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE 1=0", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Safely decodes a base64url string to bytes, handling padding/alphabet differences. */
    private byte[] safeBase64Decode(String b64url) {
        try {
            // Try URL-safe decoder first (strips padding if needed)
            String s = b64url.replaceAll("=+$", "");
            return java.util.Base64.getUrlDecoder().decode(s);
        } catch (Exception e) {
            try {
                // Fall back to standard base64
                return java.util.Base64.getDecoder().decode(b64url);
            } catch (Exception ex) {
                log.warn("safeBase64Decode failed for '{}': {}", b64url, ex.getMessage());
                return new byte[0];
            }
        }
    }

    /** Parses an ISO-8601 instant string, returning Instant.now() if null or unparseable. */
    private Instant parseInstantOrNow(String iso) {
        if (iso == null || iso.isBlank()) return Instant.now();
        try { return Instant.parse(iso); } catch (Exception e) { return Instant.now(); }
    }
}
