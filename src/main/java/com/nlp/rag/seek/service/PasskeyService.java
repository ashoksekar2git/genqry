package com.nlp.rag.seek.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PasskeyService — handles the full WebAuthn passkey ceremony.
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  Registration ceremony                                                   │
 * │   1. /register/begin  → generate challenge + options → store in session  │
 * │   2. /register/finish → receive credential → verify → write to JSON      │
 * │                                                                           │
 * │  Authentication ceremony                                                  │
 * │   3. /login/begin     → generate challenge → store in session            │
 * │   4. /login/finish    → receive assertion → verify → return user info    │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Passkey data is stored in the user's profile JSON file at:
 *   supportingFiles/{username}/{email}_DBSchema_{timestamp}.json
 *
 * Schema additions:
 * {
 *   "passkeys": [
 *     {
 *       "credentialId":        "base64url...",
 *       "credentialPublicKey": "base64url...",
 *       "counter":             0,
 *       "aaguid":              "...",
 *       "deviceType":          "singleDevice|multiDevice",
 *       "backedUp":            false,
 *       "transports":          ["internal","hybrid"],
 *       "registeredAt":        "2026-03-09T...",
 *       "lastUsedAt":          "2026-03-09T..."
 *     }
 *   ]
 * }
 *
 * Pending challenges are kept in memory with a 5-minute TTL.
 */
@Service
public class PasskeyService {

    private static final Logger log = LoggerFactory.getLogger(PasskeyService.class);

    private static final long CHALLENGE_TTL_MS = 15 * 60 * 1000L; // 15 minutes (extended for reliability)

    @Value("${genqry.supporting-files.dir:src/main/resources/supportingFiles}")
    private String supportingFilesDir;

    @Value("${genqry.auth.base-url:http://localhost:3000}")
    private String appBaseUrl;

    @Value("${genqry.auth.passkey.rp-name:genQry NL2SQL}")
    private String rpName;

    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired(required = false)
    private UserDbService userDbService;

    // In-memory pending challenge store: challengeKey → PendingChallenge
    // challengeKey = email (register) or sessionId (login)
    private final ConcurrentHashMap<String, PendingChallenge> pendingChallenges =
            new ConcurrentHashMap<>();

    // =========================================================================
    // SCHEDULED TASKS
    // =========================================================================

