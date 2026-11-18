package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.service.StoreDeletionService;
import com.itemmanagement.service.StoreProvisioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies permanent store deletion: tenant-scoped rows only, exact-name
 * confirmation, platform-admin-only authorization, full rollback on failure and
 * the intentional retention of platform and audit data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54336",
        "app.embedded-postgres.data-dir=build/pgdata-store-deletion-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "app.platform-admin.provision=false",
        "logging.level.com.itemmanagement=WARN"
})
class StoreDeletionIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Mirrors the tenant tables wiped by StoreDeletionService. */
    private static final List<String> STORE_TABLES = StoreDeletionService.STORE_TABLES_IN_DELETE_ORDER;

    /** Tenant data intentionally retained after store deletion. */
    private static final String RETAINED_TENANT_TABLE = "audit_logs";

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

    @Autowired
    private DataSource dataSource;

    private volatile String cachedPlatformToken;

    @Test
    void platformAdminDeletesOnlyTheSelectedStoreAndKeepsPlatformData() {
        Session target = provisionStore("DEL");
        Session other = provisionStore("KEEP");
        seedBusinessData(target);
        seedBusinessData(other);

        Map<String, Integer> otherCounts = storeCounts(other.storeId());
        Map<String, Integer> targetCounts = storeCounts(target.storeId());
        for (String table : STORE_TABLES) {
            assertTrue(targetCounts.get(table) > 0, "fixture must populate " + table);
        }
        long businessAuditRows = auditRowsForStore(target.storeId());
        assertTrue(businessAuditRows > 0, "store activity should be audited");

        deleteStore(target, target.storeName(), HttpStatus.NO_CONTENT);

        for (String table : STORE_TABLES) {
            assertEquals(0, storeCount(target.storeId(), table),
                    "store rows must be gone from " + table);
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stores WHERE id = ?",
                Integer.class, target.storeId()));

        assertEquals(otherCounts, storeCounts(other.storeId()),
                "another store must be untouched");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE id = ?", Integer.class, other.storeId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE store_id IS NULL", Integer.class));

        assertEquals(businessAuditRows, auditRowsForStore(target.storeId()),
                "audit history is retained after the business data is removed");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'DELETE' AND entity_type = 'STORE' "
                        + "AND entity_id = ? AND store_id IS NULL", Integer.class, target.storeId()));

        JsonNode login = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", other.storeCode(), "username", other.username(),
                        "password", "Secret@123"), null, HttpStatus.OK);
        assertNotNull(login.get("token"));
        JsonNode stores = call(HttpMethod.GET, "/api/stores?search=" + target.storeCode(),
                null, platformToken(), HttpStatus.OK);
        assertEquals(0, stores.get("totalElements").asLong());
    }

    @Test
    void onlyPlatformAdministratorsMayDeleteStores() {
        Session store = provisionStore("AUTH");
        seedBusinessData(store);

        call(HttpMethod.DELETE, deletePath(store, store.storeName()), null,
                store.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.DELETE, deletePath(store, store.storeName()), null,
                null, HttpStatus.UNAUTHORIZED);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM stores WHERE id = ?",
                Integer.class, store.storeId()));

        deleteStore(store, store.storeName(), HttpStatus.NO_CONTENT);
        assertEquals(0, storeCount(store.storeId(), "items"));
    }

    @Test
    void deletionRequiresTheExactStoreName() {
        Session store = provisionStore("NAME");

        deleteStore(store, store.storeName().toLowerCase(), HttpStatus.BAD_REQUEST);
        deleteStore(store, "WRONG" + store.storeName(), HttpStatus.BAD_REQUEST);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM stores WHERE id = ?",
                Integer.class, store.storeId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'DELETE' AND entity_type = 'STORE' "
                        + "AND entity_id = ?", Integer.class, store.storeId()));

        deleteStore(store, store.storeName(), HttpStatus.NO_CONTENT);
    }

    @Test
    void deletingANonExistentStoreReturnsNotFound() {
        deleteStoreRaw(999_999L, "anything", HttpStatus.NOT_FOUND);
    }

    @Test
    void failedDeletionRollsBackEveryChange() {
        Session store = provisionStore("ROLL");
        seedBusinessData(store);
        Map<String, Integer> before = storeCounts(store.storeId());
        long auditBefore = auditRowsForStore(store.storeId());

        jdbc.execute("CREATE FUNCTION fail_store_branch_delete() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced deletion failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_store_branch_delete BEFORE DELETE ON branches "
                + "FOR EACH ROW EXECUTE FUNCTION fail_store_branch_delete()");
        try {
            deleteStoreRaw(store.storeId(), store.storeName(), HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_store_branch_delete ON branches");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_store_branch_delete()");
        }

        assertEquals(before, storeCounts(store.storeId()),
                "a failed deletion must roll back every table");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM stores WHERE id = ?",
                Integer.class, store.storeId()));
        assertEquals(auditBefore, auditRowsForStore(store.storeId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'DELETE' AND entity_type = 'STORE' "
                        + "AND entity_id = ?", Integer.class, store.storeId()));
    }

    @Test
    void publicRegistrationIsRejectedAndPlatformAdminsCanStillCreateStores() {
        int seq = SEQ.incrementAndGet();
        String code = "PUBLIC" + seq;
        Map<String, Object> payload = storePayload(code, "Public Store " + code, "owner" + seq);
        call(HttpMethod.POST, "/api/auth/register-store", payload, null, HttpStatus.FORBIDDEN);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE store_code = ?", Integer.class, code));

        String adminCode = "ADMIN" + seq;
        String adminName = "AdminStore" + adminCode;
        JsonNode created = call(HttpMethod.POST, "/api/stores",
                storePayload(adminCode, adminName, "owner" + seq),
                platformToken(), HttpStatus.OK);
        assertEquals(adminCode, created.get("storeCode").asText());

        JsonNode login = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", adminCode, "username", "owner" + seq, "password", "Secret@123"),
                null, HttpStatus.OK);
        assertEquals("STORE_OWNER", login.at("/roles/0").asText());

        call(HttpMethod.DELETE, "/api/stores/" + created.get("id").asLong()
                        + "?confirmName=" + adminName, null,
                platformToken(), HttpStatus.NO_CONTENT);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE store_code = ?", Integer.class, adminCode));
    }

    @Test
    void auditFailureRollsBackTheEntireDeletion() {
        Session store = provisionStore("AUDITFAIL");
        seedBusinessData(store);
        Map<String, Integer> before = storeCounts(store.storeId());
        long auditBefore = auditRowsForStore(store.storeId());
        // Obtain the platform token before installing the trigger so the login
        // audit itself is not affected by the forced failure.
        platformToken();

        jdbc.execute("CREATE FUNCTION fail_store_audit_insert() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced audit failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_store_audit_insert BEFORE INSERT ON audit_logs "
                + "FOR EACH ROW EXECUTE FUNCTION fail_store_audit_insert()");
        try {
            deleteStoreRaw(store.storeId(), store.storeName(), HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_store_audit_insert ON audit_logs");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_store_audit_insert()");
        }

        assertEquals(before, storeCounts(store.storeId()),
                "the deletion must roll back when its audit record cannot be written");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM stores WHERE id = ?",
                Integer.class, store.storeId()));
        assertEquals(auditBefore, auditRowsForStore(store.storeId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'DELETE' AND entity_type = 'STORE' "
                        + "AND entity_id = ?", Integer.class, store.storeId()));

        deleteStore(store, store.storeName(), HttpStatus.NO_CONTENT);
        assertEquals(0, storeCount(store.storeId(), "items"));
    }

    @Test
    @Timeout(120)
    void deletionWaitsForTheStoreLockSoConcurrentWritesCannotOrphanRows() throws Exception {
        Session store = provisionStore("LOCK");
        seedBusinessData(store);
        String token = platformToken();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            try (PreparedStatement insert = writer.prepareStatement(
                    "INSERT INTO customers (store_id, name, mobile) VALUES (?, ?, ?)")) {
                insert.setLong(1, store.storeId());
                insert.setString(2, "Concurrent Customer");
                insert.setString(3, "9000000199");
                insert.executeUpdate();
            }

            Future<DeleteResult> deletion = executor.submit(
                    () -> deleteStoreRequest(store.storeId(), store.storeName(), token));

            awaitDeletionBlockedOnStoreLock();
            assertFalse(deletion.isDone(),
                    "deletion must wait while another transaction holds store-scoped data");

            writer.commit();

            DeleteResult result = deletion.get(30, TimeUnit.SECONDS);
            assertEquals(HttpStatus.NO_CONTENT.value(), result.status(),
                    () -> "unexpected deletion response: " + result.body());
        } finally {
            executor.shutdownNow();
        }

        for (String table : STORE_TABLES) {
            assertEquals(0, storeCount(store.storeId(), table),
                    "the concurrent write must not leave rows in " + table);
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stores WHERE id = ?",
                Integer.class, store.storeId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'DELETE' AND entity_type = 'STORE' "
                        + "AND entity_id = ? AND store_id IS NULL", Integer.class, store.storeId()));
    }

    @Test
    @Timeout(120)
    void concurrentDeletionRequestsDeleteOnceAndRejectTheSecondSafely() throws Exception {
        Session store = provisionStore("RACE");
        seedBusinessData(store);
        String token = platformToken();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<DeleteResult> delete = () -> {
                start.await();
                return deleteStoreRequest(store.storeId(), store.storeName(), token);
            };
            Future<DeleteResult> first = executor.submit(delete);
            Future<DeleteResult> second = executor.submit(delete);
            start.countDown();

            List<Integer> statuses = List.of(
                    first.get(60, TimeUnit.SECONDS).status(),
                    second.get(60, TimeUnit.SECONDS).status())
                    .stream().sorted().toList();
            assertEquals(List.of(HttpStatus.NO_CONTENT.value(), HttpStatus.NOT_FOUND.value()),
                    statuses, "exactly one deletion may win; the other must fail safely");
        } finally {
            executor.shutdownNow();
        }

        for (String table : STORE_TABLES) {
            assertEquals(0, storeCount(store.storeId(), table), "no partial state in " + table);
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stores WHERE id = ?",
                Integer.class, store.storeId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'DELETE' AND entity_type = 'STORE' "
                        + "AND entity_id = ? AND store_id IS NULL", Integer.class, store.storeId()));
    }

    @Test
    void everyDeletedTenantTableProtectsTheStoreRowWithAForeignKey() {
        List<String> tablesWithStoreForeignKey = jdbc.queryForList(
                "SELECT DISTINCT t.relname FROM pg_constraint c "
                        + "JOIN pg_class t ON t.oid = c.conrelid "
                        + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                        + "WHERE c.contype = 'f' AND n.nspname = 'public' "
                        + "AND c.confrelid = 'public.stores'::regclass",
                String.class);

        assertTrue(tablesWithStoreForeignKey.containsAll(STORE_TABLES),
                "every deleted tenant table must hold a foreign key to stores so an in-flight "
                        + "write cannot commit orphaned rows");

        // The retained audit trail is the only store_id table without an FK; it
        // is intentionally denormalised and never treated as tenant business data.
        assertFalse(tablesWithStoreForeignKey.contains(RETAINED_TENANT_TABLE),
                "audit_logs is retained by policy and has no foreign key to stores");
    }

    @Test
    void deletionCoverageMatchesEveryStoreScopedTableInTheSchema() {
        List<String> schemaTables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND column_name = 'store_id'",
                String.class);

        Set<String> expected = new java.util.TreeSet<>(StoreDeletionService.STORE_TABLES_IN_DELETE_ORDER);
        expected.add(RETAINED_TENANT_TABLE);
        assertEquals(expected, new java.util.TreeSet<>(schemaTables),
                "every table carrying store_id must be deleted by StoreDeletionService "
                        + "or explicitly retained by policy");
    }

    // ---------- fixtures ----------

    private record Session(String token, long userId, long storeId, long branchId,
                           String storeCode, String username, String storeName) {
    }

    private Session provisionStore(String prefix) {
        int seq = SEQ.incrementAndGet();
        String code = prefix + seq;
        String name = "Store" + code;
        String username = "owner" + seq;
        provisioningService.createStore(new RegisterStoreRequest(code, name, "Store Owner",
                "owner" + seq + "@test.local", "9000000100", "1 Delete Street",
                null, "Karnataka", username, "Secret@123", "Main Branch"), false);
        JsonNode auth = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", code, "username", username, "password", "Secret@123"),
                null, HttpStatus.OK);
        return new Session(auth.get("token").asText(), auth.at("/user/id").asLong(),
                auth.at("/store/id").asLong(), auth.at("/branches/0/id").asLong(),
                code, username, name);
    }

    private void seedBusinessData(Session session) {
        long categoryId = call(HttpMethod.POST, "/api/categories",
                map("categoryName", "Deletion Category " + SEQ.incrementAndGet(), "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();

        long taxId = 0;
        for (JsonNode tax : call(HttpMethod.GET, "/api/taxes/options", null, session.token(),
                HttpStatus.OK)) {
            if ("EXEMPT".equals(tax.get("taxType").asText())) {
                taxId = tax.get("id").asLong();
                break;
            }
        }
        assertTrue(taxId > 0, "exempt tax must be provisioned");

        long itemId = call(HttpMethod.POST, "/api/items",
                map("itemCode", "DEL" + UUID.randomUUID().toString().replace("-", "")
                                .substring(0, 8).toUpperCase(),
                        "itemName", "Deletion Item " + SEQ.incrementAndGet(),
                        "categoryId", categoryId, "taxId", taxId, "baseUnit", "PIECE",
                        "rate", new BigDecimal("20"), "minStockLevel", new BigDecimal("1"),
                        "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();

        long supplierId = call(HttpMethod.POST, "/api/suppliers",
                map("supplierName", "Deletion Supplier " + SEQ.incrementAndGet(),
                        "mobile", "9000000099", "address", "9 Vendor Road", "state", "Karnataka",
                        "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();

        long purchaseId = call(HttpMethod.POST, "/api/purchases",
                map("branchId", session.branchId(), "supplierId", supplierId,
                        "invoiceNumber", "INV-" + SEQ.incrementAndGet(),
                        "purchaseDate", LocalDate.now().toString(), "status", "COMPLETED",
                        "payableAmount", new BigDecimal("100"), "paidAmount", BigDecimal.ZERO,
                        "items", List.of(map("itemId", itemId, "quantity", new BigDecimal("5"),
                                "unit", "PIECE", "purchaseRate", new BigDecimal("20"),
                                "discount", BigDecimal.ZERO))),
                session.token(), HttpStatus.OK).get("id").asLong();

        call(HttpMethod.POST, "/api/supplier-payments",
                map("supplierId", supplierId, "paymentDate", LocalDate.now().toString(),
                        "paymentMethod", "CASH", "amount", new BigDecimal("50"),
                        "purchaseIds", List.of(purchaseId),
                        "clientReference", UUID.randomUUID().toString()),
                session.token(), HttpStatus.OK);

        JsonNode modifiedSale = call(HttpMethod.POST, "/api/sales",
                map("branchId", session.branchId(), "paymentMethod", "CASH",
                        "items", List.of(map("itemId", itemId, "quantity", BigDecimal.ONE,
                                "unit", "PIECE", "sellingRate", new BigDecimal("20"),
                                "discount", BigDecimal.ZERO))),
                session.token(), HttpStatus.OK);
        call(HttpMethod.POST, "/api/sales/" + modifiedSale.get("id").asLong() + "/modifications",
                map("reason", "Deletion coverage edit", "baseModificationCount", 0,
                        "items", List.of(map("itemId", itemId, "quantity", new BigDecimal("2"),
                                "unit", "PIECE", "sellingRate", new BigDecimal("20"),
                                "discount", BigDecimal.ZERO))),
                session.token(), HttpStatus.OK);

        JsonNode cancelledSale = call(HttpMethod.POST, "/api/sales",
                map("branchId", session.branchId(), "paymentMethod", "CASH",
                        "items", List.of(map("itemId", itemId, "quantity", BigDecimal.ONE,
                                "unit", "PIECE", "sellingRate", new BigDecimal("20"),
                                "discount", BigDecimal.ZERO))),
                session.token(), HttpStatus.OK);
        call(HttpMethod.POST, "/api/sales/" + cancelledSale.get("id").asLong() + "/cancel",
                map("reason", "Deletion coverage cancellation"),
                session.token(), HttpStatus.OK);

        long secondBranch = call(HttpMethod.POST, "/api/branches",
                map("branchCode", "BR" + SEQ.incrementAndGet(),
                        "branchName", "Second Branch", "address", "2 Branch Road",
                        "contactNumber", "9000000101", "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();

        call(HttpMethod.POST, "/api/transfers",
                map("fromBranchId", session.branchId(), "toBranchId", secondBranch,
                        "transferDate", LocalDate.now().toString(),
                        "items", List.of(map("itemId", itemId, "quantity", BigDecimal.ONE,
                                "unit", "PIECE"))),
                session.token(), HttpStatus.OK);

        call(HttpMethod.POST, "/api/inventory/adjustments",
                map("branchId", session.branchId(), "itemId", itemId, "type", "IN",
                        "quantity", new BigDecimal("2"), "unit", "PIECE",
                        "reason", "Opening count"),
                session.token(), HttpStatus.OK);

        String hash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO printer_configs (store_id, branch_id, printer_name, ip_address) "
                        + "VALUES (?, ?, ?, ?)", session.storeId(), session.branchId(),
                "Counter Printer", "192.168.1.50");
        jdbc.update("INSERT INTO print_pairing_codes (store_id, branch_id, code_hash, expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '10 minutes')", session.storeId(),
                session.branchId(), hash);
        jdbc.update("INSERT INTO print_devices (store_id, branch_id, device_name, platform, "
                        + "token_hash, paired_by) VALUES (?, ?, ?, 'WINDOWS', ?, ?)",
                session.storeId(), session.branchId(), "Counter PC", hash, session.userId());
        jdbc.update("INSERT INTO print_jobs (store_id, branch_id, job_key, document_type, channel, "
                        + "trigger_type, created_by) VALUES (?, ?, ?, 'SALE_RECEIPT', 'WINDOWS', "
                        + "'AUTO', ?)", session.storeId(), session.branchId(),
                "job-" + UUID.randomUUID(), session.userId());

        Long batchId = jdbc.queryForObject(
                "INSERT INTO item_import_batches (store_id, branch_id, import_number, file_name, "
                        + "file_hash, uploaded_by) VALUES (?, ?, ?, 'items.xlsx', ?, ?) RETURNING id",
                Long.class, session.storeId(), session.branchId(),
                "IMP-" + SEQ.incrementAndGet(), hash, session.userId());
        jdbc.update("INSERT INTO item_import_rows (batch_id, store_id, row_number, item_name, "
                        + "import_action, validation_status) VALUES (?, ?, 1, 'Imported Item', "
                        + "'CREATE', 'VALID')", batchId, session.storeId());

        jdbc.update("INSERT INTO subscription_change_history (store_id, changed_by, new_end_date) "
                + "VALUES (?, ?, now() + interval '1 year')", session.storeId(), session.userId());
        jdbc.update("INSERT INTO customers (store_id, name, mobile) VALUES (?, 'Walk-in Customer', "
                + "'9000000103')", session.storeId());
    }

    private Map<String, Object> storePayload(String code, String name, String username) {
        return map("storeCode", code, "storeName", name, "ownerName", "Store Owner",
                "email", username + "@test.local", "mobile", "9000000102",
                "address", "3 Admin Street", "gstNumber", null, "state", "Karnataka",
                "username", username, "password", "Secret@123", "branchName", "Main Branch");
    }

    private void deleteStore(Session store, String confirmName, HttpStatus expected) {
        deleteStoreRaw(store.storeId(), confirmName, expected);
    }

    private void deleteStoreRaw(long storeId, String confirmName, HttpStatus expected) {
        call(HttpMethod.DELETE, "/api/stores/" + storeId + "?confirmName=" + confirmName,
                null, platformToken(), expected);
    }

    private DeleteResult deleteStoreRequest(long storeId, String confirmName, String token) {
        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/stores/" + storeId + "?confirmName=" + confirmName,
                HttpMethod.DELETE, entity(null, token), JsonNode.class);
        return new DeleteResult(response.getStatusCode().value(),
                String.valueOf(response.getBody()));
    }

    private record DeleteResult(int status, String body) {
    }

    private void awaitDeletionBlockedOnStoreLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            // pg_blocking_pids is visible without the pg_read_all_stats privilege,
            // unlike other sessions' query text.
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity "
                            + "WHERE datname = current_database() AND pid <> pg_backend_pid() "
                            + "AND cardinality(pg_blocking_pids(pid)) > 0",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        fail("deletion never blocked on the store row lock");
    }

    private String deletePath(Session store, String confirmName) {
        return "/api/stores/" + store.storeId() + "?confirmName=" + confirmName;
    }

    private Map<String, Integer> storeCounts(long storeId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : STORE_TABLES) {
            counts.put(table, storeCount(storeId, table));
        }
        return counts;
    }

    private int storeCount(long storeId, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE store_id = ?", Integer.class, storeId);
        return count == null ? 0 : count;
    }

    private long auditRowsForStore(long storeId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE store_id = ?", Long.class, storeId);
        return count == null ? 0 : count;
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

    // ---------- HTTP plumbing ----------

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
