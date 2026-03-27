package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.config.JwtTokenProvider;
import com.nlp.rag.seek.service.PasskeyService;
import com.nlp.rag.seek.service.PasskeyService.PasskeyResult;
import com.nlp.rag.seek.service.UserDbService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PasskeyController — WebAuthn / Passkey endpoints.
 *
 * Base path: /api/v1/auth/passkey
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │ POST /register/begin    Generate PublicKeyCredentialCreationOptions      │
 * │ POST /register/finish   Verify + persist passkey in user profile JSON    │
 * │ POST /login/begin       Generate PublicKeyCredentialRequestOptions       │
 * │ POST /login/finish      Verify assertion, return user session info       │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * All passkey data is written to:
 *   supportingFiles/{username}/{email}_DBSchema_{timestamp}.json
 * under a "passkeys" array, so it lives alongside the Fernet-encrypted
 * password and verification status for that user.
 */
@RestController
@RequestMapping("/api/v1/auth/passkey")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Passkey", description = "WebAuthn / Passkey registration and authentication")
public class PasskeyController {

    private static final Logger log = LoggerFactory.getLogger(PasskeyController.class);

    @Autowired
    private PasskeyService passkeyService;

    @Autowired
    private UserDbService userDbService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // =========================================================================
    // GET /api/v1/auth/passkey/list?email=user@example.com
    // =========================================================================

