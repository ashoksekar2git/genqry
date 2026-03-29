package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.model.BusinessRule;
import com.nlp.rag.seek.config.BusinessRulesJsonExporter;
import com.nlp.rag.seek.service.BusinessRulesService;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for managing business rules stored in the genQry DB.
 *
 * Base path: /api/v1/admin/business-rules
 *
 * GET    /                        List all rules (all types)
 * GET    /?type={ruleType}        List rules by type
 * GET    /{id}                    Get single rule by id
 * POST   /                        Add a new rule
 * PUT    /{id}                    Update an existing rule
 * PATCH  /{id}/enable             Enable a rule
 * PATCH  /{id}/disable            Disable a rule
 * DELETE /{id}                    Delete a rule
 *
 * Rule types: SQL_GENERAL | SQL_EAV | DOCUMENT | EAV_RETRY
 */
@RestController
@RequestMapping("/api/v1/admin")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Business Rules", description = "CRUD for LLM prompt injection rules")
public class BusinessRulesController {

    private static final Logger log = LoggerFactory.getLogger(BusinessRulesController.class);

    @Autowired
    private BusinessRulesService service;

    @Autowired
    private UserDbService userDbService;

    @Autowired
    private BusinessRulesJsonExporter rulesJsonExporter;

    // =========================================================================
    // GET /api/v1/admin/business-rules
    // GET /api/v1/admin/business-rules?type=SQL_GENERAL
    // =========================================================================
    @Operation(summary = "List business rules", description = "List all rules or filter by type (SQL_GENERAL|SQL_EAV|DOCUMENT|EAV_RETRY)")
    @GetMapping("/business-rules")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "type", required = false) String type) {

        log.info("GET /api/v1/admin/business-rules type='{}'", type);

        List<BusinessRule> rules = (type != null && !type.isBlank())
                ? service.getAllRulesByType(type.toUpperCase())
                : service.getAllRules();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("rules", rules);
        resp.put("count", rules.size());
        if (type != null) resp.put("type", type.toUpperCase());
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // GET /api/v1/admin/business-rules/{id}
    // =========================================================================
    @GetMapping("/business-rules/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable int id) {
        BusinessRule rule = service.getById(id);
        if (rule == null) return notFound("No rule found with id=" + id);
        return ResponseEntity.ok(Map.of("rule", rule));
    }

    // =========================================================================
    // POST /api/v1/admin/business-rules
    //
    // {
    //   "ruleType":   "SQL_GENERAL",         (required)
    //   "ruleNumber": 11,                    (required)
    //   "ruleText":   "Always alias tables", (required)
    //   "addedBy":    "admin",               (optional, default "admin")
    //   "dataSource": "ecommerce",           (optional, null = global)
    //   "userId":     1,                     (optional)
    //   "enabled":    true                   (optional, default true)
    // }
    // =========================================================================
    @PostMapping("/business-rules")
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, Object> body) {
        log.info("POST /api/v1/admin/business-rules — body={}", body);
        try {
            BusinessRule rule = mapToRule(body);
            int id = service.addRule(rule);
            if (id < 0) return serverError("Failed to insert rule into database");

            BusinessRule saved = service.getById(id);
            int ownerUserId = saved.getUserId() != null ? saved.getUserId() : -1;
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Rule added successfully");
            resp.put("rule",    toEnrichedRule(saved, ownerUserId));
            refreshRulesJson(); // Keep JSON export in sync
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Add business rule failed: {}", e.getMessage(), e);
            return serverError(e.getMessage());
        }
    }

    // =========================================================================
    // POST /api/v1/admin/business-rules/rule
    //
    // Simplified add — frontend only needs to send:
    //   { "ruleType": "DOCUMENT", "ruleText": "Adding new rule test", "username": "ragexplorer" }
    // Optional: addedBy, category, enabled, userId, ruleNumber
    // ruleNumber is auto-assigned (max+1 for that ruleType)
    // addedBy defaults to username (so getRules can find it)
    // =========================================================================
    @PostMapping("/business-rules/rule")
    public ResponseEntity<Map<String, Object>> addRule(@RequestBody Map<String, Object> body) {
        log.info("POST /api/v1/admin/business-rules/rule — body={}", body);
        try {
            Object rt = body.get("ruleType");
            if (rt == null || rt.toString().isBlank())
                return badRequest("ruleType is required");

            Object text = body.get("ruleText");
            if (text == null || text.toString().isBlank())
                return badRequest("ruleText is required");

            String ruleType = rt.toString().toUpperCase();

            BusinessRule rule = new BusinessRule();
            rule.setRuleType(ruleType);
            rule.setRuleText(text.toString());

            // Auto-assign next rule number if not provided
            if (body.containsKey("ruleNumber")) {
                rule.setRuleNumber(toInt(body.get("ruleNumber")));
            } else {
                int nextNum = service.getNextRuleNumber(ruleType);
                rule.setRuleNumber(nextNum);
            }

            // addedBy: prefer explicit "addedBy", fall back to "username", then "Root"
            String addedBy = resolveAddedBy(body);
            rule.setAddedBy(addedBy);
            rule.setCategory(body.containsKey("category") ? (String) body.get("category") : null);
            rule.setEnabled(body.containsKey("enabled") ? toBool(body.get("enabled")) : true);

            // Resolve userId: prefer explicit "userId", fall back to looking up userName/username
            Object uid = body.get("userId");
            if (uid != null) {
                rule.setUserId(toInt(uid));
            } else {
                String un = resolveUsername(body);
                if (un != null) {
                    Map<String, Object> userRow = userDbService.findUserByUsername(un);
                    if (userRow != null) {
                        rule.setUserId(((Number) userRow.get("id")).intValue());
                    }
                }
            }

            int id = service.addRule(rule);
            if (id < 0) return serverError("Failed to insert rule into database");

            BusinessRule saved = service.getById(id);
            int ownerUserId = saved.getUserId() != null ? saved.getUserId() : -1;
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Rule added successfully");
            resp.put("rule",    toEnrichedRule(saved, ownerUserId));
            refreshRulesJson(); // Keep JSON export in sync
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Add business rule failed: {}", e.getMessage(), e);
            return serverError(e.getMessage());
        }
    }

    // =========================================================================
    // PUT /api/v1/admin/business-rules/{id}
    // Users can only update rules they added (not Root rules).
    // =========================================================================
    @PutMapping("/business-rules/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable int id,
            @RequestBody Map<String, Object> body) {
        log.info("PUT /api/v1/admin/business-rules/{}", id);
        try {
            BusinessRule existing = service.getById(id);
            if (existing == null) return notFound("No rule found with id=" + id);

            // Authorization: Root rules cannot be modified
            if ("Root".equalsIgnoreCase(existing.getAddedBy())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false,
                                     "message", "Root rules cannot be modified. You can only update rules you added."));
            }

            if (body.containsKey("ruleNumber")) existing.setRuleNumber(toInt(body.get("ruleNumber")));
            if (body.containsKey("ruleText"))   existing.setRuleText((String) body.get("ruleText"));
            if (body.containsKey("category")) existing.setCategory((String) body.get("category"));
            if (body.containsKey("enabled"))    existing.setEnabled(toBool(body.get("enabled")));
            existing.setId(id);

            BusinessRule updated = service.updateRule(existing);
            if (updated == null) return notFound("No rule found with id=" + id);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Rule updated successfully");
            resp.put("rule",    updated);
            refreshRulesJson(); // Keep JSON export in sync
            return ResponseEntity.ok(resp);

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Update business rule id={} failed: {}", id, e.getMessage(), e);
            return serverError(e.getMessage());
        }
    }

    // =========================================================================
    // PATCH /api/v1/admin/business-rules/rule/{id}
    //
    // Updates an existing rule.
    // If userType=Root in payload → can update ANY rule (including Root rules).
    // Otherwise → can only update rules the user added (not Root rules).
    // =========================================================================
    @PatchMapping("/business-rules/rule/{id}")
    public ResponseEntity<Map<String, Object>> patchRule(
            @PathVariable int id,
            @RequestBody Map<String, Object> body) {
        log.info("PATCH /api/v1/admin/business-rules/rule/{} — body={}", id, body);
        try {
            BusinessRule existing = service.getById(id);
            if (existing == null) return notFound("No rule found with id=" + id);

            // Check if the requesting user is Root
            Object ut = body.get("userType");
            boolean isRootUser = ut != null && "Root".equalsIgnoreCase(ut.toString());

            if (!isRootUser) {
                // Non-Root users cannot modify Root rules
                if ("Root".equalsIgnoreCase(existing.getAddedBy())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("success", false,
                                         "message", "Root rules cannot be modified. You can only update rules you added."));
                }

                // Non-Root users can only update their own rules
                String reqUser = resolveUsername(body);
                if (reqUser != null && existing.getUserId() != null) {
                    Map<String, Object> userRow = userDbService.findUserByUsername(reqUser);
                    if (userRow != null) {
                        int reqUserId = ((Number) userRow.get("id")).intValue();
                        if (existing.getUserId() != reqUserId) {
                            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                    .body(Map.of("success", false,
                                                 "message", "You can only update rules you added."));
                        }
                    }
                }
            }

            // Apply updates from payload
            if (body.containsKey("ruleText"))   existing.setRuleText((String) body.get("ruleText"));
            if (body.containsKey("ruleType"))   existing.setRuleType(body.get("ruleType").toString().toUpperCase());
            if (body.containsKey("category")) existing.setCategory((String) body.get("category"));
            if (body.containsKey("ruleNumber")) existing.setRuleNumber(toInt(body.get("ruleNumber")));
            if (body.containsKey("enabled"))    existing.setEnabled(toBool(body.get("enabled")));
            existing.setId(id);

            BusinessRule updated = service.updateRule(existing);
            if (updated == null) return serverError("Failed to update rule");

            int ownerUserId = updated.getUserId() != null ? updated.getUserId() : -1;
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Rule updated successfully");
            resp.put("rule",    toEnrichedRule(updated, ownerUserId));
            refreshRulesJson(); // Keep JSON export in sync
            return ResponseEntity.ok(resp);

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Patch business rule id={} failed: {}", id, e.getMessage(), e);
            return serverError(e.getMessage());
        }
    }

    // =========================================================================
    // PATCH /api/v1/admin/business-rules/{id}/enable
    // PATCH /api/v1/admin/business-rules/{id}/disable
    // =========================================================================
    @PatchMapping("/business-rules/{id}/enable")
    public ResponseEntity<Map<String, Object>> enable(@PathVariable int id) {
        log.info("PATCH /api/v1/admin/business-rules/{}/enable", id);
        BusinessRule r = service.setEnabled(id, true);
        if (r == null) return notFound("No rule found with id=" + id);
        refreshRulesJson(); // Keep JSON export in sync
        return ResponseEntity.ok(Map.of("success", true, "message", "Rule enabled", "rule", r));
    }

    @PatchMapping("/business-rules/{id}/disable")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable int id) {
        log.info("PATCH /api/v1/admin/business-rules/{}/disable", id);
        BusinessRule r = service.setEnabled(id, false);
        if (r == null) return notFound("No rule found with id=" + id);
        refreshRulesJson(); // Keep JSON export in sync
        return ResponseEntity.ok(Map.of("success", true, "message", "Rule disabled", "rule", r));
    }

    // =========================================================================
    // DELETE /api/v1/admin/business-rules/rule/{id}
    // Deletes a user-added rule. Root rules cannot be deleted.
    // =========================================================================
    @DeleteMapping("/business-rules/rule/{id}")
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable int id) {
        log.info("DELETE /api/v1/admin/business-rules/rule/{}", id);

        BusinessRule existing = service.getById(id);
        if (existing == null) return notFound("No rule found with id=" + id);

        if ("Root".equalsIgnoreCase(existing.getAddedBy())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false,
                                 "message", "Root rules cannot be deleted."));
        }

        boolean deleted = service.deleteRule(id);
        if (!deleted) return serverError("Failed to delete rule");

        refreshRulesJson(); // Keep JSON export in sync
        return ResponseEntity.ok(Map.of("success", true, "message", "Rule deleted successfully", "id", id));
    }

    // =========================================================================
    // DELETE /api/v1/admin/business-rules/{id}
    // Users can only delete rules they added (not Root rules).
    // =========================================================================
    @DeleteMapping("/business-rules/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable int id) {
        log.info("DELETE /api/v1/admin/business-rules/{}", id);

        BusinessRule existing = service.getById(id);
        if (existing == null) return notFound("No rule found with id=" + id);

        if ("Root".equalsIgnoreCase(existing.getAddedBy())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false,
                                 "message", "Root rules cannot be deleted. You can only delete rules you added."));
        }

        boolean deleted = service.deleteRule(id);
        if (!deleted) return notFound("No rule found with id=" + id);
        refreshRulesJson(); // Keep JSON export in sync
        return ResponseEntity.ok(Map.of("success", true, "message", "Rule deleted", "id", id));
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Re-exports BusinessRulesForPrompts.json after any rule CRUD operation
     * so pipeline services pick up the latest rules.
     */
    private void refreshRulesJson() {
        try {
            rulesJsonExporter.exportRulesToJson();
        } catch (Exception e) {
            log.warn("Failed to refresh BusinessRulesForPrompts.json: {}", e.getMessage());
        }
    }

    // =========================================================================
    // GET /api/v1/admin/getRules?username=AshokSekar
    //
    // Resolves the userId and user_type from the username.
    //   - If user_type is "Root" → returns ALL rules from business_rules
    //     (all editable since Root is the super admin).
    //   - Otherwise → returns rules owned by that userId (user_id = ?)
    //     PLUS all core/Root rules (added_by = 'Root').
    // Each rule carries an "editable" flag.
    // Grouped by rule_type for easy frontend rendering.
    // =========================================================================
    @GetMapping("/getRules")
    public ResponseEntity<Map<String, Object>> getRulesForUser(
            @RequestParam("username") String username) {

        log.info("GET /api/v1/admin/getRules — username='{}'", username);

        if (username == null || username.isBlank()) {
            return badRequest("username query parameter is required");
        }

        try {
            String user = username.trim();

            // Look up user from the users table
            Map<String, Object> userRow = userDbService.findUserByUsername(user);
            if (userRow == null) {
                return badRequest("User '" + user + "' not found");
            }
            int userId = ((Number) userRow.get("id")).intValue();
            String userType = userRow.get("user_type") != null
                    ? userRow.get("user_type").toString() : "";
            boolean isRoot = "Root".equalsIgnoreCase(userType);

            // Root user sees ALL rules; non-Root sees own rules + Root rules
            List<BusinessRule> rules = isRoot
                    ? service.getAllRules()
                    : service.getRulesForUserId(userId);

            // Build enriched list: each rule gets an "editable" flag
            List<Map<String, Object>> enriched = rules.stream()
                    .map(r -> {
                        Map<String, Object> ruleMap = toEnrichedRule(r, userId);
                        // Root user can edit all rules
                        if (isRoot) ruleMap.put("editable", true);
                        return ruleMap;
                    })
                    .collect(Collectors.toList());

            // Group by ruleType for structured frontend rendering
            Map<String, List<Map<String, Object>>> grouped = enriched.stream()
                    .collect(Collectors.groupingBy(
                            r -> (String) r.get("ruleType"),
                            LinkedHashMap::new,
                            Collectors.toList()));

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("username",   user);
            resp.put("userId",     userId);
            resp.put("userType",   userType);
            resp.put("isRoot",     isRoot);
            resp.put("totalCount", enriched.size());
            resp.put("rulesByType", grouped);
            resp.put("rules",      enriched);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("getRulesForUser failed for username='{}': {}", username, e.getMessage(), e);
            return serverError(e.getMessage());
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private BusinessRule mapToRule(Map<String, Object> body) {
        BusinessRule rule = new BusinessRule();

        Object rt = body.get("ruleType");
        if (rt == null || rt.toString().isBlank())
            throw new IllegalArgumentException("ruleType is required");
        rule.setRuleType(rt.toString().toUpperCase());

        Object rn = body.get("ruleNumber");
        if (rn == null) throw new IllegalArgumentException("ruleNumber is required");
        rule.setRuleNumber(toInt(rn));

        Object text = body.get("ruleText");
        if (text == null || text.toString().isBlank())
            throw new IllegalArgumentException("ruleText is required");
        rule.setRuleText(text.toString());

        rule.setAddedBy(resolveAddedBy(body));
        rule.setCategory(body.containsKey("category") ? (String) body.get("category") : null);
        rule.setEnabled(body.containsKey("enabled") ? toBool(body.get("enabled")) : true);

        // Resolve userId: prefer explicit "userId", fall back to looking up userName/username
        Object uid = body.get("userId");
        if (uid != null) {
            rule.setUserId(toInt(uid));
        } else {
            String un = resolveUsername(body);
            if (un != null) {
                Map<String, Object> userRow = userDbService.findUserByUsername(un);
                if (userRow != null) {
                    rule.setUserId(((Number) userRow.get("id")).intValue());
                }
            }
        }
        return rule;
    }

    private int     toInt(Object o)  { return o instanceof Number n ? n.intValue() : Integer.parseInt(o.toString()); }
    private boolean toBool(Object o) { return o instanceof Boolean b ? b : Boolean.parseBoolean(o.toString()); }

    /**
     * Extracts the username from the request body.
     * Checks both "userName" (camelCase) and "username" (lowercase).
     */
    private String resolveUsername(Map<String, Object> body) {
        Object un = body.get("userName");
        if (un != null && !un.toString().isBlank()) return un.toString().trim();
        un = body.get("username");
        if (un != null && !un.toString().isBlank()) return un.toString().trim();
        return null;
    }

    /**
     * Resolves the addedBy value from the request body.
     * Priority: explicit "addedBy" → userName/username → "Root"
     */
    private String resolveAddedBy(Map<String, Object> body) {
        Object ab = body.get("addedBy");
        if (ab != null && !ab.toString().isBlank()) return ab.toString();
        String un = resolveUsername(body);
        if (un != null) return un;
        return "Root";
    }

    /**
     * Converts a BusinessRule to a Map with an "editable" flag.
     * editable = true when the rule's user_id matches the requesting userId (not a Root rule).
     */
    private Map<String, Object> toEnrichedRule(BusinessRule r, int requestingUserId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          r.getId());
        m.put("userId",      r.getUserId());
        m.put("ruleType",    r.getRuleType());
        m.put("ruleNumber",  r.getRuleNumber());
        m.put("ruleText",    r.getRuleText());
        m.put("addedBy",     r.getAddedBy());
        m.put("category",  r.getCategory());
        m.put("enabled",     r.isEnabled());
        m.put("createdAt",   r.getCreatedAt());
        m.put("updatedAt",   r.getUpdatedAt());
        // editable = true only if the rule belongs to this user (not Root)
        m.put("editable",    r.getUserId() != null && r.getUserId() == requestingUserId);
        return m;
    }

    private ResponseEntity<Map<String, Object>> notFound(String msg) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "message", msg));
    }
    private ResponseEntity<Map<String, Object>> badRequest(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "message", msg));
    }
    private ResponseEntity<Map<String, Object>> serverError(String msg) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", msg));
    }
}
