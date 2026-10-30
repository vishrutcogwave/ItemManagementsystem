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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end HTTP tests against a real embedded PostgreSQL instance.
 * Verifies multi-tenant isolation, authorization, inventory invariants and atomicity.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54330",
        "app.embedded-postgres.data-dir=build/pgdata-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "logging.level.com.itemmanagement=WARN"
})
class SystemInvariantIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    // ---------- authentication ----------

    @Test
    void rejectsMissingAndInvalidTokens() {
        call(HttpMethod.GET, "/api/items", null, null, HttpStatus.UNAUTHORIZED);
        call(HttpMethod.GET, "/api/items", null, "not-a-jwt", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsWrongPassword() {
        Session session = registerStore();
        JsonNode error = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", session.storeCode(), "username", session.username(), "password", "WrongPass@1"),
                null, HttpStatus.UNAUTHORIZED);
        assertNotNull(error.get("message"));
    }

    // ---------- tenant isolation ----------

    @Test
    void storeCannotReadOrUseAnotherStoresData() {
        Session a = registerStore();
        Session b = registerStore();

        long categoryId = createCategory(a, "Shared");
        ItemRef item = createItem(a, categoryId, "A");
        long supplierA = createSupplier(a, "A");
        long purchaseId = purchase(a, supplierA, item.id(), "4", "COMPLETED").get("id").asLong();
        long saleId = sale(a, item.id(), "1").get("id").asLong();

        // B cannot see A's master data
        call(HttpMethod.GET, "/api/items/" + item.id(), null, b.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.PUT, "/api/items/" + item.id(), itemBody(b, categoryId, "HACK"), b.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.GET, "/api/suppliers/" + supplierA, null, b.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.GET, "/api/branches/" + a.branchId(), null, b.token(), HttpStatus.NOT_FOUND);

        JsonNode items = call(HttpMethod.GET, "/api/items?search=" + item.code(), null, b.token(), HttpStatus.OK);
        assertEquals(0, items.get("content").size());

        // B cannot attach A's item to its own purchase
        long supplierB = createSupplier(b, "B");
        call(HttpMethod.POST, "/api/purchases",
                purchaseBody(b, supplierB, item.id(), "1", "COMPLETED"),
                b.token(), HttpStatus.BAD_REQUEST);

        // B cannot read A's transactions
        call(HttpMethod.GET, "/api/purchases/" + purchaseId, null, b.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.GET, "/api/sales/" + saleId, null, b.token(), HttpStatus.NOT_FOUND);
    }

    @Test
    void platformAdminCannotReadStoreDataButCanManageStores() {
        Session store = registerStore();
        String platformToken = login("PLATFORM", "platform", "Admin@123");

        call(HttpMethod.GET, "/api/items", null, platformToken, HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/dashboard", null, platformToken, HttpStatus.FORBIDDEN);

        JsonNode stores = call(HttpMethod.GET, "/api/stores?search=" + store.storeCode(),
                null, platformToken, HttpStatus.OK);
        assertTrue(stores.get("content").size() >= 1);

        JsonNode created = call(HttpMethod.POST, "/api/stores", registerBody(), platformToken, HttpStatus.OK);
        assertNotNull(created.get("id"));
    }

    // ---------- purchases ----------

    @Test
    void purchaseLifecycleUpdatesStockAtomically() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Grocery");
        ItemRef item = createItem(session, categoryId, "P");
        long supplierId = createSupplier(session, "P");

        assertEquals(0, stockOf(session, item.code()).compareTo(BigDecimal.ZERO));

        JsonNode draft = purchase(session, supplierId, item.id(), "10", "DRAFT");
        assertEquals("DRAFT", draft.get("status").asText());
        assertEquals(0, stockOf(session, item.code()).compareTo(BigDecimal.ZERO));

        long purchaseId = draft.get("id").asLong();
        call(HttpMethod.POST, "/api/purchases/" + purchaseId + "/complete", null, session.token(), HttpStatus.OK);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("10")));

        call(HttpMethod.POST, "/api/purchases/" + purchaseId + "/cancel", null, session.token(), HttpStatus.OK);
        assertEquals(0, stockOf(session, item.code()).compareTo(BigDecimal.ZERO));

        call(HttpMethod.POST, "/api/purchases/" + purchaseId + "/cancel", null, session.token(), HttpStatus.BAD_REQUEST);
        assertEquals(0, stockOf(session, item.code()).compareTo(BigDecimal.ZERO));
    }

    @Test
    void purchaseCannotBeCancelledOnceStockIsSold() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Grocery");
        ItemRef item = createItem(session, categoryId, "S");
        long supplierId = createSupplier(session, "S");

        long purchaseId = purchase(session, supplierId, item.id(), "2", "COMPLETED").get("id").asLong();
        sale(session, item.id(), "2");
        assertEquals(0, stockOf(session, item.code()).compareTo(BigDecimal.ZERO));

        call(HttpMethod.POST, "/api/purchases/" + purchaseId + "/cancel", null, session.token(), HttpStatus.BAD_REQUEST);
        assertEquals(0, stockOf(session, item.code()).compareTo(BigDecimal.ZERO));
    }

    // ---------- sales ----------

    @Test
    void saleDecrementsStockAndBlocksOverselling() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Dairy");
        ItemRef item = createItem(session, categoryId, "L");
        long supplierId = createSupplier(session, "L");
        purchase(session, supplierId, item.id(), "5", "COMPLETED");

        JsonNode sale = sale(session, item.id(), "4");
        assertEquals(0, new BigDecimal(sale.get("grandTotal").asText()).compareTo(new BigDecimal("80")));
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("1")));

        JsonNode rejected = call(HttpMethod.POST, "/api/sales", saleBody(session, item.id(), "2"),
                session.token(), HttpStatus.BAD_REQUEST);
        assertTrue(rejected.get("message").asText().toLowerCase().contains("insufficient stock"));
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("1")));

        long saleId = sale.get("id").asLong();
        call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel", null, session.token(), HttpStatus.OK);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("5")));
        call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel", null, session.token(), HttpStatus.BAD_REQUEST);
    }

    @Test
    void concurrentSalesCannotOversellStock() throws Exception {
        Session session = registerStore();
        long categoryId = createCategory(session, "Dairy");
        ItemRef item = createItem(session, categoryId, "C");
        long supplierId = createSupplier(session, "C");
        purchase(session, supplierId, item.id(), "1", "COMPLETED");

        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                ResponseEntity<JsonNode> response = rest.exchange("/api/sales", HttpMethod.POST,
                        entity(saleBody(session, item.id(), "1"), session.token()), JsonNode.class);
                return response.getStatusCode().value();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int successes = 0;
        int rejected = 0;
        for (Future<Integer> result : results) {
            int status = result.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                successes++;
            } else if (status == 400) {
                rejected++;
            }
        }
        pool.shutdown();

        assertEquals(1, successes, "exactly one sale may succeed");
        assertEquals(attempts - 1, rejected, "the rest must be rejected");
        assertEquals(0, stockOf(session, item.code()).compareTo(BigDecimal.ZERO));
    }

    @Test
    void rejectsNegativeAdjustmentsAndDuplicateLines() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Misc");
        ItemRef item = createItem(session, categoryId, "N");
        long supplierId = createSupplier(session, "N");
        purchase(session, supplierId, item.id(), "3", "COMPLETED");

        call(HttpMethod.POST, "/api/inventory/adjustments",
                map("branchId", session.branchId(), "itemId", item.id(), "type", "OUT",
                        "quantity", new BigDecimal("4"), "unit", "PIECE", "reason", "Spoilage"),
                session.token(), HttpStatus.BAD_REQUEST);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("3")));

        Map<String, Object> line = saleLine(item.id(), "1");
        call(HttpMethod.POST, "/api/sales",
                map("branchId", session.branchId(), "paymentMethod", "CASH", "items", List.of(line, line)),
                session.token(), HttpStatus.BAD_REQUEST);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("3")));
    }

    @Test
    void rejectsPaymentAboveGrandTotal() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Misc");
        ItemRef item = createItem(session, categoryId, "Y");

        call(HttpMethod.POST, "/api/sales",
                map("branchId", session.branchId(), "paymentMethod", "CASH",
                        "paidAmount", new BigDecimal("999"),
                        "items", List.of(saleLine(item.id(), "1"))),
                session.token(), HttpStatus.BAD_REQUEST);
    }

    // ---------- stock transfers ----------

    @Test
    void transferMovesStockOnlyAfterDispatchAndReceive() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Bulk");
        ItemRef item = createItem(session, categoryId, "T");
        long supplierId = createSupplier(session, "T");
        long destination = createBranch(session, "BR2");
        purchase(session, supplierId, item.id(), "3", "COMPLETED");

        long tooLargeId = transfer(session, destination, item.id(), "4").get("id").asLong();
        call(HttpMethod.POST, "/api/transfers/" + tooLargeId + "/approve", null, session.token(), HttpStatus.OK);
        call(HttpMethod.POST, "/api/transfers/" + tooLargeId + "/dispatch", null, session.token(), HttpStatus.BAD_REQUEST);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("3")));

        long transferId = transfer(session, destination, item.id(), "2").get("id").asLong();
        call(HttpMethod.POST, "/api/transfers/" + transferId + "/approve", null, session.token(), HttpStatus.OK);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("3")));
        call(HttpMethod.POST, "/api/transfers/" + transferId + "/dispatch", null, session.token(), HttpStatus.OK);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("1")));
        call(HttpMethod.POST, "/api/transfers/" + transferId + "/receive", null, session.token(), HttpStatus.OK);
        assertEquals(0, stockAt(session, destination, item.code()).compareTo(new BigDecimal("2")));
        call(HttpMethod.POST, "/api/transfers/" + transferId + "/receive", null, session.token(), HttpStatus.BAD_REQUEST);
    }

    // ---------- authorization ----------

    @Test
    void branchStaffIsLimitedToAssignedBranchAndPermissions() {
        Session owner = registerStore();
        long categoryId = createCategory(owner, "Ops");
        ItemRef item = createItem(owner, categoryId, "O");
        long supplierId = createSupplier(owner, "O");
        purchase(owner, supplierId, item.id(), "2", "COMPLETED");
        long otherBranch = createBranch(owner, "BR2");

        long staffRoleId = roleId(owner, "BRANCH_STAFF");
        JsonNode staff = call(HttpMethod.POST, "/api/users",
                map("username", "staff" + SEQ.get(), "email", "staff" + SEQ.get() + "@test.local",
                        "password", "Staff@123", "fullName", "Branch Staff",
                        "mobile", "9000000002", "roleId", staffRoleId, "branchIds", List.of(owner.branchId())),
                owner.token(), HttpStatus.OK);
        Session staffSession = loginSession(owner.storeCode(), staff.get("username").asText(), "Staff@123");

        call(HttpMethod.GET, "/api/items", null, staffSession.token(), HttpStatus.OK);
        call(HttpMethod.POST, "/api/items", itemBody(owner, categoryId, "STAFF"), staffSession.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/reports/sales", null, staffSession.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/users", null, staffSession.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/branches/all", null, staffSession.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.GET, "/api/inventory?branchId=" + otherBranch, null, staffSession.token(), HttpStatus.FORBIDDEN);

        // Staff can bill stock at their own branch
        call(HttpMethod.POST, "/api/sales", saleBody(staffSession, item.id(), "1"), staffSession.token(), HttpStatus.OK);
    }

    @Test
    void deactivationImmediatelyRevokesExistingTokens() {
        Session owner = registerStore();
        String platformToken = login("PLATFORM", "platform", "Admin@123");

        JsonNode store = call(HttpMethod.GET, "/api/stores?search=" + owner.storeCode(),
                null, platformToken, HttpStatus.OK).get("content").get(0);
        long storeId = store.get("id").asLong();

        call(HttpMethod.PUT, "/api/stores/" + storeId + "/status", map("status", "INACTIVE"),
                platformToken, HttpStatus.OK);
        call(HttpMethod.GET, "/api/items", null, owner.token(), HttpStatus.UNAUTHORIZED);
        call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", owner.storeCode(), "username", owner.username(), "password", "Secret@123"),
                null, HttpStatus.FORBIDDEN);

        call(HttpMethod.PUT, "/api/stores/" + storeId + "/status", map("status", "ACTIVE"),
                platformToken, HttpStatus.OK);
        Session reactivated = loginSession(owner.storeCode(), owner.username(), "Secret@123");
        call(HttpMethod.GET, "/api/items", null, reactivated.token(), HttpStatus.OK);

        long managerRoleId = roleId(reactivated, "BRANCH_MANAGER");
        JsonNode manager = call(HttpMethod.POST, "/api/users",
                map("username", "mgr" + SEQ.get(), "email", "mgr" + SEQ.get() + "@test.local",
                        "password", "Manager@123", "fullName", "Manager One",
                        "roleId", managerRoleId, "branchIds", List.of(reactivated.branchId())),
                reactivated.token(), HttpStatus.OK);
        Session managerSession = loginSession(reactivated.storeCode(), manager.get("username").asText(), "Manager@123");
        call(HttpMethod.GET, "/api/items", null, managerSession.token(), HttpStatus.OK);

        call(HttpMethod.PUT, "/api/users/" + manager.get("id").asLong() + "/status",
                map("status", "INACTIVE"), reactivated.token(), HttpStatus.OK);
        call(HttpMethod.GET, "/api/items", null, managerSession.token(), HttpStatus.UNAUTHORIZED);
    }

    // ---------- tax master and GST ----------

    @Test
    void taxMasterIsStoreSpecificAndProtectedWhenUsed() {
        Session a = registerStore();
        Session b = registerStore();

        JsonNode options = call(HttpMethod.GET, "/api/taxes/options", null, a.token(), HttpStatus.OK);
        assertEquals(5, options.size());

        long gst5 = taxIdByRate(a, "5");
        call(HttpMethod.GET, "/api/taxes/" + gst5, null, b.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.PUT, "/api/taxes/" + gst5,
                map("taxName", "Hacked", "gstPercentage", new BigDecimal("99")),
                b.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.DELETE, "/api/taxes/" + gst5, null, b.token(), HttpStatus.NOT_FOUND);

        long categoryId = createCategory(a, "Taxed");
        createItem(a, categoryId, "TAX", gst5);
        call(HttpMethod.DELETE, "/api/taxes/" + gst5, null, a.token(), HttpStatus.BAD_REQUEST);

        JsonNode created = call(HttpMethod.POST, "/api/taxes",
                map("taxName", "Special 7%", "description", "Custom slab",
                        "gstPercentage", new BigDecimal("7"), "taxType", "GST", "status", "ACTIVE"),
                a.token(), HttpStatus.OK);
        assertMoney(created.get("cgstPercentage"), "3.50");
        assertMoney(created.get("sgstPercentage"), "3.50");
        assertMoney(created.get("igstPercentage"), "7.00");
        long unusedId = created.get("id").asLong();
        call(HttpMethod.DELETE, "/api/taxes/" + unusedId, null, a.token(), HttpStatus.OK);
        call(HttpMethod.GET, "/api/taxes/" + unusedId, null, a.token(), HttpStatus.NOT_FOUND);
    }

    @Test
    void gstIsCalculatedOnServerAndSnapshotsStayImmutable() {
        Session session = registerStore();
        long gst5 = taxIdByRate(session, "5");
        long categoryId = createCategory(session, "GST");
        ItemRef item = createItem(session, categoryId, "GST", gst5);
        long supplier = createSupplier(session, "Intra", "Karnataka");

        JsonNode purchase = purchase(session, supplier, item.id(), "10", "COMPLETED");
        assertMoney(purchase.get("taxableAmount"), "100.00");
        assertMoney(purchase.get("cgstAmount"), "2.50");
        assertMoney(purchase.get("sgstAmount"), "2.50");
        assertMoney(purchase.get("igstAmount"), "0.00");
        assertMoney(purchase.get("tax"), "5.00");
        assertMoney(purchase.get("grandTotal"), "105.00");
        assertEquals("GST 5%", purchase.at("/items/0/taxName").asText());
        assertMoney(purchase.at("/items/0/gstPercentage"), "5.00");

        JsonNode sale = sale(session, item.id(), "4");
        assertMoney(sale.get("taxableAmount"), "80.00");
        assertMoney(sale.get("cgstAmount"), "2.00");
        assertMoney(sale.get("sgstAmount"), "2.00");
        assertMoney(sale.get("igstAmount"), "0.00");
        assertMoney(sale.get("tax"), "4.00");
        assertMoney(sale.get("grandTotal"), "84.00");
        long saleId = sale.get("id").asLong();

        long interSupplier = createSupplier(session, "Inter", "Tamil Nadu");
        JsonNode interPurchase = purchase(session, interSupplier, item.id(), "10", "COMPLETED");
        assertMoney(interPurchase.get("cgstAmount"), "0.00");
        assertMoney(interPurchase.get("sgstAmount"), "0.00");
        assertMoney(interPurchase.get("igstAmount"), "5.00");
        assertMoney(interPurchase.get("tax"), "5.00");
        assertMoney(interPurchase.get("grandTotal"), "105.00");

        call(HttpMethod.PUT, "/api/taxes/" + gst5,
                map("taxName", "GST 5%", "description", "Now 18%",
                        "gstPercentage", new BigDecimal("18"), "taxType", "GST", "status", "ACTIVE"),
                session.token(), HttpStatus.OK);

        JsonNode rereadSale = call(HttpMethod.GET, "/api/sales/" + saleId, null, session.token(),
                HttpStatus.OK);
        assertMoney(rereadSale.get("tax"), "4.00");
        assertMoney(rereadSale.get("taxableAmount"), "80.00");
        assertMoney(rereadSale.at("/items/0/gstPercentage"), "5.00");
        assertMoney(rereadSale.at("/items/0/taxableAmount"), "80.00");
        assertEquals("GST 5%", rereadSale.at("/items/0/taxName").asText());

        JsonNode report = call(HttpMethod.GET, "/api/reports/gst?from=" + LocalDate.now()
                        + "&to=" + LocalDate.now(), null, session.token(), HttpStatus.OK);
        JsonNode row = report.get("rows").get(0);
        assertMoney(row.get("gstPercentage"), "5.00");
        assertMoney(row.get("taxableAmount"), "80.00");
        assertMoney(row.get("cgstAmount"), "2.00");
        assertMoney(row.get("sgstAmount"), "2.00");
        assertMoney(row.get("taxAmount"), "4.00");
        assertMoney(report.at("/totals/taxAmount"), "4.00");

        JsonNode filtered = call(HttpMethod.GET, "/api/reports/gst?from=" + LocalDate.now()
                        + "&to=" + LocalDate.now() + "&taxRate=18", null, session.token(),
                HttpStatus.OK);
        assertEquals(0, filtered.get("rows").size());
    }

    @Test
    void inclusiveTaxSettingBackCalculatesTaxableAmount() {
        Session session = registerStore();
        long gst18 = taxIdByRate(session, "18");
        long categoryId = createCategory(session, "Inclusive");
        ItemRef item = createItem(session, categoryId, "INC", gst18);
        long supplier = createSupplier(session, "Inclusive", "Karnataka");
        purchase(session, supplier, item.id(), "5", "COMPLETED");

        call(HttpMethod.PUT, "/api/stores/current",
                map("storeName", "Inclusive Store", "ownerName", "Store Owner",
                        "email", "inclusive@test.local", "mobile", "9000000003",
                        "address", "1 Test Street", "gstNumber", null, "taxInclusive", true),
                session.token(), HttpStatus.OK);

        JsonNode sale = call(HttpMethod.POST, "/api/sales",
                saleBody(session, item.id(), "1", "100"), session.token(), HttpStatus.OK);
        assertMoney(sale.get("taxableAmount"), "84.75");
        assertMoney(sale.get("tax"), "15.25");
        assertMoney(sale.get("cgstAmount"), "7.63");
        assertMoney(sale.get("sgstAmount"), "7.62");
        assertMoney(sale.get("grandTotal"), "100.00");
        assertMoney(sale.at("/items/0/taxableAmount"), "84.75");
        assertMoney(sale.at("/items/0/totalAmount"), "100.00");
    }

    // ---------- fixtures ----------

    private record Session(String token, long storeId, long branchId, String storeCode, String username) {
    }

    private record ItemRef(long id, String code) {
    }

    private Session registerStore() {
        int seq = SEQ.incrementAndGet();
        String code = "T" + seq + Integer.toString((int) (System.currentTimeMillis() % 1296), 36).toUpperCase();
        String username = "owner" + seq;
        call(HttpMethod.POST, "/api/auth/register-store",
                registerBodyWith(code, "Test Store " + code, "owner" + seq + "@test.local", username), null,
                HttpStatus.CREATED);
        return loginWithUsername(code, username);
    }

    private Map<String, Object> registerBody() {
        int seq = SEQ.incrementAndGet();
        String code = "N" + seq + Integer.toString((int) (System.currentTimeMillis() % 1296), 36).toUpperCase();
        return registerBodyWith(code, "New Store " + code, "new" + seq + "@test.local", "newowner" + seq);
    }

    private Map<String, Object> registerBodyWith(String code, String name, String email, String username) {
        return map(
                "storeCode", code,
                "storeName", name,
                "ownerName", "Store Owner",
                "email", email,
                "mobile", "9000000003",
                "address", "1 Test Street",
                "gstNumber", null,
                "state", "Karnataka",
                "username", username,
                "password", "Secret@123",
                "branchName", "Main Branch");
    }

    private Session loginWithUsername(String storeCode, String username) {
        JsonNode auth = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", storeCode, "username", username, "password", "Secret@123"),
                null, HttpStatus.OK);
        return new Session(auth.get("token").asText(), auth.at("/store/id").asLong(),
                auth.at("/branches/0/id").asLong(), storeCode, username);
    }

    private String login(String storeCode, String username, String password) {
        return call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", storeCode, "username", username, "password", password),
                null, HttpStatus.OK).get("token").asText();
    }

    private Session loginSession(String storeCode, String username, String password) {
        JsonNode auth = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", storeCode, "username", username, "password", password),
                null, HttpStatus.OK);
        return new Session(auth.get("token").asText(), auth.at("/store/id").asLong(),
                auth.at("/branches/0/id").asLong(), storeCode, username);
    }

    private long createCategory(Session session, String name) {
        return call(HttpMethod.POST, "/api/categories",
                map("categoryName", name + " " + SEQ.incrementAndGet(), "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();
    }

    private ItemRef createItem(Session session, long categoryId, String prefix) {
        return createItem(session, categoryId, prefix, exemptTaxId(session));
    }

    private ItemRef createItem(Session session, long categoryId, String prefix, long taxId) {
        JsonNode item = call(HttpMethod.POST, "/api/items", itemBody(session, categoryId, prefix, taxId),
                session.token(), HttpStatus.OK);
        return new ItemRef(item.get("id").asLong(), item.get("itemCode").asText());
    }

    private Map<String, Object> itemBody(Session session, long categoryId, String prefix) {
        return itemBody(session, categoryId, prefix, exemptTaxId(session));
    }

    private Map<String, Object> itemBody(Session session, long categoryId, String prefix, long taxId) {
        String code = "IT" + prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return map("itemCode", code, "itemName", "Item " + prefix + " " + SEQ.incrementAndGet(),
                "categoryId", categoryId, "taxId", taxId, "baseUnit", "PIECE",
                "rate", new BigDecimal("20"),
                "minStockLevel", new BigDecimal("1"), "status", "ACTIVE");
    }

    private long exemptTaxId(Session session) {
        JsonNode taxes = call(HttpMethod.GET, "/api/taxes/options", null, session.token(), HttpStatus.OK);
        for (JsonNode tax : taxes) {
            if ("EXEMPT".equals(tax.get("taxType").asText())) {
                return tax.get("id").asLong();
            }
        }
        throw new IllegalStateException("Exempt tax was not provisioned for the store");
    }

    private long taxIdByRate(Session session, String rate) {
        JsonNode taxes = call(HttpMethod.GET, "/api/taxes/options", null, session.token(), HttpStatus.OK);
        for (JsonNode tax : taxes) {
            if (new BigDecimal(tax.get("gstPercentage").asText()).compareTo(new BigDecimal(rate)) == 0) {
                return tax.get("id").asLong();
            }
        }
        throw new IllegalStateException("Tax rate not found: " + rate);
    }

    private long createSupplier(Session session, String prefix) {
        return createSupplier(session, prefix, "Karnataka");
    }

    private long createSupplier(Session session, String prefix, String state) {
        return call(HttpMethod.POST, "/api/suppliers",
                map("supplierName", "Supplier " + prefix + SEQ.incrementAndGet(),
                        "mobile", "9000000099", "address", "9 Vendor Road",
                        "state", state, "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();
    }

    private long createBranch(Session session, String code) {
        return call(HttpMethod.POST, "/api/branches",
                map("branchCode", code, "branchName", "Branch " + code,
                        "address", "2 Branch Road", "contactNumber", "9000000004", "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();
    }

    private long roleId(Session session, String roleCode) {
        JsonNode roles = call(HttpMethod.GET, "/api/users/roles/available", null, session.token(), HttpStatus.OK);
        for (JsonNode role : roles) {
            if (roleCode.equals(role.get("code").asText())) {
                return role.get("id").asLong();
            }
        }
        throw new IllegalStateException("Role not found: " + roleCode);
    }

    private JsonNode purchase(Session session, long supplierId, long itemId, String quantity, String status) {
        return call(HttpMethod.POST, "/api/purchases",
                purchaseBody(session, supplierId, itemId, quantity, status), session.token(), HttpStatus.OK);
    }

    private Map<String, Object> purchaseBody(Session session, long supplierId, long itemId,
                                             String quantity, String status) {
        return map("branchId", session.branchId(), "supplierId", supplierId,
                "invoiceNumber", "INV-" + SEQ.incrementAndGet(),
                "purchaseDate", LocalDate.now().toString(),
                "status", status,
                "items", List.of(map("itemId", itemId, "quantity", new BigDecimal(quantity),
                        "unit", "PIECE", "purchaseRate", new BigDecimal("10"),
                        "discount", BigDecimal.ZERO)));
    }

    private JsonNode sale(Session session, long itemId, String quantity) {
        return call(HttpMethod.POST, "/api/sales", saleBody(session, itemId, quantity),
                session.token(), HttpStatus.OK);
    }

    private Map<String, Object> saleBody(Session session, long itemId, String quantity) {
        return saleBody(session, itemId, quantity, "20");
    }

    private Map<String, Object> saleBody(Session session, long itemId, String quantity, String rate) {
        return map("branchId", session.branchId(), "paymentMethod", "CASH",
                "items", List.of(saleLine(itemId, quantity, rate)));
    }

    private Map<String, Object> saleLine(long itemId, String quantity) {
        return saleLine(itemId, quantity, "20");
    }

    private Map<String, Object> saleLine(long itemId, String quantity, String rate) {
        return map("itemId", itemId, "quantity", new BigDecimal(quantity), "unit", "PIECE",
                "sellingRate", new BigDecimal(rate), "discount", BigDecimal.ZERO);
    }

    private JsonNode transfer(Session session, long toBranchId, long itemId, String quantity) {
        return call(HttpMethod.POST, "/api/transfers",
                map("fromBranchId", session.branchId(), "toBranchId", toBranchId,
                        "transferDate", LocalDate.now().toString(),
                        "items", List.of(map("itemId", itemId, "quantity", new BigDecimal(quantity),
                                "unit", "PIECE"))),
                session.token(), HttpStatus.OK);
    }

    private void assertMoney(JsonNode node, String expected) {
        assertNotNull(node, "expected an amount but the field was missing");
        assertEquals(0, new BigDecimal(node.asText()).compareTo(new BigDecimal(expected)),
                () -> "expected " + expected + " but was " + node.asText());
    }

    private BigDecimal stockOf(Session session, String itemCode) {
        return stockAt(session, session.branchId(), itemCode);
    }

    private BigDecimal stockAt(Session session, long branchId, String itemCode) {
        JsonNode page = call(HttpMethod.GET,
                "/api/inventory?branchId=" + branchId + "&search=" + itemCode + "&size=5",
                null, session.token(), HttpStatus.OK);
        JsonNode content = page.get("content");
        return content.isEmpty() ? BigDecimal.ZERO : content.get(0).get("currentStock").decimalValue();
    }

    // ---------- HTTP plumbing ----------

    private JsonNode call(HttpMethod method, String path, Object body, String token, HttpStatus expected) {
        ResponseEntity<JsonNode> response = rest.exchange(path, method, entity(body, token), JsonNode.class);
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