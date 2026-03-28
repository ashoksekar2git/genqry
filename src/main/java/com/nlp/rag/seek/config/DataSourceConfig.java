package com.nlp.rag.seek.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * DataSource configuration using {@link LazySecretDataSource} — supports both
 * <b>local</b> (credentials from properties) and <b>cloud</b> (credentials
 * from bootstrap) modes.
 *
 * <h3>4-DataSource Architecture</h3>
 * <pre>
 * ┌───────────────────────────────────────────────────────────────────────────┐
 * │ #  SLOT            LIFECYCLE   PURPOSE                   MANAGED BY      │
 * ├───────────────────────────────────────────────────────────────────────────┤
 * │ 1  PRIMARY         Lazy        seek DB — user/auth/RAG   THIS CLASS      │
 * │                    (on first   metadata, business rules  (LazySecret-    │
 * │                     getConn)                             DataSource)     │
 * │                                                                          │
 * │ 2  SECONDARY       Lazy        ecommerce DB — default    THIS CLASS      │
 * │                    (on first   NL2SQL / Explore target   (LazySecret-    │
 * │                     getConn)                             DataSource)     │
 * │                                                                          │
 * │ 3  ADHOC_PG        Dynamic     User-connected PostgreSQL  DynamicData-   │
 * │                    (on /connect) databases               SourceRegistry  │
 * │                                                          (HikariCP pool) │
 * │                                                                          │
 * │ 4  ADHOC_MYSQL     Dynamic     User-connected MySQL       DynamicData-   │
 * │                    (on /connect) databases               SourceRegistry  │
 * │                                                          (HikariCP pool) │
 * └───────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>In <b>local mode</b> the password comes from {@code application.properties}
 * and the pool is created on the first {@code getConnection()} call (typically
 * during {@code DatabaseMigrationRunner}).</p>
 *
 * <p>In <b>cloud mode</b> the password field in properties is empty; the pool
 * stays dormant until the admin uploads secrets via the bootstrap page and
 * calls {@link LazySecretDataSource#reinitialize(String)}.</p>
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    // =========================================================================
    // PRIMARY – seek DB (user/auth/RAG metadata)
    // =========================================================================

    @Bean(name = {"primaryDataSource", "routingDataSource"})
    @Primary
    public LazySecretDataSource primaryDataSource(
            SecretStore secretStore,
            @Value("${spring.datasource.primary.url}") String url,
            @Value("${spring.datasource.primary.username}") String username,
            @Value("${spring.datasource.primary.password:}") String propertyPassword,
            @Value("${spring.datasource.primary.driver-class-name}") String driver) {

        LazySecretDataSource ds = new LazySecretDataSource(
                "PRIMARY (seek)", url, username, driver,
                () -> resolvePassword(secretStore, SecretStore.DB_PRIMARY_PASSWORD, propertyPassword)
        );
        log.info("DataSource bean → PRIMARY (seek) [lazy] url={}", url);
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

    @Bean(name = "secondaryDataSource")
    public LazySecretDataSource secondaryDataSource(
            SecretStore secretStore,
            @Value("${spring.datasource.secondary.url}") String url,
            @Value("${spring.datasource.secondary.username}") String username,
            @Value("${spring.datasource.secondary.password:}") String propertyPassword,
            @Value("${spring.datasource.secondary.driver-class-name}") String driver) {

        LazySecretDataSource ds = new LazySecretDataSource(
                "SECONDARY (ecommerce)", url, username, driver,
                () -> resolvePassword(secretStore, SecretStore.DB_SECONDARY_PASSWORD, propertyPassword)
        );
        log.info("DataSource bean → SECONDARY (ecommerce) [lazy] url={}", url);
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

    /**
     * Resolves the password to use.
     * Cloud mode: reads from SecretStore (populated during bootstrap).
     * Local mode: uses the value from application.properties.
     */
    private String resolvePassword(SecretStore store, String secretKey, String propertyPassword) {
        // SecretStore value takes priority (set during bootstrap in cloud mode)
        String fromStore = store.get(secretKey);
        if (fromStore != null && !fromStore.isBlank()) {
            return fromStore;
        }
        // Fall back to application.properties value (local/dev mode)
        return propertyPassword;
    }
}
