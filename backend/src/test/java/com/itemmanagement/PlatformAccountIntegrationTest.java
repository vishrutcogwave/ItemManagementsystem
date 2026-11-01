package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.UserRepository;
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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Platform-administrator management of store login accounts: discovery,
 * username changes, enable/disable, one-time password resets, credential audit
 * history and authorization boundaries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54333",
        "app.embedded-postgres.data-dir=build/pgdata-platform-account-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "logging.level.com.itemmanagement=WARN"
})
class PlatformAccountIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

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

    private volatile String cachedPlatformToken;

    @Test
    void platformAdminFindsStoreAccountsByStoreUsernameAndCodeWithoutPasswordHashes() {
        Session store = registerStore("ACCT");
        long ownerId = store.userId();

        JsonNode allAccounts = call(HttpMethod.GET, "/api/platform/accounts", null,
                platformToken(), HttpStatus.OK);
        assertTrue(allAccounts.get("totalElements").asLong() >= 1);

        JsonNode byCode = call(HttpMethod.GET,
                "/api/platform/accounts?search=" + store.storeCode(), null,
                platformToken(), HttpStatus.OK);
        assertEquals(1, byCode.get("content").size());
        JsonNode account = byCode.at("/content/0");
        assertEquals(ownerId, account.get("id").asLong());
        assertEquals(store.storeCode(), account.get("storeCode").asText());
        assertEquals("STORE_OWNER", account.at("/roles/0/code").asText());
        assertTrue(account.get("owner").asBoolean());
        assertTrue(account.get("status").asText().equals("ACTIVE"));
        assertNoPasswordFields(account);

        JsonNode byStoreName = call(HttpMethod.GET,
                "/api/platform/accounts?search=Account", null,
                platformToken(), HttpStatus.OK);
        assertTrue(byStoreName.get("totalElements").asLong() >= 1);

        JsonNode byUsername = call(HttpMethod.GET,
                "/api/platform/accounts?search=" + store.username(), null,
                platformToken(), HttpStatus.OK);
        assertEquals(ownerId, byUsername.at("/content/0/id").asLong());

        JsonNode platformAccounts = call(HttpMethod.GET,
                "/api/platform/accounts?search=platform", null,
                platformToken(), HttpStatus.OK);
        for (JsonNode row : platformAccounts.get("content")) {
            assertTrue(row.get("storeId").asLong() > 0,
                    "platform-level accounts must never appear in the store account list");
        }

        JsonNode detail = call(HttpMethod.GET, "/api/platform/accounts/" + ownerId, null,
                platformToken(), HttpStatus.OK);
        assertEquals(ownerId, detail.get("id").asLong());
        assertNoPasswordFields(detail);
    }

    @Test
    void resetPasswordReturnsOneTimeTemporaryPasswordAndAuditsTheChange() {
        Session store = registerStore("RESET");

        ResponseEntity<JsonNode> missingConfirmation = rest.exchange(
                "/api/platform/accounts/" + store.userId() + "/reset-password", HttpMethod.POST,
                entity(map("confirm", false), platformToken()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), missingConfirmation.getStatusCode().value());

        JsonNode reset = call(HttpMethod.POST,
                "/api/platform/accounts/" + store.userId() + "/reset-password",
                map("confirm", true), platformToken(), HttpStatus.OK);
        String temporaryPassword = reset.get("temporaryPassword").asText();
        assertFalse(temporaryPassword.isBlank());
        assertEquals(14, temporaryPassword.length());
        assertEquals(store.username(), reset.get("username").asText());
        assertEquals(store.storeCode(), reset.get("storeCode").asText());

        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, store.userId());
        assertNotEquals(temporaryPassword, storedHash);
        assertTrue(storedHash.startsWith("$2"), "the password must stay hashed");

        // The old password no longer works and the generated one does.
        ResponseEntity<JsonNode> oldLogin = rest.exchange("/api/auth/login", HttpMethod.POST,
                entity(map("storeCode", store.storeCode(), "username", store.username(),
                        "password", "Secret@123"), null), JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), oldLogin.getStatusCode().value());
        JsonNode newLogin = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", store.storeCode(), "username", store.username(),
                        "password", temporaryPassword), null, HttpStatus.OK);
        assertFalse(newLogin.get("token").asText().isBlank());

        JsonNode history = call(HttpMethod.GET,
                "/api/platform/accounts/" + store.userId() + "/history", null,
                platformToken(), HttpStatus.OK);
        assertEquals(1, history.size());
        assertEquals("PASSWORD_RESET", history.get(0).get("action").asText());
        assertEquals("platform", history.get(0).get("actorUsername").asText());
        assertFalse(history.get(0).get("details").asText().contains(temporaryPassword),
                "audit details must never contain the temporary password");
    }

    @Test
    void usernamesCanBeChangedByThePlatformAdminWithStoreScopedUniqueness() {
        Session store = registerStore("NAME");
        Session staff = createUserSession(store, "BRANCH_STAFF");
        String newUsername = "renamed" + SEQ.incrementAndGet();

        JsonNode updated = call(HttpMethod.PUT,
                "/api/platform/accounts/" + staff.userId() + "/username",
                map("username", newUsername), platformToken(), HttpStatus.OK);
        assertEquals(newUsername, updated.get("username").asText());

        ResponseEntity<JsonNode> oldLogin = rest.exchange("/api/auth/login", HttpMethod.POST,
                entity(map("storeCode", store.storeCode(), "username", staff.username(),
                        "password", "Secret@123"), null), JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), oldLogin.getStatusCode().value());
        call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", store.storeCode(), "username", newUsername,
                        "password", "Secret@123"), null, HttpStatus.OK);

        ResponseEntity<JsonNode> duplicate = rest.exchange(
                "/api/platform/accounts/" + staff.userId() + "/username", HttpMethod.PUT,
                entity(map("username", store.username()), platformToken()), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT.value(), duplicate.getStatusCode().value());

        // The same username may exist in another tenant.
        Session other = registerStore("NAME");
        JsonNode otherUpdated = call(HttpMethod.PUT,
                "/api/platform/accounts/" + other.userId() + "/username",
                map("username", newUsername), platformToken(), HttpStatus.OK);
        assertEquals(newUsername, otherUpdated.get("username").asText());

        ResponseEntity<JsonNode> invalid = rest.exchange(
                "/api/platform/accounts/" + staff.userId() + "/username", HttpMethod.PUT,
                entity(map("username", "bad name!"), platformToken()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), invalid.getStatusCode().value());

        JsonNode history = call(HttpMethod.GET,
                "/api/platform/accounts/" + staff.userId() + "/history", null,
                platformToken(), HttpStatus.OK);
        assertEquals("USERNAME_UPDATE", history.get(0).get("action").asText());
        assertTrue(history.get(0).get("details").asText().contains(staff.username()));
    }

    @Test
    void platformAdminCanDisableAndEnableAStoreLoginAccount() {
        Session store = registerStore("STATUS");
        Session staff = createUserSession(store, "BRANCH_STAFF");

        JsonNode disabled = call(HttpMethod.PUT,
                "/api/platform/accounts/" + staff.userId() + "/status",
                map("status", "INACTIVE"), platformToken(), HttpStatus.OK);
        assertEquals("INACTIVE", disabled.get("status").asText());

        ResponseEntity<JsonNode> blocked = rest.exchange("/api/auth/login", HttpMethod.POST,
                entity(map("storeCode", store.storeCode(), "username", staff.username(),
                        "password", "Secret@123"), null), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), blocked.getStatusCode().value());

        JsonNode enabled = call(HttpMethod.PUT,
                "/api/platform/accounts/" + staff.userId() + "/status",
                map("status", "ACTIVE"), platformToken(), HttpStatus.OK);
        assertEquals("ACTIVE", enabled.get("status").asText());
        call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", store.storeCode(), "username", staff.username(),
                        "password", "Secret@123"), null, HttpStatus.OK);

        JsonNode history = call(HttpMethod.GET,
                "/api/platform/accounts/" + staff.userId() + "/history", null,
                platformToken(), HttpStatus.OK);
        assertEquals(2, history.size());
        assertEquals("ACTIVATE", history.get(0).get("action").asText());
        assertEquals("DEACTIVATE", history.get(1).get("action").asText());
    }

    @Test
    void storeUsersAndPlatformAccountsAreRejectedByTheAccountApi() {
        Session store = registerStore("AUTH");

        ResponseEntity<JsonNode> forbidden = rest.exchange("/api/platform/accounts", HttpMethod.GET,
                bearerOnly(store.token()), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), forbidden.getStatusCode().value());

        ResponseEntity<JsonNode> forbiddenReset = rest.exchange(
                "/api/platform/accounts/" + store.userId() + "/reset-password", HttpMethod.POST,
                entity(map("confirm", true), store.token()), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), forbiddenReset.getStatusCode().value());

        platformToken();
        User platform = userRepository.findByUsernameIgnoreCaseAndStoreIdIsNull("platform")
                .orElseThrow();
        ResponseEntity<JsonNode> platformAccount = rest.exchange(
                "/api/platform/accounts/" + platform.getId(), HttpMethod.GET,
                bearerOnly(platformToken()), JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND.value(), platformAccount.getStatusCode().value());

        ResponseEntity<JsonNode> resetPlatform = rest.exchange(
                "/api/platform/accounts/" + platform.getId() + "/reset-password", HttpMethod.POST,
                entity(map("confirm", true), platformToken()), JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND.value(), resetPlatform.getStatusCode().value());
    }

    // ---------- fixtures ----------

    private record Session(String token, long userId, long storeId, long branchId,
                           String storeCode, String username) {
    }

    private Session registerStore(String prefix) {
        int seq = SEQ.incrementAndGet();
        String code = "P" + prefix.charAt(0) + seq
                + Integer.toString((int) (System.currentTimeMillis() % 1296), 36).toUpperCase();
        String username = "owner" + seq;
        call(HttpMethod.POST, "/api/auth/register-store",
                map("storeCode", code,
                        "storeName", "Platform Account Store " + code,
                        "ownerName", "Store Owner",
                        "email", "owner" + seq + "@test.local",
                        "mobile", "9000000007",
                        "address", "1 Test Street",
                        "state", "Karnataka",
                        "username", username,
                        "password", "Secret@123",
                        "branchName", "Main Branch"), null, HttpStatus.CREATED);
        return loginSession(code, username, "Secret@123");
    }

    private Session loginSession(String storeCode, String username, String password) {
        JsonNode auth = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", storeCode, "username", username, "password", password),
                null, HttpStatus.OK);
        return new Session(auth.get("token").asText(), auth.at("/user/id").asLong(),
                auth.at("/store/id").asLong(), auth.at("/branches/0/id").asLong(),
                storeCode, username);
    }

    private Session createUserSession(Session owner, String roleCode) {
        int seq = SEQ.incrementAndGet();
        String username = "staff" + seq;
        call(HttpMethod.POST, "/api/users",
                map("username", username,
                        "email", username + "@test.local",
                        "password", "Secret@123",
                        "fullName", "Staff " + username,
                        "mobile", "9000000013",
                        "roleId", roleId(owner, roleCode),
                        "branchIds", List.of(owner.branchId())),
                owner.token(), HttpStatus.OK);
        return loginSession(owner.storeCode(), username, "Secret@123");
    }

    private long roleId(Session session, String roleCode) {
        JsonNode roles = call(HttpMethod.GET, "/api/users/roles/available", null,
                session.token(), HttpStatus.OK);
        for (JsonNode role : roles) {
            if (roleCode.equals(role.get("code").asText())) {
                return role.get("id").asLong();
            }
        }
        throw new IllegalStateException("Role not found: " + roleCode);
    }

    private String platformToken() {
        String token = cachedPlatformToken;
        if (token == null) {
            synchronized (this) {
                if (cachedPlatformToken == null) {
                    userRepository.findByUsernameIgnoreCaseAndStoreIdIsNull("platform").orElseGet(() -> {
                        Role role = roleRepository.findByStoreIdIsNullAndCode("PLATFORM_ADMIN")
                                .orElseThrow(() -> new IllegalStateException("PLATFORM_ADMIN role is missing"));
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
                    cachedPlatformToken = call(HttpMethod.POST, "/api/auth/login",
                            map("storeCode", "PLATFORM", "username", "platform",
                                    "password", "Admin@123"), null, HttpStatus.OK)
                            .get("token").asText();
                }
                token = cachedPlatformToken;
            }
        }
        return token;
    }

    private void assertNoPasswordFields(JsonNode account) {
        Iterator<String> fields = account.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next().toLowerCase();
            assertFalse(field.contains("password"), "account responses must not expose " + field);
            assertFalse(field.contains("hash"), "account responses must not expose " + field);
        }
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

    private HttpEntity<Object> entity(Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> bearerOnly(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(headers);
    }

    private Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
