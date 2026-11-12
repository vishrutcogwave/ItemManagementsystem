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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end supplier settlement tests against a real embedded PostgreSQL.
 * Covers partial payments, multi-bill allocation, idempotency, validation,
 * history, reports and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54335",
        "app.embedded-postgres.data-dir=build/pgdata-supplier-payment-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "logging.level.com.itemmanagement=WARN"
})
class SupplierPaymentIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    // ---------- core settlement flow ----------

    @Test
    void unpaidPurchaseBecomesPartialThenPaid() {
        Session session = registerStore();
        long supplierId = createSupplier(session, "ABC Automobiles");
        long bill = completedPurchase(session, supplierId, "2000", "0");

        assertOutstanding(session, supplierId, "2000", "0", "2000");
        JsonNode purchase = getPurchase(session, bill);
        assertEquals("UNPAID", purchase.get("paymentStatus").asText());
        assertMoney(purchase.get("paidAmount"), "0");
        assertMoney(purchase.get("balanceAmount"), "2000");

        JsonNode first = pay(session, supplierId, "1000", List.of(bill), "CASH", UUID.randomUUID().toString());
        assertEquals("SPAY", first.get("paymentNumber").asText().substring(0, 4));
        assertEquals(1, first.get("allocations").size());

        assertOutstanding(session, supplierId, "2000", "1000", "1000");
        purchase = getPurchase(session, bill);
        assertEquals("PARTIALLY_PAID", purchase.get("paymentStatus").asText());
        assertMoney(purchase.get("paidAmount"), "1000");
        assertMoney(purchase.get("balanceAmount"), "1000");

        pay(session, supplierId, "1000", List.of(bill), "UPI", UUID.randomUUID().toString());

        assertOutstanding(session, supplierId, "2000", "2000", "0");
        purchase = getPurchase(session, bill);
        assertEquals("PAID", purchase.get("paymentStatus").asText());
        assertMoney(purchase.get("paidAmount"), "2000");
        assertMoney(purchase.get("balanceAmount"), "0");

        JsonNode bills = call(HttpMethod.GET, "/api/supplier-payments/suppliers/" + supplierId + "/bills",
                null, session.token(), HttpStatus.OK);
        assertEquals(1, bills.size());
        assertEquals("PAID", bills.get(0).get("settlementStatus").asText());
    }

    @Test
    void onePaymentAllocatesOldestBillFirstAcrossBills() {
        Session session = registerStore();
        long supplierId = createSupplier(session, "Multi Bill Supplier");
        long older = completedPurchase(session, supplierId, "3000", "0", LocalDate.now().minusDays(2));
        long newer = completedPurchase(session, supplierId, "2000", "0", LocalDate.now().minusDays(1));

        JsonNode payment = pay(session, supplierId, "4000", List.of(newer, older), "BANK",
                UUID.randomUUID().toString());
        assertEquals(2, payment.get("allocations").size());
        // The request order is ignored: the oldest bill is settled first.
        assertMoney(payment.get("allocations").get(0).get("amount"), "3000");
        assertMoney(payment.get("allocations").get(1).get("amount"), "1000");

        assertEquals("PAID", getPurchase(session, older).get("paymentStatus").asText());
        assertEquals("PARTIALLY_PAID", getPurchase(session, newer).get("paymentStatus").asText());
        assertOutstanding(session, supplierId, "5000", "4000", "1000");
    }

    @Test
    void onePaymentCanSettleMultipleBillsCompletely() {
        Session session = registerStore();
        long supplierId = createSupplier(session, "Bulk Settle Supplier");
        long first = completedPurchase(session, supplierId, "1200", "0");
        long second = completedPurchase(session, supplierId, "800", "0");

        JsonNode payment = pay(session, supplierId, "2000", List.of(first, second), "CARD",
                UUID.randomUUID().toString());
        assertEquals(2, payment.get("allocations").size());
        assertEquals("PAID", getPurchase(session, first).get("paymentStatus").asText());
        assertEquals("PAID", getPurchase(session, second).get("paymentStatus").asText());
        assertOutstanding(session, supplierId, "2000", "2000", "0");
    }

    @Test
    void rateLessPurchaseUsesEnteredPayableAndKeepsItemQuantities() {
        Session session = registerStore();
        long supplierId = createSupplier(session, "Rate-less Supplier");
        long itemId = createItem(session, "Rate-less Item");

        JsonNode created = call(HttpMethod.POST, "/api/purchases",
                map("branchId", session.branchId(), "supplierId", supplierId,
                        "purchaseDate", LocalDate.now().toString(), "status", "COMPLETED",
                        "payableAmount", new BigDecimal("10000"),
                        "paidAmount", new BigDecimal("6000"),
                        "items", List.of(map("itemId", itemId,
                                "quantity", new BigDecimal("4"), "unit", "PIECE"))),
                session.token(), HttpStatus.OK);
        long bill = created.get("id").asLong();

        // The entered payable is the purchase amount; lines carry quantity only.
        assertMoney(created.get("grandTotal"), "10000");
        assertMoney(created.get("paidAmount"), "6000");
        assertMoney(created.get("balanceAmount"), "4000");
        assertEquals("PARTIALLY_PAID", created.get("paymentStatus").asText());
        assertMoney(created.get("items").get(0).get("purchaseRate"), "0");
        assertEquals(0, new BigDecimal("4").compareTo(
                created.get("items").get(0).get("quantity").decimalValue()));
        String itemCode = created.get("items").get(0).get("itemCode").asText();
        assertEquals(0, new BigDecimal("4").compareTo(stockOf(session, itemCode)));

        pay(session, supplierId, "4000", List.of(bill), "BANK", UUID.randomUUID().toString());

        JsonNode settled = getPurchase(session, bill);
        assertEquals("PAID", settled.get("paymentStatus").asText());
        assertMoney(settled.get("balanceAmount"), "0");
        assertOutstanding(session, supplierId, "10000", "10000", "0");
        // Settlement must not move stock again.
        assertEquals(0, new BigDecimal("4").compareTo(stockOf(session, itemCode)));
    }

    // ---------- validation and idempotency ----------

    @Test
    void rejectsInvalidPaymentAmountsAndBills() {
        Session session = registerStore();
        long supplierId = createSupplier(session, "Validation Supplier");
        long bill = completedPurchase(session, supplierId, "2000", "0");

        call(HttpMethod.POST, "/api/supplier-payments",
                paymentBody(supplierId, "0", List.of(bill), "CASH", null),
                session.token(), HttpStatus.BAD_REQUEST);
        call(HttpMethod.POST, "/api/supplier-payments",
                paymentBody(supplierId, "2000.01", List.of(bill), "CASH", null),
                session.token(), HttpStatus.BAD_REQUEST);
        call(HttpMethod.POST, "/api/supplier-payments",
                map("supplierId", supplierId, "paymentDate", LocalDate.now().toString(),
                        "paymentMethod", "CASH", "amount", new BigDecimal("100"),
                        "purchaseIds", List.of()),
                session.token(), HttpStatus.BAD_REQUEST);

        // A draft bill is not payable.
        long draft = call(HttpMethod.POST, "/api/purchases",
                purchaseBody(session, supplierId, createItem(session, "Draft Item"),
                        "500", "0", LocalDate.now(), "DRAFT"),
                session.token(), HttpStatus.OK).get("id").asLong();
        call(HttpMethod.POST, "/api/supplier-payments",
                paymentBody(supplierId, "100", List.of(draft), "CASH", null),
                session.token(), HttpStatus.BAD_REQUEST);

        // A bill from another supplier is rejected.
        long otherSupplier = createSupplier(session, "Other Supplier");
        call(HttpMethod.POST, "/api/supplier-payments",
                paymentBody(otherSupplier, "100", List.of(bill), "CASH", null),
                session.token(), HttpStatus.BAD_REQUEST);

        assertOutstanding(session, supplierId, "2000", "0", "2000");
    }

    @Test
    void fullyPaidBillCannotBePaidAgain() {
        Session session = registerStore();
        long supplierId = createSupplier(session, "Settled Supplier");
        long bill = completedPurchase(session, supplierId, "1000", "0");

        pay(session, supplierId, "1000", List.of(bill), "CASH", UUID.randomUUID().toString());
        call(HttpMethod.POST, "/api/supplier-payments",
                paymentBody(supplierId, "1", List.of(bill), "CASH", UUID.randomUUID().toString()),
                session.token(), HttpStatus.BAD_REQUEST);

        assertOutstanding(session, supplierId, "1000", "1000", "0");
        assertEquals(1, paymentHistory(session).size());
    }

    @Test
    void duplicateClientReferenceReturnsSamePaymentWithoutDoubleCounting() {
        Session session = registerStore();
        long supplierId = createSupplier(session, "Idempotent Supplier");
        long bill = completedPurchase(session, supplierId, "2000", "0");
        String reference = UUID.randomUUID().toString();

        JsonNode first = pay(session, supplierId, "500", List.of(bill), "CASH", reference);
        JsonNode second = pay(session, supplierId, "500", List.of(bill), "CASH", reference);

        assertEquals(first.get("id").asLong(), second.get("id").asLong());
        assertEquals(first.get("paymentNumber").asText(), second.get("paymentNumber").asText());
        assertOutstanding(session, supplierId, "2000", "500", "1500");
        assertEquals(1, paymentHistory(session).size());
    }

    // ---------- history and reports ----------

    @Test
    void paymentHistoryAndReportsReconcile() {
        Session session = registerStore();
        long supplierId = createSupplier(session, "Report Supplier");
        long bill = completedPurchase(session, supplierId, "5000", "0");
        JsonNode payment = pay(session, supplierId, "2000", List.of(bill), "BANK",
                UUID.randomUUID().toString());

        JsonNode history = paymentHistory(session);
        assertEquals(1, history.size());
        JsonNode row = history.get(0);
        assertEquals(payment.get("paymentNumber").asText(), row.get("paymentNumber").asText());
        assertEquals(supplierId, row.get("supplierId").asLong());
        assertEquals("BANK", row.get("paymentMethod").asText());
        assertMoney(row.get("amount"), "2000");
        assertEquals(1, row.get("allocations").size());
        assertEquals(bill, row.get("allocations").get(0).get("purchaseId").asLong());
        assertNotNull(row.get("createdByName"));

        JsonNode outstandingReport = call(HttpMethod.GET, "/api/reports/supplier-outstanding",
                null, session.token(), HttpStatus.OK);
        JsonNode reportRow = reportRowFor(outstandingReport.get("rows"), supplierId);
        assertMoney(reportRow.get("totalPurchaseAmount"), "5000");
        assertMoney(reportRow.get("totalPaid"), "2000");
        assertMoney(reportRow.get("outstandingAmount"), "3000");

        JsonNode billsReport = call(HttpMethod.GET, "/api/reports/supplier-bills?supplierId=" + supplierId,
                null, session.token(), HttpStatus.OK);
        assertEquals(1, billsReport.get("rows").size());
        assertMoney(billsReport.at("/totals/grandTotal"), "5000");
        assertMoney(billsReport.at("/totals/paidAmount"), "2000");
        assertMoney(billsReport.at("/totals/balanceAmount"), "3000");
        assertEquals("PARTIALLY_PAID", billsReport.at("/rows/0/settlementStatus").asText());

        JsonNode paymentsReport = call(HttpMethod.GET, "/api/reports/supplier-payments?supplierId=" + supplierId,
                null, session.token(), HttpStatus.OK);
        assertEquals(1, paymentsReport.get("rows").size());
        assertMoney(paymentsReport.at("/totals/amount"), "2000");
        assertEquals(1, paymentsReport.get("totals").get("payments").asInt());

        JsonNode ledger = call(HttpMethod.GET, "/api/reports/supplier-ledger?supplierId=" + supplierId,
                null, session.token(), HttpStatus.OK);
        assertMoney(ledger.get("openingBalance"), "0");
        assertEquals(2, ledger.get("rows").size());
        assertMoney(ledger.get("totalDebit"), "5000");
        assertMoney(ledger.get("totalCredit"), "2000");
        assertMoney(ledger.get("closingBalance"), "3000");
    }

    // ---------- compatibility and isolation ----------

    @Test
    void recordingPaymentsLeavesPurchaseAndStockIntact() {
        Session session = registerStore();
        long supplierId = createSupplier(session, "Stock Supplier");
        long itemId = createItem(session, "Stock Item");
        long bill = completedPurchase(session, supplierId, itemId, "200", "0", LocalDate.now(), "COMPLETED");

        JsonNode before = getPurchase(session, bill);
        BigDecimal stockBefore = stockOf(session, before.get("items").get(0).get("itemCode").asText());

        pay(session, supplierId, "80", List.of(bill), "CASH", UUID.randomUUID().toString());

        JsonNode after = getPurchase(session, bill);
        assertMoney(after.get("grandTotal"), before.get("grandTotal").asText());
        assertEquals("COMPLETED", after.get("status").asText());
        assertEquals("PARTIALLY_PAID", after.get("paymentStatus").asText());
        assertEquals(0, stockBefore.compareTo(
                stockOf(session, after.get("items").get(0).get("itemCode").asText())));
    }

    @Test
    void otherStoresCannotSeeOrSettlePayments() {
        Session a = registerStore();
        Session b = registerStore();
        long supplierA = createSupplier(a, "Tenant A Supplier");
        long billA = completedPurchase(a, supplierA, "1000", "0");
        JsonNode payment = pay(a, supplierA, "400", List.of(billA), "CASH", UUID.randomUUID().toString());

        long paymentId = payment.get("id").asLong();
        call(HttpMethod.GET, "/api/supplier-payments/" + paymentId, null, b.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.GET, "/api/supplier-payments/suppliers/" + supplierA + "/bills",
                null, b.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.POST, "/api/supplier-payments",
                paymentBody(supplierA, "100", List.of(billA), "CASH", null),
                b.token(), HttpStatus.BAD_REQUEST);

        JsonNode bOutstanding = call(HttpMethod.GET, "/api/supplier-payments/outstanding",
                null, b.token(), HttpStatus.OK);
        assertEquals(0, bOutstanding.get("content").size());
        assertEquals(0, paymentHistory(b).size());
    }

    // ---------- fixtures ----------

    private record Session(String token, long storeId, long branchId, String storeCode, String username) {
    }

    private Session registerStore() {
        int seq = SEQ.incrementAndGet();
        String code = "SP" + seq + Integer.toString((int) (System.currentTimeMillis() % 1296), 36).toUpperCase();
        String username = "owner" + seq;
        call(HttpMethod.POST, "/api/auth/register-store",
                map("storeCode", code, "storeName", "Supplier Pay Store " + code,
                        "ownerName", "Store Owner", "email", "sp" + seq + "@test.local",
                        "mobile", "9000000011", "address", "1 Payment Street", "gstNumber", null,
                        "state", "Karnataka", "username", username, "password", "Secret@123",
                        "branchName", "Main Branch"),
                null, HttpStatus.CREATED);
        JsonNode auth = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", code, "username", username, "password", "Secret@123"),
                null, HttpStatus.OK);
        return new Session(auth.get("token").asText(), auth.at("/store/id").asLong(),
                auth.at("/branches/0/id").asLong(), code, username);
    }

    private long createItem(Session session, String name) {
        long categoryId = call(HttpMethod.POST, "/api/categories",
                map("categoryName", name + " " + SEQ.incrementAndGet(), "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();
        JsonNode taxes = call(HttpMethod.GET, "/api/taxes/options", null, session.token(), HttpStatus.OK);
        long taxId = 0;
        for (JsonNode tax : taxes) {
            if ("EXEMPT".equals(tax.get("taxType").asText())) {
                taxId = tax.get("id").asLong();
                break;
            }
        }
        assertTrue(taxId > 0, "Exempt tax must be provisioned");
        String code = "SPI" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        JsonNode item = call(HttpMethod.POST, "/api/items",
                map("itemCode", code, "itemName", name + " " + SEQ.incrementAndGet(),
                        "categoryId", categoryId, "taxId", taxId, "baseUnit", "PIECE",
                        "rate", new BigDecimal("20"), "minStockLevel", new BigDecimal("1"),
                        "status", "ACTIVE"),
                session.token(), HttpStatus.OK);
        return item.get("id").asLong();
    }

    private long createSupplier(Session session, String name) {
        return call(HttpMethod.POST, "/api/suppliers",
                map("supplierName", name + " " + SEQ.incrementAndGet(), "mobile", "9000000099",
                        "address", "9 Vendor Road", "state", "Karnataka", "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();
    }

    private long completedPurchase(Session session, long supplierId, String amount, String paid) {
        return completedPurchase(session, supplierId, amount, paid, LocalDate.now());
    }

    private long completedPurchase(Session session, long supplierId, String amount, String paid,
                                   LocalDate date) {
        return completedPurchase(session, supplierId, createItem(session, "Purchase Item"),
                amount, paid, date, "COMPLETED");
    }

    private long completedPurchase(Session session, long supplierId, long itemId, String amount,
                                   String paid, LocalDate date, String status) {
        return call(HttpMethod.POST, "/api/purchases",
                purchaseBody(session, supplierId, itemId, amount, paid, date, status),
                session.token(), HttpStatus.OK).get("id").asLong();
    }

    private Map<String, Object> purchaseBody(Session session, long supplierId, long itemId,
                                             String amount, String paid, LocalDate date, String status) {
        return map("branchId", session.branchId(), "supplierId", supplierId,
                "invoiceNumber", "INV-" + SEQ.incrementAndGet(),
                "purchaseDate", date.toString(), "status", status,
                "payableAmount", new BigDecimal(amount),
                "paidAmount", new BigDecimal(paid),
                "items", List.of(map("itemId", itemId, "quantity", BigDecimal.ONE,
                        "unit", "PIECE", "purchaseRate", new BigDecimal(amount),
                        "discount", BigDecimal.ZERO)));
    }

    private Map<String, Object> paymentBody(long supplierId, String amount, List<Long> billIds,
                                            String method, String clientReference) {
        Map<String, Object> body = map("supplierId", supplierId,
                "paymentDate", LocalDate.now().toString(),
                "paymentMethod", method,
                "amount", new BigDecimal(amount),
                "purchaseIds", billIds);
        if (clientReference != null) {
            body.put("clientReference", clientReference);
        }
        return body;
    }

    private JsonNode pay(Session session, long supplierId, String amount, List<Long> billIds,
                         String method, String clientReference) {
        return call(HttpMethod.POST, "/api/supplier-payments",
                paymentBody(supplierId, amount, billIds, method, clientReference),
                session.token(), HttpStatus.OK);
    }

    private JsonNode getPurchase(Session session, long id) {
        return call(HttpMethod.GET, "/api/purchases/" + id, null, session.token(), HttpStatus.OK);
    }

    private JsonNode paymentHistory(Session session) {
        return call(HttpMethod.GET, "/api/supplier-payments?size=50", null, session.token(),
                HttpStatus.OK).get("content");
    }

    private void assertOutstanding(Session session, long supplierId, String purchase, String paid,
                                   String outstanding) {
        JsonNode page = call(HttpMethod.GET, "/api/supplier-payments/outstanding?size=50",
                null, session.token(), HttpStatus.OK);
        JsonNode row = reportRowFor(page.get("content"), supplierId);
        assertMoney(row.get("totalPurchaseAmount"), purchase);
        assertMoney(row.get("totalPaid"), paid);
        assertMoney(row.get("outstandingAmount"), outstanding);
    }

    private JsonNode reportRowFor(JsonNode rows, long supplierId) {
        for (JsonNode row : rows) {
            if (row.get("supplierId").asLong() == supplierId) {
                return row;
            }
        }
        throw new AssertionError("Supplier " + supplierId + " not found in report");
    }

    private BigDecimal stockOf(Session session, String itemCode) {
        JsonNode page = call(HttpMethod.GET,
                "/api/inventory?branchId=" + session.branchId() + "&search=" + itemCode + "&size=5",
                null, session.token(), HttpStatus.OK);
        JsonNode content = page.get("content");
        return content.isEmpty() ? BigDecimal.ZERO : content.get(0).get("currentStock").decimalValue();
    }

    private void assertMoney(JsonNode node, String expected) {
        assertNotNull(node, "expected an amount but the field was missing");
        assertEquals(0, new BigDecimal(node.asText()).compareTo(new BigDecimal(expected)),
                () -> "expected " + expected + " but was " + node.asText());
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
