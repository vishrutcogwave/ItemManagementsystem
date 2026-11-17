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
 * Covers the per-address throttle on the unauthenticated print-bridge pairing
 * endpoint. The pairing limit is narrowed to three failures so the tests stay
 * short; production defaults live in {@code application.yml}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54342",
        "app.embedded-postgres.data-dir=build/pgdata-pairing-rate-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "app.auth.pairing-max-attempts=3",
        "app.auth.pairing-window-seconds=60",
        "app.auth.pairing-lockout-seconds=300",
        "logging.level.com.itemmanagement=WARN"
})
class PairingRateLimitIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PAIR_PATH = "/api/printing/devices/pair";
    private static final String GENERIC_MESSAGE = "Pairing code is invalid or has expired";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private StoreProvisioningService provisioningService;

    @BeforeEach
    void resetRateLimits() {
        loginAttemptService.clearAll();
    }

    @Test
    void repeatedInvalidAttemptsFromOneAddressAreThrottled() {
        Session session = registerStore();
        String validCode = newPairingCode(session);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertGenericFailure(pairFrom("198.51.100.10", "ZZZZZZZ" + attempt));
        }

        // Once the address is locked even a real, unused code gets 429, so the
        // throttle cannot be used to probe whether a code exists.
        ResponseEntity<JsonNode> throttled = pairFrom("198.51.100.10", validCode);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), throttled.getStatusCode().value());
        String message = throttled.getBody().get("message").asText();
        assertTrue(message.contains("pairing"),
                () -> "the throttle must describe pairing, not sign-in: " + message);
        assertFalse(message.toLowerCase().contains("sign-in"),
                () -> "the pairing throttle must not reuse the sign-in wording: " + message);

        // The throttled request never reached the service, so the code is intact.
        JsonNode paired = pairExpect("198.51.100.20", validCode, HttpStatus.OK);
        assertFalse(paired.get("deviceToken").asText().isBlank());
    }

    @Test
    void differentAddressesHaveIndependentBudgets() {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertGenericFailure(pairFrom("198.51.100.11", "YYYYYYY" + attempt));
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(),
                pairFrom("198.51.100.11", "YYYYYYYY").getStatusCode().value());

        assertGenericFailure(pairFrom("198.51.100.12", "XXXXXXXX"));
    }

    @Test
    void aSuccessfulPairingDoesNotCountAsAFailure() {
        Session session = registerStore();
        assertGenericFailure(pairFrom("198.51.100.13", "AAAAAAAA"));
        assertGenericFailure(pairFrom("198.51.100.13", "BBBBBBBB"));

        JsonNode paired = pairExpect("198.51.100.13", newPairingCode(session), HttpStatus.OK);
        assertFalse(paired.get("deviceToken").asText().isBlank());

        // The success neither incremented nor reset the counter: the third
        // failure still returns the ordinary generic rejection.
        assertGenericFailure(pairFrom("198.51.100.13", "CCCCCCCC"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(),
                pairFrom("198.51.100.13", "DDDDDDDD").getStatusCode().value());
    }

    @Test
    void unknownUsedAndExpiredCodesReturnTheSameResponse() {
        Session session = registerStore();

        String usedCode = newPairingCode(session);
        pairExpect("198.51.100.14", usedCode, HttpStatus.OK);
        assertGenericFailure(pairFrom("198.51.100.14", usedCode));

        String expiredCode = newPairingCode(session);
        jdbc.update("UPDATE print_pairing_codes SET expires_at = now() - interval '1 minute' "
                + "WHERE used_at IS NULL");
        assertGenericFailure(pairFrom("198.51.100.14", expiredCode));

        assertGenericFailure(pairFrom("198.51.100.14", "QQQQQQQQ"));

        // Three failures lock the address; the next probe is throttled, not 400.
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(),
                pairFrom("198.51.100.14", "WWWWWWWW").getStatusCode().value());
    }

    // ---------- fixtures ----------

    private record Session(String token, long branchId) {
    }

    private Session registerStore() {
        int seq = SEQ.incrementAndGet();
        String storeCode = "R" + seq + Integer.toString(
                (int) (System.currentTimeMillis() % 1296), 36).toUpperCase();
        String username = "owner" + seq;
        provisioningService.createStore(new RegisterStoreRequest(storeCode,
                "Pairing Rate Store " + storeCode, "Store Owner",
                "owner" + seq + "@test.local", "9000000031", "3 Test Street", null,
                "Karnataka", username, "Secret@123", "Main Branch"), false);
        JsonNode auth = exchange(HttpMethod.POST, "/api/auth/login", null, null,
                map("storeCode", storeCode, "username", username, "password", "Secret@123"),
                HttpStatus.OK);
        return new Session(auth.get("token").asText(), auth.at("/branches/0/id").asLong());
    }

    private String newPairingCode(Session session) {
        JsonNode code = exchange(HttpMethod.POST,
                "/api/printing/printers/pairing-code?branchId=" + session.branchId(), null,
                session.token(), null, HttpStatus.OK);
        assertNotNull(code.get("code"));
        return code.get("code").asText();
    }

    private JsonNode pairExpect(String address, String code, HttpStatus expected) {
        return exchange(HttpMethod.POST, PAIR_PATH, address, null,
                map("code", code, "deviceName", "Rate Bridge", "platform", "ANDROID"), expected);
    }

    private ResponseEntity<JsonNode> pairFrom(String address, String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", address);
        return rest.exchange(PAIR_PATH, HttpMethod.POST, new HttpEntity<>(
                map("code", code, "deviceName", "Rate Bridge", "platform", "ANDROID"), headers),
                JsonNode.class);
    }

    private void assertGenericFailure(ResponseEntity<JsonNode> response) {
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value(),
                () -> "expected 400 but got " + response.getStatusCode() + " "
                        + response.getBody());
        assertEquals(GENERIC_MESSAGE, response.getBody().get("message").asText());
    }

    private JsonNode exchange(HttpMethod method, String path, String forwardedFor, String token,
                              Object body, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<JsonNode> response = rest.exchange(path, method,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(expected.value(), response.getStatusCode().value(),
                () -> method + " " + path + " expected " + expected + " but got "
                        + response.getStatusCode() + " " + response.getBody());
        return response.getBody();
    }

    private Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
