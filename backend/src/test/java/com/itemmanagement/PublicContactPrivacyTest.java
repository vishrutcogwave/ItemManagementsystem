package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.UserRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The public sign-up screen and the expired-subscription response must only ever
 * show the dedicated support contact stored in platform_settings. They must never
 * fall back to a platform administrator's personal email address or mobile number.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54338",
        "app.embedded-postgres.data-dir=build/pgdata-public-contact-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "app.platform-admin.provision=false",
        "logging.level.com.itemmanagement=WARN"
})
class PublicContactPrivacyTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String ADMIN_EMAIL = "personal.admin@test.local";
    private static final String ADMIN_MOBILE = "9876500123";
    private static final String ADMIN_PASSWORD = "Admin@123";
    private static final String OWNER_PASSWORD = "Secret@123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StoreProvisioningService provisioningService;

    private volatile String cachedAdminToken;

    @BeforeEach
    void ensurePlatformAdminExists() {
        ensurePlatformAdmin();
    }

    @Test
    void publicContactDoesNotExposeAdminEmailWhenSupportEmailIsMissing() {
        updateSettings(null, "1800555010");

        JsonNode contact = publicContact();
        assertEquals("", contact.get("contactEmail").asText());
        assertEquals("1800555010", contact.get("contactPhone").asText());
        assertNoAdminPersonalData(contact);
    }

    @Test
    void publicContactDoesNotExposeAdminMobileWhenSupportPhoneIsMissing() {
        updateSettings("help@ims.test", null);

        JsonNode contact = publicContact();
        assertEquals("help@ims.test", contact.get("contactEmail").asText());
        assertEquals("", contact.get("contactPhone").asText());
        assertNoAdminPersonalData(contact);
    }

    @Test
    void unconfiguredPublicContactIsEmptyInsteadOfPersonalData() {
        updateSettings(null, null);

        JsonNode contact = publicContact();
        assertEquals("", contact.get("contactEmail").asText());
        assertEquals("", contact.get("contactPhone").asText());
        assertNoAdminPersonalData(contact);
    }

    @Test
    void configuredSupportDetailsAreReturnedByThePublicEndpoint() {
        updateSettings("support@ims.test", "1800123456");

        JsonNode contact = publicContact();
        assertEquals("support@ims.test", contact.get("contactEmail").asText());
        assertEquals("1800123456", contact.get("contactPhone").asText());
        assertNotNull(contact.get("updatedAt"));
        assertNoAdminPersonalData(contact);

        JsonNode adminContact = call(HttpMethod.GET, "/api/platform/contact", null,
                adminToken(), HttpStatus.OK);
        assertEquals("support@ims.test", adminContact.get("contactEmail").asText());
        assertEquals("1800123456", adminContact.get("contactPhone").asText());
    }

    @Test
    void expiredSubscriptionResponseDoesNotFallBackToAdminContact() {
        updateSettings(null, null);

        int seq = SEQ.incrementAndGet();
        String code = "PRIV" + seq;
        String username = "owner" + seq;
        provisioningService.createStore(new RegisterStoreRequest(code, "Privacy Store " + code,
                "Store Owner", username + "@test.local", "9000000300", "1 Privacy Street",
                null, "Karnataka", username, OWNER_PASSWORD, "Main Branch"), false);
        JsonNode auth = login(code, username, OWNER_PASSWORD);
        long storeId = auth.at("/store/id").asLong();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        call(HttpMethod.PUT, "/api/stores/" + storeId + "/subscription",
                map("startDate", today.minusYears(1).toString(),
                        "endDate", today.minusDays(1).toString(), "maxBranches", 5),
                adminToken(), HttpStatus.OK);

        JsonNode blocked = call(HttpMethod.GET, "/api/items", null,
                auth.get("token").asText(), HttpStatus.FORBIDDEN);
        assertEquals("SUBSCRIPTION_EXPIRED", blocked.get("code").asText());
        assertEquals("", blocked.get("contactEmail").asText());
        assertEquals("", blocked.get("contactPhone").asText());
        assertNoAdminPersonalData(blocked);
    }

    // ---------- fixtures ----------

    private JsonNode publicContact() {
        return call(HttpMethod.GET, "/api/platform/public-contact", null, null, HttpStatus.OK);
    }

    private void updateSettings(String email, String phone) {
        jdbc.update("UPDATE platform_settings SET contact_email = ?, contact_phone = ? WHERE id = 1",
                email, phone);
    }

    private void assertNoAdminPersonalData(JsonNode body) {
        String text = body.toString();
        assertFalse(text.contains(ADMIN_EMAIL),
                "public response must not expose the platform admin email: " + text);
        assertFalse(text.contains(ADMIN_MOBILE),
                "public response must not expose the platform admin mobile: " + text);
    }

    private void ensurePlatformAdmin() {
        if (userRepository.findByUsernameIgnoreCaseAndStoreIdIsNull("platform").isPresent()) {
            return;
        }
        Role role = roleRepository.findByStoreIdIsNullAndCode("PLATFORM_ADMIN")
                .orElseThrow(() -> new IllegalStateException("PLATFORM_ADMIN role is missing"));
        userRepository.save(User.builder()
                .storeId(null)
                .username("platform")
                .email(ADMIN_EMAIL)
                .passwordHash(passwordEncoder.encode(ADMIN_PASSWORD))
                .fullName("Platform Administrator")
                .mobile(ADMIN_MOBILE)
                .status(StoreStatus.ACTIVE)
                .roles(Set.of(role))
                .branches(Set.of())
                .build());
    }

    private String adminToken() {
        String token = cachedAdminToken;
        if (token == null) {
            synchronized (this) {
                if (cachedAdminToken == null) {
                    cachedAdminToken = login("PLATFORM", "platform", ADMIN_PASSWORD)
                            .get("token").asText();
                }
                token = cachedAdminToken;
            }
        }
        return token;
    }

    private JsonNode login(String storeCode, String username, String password) {
        return call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", storeCode, "username", username, "password", password),
                null, HttpStatus.OK);
    }

    private JsonNode call(HttpMethod method, String path, Object body, String token,
                          HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
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

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }
}
