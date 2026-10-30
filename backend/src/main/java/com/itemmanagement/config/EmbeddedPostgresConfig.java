package com.itemmanagement.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Development runtime that starts a real PostgreSQL server without a system install.
 * Production should disable this and use the "postgres" Spring profile.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.embedded-postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class EmbeddedPostgresConfig {

    @Value("${app.embedded-postgres.port:54329}")
    private int port;

    @Value("${app.embedded-postgres.data-dir:./.pgdata}")
    private String dataDir;

    @Value("${app.embedded-postgres.database:item_management_db}")
    private String database;

    @Value("${app.embedded-postgres.clean:false}")
    private boolean clean;

    private EmbeddedPostgres postgres;

    @Bean
    @Primary
    public DataSource dataSource() throws Exception {
        Path dir = Path.of(dataDir).toAbsolutePath();
        Files.createDirectories(dir);
        log.info("Starting embedded PostgreSQL on port {} (data dir {})", port, dir);
        postgres = EmbeddedPostgres.builder()
                .setPort(port)
                .setDataDirectory(dir)
                .setCleanDataDirectory(clean)
                .start();

        String adminUrl = postgres.getJdbcUrl("postgres", "postgres");
        try (Connection connection = DriverManager.getConnection(adminUrl)) {
            boolean exists;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT 1 FROM pg_database WHERE datname = '" + database + "'")) {
                exists = rs.next();
            }
            if (!exists) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE DATABASE " + database);
                    log.info("Created database {}", database);
                }
            }
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl("postgres", database));
        config.setUsername("postgres");
        config.setPassword("postgres");
        config.setMaximumPoolSize(15);
        config.setConnectionTimeout(30_000);
        return new HikariDataSource(config);
    }

    @PreDestroy
    public void shutdown() {
        if (postgres != null) {
            try {
                postgres.close();
            } catch (Exception e) {
                log.warn("Failed to stop embedded PostgreSQL", e);
            }
        }
    }
}