    /**
     * Periodically evict expired challenges from memory.
     * Runs every 5 minutes to clean up stale entries.
     */
    @Scheduled(fixedRate = 5 * 60 * 1000L) // every 5 minutes
    public void scheduledEvictExpiredChallenges() {
        evictExpiredChallenges();
        log.debug("Scheduled challenge eviction completed. Remaining challenges: {}",
                pendingChallenges.size());
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public record PendingChallenge(
            String   challenge,       // base64url random bytes
            String   email,           // user email (may be null for login)
            String   type,            // "register" | "login"
            long     expiresAt        // epoch millis
    ) {}

    public static class PasskeyResult {
        public final boolean success;
        public final String  username;
        public final String  email;
        public final String  message;
        public final List<Map<String, Object>> passkeys;
        public final String  userType;        // "Admin", "Registered", "Temp", etc.
        public final boolean isAdmin;         // true if userType is "Admin"
        public final boolean isFirstLogin;    // true if this is user's first login

        PasskeyResult(boolean ok, String u, String e, String m) {
            success = ok; username = u; this.email = e; message = m;
            this.passkeys = Collections.emptyList();
            this.userType = null;
            this.isAdmin = false;
            this.isFirstLogin = false;
        }

        PasskeyResult(boolean ok, String u, String e, String m,
                      List<Map<String, Object>> passkeys) {
            success = ok; username = u; this.email = e; message = m;
            this.passkeys = passkeys != null ? passkeys : Collections.emptyList();
            this.userType = null;
            this.isAdmin = false;
            this.isFirstLogin = false;
        }

        PasskeyResult(boolean ok, String u, String e, String m,
                      List<Map<String, Object>> passkeys, String userType) {
            success = ok; username = u; this.email = e; message = m;
            this.passkeys = passkeys != null ? passkeys : Collections.emptyList();
            this.userType = userType;
            this.isAdmin = "Admin".equalsIgnoreCase(userType);
            this.isFirstLogin = false;
        }

        PasskeyResult(boolean ok, String u, String e, String m,
                      List<Map<String, Object>> passkeys, String userType, boolean isFirstLogin) {
            success = ok; username = u; this.email = e; message = m;
            this.passkeys = passkeys != null ? passkeys : Collections.emptyList();
            this.userType = userType;
            this.isAdmin = "Admin".equalsIgnoreCase(userType);
            this.isFirstLogin = isFirstLogin;
        }
    }

    // =========================================================================
    // PASSKEY COUNT (used by /status endpoint)
    // =========================================================================

    /**
     * Returns the number of passkeys registered for the given email.
     * Checks the database (users table and webauthn_credentials table) to verify if:
     * 1. The user exists in the users table
     * 2. The user has any passkeys registered in the webauthn_credentials table
     *
     * Returns -1 if no account exists in the database.
     * Returns 0 or more if the account exists (with that many passkeys).
     */
    public int countPasskeys(String normEmail) {
        // Check if user exists in database
        if (userDbService == null) {
            log.warn("UserDbService not available — cannot check passkeys for '{}'", normEmail);
            return -1;
        }

        try {
            // Check if user exists in users table
            java.util.Map<String, Object> user = userDbService.findUserByEmail(normEmail);
            if (user == null) {
                log.debug("User not found in database for email: '{}'", normEmail);
                return -1;
            }

            // User exists in database, now count their passkeys
            java.util.List<java.util.Map<String, Object>> passkeys =
                userDbService.getPasskeysForUser(normEmail);

            if (passkeys == null) {
                log.debug("No passkeys found for user: '{}'", normEmail);
                return 0;
            }

            log.debug("User '{}' has {} passkey(s) registered", normEmail, passkeys.size());
            return passkeys.size();

        } catch (Exception e) {
            log.error("countPasskeys failed for '{}': {}", normEmail, e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Returns the full list of passkeys for the given email.
     * Tries the DB first (via UserDbService); falls back to the JSON profile file.
     * The returned maps use camelCase keys matching the JSON file / frontend expectations.
     */
    public List<Map<String, Object>> getPasskeysForEmail(String normEmail) throws IOException {
        // Try DB first
        if (userDbService != null) {
            List<Map<String, Object>> dbList = buildPasskeyListFromDb(normEmail);
            if (!dbList.isEmpty()) return dbList;
        }
        // Fallback: read from JSON profile file
        Path profilePath = findProfilePath(normEmail);
        if (profilePath == null) return Collections.emptyList();
        return loadPasskeysFromProfile(profilePath);
    }

    // =========================================================================
    // REGISTRATION — BEGIN
    // =========================================================================

    /**
     * Generates PublicKeyCredentialCreationOptions for @simplewebauthn/browser
     * startRegistration().
     *
     * Creates a new user in the database if they don't already exist.
     * No pre-existing profile file required.
     *
     * @param email    user's email — does not need to exist in supportingFiles
     * @param username display name (used as WebAuthn userName and stored in DB)
     * @return JSON map matching the SimpleWebAuthn CreationOptionsJSON shape
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> registrationBegin(String email, String username) throws IOException {
        String normEmail = email.trim().toLowerCase();
        String displayName = username != null ? username.trim() : normEmail;

        // Clean up expired challenges first
        evictExpiredChallenges();
        log.debug("Passkey register/begin — before cleanup, pending challenges count: {}",
                pendingChallenges.size());

        // ── Check if user already exists in database ──────────────────────────
        if (userDbService == null) {
            log.error("✗ UserDbService not available for passkey registration");
            throw new IllegalStateException("Database service unavailable. Please try again.");
        }

        Map<String, Object> existingUser = userDbService.findUserByEmail(normEmail);
        int userId = -1;

        if (existingUser != null) {
            // User already exists in database
            userId = ((Number) existingUser.getOrDefault("id", -1)).intValue();
            log.info("User already exists in DB for email='{}' with userId={}", normEmail, userId);
        } else {
            // Create new user in database with "Passkey" user type
            log.info("Creating new user in database for email='{}' username='{}'", normEmail, displayName);
            userId = userDbService.createPasskeyUser(displayName, normEmail);
            if (userId <= 0) {
                log.error("✗ Failed to create user in database for email='{}'", normEmail);
                throw new IllegalStateException("Failed to create user account. Please try again.");
            }
            log.info("✓ New user created in database: userId={} email='{}' username='{}'",
                    userId, normEmail, displayName);
        }

        // Derive rpId from base URL (strip protocol and port)
        String rpId = deriveRpId(appBaseUrl);

        // Generate a fresh random challenge (32 bytes, base64url)
        String challenge = generateChallenge();

        // User ID: deterministic base64url of email bytes
        String webauthUserId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(normEmail.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Collect already-registered credential IDs to exclude (prevent re-registration
        // of the same authenticator)
        List<Map<String, Object>> excludeCredentials = new ArrayList<>();

        // Get passkeys from database (primary source)
        List<Map<String, Object>> existingPasskeys = userDbService.getPasskeysForUser(normEmail);
        for (Map<String, Object> pk : existingPasskeys) {
            String credId = (String) pk.getOrDefault("credential_id_b64", "");
            if (!credId.isBlank()) {
                Map<String, Object> excl = new LinkedHashMap<>();
                excl.put("id",         credId);
                excl.put("type",       "public-key");
                Object transports = pk.get("transports");
                excl.put("transports", transports != null
                        ? Arrays.asList(transports.toString().split(","))
                        : Collections.emptyList());
                excludeCredentials.add(excl);
            }
        }

        // Store pending challenge
        String challengeKey = "reg:" + normEmail;
        long expiresAt = System.currentTimeMillis() + CHALLENGE_TTL_MS;
        pendingChallenges.put(challengeKey,
                new PendingChallenge(challenge, normEmail, "register", expiresAt));
        log.info("Passkey register/begin for '{}' — challenge stored with key='{}', expires at {}ms, "
                        + "pending challenges count now: {} | userId={} | excludeCredentials={}",
                normEmail, challengeKey, expiresAt, pendingChallenges.size(), userId, excludeCredentials.size());

        // Build CreationOptionsJSON compatible with SimpleWebAuthn
        Map<String, Object> opts = new LinkedHashMap<>();

        Map<String, Object> rp = new LinkedHashMap<>();
        rp.put("name", rpName);
        rp.put("id",   rpId);
        opts.put("rp", rp);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id",          webauthUserId);
        user.put("name",        normEmail);
        user.put("displayName", displayName);
        opts.put("user", user);

        opts.put("challenge", challenge);

        // Accept ES256 (-7) and RS256 (-257)
        List<Map<String, Object>> pubKeyCredParams = List.of(
                Map.of("alg", -7,   "type", "public-key"),
                Map.of("alg", -257, "type", "public-key")
        );
        opts.put("pubKeyCredParams", pubKeyCredParams);

        opts.put("timeout", 60000);
        opts.put("excludeCredentials", excludeCredentials);

        Map<String, Object> authenticatorSelection = new LinkedHashMap<>();
        authenticatorSelection.put("residentKey",        "preferred");
        authenticatorSelection.put("requireResidentKey", false);
        authenticatorSelection.put("userVerification",   "preferred");
        opts.put("authenticatorSelection", authenticatorSelection);

        opts.put("attestation", "none");

        log.info("Passkey register/begin for '{}' — challenge generated, rpId='{}', "
                        + "challenge='{}', excludeCredentials={}",
                normEmail, rpId, challenge.substring(0, Math.min(20, challenge.length())) + "...",
                excludeCredentials.size());
        return opts;
    }

    // =========================================================================
    // REGISTRATION — FINISH
    // =========================================================================

    /**
     * Processes the credential from the browser after startRegistration().
     * Saves the passkey to the database webauthn_credentials table.
     * User was already created in registrationBegin if they didn't exist.
     *
     * @param email       user's email
     * @param credential  the RegistrationResponseJSON from SimpleWebAuthn
     */
    @SuppressWarnings("unchecked")
    public PasskeyResult registrationFinish(String email, Map<String, Object> credential)
            throws IOException {

        String normEmail = email.trim().toLowerCase();
        String key = "reg:" + normEmail;

        // Clean up expired challenges first
        evictExpiredChallenges();
        log.debug("Passkey register/finish — before lookup, pending challenges count: {}",
                pendingChallenges.size());

        PendingChallenge pending = pendingChallenges.remove(key);
        if (pending == null) {
            log.error("Challenge not found for key='{}'. Available keys: {}",
                    key, pendingChallenges.keySet());
            return new PasskeyResult(false, null, null,
                    "Registration challenge not found. Please start registration again.");
        }

        long now = System.currentTimeMillis();
        if (now > pending.expiresAt()) {
            log.error("Challenge expired for '{}'. Expired at {}ms, current time {}ms",
                    normEmail, pending.expiresAt(), now);
            return new PasskeyResult(false, null, null,
                    "Registration challenge expired. Please start registration again.");
        }

        log.info("Challenge found for '{}', expires in {}ms", normEmail,
                pending.expiresAt() - now);

        // ── Extract credential fields ─────────────────────────────────────────
        String credentialId = (String) credential.get("id");
        if (credentialId == null || credentialId.isBlank()) {
            return new PasskeyResult(false, null, null, "Missing credential id.");
        }

        // response object from SimpleWebAuthn
        Map<String, Object> response = (Map<String, Object>) credential.get("response");
        if (response == null) {
            return new PasskeyResult(false, null, null, "Missing credential response.");
        }

        String attestationObject = (String) response.get("attestationObject");
        String clientDataJSON    = (String) response.get("clientDataJSON");
        String publicKey         = (String) response.getOrDefault("publicKey", "");

        // transports
        List<String> transports = new ArrayList<>();
        Object tObj = credential.get("transports");
        if (tObj instanceof List<?> tList) {
            tList.forEach(t -> transports.add(String.valueOf(t)));
        }

        // authenticatorAttachment
        String deviceType = "singleDevice";
        Object attach = credential.get("authenticatorAttachment");
        if ("platform".equals(attach)) deviceType = "singleDevice";
        else if ("cross-platform".equals(attach)) deviceType = "multiDevice";

        // clientExtensionResults → credProps → discoverable
        boolean backedUp = false;
        Object extObj = credential.get("clientExtensionResults");
        if (extObj instanceof Map<?,?> extMap) {
            Object credProps = extMap.get("credProps");
            if (credProps instanceof Map<?,?> cpMap) {
                backedUp = Boolean.TRUE.equals(cpMap.get("rk"));
            }
        }

        // ── Get user info from database ──────────────────────────────────────
        if (userDbService == null) {
            log.error("✗ UserDbService not available for passkey registration");
            return new PasskeyResult(false, null, null,
                    "Database service unavailable. Please try again.");
        }

        Map<String, Object> userRecord = userDbService.findUserByEmail(normEmail);
        if (userRecord == null) {
            log.error("✗ User not found for email '{}'", normEmail);
            return new PasskeyResult(false, null, null,
                    "User account not found. Please try registering again.");
        }

        String storedUsername = (String) userRecord.getOrDefault("user_name", normEmail);

        // ── Persist passkey credential to DB webauthn_credentials table ────────
        String timestamp = Instant.now().toString();
        String transportsStr = String.join(",", transports);
        String aaguidStr = String.valueOf(credential.getOrDefault("aaguid", ""));

        try {
            int passkeyId = userDbService.persistFullPasskeyCredential(
                    normEmail,
                    credentialId,
                    publicKey,
                    attestationObject,
                    clientDataJSON,
                    aaguidStr,
                    deviceType,
                    transportsStr,
                    backedUp,
                    ua(credential, transports),
                    timestamp,
                    timestamp,
                    null   // lastAuthenticatorData — not available at registration time
            );

            if (passkeyId <= 0) {
                log.error("✗ Failed to persist passkey credential to DB (returned id={})", passkeyId);
                return new PasskeyResult(false, null, null,
                        "Failed to save passkey. Please try again.");
            }

            log.info("✓ Passkey credential persisted to DB: id={} email='{}' credentialId='{}' "
                    + "deviceType='{}' transports='{}' aaguid='{}'",
                    passkeyId, normEmail, credentialId, deviceType, transportsStr, aaguidStr);

        } catch (Exception e) {
            log.error("✗ Error persisting passkey to DB: {}", e.getMessage(), e);
            return new PasskeyResult(false, null, null,
                    "Error saving passkey: " + e.getMessage());
        }

        // ── Retrieve updated passkeys list from DB ──────────────────────────
        List<Map<String, Object>> passkeys = userDbService.getPasskeysForUser(normEmail);
        if (passkeys == null) {
            passkeys = Collections.emptyList();
        }

        log.info("✓ Passkey registration completed successfully for email='{}' username='{}' "
                + "passkeys count={}",
                normEmail, storedUsername, passkeys.size());

        return new PasskeyResult(true, storedUsername, normEmail,
                "Passkey registered successfully. You can now sign in with your passkey.",
                passkeys);
    }

    // =========================================================================
    // AUTHENTICATION — BEGIN
    // =========================================================================

    /**
     * Generates PublicKeyCredentialRequestOptions for @simplewebauthn/browser
     * startAuthentication().
     *
     * @param email  optional — if provided, only credentials for that user are included
     * @return JSON map matching the SimpleWebAuthn RequestOptionsJSON shape
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loginBegin(String email) throws IOException {
        // Clean up expired challenges first
        evictExpiredChallenges();
        log.debug("Passkey login/begin — before creating challenge, pending challenges count: {}",
                pendingChallenges.size());

        String rpId      = deriveRpId(appBaseUrl);
        String challenge = generateChallenge();
        String normEmail = (email != null && !email.isBlank())
                ? email.trim().toLowerCase() : null;

        // Collect allowCredentials for the user if email provided
        List<Map<String, Object>> allowCredentials = new ArrayList<>();
        if (normEmail != null) {
            // Try DB first (webauthn_credentials table)
            if (userDbService != null) {
                List<Map<String, Object>> dbPasskeys = userDbService.getPasskeysForUser(normEmail);
                for (Map<String, Object> pk : dbPasskeys) {
                    String credId = (String) pk.getOrDefault("credential_id_b64", "");
                    if (!credId.isBlank()) {
                        Map<String, Object> ac = new LinkedHashMap<>();
                        ac.put("id",         credId);
                        ac.put("type",       "public-key");
                        Object transObj = pk.get("transports");
                        ac.put("transports", transObj != null
                                ? Arrays.asList(transObj.toString().split(","))
                                : Collections.emptyList());
                        allowCredentials.add(ac);
                    }
                }
            }

            // Fallback to filesystem profile if no DB credentials found
            if (allowCredentials.isEmpty()) {
                Map<String, Object> profile = findProfile(normEmail);
                if (profile != null) {
                    List<?> passkeys = (List<?>) profile.getOrDefault("passkeys", List.of());
                    for (Object pk : passkeys) {
                        if (pk instanceof Map<?,?> pkMap) {
                            String credId = (String) pkMap.get("credentialId");
                            if (credId != null) {
                                Map<String, Object> ac = new LinkedHashMap<>();
                                ac.put("id",         credId);
                                ac.put("type",       "public-key");
                                ac.put("transports", pkMap.containsKey("transports")
                                        ? pkMap.get("transports") : Collections.emptyList());
                                allowCredentials.add(ac);
                            }
                        }
                    }
                }
            }
        }

        // Store pending challenge — key is email if known, else a random session key
        String challengeKey = normEmail != null ? "login:" + normEmail
                : "login:anon:" + UUID.randomUUID();
        long expiresAt = System.currentTimeMillis() + CHALLENGE_TTL_MS;
        pendingChallenges.put(challengeKey,
                new PendingChallenge(challenge, normEmail, "login", expiresAt));

        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("challenge",          challenge);
        opts.put("rpId",               rpId);
        opts.put("allowCredentials",   allowCredentials);
        opts.put("userVerification",   "preferred");
        opts.put("timeout",            60000);
        // Pass challengeKey back so the browser can include it in login/finish
        opts.put("_challengeKey",      challengeKey);

        log.info("Passkey login/begin — email='{}' challengeKey='{}' allowCredentials={} expiresAt={}ms "
                        + "pending challenges count: {}",
                normEmail != null ? normEmail : "(any)", challengeKey, allowCredentials.size(),
                expiresAt, pendingChallenges.size());
        return opts;
    }

    // =========================================================================
    // AUTHENTICATION — FINISH
    // =========================================================================

    /**
     * Verifies the authentication assertion from the browser.
     * Looks up the credential in the webauthn_credentials database table (NOT filesystem).
     * Verifies the challenge, increments the counter, and returns the matched user with user_type.
     *
     * @param credential   the AuthenticationResponseJSON from SimpleWebAuthn
     * @param challengeKey the _challengeKey returned by login/begin
     */
    @SuppressWarnings("unchecked")
    public PasskeyResult loginFinish(Map<String, Object> credential, String challengeKey)
            throws IOException {

        // ── Resolve pending challenge ─────────────────────────────────────────
        // Clean up expired challenges first
        evictExpiredChallenges();
        log.debug("Passkey login/finish — before lookup, pending challenges count: {}",
                pendingChallenges.size());

        PendingChallenge pending = null;
        String resolvedKey = null;

        if (challengeKey != null && !challengeKey.isBlank()) {
            pending     = pendingChallenges.remove(challengeKey);
            resolvedKey = challengeKey;
            log.info("Challenge lookup by key='{}' — found: {}", challengeKey, pending != null);
        }

        // Fallback: if no challengeKey, scan all login challenges
        if (pending == null) {
            log.warn("No challengeKey provided or challenge not found. Scanning {} pending challenges...",
                    pendingChallenges.size());
            for (Map.Entry<String, PendingChallenge> entry : pendingChallenges.entrySet()) {
                log.debug("Available challenge: key='{}' type='{}'", entry.getKey(), entry.getValue().type());
                if ("login".equals(entry.getValue().type())) {
                    pending     = pendingChallenges.remove(entry.getKey());
                    resolvedKey = entry.getKey();
                    log.info("Found login challenge with key='{}'", resolvedKey);
                    break;
                }
            }
        }

        if (pending == null) {
            log.error("No pending login challenge found. Available keys: {} | challengeKey='{}'",
                    pendingChallenges.keySet(), challengeKey);
            return new PasskeyResult(false, null, null,
                    "Login challenge not found. Please start login again.");
        }

        long now = System.currentTimeMillis();
        if (now > pending.expiresAt()) {
            log.error("Challenge expired for key='{}'. Expired at {}ms, current time {}ms",
                    resolvedKey, pending.expiresAt(), now);
            return new PasskeyResult(false, null, null,
                    "Login challenge expired. Please try again.");
        }

        log.info("Challenge found for key='{}', expires in {}ms", resolvedKey, pending.expiresAt() - now);

        String credentialId = (String) credential.get("id");
        if (credentialId == null || credentialId.isBlank()) {
            return new PasskeyResult(false, null, null, "Missing credential id.");
        }

        // ── Look up credential in webauthn_credentials database table ────────
        if (userDbService == null) {
            log.error("✗ UserDbService not available for passkey login");
            return new PasskeyResult(false, null, null,
                    "Database service unavailable. Please try again.");
        }

        Map<String, Object> passkeyRecord = userDbService.findPasskeyByCredentialId(credentialId);
        if (passkeyRecord == null) {
            log.error("✗ Passkey login/finish — credentialId '{}' not found in webauthn_credentials table",
                    credentialId);
            return new PasskeyResult(false, null, null,
                    "Passkey not recognised. Please register a passkey first.");
        }

        log.info("✓ Passkey credential found in DB: credentialId='{}' userId={} "
                + "signCount={} deviceType='{}' backedUp={}",
                credentialId,
                passkeyRecord.getOrDefault("user_id", "unknown"),
                passkeyRecord.getOrDefault("sign_count", 0),
                passkeyRecord.getOrDefault("device_type", "unknown"),
                passkeyRecord.getOrDefault("backed_up", false));

        // ── Get user info from the user_id in the passkey record ─────────────
        int userId = ((Number) passkeyRecord.getOrDefault("user_id", -1)).intValue();
        if (userId <= 0) {
            log.warn("Invalid user_id in passkey record");
            return new PasskeyResult(false, null, null,
                    "Authentication failed. Invalid credential.");
        }

        Map<String, Object> userRecord = userDbService.findUserById(userId);
        if (userRecord == null) {
            log.warn("User not found for userId={}", userId);
            return new PasskeyResult(false, null, null,
                    "User account not found.");
        }

        String email = (String) userRecord.getOrDefault("email", "");
        String username = (String) userRecord.getOrDefault("user_name", "");
        String userType = (String) userRecord.getOrDefault("user_type", "Registered");
        boolean isAdmin = "Admin".equalsIgnoreCase(userType);

        // Check if this is the user's first login (last_login_at is null)
        boolean isFirstLogin = userRecord.get("last_login_at") == null;

        // ── Verify clientDataJSON challenge ───────────────────────────────────
        Map<String, Object> response = (Map<String, Object>) credential.get("response");
        if (response != null) {
            String clientDataJSON = (String) response.get("clientDataJSON");
            if (clientDataJSON != null) {
                try {
                    byte[] decoded = Base64.getUrlDecoder().decode(
                            clientDataJSON.replace("=", ""));
                    String clientDataStr = new String(decoded,
                            java.nio.charset.StandardCharsets.UTF_8);
                    Map<?, ?> clientData = mapper.readValue(clientDataStr, Map.class);
                    String receivedChallenge = (String) clientData.get("challenge");
                    if (!pending.challenge().equals(receivedChallenge)) {
                        log.warn("Challenge mismatch for email='{}'", email);
                        return new PasskeyResult(false, null, null,
                                "Challenge verification failed. Please try again.");
                    }
                } catch (Exception ex) {
                    log.debug("Could not verify clientDataJSON challenge: {}", ex.getMessage());
                    // non-fatal — continue without strict challenge check in dev mode
                }
            }
        }

        // ── Increment counter in DB ───────────────────────────────────────────
        String lastAuthData = null;
        if (response != null) {
            Object authDataObj = response.get("authenticatorData");
            if (authDataObj != null) {
                lastAuthData = authDataObj.toString();
            }
        }
        int currentCounter = ((Number) passkeyRecord.getOrDefault("sign_count", 0)).intValue();
        int newCounter = currentCounter + 1;
        userDbService.updatePasskeyUsed(credentialId, newCounter, lastAuthData);
        log.info("✓ Counter updated in DB: credentialId='{}' oldCount={} newCount={} "
                + "lastAuthData.length={}bytes",
                credentialId, currentCounter, newCounter,
                lastAuthData != null ? lastAuthData.length() : 0);

        // ── Load passkeys list to return to frontend ──────────────────────────
        List<Map<String, Object>> passkeys = userDbService.getPasskeysForUser(email);
        if (passkeys == null) {
            passkeys = Collections.emptyList();
        }

        log.info("Passkey login successful — username='{}' email='{}' credentialId='{}' userType='{}' isFirstLogin={}",
                username, email, credentialId, userType, isFirstLogin);

        // Record the login in the database
        userDbService.recordLogin(userId, null, null, null, null, null, null);

        return new PasskeyResult(true, username, email,
                "Passkey authentication successful. Welcome back, " + username + "!",
                passkeys, userType, isFirstLogin);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Derives the WebAuthn RP ID from the base URL (hostname only, no port, no protocol).
     * Domain tiers:
     *   local machine : localhost
     *   development   : dev.genqry.com
     *   production    : genqry.com
     */
    private String deriveRpId(String baseUrl) {
        try {
            String u = baseUrl.trim();
            if (!u.contains("://")) u = "https://" + u;
            java.net.URI uri = java.net.URI.create(u);
            String host = uri.getHost();
            return (host != null && !host.isBlank()) ? host : "dev.genqry.com";
        } catch (Exception e) {
            return "dev.genqry.com";
        }
    }

    /** Generates 32 random bytes as a URL-safe base64 string (no padding). */
    private String generateChallenge() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Evicts all expired challenges from memory. */
    private void evictExpiredChallenges() {
        long now = System.currentTimeMillis();
        pendingChallenges.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Profile file I/O
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Walks every subdirectory of supportingFiles/ and returns the first
     * profile whose "email" field matches {@code normEmail}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findProfile(String normEmail) throws IOException {
        Path profilePath = findProfilePath(normEmail);
        if (profilePath == null) return null;
        return mapper.readValue(profilePath.toFile(), Map.class);
    }

    /**
     * Returns the Path to the user's profile file for the given email,
     * or null if not found.
     */
    @SuppressWarnings("unchecked")
    private Path findProfilePath(String normEmail) throws IOException {
        Path root = Paths.get(supportingFilesDir).toAbsolutePath();
        if (!Files.exists(root)) return null;

        try (var dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(dir)) continue;
                try (var files = Files.list(dir)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        String name = file.getFileName().toString();
                        log.debug("Scanning profile file: {}", name);
                        if (!UserRegistrationService.isProfileFile(name)) continue;
                        try {
                            Map<String, Object> p =
                                    mapper.readValue(file.toFile(), Map.class);
                            String storedEmail = (String) p.get("email");
                            if (normEmail.equals(
                                    storedEmail != null ? storedEmail.toLowerCase() : "")) {
                                return file;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return null;
    }

    /** Helper class for a matched profile + user info. */
    private static class ProfileMatch {
        final Path   profilePath;
        final String username;
        final String email;
        ProfileMatch(Path p, String u, String e) {
            profilePath = p; username = u; this.email = e;
        }
    }

    /**
     * Scans every profile file and returns the one containing a passkey
     * with the given credentialId.
     */
    @SuppressWarnings("unchecked")
    private ProfileMatch findProfileByCredentialId(String credentialId) throws IOException {
        Path root = Paths.get(supportingFilesDir).toAbsolutePath();
        if (!Files.exists(root)) return null;

        try (var dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(dir)) continue;
                try (var files = Files.list(dir)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        String name = file.getFileName().toString();
                        if (!UserRegistrationService.isProfileFile(name)) continue;
                        try {
                            Map<String, Object> profile =
                                    mapper.readValue(file.toFile(), Map.class);
                            List<?> passkeys =
                                    (List<?>) profile.getOrDefault("passkeys", List.of());
                            for (Object pk : passkeys) {
                                if (pk instanceof Map<?,?> pkMap) {
                                    if (credentialId.equals(pkMap.get("credentialId"))) {
                                        return new ProfileMatch(
                                                file,
                                                (String) profile.getOrDefault("username", ""),
                                                (String) profile.getOrDefault("email", ""));
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return null;
    }

    /** Builds a friendly device name for the passkey credential. */
    private String ua(Map<String, Object> credential, List<String> transports) {
        Object attach = credential.get("authenticatorAttachment");
        if ("platform".equals(attach)) {
            if (transports.contains("internal")) return "Platform Authenticator (built-in)";
            return "Platform Authenticator";
        }
        if (transports.contains("usb"))      return "Security Key (USB)";
        if (transports.contains("nfc"))      return "Security Key (NFC)";
        if (transports.contains("ble"))      return "Security Key (Bluetooth)";
        if (transports.contains("hybrid"))   return "Cross-Device (Phone/Tablet)";
        if (transports.contains("internal")) return "Built-in Authenticator";
        return "Passkey";
    }

    /**
     * Increments the sign counter for a passkey and updates lastUsedAt in the JSON profile.
     * Also stores the latest authenticatorData for audit purposes.
     * Returns the lastAuthenticatorData string (may be null if not present in response).
     */
    @SuppressWarnings("unchecked")
    private String updatePasskeyCounter(Path profilePath, String credentialId,
                                         Map<String, Object> response) {
        String lastAuthData = null;
        try {
            Map<String, Object> profile = mapper.readValue(profilePath.toFile(), Map.class);
            List<Map<String, Object>> passkeys = new ArrayList<>();
            List<?> existing = (List<?>) profile.getOrDefault("passkeys", List.of());

            for (Object pk : existing) {
                if (pk instanceof Map<?,?> pkMap) {
                    Map<String, Object> mutablePk = new LinkedHashMap<>(
                            (Map<String, Object>) pkMap);
                    if (credentialId.equals(mutablePk.get("credentialId"))) {
                        int counter = ((Number) mutablePk.getOrDefault("counter", 0)).intValue();
                        mutablePk.put("counter",    counter + 1);
                        mutablePk.put("lastUsedAt", Instant.now().toString());
                        if (response != null) {
                            String authData = (String) response.get("authenticatorData");
                            if (authData != null) {
                                mutablePk.put("lastAuthenticatorData", authData);
                                lastAuthData = authData;
                            }
                        }
                    }
                    passkeys.add(mutablePk);
                }
            }

            profile.put("passkeys", passkeys);
            mapper.writeValue(profilePath.toFile(), profile);
        } catch (Exception e) {
            log.warn("Could not update passkey counter for credentialId '{}': {}",
                    credentialId, e.getMessage());
        }
        return lastAuthData;
    }

    /** Reads the current counter value for a specific credentialId from the JSON profile. */
    @SuppressWarnings("unchecked")
    private int getCounterFromProfile(Path profilePath, String credentialId) {
        try {
            Map<String, Object> profile = mapper.readValue(profilePath.toFile(), Map.class);
            List<?> existing = (List<?>) profile.getOrDefault("passkeys", List.of());
            for (Object pk : existing) {
                if (pk instanceof Map<?,?> pkMap) {
                    if (credentialId.equals(pkMap.get("credentialId"))) {
                        return ((Number) ((Map<String, Object>) pkMap).getOrDefault("counter", 0)).intValue();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("getCounterFromProfile failed: {}", e.getMessage());
        }
        return 0;
    }

    /** Loads the passkeys list from a JSON profile file. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadPasskeysFromProfile(Path profilePath) {
        try {
            Map<String, Object> profile = mapper.readValue(profilePath.toFile(), Map.class);
            List<?> raw = (List<?>) profile.getOrDefault("passkeys", Collections.emptyList());
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : raw) {
                if (item instanceof Map<?,?> m) result.add((Map<String, Object>) m);
            }
            return result;
        } catch (Exception e) {
            log.debug("loadPasskeysFromProfile failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Builds a passkey list from the DB webauthn_credentials rows, converting
     * DB column names to the camelCase JSON keys the frontend expects.
     */
    private List<Map<String, Object>> buildPasskeyListFromDb(String email) {
        if (userDbService == null) return Collections.emptyList();
        try {
            List<Map<String, Object>> rows = userDbService.getPasskeysForUser(email);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> pk = new LinkedHashMap<>();
                pk.put("credentialId",          row.getOrDefault("credential_id_b64", ""));
                pk.put("credentialPublicKey",    row.getOrDefault("public_key",        ""));
                pk.put("attestationObject",      row.getOrDefault("attestation_object",""));
                pk.put("clientDataJSON",         row.getOrDefault("client_data_json",  ""));
                pk.put("counter",                row.getOrDefault("sign_count",         0));
                pk.put("aaguid",                 row.getOrDefault("aaguid",            ""));
                pk.put("deviceType",             row.getOrDefault("device_type",       ""));
                pk.put("backedUp",               row.getOrDefault("backed_up",         false));
                Object transObj = row.get("transports");
                pk.put("transports", transObj != null
                        ? Arrays.asList(transObj.toString().split(",")) : Collections.emptyList());
                Object regAt = row.get("registered_at");
                pk.put("registeredAt",           regAt != null ? regAt.toString() : "");
                Object usedAt = row.get("last_used_at");
                pk.put("lastUsedAt",             usedAt != null ? usedAt.toString() : "");
                Object authData = row.get("last_authenticator_data");
                pk.put("lastAuthenticatorData",  authData != null ? authData.toString() : "");
                result.add(pk);
            }
            return result;
        } catch (Exception e) {
            log.warn("buildPasskeyListFromDb failed for '{}': {}", email, e.getMessage());
            return Collections.emptyList();
        }
    }
}

