package com.nlp.rag.seek.repository;

import com.nlp.rag.seek.model.BusinessRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * JDBC repository for the {@code business_rules} table in the seek DB.
 */
@Repository
public class BusinessRulesRepository {

    private static final Logger log = LoggerFactory.getLogger(BusinessRulesRepository.class);

    @Autowired
    @Qualifier("primaryJdbcTemplate")
    private JdbcTemplate jdbc;

    // ── RowMapper ─────────────────────────────────────────────────────────────

    private static final RowMapper<BusinessRule> ROW_MAPPER = (rs, rowNum) -> {
        BusinessRule r = new BusinessRule();
        r.setId(rs.getInt("id"));
        int uid = rs.getInt("user_id");
        r.setUserId(rs.wasNull() ? null : uid);
        r.setRuleType(rs.getString("rule_type"));
        r.setRuleNumber(rs.getInt("rule_number"));
        r.setRuleText(rs.getString("rule_text"));
        r.setAddedBy(rs.getString("added_by"));
        r.setCategory(rs.getString("category"));
        r.setEnabled(rs.getBoolean("enabled"));
        Timestamp ca = rs.getTimestamp("created_at");
        r.setCreatedAt(ca != null ? ca.toInstant() : null);
        Timestamp ua = rs.getTimestamp("updated_at");
        r.setUpdatedAt(ua != null ? ua.toInstant() : null);
        return r;
    };

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Returns all rules of a given type that are enabled, ordered by rule_number. */
    public List<BusinessRule> findEnabledByType(String ruleType) {
        return jdbc.query("""
                SELECT * FROM business_rules
                 WHERE rule_type = ? AND enabled = TRUE
                 ORDER BY rule_number
                """, ROW_MAPPER, ruleType);
    }

    /** Returns ALL rules of a given type (enabled and disabled), ordered by rule_number. */
    public List<BusinessRule> findAllByType(String ruleType) {
        return jdbc.query("""
                SELECT * FROM business_rules
                 WHERE rule_type = ?
                 ORDER BY rule_number
                """, ROW_MAPPER, ruleType);
    }

    /** Returns all rules (all types), ordered by rule_type then rule_number. */
    public List<BusinessRule> findAll() {
        return jdbc.query("""
                SELECT * FROM business_rules
                 ORDER BY rule_type, rule_number
                """, ROW_MAPPER);
    }

    /** Returns all rules belonging to a specific user + type, ordered by rule_number. */
    public List<BusinessRule> findByUserAndType(int userId, String ruleType) {
        return jdbc.query("""
                SELECT * FROM business_rules
                 WHERE user_id = ? AND rule_type = ?
                 ORDER BY rule_number
                """, ROW_MAPPER, userId, ruleType);
    }

    /** Returns enabled rules scoped to a data_source (DB name or document name). */
    public List<BusinessRule> findEnabledByTypeAndSource(String ruleType, String dataSource) {
        return jdbc.query("""
                SELECT * FROM business_rules
                 WHERE rule_type = ?
                   AND enabled   = TRUE
                   AND (category = ? OR category IS NULL)
                 ORDER BY rule_number
                """, ROW_MAPPER, ruleType, dataSource);
    }

    /**
     * Returns all rules added by the given username PLUS all core rules (added_by = 'Root').
     * Ordered by rule_type then rule_number for consistent display.
     */
    public List<BusinessRule> findByAddedByOrCore(String username) {
        return jdbc.query("""
                SELECT * FROM business_rules
                 WHERE added_by = ? OR added_by = 'Root'
                 ORDER BY rule_type, rule_number
                """, ROW_MAPPER, username);
    }

    /**
     * Returns all rules owned by the given user_id PLUS all core/Root rules.
     * Ordered by rule_type then rule_number.
     */
    public List<BusinessRule> findByUserIdOrCore(int userId) {
        return jdbc.query("""
                SELECT * FROM business_rules
                 WHERE user_id = ? OR added_by = 'Root'
                 ORDER BY rule_type, rule_number
                """, ROW_MAPPER, userId);
    }

    /** Returns the max rule_number for a given rule_type, or 0 if none exist. */
    public int getMaxRuleNumber(String ruleType) {
        try {
            Integer max = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(rule_number), 0) FROM business_rules WHERE rule_type = ?",
                    Integer.class, ruleType);
            return max != null ? max : 0;
        } catch (Exception e) {
            log.warn("getMaxRuleNumber failed for type='{}': {}", ruleType, e.getMessage());
            return 0;
        }
    }

    /** Looks up a single rule by primary key. */
    public BusinessRule findById(int id) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM business_rules WHERE id = ?", ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /**
     * Inserts a new business rule. Returns the generated id, or -1 on failure.
     */
    public int insert(BusinessRule rule) {
        String sql = """
                INSERT INTO business_rules
                  (user_id, rule_type, rule_number, rule_text, added_by,
                   category, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """;
        try {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                if (rule.getUserId() != null) ps.setInt(1, rule.getUserId());
                else ps.setNull(1, java.sql.Types.INTEGER);
                ps.setString(2, rule.getRuleType());
                ps.setInt(3, rule.getRuleNumber());
                ps.setString(4, rule.getRuleText());
                ps.setString(5, rule.getAddedBy() != null ? rule.getAddedBy() : "admin");
                ps.setString(6, rule.getCategory());
                ps.setBoolean(7, rule.isEnabled());
                return ps;
            }, kh);
            Number key = kh.getKey();
            int newId = key != null ? key.intValue() : -1;
            log.info("BusinessRule inserted: id={} type='{}' number={}", newId, rule.getRuleType(), rule.getRuleNumber());
            return newId;
        } catch (Exception e) {
            log.error("BusinessRule insert failed: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Updates an existing rule's text, number, data_source, and enabled flag.
     * Returns true if a row was updated.
     */
    public boolean update(BusinessRule rule) {
        try {
            int rows = jdbc.update("""
                    UPDATE business_rules
                       SET rule_number  = ?,
                           rule_text    = ?,
                           category  = ?,
                           enabled      = ?,
                           updated_at   = CURRENT_TIMESTAMP
                     WHERE id = ?
                    """,
                    rule.getRuleNumber(),
                    rule.getRuleText(),
                    rule.getCategory(),
                    rule.isEnabled(),
                    rule.getId());
            return rows > 0;
        } catch (Exception e) {
            log.error("BusinessRule update failed for id={}: {}", rule.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Toggles the enabled flag for a rule.
     * Returns true if a row was updated.
     */
    public boolean setEnabled(int id, boolean enabled) {
        try {
            int rows = jdbc.update("""
                    UPDATE business_rules
                       SET enabled    = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                    """, enabled, id);
            return rows > 0;
        } catch (Exception e) {
            log.error("BusinessRule setEnabled failed for id={}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes a rule by id. Returns true if a row was deleted.
     */
    public boolean delete(int id) {
        try {
            int rows = jdbc.update("DELETE FROM business_rules WHERE id = ?", id);
            return rows > 0;
        } catch (Exception e) {
            log.error("BusinessRule delete failed for id={}: {}", id, e.getMessage());
            return false;
        }
    }
}
