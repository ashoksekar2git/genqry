package com.nlp.rag.seek.service;

import com.nlp.rag.seek.model.DatabaseConnectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of user-connected database credentials.
 *
 * When a user calls POST /api/v1/admin/database/connect, the connection info
 * is cached here (keyed by databaseName). The SQLExecutionService uses this
 * registry to open a JDBC connection to the correct database for query execution.
 *
 * Credentials are kept in memory only — never persisted to disk.
 * Entries are evicted when the server restarts.
 */
@Service
public class DynamicDataSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceRegistry.class);

    /** databaseName (lower) → connection info */
    private final Map<String, ConnectionInfo> registry = new ConcurrentHashMap<>();

    /**
     * Stores the connection info for the given database.
     * Called after a successful POST /database/connect.
     */
    public void register(DatabaseConnectRequest req) {
        String key = req.getDatabaseName().trim().toLowerCase();
        registry.put(key, new ConnectionInfo(
                req.toJdbcUrl(),
                req.getUsername(),
                req.getPassword(),
                req.getDatabaseType(),
                req.toDriverClassName()
        ));
        log.info("Registered dynamic datasource: '{}' → {}", key, req.toJdbcUrl());
    }

    /**
     * Returns true if a dynamic datasource is registered for the given database.
     */
    public boolean isRegistered(String databaseName) {
        if (databaseName == null) return false;
        return registry.containsKey(databaseName.trim().toLowerCase());
    }

    /**
     * Opens a new JDBC connection to the registered database.
     * Returns null if no registration exists for the given database name.
     */
    public Connection openConnection(String databaseName) throws SQLException {
        if (databaseName == null) return null;
        ConnectionInfo info = registry.get(databaseName.trim().toLowerCase());
        if (info == null) return null;

        try {
            Class.forName(info.driverClassName);
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver not found: " + info.driverClassName, e);
        }

        Properties props = new Properties();
        props.setProperty("user", info.username);
        props.setProperty("password", info.password);

        String dbType = info.databaseType != null ? info.databaseType.toLowerCase() : "";
        if (dbType.contains("mysql")) {
            props.setProperty("connectTimeout", "5000");
            props.setProperty("socketTimeout", "30000");
        } else {
            props.setProperty("loginTimeout", "5");
        }

        return DriverManager.getConnection(info.jdbcUrl, props);
    }

    /**
     * Returns the database type (MySQL, PostgreSQL, etc.) for a registered database.
     */
    public String getDatabaseType(String databaseName) {
        if (databaseName == null) return null;
        ConnectionInfo info = registry.get(databaseName.trim().toLowerCase());
        return info != null ? info.databaseType : null;
    }

    /**
     * Removes a registered datasource.
     */
    public void unregister(String databaseName) {
        if (databaseName != null) {
            registry.remove(databaseName.trim().toLowerCase());
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private record ConnectionInfo(
            String jdbcUrl,
            String username,
            String password,
            String databaseType,
            String driverClassName
    ) {}
}

