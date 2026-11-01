package com.itemmanagement.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public operational endpoints. The health response intentionally contains no
 * version, host, credential or configuration details that could aid an attacker.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SystemStatusController {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<BuildProperties> buildProperties;

    @Value("${app.build.git-commit:unknown}")
    private String configuredGitCommit;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean databaseUp;
        try {
            Integer probe = jdbcTemplate.queryForObject("select 1", Integer.class);
            databaseUp = probe != null && probe == 1;
        } catch (Exception ex) {
            databaseUp = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", databaseUp ? "UP" : "DOWN");
        body.put("database", databaseUp ? "UP" : "DOWN");
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> version() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", buildInfo("build.name", "item-management-system"));
        body.put("version", buildInfo("build.version", "unknown"));
        body.put("artifact", buildInfo("build.artifact", "unknown"));
        body.put("buildTime", buildInfo("build.time", "unknown"));
        body.put("gitCommit", resolveGitCommit());
        body.put("javaVersion", System.getProperty("java.version"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private String buildInfo(String key, String fallback) {
        BuildProperties properties = buildProperties.getIfAvailable();
        if (properties == null) {
            return fallback;
        }
        String suffix = key.substring("build.".length());
        String value = switch (suffix) {
            case "name" -> properties.getName();
            case "version" -> properties.getVersion();
            case "artifact" -> properties.getArtifact();
            case "time" -> properties.getTime() == null ? null : properties.getTime().toString();
            default -> properties.get(suffix);
        };
        return value == null || value.isBlank() ? fallback : value;
    }

    private String resolveGitCommit() {
        if (configuredGitCommit != null && !configuredGitCommit.isBlank()
                && !"unknown".equals(configuredGitCommit)) {
            return configuredGitCommit;
        }
        BuildProperties properties = buildProperties.getIfAvailable();
        String commit = properties == null ? null : properties.get("git.commit");
        return commit == null || commit.isBlank() ? "unknown" : commit;
    }
}
