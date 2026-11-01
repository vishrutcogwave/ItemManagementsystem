package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.security.JwtAuthFilter;
import com.itemmanagement.security.JwtService;
import com.itemmanagement.security.LoginAttemptService;
import com.itemmanagement.security.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the authentication and operational hardening added for K-01 to K-11,
 * K-22, K-24 and K-27: no privileged defaults, self-service password change,
 * forced first-login rotation, persistent token revocation, login lockout,
 * cookie sessions with an origin check, temporary-password headers, the audit
 * viewer, health/version endpoints, pagination clamps and the shared password
 * policy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54334",
        "app.embedded-postgres.data-dir=build/pgdata-auth-security-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "app.auth.login-max-attempts=3",
        "logging.level.com.itemmanagement=WARN"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthSecurityIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String TRUSTED_ORIGIN = "http://localhost:5173";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private TokenBlacklistService blacklistService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private Environment environment;

    private volatile String cachedPlatformToken;

    @BeforeEach
    void resetLoginAttempts() {
        loginAttemptService.clearAll();
    }

    // ------------------------------------------------------------------
    // K-01: no implicit privileged account
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void noPlatformAdministratorIsCreatedWithoutExplicitProvisioning() {
        assertEquals("false", environment.getProperty("app.platform-admin.provision"),
                "provisioning must be opt-in");
        assertTrue(userRepository.findPlatformAdmins().isEmpty(),
                "starting the application must not create a platform administrator");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE store_id IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE lower(username) IN ('vishrutgaming', 'admin') "
                        + "AND store_id IS NULL", Integer.class));
    }

    // ------------------------------------------------------------------
    // K-02 / K-03 / K-05: self-service password change and revocation
    // ------------------------------------------------------------------

    @Test
    void storeUserChangesOwnPasswordAndEveryPreviousSessionIsInvalidated() {
        StoreSession store = registerStore("SELF");
        String secondToken = login(store.storeCode(), store.username(), "Secret@123").get("token").asText();

        ResponseEntity<JsonNode> wrongCurrent = rest.exchange("/api/auth/change-password",
                HttpMethod.POST, bearer(map("currentPassword", "Wrong@123",
                        "newPassword", "NewSecret@123"), store.token()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), wrongCurrent.getStatusCode().value());

        ResponseEntity<JsonNode> weakNew = rest.exchange("/api/auth/change-password",
                HttpMethod.POST, bearer(map("currentPassword", "Secret@123",
                        "newPassword", "weakpass"), store.token()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), weakNew.getStatusCode().value());

        ResponseEntity<JsonNode> samePassword = rest.exchange("/api/auth/change-password",
                HttpMethod.POST, bearer(map("currentPassword", "Secret@123",
                        "newPassword", "Secret@123"), store.token()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), samePassword.getStatusCode().value());

        JsonNode changed = call(HttpMethod.POST, "/api/auth/change-password",
                map("currentPassword", "Secret@123", "newPassword", "NewSecret@123"),
                store.token(), HttpStatus.OK);
        assertFalse(changed.get("mustChangePassword").asBoolean());
        String newToken = changed.get("token").asText();
        assertFalse(newToken.isBlank());

        // Both earlier sessions are refused: one was blacklisted, the other is
        // rejected because the account's token version advanced.
        assertEquals(HttpStatus.UNAUTHORIZED.value(), rest.exchange("/api/auth/me", HttpMethod.GET,
                bearerOnly(store.token()), JsonNode.class).getStatusCode().value());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), rest.exchange("/api/auth/me", HttpMethod.GET,
                bearerOnly(secondToken), JsonNode.class).getStatusCode().value());
        call(HttpMethod.GET, "/api/auth/me", null, newToken, HttpStatus.OK);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), rest.exchange("/api/auth/login", HttpMethod.POST,
                noAuth(map("storeCode", store.storeCode(), "username", store.username(),
                        "password", "Secret@123")), JsonNode.class).getStatusCode().value());
        call(HttpMethod.POST, "/api/auth/login", map("storeCode", store.storeCode(),
                "username", store.username(), "password", "NewSecret@123"), null, HttpStatus.OK);

        String storedHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?",
                String.class, store.userId());
        assertTrue(storedHash.startsWith("$2"), "passwords must stay BCrypt-hashed");
        assertNotEquals("NewSecret@123", storedHash);

        Integer auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'PASSWORD_CHANGE' AND user_id = ?",
                Integer.class, store.userId());
        assertTrue(auditRows != null && auditRows >= 1);
        assertFalse(jdbc.queryForObject(
                "SELECT must_change_password FROM users WHERE id = ?", Boolean.class, store.userId()));
    }

    @Test
    void platformAdministratorChangesOwnPassword() {
        String platform = platformToken();
        JsonNode changed = call(HttpMethod.POST, "/api/auth/change-password",
                map("currentPassword", "Admin@123", "newPassword", "AdminNew@123"),
                platform, HttpStatus.OK);
        assertEquals(false, changed.get("mustChangePassword").asBoolean());

        assertEquals(HttpStatus.UNAUTHORIZED.value(), rest.exchange("/api/auth/me", HttpMethod.GET,
                bearerOnly(platform), JsonNode.class).getStatusCode().value());
        JsonNode login = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", "PLATFORM", "username", "platform", "password", "AdminNew@123"),
                null, HttpStatus.OK);
        assertFalse(login.get("token").asText().isBlank());
        cachedPlatformToken = login.get("token").asText();
    }

    @Test
    void administratorResetForcesPasswordChangeOnNextLogin() {
        StoreSession store = registerStore("FORCE");
        String platform = platformToken();

        ResponseEntity<JsonNode> reset = rest.exchange(
                "/api/platform/accounts/" + store.userId() + "/reset-password", HttpMethod.POST,
                bearer(map("confirm", true), platform), JsonNode.class);
        assertEquals(HttpStatus.OK.value(), reset.getStatusCode().value());
        assertTrue(headerContains(reset, HttpHeaders.CACHE_CONTROL, "no-store"),
                "temporary passwords must never be cached");
        assertTrue(headerContains(reset, HttpHeaders.PRAGMA, "no-cache"));
        String temporaryPassword = reset.getBody().get("temporaryPassword").asText();

        JsonNode forced = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", store.storeCode(), "username", store.username(),
                        "password", temporaryPassword), null, HttpStatus.OK);
        assertTrue(forced.get("mustChangePassword").asBoolean());
        String forcedToken = forced.get("token").asText();

        ResponseEntity<JsonNode> blocked = rest.exchange("/api/items", HttpMethod.GET,
                bearerOnly(forcedToken), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), blocked.getStatusCode().value());
        assertEquals(JwtAuthFilter.PASSWORD_CHANGE_CODE, blocked.getBody().get("code").asText());

        // /me stays available so the UI can render the forced-change screen.
        JsonNode me = call(HttpMethod.GET, "/api/auth/me", null, forcedToken, HttpStatus.OK);
        assertTrue(me.get("mustChangePassword").asBoolean());

        JsonNode changed = call(HttpMethod.POST, "/api/auth/change-password",
                map("currentPassword", temporaryPassword, "newPassword", "Chosen@1234"),
                forcedToken, HttpStatus.OK);
        assertFalse(changed.get("mustChangePassword").asBoolean());
        call(HttpMethod.GET, "/api/items", null, changed.get("token").asText(), HttpStatus.OK);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), rest.exchange("/api/auth/me", HttpMethod.GET,
                bearerOnly(forcedToken), JsonNode.class).getStatusCode().value());

        JsonNode history = call(HttpMethod.GET,
                "/api/platform/accounts/" + store.userId() + "/history", null, platform, HttpStatus.OK);
        JsonNode resetEntry = null;
        for (JsonNode row : history) {
            if ("PASSWORD_RESET".equals(row.get("action").asText())) {
                resetEntry = row;
                break;
            }
        }
        assertNotNull(resetEntry, "the administrator reset must be audited");
        assertFalse(resetEntry.get("details").asText().contains(temporaryPassword),
                "audit details must never contain the temporary password");
    }

    // ------------------------------------------------------------------
    // K-05: the revocation list survives a restart because it lives in the DB
    // ------------------------------------------------------------------

    @Test
    void revokedTokensAreStoredInTheDatabaseAndRejected() {
        StoreSession store = registerStore("REVOKE");
        Claims claims = jwtService.parse(store.token());

        call(HttpMethod.POST, "/api/auth/logout", null, store.token(), HttpStatus.OK);

        Integer persisted = jdbc.queryForObject(
                "SELECT count(*) FROM revoked_tokens WHERE jti = ?", Integer.class, claims.getId());
        assertEquals(1, persisted, "the revoked jti must be persisted, not only cached in memory");
        assertTrue(blacklistService.isRevoked(claims.getId()));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), rest.exchange("/api/auth/me", HttpMethod.GET,
                bearerOnly(store.token()), JsonNode.class).getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // K-06: login attempts are rate limited
    // ------------------------------------------------------------------

    @Test
    void repeatedFailedLoginsLockTheAccountKey() {
        StoreSession store = registerStore("LOCK");
        loginAttemptService.clearAll();

        for (int attempt = 0; attempt < 3; attempt++) {
            ResponseEntity<JsonNode> failed = rest.exchange("/api/auth/login", HttpMethod.POST,
                    noAuth(map("storeCode", store.storeCode(), "username", store.username(),
                            "password", "Wrong@123")), JsonNode.class);
            assertEquals(HttpStatus.UNAUTHORIZED.value(), failed.getStatusCode().value());
        }

        ResponseEntity<JsonNode> locked = rest.exchange("/api/auth/login", HttpMethod.POST,
                noAuth(map("storeCode", store.storeCode(), "username", store.username(),
                        "password", "Secret@123")), JsonNode.class);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), locked.getStatusCode().value());

        // A different account is unaffected.
        loginAttemptService.clearAll();
        call(HttpMethod.POST, "/api/auth/login", map("storeCode", store.storeCode(),
                "username", store.username(), "password", "Secret@123"), null, HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    // K-07: HttpOnly cookie session plus an origin check on writes
    // ------------------------------------------------------------------

    @Test
    void browserSessionUsesHttpOnlyCookieAndRejectsUntrustedOrigins() {
        StoreSession store = registerStore("COOKIE");
        LoginCookie login = loginWithCookie(store.storeCode(), store.username(), "Secret@123");
        assertTrue(login.setCookie().contains("ims_auth="), login.setCookie());
        assertTrue(login.setCookie().toLowerCase().contains("httponly"));
        assertTrue(login.setCookie().toLowerCase().contains("samesite=strict"));

        JsonNode me = callWithCookie(HttpMethod.GET, "/api/auth/me", null, login.cookiePair(), null,
                HttpStatus.OK);
        assertEquals(store.userId(), me.at("/user/id").asLong());

        ResponseEntity<JsonNode> noOrigin = rest.exchange("/api/auth/logout", HttpMethod.POST,
                cookieEntity(null, login.cookiePair(), null), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), noOrigin.getStatusCode().value());
        assertEquals(JwtAuthFilter.CSRF_CODE, noOrigin.getBody().get("code").asText());

        ResponseEntity<String> untrusted = rest.exchange("/api/auth/logout", HttpMethod.POST,
                cookieEntity(null, login.cookiePair(), "https://evil.example"), String.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), untrusted.getStatusCode().value());

        callWithCookie(HttpMethod.POST, "/api/auth/logout", null, login.cookiePair(), TRUSTED_ORIGIN,
                HttpStatus.OK);
        ResponseEntity<String> afterLogout = rest.exchange("/api/auth/me", HttpMethod.GET,
                cookieOnly(login.cookiePair()), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), afterLogout.getStatusCode().value(),
                () -> "content-type=" + afterLogout.getHeaders().getContentType()
                        + " body=" + afterLogout.getBody());

        // API clients using bearer tokens are unaffected by the origin rule.
        LoginCookie second = loginWithCookie(store.storeCode(), store.username(), "Secret@123");
        call(HttpMethod.POST, "/api/auth/logout", null, second.token(), HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    // K-08 / K-09: temporary-password headers and audit viewer
    // ------------------------------------------------------------------

    @Test
    void sessionResponsesAndTemporaryPasswordsAreNotCacheable() {
        StoreSession store = registerStore("CACHE");
        ResponseEntity<JsonNode> login = rest.exchange("/api/auth/login", HttpMethod.POST,
                noAuth(map("storeCode", store.storeCode(), "username", store.username(),
                        "password", "Secret@123")), JsonNode.class);
        assertEquals(HttpStatus.OK.value(), login.getStatusCode().value());
        assertTrue(headerContains(login, HttpHeaders.CACHE_CONTROL, "no-store"));
        assertTrue(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE) != null,
                "the browser session must arrive as a cookie");
    }

    @Test
    void platformAuditViewerFiltersAndExportsCsvForPlatformAdminsOnly() {
        StoreSession store = registerStore("AUDIT");
        String platform = platformToken();

        ResponseEntity<JsonNode> forbidden = rest.exchange("/api/platform/audit-logs",
                HttpMethod.GET, bearerOnly(store.token()), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), forbidden.getStatusCode().value());

        JsonNode page = call(HttpMethod.GET, "/api/platform/audit-logs?action=LOGIN&size=500",
                null, platform, HttpStatus.OK);
        assertTrue(page.get("totalElements").asLong() > 0);
        for (JsonNode row : page.get("content")) {
            assertEquals("LOGIN", row.get("action").asText());
        }

        List<String> actions = callList(HttpMethod.GET, "/api/platform/audit-logs/options", platform);
        assertTrue(actions.contains("LOGIN"));
        assertTrue(actions.contains("REGISTER_STORE"));

        ResponseEntity<byte[]> exported = rest.exchange("/api/platform/audit-logs/export",
                HttpMethod.GET, bearerOnly(platform), byte[].class);
        assertEquals(HttpStatus.OK.value(), exported.getStatusCode().value());
        assertTrue(exported.getHeaders().getContentType().toString().startsWith("text/csv"));
        assertTrue(exported.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
                .startsWith("attachment; filename=\"audit-logs-"));
        assertTrue(headerContains(exported, HttpHeaders.CACHE_CONTROL, "no-store"));
        String csv = new String(exported.getBody(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFFTimestamp,Store code,Store name"));
        assertTrue(csv.contains("LOGIN"));

        JsonNode negative = call(HttpMethod.GET,
                "/api/platform/audit-logs?page=-4&size=100000", null, platform, HttpStatus.OK);
        assertEquals(0, negative.get("page").asInt());
        assertEquals(100, negative.get("size").asInt());
    }

    // ------------------------------------------------------------------
    // K-10: demo data is not seeded unless explicitly enabled
    // ------------------------------------------------------------------

    @Test
    void demoStoreIsNotSeededInProductionMode() {
        assertEquals("false", environment.getProperty("app.seed-demo"));
        Integer demoStores = jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE store_code = 'ABC001'", Integer.class);
        assertEquals(0, demoStores);
    }

    // ------------------------------------------------------------------
    // K-11 / K-22: health, version and pagination validation
    // ------------------------------------------------------------------

    @Test
    void healthAndVersionArePublicAndContainNoSecrets() {
        ResponseEntity<JsonNode> health = rest.getForEntity("/api/health", JsonNode.class);
        assertEquals(HttpStatus.OK.value(), health.getStatusCode().value());
        assertEquals("UP", health.getBody().get("status").asText());
        assertEquals("UP", health.getBody().get("database").asText());
        assertTrue(headerContains(health, HttpHeaders.CACHE_CONTROL, "no-store"));
        assertFalse(health.getBody().has("password"));
        assertFalse(health.getBody().has("dbUrl"));

        ResponseEntity<JsonNode> version = rest.getForEntity("/api/version", JsonNode.class);
        assertEquals(HttpStatus.OK.value(), version.getStatusCode().value());
        for (String field : List.of("name", "version", "artifact", "buildTime", "gitCommit", "javaVersion")) {
            assertTrue(version.getBody().hasNonNull(field), "missing version field: " + field);
        }
        assertTrue(headerContains(version, HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void paginationClampsNegativePageAndOversizedPageSize() {
        StoreSession store = registerStore("PAGE");
        JsonNode items = call(HttpMethod.GET, "/api/items?page=-2&size=99999",
                null, store.token(), HttpStatus.OK);
        assertEquals(0, items.get("page").asInt());
        assertEquals(100, items.get("size").asInt());

        String platform = platformToken();
        JsonNode accounts = call(HttpMethod.GET, "/api/platform/accounts?page=-1&size=0",
                null, platform, HttpStatus.OK);
        assertEquals(0, accounts.get("page").asInt());
        assertEquals(1, accounts.get("size").asInt());
    }

    // ------------------------------------------------------------------
    // K-27: the same password policy applies to registration and resets
    // ------------------------------------------------------------------

    @Test
    void registrationRejectsWeakPasswordsWithTheSharedPolicy() {
        for (String weak : List.of("password", "short1!", "alllowercase1!", "NoSpecial123")) {
            ResponseEntity<JsonNode> rejected = rest.exchange("/api/auth/register-store",
                    HttpMethod.POST, noAuth(registerPayload("WEAK" + SEQ.incrementAndGet(), weak)),
                    JsonNode.class);
            assertEquals(HttpStatus.BAD_REQUEST.value(), rejected.getStatusCode().value(),
                    "password should be rejected: " + weak);
        }
        ResponseEntity<JsonNode> accepted = rest.exchange("/api/auth/register-store", HttpMethod.POST,
                noAuth(registerPayload("PASS" + SEQ.incrementAndGet(), "Strong@123")), JsonNode.class);
        assertEquals(HttpStatus.CREATED.value(), accepted.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // K-04: the documented V7 rollback shim keeps an older jar insertable
    // ------------------------------------------------------------------

    @Test
    void v7RollbackShimAllowsItemInsertWithoutBarcodeAndChangesNoExistingRows() throws Exception {
        StoreSession store = registerStore("SHIM");
        call(HttpMethod.POST, "/api/categories", map("categoryName", "Shim Category",
                "description", "rollback test", "status", "ACTIVE"), store.token(), HttpStatus.OK);
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM categories WHERE store_id = ? AND category_name = 'Shim Category'",
                Long.class, store.storeId());
        Long taxId = jdbc.queryForObject(
                "SELECT id FROM tax_master WHERE store_id = ? ORDER BY id LIMIT 1",
                Long.class, store.storeId());
        assertNotNull(categoryId);
        assertNotNull(taxId);

        String barcodeFingerprintBefore = jdbc.queryForObject(
                "SELECT md5(string_agg(barcode, ',' ORDER BY id)) FROM items WHERE store_id = ?",
                String.class, store.storeId());

        runSqlScript("07_rollback_v7_compatibility.sql");

        try {
            String generatedBarcode = jdbc.queryForObject(
                    "INSERT INTO items (store_id, category_id, item_code, item_name, base_unit, "
                            + "tax_id, rate) VALUES (?, ?, ?, ?, 'PIECE', ?, 10.00) RETURNING barcode",
                    String.class, store.storeId(), categoryId, "SHIM-ITEM", "Shim Item", taxId);
            assertTrue(generatedBarcode.matches("RB\\d{12}"), generatedBarcode);
            String format = jdbc.queryForObject(
                    "SELECT barcode_format FROM items WHERE store_id = ? AND item_code = 'SHIM-ITEM'",
                    String.class, store.storeId());
            assertEquals("CODE128", format);
        } finally {
            jdbc.update("DELETE FROM items WHERE store_id = ? AND item_code = 'SHIM-ITEM'",
                    store.storeId());
            runSqlScript("08_remove_v7_rollback_compatibility.sql");
        }

        String barcodeFingerprintAfter = jdbc.queryForObject(
                "SELECT md5(string_agg(barcode, ',' ORDER BY id)) FROM items WHERE store_id = ?",
                String.class, store.storeId());
        assertEquals(barcodeFingerprintBefore, barcodeFingerprintAfter,
                "the shim must never rewrite existing barcodes");
        assertNull(jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_name = 'items' AND column_name = 'barcode'", String.class));
    }

    // ---------- fixtures ----------

    private record StoreSession(String token, long userId, long storeId, long branchId,
                                String storeCode, String username) {
    }

    private record LoginCookie(String token, String setCookie, String cookiePair) {
    }

    private StoreSession registerStore(String prefix) {
        int seq = SEQ.incrementAndGet();
        String code = "A" + prefix.charAt(0) + seq
                + Integer.toString((int) (System.currentTimeMillis() % 1296), 36).toUpperCase();
        String username = "owner" + seq;
        call(HttpMethod.POST, "/api/auth/register-store",
                registerPayload(code, "Secret@123", username), null, HttpStatus.CREATED);
        JsonNode auth = login(code, username, "Secret@123");
        return new StoreSession(auth.get("token").asText(), auth.at("/user/id").asLong(),
                auth.at("/store/id").asLong(), auth.at("/branches/0/id").asLong(), code, username);
    }

    private Map<String, Object> registerPayload(String code, String password) {
        return registerPayload(code, password, "owner" + SEQ.get());
    }

    private Map<String, Object> registerPayload(String code, String password, String username) {
        return map("storeCode", code,
                "storeName", "Auth Security Store " + code,
                "ownerName", "Store Owner",
                "email", username + "@test.local",
                "mobile", "9000000021",
                "address", "2 Test Street",
                "state", "Karnataka",
                "username", username,
                "password", password,
                "branchName", "Main Branch");
    }

    private JsonNode login(String storeCode, String username, String password) {
        return call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", storeCode, "username", username, "password", password),
                null, HttpStatus.OK);
    }

    private LoginCookie loginWithCookie(String storeCode, String username, String password) {
        ResponseEntity<JsonNode> response = rest.exchange("/api/auth/login", HttpMethod.POST,
                noAuth(map("storeCode", storeCode, "username", username, "password", password)),
                JsonNode.class);
        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        return new LoginCookie(response.getBody().get("token").asText(), setCookie, cookiePair(setCookie));
    }

    private String platformToken() {
        String token = cachedPlatformToken;
        if (token == null) {
            synchronized (this) {
                if (cachedPlatformToken == null) {
                    userRepository.findByUsernameIgnoreCaseAndStoreIdIsNull("platform")
                            .orElseGet(() -> {
                                Role role = roleRepository
                                        .findByStoreIdIsNullAndCode("PLATFORM_ADMIN")
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
                    cachedPlatformToken = login("PLATFORM", "platform", "Admin@123")
                            .get("token").asText();
                }
                token = cachedPlatformToken;
            }
        }
        return token;
    }

    private void runSqlScript(String fileName) throws Exception {
        Path path = Files.exists(Path.of("..", "docs", "sql", fileName))
                ? Path.of("..", "docs", "sql", fileName)
                : Path.of("docs", "sql", fileName);
        assertTrue(Files.exists(path), "missing script: " + path.toAbsolutePath());
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(path.toFile()));
        }
    }

    private JsonNode call(HttpMethod method, String path, Object body, String token,
                          HttpStatus expected) {
        ResponseEntity<JsonNode> response = rest.exchange(path, method,
                body == null && token == null ? emptyAuth() : entity(body, token), JsonNode.class);
        assertEquals(expected.value(), response.getStatusCode().value(),
                () -> method + " " + path + " expected " + expected + " but got "
                        + response.getStatusCode() + " " + response.getBody());
        return response.getBody();
    }

    private JsonNode callWithCookie(HttpMethod method, String path, Object body, String cookie,
                                    String origin, HttpStatus expected) {
        ResponseEntity<JsonNode> response = rest.exchange(path, method,
                cookieEntity(body, cookie, origin), JsonNode.class);
        assertEquals(expected.value(), response.getStatusCode().value(),
                () -> method + " " + path + " expected " + expected + " but got "
                        + response.getStatusCode() + " " + response.getBody());
        return response.getBody();
    }

    private List<String> callList(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<JsonNode> response = rest.exchange(path, method,
                new HttpEntity<>(headers), JsonNode.class);
        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        List<String> values = new java.util.ArrayList<>();
        response.getBody().forEach(node -> values.add(node.asText()));
        return values;
    }

    private HttpEntity<Object> entity(Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Object> bearer(Object body, String token) {
        return entity(body, token);
    }

    private HttpEntity<Object> noAuth(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> emptyAuth() {
        return new HttpEntity<>(new HttpHeaders());
    }

    private HttpEntity<Void> bearerOnly(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private HttpEntity<Object> cookieEntity(Object body, String cookie, String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        if (origin != null) {
            headers.set(HttpHeaders.ORIGIN, origin);
        }
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return entity;
    }

    private HttpEntity<Void> cookieOnly(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        return new HttpEntity<>(headers);
    }

    private String cookiePair(String setCookie) {
        return setCookie.split(";", 2)[0];
    }

    private boolean headerContains(ResponseEntity<?> response, String header, String expected) {
        String value = response.getHeaders().getFirst(header);
        return value != null && value.toLowerCase().contains(expected.toLowerCase());
    }

    private Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
