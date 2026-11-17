package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.service.BuildInfoService;
import com.itemmanagement.service.StoreProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the public version endpoint no longer fingerprints the deployment:
 * the revision is readable only through the platform-admin endpoint, and no
 * unauthenticated endpoint leaks it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54343",
        "app.embedded-postgres.data-dir=build/pgdata-version-disclosure-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "app.platform-admin.provision=false",
        "app.build.git-commit=0123456789abcdef0123456789abcdef01234567",
        "logging.level.com.itemmanagement=WARN"
})
class VersionDisclosureIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String OWNER_PASSWORD = "Secret@123";
    private static final String CONFIGURED_COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StoreProvisioningService provisioningService;

    @Autowired
    private BuildInfoService buildInfoService;

    @Test
    void publicVersionExposesOnlyNameAndVersion() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/version", JsonNode.class);
        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        JsonNode body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size(), () -> "unexpected public fields: " + body);
        assertEquals(buildInfoService.name(), body.get("name").asText());
        assertEquals(buildInfoService.version(), body.get("version").asText());
        for (String field : List.of("artifact", "buildTime", "gitCommit", "javaVersion")) {
            assertFalse(body.has(field), "public /api/version must not expose " + field);
        }
        assertTrue(headerContains(response, HttpHeaders.CACHE_CONTROL, "no-store"));
        assertFalse(rest.getForObject("/api/version", String.class).contains(CONFIGURED_COMMIT),
                "public /api/version must not contain the revision");
    }

    @Test
    void anonymousCallerCannotReadTheDeploymentCommit() {
        ResponseEntity<String> response = rest.getForEntity("/api/platform/version", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().contains(CONFIGURED_COMMIT));
    }

    @Test
    void storeUserCannotReadTheDeploymentCommit() {
        String token = provisionStore("DISC").token();
        ResponseEntity<String> response = rest.exchange("/api/platform/version", HttpMethod.GET,
                entity(token), String.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().contains(CONFIGURED_COMMIT));
    }

    @Test
    void platformAdministratorReadsTheDeploymentCommit() {
        ResponseEntity<JsonNode> response = rest.exchange("/api/platform/version", HttpMethod.GET,
                entity(platformToken()), JsonNode.class);
        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        JsonNode body = response.getBody();
        assertNotNull(body);
        assertEquals(buildInfoService.name(), body.get("name").asText());
        assertEquals(buildInfoService.version(), body.get("version").asText());
        assertEquals(CONFIGURED_COMMIT, body.get("gitCommit").asText());
        assertEquals(buildInfoService.buildTime(), body.get("buildTime").asText());
        assertFalse("unknown".equals(body.get("buildTime").asText()),
                "build time must come from build-info.properties");
        assertFalse(body.has("javaVersion"),
                "deployment verification does not need the Java runtime version");
        assertTrue(headerContains(response, HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void noPublicEndpointLeaksTheRevision() {
        for (String path : List.of("/api/health", "/api/version", "/api/platform/public-contact")) {
            ResponseEntity<String> response = rest.getForEntity(path, String.class);
            assertEquals(HttpStatus.OK.value(), response.getStatusCode().value(), path);
            assertNotNull(response.getBody(), path);
            assertFalse(response.getBody().contains(CONFIGURED_COMMIT),
                    path + " must not expose the revision");
        }
    }

    // ---------- fixtures ----------

    private record Session(String token) {
    }

    private Session provisionStore(String prefix) {
        int seq = SEQ.incrementAndGet();
        String code = prefix + seq;
        String username = "owner" + seq;
        provisioningService.createStore(new RegisterStoreRequest(code, "Store " + code, "Store Owner",
                username + "@test.local", "9000000300", "3 Version Street",
                null, "Karnataka", username, OWNER_PASSWORD, "Main Branch"), false);
        JsonNode auth = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", code, "username", username, "password", OWNER_PASSWORD),
                null, HttpStatus.OK);
        return new Session(auth.get("token").asText());
    }

    private String platformToken() {
        userRepository.findByUsernameIgnoreCaseAndStoreIdIsNull("platform")
                .orElseGet(() -> {
                    Role role = roleRepository.findByStoreIdIsNullAndCode("PLATFORM_ADMIN")
                            .orElseThrow(() -> new IllegalStateException(
                                    "PLATFORM_ADMIN role is missing"));
                    return userRepository.save(User.builder()
                            .storeId(null)
                            .username("platform")
                            .email("platform@test.local")
                            .passwordHash(passwordEncoder.encode("Admin@123"))
                            .fullName("Platform Administrator")
                            .mobile("9000000001")
                            .status(StoreStatus.ACTIVE)
                            .roles(Set.of(role))
                            .branches(Set.of())
                            .build());
                });
        return call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", "PLATFORM", "username", "platform", "password", "Admin@123"),
                null, HttpStatus.OK).get("token").asText();
    }

    private JsonNode call(HttpMethod method, String path, Object body, String token,
                          HttpStatus expected) {
        ResponseEntity<JsonNode> response = rest.exchange(path, method, entity(body, token),
                JsonNode.class);
        assertEquals(expected.value(), response.getStatusCode().value(),
                () -> method + " " + path + " expected " + expected + " but got "
                        + response.getStatusCode() + " " + response.getBody());
        return response.getBody();
    }

    private HttpEntity<Object> entity(String token) {
        return entity(null, token);
    }

    private HttpEntity<Object> entity(Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private static boolean headerContains(ResponseEntity<?> response, String header, String fragment) {
        String value = response.getHeaders().getFirst(header);
        return value != null && value.contains(fragment);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }
}
