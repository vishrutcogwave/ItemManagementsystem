package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.entity.Permission;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.PermissionRepository;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that platform identity cannot be forged from store-scoped data: reserved
 * PLATFORM_* roles and platform-only permissions are rejected, legacy escalated
 * rows grant nothing, every platform API is guarded by a trusted server-side
 * check, and a genuine platform administrator keeps full access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54337",
        "app.embedded-postgres.data-dir=build/pgdata-platform-escalation-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "app.platform-admin.provision=false",
        "logging.level.com.itemmanagement=WARN"
})
class PlatformEscalationSecurityTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
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
    private PermissionRepository permissionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StoreProvisioningService provisioningService;

    private volatile String cachedPlatformToken;

    @Test
    void storeOwnerCannotCreateReservedPlatformRoles() {
        Session store = provisionStore("RES");

        createRole(store, "PLATFORM_ADMIN", List.of(), HttpStatus.FORBIDDEN);
        createRole(store, "platform_ops", List.of(), HttpStatus.FORBIDDEN);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM roles WHERE store_id = ? AND code LIKE 'PLATFORM%'",
                Integer.class, store.storeId()));
    }

    @Test
    void storeOwnerCannotAssignPlatformPermissionToRoles() {
        Session store = provisionStore("PERM");

        createRole(store, "PLATFORM_GRANT", List.of("PLATFORM_MANAGE"), HttpStatus.FORBIDDEN);

        long roleId = createRole(store, "OPS_CLERK", List.of("ITEM_VIEW"), HttpStatus.OK)
                .get("id").asLong();
        call(HttpMethod.PUT, "/api/roles/" + roleId,
                map("code", "OPS_CLERK", "name", "Ops Clerk",
                        "permissionCodes", List.of("PLATFORM_MANAGE")),
                store.token(), HttpStatus.FORBIDDEN);

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE rp.role_id = ? AND p.code = 'PLATFORM_MANAGE'",
                Integer.class, roleId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE rp.role_id = ? AND p.code = 'ITEM_VIEW'",
                Integer.class, roleId));
    }

    @Test
    void storeOwnerCannotModifyPlatformRoles() {
        Session store = provisionStore("HOLD");
        Role platformRole = roleRepository.findByStoreIdIsNullAndCode("PLATFORM_ADMIN")
                .orElseThrow(() -> new IllegalStateException("PLATFORM_ADMIN role is missing"));
        String originalName = platformRole.getName();
        int originalPermissions = platformRole.getPermissions().size();

        call(HttpMethod.PUT, "/api/roles/" + platformRole.getId(),
                map("code", "PLATFORM_ADMIN", "name", "Hijacked Platform Admin",
                        "permissionCodes", List.of("ITEM_VIEW")),
                store.token(), HttpStatus.NOT_FOUND);

        assertEquals(originalName, jdbc.queryForObject(
                "SELECT name FROM roles WHERE id = ?", String.class, platformRole.getId()));
        assertEquals(originalPermissions, jdbc.queryForObject(
                "SELECT count(*) FROM role_permissions WHERE role_id = ?",
                Integer.class, platformRole.getId()));

        Role legacyRole = roleRepository.save(Role.builder()
                .storeId(store.storeId())
                .code("PLATFORM_LEGACY")
                .name("Legacy Platform Role")
                .isSystem(false)
                .permissions(Set.of())
                .build());
        call(HttpMethod.PUT, "/api/roles/" + legacyRole.getId(),
                map("code", "PLATFORM_LEGACY", "name", "Renamed Legacy Role",
                        "permissionCodes", List.of("ITEM_VIEW")),
                store.token(), HttpStatus.FORBIDDEN);
        assertEquals("Legacy Platform Role", jdbc.queryForObject(
                "SELECT name FROM roles WHERE id = ?", String.class, legacyRole.getId()));
    }

    @Test
    void storeOwnerCannotCreateUsersWithPlatformRoles() {
        Session store = provisionStore("USER");
        Role platformRole = roleRepository.findByStoreIdIsNullAndCode("PLATFORM_ADMIN")
                .orElseThrow(() -> new IllegalStateException("PLATFORM_ADMIN role is missing"));
        String username = "platformclone" + SEQ.incrementAndGet();

        call(HttpMethod.POST, "/api/users",
                map("username", username, "email", username + "@test.local",
                        "password", "Clone@12345", "fullName", "Platform Clone",
                        "roleId", platformRole.getId(), "branchIds", List.of()),
                store.token(), HttpStatus.BAD_REQUEST);

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = ?", Integer.class, username));
    }

    @Test
    void legacyReservedRolesAreHiddenFromStoreRoleLists() {
        Session store = provisionStore("HIDDEN");
        Permission platformManage = permissionRepository.findByCodeIn(List.of("PLATFORM_MANAGE"))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("PLATFORM_MANAGE permission is missing"));
        Permission itemView = permissionRepository.findByCodeIn(List.of("ITEM_VIEW"))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("ITEM_VIEW permission is missing"));

        roleRepository.save(Role.builder()
                .storeId(store.storeId())
                .code("PLATFORM_SNEAK")
                .name("Legacy Sneak Role")
                .isSystem(false)
                .permissions(Set.of(platformManage))
                .build());
        roleRepository.save(Role.builder()
                .storeId(store.storeId())
                .code("MIXED_OPS")
                .name("Mixed Ops")
                .isSystem(false)
                .permissions(Set.of(itemView, platformManage))
                .build());

        JsonNode roles = call(HttpMethod.GET, "/api/roles", null, store.token(), HttpStatus.OK);
        boolean mixedSeen = false;
        for (JsonNode role : roles) {
            String code = role.get("code").asText();
            assertFalse(code.startsWith("PLATFORM_"), "reserved roles must not be listed");
            for (JsonNode permission : role.get("permissions")) {
                assertNotEquals("PLATFORM_MANAGE", permission.get("code").asText(),
                        "platform-only permissions must not be exposed in store role responses");
            }
            mixedSeen |= "MIXED_OPS".equals(code);
        }
        assertTrue(mixedSeen, "ordinary store roles must remain listed");

        JsonNode available = call(HttpMethod.GET, "/api/users/roles/available", null,
                store.token(), HttpStatus.OK);
        for (JsonNode role : available) {
            assertFalse(role.get("code").asText().startsWith("PLATFORM_"),
                    "reserved roles must not be offered for assignment");
        }
    }

    @Test
    void storeOwnerCannotAssignLegacyReservedRolesToUsers() {
        Session store = provisionStore("NOASSIGN");
        Role legacy = roleRepository.save(Role.builder()
                .storeId(store.storeId())
                .code("PLATFORM_ADMIN")
                .name("Legacy Platform Admin")
                .isSystem(false)
                .permissions(Set.of())
                .build());

        String blockedUsername = "blocked" + SEQ.incrementAndGet();
        call(HttpMethod.POST, "/api/users",
                map("username", blockedUsername, "email", blockedUsername + "@test.local",
                        "password", "Clerk@12345", "fullName", "Blocked User",
                        "roleId", legacy.getId(), "branchIds", List.of()),
                store.token(), HttpStatus.FORBIDDEN);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = ?", Integer.class, blockedUsername));

        long clerkRoleId = createRole(store, "CLERK", List.of("ITEM_VIEW"), HttpStatus.OK)
                .get("id").asLong();
        String clerkUsername = "clerk" + SEQ.incrementAndGet();
        long userId = call(HttpMethod.POST, "/api/users",
                map("username", clerkUsername, "email", clerkUsername + "@test.local",
                        "password", "Clerk@12345", "fullName", "Clerk User",
                        "roleId", clerkRoleId, "branchIds", List.of()),
                store.token(), HttpStatus.OK).get("id").asLong();

        call(HttpMethod.PUT, "/api/users/" + userId,
                map("fullName", "Escalated Clerk", "email", clerkUsername + "@test.local",
                        "mobile", null, "roleId", legacy.getId(), "branchIds", List.of(),
                        "status", "ACTIVE"),
                store.token(), HttpStatus.FORBIDDEN);
        assertEquals(clerkRoleId, jdbc.queryForObject(
                "SELECT role_id FROM user_roles WHERE user_id = ?", Long.class, userId));
    }

    @Test
    void storeUserAssociatedWithTheGlobalPlatformRoleCannotBecomePlatformAdmin() {
        Session store = provisionStore("GLOBAL");
        Session victim = provisionStore("GLOBALVIC");
        Role globalAdminRole = roleRepository.findByStoreIdIsNullAndCode("PLATFORM_ADMIN")
                .orElseThrow(() -> new IllegalStateException("PLATFORM_ADMIN role is missing"));
        User user = userRepository
                .findByStoreIdAndUsernameIgnoreCase(store.storeId(), store.username())
                .orElseThrow(() -> new IllegalStateException("store owner is missing"));
        Set<Role> roles = new java.util.HashSet<>(user.getRoles());
        roles.add(globalAdminRole);
        user.setRoles(roles);
        userRepository.save(user);

        JsonNode auth = login(store.storeCode(), store.username(), OWNER_PASSWORD);
        boolean carriesGlobalRole = false;
        for (JsonNode role : auth.get("roles")) {
            carriesGlobalRole |= "PLATFORM_ADMIN".equals(role.asText());
        }
        assertTrue(carriesGlobalRole, "the global role association is intentionally present");
        assertFalse(auth.get("platformAdmin").asBoolean(),
                "a store-scoped account is never a platform administrator");
        for (JsonNode permission : auth.get("permissions")) {
            assertNotEquals("PLATFORM_MANAGE", permission.asText());
        }

        String token = auth.get("token").asText();
        call(HttpMethod.GET, "/api/stores", null, token, HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/stores/" + victim.storeId(), null, token, HttpStatus.FORBIDDEN);
        call(HttpMethod.DELETE, "/api/stores/" + victim.storeId()
                + "?confirmName=" + victim.storeName(), null, token, HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/stores/current", null, token, HttpStatus.OK);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM stores WHERE id = ?",
                Integer.class, victim.storeId()));
    }

    @Test
    void legacyEscalatedRowsGrantNoPlatformAccess() {
        Session store = provisionStore("LEG");
        Session victim = provisionStore("VIC");
        Permission platformManage = permissionRepository.findByCodeIn(List.of("PLATFORM_MANAGE"))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("PLATFORM_MANAGE permission is missing"));

        Role rogueRole = roleRepository.save(Role.builder()
                .storeId(store.storeId())
                .code("PLATFORM_ADMIN")
                .name("Forged Platform Admin")
                .description("Simulates a role created before reserved codes were blocked")
                .isSystem(false)
                .permissions(Set.of(platformManage))
                .build());
        String username = "rogue" + SEQ.incrementAndGet();
        userRepository.save(User.builder()
                .storeId(store.storeId())
                .username(username)
                .email(username + "@test.local")
                .passwordHash(passwordEncoder.encode("Rogue@12345"))
                .fullName("Rogue User")
                .status(StoreStatus.ACTIVE)
                .roles(Set.of(rogueRole))
                .branches(Set.of())
                .build());

        JsonNode auth = login(store.storeCode(), username, "Rogue@12345");
        assertEquals("PLATFORM_ADMIN", auth.at("/roles/0").asText(),
                "the forged role may still exist in legacy data");
        assertFalse(auth.get("platformAdmin").asBoolean(),
                "a store-scoped account is never a platform administrator");
        for (JsonNode permission : auth.get("permissions")) {
            assertNotEquals("PLATFORM_MANAGE", permission.asText(),
                    "platform permissions are filtered from store sessions");
        }
        String token = auth.get("token").asText();

        call(HttpMethod.GET, "/api/stores", null, token, HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/stores/" + victim.storeId(), null, token, HttpStatus.FORBIDDEN);
        call(HttpMethod.PUT, "/api/stores/" + victim.storeId(),
                map("storeName", victim.storeName(), "ownerName", "Hijacked",
                        "email", "hijack@evil.local", "mobile", "9000000999",
                        "address", "1 Hijack Road", "gstNumber", null, "taxInclusive", false),
                token, HttpStatus.FORBIDDEN);
        call(HttpMethod.PUT, "/api/stores/" + victim.storeId() + "/status",
                map("status", "INACTIVE"), token, HttpStatus.FORBIDDEN);
        call(HttpMethod.POST, "/api/stores", storePayload("CLONE" + SEQ.incrementAndGet(),
                "Clone Store", "clone" + SEQ.get()), token, HttpStatus.FORBIDDEN);
        call(HttpMethod.DELETE, "/api/stores/" + victim.storeId()
                + "?confirmName=" + victim.storeName(), null, token, HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/platform/accounts", null, token, HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/platform/audit-logs", null, token, HttpStatus.FORBIDDEN);
        call(HttpMethod.PUT, "/api/platform/contact",
                map("contactEmail", "attacker@evil.local", "contactPhone", "9000000999"),
                token, HttpStatus.FORBIDDEN);

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE id = ?", Integer.class, victim.storeId()));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM stores WHERE id = ?", String.class, victim.storeId()));
        assertFalse("hijack@evil.local".equals(jdbc.queryForObject(
                "SELECT email FROM stores WHERE id = ?", String.class, victim.storeId())));
    }

    @Test
    void storeOwnerCannotReachPlatformApisButKeepsStoreEndpoints() {
        Session store = provisionStore("OWN");
        Session victim = provisionStore("TARGET");

        call(HttpMethod.GET, "/api/stores", null, store.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/platform/accounts", null, store.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/platform/audit-logs", null, store.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/platform/contact", null, store.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.PUT, "/api/platform/contact",
                map("contactEmail", "owner@evil.local", "contactPhone", "9000000888"),
                store.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.POST, "/api/stores",
                storePayload("OWNCLONE" + SEQ.incrementAndGet(), "Owner Clone", "ownerclone"
                        + SEQ.get()), store.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.DELETE, "/api/stores/" + victim.storeId()
                + "?confirmName=" + victim.storeName(), null, store.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.PUT, "/api/stores/" + victim.storeId() + "/subscription",
                map("startDate", LocalDate.now().toString(),
                        "endDate", LocalDate.now().plusYears(5).toString(), "maxBranches", 500),
                store.token(), HttpStatus.FORBIDDEN);

        JsonNode current = call(HttpMethod.GET, "/api/stores/current", null, store.token(),
                HttpStatus.OK);
        assertEquals(store.storeCode(), current.get("storeCode").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE id = ?", Integer.class, victim.storeId()));
    }

    @Test
    void permissionCatalogueHidesPlatformPermissionsFromStoreAdmins() {
        Session store = provisionStore("CAT");
        JsonNode permissions = call(HttpMethod.GET, "/api/permissions", null, store.token(),
                HttpStatus.OK);

        boolean itemView = false;
        for (JsonNode permission : permissions) {
            String code = permission.get("code").asText();
            assertNotEquals("PLATFORM_MANAGE", code,
                    "platform-only permissions must not be offered to store administrators");
            itemView |= "ITEM_VIEW".equals(code);
        }
        assertTrue(itemView, "ordinary permissions must remain available");
    }

    @Test
    void genuinePlatformAdministratorKeepsFullAccessAndCanDeleteATestStore() {
        String adminToken = platformToken();
        JsonNode adminLogin = login("PLATFORM", "platform", "Admin@123");
        assertTrue(adminLogin.get("platformAdmin").asBoolean());

        int seq = SEQ.incrementAndGet();
        String code = "GENUINE" + seq;
        String name = "Genuine Store " + code;
        String username = "genuineowner" + seq;
        JsonNode created = call(HttpMethod.POST, "/api/stores",
                storePayload(code, name, username), adminToken, HttpStatus.OK);
        long storeId = created.get("id").asLong();
        call(HttpMethod.POST, "/api/stores",
                storePayload("KEEPGEN" + seq, "Keep Store " + code, "keepowner" + seq),
                adminToken, HttpStatus.OK);

        JsonNode stores = call(HttpMethod.GET, "/api/stores?search=" + code, null, adminToken,
                HttpStatus.OK);
        assertTrue(stores.get("totalElements").asLong() >= 1);
        call(HttpMethod.GET, "/api/stores/" + storeId, null, adminToken, HttpStatus.OK);
        call(HttpMethod.GET, "/api/platform/accounts", null, adminToken, HttpStatus.OK);
        call(HttpMethod.GET, "/api/platform/audit-logs", null, adminToken, HttpStatus.OK);

        JsonNode contact = call(HttpMethod.GET, "/api/platform/contact", null, adminToken,
                HttpStatus.OK);
        String previousEmail = contact.get("contactEmail").asText();
        String previousPhone = contact.get("contactPhone").asText();
        JsonNode updated = call(HttpMethod.PUT, "/api/platform/contact",
                map("contactEmail", "support@ims.test", "contactPhone", "1800123456"),
                adminToken, HttpStatus.OK);
        assertEquals("support@ims.test", updated.get("contactEmail").asText());
        // Restore the raw row: with no configured support contact the response is now
        // empty, and the admin-only PUT requires both fields.
        jdbc.update("UPDATE platform_settings SET contact_email = ?, contact_phone = ? WHERE id = 1",
                previousEmail.isBlank() ? null : previousEmail,
                previousPhone.isBlank() ? null : previousPhone);

        JsonNode subscription = call(HttpMethod.PUT, "/api/stores/" + storeId + "/subscription",
                map("startDate", LocalDate.now().toString(),
                        "endDate", LocalDate.now().plusYears(1).toString(),
                        "maxBranches", 7),
                adminToken, HttpStatus.OK);
        assertEquals(7, subscription.get("maxBranches").asInt());

        call(HttpMethod.PUT, "/api/stores/" + storeId + "/status",
                map("status", "INACTIVE"), adminToken, HttpStatus.OK);
        call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", code, "username", username, "password", OWNER_PASSWORD),
                null, HttpStatus.FORBIDDEN);
        call(HttpMethod.PUT, "/api/stores/" + storeId + "/status",
                map("status", "ACTIVE"), adminToken, HttpStatus.OK);
        assertNotNull(login(code, username, OWNER_PASSWORD).get("token"));

        call(HttpMethod.DELETE, "/api/stores/" + storeId + "?confirmName=" + name, null,
                adminToken, HttpStatus.NO_CONTENT);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE id = ?", Integer.class, storeId));
        call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", code, "username", username, "password", OWNER_PASSWORD),
                null, HttpStatus.UNAUTHORIZED);
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE store_code = ?",
                Integer.class, "KEEPGEN" + seq));
    }

    // ---------- fixtures ----------

    private record Session(String token, long storeId, long branchId,
                           String storeCode, String storeName, String username) {
    }

    private Session provisionStore(String prefix) {
        int seq = SEQ.incrementAndGet();
        String code = prefix + seq;
        String name = "Store " + code;
        String username = "owner" + seq;
        provisioningService.createStore(new RegisterStoreRequest(code, name, "Store Owner",
                username + "@test.local", "9000000200", "1 Security Street",
                null, "Karnataka", username, OWNER_PASSWORD, "Main Branch"), false);
        JsonNode auth = login(code, username, OWNER_PASSWORD);
        return new Session(auth.get("token").asText(), auth.at("/store/id").asLong(),
                auth.at("/branches/0/id").asLong(), code, name, username);
    }

    private JsonNode createRole(Session store, String code, List<String> permissions,
                                HttpStatus expected) {
        return call(HttpMethod.POST, "/api/roles",
                map("code", code, "name", code + " Role",
                        "description", "escalation test", "permissionCodes", permissions),
                store.token(), expected);
    }

    private Map<String, Object> storePayload(String code, String name, String username) {
        return map("storeCode", code, "storeName", name, "ownerName", "Store Owner",
                "email", username + "@test.local", "mobile", "9000000222",
                "address", "2 Security Street", "gstNumber", null, "state", "Karnataka",
                "username", username, "password", OWNER_PASSWORD, "branchName", "Main Branch");
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

    private JsonNode login(String storeCode, String username, String password) {
        return call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", storeCode, "username", username, "password", password),
                null, HttpStatus.OK);
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

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }
}
