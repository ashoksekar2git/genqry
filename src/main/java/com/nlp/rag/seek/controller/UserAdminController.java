package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.service.UserDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.mail.internet.MimeMessage;
import java.util.*;

/**
 * Admin controller for user management and invite-a-friend.
 *
 * Base path: /api/v1/admin/users
 *
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │ GET  /api/v1/admin/users           List all users (Root only)         │
 * │ POST /api/v1/admin/users/invite    Send invite email to a friend      │
 * └────────────────────────────────────────────────────────────────────────┘
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "User Admin", description = "User management (admin only)")
public class UserAdminController {

    private static final Logger log = LoggerFactory.getLogger(UserAdminController.class);

    @Autowired
    private UserDbService userDbService;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${genqry.auth.mail.from:genQry <ragdataseek@gmail.com>}")
    private String mailFrom;

    @Value("${genqry.auth.mail.enabled:true}")
    private boolean mailEnabled;

    @Value("${genqry.auth.base-url:http://localhost:3000}")
    private String baseUrl;

    // =========================================================================
    // GET /api/v1/admin/users
    // =========================================================================

    /**
     * Returns all users from the genQry DB users table.
     * Intended for Root/Admin users only (frontend enforces via userType).
     *
     * Response (200):
     * {
     *   "success":    true,
     *   "totalUsers": 5,
     *   "users": [
     *     {
     *       "id": 1000,
     *       "user_name": "AshokSekar",
     *       "email": "ashok@example.com",
     *       "user_type": "Root",
     *       "account_status": "Active",
     *       "display_name": "Ashok Sekar",
     *       "is_verified": true,
     *       "last_login_at": "2026-03-23T10:00:00Z",
     *       "created_at": "2026-01-01T00:00:00Z"
     *     },
     *     ...
     *   ]
     * }
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listAllUsers() {
        log.info("GET /api/v1/admin/users — fetching all users");

        List<Map<String, Object>> users = userDbService.getAllUsers();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",    true);
        resp.put("totalUsers", users.size());
        resp.put("users",      users);

        if (users.isEmpty()) {
            resp.put("message", "No users found in the database");
        }

        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // POST /api/v1/admin/users/invite
    // =========================================================================

    /**
     * Sends an invite email to a friend to check out the genQry application.
     *
     * Request body:
     * {
     *   "email":     "friend@example.com",
     *   "message":   "Hey, check out genQry — it converts natural language to SQL!",
     *   "invitedBy": "AshokSekar"
     * }
     *
     * Response (200):
     * {
     *   "success": true,
     *   "email":   "friend@example.com",
     *   "message": "Invite sent successfully to friend@example.com"
     * }
     *
     * Response (400): missing email
     * Response (503): email not configured
     */
    @PostMapping("/invite")
    public ResponseEntity<Map<String, Object>> inviteFriend(
            @RequestBody Map<String, Object> body) {

        String email     = body.get("email") != null ? body.get("email").toString().trim() : "";
        String message   = body.get("message") != null ? body.get("message").toString().trim() : "";
        String invitedBy = body.get("invitedBy") != null ? body.get("invitedBy").toString().trim() : "A genQry user";

        log.info("POST /api/v1/admin/users/invite — email='{}' invitedBy='{}'", email, invitedBy);

        if (email.isBlank() || !email.contains("@")) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "A valid email address is required"));
        }

        if (!mailEnabled || mailSender == null) {
            log.warn("Email not configured — cannot send invite to '{}'", email);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    Map.of("success", false,
                           "message", "Email service is not configured. Please set up SMTP."));
        }

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(email);
            helper.setSubject("You're Invited to Try genQry — Natural Language to SQL");
            helper.setText(buildInviteEmailHtml(invitedBy, message, email), true);

            mailSender.send(msg);
            log.info("Invite email sent to '{}' by '{}'", email, invitedBy);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("email",   email);
            resp.put("message", "Invite sent successfully to " + email);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("Failed to send invite email to '{}': {}", email, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("success", false,
                           "message", "Failed to send invite email: " + e.getMessage()));
        }
    }

    // =========================================================================
    // Email template
    // =========================================================================

    private String buildInviteEmailHtml(String invitedBy, String personalMessage, String recipientEmail) {
        String signUpUrl = baseUrl + "/register";
        String safeMessage = personalMessage.isBlank()
                ? "I've been using genQry and thought you'd love it. It converts natural language questions into SQL queries using AI — give it a try!"
                : personalMessage.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

        return String.format("""
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
                                      letter-spacing:1px">Natural Language → SQL &amp; Document Intelligence</p>
                          </td>
                        </tr>
                        <!-- Body -->
                        <tr>
                          <td style="padding:32px">
                            <h2 style="color:#f1f5f9;margin:0 0 8px;font-size:20px">
                              You've Been Invited! 🎉
                            </h2>
                            <p style="color:#94a3b8;font-size:14px;line-height:1.6;margin:0 0 16px">
                              <strong style="color:#f1f5f9">%s</strong> thinks you'd enjoy genQry:
                            </p>
                            <div style="background:#0f172a;border-left:3px solid #ea580c;
                                        padding:12px 16px;border-radius:4px;margin:0 0 24px">
                              <p style="color:#cbd5e1;font-size:14px;line-height:1.6;margin:0;font-style:italic">
                                "%s"
                              </p>
                            </div>
                            <p style="color:#94a3b8;font-size:14px;line-height:1.6;margin:0 0 8px">
                              <strong style="color:#f1f5f9">What is genQry?</strong>
                            </p>
                            <ul style="color:#94a3b8;font-size:13px;line-height:1.8;margin:0 0 24px;padding-left:20px">
                              <li>Ask questions in plain English — get SQL queries instantly</li>
                              <li>Upload documents and ask questions — AI-powered answers</li>
                              <li>EAV table intelligence — handles complex schemas automatically</li>
                              <li>Built-in hallucination detection &amp; retry logic</li>
                              <li>Secure passkey authentication — no passwords needed</li>
                            </ul>
                            <!-- CTA Button -->
                            <table width="100%%" cellpadding="0" cellspacing="0">
                              <tr>
                                <td align="center">
                                  <table cellpadding="0" cellspacing="0">
                                    <tr>
                                      <td style="background:linear-gradient(135deg,#ea580c 0%%,#dc2626 100%%);
                                                 border-radius:6px;padding:14px 32px">
                                        <a href="%s" style="color:#fff;text-decoration:none;
                                                           font-weight:700;font-size:14px;
                                                           letter-spacing:1px;text-transform:uppercase;
                                                           display:inline-block">
                                          Get Started with genQry
                                        </a>
                                      </td>
                                    </tr>
                                  </table>
                                </td>
                              </tr>
                            </table>
                            <p style="color:#64748b;font-size:12px;margin:24px 0 0;text-align:center">
                              <a href="%s" style="color:#0ea5e9;text-decoration:none">%s</a>
                            </p>
                          </td>
                        </tr>
                        <!-- Footer -->
                        <tr>
                          <td style="background:#0f172a;padding:16px 32px;
                                     border-top:1px solid #334155;text-align:center">
                            <p style="color:#475569;font-size:11px;margin:0">
                              This invite was sent by %s via genQry.
                              If you didn't expect this, you can safely ignore it.
                            </p>
                          </td>
                        </tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """,
                invitedBy,      // %s — invited by name in body
                safeMessage,    // %s — personal message
                signUpUrl,      // %s — CTA button href
                signUpUrl,      // %s — plain link href
                signUpUrl,      // %s — plain link text
                invitedBy       // %s — footer attribution
        );
    }
}
