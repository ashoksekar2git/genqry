package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles user registration:
 *
 *  1. Sanitises username (alphanumeric + underscore only, lower-cased).
 *  2. Creates a per-user directory under supportingFiles/{username}/
 *  3. Generates a user profile file:
 *         supportingFiles/{username}/{email}_{mmddyyyy}.json
 *     e.g.  mailashoky_gmail_com_03102026.json
 *     containing:
 *       - username, email
 *       - encryptedPassword  (Fernet AES-128-CBC token)
 *       - verificationToken  (UUID)
 *       - tokenExpiresAt     (ISO-8601, TTL from config)
 *       - verified           (false initially)
 *       - registeredAt       (ISO-8601)
 *  4. Builds a verification link and sends a verification email.
 *
 *  File-name convention:
 *    {email}_{mmddyyyy}.json
 *    where email special chars (@, .) are replaced with _
 *
 *  Profile files are detected by the helper {@link #isProfileFile(String)}
 *  which accepts BOTH the new format and the legacy _DBSchema_ format so
 *  that existing accounts continue to work without migration.
 */
@Service
public class UserRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationService.class);

    /** Date formatter for the filename suffix: mmddyyyy  e.g. 03102026 */
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMddyyyy").withZone(ZoneOffset.UTC);

    @Value("${genqry.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    @Value("${genqry.auth.base-url:http://localhost:3000}")
    private String baseUrl;

    @Value("${genqry.auth.token.ttl-hours:24}")
    private long tokenTtlHours;

    @Value("${genqry.auth.mail.from:genQry <no-reply@genqry.local>}")
    private String mailFrom;

    @Value("${genqry.auth.mail.enabled:true}")
    private boolean mailEnabled;

    @Autowired
    private FernetEncryptionService fernetService;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Autowired
    private UserDbService userDbService;

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // =========================================================================
    // File-name helpers
    // =========================================================================

    /**
     * Builds the profile filename:
     *   {emailSafe}_{mmddyyyy}.json
     *
     * e.g. mailashoky_gmail_com_03102026.json
     *
     * @param safeEmail already lower-cased email with @ and . replaced by _
     * @param now       creation instant used for the date suffix
     */
    public static String buildProfileFilename(String safeEmail, Instant now) {
        String dateSuffix = DATE_FMT.format(now);
        return safeEmail + "_" + dateSuffix + ".json";
    }

    // Keep legacy overload so existing callers that still pass selectedDb compile
    public static String buildProfileFilename(String safeEmail, String ignoredDb, Instant now) {
        return buildProfileFilename(safeEmail, now);
    }

    /**
     * Returns the email-safe prefix from a raw email address:
     *   "mail@example.com" → "mail_example_com"
     */
    public static String emailToFilePrefix(String email) {
        return email.trim().toLowerCase().replaceAll("[@.]", "_");
    }

    /**
     * Returns true when a filename looks like a genQry user profile file.
     * Accepts BOTH the new format  ({email}_{db}_{mmddyyyy}.json)
     * and the legacy format ({email}_DBSchema_{yyyyMMdd_HHmmss}.json)
     * so existing accounts are not broken.
     *
     * Heuristic: the file must end with .json and its name must start with
     * a string that contains an underscore (all email-derived prefixes do)
     * AND contain either "_DBSchema_" (legacy) or match the new pattern
     * (at least 3 underscore-separated segments ending in 8 digits).
     */
    public static boolean isProfileFile(String fileName) {
        if (fileName == null || !fileName.endsWith(".json")) return false;
        // Legacy pattern
        if (fileName.contains("_DBSchema_")) return true;
        // New pattern: email_dbname_mmddyyyy.json
        // The date suffix is always exactly 8 digits right before ".json"
        String withoutExt = fileName.substring(0, fileName.length() - 5); // strip .json
        // Must end with _DDDDDDDD (8 digits)
        if (withoutExt.matches(".*_\\d{8}$")) {
            // Must have at least 3 segments separated by underscores
            return withoutExt.split("_").length >= 3;
        }
        return false;
    }

    // =========================================================================
    // Public API — register
    // =========================================================================

    /**
     * Convenience overload — no selectedDb.
     */
    public Path register(String username, String email, String password)
            throws IOException {
        return register(username, email, password, null);
    }

    /**
     * Registers a new user.
     * The selectedDb parameter is accepted but ignored — DB selection is
     * handled separately at query time, not stored at registration.
     */
    public Path register(String username, String email, String password,
                         @SuppressWarnings("unused") String ignoredSelectedDb)
            throws IOException {

        String safeUsername = sanitizeUsername(username);
        String safeEmail    = email.trim().toLowerCase();
        String emailPrefix  = emailToFilePrefix(safeEmail);

        // ── 1. Check if user already exists in database ───────────────────────
        Map<String, Object> existingUserByUsername = userDbService.findUserByUsername(safeUsername);
        if (existingUserByUsername != null) {
            throw new IllegalArgumentException(
                    "Username '" + safeUsername + "' is already Taken. " +
                    "Please choose a different username");
        }
        Map<String, Object> existingUserByEmail = userDbService.findUserByEmail(safeEmail);
        if (existingUserByEmail != null) {
            throw new IllegalArgumentException(
                    "Email '" + safeEmail + "' is already registered. " +
                    "Please use a different email address.");
        }

        // ── 2. Create per-user directory ──────────────────────────────────────
        Path supportingFilesPath = Paths.get(supportingFilesDir).toAbsolutePath();
        Path userDir = supportingFilesPath.resolve(safeUsername);
        if (!Files.exists(userDir)) {
            Files.createDirectories(userDir);
            log.info("Created user directory: {}", userDir);
        }

        // ── 3. Encrypt password ───────────────────────────────────────────────
        String encryptedPassword = fernetService.encrypt(password);

        // ── 4. Generate verification token ────────────────────────────────────
        String  verificationToken = UUID.randomUUID().toString().replace("-", "");
        Instant now       = Instant.now();
        Instant expiresAt = now.plusSeconds(tokenTtlHours * 3600L);

        // ── 5. Build profile JSON ─────────────────────────────────────────────
        ObjectNode profile = objectMapper.createObjectNode();
        profile.put("username",          safeUsername);
        profile.put("email",             safeEmail);
        profile.put("encryptedPassword", encryptedPassword);
        profile.put("verificationToken", verificationToken);
        profile.put("tokenExpiresAt",    expiresAt.toString());
        profile.put("verified",          false);
        profile.put("registeredAt",      now.toString());

        // ── 6. Write {email}_{mmddyyyy}.json ──────────────────────────────────
        String fileName    = buildProfileFilename(emailPrefix, now);
        Path   profileFile = userDir.resolve(fileName);
        objectMapper.writeValue(profileFile.toFile(), profile);
        log.info("User profile written → {}", profileFile);

        // ── 7. Persist user to genQry DB ────────────────────────────────────────
        int dbUserId = userDbService.persistRegisteredUser(
                safeUsername, safeEmail, password,
                verificationToken, expiresAt);
        if (dbUserId > 0) {
            log.info("User persisted to DB: id={} email='{}'", dbUserId, safeEmail);
        }

        // ── 8. Send verification email ────────────────────────────────────────
        String verificationLink = baseUrl.stripTrailing() +
                "/api/v1/auth/verify?token=" + verificationToken;
        sendVerificationEmail(safeEmail, safeUsername, verificationLink);

        return profileFile;
    }

    // =========================================================================
    // Public API — verifyToken
    // =========================================================================

    @SuppressWarnings("unchecked")
    public String verifyToken(String token) throws IOException {
        if (token == null || token.isBlank()) return null;

        Path supportingFilesPath = Paths.get(supportingFilesDir).toAbsolutePath();
        if (!Files.exists(supportingFilesPath)) return null;

        try (var dirs = Files.list(supportingFilesPath)) {
            for (Path userDir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(userDir)) continue;
                try (var files = Files.list(userDir)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        if (!isProfileFile(file.getFileName().toString())) continue;

                        java.util.Map<String, Object> profile;
                        try {
                            profile = objectMapper.readValue(file.toFile(), java.util.Map.class);
                        } catch (Exception ex) { continue; }

                        String storedToken = (String) profile.get("verificationToken");
                        if (!token.equals(storedToken)) continue;

                        // Check expiry
                        String expiresAtStr = (String) profile.get("tokenExpiresAt");
                        if (expiresAtStr != null) {
                            if (Instant.now().isAfter(Instant.parse(expiresAtStr))) {
                                log.warn("Verification token expired for file '{}'", file);
                                return null;
                            }
                        }

                        profile.put("verified",   true);
                        profile.put("verifiedAt", Instant.now().toString());
                        objectMapper.writeValue(file.toFile(), profile);

                        String email = (String) profile.get("email");
                        log.info("User '{}' verified successfully (file updated)", profile.get("username"));

                        // Also mark verified in DB — critical for login to work
                        int dbUserId = userDbService.verifyEmailToken(token);
                        if (dbUserId > 0) {
                            log.info("DB verification successful: userId={} email='{}'", dbUserId, email);
                        } else {
                            log.error("DB verification FAILED for token='{}' email='{}' — " +
                                      "token may not exist in DB, already verified, or expired in DB. " +
                                      "User will not be able to log in until DB is updated.", token, email);
                        }

                        return email;
                    }
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Login
    // =========================================================================

    public static class LoginResult {
        public enum Status { OK, NOT_FOUND, NOT_VERIFIED, BAD_PASSWORD }
        public final Status       status;
        public final String       username;
        public final String       email;
        public final List<Map<String, String>> fileList;

        LoginResult(Status s, String username, String email, List<Map<String, String>> fileList) {
            this.status   = s;
            this.username = username;
            this.email    = email;
            this.fileList = fileList != null ? fileList : Collections.emptyList();
        }
        /** Convenience constructor for error cases. */
        LoginResult(Status s, String username, String email) {
            this(s, username, email, Collections.emptyList());
        }
    }

    /**
     * Validates login credentials.
     * Scans every per-user directory, finds profile files using
     * {@link #isProfileFile(String)}, matches email, checks verified flag,
     * decrypts and compares password.
     *
     * Also accepts an optional {@code selectedDb} parameter from the UI to
     * narrow the profile search when the user supplies it.
     * If {@code selectedDb} is null or blank, all profiles for the email are checked.
     */
    @SuppressWarnings("unchecked")
    public LoginResult login(String email, String password) {
        return login(email, password, null);
    }

    @SuppressWarnings("unchecked")
    public LoginResult login(String email, String password,
                             @SuppressWarnings("unused") String ignoredSelectedDb) {
        String normEmail = email.trim().toLowerCase();

        // ── Step 1: Validate credentials against the database ─────────────────
        Map<String, Object> dbUser = userDbService.findUserByEmail(normEmail);

        if (dbUser == null) {
            log.warn("Login: no DB record for email='{}'", normEmail);
            return loginFromFile(normEmail, password);
        }

        // Verify email is confirmed (column: is_verified)
        Object isVerifiedObj = dbUser.get("is_verified");
        boolean isVerified = Boolean.TRUE.equals(isVerifiedObj)
                || "true".equalsIgnoreCase(String.valueOf(isVerifiedObj));
        if (!isVerified) {
            log.warn("Login: account not verified for email='{}'", normEmail);
            String storedUsername = (String) dbUser.getOrDefault("user_name", "");
            return new LoginResult(LoginResult.Status.NOT_VERIFIED, storedUsername, normEmail);
        }

        // Verify password
        byte[] passwordHash = (byte[]) dbUser.get("password_hash");
        if (passwordHash == null || passwordHash.length == 0) {
            log.warn("Login: no password set for email='{}' (passkey-only account)", normEmail);
            return new LoginResult(LoginResult.Status.NOT_FOUND, null, null);
        }
        if (!userDbService.checkPassword(dbUser, password)) {
            log.warn("Login: bad password for email='{}'", normEmail);
            return new LoginResult(LoginResult.Status.BAD_PASSWORD, null, null);
        }

        String storedUsername = (String) dbUser.getOrDefault("user_name", "");

        // ── Step 2: Scan / create supportingFiles/<username> directory ─────────
        List<Map<String, String>> fileList = resolveUserFileList(storedUsername);

        log.info("Login OK (DB) — username='{}' email='{}' files={}",
                storedUsername, normEmail, fileList.size());
        return new LoginResult(LoginResult.Status.OK, storedUsername, normEmail, fileList);
    }

    /**
     * Resolves the file list for a user's supportingFiles directory.
     *
     * Directory lookup is case-insensitive: if the DB username is "ragexplorer"
     * but the directory on disk is "ragexplorer", or if it is "AshokSekar" but
     * the DB stores "AshokSekar" — we find the actual directory by scanning for a
     * case-insensitive name match.  This prevents cross-user contamination where
     * forcing toLowerCase() would either miss the real dir or create a duplicate.
     *
     * File categorisation (returned in fileList):
     *  • Schema JSON files matching &lt;DBName&gt;_schema_&lt;timestamp&gt;.json
     *    → { "type": "database",  "name": "&lt;DBName&gt;" }
     *  • Uploaded documents (pdf, docx, xlsx, txt, etc.)
     *    → { "type": "document",  "name": "&lt;documentName&gt;" }
     *  • Internal files (vector indices, transcripts, profiles) are excluded
     *
     * Behaviour:
     *  • Exact or case-insensitive match found → scan and return files
     *  • No directory found on disk → create {supportingFilesDir}/{username}
     *    (preserving the original case from the DB) and return empty list
     */

    /** Pattern to match schema files: &lt;DBName&gt;_schema_&lt;timestamp&gt;.json */
    private static final Pattern SCHEMA_FILE_PATTERN =
            Pattern.compile("^(.+?)_schema_\\d{8}_\\d{6}\\.json$", Pattern.CASE_INSENSITIVE);

    /** Known document file extensions */
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".doc", ".xlsx", ".xls", ".csv",
            ".txt", ".rtf", ".pptx", ".ppt", ".odt", ".ods", ".odp");

    private List<Map<String, String>> resolveUserFileList(String username) {
        if (username == null || username.isBlank()) return Collections.emptyList();

        Path base = Paths.get(supportingFilesDir).toAbsolutePath();
        String trimmed = username.trim();

        try {
            // ── Step 1: find the actual directory (case-insensitive scan) ────
            Path resolvedDir = null;

            // First try exact match (fastest path)
            Path exact = base.resolve(trimmed);
            if (Files.isDirectory(exact)) {
                resolvedDir = exact;
            } else {
                // Case-insensitive scan — avoids creating "ashoksekar" when "AshokSekar" exists
                if (Files.isDirectory(base)) {
                    try (var topLevel = Files.list(base)) {
                        resolvedDir = topLevel
                                .filter(Files::isDirectory)
                                .filter(p -> p.getFileName().toString()
                                              .equalsIgnoreCase(trimmed))
                                .findFirst()
                                .orElse(null);
                    }
                }
            }

            // ── Step 2: create directory if it still doesn't exist ────────────
            if (resolvedDir == null) {
                resolvedDir = base.resolve(trimmed);   // use original case from DB
                Files.createDirectories(resolvedDir);
                log.info("Created user directory: {}", resolvedDir);
                return Collections.emptyList();
            }

            // ── Step 3: collect user-visible files as structured objects ──────
            List<Map<String, String>> files = new ArrayList<>();
            final Path finalDir = resolvedDir;
            try (var stream = Files.list(finalDir)) {
                stream.sorted((a, b) -> a.getFileName().toString()
                              .compareToIgnoreCase(b.getFileName().toString()))
                      .forEach(path -> {
                          String name = path.getFileName().toString();

                          // Skip internal system files
                          if (isInternalSystemFile(name)) return;

                          if (Files.isRegularFile(path)) {
                              // Check if it's a schema file: <DBName>_schema_<timestamp>.json
                              Matcher schemaMatcher = SCHEMA_FILE_PATTERN.matcher(name);
                              if (schemaMatcher.matches()) {
                                  String dbName = schemaMatcher.group(1);
                                  files.add(Map.of("type", "database", "name", dbName));
                              } else if (isDocumentFile(name)) {
                                  // Regular document (pdf, docx, xlsx, txt, etc.)
                                  files.add(Map.of("type", "document", "name", name));
                              }
                          }
                      });
            }
            log.info("User '{}' dir='{}' → {} visible file(s): {}",
                    trimmed, resolvedDir.getFileName(), files.size(), files);
            return files;

        } catch (IOException e) {
            log.warn("Could not resolve file list for user '{}': {}", trimmed, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Returns true if the file is a user-visible document based on its extension.
     */
    private boolean isDocumentFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return DOCUMENT_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /**
     * Returns true for files that are internal system artefacts and should NOT
     * be surfaced to the user in the fileList response.
     *
     *  doc_vector_index_*.json   — persisted document vector store index
     *  *_DBSchema.json           — DB schema snapshots written by SchemaExportService
     *  Any file matching the profile naming pattern: <email>_<db>_<date>.json
     *    e.g. promotions2us_gmail_com_ecommerce_03202026.json
     */
    private boolean isInternalSystemFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        // Transcript files
        if (lower.equals("transcripts.json")) return true;
        if (lower.startsWith("transcripts_") && lower.endsWith(".json")) return true;
        // Vector index files
        if (lower.startsWith("doc_vector_index_") && lower.endsWith(".json")) return true;
        if (lower.startsWith("vector_index_") && lower.endsWith(".json")) return true;
        // DB schema snapshots (old naming convention)
        if (lower.endsWith("_dbschema.json")) return true;
        // Profile JSON files written during registration/login (contain email fragment)
        // Pattern: anything_gmail_com_*.json  or  anything_ecommerce_*.json
        if (lower.endsWith(".json") && (lower.contains("_gmail_") || lower.contains("_yahoo_")
                || lower.contains("_outlook_") || lower.contains("_hotmail_")
                || lower.contains("_ecommerce_") || lower.contains("_genqry_")
                || isProfileFile(fileName))) return true;
        // Profile JSON pattern: <email>_<mmddyyyy>.json  (8-digit date suffix)
        if (lower.endsWith(".json")) {
            String withoutExt = lower.substring(0, lower.length() - 5);
            if (withoutExt.matches(".*_\\d{8}$")) return true;
        }
        return false;
    }

    /**
     * Fallback file-based login for accounts not yet in the DB.
     * Scans supportingFiles directories the same way the old login() did.
     */
    @SuppressWarnings("unchecked")
    private LoginResult loginFromFile(String normEmail, String password) {
        Path supportingFilesPath = Paths.get(supportingFilesDir).toAbsolutePath();
        if (!Files.exists(supportingFilesPath)) {
            log.warn("supportingFiles directory not found: {}", supportingFilesPath);
            return new LoginResult(LoginResult.Status.NOT_FOUND, null, null);
        }

        try (var dirs = Files.list(supportingFilesPath)) {
            for (Path userDir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(userDir)) continue;

                try (var files = Files.list(userDir)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        if (!isProfileFile(file.getFileName().toString())) continue;

                        java.util.Map<String, Object> profile;
                        try {
                            profile = objectMapper.readValue(file.toFile(), java.util.Map.class);
                        } catch (Exception ex) {
                            log.debug("Skipping unreadable profile '{}': {}", file, ex.getMessage());
                            continue;
                        }

                        String storedEmail = (String) profile.get("email");
                        if (storedEmail == null || !normEmail.equals(storedEmail.toLowerCase()))
                            continue;

                        String storedUsername = (String) profile.get("username");

                        if (!Boolean.TRUE.equals(profile.get("verified"))) {
                            log.warn("File login: unverified account '{}'", normEmail);
                            return new LoginResult(LoginResult.Status.NOT_VERIFIED,
                                    storedUsername, normEmail);
                        }

                        String encPwd = (String) profile.get("encryptedPassword");
                        if (encPwd == null || encPwd.isBlank()) {
                            return new LoginResult(LoginResult.Status.NOT_FOUND, null, null);
                        }
                        String decrypted;
                        try {
                            decrypted = fernetService.decrypt(encPwd);
                        } catch (Exception ex) {
                            log.error("File login: decrypt failed for '{}': {}", normEmail, ex.getMessage());
                            return new LoginResult(LoginResult.Status.NOT_FOUND, null, null);
                        }

                        if (!decrypted.equals(password)) {
                            log.warn("File login: bad password for '{}'", normEmail);
                            return new LoginResult(LoginResult.Status.BAD_PASSWORD, null, null);
                        }

                        List<Map<String, String>> fileList = resolveUserFileList(storedUsername);
                        log.info("Login OK (file) — username='{}' email='{}'",
                                storedUsername, normEmail);
                        return new LoginResult(LoginResult.Status.OK,
                                storedUsername, normEmail, fileList);
                    }
                } catch (Exception ex) {
                    log.debug("Error scanning userDir '{}': {}", userDir, ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("File login scan failed: {}", ex.getMessage(), ex);
        }

        log.warn("No account found for email='{}'", normEmail);
        return new LoginResult(LoginResult.Status.NOT_FOUND, null, null);
    }

    // =========================================================================
    // Reset Password
    // =========================================================================

    /**
     * Resets the user's password using a valid reset token.
     *
     * Steps:
     *  1. Validate token exists and is not expired (via DB)
     *  2. Encrypt the new password
     *  3. Update password_hash in DB, clear reset token fields, set updated_at
     *  4. Update the JSON profile file so file-based login also works
     *
     * @param token       the UUID reset token from the email link
     * @param newPassword plain text new password
     * @return true if password was updated successfully, false if token invalid/expired
     */
    @SuppressWarnings("unchecked")
    public boolean resetPassword(String token, String newPassword) throws Exception {
        // Step 1: Validate token and get user from DB
        Map<String, Object> user = userDbService.findUserByResetToken(token);
        if (user == null) {
            log.warn("resetPassword: token not found or expired — token='{}'", token);
            return false;
        }

        String email    = (String) user.get("email");
        String username = (String) user.getOrDefault("user_name", "");

        // Step 2: Encrypt new password
        String encryptedPassword = fernetService.encrypt(newPassword);
        byte[] encryptedBytes    = encryptedPassword.getBytes();

        // Step 3: Update DB — set new password_hash, clear reset token, set updated_at
        boolean dbUpdated = userDbService.resetPasswordWithToken(token, encryptedBytes);
        if (!dbUpdated) {
            log.error("resetPassword: DB update failed for email='{}'", email);
            return false;
        }
        log.info("resetPassword: DB password updated for email='{}'", email);

        // Step 4: Also update the JSON profile file so file-based login works
        try {
            Path supportingFilesPath = Paths.get(supportingFilesDir).toAbsolutePath();
            if (Files.exists(supportingFilesPath)) {
                try (var dirs = Files.list(supportingFilesPath)) {
                    for (Path userDir : (Iterable<Path>) dirs::iterator) {
                        if (!Files.isDirectory(userDir)) continue;
                        try (var files = Files.list(userDir)) {
                            for (Path file : (Iterable<Path>) files::iterator) {
                                if (!isProfileFile(file.getFileName().toString())) continue;
                                Map<String, Object> profile;
                                try {
                                    profile = objectMapper.readValue(file.toFile(), java.util.Map.class);
                                } catch (Exception ex) { continue; }
                                String fileEmail = (String) profile.get("email");
                                if (!email.equalsIgnoreCase(fileEmail)) continue;
                                // Update encryptedPassword and updatedAt in profile file
                                profile.put("encryptedPassword", encryptedPassword);
                                profile.put("updatedAt", Instant.now().toString());
                                objectMapper.writeValue(file.toFile(), profile);
                                log.info("resetPassword: profile file updated for email='{}'", email);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            // Non-fatal — DB is source of truth; log and continue
            log.warn("resetPassword: could not update profile file for email='{}': {}", email, ex.getMessage());
        }

        return true;
    }

    // =========================================================================
    // Forgot Password
    // =========================================================================

    /**
     * Result class for forgot password operations.
     */
    public static class ForgotPasswordResult {
        public enum Status { PASSKEY_ONLY, USER_NOT_FOUND, EMAIL_SENT }

        public final Status  status;
        public final String  email;
        public final String  authMethod;   // null | "passkey" | "password"
        public final boolean success;
        public final String  message;

        public ForgotPasswordResult(Status status, String email,
                                    String authMethod, boolean success, String message) {
            this.status     = status;
            this.email      = email;
            this.authMethod = authMethod;
            this.success    = success;
            this.message    = message;
        }
    }

    /**
     * Handles forgot password request.
     *
     * Cases:
     *  USER_NOT_FOUND  → success=false, authMethod=null
     *  PASSKEY_ONLY    → success=true,  authMethod="passkey"
     *  EMAIL_SENT      → success=true,  authMethod="password"  (token saved + email sent)
     */
    public ForgotPasswordResult forgotPassword(String email) {
        String normEmail = email.trim().toLowerCase();

        Map<String, Object> user = userDbService.findUserByEmail(normEmail);

        // ── Not registered ────────────────────────────────────────────────────
        if (user == null) {
            log.info("Forgot password: email='{}' not registered", normEmail);
            return new ForgotPasswordResult(
                    ForgotPasswordResult.Status.USER_NOT_FOUND,
                    normEmail, null, false,
                    "User is not registered yet.");
        }

        // ── Passkey-only account (no password hash) ───────────────────────────
        byte[] passwordHash = (byte[]) user.get("password_hash");
        if (passwordHash == null || passwordHash.length == 0) {
            log.info("Forgot password: email='{}' is passkey-only", normEmail);
            return new ForgotPasswordResult(
                    ForgotPasswordResult.Status.PASSKEY_ONLY,
                    normEmail, "passkey", true,
                    "Your account uses passkey authentication. Please sign in with your passkey instead.");
        }

        // ── Password account — generate reset token and email ─────────────────
        String  resetToken = UUID.randomUUID().toString();
        Instant expiresAt  = Instant.now().plusSeconds(tokenTtlHours * 3600);
        int     userId     = ((Number) user.get("id")).intValue();

        boolean tokenSaved = userDbService.savePasswordResetToken(userId, resetToken, expiresAt);
        if (!tokenSaved) {
            log.error("Forgot password: failed to save reset token for email='{}'", normEmail);
            return new ForgotPasswordResult(
                    ForgotPasswordResult.Status.EMAIL_SENT,
                    normEmail, "password", true,
                    "Error generating reset link. Please try again later.");
        }

        String username  = (String) user.getOrDefault("user_name", "User");
        String resetLink = baseUrl.stripTrailing() +
                "/api/v1/auth/reset-password?token=" + resetToken;
        sendPasswordResetEmail(normEmail, username, resetLink);

        log.info("Forgot password: reset email sent to '{}'", normEmail);
        return new ForgotPasswordResult(
                ForgotPasswordResult.Status.EMAIL_SENT,
                normEmail, "password", true,
                "Password reset link has been sent to your email. Please check your inbox.");
    }

    // =========================================================================
    // Email
    // =========================================================================

    private void sendVerificationEmail(String toEmail, String username, String verificationLink) {
        if (!mailEnabled) {
            log.warn("Email disabled — verification link for '{}': {}", toEmail, verificationLink);
            return;
        }
        if (mailSender == null) {
            log.warn("JavaMailSender not configured — link for '{}': {}", toEmail, verificationLink);
            return;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(toEmail);
            helper.setSubject("Welcome to genQry — Verify your email address");
            helper.setText(buildEmailHtml(username.toUpperCase(), verificationLink), true);
            mailSender.send(msg);
            log.info("Verification email sent to '{}'", toEmail);
        } catch (Exception e) {
            log.error("Failed to send verification email to '{}': {}", toEmail, e.getMessage(), e);
        }
    }

    private void sendPasswordResetEmail(String toEmail, String username, String resetLink) {
        if (!mailEnabled) {
            log.warn("Email disabled — password reset link for '{}': {}", toEmail, resetLink);
            return;
        }
        if (mailSender == null) {
            log.warn("JavaMailSender not configured — link for '{}': {}", toEmail, resetLink);
            return;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(toEmail);
            helper.setSubject("genQry — Reset your password");
            helper.setText(buildPasswordResetEmailHtml(username.toUpperCase(), resetLink), true);
            mailSender.send(msg);
            log.info("Password reset email sent to '{}'", toEmail);
        } catch (Exception e) {
            log.error("Failed to send password reset email to '{}': {}", toEmail, e.getMessage(), e);
        }
    }

    private String buildPasswordResetEmailHtml(String username, String resetLink) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#0f172a;font-family:Arial,Helvetica,sans-serif">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#0f172a;padding:32px 16px">
                    <tr><td align="center">
                      <table width="520" cellpadding="0" cellspacing="0"
                             style="background:#1e293b;border-radius:12px;overflow:hidden;
                                    box-shadow:0 4px 24px rgba(0,0,0,.4)">
                        <!-- Header -->
                        <tr>
                          <td style="background:linear-gradient(135deg,#ea580c 0%%,#dc2626 100%%);
                                     padding:28px 32px;text-align:center">
                            <h1 style="margin:0;color:#fff;font-size:26px;letter-spacing:2px;
                                       font-weight:700;text-transform:uppercase">genQry</h1>
                            <p style="margin:4px 0 0;color:rgba(255,255,255,.75);font-size:12px;
                                      letter-spacing:1px">Natural Language → SQL</p>
                          </td>
                        </tr>
                        <!-- Body -->
                        <tr>
                          <td style="padding:32px">
                            <h2 style="color:#f1f5f9;margin:0 0 8px;font-size:20px">
                              Reset your password, %s
                            </h2>
                            <p style="color:#94a3b8;font-size:14px;line-height:1.6;margin:0 0 24px">
                              We received a request to reset your password. Click the button below to create a new password.
                            </p>
                            <!-- CTA Button -->
                            <table width="100%%" cellpadding="0" cellspacing="0">
                              <tr>
                                <td align="center">
                                  <table cellpadding="0" cellspacing="0">
                                    <tr>
                                      <td style="background:linear-gradient(135deg,#ea580c 0%%,#dc2626 100%%);;
                                                 border-radius:6px;padding:14px 32px">
                                        <a href="%s" style="color:#fff;text-decoration:none;
                                                           font-weight:700;font-size:14px;
                                                           letter-spacing:1px;text-transform:uppercase;
                                                           display:inline-block">
                                          Reset Password
                                        </a>
                                      </td>
                                    </tr>
                                  </table>
                                </td>
                              </tr>
                            </table>
                            <p style="color:#94a3b8;font-size:13px;line-height:1.6;margin:24px 0 0">
                              Or copy this link into your browser:
                            </p>
                            <p style="color:#64748b;font-size:12px;margin:8px 0;word-break:break-all">
                              <a href="%s" style="color:#0ea5e9;text-decoration:none">%s</a>
                            </p>
                          </td>
                        </tr>
                        <!-- Footer -->
                        <tr>
                          <td style="background:#0f172a;padding:16px 32px;
                                     border-top:1px solid #334155;text-align:center">
                            <p style="color:#475569;font-size:11px;margin:0">
                              This link expires in %d hour(s). If you did not request a password reset,
                              you can safely ignore this email. Your account is secure.
                            </p>
                          </td>
                        </tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(username, resetLink, resetLink, resetLink, tokenTtlHours);
    }

    private String buildEmailHtml(String username, String verificationLink) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#0f172a;font-family:Arial,Helvetica,sans-serif">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#0f172a;padding:32px 16px">
                    <tr><td align="center">
                      <table width="520" cellpadding="0" cellspacing="0"
                             style="background:#1e293b;border-radius:12px;overflow:hidden;
                                    box-shadow:0 4px 24px rgba(0,0,0,.4)">
                        <!-- Header -->
                        <tr>
                          <td style="background:linear-gradient(135deg,#ea580c 0%%,#dc2626 100%%);
                                     padding:28px 32px;text-align:center">
                            <h1 style="margin:0;color:#fff;font-size:26px;letter-spacing:2px;
                                       font-weight:700;text-transform:uppercase">genQry</h1>
                            <p style="margin:4px 0 0;color:rgba(255,255,255,.75);font-size:12px;
                                      letter-spacing:1px">Natural Language → SQL</p>
                          </td>
                        </tr>
                        <!-- Body -->
                        <tr>
                          <td style="padding:32px">
                            <h2 style="color:#f1f5f9;margin:0 0 8px;font-size:20px">
                              Welcome to genQry, %s!
                            </h2>
                            <p style="color:#94a3b8;font-size:14px;line-height:1.6;margin:0 0 24px">
                              Thanks for registering. Please verify your email address to activate
                              your account and start using genQry.
                            </p>
                            <!-- CTA Button -->
                            <table width="100%%" cellpadding="0" cellspacing="0">
                              <tr><td align="center" style="padding:8px 0 28px">
                                <a href="%s"
                                   style="display:inline-block;background:#ea580c;color:#fff;
                                          text-decoration:none;padding:14px 36px;border-radius:8px;
                                          font-size:15px;font-weight:600;letter-spacing:.3px">
                                  ✓ &nbsp;Verify Email Address
                                </a>
                              </td></tr>
                            </table>
                            <!-- Fallback link -->
                            <p style="color:#64748b;font-size:12px;line-height:1.6;margin:0 0 4px">
                              Button not working? Copy and paste this link into your browser:
                            </p>
                            <p style="margin:0;word-break:break-all">
                              <a href="%s" style="color:#f97316;font-size:12px">%s</a>
                            </p>
                          </td>
                        </tr>
                        <!-- Footer -->
                        <tr>
                          <td style="background:#0f172a;padding:16px 32px;
                                     border-top:1px solid #334155;text-align:center">
                            <p style="color:#475569;font-size:11px;margin:0">
                              This link expires in %d hour(s). If you did not create a genQry account,
                              you can safely ignore this email.
                            </p>
                          </td>
                        </tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(username, verificationLink, verificationLink,
                              verificationLink, tokenTtlHours);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String sanitizeUsername(String raw) {
        if (raw == null || raw.isBlank())
            throw new IllegalArgumentException("username must not be blank");
        String safe = raw.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (safe.isBlank() || safe.equals("_"))
            throw new IllegalArgumentException(
                    "username '" + raw + "' contains no valid characters after sanitisation");
        return safe;
    }
}
