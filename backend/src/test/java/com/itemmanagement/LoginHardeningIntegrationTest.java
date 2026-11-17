package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.security.LoginAttemptService;
import com.itemmanagement.service.StoreProvisioningService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the login hardening added after the security audit: generic responses
 * for unknown stores/usernames, the aggregate per-address failure limit and the
 * fact that a forwarded client address cannot reset it.
 *
 * <p>The trusted-proxy range is overridden so the loopback test client is not a
 * trusted proxy. That mirrors a direct remote caller: X-Forwarded-For must then
 * be ignored. The trusted-proxy topology is covered by
 * {@link ForwardedClientIpIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54341",
        "app.embedded-postgres.data-dir=build/pgdata-login-hardening-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "app.auth.login-max-attempts=3",
        "app.auth.login-ip-max-attempts=5",
        "app.auth.pairing-max-attempts=3",
        "server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.1",
        "logging.level.com.itemmanagement=WARN"
})
class LoginHardeningIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String GENERIC_MESSAGE = "Invalid store code, username or password";
    private static final String PASSWORD = "Secret@123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private StoreProvisioningService provisioningService;

    @BeforeEach
    void resetLoginAttempts() {
        loginAttemptService.clearAll();
    }

    @Test
    void unknownStoreAndUsernameResponsesStayGenericAndCreateNoAccounts() {
        StoreSession store = registerStore("GENERIC");

        ResponseEntity<JsonNode> unknownStore = login("MISSING" + SEQ.incrementAndGet(),
                "ghost", PASSWORD, null);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), unknownStore.getStatusCode().value());
        assertEquals(GENERIC_MESSAGE, unknownStore.getBody().get("message").asText());

        ResponseEntity<JsonNode> unknownUser = login(store.storeCode(), "ghost", PASSWORD, null);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), unknownUser.getStatusCode().value());
        assertEquals(GENERIC_MESSAGE, unknownUser.getBody().get("message").asText());

        ResponseEntity<JsonNode> wrongPassword = login(store.storeCode(), store.username(), "Wrong@123", null);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), wrongPassword.getStatusCode().value());
        assertEquals(GENERIC_MESSAGE, wrongPassword.getBody().get("message").asText());

        ResponseEntity<JsonNode> accepted = login(store.storeCode(), store.username(), PASSWORD, null);
        assertEquals(HttpStatus.OK.value(), accepted.getStatusCode().value());
        assertFalse(accepted.getBody().get("token").asText().isBlank());

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE store_id = ?", Integer.class, store.storeId()),
                "failed logins must never provision placeholder users");
    }

    @Test
    void fiveFailuresAcrossDifferentUsernamesLockTheClientAddress() {
        StoreSession store = registerStore("SPREAD");

        for (int attempt = 0; attempt < 5; attempt++) {
            ResponseEntity<JsonNode> failed = login(store.storeCode(), "ghost" + attempt,
                    "Wrong@123", null);
            assertEquals(HttpStatus.UNAUTHORIZED.value(), failed.getStatusCode().value(),
                    "attempt " + attempt + " should still be an ordinary credential failure");
            assertEquals(GENERIC_MESSAGE, failed.getBody().get("message").asText());
        }

        ResponseEntity<JsonNode> locked = login(store.storeCode(), store.username(), PASSWORD, null);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), locked.getStatusCode().value());
        assertTrue(locked.getBody().get("message").asText().contains("Too many failed sign-in attempts"));

        // The account itself is not locked by the address limit.
        loginAttemptService.clearAll();
        assertEquals(HttpStatus.OK.value(),
                login(store.storeCode(), store.username(), PASSWORD, null).getStatusCode().value());
    }

    @Test
    void forwardedForFromAnUntrustedPeerDoesNotBypassTheAggregateLimit() {
        StoreSession store = registerStore("SPOOF");

        for (int attempt = 0; attempt < 5; attempt++) {
            ResponseEntity<JsonNode> failed = login(store.storeCode(), "ghost" + attempt,
                    "Wrong@123", "203.0.113." + (attempt + 1));
            assertEquals(HttpStatus.UNAUTHORIZED.value(), failed.getStatusCode().value());
        }

        ResponseEntity<JsonNode> locked = login(store.storeCode(), store.username(), PASSWORD,
                "203.0.113.99");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), locked.getStatusCode().value(),
                "an untrusted peer cannot replace the connection address used for throttling");
        assertTrue(locked.getBody().get("message").asText().contains("Too many failed sign-in attempts"));
    }

    @Test
    void perAccountLimitStillLocksRepeatedGuessesForOneAccount() {
        StoreSession store = registerStore("ACCOUNT");

        for (int attempt = 0; attempt < 3; attempt++) {
            ResponseEntity<JsonNode> failed = login(store.storeCode(), store.username(),
                    "Wrong@123", null);
            assertEquals(HttpStatus.UNAUTHORIZED.value(), failed.getStatusCode().value());
        }

        ResponseEntity<JsonNode> locked = login(store.storeCode(), store.username(), PASSWORD, null);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), locked.getStatusCode().value());

        // Only that account key is locked; the address budget (3 of 5) is intact.
        ResponseEntity<JsonNode> otherUser = login(store.storeCode(), "ghost", PASSWORD, null);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), otherUser.getStatusCode().value());
        assertEquals(GENERIC_MESSAGE, otherUser.getBody().get("message").asText());

        loginAttemptService.clearAll();
        assertEquals(HttpStatus.OK.value(),
                login(store.storeCode(), store.username(), PASSWORD, null).getStatusCode().value());
    }

    @Test
    void forwardedForFromAnUntrustedPeerDoesNotBypassThePairingLimit() {
        for (int attempt = 0; attempt < 3; attempt++) {
            ResponseEntity<JsonNode> failed = pair("ZZZZZZZ" + attempt,
                    "203.0.113." + (attempt + 1));
            assertEquals(HttpStatus.BAD_REQUEST.value(), failed.getStatusCode().value());
            assertEquals("Pairing code is invalid or has expired",
                    failed.getBody().get("message").asText());
        }

        ResponseEntity<JsonNode> locked = pair("ZZZZZZZZ", "203.0.113.99");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), locked.getStatusCode().value(),
                "an untrusted peer cannot replace the connection address used for throttling");
    }

    // ---------- fixtures ----------

    private record StoreSession(String storeCode, String username, long storeId) {
    }

    private StoreSession registerStore(String prefix) {
        int seq = SEQ.incrementAndGet();
        String code = "H" + prefix.charAt(0) + seq
                + Integer.toString((int) (System.currentTimeMillis() % 1296), 36).toUpperCase();
        String username = "owner" + seq;
        provisioningService.createStore(new RegisterStoreRequest(code, "Login Hardening " + code,
                "Store Owner", username + "@test.local", "9000000021", "2 Test Street", null,
                "Karnataka", username, PASSWORD, "Main Branch"), false);
        Long storeId = jdbc.queryForObject("SELECT id FROM stores WHERE store_code = ?",
                Long.class, code);
        assertNotNull(storeId);
        return new StoreSession(code, username, storeId);
    }

    private ResponseEntity<JsonNode> login(String storeCode, String username, String password,
                                           String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("storeCode", storeCode);
        payload.put("username", username);
        payload.put("password", password);
        return rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(payload, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> pair(String code, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("deviceName", "Spoof Bridge");
        payload.put("platform", "WINDOWS");
        return rest.exchange("/api/printing/devices/pair", HttpMethod.POST,
                new HttpEntity<>(payload, headers), JsonNode.class);
    }
}
