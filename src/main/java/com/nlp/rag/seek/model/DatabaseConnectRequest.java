package com.nlp.rag.seek.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request payload for POST /api/v1/admin/database/connect
 */
@Schema(description = "Database connection payload")
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatabaseConnectRequest {

    @Schema(description = "Target database name", example = "ecommerce")
    private String databaseName;

    @Schema(description = "Database host", example = "localhost")
    private String host;

    @Schema(description = "Database port", example = "5432")
    private int    port;

    @Schema(description = "DB login username", example = "postgres")
    private String username;

    @Schema(description = "DB login password", example = "root")
    private String password;

    @Schema(description = "Database type (PostgreSQL|MySQL|H2)", example = "PostgreSQL")
    private String databaseType;

    @Schema(description = "Logged-in genQry application user", example = "AshokSekar")
    private String seekUserName;

    public DatabaseConnectRequest() {}

    public String getDatabaseName() { return databaseName; }
    public String getHost()         { return host; }
    public int    getPort()         { return port; }
    public String getUsername()     { return username; }
    public String getPassword()     { return password; }
    public String getDatabaseType() { return databaseType; }
    public String getSeekUserName() { return seekUserName; }

    public void setDatabaseName(String v) { this.databaseName = v; }
    public void setHost(String v)         { this.host = v; }
    public void setPort(int v)            { this.port = v; }
    public void setUsername(String v)     { this.username = v; }
    public void setPassword(String v)     { this.password = v; }
    public void setDatabaseType(String v) { this.databaseType = v; }
    public void setSeekUserName(String v) { this.seekUserName = v; }

    /** Returns true when the genQry application userName is supplied. */
    public boolean hasSeekUserName() {
        return seekUserName != null && !seekUserName.isBlank();
    }

    /** Builds a JDBC URL from the supplied fields. */
    public String toJdbcUrl() {
        String type = databaseType != null ? databaseType.toLowerCase() : "postgresql";
        if (type.contains("mysql")) {
            return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=UTC&characterEncoding=UTF-8", host, port, databaseName);
        }
        // default → postgresql
        return String.format("jdbc:postgresql://%s:%d/%s", host, port, databaseName);
    }

    /** Returns the JDBC driver class name for the requested DB type. */
    public String toDriverClassName() {
        String type = databaseType != null ? databaseType.toLowerCase() : "postgresql";
        if (type.contains("mysql")) return "com.mysql.cj.jdbc.Driver";
        return "org.postgresql.Driver";
    }
}

