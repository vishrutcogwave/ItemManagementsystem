package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A token carries the branch scope, so changing a user's role or branches must
 * invalidate it immediately. Profile-only edits must not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54339",
        "app.embedded-postgres.data-dir=build/pgdata-branch-scope-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "logging.level.com.itemmanagement=WARN"
})
class BranchScopeTokenInvalidationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StoreProvisioningService provisioningService;

    @Test
    void removingBranchThroughUserUpdateInvalidatesExistingToken() {
        Tenant tenant = registerStore("REMOVE");
        Session staff = createUserSession(tenant, "STORE_ADMIN", List.of(tenant.branchId()));
        call(HttpMethod.GET, "/api/auth/me", null, staff.token(), HttpStatus.OK);
        long versionBefore = tokenVersion(staff.userId());

        call(HttpMethod.PUT, "/api/users/" + staff.userId(), updateBody(tenant, staff,
                "STORE_ADMIN", List.of()), tenant.token(), HttpStatus.OK);

        assertEquals(versionBefore + 1, tokenVersion(staff.userId()));
        expectUnauthorized(staff.token());
    }

    @Test
    void assigningAnotherBranchThroughBranchEndpointInvalidatesExistingToken() {
        Tenant tenant = registerStore("ASSIGN");
        Session staff = createUserSession(tenant, "BRANCH_STAFF", List.of(tenant.branchId()));
        long secondBranchId = createBranch(tenant);
        call(HttpMethod.GET, "/api/auth/me", null, staff.token(), HttpStatus.OK);
        long versionBefore = tokenVersion(staff.userId());

        call(HttpMethod.POST, "/api/users/" + staff.userId() + "/branches",
                map("branchIds", List.of(secondBranchId)), tenant.token(), HttpStatus.OK);

        assertEquals(versionBefore + 1, tokenVersion(staff.userId()));
        expectUnauthorized(staff.token());

        // A fresh login carries the new scope and works.
        Session fresh = loginSession(tenant.storeCode(), staff.username(), "Secret@123");
        call(HttpMethod.GET, "/api/auth/me", null, fresh.token(), HttpStatus.OK);
    }

    @Test
    void changingRoleInvalidatesExistingToken() {
        Tenant tenant = registerStore("ROLE");
        Session staff = createUserSession(tenant, "BRANCH_STAFF", List.of(tenant.branchId()));
        call(HttpMethod.GET, "/api/auth/me", null, staff.token(), HttpStatus.OK);
        long versionBefore = tokenVersion(staff.userId());

        call(HttpMethod.PUT, "/api/users/" + staff.userId(), updateBody(tenant, staff,
                "BRANCH_MANAGER", List.of(tenant.branchId())), tenant.token(), HttpStatus.OK);

        assertEquals(versionBefore + 1, tokenVersion(staff.userId()));
        expectUnauthorized(staff.token());
    }

    @Test
    void profileOnlyUpdateKeepsExistingTokenValid() {
        Tenant tenant = registerStore("PROFILE");
        Session staff = createUserSession(tenant, "BRANCH_STAFF", List.of(tenant.branchId()));
        long versionBefore = tokenVersion(staff.userId());

        Map<String, Object> body = updateBody(tenant, staff, "BRANCH_STAFF", List.of(tenant.branchId()));
        body.put("fullName", "Renamed Staff");
        body.put("mobile", "9000000021");
        call(HttpMethod.PUT, "/api/users/" + staff.userId(), body, tenant.token(), HttpStatus.OK);

        assertEquals(versionBefore, tokenVersion(staff.userId()),
                "an unrelated profile edit must not bump the token version");
        call(HttpMethod.GET, "/api/auth/me", null, staff.token(), HttpStatus.OK);
    }

    @Test
    void reassigningTheSameBranchesKeepsExistingTokenValid() {
        Tenant tenant = registerStore("SAME");
        Session staff = createUserSession(tenant, "BRANCH_STAFF", List.of(tenant.branchId()));
        long versionBefore = tokenVersion(staff.userId());

        call(HttpMethod.POST, "/api/users/" + staff.userId() + "/branches",
                map("branchIds", List.of(tenant.branchId())), tenant.token(), HttpStatus.OK);

        assertEquals(versionBefore, tokenVersion(staff.userId()),
                "re-saving the same branch set must not invalidate the session");
        call(HttpMethod.GET, "/api/auth/me", null, staff.token(), HttpStatus.OK);
    }

    @Test
    void adminPasswordResetStillInvalidatesTokenAndForcesChange() {
        Tenant tenant = registerStore("PASS");
        Session staff = createUserSession(tenant, "BRANCH_STAFF", List.of(tenant.branchId()));
        long versionBefore = tokenVersion(staff.userId());

        Map<String, Object> body = updateBody(tenant, staff, "BRANCH_STAFF", List.of(tenant.branchId()));
        body.put("password", "NewSecret@123");
        call(HttpMethod.PUT, "/api/users/" + staff.userId(), body, tenant.token(), HttpStatus.OK);

        assertEquals(versionBefore + 1, tokenVersion(staff.userId()),
                "a password reset must still bump the token version exactly once");
        expectUnauthorized(staff.token());

        JsonNode login = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", tenant.storeCode(), "username", staff.username(),
                        "password", "NewSecret@123"), null, HttpStatus.OK);
        assertEquals(true, login.get("mustChangePassword").asBoolean());
        ResponseEntity<JsonNode> blocked = rest.exchange("/api/items", HttpMethod.GET,
                bearerOnly(login.get("token").asText()), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), blocked.getStatusCode().value());
        assertEquals("PASSWORD_CHANGE_REQUIRED", blocked.getBody().get("code").asText());
    }

    @Test
    void deactivatingBranchInvalidatesItsScopedSessionsForReadReportAndPrint() {
        Tenant tenant = registerStore("DEACT");
        Session staff = createUserSession(tenant, "BRANCH_STAFF", List.of(tenant.branchId()));
        Session manager = createUserSession(tenant, "BRANCH_MANAGER", List.of(tenant.branchId()));
        long staffVersionBefore = tokenVersion(staff.userId());
        long managerVersionBefore = tokenVersion(manager.userId());

        call(HttpMethod.GET, inventoryPath(tenant), null, staff.token(), HttpStatus.OK);
        call(HttpMethod.GET, reportPath(tenant), null, manager.token(), HttpStatus.OK);
        call(HttpMethod.GET, printPath(tenant), null, staff.token(), HttpStatus.OK);

        call(HttpMethod.PUT, "/api/branches/" + tenant.branchId() + "/status",
                map("status", "INACTIVE"), tenant.token(), HttpStatus.OK);

        assertEquals(staffVersionBefore + 1, tokenVersion(staff.userId()));
        assertEquals(managerVersionBefore + 1, tokenVersion(manager.userId()));

        expectUnauthorized(HttpMethod.GET, inventoryPath(tenant), staff.token());
        expectUnauthorized(HttpMethod.GET, reportPath(tenant), manager.token());
        expectUnauthorized(HttpMethod.GET, printPath(tenant), staff.token());

        // Owners reach every branch through allBranches, so their session is not
        // invalidated and they can still reactivate the branch.
        call(HttpMethod.GET, "/api/auth/me", null, tenant.token(), HttpStatus.OK);
    }

    @Test
    void deactivatingThroughBranchUpdateAlsoInvalidatesSessions() {
        Tenant tenant = registerStore("UPDD");
        Session staff = createUserSession(tenant, "BRANCH_STAFF", List.of(tenant.branchId()));
        long versionBefore = tokenVersion(staff.userId());

        call(HttpMethod.PUT, "/api/branches/" + tenant.branchId(),
                map("branchCode", "MAIN",
                        "branchName", "Main Branch",
                        "address", "1 Test Street",
                        "contactNumber", "9000000007",
                        "state", "Karnataka",
                        "status", "INACTIVE"),
                tenant.token(), HttpStatus.OK);

        assertEquals(versionBefore + 1, tokenVersion(staff.userId()));
        expectUnauthorized(HttpMethod.GET, inventoryPath(tenant), staff.token());
    }

    @Test
    void deactivatingOneBranchLeavesUsersOfOtherBranchesUntouched() {
        Tenant tenant = registerStore("OTHER");
        long secondBranchId = createBranch(tenant);
        Session secondStaff = createUserSession(tenant, "BRANCH_STAFF", List.of(secondBranchId));
        long versionBefore = tokenVersion(secondStaff.userId());

        call(HttpMethod.PUT, "/api/branches/" + tenant.branchId() + "/status",
                map("status", "INACTIVE"), tenant.token(), HttpStatus.OK);

        assertEquals(versionBefore, tokenVersion(secondStaff.userId()),
                "a session scoped to another branch must not be invalidated");
        call(HttpMethod.GET, "/api/inventory?branchId=" + secondBranchId, null,
                secondStaff.token(), HttpStatus.OK);
    }

    @Test
    void reactivatingBranchDoesNotRestoreTheOldToken() {
        Tenant tenant = registerStore("REACT");
        Session staff = createUserSession(tenant, "BRANCH_STAFF", List.of(tenant.branchId()));
        long versionAfterLogin = tokenVersion(staff.userId());

        call(HttpMethod.PUT, "/api/branches/" + tenant.branchId() + "/status",
                map("status", "INACTIVE"), tenant.token(), HttpStatus.OK);
        assertEquals(versionAfterLogin + 1, tokenVersion(staff.userId()));

        call(HttpMethod.PUT, "/api/branches/" + tenant.branchId() + "/status",
                map("status", "ACTIVE"), tenant.token(), HttpStatus.OK);

        assertEquals(versionAfterLogin + 1, tokenVersion(staff.userId()),
                "reactivation must not roll the token version back");
        expectUnauthorized(HttpMethod.GET, inventoryPath(tenant), staff.token());

        Session fresh = loginSession(tenant.storeCode(), staff.username(), "Secret@123");
        call(HttpMethod.GET, inventoryPath(tenant), null, fresh.token(), HttpStatus.OK);
    }

    private String inventoryPath(Tenant tenant) {
        return "/api/inventory?branchId=" + tenant.branchId();
    }

    private String reportPath(Tenant tenant) {
        return "/api/reports/stock?branchId=" + tenant.branchId();
    }

    private String printPath(Tenant tenant) {
        return "/api/printing/jobs?branchId=" + tenant.branchId();
    }

    // ---------- fixtures ----------

    private record Tenant(String storeCode, String token, long branchId) {
    }

    private record Session(String token, long userId, long branchId, String storeCode,
                           String username, String email) {
    }

    private Tenant registerStore(String prefix) {
        int seq = SEQ.incrementAndGet();
        String code = "S" + prefix.charAt(0) + seq
                + Integer.toString((int) (System.currentTimeMillis() % 1296), 36).toUpperCase();
        String username = "owner" + seq;
        provisioningService.createStore(new RegisterStoreRequest(code,
                "Branch Scope Store " + code, "Store Owner", "owner" + seq + "@test.local",
                "9000000007", "1 Test Street", null, "Karnataka", username, "Secret@123",
                "Main Branch"), false);
        Session owner = loginSession(code, username, "Secret@123");
        return new Tenant(code, owner.token(), owner.branchId());
    }

    private Session loginSession(String storeCode, String username, String password) {
        JsonNode auth = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", storeCode, "username", username, "password", password),
                null, HttpStatus.OK);
        return new Session(auth.get("token").asText(), auth.at("/user/id").asLong(),
                auth.at("/branches/0/id").asLong(), storeCode, username, username + "@test.local");
    }

    private Session createUserSession(Tenant tenant, String roleCode, List<Long> branchIds) {
        int seq = SEQ.incrementAndGet();
        String username = "user" + seq;
        String email = username + "@test.local";
        call(HttpMethod.POST, "/api/users",
                map("username", username,
                        "email", email,
                        "password", "Secret@123",
                        "fullName", "Scope " + username,
                        "mobile", "9000000013",
                        "roleId", roleId(tenant, roleCode),
                        "branchIds", branchIds),
                tenant.token(), HttpStatus.OK);
        Session created = loginSession(tenant.storeCode(), username, "Secret@123");
        assertEquals(email, created.email());
        return created;
    }

    private Map<String, Object> updateBody(Tenant tenant, Session staff, String roleCode,
                                           List<Long> branchIds) {
        return map("fullName", "Scope " + staff.username(),
                "email", staff.email(),
                "mobile", "9000000013",
                "roleId", roleId(tenant, roleCode),
                "branchIds", branchIds,
                "status", "ACTIVE");
    }

    private long createBranch(Tenant tenant) {
        int seq = SEQ.incrementAndGet();
        String code = "B2" + seq;
        JsonNode branch = call(HttpMethod.POST, "/api/branches",
                map("branchCode", code,
                        "branchName", "Second Branch " + code,
                        "address", "2 Test Street",
                        "contactNumber", "9000000015",
                        "state", "Karnataka",
                        "status", "ACTIVE"),
                tenant.token(), HttpStatus.OK);
        return branch.get("id").asLong();
    }

    private long roleId(Tenant tenant, String roleCode) {
        JsonNode roles = call(HttpMethod.GET, "/api/users/roles/available", null,
                tenant.token(), HttpStatus.OK);
        for (JsonNode role : roles) {
            if (roleCode.equals(role.get("code").asText())) {
                return role.get("id").asLong();
            }
        }
        throw new IllegalStateException("Role not found: " + roleCode);
    }

    private long tokenVersion(long userId) {
        Long value = jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?",
                Long.class, userId);
        return value == null ? -1 : value;
    }

    private void expectUnauthorized(String token) {
        expectUnauthorized(HttpMethod.GET, "/api/auth/me", token);
    }

    private void expectUnauthorized(HttpMethod method, String path, String token) {
        ResponseEntity<JsonNode> response = rest.exchange(path, method, bearerOnly(token),
                JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatusCode().value(),
                () -> method + " " + path
                        + " must reject a token whose branch scope was deactivated");
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
