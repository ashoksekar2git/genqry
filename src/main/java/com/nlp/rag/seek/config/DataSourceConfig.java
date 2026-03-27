package com.nlp.rag.seek.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Dual DataSource configuration — two PostgreSQL databases.
 *
 * PRIMARY   → seek DB      (spring.datasource.primary.*)   — user/auth data, RAG metadata
 * SECONDARY → ecommerce DB (spring.datasource.secondary.*) — query target for NL2SQL / Explore
 *
 * Inject anywhere via:
 *   @Qualifier("primaryDataSource")    DataSource
 *   @Qualifier("routingDataSource")    DataSource   ← preferred alias for primary
 *   @Qualifier("primaryJdbcTemplate")  JdbcTemplate
 *   @Qualifier("routingJdbcTemplate")  JdbcTemplate ← preferred alias for primary
 *
 *   @Qualifier("secondaryDataSource")  DataSource
 *   @Qualifier("secondaryJdbcTemplate") JdbcTemplate ← use for Explore / ecommerce queries
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);
    private static final int VALIDATION_TIMEOUT_SECONDS = 3;

    // =========================================================================
    // PRIMARY – seek DB (user/auth/RAG metadata)
    // =========================================================================

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.primary")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = {"primaryDataSource", "routingDataSource"})
    @Primary
    public DataSource primaryDataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties props) {
        DataSource ds = props.initializeDataSourceBuilder().build();
        // Set read-only for RAG operations
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);
            log.info("✅ DataSource → PRIMARY (seek) set to read-only mode");
        } catch (Exception ex) {
            log.warn("Failed to set PRIMARY datasource to read-only: {}", ex.getMessage());
        }
        if (isReachable(ds, "PRIMARY / seek")) {
            log.info("✅ DataSource → PRIMARY (seek) reachable");
        } else {
            log.warn("⚠️  PRIMARY (seek) unreachable — queries will fail until it is available");
        }
        return ds;
    }

    @Bean(name = {"primaryJdbcTemplate", "routingJdbcTemplate"})
    @Primary
    public JdbcTemplate primaryJdbcTemplate(
            @Qualifier("primaryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // =========================================================================
    // SECONDARY – ecommerce DB (NL2SQL query target / Explore page)
    // =========================================================================

    @Bean
    @ConfigurationProperties("spring.datasource.secondary")
    public DataSourceProperties secondaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "secondaryDataSource")
    public DataSource secondaryDataSource(
            @Qualifier("secondaryDataSourceProperties") DataSourceProperties props) {
        DataSource ds = props.initializeDataSourceBuilder().build();
        if (isReachable(ds, "SECONDARY / ecommerce")) {
            log.info("✅ DataSource → SECONDARY (ecommerce) reachable");
        } else {
            log.warn("⚠️  SECONDARY (ecommerce) unreachable — Explore queries will fail until it is available");
        }
        return ds;
    }

    @Bean(name = "secondaryJdbcTemplate")
    public JdbcTemplate secondaryJdbcTemplate(
            @Qualifier("secondaryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // =========================================================================
    // Active datasource name (used in logging / responses)
    // =========================================================================

    @Bean(name = "activeDataSourceName")
    public String activeDataSourceName() {
        return "PRIMARY (seek)";
    }


    @Bean(name = "secondaryDataSourceName")
    public String secondaryDataSoruceName() {
        return "SECONDARY (ecommerce)";
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isReachable(DataSource ds, String name) {
        try (Connection conn = ds.getConnection()) {
            boolean valid = conn.isValid(VALIDATION_TIMEOUT_SECONDS);
            if (!valid) log.warn("DataSource '{}' isValid() returned false", name);
            return valid;
        } catch (Exception ex) {
            log.warn("DataSource '{}' not reachable: {}", name, ex.getMessage());
            return false;
        }
    }
}
