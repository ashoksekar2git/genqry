package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlp.rag.seek.repository.UserRepository;
import com.nlp.rag.seek.service.UsernameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UserDbService — orchestrates all database-backed user operations.
 *
 * Responsibilities:
 *   1. Persist registered users to the users table
 *   2. Handle guest/temp users (ip_<timestamp>, user_type=Temp)
 *   3. Persist passkey credentials to webauthn_credentials
 *   4. Record user activity logs (login, device, browser, IP)
 *   5. Store schema_details and transcript_details
 *   6. Verify email tokens against the DB
 *
 * This service is a DB-only layer — it does NOT touch JSON profile files.
 * File-based operations remain in UserRegistrationService.
 * Both operate in parallel; the DB becomes the source of truth over time.
 */
@Service
public class UserDbService {

    private static final Logger log = LoggerFactory.getLogger(UserDbService.class);

    @Autowired
    private UserRepository repo;

    @Autowired
    private FernetEncryptionService fernetService;

    @Value("${seek.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    // =========================================================================
    // Registration
    // =========================================================================

    /**
     * Persists a newly registered user to the users table.
     * Called after the JSON profile file is created by UserRegistrationService.
     *
     * @param username          sanitised username
     * @param email             email address
     * @param plaintextPassword plain password (will be Fernet-encrypted)
     * @param verificationToken UUID token for email link
     * @param tokenExpiresAt    token expiry time
     * @return the generated user id, or -1 if DB unavailable
     */
    public int persistRegisteredUser(String username, String email,
                                      String plaintextPassword,
                                      String verificationToken, Instant tokenExpiresAt) {
        if (!repo.isAvailable()) {
            log.warn("DB unavailable — skipping DB persistence for '{}'", email);
            return -1;
        }
        try {
            byte[] encryptedBytes = fernetService.encrypt(plaintextPassword).getBytes();
            return repo.insertUser(
                    username, email, encryptedBytes,
                    "Registered", "Pending",
                    verificationToken, tokenExpiresAt,
                    "ecommerce", username   // selectedDb=ecommerce (default), displayName=username
            );
        } catch (Exception e) {
            log.error("persistRegisteredUser failed for '{}': {}", email, e.getMessage());
            return -1;
        }
    }

    // =========================================================================
    // Guest / Temp users
    // =========================================================================

    /**
     * Holds the result of a guest-user lookup/creation.
     */
    public static class GuestUserResult {
        public final int    userId;
        public final String username;
        public final String email;
        public final boolean isNew;

        public GuestUserResult(int userId, String username, String email, boolean isNew) {
            this.userId   = userId;
            this.username = username;
            this.email    = email;
            this.isNew    = isNew;
        }
    }

    /**
     * Returns an existing Temp/guest user for the given IP if one already exists,
     * otherwise creates a new one.
     *
     * username = safeIp  (sanitised IP, e.g. "192_168_1_1")
     * email    = guest_<safeIp>@seek.temp
     * user_type = Temp, account_status = Active, is_verified = true
     *
     * @param ipAddress  client IP address
     * @return GuestUserResult with userId, username, email and isNew flag
     */
    public GuestUserResult getOrCreateGuestUser(String ipAddress) {
        String safeIp = UsernameUtil.sanitize(ipAddress != null ? ipAddress : "0.0.0.0");

        if (!repo.isAvailable()) {
            log.warn("DB unavailable — guest user not persisted");
            return new GuestUserResult(-1, safeIp, "guest_" + safeIp + "@seek.temp", true);
        }

        // Check if a Temp user for this IP already exists (exact username match)
        Map<String, Object> existing = repo.findGuestByIp(safeIp);
        if (existing != null) {
            int existingId = ((Number) existing.get("id")).intValue();
            String existingUsername = (String) existing.get("user_name");
            String existingEmail    = (String) existing.get("email");
            log.info("Returning existing guest user id={} for ip='{}'", existingId, ipAddress);
            return new GuestUserResult(existingId, existingUsername, existingEmail, false);
        }

        // No existing guest — create a new one using just the IP as username
        String email = "guest_" + safeIp + "@seek.temp";

        int newId = repo.insertUser(
                safeIp, email, null,
                "Temp", "Active",
                null, null,
                null, "Guest User"
        );
        log.info("Created new guest user id={} for ip='{}'", newId, ipAddress);
        return new GuestUserResult(newId, safeIp, email, true);
    }

    /**
     * @deprecated Use {@link #getOrCreateGuestUser(String)} instead.
     */
    @Deprecated
    public int createGuestUser(String ipAddress) {
        return getOrCreateGuestUser(ipAddress).userId;
    }

    // =========================================================================
    // Passkey users
    // =========================================================================

    /**
     * Creates a new user for passkey-only registration.
     * User can register with just username and email, no password required.
     *
     * @param username  display name and username
     * @param email     user's email address (must be unique)
     * @return the generated user id, or -1 if creation fails
     */
    public int createPasskeyUser(String username, String email) {
        if (!repo.isAvailable()) {
            log.warn("DB unavailable — cannot create passkey user for '{}'", email);
            return -1;
        }
        try {
            int userId = repo.insertUser(
                    username,      // userName
                    email,         // email
                    null,          // passwordHash (no password for passkey-only)
                    "Registered",     // userType
                    "Active",      // accountStatus (no email verification needed for passkey)
                    null,          // verificationToken
                    null,          // tokenExpiresAt
                    null,          // selectedDb
                    username       // displayName
            );
            if (userId > 0) {
                log.info("✓ Passkey user created: userId={} username='{}' email='{}'",
                        userId, username, email);
            } else {
                log.error("✗ Failed to create passkey user for email='{}' (returned id={})",
                        email, userId);
            }
            return userId;
        } catch (Exception e) {
            log.error("Exception creating passkey user for '{}': {}", email, e.getMessage(), e);
            return -1;
        }
    }    // =========================================================================
    // Email verification
    // =========================================================================

    /**
     * Verifies the email token in the DB and marks the user as verified.
     * @return user id on success, -1 on failure
     */
    public int verifyEmailToken(String token) {
        if (!repo.isAvailable()) return -1;
        return repo.verifyToken(token);
    }

    /**
     * Looks up a user by their password reset token (only if not expired).
     * Returns the user row map, or null if token not found / expired.
     */
    public Map<String, Object> findUserByResetToken(String token) {
        if (!repo.isAvailable()) return null;
        return repo.findByResetToken(token);
    }

    /**
     * Updates the user's password hash and clears the reset token.
     * @return true on success
     */
    public boolean resetPasswordWithToken(String token, byte[] newPasswordHash) {
        if (!repo.isAvailable()) return false;
        return repo.resetPasswordByToken(token, newPasswordHash);
    }

    /**
     * Saves a password reset token for a user.
     * Used by the forgot-password flow.
     *
     * @param userId          the user's ID
     * @param resetToken      UUID token for password reset
     * @param expiresAt       when the token expires
     * @return true if saved successfully, false otherwise
     */
    public boolean savePasswordResetToken(int userId, String resetToken, Instant expiresAt) {
        if (!repo.isAvailable()) {
            log.warn("DB unavailable — cannot save password reset token for user id={}", userId);
            return false;
        }
        try {
            return repo.savePasswordResetToken(userId, resetToken, expiresAt);
        } catch (Exception e) {
            log.error("savePasswordResetToken failed for user id={}: {}", userId, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Login
    // =========================================================================

    /**
     * Looks up a user by email for login validation.
     * Returns the user row map, or null if not found.
     */
    public Map<String, Object> findUserForLogin(String email) {
        if (!repo.isAvailable()) return null;
        return repo.findByEmail(email);
    }

    /**
     * Looks up a user by email for registration duplicate checking.
     * Returns the user row map, or null if not found.
     */
    public Map<String, Object> findUserByEmail(String email) {
        if (!repo.isAvailable()) return null;
        return repo.findByEmail(email);
    }

    /**
     * Looks up a user by username for registration duplicate checking.
     * Returns the user row map, or null if not found.
     */
    public Map<String, Object> findUserByUsername(String username) {
        if (!repo.isAvailable()) return null;
        return repo.findByUsername(username);
    }

    /**
     * Looks up a user by user ID.
     * Returns the user row map, or null if not found.
     */
    public Map<String, Object> findUserById(int userId) {
        if (!repo.isAvailable()) return null;
        return repo.findById(userId);
    }

    /**
     * Verifies a login password against the stored encrypted password.
     */
    public boolean checkPassword(Map<String, Object> user, String plainPassword) {
        try {
            byte[] encBytes = (byte[]) user.get("password_hash");
            if (encBytes == null) return false;
            String decrypted = fernetService.decrypt(new String(encBytes));
            return plainPassword.equals(decrypted);
        } catch (Exception e) {
            log.warn("Password check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks if this is the user's first login.
     * Returns true if last_login_at is null (user has never logged in before).
     */
    public boolean isFirstLogin(Map<String, Object> user) {
        if (user == null) return false;
        Object lastLogin = user.get("last_login_at");
        return lastLogin == null;
    }

    /**
     * Records a successful login: updates last_login fields and inserts activity log.
     */
    public void recordLogin(int userId, String ipAddress, String deviceType,
                             String os, String browser, String userAgent,
                             String sessionId) {
        if (!repo.isAvailable()) return;
        String deviceInfo = String.join(" | ",
                deviceType != null ? deviceType : "",
                os         != null ? os         : "",
                browser    != null ? browser    : "").trim();
        repo.updateLastLogin(userId, ipAddress, deviceInfo);
        repo.insertActivityLog(userId, ipAddress, deviceType, os,
                browser, userAgent, sessionId, null, null);
        log.info("Login recorded: userId={} ip='{}' browser='{}'", userId, ipAddress, browser);
    }

    // =========================================================================
    // Passkey
    // =========================================================================

    /**
     * Legacy minimal persist — kept for backward compatibility.
     * New code should call persistFullPasskeyCredential instead.
     */
    public int persistPasskeyCredential(String email, String credentialId,
                                         String publicKey, String aaguid,
                                         String deviceType, String transports,
                                         String friendlyName) {
        return persistFullPasskeyCredential(email, credentialId, publicKey,
                null, null, aaguid, deviceType, transports, false,
                friendlyName, null, null, null);
    }

    /**
     * Persists the complete passkey credential record to webauthn_credentials.
     * Stores every field from the WebAuthn registration response so the DB mirrors
     * the JSON profile file exactly.
     *
     * @param email                   user's email (used to look up user id)
     * @param credentialIdB64         base64url credential id
     * @param publicKeyB64            base64url public key
     * @param attestationObject       base64url attestationObject
     * @param clientDataJSON          base64url clientDataJSON
     * @param aaguid                  AAGUID string
     * @param deviceType              "singleDevice" | "multiDevice"
     * @param transports              comma-separated transports
     * @param backedUp                cloud-backed flag
     * @param friendlyName            human-readable device name
     * @param registeredAt            ISO-8601 instant (null → now)
     * @param lastUsedAt              ISO-8601 instant (null → now)
     * @param lastAuthenticatorData   base64url authenticatorData
     * @return webauthn_credentials.id or -1
     */
    public int persistFullPasskeyCredential(String email,
                                             String credentialIdB64, String publicKeyB64,
                                             String attestationObject, String clientDataJSON,
                                             String aaguid, String deviceType,
                                             String transports, boolean backedUp,
                                             String friendlyName, String registeredAt,
                                             String lastUsedAt, String lastAuthenticatorData) {
        if (!repo.isAvailable()) {
            log.warn("DB unavailable — cannot persist passkey credential");
            return -1;
        }
        try {
            Map<String, Object> user = repo.findByEmail(email);
            if (user == null) {
                log.warn("persistFullPasskeyCredential — no user found for '{}'", email);
                return -1;
            }
            int userId = ((Number) user.get("id")).intValue();
            int result = repo.insertFullPasskeyCredential(
                    userId, credentialIdB64, publicKeyB64,
                    attestationObject, clientDataJSON,
                    aaguid, deviceType, transports, backedUp,
                    friendlyName, registeredAt, lastUsedAt, lastAuthenticatorData);

            if (result > 0) {
                log.info("✓ Passkey persisted for email='{}' credentialId='{}'", email, credentialIdB64);
            } else {
                log.error("✗ Failed to persist passkey for email='{}' credentialId='{}'", email, credentialIdB64);
            }
            return result;
        } catch (Exception e) {
            log.error("persistFullPasskeyCredential failed for '{}': {}", email, e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Returns all passkey credentials for the given email as a list of maps,
     * each map containing all DB columns.  Returns an empty list if the user
     * has no passkeys or the DB is unavailable.
     */
    public java.util.List<Map<String, Object>> getPasskeysForUser(String email) {
        if (!repo.isAvailable()) return java.util.Collections.emptyList();
        try {
            Map<String, Object> user = repo.findByEmail(email);
            if (user == null) return java.util.Collections.emptyList();
            int userId = ((Number) user.get("id")).intValue();
            return repo.findPasskeysByUserId(userId);
        } catch (Exception e) {
            log.warn("getPasskeysForUser failed for '{}': {}", email, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Looks up a passkey credential by its base64url credential ID.
     * Returns the webauthn_credentials row, or null if not found.
     */
    public Map<String, Object> findPasskeyByCredentialId(String credentialIdB64) {
        if (!repo.isAvailable()) return null;
        return repo.findPasskeyByCredentialId(credentialIdB64);
    }

    /**
     * Updates the sign counter and last_authenticator_data for a passkey after
     * successful authentication.
     *
     * @param credentialIdB64       base64url credential id
     * @param newCount              new sign counter value
     * @param lastAuthenticatorData base64url authenticatorData from the assertion
     */
    public void updatePasskeyUsed(String credentialIdB64, int newCount,
                                   String lastAuthenticatorData) {
        if (!repo.isAvailable()) return;
        repo.updateCredentialUsed(credentialIdB64, newCount, lastAuthenticatorData);
    }

    // =========================================================================
    // Schema details
    // =========================================================================

    /**
     * Saves or updates extracted schema JSON for a user+dbName combination.
     */
    public int saveSchemaDetails(String email, String dbName,
                                  String schemaJson, String filePath) {
        if (!repo.isAvailable()) return -1;
        try {
            Integer userId = null;
            if (email != null && !email.isBlank()) {
                Map<String, Object> user = repo.findByEmail(email);
                if (user != null) userId = (int) user.get("id");
            }
            return repo.upsertSchemaDetails(userId, dbName, schemaJson, filePath);
        } catch (Exception e) {
            log.warn("saveSchemaDetails failed: {}", e.getMessage());
            return -1;
        }
    }

    // =========================================================================
    // Transcript details
    // =========================================================================

    /**
     * Persists a single NL2SQL interaction transcript to the DB.
     * Legacy overload — delegates to saveTranscriptEnhanced with SUCCESS status.
     */
    public int saveTranscript(Integer userId, String userPrompt, String generatedSql,
                               String explanation, String dataSource) {
        return saveTranscriptEnhanced(userId, userPrompt, generatedSql, explanation,
                "SUCCESS", null, null, null, dataSource);
    }

    /**
     * Persists a comprehensive NL2SQL interaction transcript to the DB.
     * Actual columns written: user_id, transcript_json, status, remarks,
     *   confidence_score, error_code, data_source, created_at, updated_at.
     * user_prompt, generated_sql and explanation are packed into transcript_json (jsonb).
     *
     * @param userId          pre-resolved user ID (null for guest)
     * @param userPrompt      natural language query from user
     * @param generatedSql    SQL generated by LLM (or doc answer for Document RAG)
     * @param explanation     plain-English explanation
     * @param status          SUCCESS, FAILURE, VALIDATION_ERROR, CACHED, HALLUCINATION
     * @param remarks         notes on outcome — failure reason, retry info, etc. (null if plain success)
     * @param confidenceScore LLM confidence score (0-1)
     * @param errorCode       machine-readable error type
     * @param dataSource      database name (SQL pipeline) OR document file name (Document RAG)
     * @return                transcript ID in database, or -1 on error
     */
    public int saveTranscriptEnhanced(
            Integer userId,
            String userPrompt, String generatedSql,
            String explanation,
            String status, String remarks,
            Double confidenceScore, String errorCode, String dataSource) {

        if (!repo.isAvailable()) return -1;
        try {
            return repo.insertTranscriptEnhanced(userId, userPrompt, generatedSql,
                    explanation, status, remarks, confidenceScore, errorCode, dataSource);
        } catch (Exception e) {
            log.warn("saveTranscriptEnhanced failed: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Saves user feedback for a transcript entry.
     * Called when user rates/comments on a generated SQL query result.
     *
     * @param transcriptId  transcript record ID
     * @param userFeedback  feedback text (what the user typed)
     * @param feedbackType  feedback type string (e.g. "POSITIVE", "NEGATIVE", "NEUTRAL")
     * @return              transcript ID if successful, -1 on error
     */
    public int saveUserFeedback(int transcriptId, String userFeedback, String feedbackType) {
        if (!repo.isAvailable()) return -1;
        try {
            return repo.updateTranscriptWithFeedback(transcriptId, userFeedback, feedbackType);
        } catch (Exception e) {
            log.warn("saveUserFeedback failed: {}", e.getMessage());
            return -1;
        }
    }

    // =========================================================================
    // Admin — user management
    // =========================================================================

    /**
     * Returns all users from the DB (excludes password_hash for security).
     */
    public List<Map<String, Object>> getAllUsers() {
        if (!repo.isAvailable()) return Collections.emptyList();
        return repo.findAllUsers();
    }

    /**
     * Returns all transcript_details rows for the given userId from the DB.
     */
    public List<Map<String, Object>> getTranscriptsForUser(int userId) {
        if (!repo.isAvailable()) return Collections.emptyList();
        return repo.findTranscriptsByUserId(userId);
    }

    /**
     * Reads all transcripts for the given userName from the DB and writes them
     * to supportingFiles/{sanitizedUserName}/transcripts.json.
     *
     * The raw JDBC transcript_json column is a PostgreSQL JSONB value.
     * Depending on the JDBC driver it may arrive as:
     *   - org.postgresql.util.PGobject  → .toString() gives the JSON string
     *   - java.lang.String              → already a JSON string
     *   - java.util.Map                 → already parsed by some drivers
     *
     * This method extracts the inner JSON and writes a structured object
     * under the key "transcriptDetails":
     *   {
     *     "userPrompt":  "user's question",
     *     "explanation": "LLM explanation",
     *     "llmResponse": "generated SQL or doc answer",
     *     "remarks":     "failure reason or notes",
     *     "retry":       true/false (EAV retry indicator)
     *   }
     *
     * Called on login and after every new transcript is persisted to keep the
     * JSON file in sync with the database.
     *
     * @param userName  the raw username (will be sanitized for directory name)
     * @return the number of transcripts written, or -1 on failure
     */
    @SuppressWarnings("unchecked")
    public int exportUserTranscriptsToJson(String userName) {
        if (userName == null || userName.isBlank()) return -1;
        try {
            Map<String, Object> userRow = findUserByUsername(userName);
            if (userRow == null) {
                log.warn("exportUserTranscriptsToJson: user '{}' not found in DB", userName);
                return -1;
            }
            int userId = ((Number) userRow.get("id")).intValue();
            List<Map<String, Object>> transcripts = getTranscriptsForUser(userId);

            ObjectMapper mapper = new ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

            // Transform each row: parse transcript_json and write as "transcriptDetails"
            for (Map<String, Object> row : transcripts) {
                Object tjRaw = row.remove("transcript_json");  // remove old key
                Map<String, Object> expanded = new LinkedHashMap<>();

                // Log the actual JDBC type for first row to aid diagnostics
                if (row == transcripts.get(0)) {
                    log.debug("transcript_json JDBC type={} value-preview='{}'",
                            tjRaw != null ? tjRaw.getClass().getName() : "null",
                            tjRaw != null ? tjRaw.toString().substring(0, Math.min(120, tjRaw.toString().length())) : "null");
                }

                // Extract the JSON string from whatever type JDBC returned
                String jsonStr = extractJsonString(tjRaw, mapper);

                if (jsonStr != null && !jsonStr.isBlank()) {
                    try {
                        Map<String, Object> parsed = mapper.readValue(jsonStr, Map.class);
                        expanded.put("userPrompt",  parsed.getOrDefault("userPrompt", ""));
                        expanded.put("explanation",  parsed.getOrDefault("explanation", ""));
                        expanded.put("llmResponse",  parsed.getOrDefault("generatedSql", ""));
                        String remarksVal = String.valueOf(parsed.getOrDefault("remarks", ""));
                        expanded.put("remarks", remarksVal);
                        expanded.put("retry", remarksVal.contains("EAV_RETRY"));
                    } catch (Exception parseEx) {
                        log.debug("Could not parse transcript_json for id={}: {}",
                                row.get("id"), parseEx.getMessage());
                        setEmptyTranscriptDetails(expanded);
                    }
                } else {
                    setEmptyTranscriptDetails(expanded);
                }

                row.put("transcriptDetails", expanded);  // new key name

                // Format timestamps as readable date-time strings instead of epoch millis
                formatTimestamp(row, "created_at");
                formatTimestamp(row, "updated_at");
            }

            String safeUser = UsernameUtil.sanitize(userName);
            Path userDir = Paths.get(supportingFilesDir).toAbsolutePath().resolve(safeUser);
            Files.createDirectories(userDir);
            Path jsonFile = userDir.resolve("transcripts.json");

            mapper.writeValue(jsonFile.toFile(), transcripts);

            log.info("Exported {} transcripts → {} for user='{}'",
                    transcripts.size(), jsonFile, userName);
            return transcripts.size();
        } catch (Exception e) {
            log.warn("exportUserTranscriptsToJson failed for user='{}': {}", userName, e.getMessage());
            return -1;
        }
    }

    /**
     * Extracts a plain JSON string from the JDBC column value.
     *
     * PGobject.toString()   → "{\"userPrompt\":\"...\", ...}"
     * String                → already JSON
     * Map (pre-parsed)      → serialize back to JSON string
     * Map with "value" key  → Jackson-serialized PGobject wrapper
     */
    @SuppressWarnings("unchecked")
    private String extractJsonString(Object raw, ObjectMapper mapper) {
        if (raw == null) return null;

        // Case 1: already a String
        if (raw instanceof String s) return s;

        // Case 2: Map — could be a pre-parsed JSONB or a Jackson-serialized PGobject wrapper
        if (raw instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) raw;
            // Jackson PGobject wrapper: {"type":"jsonb","value":"{...}","null":false}
            if (map.containsKey("value") && map.containsKey("type")) {
                Object val = map.get("value");
                return val != null ? val.toString() : null;
            }
            // Pre-parsed map — serialize it back so the caller can re-parse uniformly
            try {
                return mapper.writeValueAsString(map);
            } catch (Exception e) {
                return null;
            }
        }

        // Case 3: PGobject or any other type — toString() gives the JSON string
        return raw.toString();
    }

    private void setEmptyTranscriptDetails(Map<String, Object> target) {
        target.put("userPrompt", "");
        target.put("explanation", "");
        target.put("llmResponse", "");
        target.put("remarks", "");
        target.put("retry", false);
    }

    /**
     * Converts a timestamp field in the row map from epoch millis / java.sql.Timestamp
     * to a readable "yyyy-MM-dd HH:mm:ss" string.
     */
    private void formatTimestamp(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val == null) return;

        try {
            java.time.Instant instant;
            if (val instanceof java.sql.Timestamp ts) {
                instant = ts.toInstant();
            } else if (val instanceof Number num) {
                instant = java.time.Instant.ofEpochMilli(num.longValue());
            } else if (val instanceof java.time.OffsetDateTime odt) {
                instant = odt.toInstant();
            } else {
                // Already a string or unknown type — leave as is
                return;
            }
            String formatted = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(instant);
            row.put(key, formatted);
        } catch (Exception e) {
            // Leave original value if formatting fails
        }
    }
}