    /**
     * Returns all registered passkeys for the given email.
     * Tries the DB first; falls back to the JSON profile file.
     *
     * Response (200):
     * { "email": "...", "passkeys": [ { ...full passkey fields... } ] }
     */
    @Operation(summary = "List registered passkeys", description = "Returns all registered passkeys for the given email")
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String email) {

        log.debug("GET /passkey/list — email='{}'", email);

        if (email == null || email.isBlank()) {
            return bad("email query parameter is required");
        }

        try {
            String normEmail = email.trim().toLowerCase();
            java.util.List<java.util.Map<String, Object>> passkeys =
                    passkeyService.getPasskeysForEmail(normEmail);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("email",    normEmail);
            resp.put("passkeys", passkeys);
            resp.put("count",    passkeys.size());
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("passkey/list failed: {}", e.getMessage(), e);
            return serverError("Could not retrieve passkeys: " + e.getMessage());
        }
    }

    // =========================================================================
    // GET /api/v1/auth/passkey/status?email=user@example.com
    // =========================================================================

    /**
     * Returns the passkey registration status for a given email.
     * Called by the UI before invoking the browser WebAuthn dialog so it can
     * show a helpful message instead of the OS QR-code sheet when no passkeys
     * are registered yet.
     *
     * Response (200):
     * {
     *   "email":           "user@example.com",
     *   "hasPasskeys":     true,
     *   "count":           2,
     *   "accountExists":   true
     * }
     */
    @Operation(summary = "Check passkey registration status", description = "Returns whether the email has any registered passkeys")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestParam(required = false) String email) {

        log.debug("GET /passkey/status — email='{}'", email);

        if (email == null || email.isBlank()) {
            return bad("email query parameter is required");
        }

        try {
            String normEmail = email.trim().toLowerCase();
            int count = passkeyService.countPasskeys(normEmail);
            boolean accountExists = count >= 0; // -1 means account not found

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("email",         normEmail);
            resp.put("accountExists", accountExists && count >= 0);
            resp.put("hasPasskeys",   count > 0);
            resp.put("count",         Math.max(count, 0));
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("passkey/status failed: {}", e.getMessage(), e);
            return serverError("Could not check passkey status: " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/auth/passkey/register/begin
    // =========================================================================

    /**
     * Starts passkey registration.
     *
     * Request body:
     * { "email": "user@example.com", "username": "ashok" }
     *
     * Response (200):  PublicKeyCredentialCreationOptions JSON
     *   → pass directly to SimpleWebAuthn startRegistration()
     *
     * Response (400):  { "success": false, "message": "..." }
     * Response (404):  { "success": false, "message": "No account found..." }
     */
    @Operation(summary = "Begin passkey registration", description = "Generates PublicKeyCredentialCreationOptions for the browser WebAuthn API")
    @PostMapping("/register/begin")
    public ResponseEntity<Map<String, Object>> registerBegin(
            @RequestBody Map<String, String> body) {

        String email    = body.getOrDefault("email",    "").trim();
        String username = body.getOrDefault("username", "").trim();

        log.info("POST /passkey/register/begin — email='{}'", email);

        if (email.isBlank()) {
            return bad("email is required");
        }

        try {
            Map<String, Object> options = passkeyService.registrationBegin(email, username);
            return ResponseEntity.ok(options);
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("passkey/register/begin failed for '{}': {}", email, e.getMessage(), e);
            return serverError("Failed to begin passkey registration: " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/auth/passkey/register/finish
    // =========================================================================

    /**
     * Completes passkey registration.
     *
     * Request body:
     * {
     *   "email":      "user@example.com",
     *   "credential": { ...RegistrationResponseJSON from SimpleWebAuthn... }
     * }
     *
     * Response (200):
     * { "success": true, "username": "ashok", "email": "...", "message": "..." }
     *
     * Response (400):  challenge expired, missing fields, etc.
     */
    @Operation(summary = "Finish passkey registration", description = "Verifies the WebAuthn attestation and persists the passkey credential in the DB")
    @SuppressWarnings("unchecked")
    @PostMapping("/register/finish")
    public ResponseEntity<Map<String, Object>> registerFinish(
            @RequestBody Map<String, Object> body) {

        String email = ((String) body.getOrDefault("email", "")).trim();
        Object credObj = body.get("credential");

        log.info("POST /passkey/register/finish — email='{}'", email);

        if (email.isBlank()) {
            return bad("email is required");
        }
        if (!(credObj instanceof Map)) {
            return bad("credential object is required");
        }

        try {
            PasskeyResult result = passkeyService.registrationFinish(
                    email, (Map<String, Object>) credObj);

            if (result.success) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success",  true);
                resp.put("username", result.username);
                resp.put("email",    result.email);
                resp.put("message",  result.message);
                resp.put("passkeys", result.passkeys);
                return ResponseEntity.ok(resp);
            } else {
                return bad(result.message);
            }

        } catch (Exception e) {
            log.error("passkey/register/finish failed for '{}': {}", email, e.getMessage(), e);
            return serverError("Passkey registration failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/auth/passkey/login/begin
    // =========================================================================

    /**
     * Starts passkey authentication.
     *
     * Request body (email is optional — omit for discoverable credential flow):
     * { "email": "user@example.com" }   or   {}
     *
     * Response (200):  PublicKeyCredentialRequestOptions JSON
     *   → pass directly to SimpleWebAuthn startAuthentication()
     *   → also contains "_challengeKey" — send this back in login/finish
     *
     * Response (500):  { "success": false, "message": "..." }
     */
    @PostMapping("/login/begin")
    public ResponseEntity<Map<String, Object>> loginBegin(
            @RequestBody(required = false) Map<String, String> body) {

        String email = (body != null) ? body.getOrDefault("email", "").trim() : "";
        log.info("POST /passkey/login/begin — email='{}'",
                email.isBlank() ? "(discoverable)" : email);

        try {
            Map<String, Object> options = passkeyService.loginBegin(
                    email.isBlank() ? null : email);
            return ResponseEntity.ok(options);
        } catch (Exception e) {
            log.error("passkey/login/begin failed: {}", e.getMessage(), e);
            return serverError("Failed to begin passkey login: " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/auth/passkey/login/finish
    // =========================================================================

    /**
     * Completes passkey authentication.
     *
     * Request body:
     * {
     *   "credential":    { ...AuthenticationResponseJSON from SimpleWebAuthn... },
     *   "_challengeKey": "login:user@example.com"  (returned by login/begin)
     * }
     *
     * Response (200):
     * { "success": true, "username": "ashok", "email": "...", "message": "..." }
     *
     * Response (401):  credential not found / challenge expired
     * Response (500):  unexpected error
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/login/finish")
    public ResponseEntity<Map<String, Object>> loginFinish(
            @RequestBody Map<String, Object> body) {

        Object credObj     = body.get("credential");
        String challengeKey = (String) body.getOrDefault("_challengeKey", "");

        log.info("POST /passkey/login/finish — challengeKey='{}'", challengeKey);

        if (!(credObj instanceof Map)) {
            return bad("credential object is required");
        }

        try {
            PasskeyResult result = passkeyService.loginFinish(
                    (Map<String, Object>) credObj, challengeKey);

            if (result.success) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success",      true);
                resp.put("username",     result.username);
                resp.put("email",        result.email);
                resp.put("message",      result.message);
                resp.put("passkeys",     result.passkeys);
                resp.put("userType",     result.userType);
                resp.put("isAdmin",      result.isAdmin);
                resp.put("isFirstLogin", result.isFirstLogin);
                resp.put("token",        jwtTokenProvider.generateToken(result.email, result.username, result.userType));
                resp.put("selectDb",     "ecommerce");

                // Export user's transcript history from DB to JSON on passkey login
                try {
                    userDbService.exportUserTranscriptsToJson(result.username);
                } catch (Exception ex) {
                    log.warn("Transcript export on passkey login failed for '{}': {}", result.username, ex.getMessage());
                }

                return ResponseEntity.ok(resp);
            } else {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("message", result.message);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
            }

        } catch (Exception e) {
            log.error("passkey/login/finish failed: {}", e.getMessage(), e);
            return serverError("Passkey authentication failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ResponseEntity<Map<String, Object>> bad(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.badRequest().body(body);
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    private ResponseEntity<Map<String, Object>> serverError(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}

