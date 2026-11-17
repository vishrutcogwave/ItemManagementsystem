package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.security.LoginAttemptService;
import com.itemmanagement.service.StoreProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the production proxy topology: Tomcat's RemoteIpValve trusts
 * forwarded addresses only from the loopback proxy. The test client connects
 * from loopback, so the X-Forwarded-For values below stand in for the header
 * NGINX sets to the visitor address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54342",
        "app.embedded-postgres.data-dir=build/pgdata-forwarded-client-ip-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "app.auth.login-max-attempts=5",
        "app.auth.login-ip-max-attempts=2",
        "logging.level.com.itemmanagement=WARN"
})
class ForwardedClientIpIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "Secret@123";
    private static final String LOCKED_MESSAGE = "Too many failed sign-in attempts";
    private static final String INTERNAL_PROXIES =
            "127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|0:0:0:0:0:0:0:1|::1";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private StoreProvisioningService provisioningService;

    @Autowired
    private ServerProperties serverProperties;

    @BeforeEach
    void resetLoginAttempts() {
        loginAttemptService.clearAll();
    }

    @Test
    void trustedLoopbackProxyResolvesTheRightmostForwardedAddress() {
        StoreSession store = registerStore("RIGHT");

        // A client can prepend anything; NGINX appends the real visitor last.
        assertEquals(HttpStatus.UNAUTHORIZED.value(), login(store.storeCode(), "ghost0",
                PASSWORD, "203.0.113.10, 198.51.100.7").getStatusCode().value());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), login(store.storeCode(), "ghost1",
                PASSWORD, "198.51.100.7").getStatusCode().value());

        ResponseEntity<JsonNode> locked = login(store.storeCode(), store.username(),
                PASSWORD, "198.51.100.7");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), locked.getStatusCode().value(),
                "both requests must resolve to the visitor address appended by the proxy");
        assertTrue(locked.getBody().get("message").asText().contains(LOCKED_MESSAGE));

        // The spoofed left-hand value did not create or consume a bucket.
        assertEquals(HttpStatus.UNAUTHORIZED.value(), login(store.storeCode(), "ghost2",
                PASSWORD, "203.0.113.10").getStatusCode().value());
    }

    @Test
    void differentVisitorAddressesUseIndependentAggregateBuckets() {
        StoreSession store = registerStore("VISITORS");

        assertEquals(HttpStatus.UNAUTHORIZED.value(), login(store.storeCode(), "ghost0",
                PASSWORD, "198.51.100.20").getStatusCode().value());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), login(store.storeCode(), "ghost1",
                PASSWORD, "198.51.100.20").getStatusCode().value());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), login(store.storeCode(),
                store.username(), PASSWORD, "198.51.100.20").getStatusCode().value());

        assertEquals(HttpStatus.UNAUTHORIZED.value(), login(store.storeCode(), "ghost2",
                PASSWORD, "198.51.100.21").getStatusCode().value());
        assertEquals(HttpStatus.OK.value(), login(store.storeCode(), store.username(),
                PASSWORD, "198.51.100.21").getStatusCode().value(),
                "one visitor's failures must not lock another visitor");
    }

    @Test
    void requestsWithoutForwardedHeaderUseTheSocketAddress() {
        StoreSession store = registerStore("DIRECT");

        assertEquals(HttpStatus.UNAUTHORIZED.value(),
                login(store.storeCode(), "ghost0", PASSWORD, null).getStatusCode().value());
        assertEquals(HttpStatus.UNAUTHORIZED.value(),
                login(store.storeCode(), "ghost1", PASSWORD, null).getStatusCode().value());

        ResponseEntity<JsonNode> locked = login(store.storeCode(), store.username(),
                PASSWORD, null);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), locked.getStatusCode().value());
        assertTrue(locked.getBody().get("message").asText().contains(LOCKED_MESSAGE));

        assertEquals(HttpStatus.UNAUTHORIZED.value(), login(store.storeCode(), "ghost2",
                PASSWORD, "198.51.100.30").getStatusCode().value());
    }

    @Test
    void forwardedHostAndPortHeadersAreNotProcessed() {
        assertEquals(ServerProperties.ForwardHeadersStrategy.NATIVE,
                serverProperties.getForwardHeadersStrategy());
        assertEquals(INTERNAL_PROXIES,
                serverProperties.getTomcat().getRemoteip().getInternalProxies());
        assertFalse(StringUtils.hasText(
                serverProperties.getTomcat().getRemoteip().getHostHeader()));
        assertFalse(StringUtils.hasText(
                serverProperties.getTomcat().getRemoteip().getPortHeader()));
    }

    // ---------- fixtures ----------

    private record StoreSession(String storeCode, String username, long storeId) {
    }

    private StoreSession registerStore(String prefix) {
        int seq = SEQ.incrementAndGet();
        String code = "F" + prefix.charAt(0) + seq
                + Integer.toString((int) (System.currentTimeMillis() % 1296), 36).toUpperCase();
        String username = "owner" + seq;
        provisioningService.createStore(new RegisterStoreRequest(code, "Forwarded IP " + code,
                "Store Owner", username + "@test.local", "9000000022", "3 Test Street", null,
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
}
