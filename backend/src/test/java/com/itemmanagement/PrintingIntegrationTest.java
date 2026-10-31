package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * End-to-end tests for printer configuration, one-time bridge pairing and
 * silent print jobs. Runs against a real embedded PostgreSQL and goes through
 * the HTTP API exactly like the website and the native print bridges do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54331",
        "app.embedded-postgres.data-dir=build/pgdata-printing-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "logging.level.com.itemmanagement=WARN"
})
class PrintingIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PAIR_PATH = "/api/printing/devices/pair";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- printer configuration ----------

    @Test
    void printerConfigCrudValidatesAndIsStrictlyScoped() {
        Session storeA = registerStore();
        Session storeB = registerStore();

        JsonNode printer = createPrinter(storeA, storeA.branchId(), "Counter Printer",
                "192.168.1.50", 9100, "MM80", "RECEIPT");
        assertEquals("192.168.1.50", printer.get("ipAddress").asText());
        assertEquals(9100, printer.get("port").asInt());
        assertEquals("MM80", printer.get("paperWidth").asText());
        assertEquals("RECEIPT", printer.get("purpose").asText());
        assertEquals("ACTIVE", printer.get("status").asText());

        // Duplicate names inside a branch are rejected.
        ResponseEntity<JsonNode> duplicate = rest.exchange("/api/printing/printers",
                HttpMethod.POST, entity(printerRequest(storeA.branchId(), "counter printer",
                        "192.168.1.51", 9100, "MM80", "RECEIPT", "ACTIVE"), storeA.token()),
                JsonNode.class);
        assertEquals(HttpStatus.CONFLICT.value(), duplicate.getStatusCode().value());

        // Unusable addresses, bad ports, wrong paper widths and purposes.
        assertBadRequest(storeA, printerRequest(storeA.branchId(), "Bad IP", "0.0.0.0",
                9100, "MM80", "RECEIPT", "ACTIVE"));
        assertBadRequest(storeA, printerRequest(storeA.branchId(), "Bad Port", "192.168.1.52",
                70000, "MM80", "RECEIPT", "ACTIVE"));
        assertBadRequest(storeA, printerRequest(storeA.branchId(), "Bad Paper", "192.168.1.53",
                9100, "A4", "RECEIPT", "ACTIVE"));
        assertBadRequest(storeA, printerRequest(storeA.branchId(), "Bad Purpose", "192.168.1.54",
                9100, "MM80", "KITCHEN", "ACTIVE"));

        // Update, deactivate, then list.
        long id = printer.get("id").asLong();
        JsonNode updated = call(HttpMethod.PUT, "/api/printing/printers/" + id,
                printerRequest(storeA.branchId(), "Counter Printer 2", "192.168.1.60",
                        9200, "MM58", "ALL", "ACTIVE"), storeA.token(), HttpStatus.OK);
        assertEquals("Counter Printer 2", updated.get("printerName").asText());
        assertEquals(9200, updated.get("port").asInt());
        assertEquals("MM58", updated.get("paperWidth").asText());
        assertEquals("ALL", updated.get("purpose").asText());

        JsonNode inactive = call(HttpMethod.PUT, "/api/printing/printers/" + id + "/status",
                map("status", "INACTIVE"), storeA.token(), HttpStatus.OK);
        assertEquals("INACTIVE", inactive.get("status").asText());

        JsonNode listed = call(HttpMethod.GET,
                "/api/printing/printers?branchId=" + storeA.branchId(), null,
                storeA.token(), HttpStatus.OK);
        assertEquals(1, listed.size());

        // Another store cannot read or change the printer, even by guessing ids.
        ResponseEntity<JsonNode> foreignList = rest.exchange(
                "/api/printing/printers?branchId=" + storeA.branchId(), HttpMethod.GET,
                bearerOnly(storeB.token()), JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND.value(), foreignList.getStatusCode().value());
        ResponseEntity<JsonNode> foreignUpdate = rest.exchange("/api/printing/printers/" + id,
                HttpMethod.PUT, entity(printerRequest(storeA.branchId(), "Hijacked",
                        "192.168.1.61", 9100, "MM80", "RECEIPT", "ACTIVE"), storeB.token()),
                JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND.value(), foreignUpdate.getStatusCode().value());
        ResponseEntity<JsonNode> foreignDelete = rest.exchange("/api/printing/printers/" + id,
                HttpMethod.DELETE, bearerOnly(storeB.token()), JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND.value(), foreignDelete.getStatusCode().value());

        call(HttpMethod.DELETE, "/api/printing/printers/" + id, null, storeA.token(), HttpStatus.OK);
        assertEquals(0, call(HttpMethod.GET, "/api/printing/printers?branchId=" + storeA.branchId(),
                null, storeA.token(), HttpStatus.OK).size());
    }

    @Test
    void printerTestResultsAreStored() {
        Session session = registerStore();
        long id = createPrinter(session, session.branchId(), "Test Printer",
                "192.168.1.70", 9100, "MM80", "RECEIPT").get("id").asLong();

        JsonNode failed = call(HttpMethod.PUT, "/api/printing/printers/" + id + "/test-result",
                map("success", false, "message", "Connection timed out"), session.token(), HttpStatus.OK);
        assertEquals("Connection timed out", failed.get("lastTestMessage").asText());
        assertFalse(failed.get("lastTestAt").isNull());

        JsonNode passed = call(HttpMethod.PUT, "/api/printing/printers/" + id + "/test-result",
                map("success", true, "message", "Connected successfully"), session.token(), HttpStatus.OK);
        assertEquals("Connected successfully", passed.get("lastTestMessage").asText());
    }

    // ---------- pairing and device tokens ----------

    @Test
    void pairingCodeIssuesSingleUseDeviceTokenAndConfigIsRevocable() {
        Session session = registerStore();
        createBranch(session, "SECOND");
        createPrinter(session, session.branchId(), "Receipt A", "192.168.1.80", 9100, "MM58", "RECEIPT");

        JsonNode code = call(HttpMethod.POST,
                "/api/printing/printers/pairing-code?branchId=" + session.branchId(), null,
                session.token(), HttpStatus.OK);
        String pairingCode = code.get("code").asText();
        assertEquals(8, pairingCode.length());
        assertFalse(code.get("expiresAt").isNull());

        // The bridge pairs without any JWT using only the one-time code.
        JsonNode paired = pair(pairingCode, "Counter Tablet", "ANDROID", HttpStatus.OK);
        String deviceToken = paired.get("deviceToken").asText();
        assertEquals(1, paired.get("printers").size());
        assertEquals("Receipt A", paired.at("/printers/0/printerName").asText());
        assertEquals(session.storeId(), paired.get("storeId").asLong());
        assertEquals(session.branchId(), paired.get("branchId").asLong());

        // The same code cannot be redeemed twice.
        JsonNode reuse = pair(pairingCode, "Second Tablet", "ANDROID", HttpStatus.BAD_REQUEST);
        assertTrue(reuse.get("message").asText().contains("already been used"));

        // The device token pulls only that branch's printer configuration.
        JsonNode config = deviceConfig(deviceToken, HttpStatus.OK);
        assertEquals(session.branchId(), config.get("branchId").asLong());
        assertEquals(1, config.get("printers").size());
        assertEquals("Receipt A", config.at("/printers/0/printerName").asText());
        assertFalse(config.get("syncedAt").isNull());

        JsonNode devices = call(HttpMethod.GET,
                "/api/printing/printers/devices?branchId=" + session.branchId(), null,
                session.token(), HttpStatus.OK);
        assertEquals(1, devices.size());
        assertEquals("ANDROID", devices.get(0).get("platform").asText());
        assertFalse(devices.get(0).get("lastSeenAt").isNull());

        assertTrue(deviceConfig("not-a-token", HttpStatus.UNAUTHORIZED)
                .get("message").asText().contains("Invalid"));

        // Expired codes are rejected.
        String expiredCode = call(HttpMethod.POST,
                "/api/printing/printers/pairing-code?branchId=" + session.branchId(), null,
                session.token(), HttpStatus.OK).get("code").asText();
        jdbc.update("UPDATE print_pairing_codes SET expires_at = now() - interval '1 minute' "
                + "WHERE used_at IS NULL");
        assertTrue(pair(expiredCode, "Late Tablet", "ANDROID", HttpStatus.BAD_REQUEST)
                .get("message").asText().contains("expired"));

        // Revocation cuts the bridge off immediately.
        long deviceId = devices.get(0).get("id").asLong();
        JsonNode revoked = call(HttpMethod.DELETE,
                "/api/printing/printers/devices/" + deviceId, null, session.token(), HttpStatus.OK);
        assertEquals("REVOKED", revoked.get("status").asText());
        JsonNode rejected = deviceConfig(deviceToken, HttpStatus.UNAUTHORIZED);
        assertTrue(rejected.get("message").asText().contains("revoked"));

        // Pairing and device endpoints remain store isolated.
        ResponseEntity<JsonNode> foreignDevices = rest.exchange(
                "/api/printing/printers/devices?branchId=" + session.branchId(), HttpMethod.GET,
                bearerOnly(registerStore().token()), JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND.value(), foreignDevices.getStatusCode().value());
    }

    @Test
    void pairingRequiresValidPayload() {
        JsonNode missingCode = pairRequest(map("code", "", "deviceName", "Tablet",
                "platform", "ANDROID"), HttpStatus.BAD_REQUEST);
        assertNotNull(missingCode.get("message"));
        pair( "ABCDEFGH", "X", "ANDROID", HttpStatus.BAD_REQUEST);
        pair("ABCDEFGH", "Tablet", "TOASTER", HttpStatus.BAD_REQUEST);
        pair("ZZZZZZZZ", "Tablet", "ANDROID", HttpStatus.BAD_REQUEST);
    }

    // ---------- print jobs ----------

    @Test
    void automaticAndroidPrintJobsAreIdempotentAndUseBranchPrinter() {
        Session session = registerStore();
        long saleId = createSale(session);

        // Android has no default printer, so an active configuration is required.
        JsonNode missingPrinter = printJob(session, saleId, "ANDROID", "AUTO", null,
                HttpStatus.BAD_REQUEST);
        assertTrue(missingPrinter.get("message").asText().contains("No active receipt printer"));

        long printerId = createPrinter(session, session.branchId(), "Billing Printer",
                "192.168.1.90", 9100, "MM58", "ALL").get("id").asLong();

        JsonNode job = printJob(session, saleId, "ANDROID", "AUTO", null, HttpStatus.OK);
        assertEquals("PENDING", job.get("status").asText());
        assertEquals("ANDROID", job.get("channel").asText());
        assertEquals("AUTO", job.get("trigger").asText());
        assertEquals("SALE_RECEIPT", job.get("documentType").asText());
        assertEquals(printerId, job.get("printerConfigId").asLong());
        assertEquals("192.168.1.90", job.get("printerIpAddress").asText());
        assertEquals(9100, job.get("printerPort").asInt());
        assertEquals("MM58", job.get("paperWidth").asText());
        assertEquals("ALL", job.get("printerPurpose").asText());
        assertFalse(job.get("duplicate").asBoolean());
        assertEquals(0, job.get("attempts").asInt());
        assertTrue(job.path("printedAt").isMissingNode() || job.path("printedAt").isNull());

        // A double click or a refreshed browser must never print twice.
        JsonNode duplicate = printJob(session, saleId, "ANDROID", "AUTO", null, HttpStatus.OK);
        assertEquals(job.get("id").asLong(), duplicate.get("id").asLong());
        assertTrue(duplicate.get("duplicate").asBoolean());
        assertEquals(1, jobCount(session, saleId, "ANDROID", "AUTO"));

        // Windows jobs use the PC default printer, so configuration is optional.
        JsonNode windows = printJob(session, saleId, "WINDOWS", "AUTO", null, HttpStatus.OK);
        assertEquals("WINDOWS", windows.get("channel").asText());
        assertEquals(1, jobCount(session, saleId, "WINDOWS", "AUTO"));

        // Each channel is an independent automatic print.
        assertFalse(windows.get("id").asLong() == job.get("id").asLong());

        // Status transitions are one-way and recorded.
        JsonNode printed = call(HttpMethod.PUT, "/api/printing/jobs/" + windows.get("id").asLong()
                + "/status", map("status", "PRINTED"), session.token(), HttpStatus.OK);
        assertEquals("PRINTED", printed.get("status").asText());
        assertEquals(1, printed.get("attempts").asInt());
        assertFalse(printed.get("printedAt").isNull());
        ResponseEntity<JsonNode> finalised = rest.exchange("/api/printing/jobs/"
                + windows.get("id").asLong() + "/status", HttpMethod.PUT,
                entity(map("status", "FAILED", "errorMessage", "late"), session.token()),
                JsonNode.class);
        assertEquals(HttpStatus.CONFLICT.value(), finalised.getStatusCode().value());

        JsonNode failed = call(HttpMethod.PUT, "/api/printing/jobs/" + job.get("id").asLong()
                + "/status", map("status", "FAILED", "errorMessage", "Printer is offline"),
                session.token(), HttpStatus.OK);
        assertEquals("FAILED", failed.get("status").asText());
        assertEquals("Printer is offline", failed.get("errorMessage").asText());

        // Manual reprints are new jobs and keep working after a failure.
        JsonNode reprint = call(HttpMethod.POST, "/api/printing/jobs/" + job.get("id").asLong()
                + "/reprint", null, session.token(), HttpStatus.OK);
        assertEquals("MANUAL", reprint.get("trigger").asText());
        assertFalse(reprint.get("duplicate").asBoolean());
        assertTrue(reprint.get("id").asLong() != job.get("id").asLong());
        JsonNode reprintAgain = call(HttpMethod.POST, "/api/printing/jobs/" + reprint.get("id").asLong()
                + "/reprint", null, session.token(), HttpStatus.OK);
        assertTrue(reprintAgain.get("id").asLong() != reprint.get("id").asLong());
        assertEquals(1, jobCount(session, saleId, "ANDROID", "AUTO"));

        // History is paged and filterable.
        JsonNode history = call(HttpMethod.GET, "/api/printing/jobs?size=10", null,
                session.token(), HttpStatus.OK);
        assertEquals(4, history.get("totalElements").asInt());
        JsonNode failedOnly = call(HttpMethod.GET, "/api/printing/jobs?status=FAILED", null,
                session.token(), HttpStatus.OK);
        assertEquals(1, failedOnly.get("totalElements").asInt());
        call(HttpMethod.GET, "/api/printing/jobs?status=BOGUS", null,
                session.token(), HttpStatus.BAD_REQUEST);
    }

    @Test
    void cancelledSalesAndForeignStoresCannotPrint() {
        Session storeA = registerStore();
        Session storeB = registerStore();
        long saleId = createSale(storeA);

        call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel", null, storeA.token(), HttpStatus.OK);
        JsonNode cancelled = printJob(storeA, saleId, "WINDOWS", "AUTO", null, HttpStatus.BAD_REQUEST);
        assertTrue(cancelled.get("message").asText().contains("completed sales"));

        // Store B cannot print store A's sale.
        JsonNode foreignSale = printJob(storeB, saleId, "WINDOWS", "AUTO", null, HttpStatus.NOT_FOUND);
        assertTrue(foreignSale.get("message").asText().contains("Sale not found"));

        long jobId = printJob(storeA, createSale(storeA), "WINDOWS", "AUTO", null, HttpStatus.OK)
                .get("id").asLong();
        assertEquals(HttpStatus.NOT_FOUND.value(), rest.exchange("/api/printing/jobs/" + jobId,
                HttpMethod.GET, bearerOnly(storeB.token()), JsonNode.class).getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND.value(), rest.exchange("/api/printing/jobs/" + jobId
                        + "/reprint", HttpMethod.POST, bearerOnly(storeB.token()), JsonNode.class)
                .getStatusCode().value());
        assertEquals(0, call(HttpMethod.GET, "/api/printing/jobs", null, storeB.token(), HttpStatus.OK)
                .get("totalElements").asInt());
    }

    @Test
    void concurrentAutomaticRequestsCreateExactlyOneJob() throws Exception {
        Session session = registerStore();
        long saleId = createSale(session);
        createPrinter(session, session.branchId(), "Concurrent Printer",
                "192.168.1.100", 9100, "MM80", "RECEIPT");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                ResponseEntity<JsonNode> response = rest.exchange("/api/printing/jobs",
                        HttpMethod.POST, entity(printJobRequest(saleId, "ANDROID", "AUTO", null),
                                session.token()), JsonNode.class);
                assertEquals(HttpStatus.OK.value(), response.getStatusCode().value(),
                        () -> "concurrent print failed: " + response.getBody());
                return response.getBody().get("id").asLong();
            });
        }
        List<Future<Long>> futures = tasks.stream().map(pool::submit).toList();
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Future<Long> future : futures) {
            ids.add(future.get(20, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertEquals(1, ids.size(), "all concurrent requests must resolve to one job");
        assertEquals(1, jobCount(session, saleId, "ANDROID", "AUTO"));
    }

    @Test
    void permissionsAndAuthenticationAreEnforced() {
        Session owner = registerStore();

        // No token at all.
        ResponseEntity<JsonNode> anonymous = rest.exchange(
                "/api/printing/printers?branchId=" + owner.branchId(), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), anonymous.getStatusCode().value());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), rest.exchange(
                "/api/printing/printers/pairing-code?branchId=" + owner.branchId(),
                HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), JsonNode.class)
                .getStatusCode().value());

        // Branch managers can see printers but cannot configure them.
        Session manager = createUserSession(owner, "BRANCH_MANAGER", "Manager@123");
        call(HttpMethod.GET, "/api/printing/printers?branchId=" + owner.branchId(), null,
                manager.token(), HttpStatus.OK);
        ResponseEntity<JsonNode> managerCreate = rest.exchange("/api/printing/printers",
                HttpMethod.POST, entity(printerRequest(owner.branchId(), "Manager Printer",
                        "192.168.1.110", 9100, "MM80", "RECEIPT", "ACTIVE"), manager.token()),
                JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), managerCreate.getStatusCode().value());
        ResponseEntity<JsonNode> managerPairing = rest.exchange(
                "/api/printing/printers/pairing-code?branchId=" + owner.branchId(), HttpMethod.POST,
                bearerOnly(manager.token()), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), managerPairing.getStatusCode().value());

        // Staff do not even get read access to printer configuration.
        Session staff = createUserSession(owner, "BRANCH_STAFF", "Staff@123");
        ResponseEntity<JsonNode> staffList = rest.exchange(
                "/api/printing/printers?branchId=" + owner.branchId(), HttpMethod.GET,
                bearerOnly(staff.token()), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), staffList.getStatusCode().value());
    }

    // ---------- fixtures ----------

    private record Session(String token, long userId, long storeId, long branchId,
                           String storeCode, String username) {
    }

    private Session registerStore() {
        int seq = SEQ.incrementAndGet();
        String code = "P" + seq + Integer.toString((int) (System.currentTimeMillis() % 1296), 36)
                .toUpperCase();
        String username = "owner" + seq;
        call(HttpMethod.POST, "/api/auth/register-store",
                map("storeCode", code,
                        "storeName", "Printing Store " + code,
                        "ownerName", "Store Owner",
                        "email", "owner" + seq + "@test.local",
                        "mobile", "9000000003",
                        "address", "1 Test Street",
                        "gstNumber", null,
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

    private Session createUserSession(Session owner, String roleCode, String password) {
        int seq = SEQ.incrementAndGet();
        String username = "user" + seq;
        call(HttpMethod.POST, "/api/users",
                map("username", username,
                        "email", username + "@test.local",
                        "password", password,
                        "fullName", "User " + username,
                        "mobile", "9000000011",
                        "roleId", roleId(owner, roleCode),
                        "branchIds", List.of(owner.branchId())),
                owner.token(), HttpStatus.OK);
        return loginSession(owner.storeCode(), username, password);
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

    private long createBranch(Session session, String code) {
        return call(HttpMethod.POST, "/api/branches",
                map("branchCode", code, "branchName", "Branch " + code,
                        "address", "2 Branch Road", "contactNumber", "9000000004",
                        "status", "ACTIVE"), session.token(), HttpStatus.OK).get("id").asLong();
    }

    private JsonNode createPrinter(Session session, long branchId, String name, String ip,
                                   int port, String paperWidth, String purpose) {
        return call(HttpMethod.POST, "/api/printing/printers",
                printerRequest(branchId, name, ip, port, paperWidth, purpose, "ACTIVE"),
                session.token(), HttpStatus.OK);
    }

    private Map<String, Object> printerRequest(long branchId, String name, String ip, int port,
                                               String paperWidth, String purpose, String status) {
        return map("printerName", name, "ipAddress", ip, "port", port,
                "paperWidth", paperWidth, "purpose", purpose,
                "branchId", branchId, "status", status);
    }

    private void assertBadRequest(Session session, Map<String, Object> request) {
        ResponseEntity<JsonNode> response = rest.exchange("/api/printing/printers",
                HttpMethod.POST, entity(request, session.token()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value(),
                () -> "expected 400 but got " + response.getStatusCode() + " " + response.getBody());
    }

    private JsonNode pair(String code, String deviceName, String platform, HttpStatus expected) {
        return pairRequest(map("code", code, "deviceName", deviceName, "platform", platform),
                expected);
    }

    private JsonNode pairRequest(Object body, HttpStatus expected) {
        ResponseEntity<JsonNode> response = rest.exchange(PAIR_PATH, HttpMethod.POST,
                entity(body, null), JsonNode.class);
        assertEquals(expected.value(), response.getStatusCode().value(),
                () -> "pair expected " + expected + " but got " + response.getStatusCode()
                        + " " + response.getBody());
        return response.getBody();
    }

    private JsonNode deviceConfig(String token, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("X-Device-Token", token);
        }
        ResponseEntity<JsonNode> response = rest.exchange("/api/printing/device/config",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertEquals(expected.value(), response.getStatusCode().value(),
                () -> "device config expected " + expected + " but got "
                        + response.getStatusCode() + " " + response.getBody());
        return response.getBody();
    }

    private Map<String, Object> printJobRequest(long saleId, String channel, String trigger,
                                                Long printerConfigId) {
        Map<String, Object> body = map("documentType", "SALE_RECEIPT", "documentId", saleId,
                "channel", channel, "trigger", trigger);
        if (printerConfigId != null) {
            body.put("printerConfigId", printerConfigId);
        }
        return body;
    }

    private JsonNode printJob(Session session, long saleId, String channel, String trigger,
                              Long printerConfigId, HttpStatus expected) {
        return call(HttpMethod.POST, "/api/printing/jobs",
                printJobRequest(saleId, channel, trigger, printerConfigId),
                session.token(), expected);
    }

    private int jobCount(Session session, long saleId, String channel, String trigger) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM print_jobs WHERE store_id = ? AND document_id = ? "
                        + "AND channel = ? AND trigger_type = ?",
                Integer.class, session.storeId(), saleId, channel, trigger);
        return count == null ? 0 : count;
    }

    private long createSale(Session session) {
        int seq = SEQ.incrementAndGet();
        long categoryId = call(HttpMethod.POST, "/api/categories",
                map("categoryName", "Print Category " + seq, "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();
        JsonNode tax = exemptTax(session);
        JsonNode item = call(HttpMethod.POST, "/api/items",
                map("itemCode", "PRN" + seq, "itemName", "Printed Item " + seq,
                        "categoryId", categoryId, "taxId", tax.get("id").asLong(),
                        "baseUnit", "PIECE", "rate", new BigDecimal("20"),
                        "minStockLevel", new BigDecimal("1"), "status", "ACTIVE"),
                session.token(), HttpStatus.OK);
        long itemId = item.get("id").asLong();
        call(HttpMethod.POST, "/api/inventory/adjustments",
                map("branchId", session.branchId(), "itemId", itemId, "type", "IN",
                        "quantity", new BigDecimal("20"), "unit", "PIECE",
                        "reason", "Opening stock for print test"),
                session.token(), HttpStatus.OK);
        JsonNode sale = call(HttpMethod.POST, "/api/sales",
                map("branchId", session.branchId(), "paymentMethod", "CASH",
                        "paidAmount", new BigDecimal("40"),
                        "items", List.of(map("itemId", itemId, "quantity", new BigDecimal("2"),
                                "unit", "PIECE", "sellingRate", new BigDecimal("20"),
                                "discount", BigDecimal.ZERO))),
                session.token(), HttpStatus.OK);
        return sale.get("id").asLong();
    }

    private JsonNode exemptTax(Session session) {
        JsonNode taxes = call(HttpMethod.GET, "/api/taxes/options", null,
                session.token(), HttpStatus.OK);
        for (JsonNode tax : taxes) {
            if ("EXEMPT".equals(tax.get("taxType").asText())) {
                return tax;
            }
        }
        throw new IllegalStateException("Exempt tax was not provisioned for the store");
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
