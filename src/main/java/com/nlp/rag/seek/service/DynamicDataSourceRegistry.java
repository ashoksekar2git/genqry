package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.DatabaseConnectRequest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registry of adhoc database connection pools — one slot per DB type.
 *
 * <h3>Architecture — 4 DataSource Strategy</h3>
 * <pre>
 *  ┌───────────────────────────────────────────────────────────────────────┐
 *  │ SLOT             │ LIFECYCLE │ BACKING          │ QUALIFIER / BEAN   │
 *  ├──────────────────┼───────────┼──────────────────┼────────────────────┤
 *  │ 1. PRIMARY       │ Fixed     │ Spring Bean      │ primaryDataSource  │
 *  │    seek DB       │ startup   │ (HikariCP auto)  │ primaryJdbcTemplate│
 *  ├──────────────────┼───────────┼──────────────────┼────────────────────┤
 *  │ 2. SECONDARY     │ Fixed     │ Spring Bean      │ secondaryDataSource│
 *  │    ecommerce DB  │ startup   │ (HikariCP auto)  │ secondaryJdbcTmpl  │
 *  ├──────────────────┼───────────┼──────────────────┼────────────────────┤
 *  │ 3. ADHOC_PG      │ Dynamic   │ HikariDataSource │ this registry      │
 *  │    user PostgreSQL│ on /connect│ (managed here)  │                    │
 *  ├──────────────────┼───────────┼──────────────────┼────────────────────┤
 *  │ 4. ADHOC_MYSQL   │ Dynamic   │ HikariDataSource │ this registry      │
 *  │    user MySQL    │ on /connect│ (managed here)  │                    │
 *  └───────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * When a user calls {@code POST /api/v1/admin/database/connect}, the connection
 * info is used to create a proper HikariCP connection pool in the appropriate
 * type-specific slot.  If a pool already exists in that slot (e.g., user connects
 * to a different MySQL database), the old pool is gracefully closed first.
 *
 * <p><strong>Thread safety:</strong> Each slot has its own {@link ReentrantLock}
 * so PostgreSQL and MySQL operations never block each other.</p>
 *
 * <p><strong>Credentials:</strong> Kept in memory only — never persisted to disk.
 * Pools are destroyed on server restart or explicit {@link #unregister(String)}.</p>
 */
@Service
public class DynamicDataSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceRegistry.class);

    // ── Slot type constants ──────────────────────────────────────────────────
    private static final String SLOT_POSTGRESQL = "POSTGRESQL";
    private static final String SLOT_MYSQL      = "MYSQL";

    // ── HikariCP pool defaults (adhoc connections are low-traffic) ────────────
    private static final int    MAX_POOL_SIZE        = 5;
    private static final int    MIN_IDLE             = 1;
    private static final long   CONNECTION_TIMEOUT   = 5_000L;   // 5 seconds
    private static final long   IDLE_TIMEOUT         = 60_000L;  // 1 minute
    private static final long   MAX_LIFETIME         = 300_000L; // 5 minutes
    private static final long   VALIDATION_TIMEOUT   = 3_000L;   // 3 seconds

    // ── Per-slot lock + pool ─────────────────────────────────────────────────
    private final ReentrantLock pgLock    = new ReentrantLock();
    private final ReentrantLock mysqlLock = new ReentrantLock();

    private volatile PooledSlot adhocPostgresSlot;
    private volatile PooledSlot adhocMysqlSlot;

    /** Fast lookup: databaseName (lower) → slot type (POSTGRESQL | MYSQL) */
    private final Map<String, String> nameToSlotType = new ConcurrentHashMap<>();

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Creates a HikariCP connection pool for the given database and assigns it
     * to the appropriate type-specific slot. Any previous pool in that slot
     * is gracefully closed.
     *
     * <p>Called after a successful {@code POST /database/connect}.</p>
     */
    public void register(DatabaseConnectRequest req) {
        String dbName  = req.getDatabaseName().trim().toLowerCase();
        String slotType = resolveSlotType(req.getDatabaseType());

        log.info("Registering adhoc datasource: name='{}' type='{}' slot='{}'",
                dbName, req.getDatabaseType(), slotType);

        HikariDataSource newPool = createHikariPool(req, slotType);
        JdbcTemplate newJdbc = new JdbcTemplate(newPool);

        PooledSlot newSlot = new PooledSlot(
                dbName, slotType, req.getDatabaseType(),
                req.toJdbcUrl(), newPool, newJdbc);

        if (SLOT_MYSQL.equals(slotType)) {
            replaceSlot(mysqlLock, newSlot, "ADHOC_MYSQL");
            adhocMysqlSlot = newSlot;
        } else {
            replaceSlot(pgLock, newSlot, "ADHOC_POSTGRESQL");
            adhocPostgresSlot = newSlot;
        }

        // Remove any old name→type mapping that pointed to this slot type
        nameToSlotType.entrySet().removeIf(e -> e.getValue().equals(slotType));
        nameToSlotType.put(dbName, slotType);

        log.info("✅ Adhoc {} pool created: name='{}' url='{}' maxPool={}",
                slotType, dbName, req.toJdbcUrl(), MAX_POOL_SIZE);
    }

    /**
     * Returns {@code true} if a connection pool is active for the given database name.
     */
    public boolean isRegistered(String databaseName) {
        return findSlot(databaseName) != null;
    }

    /**
     * Opens a JDBC connection from the HikariCP pool for the given database.
     *
     * @return a pooled {@link Connection}, or {@code null} if no pool exists
     * @throws SQLException if the pool cannot provide a connection
     */
    public Connection openConnection(String databaseName) throws SQLException {
        PooledSlot slot = findSlot(databaseName);
        if (slot == null) {
            log.warn("No adhoc pool registered for database '{}'", databaseName);
            return null;
        }
        log.debug("Borrowing connection from {} pool for '{}'", slot.slotType, slot.databaseName);
        return slot.dataSource.getConnection();
    }

    /**
     * Returns a {@link JdbcTemplate} backed by the adhoc pool for the given database.
     *
     * @return the JdbcTemplate, or {@code null} if no pool is registered
     */
    public JdbcTemplate getJdbcTemplate(String databaseName) {
        PooledSlot slot = findSlot(databaseName);
        return slot != null ? slot.jdbcTemplate : null;
    }

    /**
     * Returns the database type (e.g. "MySQL", "PostgreSQL") for a registered database.
     */
    public String getDatabaseType(String databaseName) {
        PooledSlot slot = findSlot(databaseName);
        return slot != null ? slot.originalDatabaseType : null;
    }

    /**
     * Returns the JDBC URL for a registered database.
     */
    public String getJdbcUrl(String databaseName) {
        PooledSlot slot = findSlot(databaseName);
        return slot != null ? slot.jdbcUrl : null;
    }

    /**
     * Closes and removes the pool for the given database name.
     */
    public void unregister(String databaseName) {
        if (databaseName == null) return;
        String key = databaseName.trim().toLowerCase();
        String slotType = nameToSlotType.remove(key);
        if (slotType == null) {
            log.warn("Cannot unregister '{}' — not found in registry", key);
            return;
        }

        if (SLOT_MYSQL.equals(slotType)) {
            closeSlot(mysqlLock, "ADHOC_MYSQL");
            adhocMysqlSlot = null;
        } else {
            closeSlot(pgLock, "ADHOC_POSTGRESQL");
            adhocPostgresSlot = null;
        }
        log.info("✅ Unregistered adhoc pool for '{}'", key);
    }

    /**
     * Gracefully shuts down all active adhoc connection pools on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down all adhoc connection pools...");
        closeSlot(pgLock, "ADHOC_POSTGRESQL");
        adhocPostgresSlot = null;
        closeSlot(mysqlLock, "ADHOC_MYSQL");
        adhocMysqlSlot = null;
        nameToSlotType.clear();
        log.info("✅ All adhoc connection pools closed");
    }

    // =========================================================================
    // Internal — Slot lookup
    // =========================================================================

    /**
     * Finds the active pool slot for the given database name.
     * Checks by name (via the fast-lookup map), then verifies the slot is alive.
     */
    private PooledSlot findSlot(String databaseName) {
        if (databaseName == null) return null;
        String key = databaseName.trim().toLowerCase();
        String slotType = nameToSlotType.get(key);
        if (slotType == null) return null;

        PooledSlot slot = SLOT_MYSQL.equals(slotType) ? adhocMysqlSlot : adhocPostgresSlot;
        if (slot == null || slot.dataSource.isClosed()) {
            nameToSlotType.remove(key);
            return null;
        }
        return slot;
    }

    // =========================================================================
    // Internal — Pool lifecycle
    // =========================================================================

    /**
     * Creates a HikariCP datasource from the given connection request.
     */
    private HikariDataSource createHikariPool(DatabaseConnectRequest req, String slotType) {
        HikariConfig config = new HikariConfig();

        // ── JDBC settings ────────────────────────────────────────────────────
        config.setJdbcUrl(req.toJdbcUrl());
        config.setUsername(req.getUsername());
        config.setPassword(req.getPassword());
        config.setDriverClassName(req.toDriverClassName());

        // ── Pool name (for JMX and logging) ──────────────────────────────────
        config.setPoolName("seek-adhoc-" + slotType.toLowerCase()
                + "-" + req.getDatabaseName().trim().toLowerCase());

        // ── Pool sizing (conservative — adhoc connections are low traffic) ────
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(MIN_IDLE);

        // ── Timeouts ─────────────────────────────────────────────────────────
        config.setConnectionTimeout(CONNECTION_TIMEOUT);
        config.setIdleTimeout(IDLE_TIMEOUT);
        config.setMaxLifetime(MAX_LIFETIME);
        config.setValidationTimeout(VALIDATION_TIMEOUT);

        // ── Read-only for safety (NL2SQL only runs SELECT) ───────────────────
        config.setReadOnly(true);
        config.setAutoCommit(true);

        // ── Connection test query ────────────────────────────────────────────
        config.setConnectionTestQuery("SELECT 1");

        log.info("Creating HikariCP pool: name='{}' url='{}' driver='{}' maxPool={} minIdle={}",
                config.getPoolName(), req.toJdbcUrl(), req.toDriverClassName(),
                MAX_POOL_SIZE, MIN_IDLE);

        return new HikariDataSource(config);
    }

    /**
     * Replaces the current pool in a slot with a new one.
     * The old pool is closed before the new one is assigned.
     */
    private void replaceSlot(ReentrantLock lock, PooledSlot newSlot, String slotLabel) {
        lock.lock();
        try {
            PooledSlot oldSlot = SLOT_MYSQL.equals(newSlot.slotType)
                    ? adhocMysqlSlot : adhocPostgresSlot;

            if (oldSlot != null && !oldSlot.dataSource.isClosed()) {
                log.info("Closing previous {} pool for '{}'", slotLabel, oldSlot.databaseName);
                try {
                    oldSlot.dataSource.close();
                } catch (Exception e) {
                    log.warn("Error closing old {} pool: {}", slotLabel, e.getMessage());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the pool in the given slot (if any).
     */
    private void closeSlot(ReentrantLock lock, String slotLabel) {
        lock.lock();
        try {
            PooledSlot slot = "ADHOC_MYSQL".equals(slotLabel) ? adhocMysqlSlot : adhocPostgresSlot;
            if (slot != null && !slot.dataSource.isClosed()) {
                log.info("Closing {} pool for '{}'", slotLabel, slot.databaseName);
                try {
                    slot.dataSource.close();
                } catch (Exception e) {
                    log.warn("Error closing {} pool: {}", slotLabel, e.getMessage());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // =========================================================================
    // Internal — Helpers
    // =========================================================================

    /**
     * Maps the user-facing database type string to the internal slot type.
     */
    private String resolveSlotType(String databaseType) {
        if (databaseType != null && databaseType.toLowerCase().contains("mysql")) {
            return SLOT_MYSQL;
        }
        return SLOT_POSTGRESQL; // default for PostgreSQL, H2, etc.
    }

    // =========================================================================
    // Internal — Slot record
    // =========================================================================

    /**
     * Holds the HikariCP pool and metadata for one adhoc database connection slot.
     */
    private static final class PooledSlot {
        final String           databaseName;        // e.g. "eduschool"
        final String           slotType;            // POSTGRESQL or MYSQL
        final String           originalDatabaseType; // e.g. "MySQL", "PostgreSQL"
        final String           jdbcUrl;
        final HikariDataSource dataSource;
        final JdbcTemplate     jdbcTemplate;

        PooledSlot(String databaseName, String slotType, String originalDatabaseType,
                   String jdbcUrl, HikariDataSource dataSource, JdbcTemplate jdbcTemplate) {
            this.databaseName        = databaseName;
            this.slotType            = slotType;
            this.originalDatabaseType = originalDatabaseType;
            this.jdbcUrl             = jdbcUrl;
            this.dataSource          = dataSource;
            this.jdbcTemplate        = jdbcTemplate;
        }
    }
}

