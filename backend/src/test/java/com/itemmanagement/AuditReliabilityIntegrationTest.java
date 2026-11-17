package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.UserRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sensitive operations must commit together with their audit record. A database
 * trigger forces every audit insert to fail; each operation must then roll back
 * instead of reporting success.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54340",
        "app.embedded-postgres.data-dir=build/pgdata-audit-reliability-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "logging.level.com.itemmanagement=WARN"
})
class AuditReliabilityIntegrationTest {

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

    @Autowired
    private StoreProvisioningService provisioningService;

    private volatile String cachedPlatformToken;

    @Test
    void passwordResetRollsBackWhenAuditCannotBePersisted() {
        Session store = registerStore("RST");
        String platform = platformToken();
        String hashBefore = passwordHash(store.userId());
        long versionBefore = tokenVersion(store.userId());

        withAuditFailure(() -> expect(HttpMethod.POST,
                "/api/platform/accounts/" + store.userId() + "/reset-password",
                map("confirm", true), platform, HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(hashBefore, passwordHash(store.userId()),
                "the password hash must not change when its audit record fails");
        assertEquals(versionBefore, tokenVersion(store.userId()));
        assertEquals(false, mustChangePassword(store.userId()));
        assertEquals(0, auditCount("PASSWORD_RESET", "USER", store.userId()));

        call(HttpMethod.POST, "/api/platform/accounts/" + store.userId() + "/reset-password",
                map("confirm", true), platform, HttpStatus.OK);
        assertNotEquals(hashBefore, passwordHash(store.userId()));
        assertEquals(1, auditCount("PASSWORD_RESET", "USER", store.userId()),
                "a successful reset must be audited");
    }

    @Test
    void usernameAndStatusChangesRollBackWhenAuditCannotBePersisted() {
        Session store = registerStore("USR");
        String platform = platformToken();
        String newUsername = "renamed" + SEQ.incrementAndGet();

        withAuditFailure(() -> {
            expect(HttpMethod.PUT, "/api/platform/accounts/" + store.userId() + "/username",
                    map("username", newUsername), platform, HttpStatus.INTERNAL_SERVER_ERROR);
            expect(HttpMethod.PUT, "/api/platform/accounts/" + store.userId() + "/status",
                    map("status", "INACTIVE"), platform, HttpStatus.INTERNAL_SERVER_ERROR);
        });

        assertEquals(store.username(), username(store.userId()));
        assertEquals("ACTIVE", accountStatus(store.userId()));
        assertEquals(0, auditCount("USERNAME_UPDATE", "USER", store.userId()));
        assertEquals(0, auditCount("DEACTIVATE", "USER", store.userId()));

        call(HttpMethod.PUT, "/api/platform/accounts/" + store.userId() + "/username",
                map("username", newUsername), platform, HttpStatus.OK);
        assertEquals(newUsername, username(store.userId()));
        assertEquals(1, auditCount("USERNAME_UPDATE", "USER", store.userId()));

        call(HttpMethod.PUT, "/api/platform/accounts/" + store.userId() + "/status",
                map("status", "INACTIVE"), platform, HttpStatus.OK);
        assertEquals("INACTIVE", accountStatus(store.userId()));
        assertEquals(1, auditCount("DEACTIVATE", "USER", store.userId()));
    }

    @Test
    void userScopeChangesRollBackWithTheirAuditRecord() {
        Session store = registerStore("UPD");
        Session staff = createUserSession(store, "BRANCH_STAFF", List.of(store.branchId()));
        long versionBefore = tokenVersion(staff.userId());
        Map<String, Object> body = updateBody(store, staff, "BRANCH_MANAGER",
                List.of(store.branchId()));

        withAuditFailure(() -> expect(HttpMethod.PUT, "/api/users/" + staff.userId(), body,
                store.token(), HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals("BRANCH_STAFF", roleCode(store, staff.userId()),
                "the role must not change when the audit record fails");
        assertEquals(versionBefore, tokenVersion(staff.userId()),
                "token invalidation must roll back with the failed audit");
        assertEquals(0, auditCount("UPDATE", "USER", staff.userId()));
        call(HttpMethod.GET, "/api/auth/me", null, staff.token(), HttpStatus.OK);

        call(HttpMethod.PUT, "/api/users/" + staff.userId(), body, store.token(), HttpStatus.OK);
        assertEquals("BRANCH_MANAGER", roleCode(store, staff.userId()));
        assertEquals(versionBefore + 1, tokenVersion(staff.userId()));
        assertEquals(1, auditCount("UPDATE", "USER", staff.userId()));
        expectUnauthorized(staff.token());
    }

    @Test
    void roleCreatesAndPermissionUpdatesRollBackWithoutAudit() {
        Session store = registerStore("ROLE");
        long branchStaffRoleId = roleId(store, "BRANCH_STAFF");
        String roleNameBefore = roleName(store, "BRANCH_STAFF");
        String permission = permissionCode(store);
        String newCode = "AUDIT_ROLE_" + SEQ.incrementAndGet();
        Map<String, Object> createRequest = map("code", newCode, "name", "Audited Role",
                "description", null, "permissionCodes", List.of(permission));
        Map<String, Object> updateRequest = map("code", "BRANCH_STAFF", "name", "Renamed Role",
                "description", null, "permissionCodes", List.of(permission));

        withAuditFailure(() -> {
            expect(HttpMethod.POST, "/api/roles", createRequest, store.token(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            expect(HttpMethod.PUT, "/api/roles/" + branchStaffRoleId, updateRequest,
                    store.token(), HttpStatus.INTERNAL_SERVER_ERROR);
        });

        assertEquals(0, roleCount(store.storeId(), newCode), "the new role must not be created");
        assertEquals(roleNameBefore, roleName(store, "BRANCH_STAFF"),
                "the role must not be renamed when the audit record fails");
        assertEquals(0, auditCountForStore("CREATE", "ROLE", store.storeId()));
        assertEquals(0, auditCount("UPDATE", "ROLE", branchStaffRoleId));

        call(HttpMethod.PUT, "/api/roles/" + branchStaffRoleId, updateRequest, store.token(),
                HttpStatus.OK);
        assertEquals("Renamed Role", roleName(store, "BRANCH_STAFF"));
        assertEquals(1, auditCount("UPDATE", "ROLE", branchStaffRoleId));
    }

    @Test
    void pairingAndRevocationRollBackWithTheirAuditRecord() {
        Session store = registerStore("DEV");
        String pairingPath = "/api/printing/printers/pairing-code?branchId=" + store.branchId();

        withAuditFailure(() -> expect(HttpMethod.POST, pairingPath, null, store.token(),
                HttpStatus.INTERNAL_SERVER_ERROR));
        assertEquals(0, pairingCodeCount(store.storeId()),
                "a pairing code must not exist without its audit record");

        String code = call(HttpMethod.POST, pairingPath, null, store.token(), HttpStatus.OK)
                .get("code").asText();

        withAuditFailure(() -> expect(HttpMethod.POST, "/api/printing/devices/pair",
                map("code", code, "deviceName", "Audit Bridge", "platform", "WINDOWS"),
                null, HttpStatus.INTERNAL_SERVER_ERROR));
        assertEquals(0, deviceCount(store.storeId()), "the device must not be paired");
        assertEquals(0, usedPairingCodes(store.storeId()),
                "the pairing code must stay unused after a rolled-back pair");

        JsonNode paired = call(HttpMethod.POST, "/api/printing/devices/pair",
                map("code", code, "deviceName", "Audit Bridge", "platform", "WINDOWS"),
                null, HttpStatus.OK);
        long deviceId = paired.get("deviceId").asLong();
        assertEquals("ACTIVE", deviceStatus(deviceId));
        assertEquals(1, auditCount("PAIR", "PRINT_DEVICE", deviceId));

        withAuditFailure(() -> expect(HttpMethod.DELETE,
                "/api/printing/printers/devices/" + deviceId, null, store.token(),
                HttpStatus.INTERNAL_SERVER_ERROR));
        assertEquals("ACTIVE", deviceStatus(deviceId), "a failed revoke must leave the device active");
        assertEquals(0, auditCount("REVOKE", "PRINT_DEVICE", deviceId));

        call(HttpMethod.DELETE, "/api/printing/printers/devices/" + deviceId, null,
                store.token(), HttpStatus.OK);
        assertEquals("REVOKED", deviceStatus(deviceId));
        assertEquals(1, auditCount("REVOKE", "PRINT_DEVICE", deviceId));
    }

    @Test
    void userCreationRollsBackWhenAuditCannotBePersisted() {
        Session store = registerStore("UC");
        String username = "newuser" + SEQ.incrementAndGet();
        Map<String, Object> body = map("username", username,
                "email", username + "@test.local",
                "password", "Secret@123",
                "fullName", "New Audited User",
                "mobile", "9000000014",
                "roleId", roleId(store, "BRANCH_STAFF"),
                "branchIds", List.of(store.branchId()));

        withAuditFailure(() -> expect(HttpMethod.POST, "/api/users", body, store.token(),
                HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(0, userCount(store.storeId(), username),
                "the account must not exist without its audit record");
        assertEquals(0, auditCountForStore("CREATE", "USER", store.storeId()));

        JsonNode created = call(HttpMethod.POST, "/api/users", body, store.token(), HttpStatus.OK);
        assertEquals(1, userCount(store.storeId(), username));
        assertEquals(1, auditCount("CREATE", "USER", created.get("id").asLong()));
    }

    @Test
    void storeCreationRollsBackWhenAuditCannotBePersisted() {
        String platform = platformToken();
        String code = "AUC" + SEQ.incrementAndGet();
        String username = "auditowner" + SEQ.incrementAndGet();
        Map<String, Object> body = map("storeCode", code,
                "storeName", "Audit Created Store",
                "ownerName", "Store Owner",
                "email", username + "@test.local",
                "mobile", "9000000008",
                "address", "1 Test Street",
                "gstNumber", null,
                "state", "Karnataka",
                "username", username,
                "password", "Secret@123",
                "branchName", "Main Branch");

        withAuditFailure(() -> expect(HttpMethod.POST, "/api/stores", body, platform,
                HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(0, storeCount(code), "the store must not exist without its audit record");
        assertEquals(0, userCountByUsername(username));

        JsonNode created = call(HttpMethod.POST, "/api/stores", body, platform, HttpStatus.OK);
        assertEquals(1, storeCount(code));
        assertEquals(1, auditCount("CREATE", "STORE", created.get("id").asLong()));
    }

    @Test
    void storeAdministrationChangesRollBackWithoutAudit() {
        Session store = registerStore("SA");
        String platform = platformToken();
        String originalName = storeName(store.storeId());
        Map<String, Object> profileUpdate = storeUpdateBody("Renamed Store",
                "renamed" + SEQ.incrementAndGet() + "@test.local");
        Map<String, Object> selfUpdate = storeUpdateBody("Self Renamed",
                "self" + SEQ.incrementAndGet() + "@test.local");

        withAuditFailure(() -> {
            expect(HttpMethod.PUT, "/api/stores/" + store.storeId(), profileUpdate, platform,
                    HttpStatus.INTERNAL_SERVER_ERROR);
            expect(HttpMethod.PUT, "/api/stores/" + store.storeId() + "/status",
                    map("status", "INACTIVE"), platform, HttpStatus.INTERNAL_SERVER_ERROR);
            expect(HttpMethod.PUT, "/api/stores/current", selfUpdate, store.token(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        });

        assertEquals(originalName, storeName(store.storeId()),
                "a failed audit must leave the store profile unchanged");
        assertEquals("ACTIVE", storeStatus(store.storeId()));
        assertEquals(0, auditCount("UPDATE", "STORE", store.storeId()));
        assertEquals(0, auditCount("DEACTIVATE", "STORE", store.storeId()));

        call(HttpMethod.PUT, "/api/stores/" + store.storeId(), profileUpdate, platform,
                HttpStatus.OK);
        assertEquals("Renamed Store", storeName(store.storeId()));
        assertEquals(1, auditCount("UPDATE", "STORE", store.storeId()));

        call(HttpMethod.PUT, "/api/stores/" + store.storeId() + "/status",
                map("status", "INACTIVE"), platform, HttpStatus.OK);
        assertEquals("INACTIVE", storeStatus(store.storeId()));
        assertEquals(1, auditCount("DEACTIVATE", "STORE", store.storeId()));
    }

    @Test
    void subscriptionChangesRollBackWithoutAudit() {
        Session store = registerStore("SUB");
        String platform = platformToken();
        JsonNode before = call(HttpMethod.GET, "/api/stores/" + store.storeId() + "/subscription",
                null, platform, HttpStatus.OK);
        Map<String, Object> body = map("startDate", "2026-02-01",
                "endDate", "2027-02-01", "maxBranches", 9, "note", "audit rollback test");

        withAuditFailure(() -> expect(HttpMethod.PUT,
                "/api/stores/" + store.storeId() + "/subscription", body, platform,
                HttpStatus.INTERNAL_SERVER_ERROR));

        JsonNode afterFailure = call(HttpMethod.GET,
                "/api/stores/" + store.storeId() + "/subscription", null, platform, HttpStatus.OK);
        assertEquals(before.get("startDate").asText(), afterFailure.get("startDate").asText());
        assertEquals(before.get("endDate").asText(), afterFailure.get("endDate").asText());
        assertEquals(before.get("maxBranches").asInt(), afterFailure.get("maxBranches").asInt());
        assertEquals(0, subscriptionHistoryCount(store.storeId()));
        assertEquals(0, auditCount("UPDATE_SUBSCRIPTION", "STORE", store.storeId()));

        call(HttpMethod.PUT, "/api/stores/" + store.storeId() + "/subscription", body, platform,
                HttpStatus.OK);
        assertEquals(1, subscriptionHistoryCount(store.storeId()));
        assertEquals(1, auditCount("UPDATE_SUBSCRIPTION", "STORE", store.storeId()));
    }

    @Test
    void platformContactChangesRollBackWithoutAudit() {
        String platform = platformToken();
        JsonNode before = call(HttpMethod.GET, "/api/platform/contact", null, platform,
                HttpStatus.OK);
        Map<String, Object> body = map("contactEmail",
                "support" + SEQ.incrementAndGet() + "@test.local",
                "contactPhone", "9000000099");

        withAuditFailure(() -> expect(HttpMethod.PUT, "/api/platform/contact", body, platform,
                HttpStatus.INTERNAL_SERVER_ERROR));

        JsonNode afterFailure = call(HttpMethod.GET, "/api/platform/contact", null, platform,
                HttpStatus.OK);
        assertEquals(before.get("contactEmail").asText(), afterFailure.get("contactEmail").asText());
        assertEquals(before.get("contactPhone").asText(), afterFailure.get("contactPhone").asText());
        assertEquals(0, auditCount("UPDATE", "PLATFORM_SETTINGS", 1L));

        call(HttpMethod.PUT, "/api/platform/contact", body, platform, HttpStatus.OK);
        assertEquals(1, auditCount("UPDATE", "PLATFORM_SETTINGS", 1L));
    }

    @Test
    void branchDeactivationRollsBackWithItsAuditRecord() {
        Session store = registerStore("BR");
        Session staff = createUserSession(store, "BRANCH_STAFF", List.of(store.branchId()));
        long versionBefore = tokenVersion(staff.userId());

        withAuditFailure(() -> expect(HttpMethod.PUT,
                "/api/branches/" + store.branchId() + "/status",
                map("status", "INACTIVE"), store.token(), HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals("ACTIVE", branchStatus(store.branchId()),
                "a failed audit must leave the branch active");
        assertEquals(versionBefore, tokenVersion(staff.userId()),
                "session invalidation must roll back with the failed audit");
        assertEquals(0, auditCount("DEACTIVATE", "BRANCH", store.branchId()));
        call(HttpMethod.GET, "/api/inventory?branchId=" + store.branchId(), null,
                staff.token(), HttpStatus.OK);

        call(HttpMethod.PUT, "/api/branches/" + store.branchId() + "/status",
                map("status", "INACTIVE"), store.token(), HttpStatus.OK);
        assertEquals("INACTIVE", branchStatus(store.branchId()));
        assertEquals(versionBefore + 1, tokenVersion(staff.userId()));
        assertEquals(1, auditCount("DEACTIVATE", "BRANCH", store.branchId()));
    }

    // ---------- forced audit failure ----------

    private interface Block {
        void run();
    }

    private void withAuditFailure(Block block) {
        jdbc.execute("CREATE FUNCTION fail_required_audit_insert() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced audit failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_required_audit_insert BEFORE INSERT ON audit_logs "
                + "FOR EACH ROW EXECUTE FUNCTION fail_required_audit_insert()");
        try {
            block.run();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_required_audit_insert ON audit_logs");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_required_audit_insert()");
        }
    }

    // ---------- fixtures ----------

    private record Session(String token, long userId, long storeId, long branchId,
                           String storeCode, String username, String email) {
    }

    private Session registerStore(String prefix) {
        int seq = SEQ.incrementAndGet();
        String code = "A" + prefix.charAt(0) + seq;
        String username = "owner" + seq;
        String email = "owner" + seq + "@test.local";
        provisioningService.createStore(new RegisterStoreRequest(code,
                "Audit Reliability Store " + code, "Store Owner", email,
                "9000000007", "1 Test Street", null, "Karnataka", username, "Secret@123",
                "Main Branch"), false);
        JsonNode auth = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", code, "username", username, "password", "Secret@123"),
                null, HttpStatus.OK);
        return new Session(auth.get("token").asText(), auth.at("/user/id").asLong(),
                auth.at("/store/id").asLong(), auth.at("/branches/0/id").asLong(),
                code, username, email);
    }

    private Session createUserSession(Session store, String roleCode, List<Long> branchIds) {
        int seq = SEQ.incrementAndGet();
        String username = "user" + seq;
        String email = username + "@test.local";
        call(HttpMethod.POST, "/api/users",
                map("username", username,
                        "email", email,
                        "password", "Secret@123",
                        "fullName", "Audit User",
                        "mobile", "9000000013",
                        "roleId", roleId(store, roleCode),
                        "branchIds", branchIds),
                store.token(), HttpStatus.OK);
        JsonNode auth = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", store.storeCode(), "username", username,
                        "password", "Secret@123"), null, HttpStatus.OK);
        return new Session(auth.get("token").asText(), auth.at("/user/id").asLong(),
                store.storeId(), store.branchId(), store.storeCode(), username, email);
    }

    private Map<String, Object> updateBody(Session store, Session staff, String roleCode,
                                           List<Long> branchIds) {
        return map("fullName", "Audit User",
                "email", staff.email(),
                "mobile", "9000000013",
                "roleId", roleId(store, roleCode),
                "branchIds", branchIds,
                "status", "ACTIVE");
    }

    private long roleId(Session store, String roleCode) {
        JsonNode roles = call(HttpMethod.GET, "/api/users/roles/available", null,
                store.token(), HttpStatus.OK);
        for (JsonNode role : roles) {
            if (roleCode.equals(role.get("code").asText())) {
                return role.get("id").asLong();
            }
        }
        throw new IllegalStateException("Role not found: " + roleCode);
    }

    private String roleCode(Session store, long userId) {
        return call(HttpMethod.GET, "/api/users/" + userId, null, store.token(), HttpStatus.OK)
                .at("/roles/0/code").asText();
    }

    private String roleName(Session store, String roleCode) {
        JsonNode roles = call(HttpMethod.GET, "/api/roles", null, store.token(), HttpStatus.OK);
        for (JsonNode role : roles) {
            if (roleCode.equals(role.get("code").asText())) {
                return role.get("name").asText();
            }
        }
        throw new IllegalStateException("Role not found: " + roleCode);
    }

    private String permissionCode(Session store) {
        JsonNode permissions = call(HttpMethod.GET, "/api/permissions", null, store.token(),
                HttpStatus.OK);
        assertTrue(permissions.size() > 0, "a store must expose at least one permission");
        return permissions.get(0).get("code").asText();
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

    // ---------- state checks ----------

    private String passwordHash(long userId) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?",
                String.class, userId);
    }

    private long tokenVersion(long userId) {
        Long value = jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?",
                Long.class, userId);
        return value == null ? -1 : value;
    }

    private boolean mustChangePassword(long userId) {
        Boolean value = jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?",
                Boolean.class, userId);
        return Boolean.TRUE.equals(value);
    }

    private String username(long userId) {
        return jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, userId);
    }

    private String accountStatus(long userId) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
    }

    private int roleCount(long storeId, String code) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM roles WHERE store_id = ? AND code = ?",
                Integer.class, storeId, code);
        return count == null ? 0 : count;
    }

    private int userCount(long storeId, String username) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE store_id = ? AND lower(username) = lower(?)",
                Integer.class, storeId, username);
        return count == null ? 0 : count;
    }

    private int userCountByUsername(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE lower(username) = lower(?)",
                Integer.class, username);
        return count == null ? 0 : count;
    }

