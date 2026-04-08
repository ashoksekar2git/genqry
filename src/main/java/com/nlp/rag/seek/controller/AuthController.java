package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.config.JwtTokenProvider;
import com.nlp.rag.seek.model.LoginRequest;
import com.nlp.rag.seek.model.UserRegistrationRequest;
import com.nlp.rag.seek.model.ForgotPasswordRequest;
import com.nlp.rag.seek.service.UserAgentParser;
import com.nlp.rag.seek.service.UserDbService;
import com.nlp.rag.seek.service.UserRegistrationService;
import com.nlp.rag.seek.service.UserRegistrationService.LoginResult;
import com.nlp.rag.seek.service.UserRegistrationService.ForgotPasswordResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for user authentication.
 *
 * Base path: /api/v1/auth
 *
 * POST /register      Register a new user (file + DB)
 * GET  /verify        Verify email via token link
 * POST /login         Password-based login (file + DB)
 * POST /guest         Create a temporary guest session (DB only)
 */
@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Auth", description = "User registration, login, password reset, email verification")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private UserRegistrationService registrationService;

    @Autowired
    private UserDbService userDbService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // =========================================================================
    // POST /api/v1/auth/register
    // =========================================================================

    /**
     * Registers a new user.
     *
     * Request body:
     * {
     *   "username": "ashok",
     *   "email":    "ashok@example.com",
     *   "password": "mySecret123"
     * }
     *
     * On success (201 Created):
     * {
     *   "status":    "registered",
     *   "username":  "ashok",
     *   "email":     "ashok@example.com",
     *   "userDir":   "/absolute/path/to/supportingFiles/ashok",
     *   "profileFile": "ashok_example_com_ecommerce_03102026.json",
     *   "message":   "Registration successful. Please check your email to verify your account."
     * }
     *
     * On duplicate username (409 Conflict):
     * { "status": "error", "message": "Username 'ashok' is already registered..." }
     *
     * On validation error (400 Bad Request):
     * { "status": "error", "message": "email must be a valid email address" }
     */
    @Operation(summary = "Register a new user", description = "Creates user account and sends verification email")
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
            @Valid @RequestBody UserRegistrationRequest request,
            HttpServletRequest httpRequest) {

        log.info("POST /api/v1/auth/register — username='{}' email='{}'",
                request.getUsername(), request.getEmail());

        try {
            Path profileFile = registrationService.register(
                    request.getUsername(),
                    request.getEmail(),
                    request.getPassword());

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success",     true);
            resp.put("status",      "registered");
            resp.put("username",    request.getUsername().trim().toLowerCase()
                    .replaceAll("[^a-z0-9_]", "_"));
            resp.put("email",       request.getEmail().trim().toLowerCase());
            resp.put("userDir",     profileFile.getParent().toAbsolutePath().toString());
            resp.put("profileFile", profileFile.getFileName().toString());
            resp.put("message",
                    "Registration successful. Please check your email to verify your account.");

            log.info("Registration complete for '{}' → {}", request.getUsername(), profileFile);
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);

        } catch (IllegalArgumentException e) {
            log.warn("Registration rejected: {}", e.getMessage());
            return conflict("error", e.getMessage());

        } catch (Exception e) {
            log.error("Registration failed for '{}': {}", request.getUsername(), e.getMessage(), e);
            return serverError("Registration failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/auth/login
    // =========================================================================

    /**
     * Validates login credentials against the stored Fernet-encrypted profile.
     *
     * Request body:
     * {
     *   "email":    "mailashoky@gmail.com",
     *   "password": "mySecret123"
     * }
     *
     * On success (200 OK):
     * {
     *   "success":  true,
     *   "username": "admin",
     *   "email":    "mailashoky@gmail.com",
     *   "message":  "Login successful. Welcome back, admin!"
     * }
     *
     * On bad credentials (401):
     * { "success": false, "message": "Invalid email or password." }
     *
     * On unverified account (403):
     * { "success": false, "message": "Email address not verified yet. Please check your inbox." }
     */
    @Operation(summary = "Login with email/password", description = "Validates credentials and returns JWT token + user profile")
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        log.info("POST /api/v1/auth/login — email='{}'", request.getEmail());

        String ipAddress  = resolveClientIp(httpRequest);
        String userAgent  = httpRequest.getHeader("User-Agent");
        String sessionId  = UUID.randomUUID().toString();
        UserAgentParser.ParsedUA ua = UserAgentParser.parse(userAgent);

        try {
            LoginResult result = registrationService.login(
                    request.getEmail(), request.getPassword());

            switch (result.status) {

                case OK -> {
                    // Record login in DB
                    Map<String, Object> dbUser = userDbService.findUserForLogin(request.getEmail());
                    String userType = "User"; // default
                    boolean isFirstLogin = false;

                    if (dbUser != null) {
                        int userId = ((Number) dbUser.get("id")).intValue();

                        // Check if this is first login BEFORE recording it
                        isFirstLogin = userDbService.isFirstLogin(dbUser);

                        userDbService.recordLogin(userId, ipAddress,
                                ua.deviceType(), ua.os(), ua.browser(),
                                userAgent, sessionId);

                        // Use the actual user_type stored in DB ("Registered", "Temp", "Passkey", etc.)
                        Object dbUserType = dbUser.get("user_type");
                        if (dbUserType != null && !dbUserType.toString().isBlank()) {
                            userType = dbUserType.toString();
                        }
                    }

                    // fileList already has internal system files filtered by resolveUserFileList()
                    // Partition: databases (schema JSON) vs user-uploaded documents
                    java.util.List<java.util.Map<String, String>> allFiles = result.fileList;
                    java.util.List<java.util.Map<String, String>> databases = allFiles.stream()
                            .filter(f -> "database".equals(f.get("type")))
                            .collect(java.util.stream.Collectors.toList());
                    java.util.List<java.util.Map<String, String>> documents = allFiles.stream()
                            .filter(f -> "document".equals(f.get("type")))
                            .collect(java.util.stream.Collectors.toList());

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("success",      true);
                    resp.put("username",     result.username);
                    resp.put("email",        result.email);
                    resp.put("userType",     userType);
                    resp.put("sessionId",    sessionId);
                    resp.put("isFirstLogin", isFirstLogin);
                    resp.put("fileList",     allFiles);
                    resp.put("databases",    databases);
                    resp.put("documents",    documents);
                    resp.put("fileCount",    allFiles.size());
                    resp.put("token",        jwtTokenProvider.generateToken(result.email, result.username, userType));
                    resp.put("selectDb",     "ecommerce");
                    resp.put("message",      "Login successful. Welcome back, " + result.username + "!");

                    // Export user's transcript history from DB to JSON on login
                    try {
                        userDbService.exportUserTranscriptsToJson(result.username);
                    } catch (Exception ex) {
                        log.warn("Transcript export on login failed for '{}': {}", result.username, ex.getMessage());
                    }

                    return ResponseEntity.ok(resp);
                }

                case NOT_VERIFIED -> {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("success", false);
                    err.put("message", "Email address not verified yet. " +
                                       "Please check your inbox and click the verification link.");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
                }

                case BAD_PASSWORD, NOT_FOUND -> {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("success", false);
                    err.put("message", "Invalid email or password. Please try again.");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
                }

                default -> { return serverError("Unexpected login status: " + result.status); }
            }

        } catch (Exception e) {
            log.error("Login failed for '{}': {}", request.getEmail(), e.getMessage(), e);
            return serverError("Login failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/auth/guest  — continue as guest / temp user
    // =========================================================================

    /**
     * Creates a temporary guest session.
     *
     * On success (200 OK):
     * {
     *   "success":   true,
     *   "status":    "guest",
     *   "username":  "127_0_0_1",
     *   "email":     "guest_127_0_0_1@genqry.temp",
     *   "userType":  "Temp",
     *   "sessionId": "d8578edf8458ce06fbc5bb76a58c5ca4",
     *   "message":   "Continuing as guest. Your session is temporary."
     * }
     */
    @Operation(summary = "Continue as guest", description = "Creates a temporary guest session with a JWT token")
    @PostMapping("/guest")
    public ResponseEntity<Map<String, Object>> continueAsGuest(HttpServletRequest httpRequest) {

        String ipAddress = resolveClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        String sessionId = UUID.randomUUID().toString();
        UserAgentParser.ParsedUA ua = UserAgentParser.parse(userAgent);

        log.info("POST /api/v1/auth/guest — ip='{}' browser='{}'", ipAddress, ua.browser());

        // Reuse an existing Temp user for this IP, or create a new one
        UserDbService.GuestUserResult guest = userDbService.getOrCreateGuestUser(ipAddress);

        if (guest.userId > 0) {
            userDbService.recordLogin(guest.userId, ipAddress,
                    ua.deviceType(), ua.os(), ua.browser(),
                    userAgent, sessionId);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",   true);
        resp.put("status",    "guest");
        resp.put("username",  guest.username);
        resp.put("email",     guest.email);
        resp.put("userType",  "Temp");
        resp.put("sessionId", sessionId);
        resp.put("isNewGuest", guest.isNew);
        resp.put("token",     jwtTokenProvider.generateToken(guest.email, guest.username, "Temp"));
        resp.put("message",   "Continuing as guest. Your session is temporary.");
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // GET /api/v1/auth/verify?token=…
    // =========================================================================

    /**
     * Verifies a user's email address using the token from the verification link.
     *
     * GET /api/auth/verify?token=abc123...
     *
     * On success (200 OK):
     * {
     *   "status":  "verified",
     *   "email":   "ashok@example.com",
     *   "message": "Email verified successfully. You may now log in."
     * }
     *
     * On invalid/expired token (400 Bad Request):
     * { "status": "error", "message": "Invalid or expired verification token." }
     */
    @Operation(summary = "Verify email address", description = "Validates the email verification token from the link")
    @GetMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestParam("token") String token) {

        log.info("GET /api/v1/auth/verify — token='{}'", token);

        try {
            String email = registrationService.verifyToken(token);

            if (email == null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("status",  "error");
                err.put("message", "Invalid or expired verification token.");
                return ResponseEntity.badRequest().body(err);
            }

            // Look up username from DB so we can include it in the response
            String username = email; // fallback to email if DB lookup fails
            try {
                Map<String, Object> dbUser = userDbService.findUserForLogin(email);
                if (dbUser != null && dbUser.get("user_name") != null) {
                    username = dbUser.get("user_name").toString();
                }
            } catch (Exception ex) {
                log.warn("Could not look up username for email='{}': {}", email, ex.getMessage());
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success",  true);
            resp.put("status",   "verified");
            resp.put("verified", true);
            resp.put("email",    email);
            resp.put("username", username);
            resp.put("message",  "Email verified successfully. You may now log in.");
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("Token verification failed: {}", e.getMessage(), e);
            return serverError("Verification failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // GET /api/v1/auth/reset-password/validate?token=…
    // =========================================================================

    /**
     * Validates a password reset token without consuming it.
     * Called by the Next.js route handler before redirecting to the reset UI.
     *
     * On valid token (200 OK):
     * { "valid": true, "email": "user@example.com", "username": "ashok" }
     *
     * On invalid/expired token (400 Bad Request):
     * { "valid": false, "message": "Password reset link is invalid or has expired." }
     */
    @Operation(summary = "Validate password reset token", description = "Checks if a reset token is still valid without consuming it")
    @GetMapping("/reset-password/validate")
    public ResponseEntity<Map<String, Object>> validateResetToken(
            @RequestParam("token") String token) {

        log.info("GET /api/v1/auth/reset-password/validate — token='{}'", token);

        try {
            Map<String, Object> user = userDbService.findUserByResetToken(token);

            if (user == null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("valid",   false);
                err.put("message", "Password reset link is invalid or has expired.");
                return ResponseEntity.badRequest().body(err);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("valid",    true);
            resp.put("email",    user.get("email"));
            resp.put("username", user.get("user_name"));
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("validateResetToken failed: {}", e.getMessage(), e);
            return serverError("Token validation failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/auth/reset-password
    // =========================================================================

    /**
     * Resets the user's password using the reset token.
     *
     * Request body:
     * { "token": "uuid-token", "newPassword": "newSecret123" }
     *
     * On success (200 OK):
     * { "success": true, "message": "Password reset successfully. You may now log in." }
     *
     * On invalid/expired token (400 Bad Request):
     * { "success": false, "message": "Password reset link is invalid or has expired." }
     */
    @Operation(summary = "Reset password", description = "Resets user password using the reset token")
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(
            @RequestBody Map<String, String> body) {

        String token       = body.getOrDefault("token",       "").trim();
        String newPassword = body.getOrDefault("newPassword", "").trim();

        log.info("POST /api/v1/auth/reset-password — token='{}'", token);

        if (token.isBlank() || newPassword.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "Token and new password are required.");
            return ResponseEntity.badRequest().body(err);
        }

        if (newPassword.length() < 8) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "Password must be at least 8 characters.");
            return ResponseEntity.badRequest().body(err);
        }

        try {
            boolean updated = registrationService.resetPassword(token, newPassword);

            if (!updated) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("message", "Password reset link is invalid or has expired.");
                return ResponseEntity.badRequest().body(err);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Password reset successfully. You may now log in.");
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("resetPassword failed: {}", e.getMessage(), e);
            return serverError("Password reset failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/auth/forgot-password
    // =========================================================================

    /**
     * Handles forgotten password requests.
     *
     * Request body:
     * {
     *   "email": "user@example.com"
     * }
     *
     * Response cases:
     *
     * 1. User not found (200 OK):
     * {
     *   "success": true,
     *   "email": "user@example.com",
     *   "message": "User is not registered yet."
     * }
     *
     * 2. User registered via passkey only (200 OK):
     * {
     *   "success": true,
     *   "email": "user@example.com",
     *   "message": "You have registered through passkey. Please use passkey to login."
     * }
     *
     * 3. Password reset email sent (200 OK):
     * {
     *   "success": true,
     *   "email": "user@example.com",
     *   "message": "Password reset link has been sent to your email. Please check your inbox."
     * }
     */
    @Operation(summary = "Forgot password", description = "Sends a password reset link to the user's email")
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {

        log.info("POST /api/v1/auth/forgot-password — email='{}'", request.getEmail());

        try {
            ForgotPasswordResult result = registrationService.forgotPassword(request.getEmail());

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success",    result.success);
            resp.put("authMethod", result.authMethod);   // null | "passkey" | "password"
            resp.put("message",    result.message);

            log.info("Forgot password: status={} success={} authMethod='{}' email='{}'",
                    result.status, result.success, result.authMethod, result.email);

            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("Forgot password failed for '{}': {}", request.getEmail(), e.getMessage(), e);
            return serverError("Forgot password request failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/auth/token
    // =========================================================================

    /**
     * Issues a fresh JWT for an existing authenticated session.
     * Called by the frontend when a user has a valid session (localStorage)
     * but no JWT token stored (e.g. after upgrading from an older version).
     *
     * Request body: { "username": "AshokSekar" }
     *
     * Response (200): { "success": true, "token": "eyJ..." }
     * Response (404): { "success": false, "message": "User not found" }
     */
    @Operation(summary = "Issue JWT token", description = "Issues a fresh JWT for an existing authenticated session by username")
    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> issueToken(
            @RequestBody Map<String, String> body) {

        String username = body.getOrDefault("username", "").trim();
        log.info("POST /api/v1/auth/token — username='{}'", username);

        if (username.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "username is required");
            return ResponseEntity.badRequest().body(err);
        }

        try {
            Map<String, Object> dbUser = userDbService.findUserByUsername(username);
            if (dbUser == null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("message", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
            }

            String email    = String.valueOf(dbUser.getOrDefault("email", ""));
            String userType = String.valueOf(dbUser.getOrDefault("user_type", "Registered"));
            String token    = jwtTokenProvider.generateToken(email, username, userType);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success",  true);
            resp.put("token",    token);
            resp.put("username", username);
            resp.put("userType", userType);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("Token issuance failed for '{}': {}", username, e.getMessage(), e);
            return serverError("Token issuance failed: " + e.getMessage());
        }
    }

    // =========================================================================

    /** Resolves the real client IP, honouring X-Forwarded-For and X-Real-IP headers. */
    private String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String xri = req.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri.trim();
        String addr = req.getRemoteAddr();
        // normalise IPv6 loopback
        return "0:0:0:0:0:0:0:1".equals(addr) ? "127.0.0.1" : addr;
    }

    private ResponseEntity<Map<String, Object>> conflict(String status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status",  status);
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private ResponseEntity<Map<String, Object>> serverError(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status",  "error");
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}

