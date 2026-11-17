package com.itemmanagement.controller;

import com.itemmanagement.service.BuildInfoService;
import lombok.RequiredArgsConstructor;
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
 * Public operational endpoints. The health and version responses intentionally
 * contain no host, credential, configuration or revision details that could aid
 * an attacker; deployment verification lives in the platform-only endpoint.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SystemStatusController {

    private final JdbcTemplate jdbcTemplate;
    private final BuildInfoService buildInfoService;

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
        body.put("name", buildInfoService.name());
        body.put("version", buildInfoService.version());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