    private int storeCount(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE lower(store_code) = lower(?)",
                Integer.class, code);
        return count == null ? 0 : count;
    }

    private String storeName(long storeId) {
        return jdbc.queryForObject("SELECT store_name FROM stores WHERE id = ?",
                String.class, storeId);
    }

    private String storeStatus(long storeId) {
        return jdbc.queryForObject("SELECT status FROM stores WHERE id = ?",
                String.class, storeId);
    }

    private String branchStatus(long branchId) {
        return jdbc.queryForObject("SELECT status FROM branches WHERE id = ?",
                String.class, branchId);
    }

    private int subscriptionHistoryCount(long storeId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM subscription_change_history WHERE store_id = ?",
                Integer.class, storeId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> storeUpdateBody(String storeName, String email) {
        return map("storeName", storeName,
                "ownerName", "Store Owner",
                "email", email,
                "mobile", "9000000007",
                "address", "1 Test Street",
                "gstNumber", null,
                "taxInclusive", true);
    }

    private int pairingCodeCount(long storeId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM print_pairing_codes WHERE store_id = ?",
                Integer.class, storeId);
        return count == null ? 0 : count;
    }

    private int usedPairingCodes(long storeId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM print_pairing_codes WHERE store_id = ? AND used_at IS NOT NULL",
                Integer.class, storeId);
        return count == null ? 0 : count;
    }

    private int deviceCount(long storeId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM print_devices WHERE store_id = ?",
                Integer.class, storeId);
        return count == null ? 0 : count;
    }

    private String deviceStatus(long deviceId) {
        return jdbc.queryForObject("SELECT status FROM print_devices WHERE id = ?",
                String.class, deviceId);
    }

    private int auditCount(String action, String entityType, long entityId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = ? AND entity_type = ? AND entity_id = ?",
                Integer.class, action, entityType, entityId);
        return count == null ? 0 : count;
    }

    private int auditCountForStore(String action, String entityType, long storeId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = ? AND entity_type = ? AND store_id = ?",
                Integer.class, action, entityType, storeId);
        return count == null ? 0 : count;
    }

    // ---------- HTTP plumbing ----------

    private JsonNode call(HttpMethod method, String path, Object body, String token,
                          HttpStatus expected) {
        return exchange(method, path, body, token, expected).getBody();
    }

    private void expect(HttpMethod method, String path, Object body, String token,
                        HttpStatus expected) {
        exchange(method, path, body, token, expected);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body,
                                              String token, HttpStatus expected) {
        ResponseEntity<JsonNode> response = rest.exchange(path, method, entity(body, token),
                JsonNode.class);
        assertEquals(expected.value(), response.getStatusCode().value(),
                () -> method + " " + path + " expected " + expected + " but got "
                        + response.getStatusCode() + " " + response.getBody());
        return response;
    }

    private void expectUnauthorized(String token) {
        ResponseEntity<JsonNode> response = rest.exchange("/api/auth/me", HttpMethod.GET,
                bearerOnly(token), JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatusCode().value(),
                "the invalidated token must be rejected");
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
        headers.setBearerAuth(token);
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
