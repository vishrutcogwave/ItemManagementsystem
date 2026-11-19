package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.service.NumberGenerator;
import com.itemmanagement.service.StoreProvisioningService;
import com.itemmanagement.util.DocumentNumberOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
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
 * End-to-end HTTP tests for bill modification and cancellation against a real
 * embedded PostgreSQL instance. Shares the application context with
 * {@link SystemInvariantIntegrationTest} by using identical test properties.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54330",
        "app.embedded-postgres.data-dir=build/pgdata-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "logging.level.com.itemmanagement=WARN"
})
class BillModificationCancellationIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StoreProvisioningService provisioningService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NumberGenerator numberGenerator;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void salesKeepNumericAndHistoricalReferencesSearchableAcrossStores() {
        Session store = registerStore();
        Session other = registerStore();
        long category = createCategory(store, "Document numbers");
        ItemRef item = createItem(store, category, "NUMBER", taxIdByRate(store, "5"));
        JsonNode purchase = purchase(store, createSupplier(store, "NUMBER"), item.id(), "10", "COMPLETED");
        assertTrue(purchase.get("purchaseNumber").asText().matches("[1-9][0-9]*"));
        JsonNode historical = sale(store, item.id(), "1");
        jdbc.update("UPDATE sales SET sales_number = ? WHERE id = ?", "SAL-1109", historical.get("id").asLong());
        JsonNode current = sale(store, item.id(), "1");
        String number = current.get("salesNumber").asText();
        assertTrue(number.matches("[1-9][0-9]*"));
        assertEquals(number, jdbc.queryForObject("SELECT sales_number FROM sales WHERE id = ?",
                String.class, current.get("id").asLong()));
        for (String reference : List.of("SAL-1109", number)) {
            JsonNode found = call(HttpMethod.GET, "/api/sales?search=" + reference, null, store.token(), HttpStatus.OK);
            assertEquals(reference, found.at("/content/0/salesNumber").asText());
            JsonNode isolated = call(HttpMethod.GET, "/api/sales?search=" + reference, null, other.token(), HttpStatus.OK);
            assertEquals(0, isolated.get("totalElements").asInt());
        }
        JsonNode movements = call(HttpMethod.GET, "/api/inventory/transactions?search=" + number,
                null, store.token(), HttpStatus.OK);
        assertTrue(java.util.stream.StreamSupport.stream(movements.get("content").spliterator(), false)
                .anyMatch(row -> number.equals(row.get("referenceNumber").asText())
                        && "SALE_OUT".equals(row.get("transactionType").asText())));
    }

    @Test
    void concurrentPosAndRegularSalesUseDistinctPersistedNumbers() throws Exception {
        Session store = registerStore();
        ItemRef item = createItem(store, createCategory(store, "Concurrent numbers"), "SEQ", taxIdByRate(store, "5"));
        purchase(store, createSupplier(store, "SEQ"), item.id(), "10", "COMPLETED");
        var pos = map("branchId", store.branchId(), "pos", true,
                "items", List.of(modificationLine(item.id(), "1", "20", "0")),
                "payments", List.of(map("method", "CASH", "amount", 10), map("method", "UPI", "amount", 11)));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> regular = pool.submit(() -> {
                start.await();
                return sale(store, item.id(), "1");
            });
            Future<JsonNode> split = pool.submit(() -> {
                start.await();
                return call(HttpMethod.POST, "/api/sales/split", pos, store.token(), HttpStatus.OK);
            });
            start.countDown();
            JsonNode first = regular.get(15, TimeUnit.SECONDS);
            JsonNode second = split.get(15, TimeUnit.SECONDS);
            String regularNumber = first.get("salesNumber").asText();
            String posNumber = second.get("salesNumber").asText();
            assertTrue(regularNumber.matches("[1-9][0-9]*"));
            assertTrue(posNumber.matches("[1-9][0-9]*"));
            assertEquals(1L, Math.abs(Long.parseLong(regularNumber) - Long.parseLong(posNumber)));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT sales_number) FROM sales WHERE id IN (?, ?)", Integer.class,
                    first.get("id").asLong(), second.get("id").asLong()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reportsSortNumericDocumentNumbersNumericallyAndKeepLegacyReferences() {
        assertEquals(List.of("SAL-1001", "SAL-1009", "1", "2", "10"),
                List.of("10", "SAL-1009", "2", "1", "SAL-1001").stream()
                        .sorted(DocumentNumberOrder.ASCENDING).toList());
    }

    @Test
    void upgradeKeepsPrefixedRecordsAndReservesAlreadyUsedNumericNumbers() throws Exception {
        String migration = new ClassPathResource("db/migration/V14__numeric_document_sequences.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        transaction.executeWithoutResult(status -> {
            jdbc.execute("CREATE SCHEMA numbering_upgrade_test");
            jdbc.execute("SET LOCAL search_path TO numbering_upgrade_test");
            Map.of("purchases", "purchase_number", "sales", "sales_number",
                    "stock_transfers", "transfer_number", "stock_adjustments", "adjustment_number",
                    "item_import_batches", "import_number", "supplier_payments", "payment_number",
                    "sale_modifications", "modification_number", "sale_cancellations", "cancellation_number")
                    .forEach((table, column) -> jdbc.execute("CREATE TABLE " + table + " (" + column + " text)"));
            jdbc.execute("INSERT INTO sales VALUES ('SAL-1109'), ('12'), ('15')");
            jdbc.execute("INSERT INTO purchases VALUES ('PUR-2000')");
            jdbc.execute(migration);
            assertEquals(16L, jdbc.queryForObject("SELECT nextval('sale_document_seq')", Long.class));
            assertEquals(1L, jdbc.queryForObject("SELECT nextval('purchase_document_seq')", Long.class));
            assertEquals(List.of("12", "15", "SAL-1109"),
                    jdbc.queryForList("SELECT sales_number FROM sales ORDER BY sales_number", String.class));
            status.setRollbackOnly();
        });
    }

    @Test
    void documentSequencesAreIndependentAndNeverReuseRolledBackAllocations() throws Exception {
        String rolledBack = transaction.execute(status -> {
            String number = numberGenerator.nextSale();
            status.setRollbackOnly();
            return number;
        });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> allocations = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                allocations.add(pool.submit(numberGenerator::nextSale));
            }
            var numbers = new HashSet<String>();
            for (Future<String> allocation : allocations) {
                String number = allocation.get(10, TimeUnit.SECONDS);
                assertTrue(Long.parseLong(number) > Long.parseLong(rolledBack));
                assertTrue(numbers.add(number), "Concurrent allocation reused a number");
            }
        } finally {
            pool.shutdownNow();
        }
        Long before = jdbc.queryForObject("SELECT last_value FROM sale_document_seq", Long.class);
        assertTrue(numberGenerator.nextPurchase().matches("[1-9][0-9]*"));
        assertEquals(before, jdbc.queryForObject("SELECT last_value FROM sale_document_seq", Long.class));
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_sequences WHERE schemaname = 'public' "
                        + "AND sequencename LIKE '%_document_seq' AND start_value = 1", Integer.class));
    }

    // ---------- modification ----------

    @Test
    void posSalePersistsSplitPaymentsAndRejectsInvalidTender() {
        Session session = registerStore();
        long category = createCategory(session, "POS tender");
        ItemRef item = createItem(session, category, "POS", taxIdByRate(session, "5"));
        purchase(session, createSupplier(session, "POS"), item.id(), "10", "COMPLETED");
        var body = map("branchId", session.branchId(), "pos", true,
                "items", List.of(modificationLine(item.id(), "2", "20", "0")));

        call(HttpMethod.POST, "/api/sales/split", body, session.token(), HttpStatus.BAD_REQUEST);
        body.put("payments", List.of(map("method", "CASH", "amount", 42)));
        call(HttpMethod.POST, "/api/sales/split", body, session.token(), HttpStatus.BAD_REQUEST);
        for (var payments : List.of(
                List.of(map("method", "CASH", "amount", 40), map("method", "UPI", "amount", 3)),
                List.of(map("method", "CASH", "amount", 10), map("method", "CASH", "amount", 12)),
                List.of(map("method", "CASH", "amount", 10), map("method", "CREDIT", "amount", 12)),
                List.of(map("method", "CASH", "amount", 10), map("method", "UPI", "amount", 0)))) {
            body.put("payments", payments);
            call(HttpMethod.POST, "/api/sales/split", body, session.token(), HttpStatus.BAD_REQUEST);
        }
        assertEquals(0, stockOf(session, item.code()).compareTo(BigDecimal.TEN));

        body.put("payments", List.of(map("method", "CASH", "amount", 12), map("method", "UPI", "amount", 10),
                map("method", "CARD", "amount", 10), map("method", "BANK", "amount", 5)));
        JsonNode created = call(HttpMethod.POST, "/api/sales/split", body, session.token(), HttpStatus.OK);
        assertTrue(created.get("salesNumber").asText().matches("[1-9][0-9]*"));
        assertMoney(created.get("paidAmount"), "37");
        assertMoney(created.get("balanceAmount"), "5");
        JsonNode saved = call(HttpMethod.GET, "/api/sales/" + created.get("id").asLong(),
                null, session.token(), HttpStatus.OK);
        assertEquals(created.get("payments"), saved.get("payments"));
        assertEquals(4, saved.get("payments").size());
        JsonNode summary = call(HttpMethod.GET, "/api/reports/payment-summary", null, session.token(), HttpStatus.OK);
        assertMoney(summary.get("collected"), "37");
        assertEquals(4, summary.get("methods").size());
        assertEquals(1, summary.get("bills").asInt());
    }

    @Test
    void paymentOnlyEditsPreserveBillsAndReportOnlyCurrentTender() {
        Session session = registerStore();
        long category = createCategory(session, "Tender");
        ItemRef item = createItem(session, category, "TND", taxIdByRate(session, "5"));
        purchase(session, createSupplier(session, "TND"), item.id(), "10", "COMPLETED");
        JsonNode original = sale(session, item.id(), "2");
        String path = "/api/sales/" + original.get("id").asLong() + "/modifications";
        var lines = List.of(modificationLine(item.id(), "2", "20", "0"));

        var invalid = modificationBody("Invalid tender", 0, lines);
        for (String amount : List.of("-1", "0", "1.001", "42.01")) {
            invalid.put("payments", List.of(map("method", "UPI", "amount", new BigDecimal(amount))));
            call(HttpMethod.POST, path, invalid, session.token(), HttpStatus.BAD_REQUEST);
        }
        invalid.put("payments", List.of(map("method", "UPI", "amount", 5), map("method", "UPI", "amount", 6)));
        call(HttpMethod.POST, path, invalid, session.token(), HttpStatus.BAD_REQUEST);

        var revisions = List.of(
                List.of(map("method", "BANK", "amount", 42)),
                List.of(map("method", "CASH", "amount", 12), map("method", "UPI", "amount", 30)),
                List.of(map("method", "CASH", "amount", 12), map("method", "CARD", "amount", 30)),
                List.of(map("method", "CASH", "amount", 12), map("method", "UPI", "amount", 10),
                        map("method", "CARD", "amount", 15)));
        for (int revision = 0; revision < revisions.size(); revision++) {
            var body = modificationBody("Payment correction", revision, lines);
            var payments = revisions.get(revision);
            body.put("payments", payments);
            JsonNode preview = call(HttpMethod.POST, path + "/preview", body, session.token(), HttpStatus.OK);
            assertMoney(preview.get("modifiedTotal"), "42.00");
            JsonNode applied = call(HttpMethod.POST, path, body, session.token(), HttpStatus.OK);
            assertEquals(payments.size(), applied.get("sale").get("payments").size());
            assertEquals(revision + 1, applied.get("sale").get("modificationCount").asInt());
            assertNotNull(applied.get("modifiedBy"));
            assertNotNull(applied.get("modifiedAt"));
            assertEquals(0, applied.get("lines").size());
            assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("8")));
            call(HttpMethod.POST, path, body, session.token(), HttpStatus.CONFLICT);
        }

        JsonNode history = call(HttpMethod.GET, path, null, session.token(), HttpStatus.OK);
        assertEquals(4, history.size());
        assertMoney(history.get(0).get("additionalPayable"), "5.00");
        assertMoney(history.get(0).get("refundDue"), "0.00");
        JsonNode first = history.get(3);
        assertEquals("CASH", first.get("paymentsBefore").get(0).get("method").asText());
        assertEquals("BANK", first.get("paymentsAfter").get(0).get("method").asText());

        String report = "/api/reports/payment-summary?storeId=" + session.storeId()
                + "&branchId=" + session.branchId();
        JsonNode summary = call(HttpMethod.GET, report, null, session.token(), HttpStatus.OK);
        assertMoney(summary.get("billAmount"), "42.00");
        assertMoney(summary.get("collected"), "37.00");
        assertEquals(1, summary.get("bills").asInt());
        assertEquals(1, summary.get("partiallyPaid").asInt());
        assertEquals(3, summary.get("methods").size());
        JsonNode upi = call(HttpMethod.GET, report + "&method=UPI", null, session.token(), HttpStatus.OK);
        assertMoney(upi.get("collected"), "10.00");
        assertEquals(1, upi.get("methods").get(0).get("transactions").asInt());
        JsonNode cashier = call(HttpMethod.GET, report + "&cashierId=" + summary.get("cashiers").get(0).get("id").asLong(),
                null, session.token(), HttpStatus.OK);
        assertMoney(cashier.get("collected"), "37.00");
        JsonNode outOfRange = call(HttpMethod.GET, report + "&from=2000-01-01&to=2000-01-31",
                null, session.token(), HttpStatus.OK);
        assertEquals(0, outOfRange.get("bills").asInt());
        call(HttpMethod.GET, "/api/reports/payment-summary?storeId=" + (session.storeId() + 1),
                null, session.token(), HttpStatus.FORBIDDEN);
    }

    @Test
    void modificationRecalculatesTaxTotalsAndStock() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Bill");
        ItemRef item = createItem(session, categoryId, "MOD", taxIdByRate(session, "5"));
        long supplierId = createSupplier(session, "MOD");
        purchase(session, supplierId, item.id(), "10", "COMPLETED");

        JsonNode sale = sale(session, item.id(), "2");
        long saleId = sale.get("id").asLong();
        assertMoney(sale.get("grandTotal"), "42.00");
        assertMoney(sale.get("tax"), "2.00");
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("8")));
        assertEquals(0, sale.get("modificationCount").asInt());

        JsonNode preview = call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications/preview",
                modificationBody("Customer bought three more", 0,
                        List.of(modificationLine(item.id(), "5", "20", "0"))),
                session.token(), HttpStatus.OK);
        assertMoney(preview.get("originalTotal"), "42.00");
        assertMoney(preview.get("modifiedTotal"), "105.00");
        assertMoney(preview.get("differenceAmount"), "63.00");
        assertMoney(preview.get("additionalPayable"), "63.00");
        assertMoney(preview.get("refundDue"), "0.00");
        assertMoney(preview.get("originalQuantity"), "2");
        assertMoney(preview.get("modifiedQuantity"), "5");
        assertMoney(preview.get("quantityIncreased"), "3");
        assertMoney(preview.get("quantityDecreased"), "0");
        assertEquals(0, preview.get("itemsAdded").asInt());
        assertEquals(0, preview.get("itemsRemoved").asInt());
        assertEquals(1, preview.get("itemsUpdated").asInt());
        assertEquals("UPDATED", preview.get("lines").get(0).get("changeType").asText());
        assertMoney(preview.get("lines").get(0).get("taxAmount"), "5.00");

        // Preview must not persist anything.
        JsonNode untouched = call(HttpMethod.GET, "/api/sales/" + saleId, null, session.token(), HttpStatus.OK);
        assertMoney(untouched.get("grandTotal"), "42.00");
        assertEquals(0, untouched.get("modificationCount").asInt());
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("8")));

        JsonNode applied = call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Customer bought three more", 0,
                        List.of(modificationLine(item.id(), "5", "20", "0"))),
                session.token(), HttpStatus.OK);
        assertTrue(applied.get("modificationNumber").asText().matches("[1-9][0-9]*"));
        assertEquals(1, applied.get("sale").get("modificationCount").asInt());
        assertMoney(applied.get("sale").get("subtotal"), "100.00");
        assertMoney(applied.get("sale").get("cgstAmount"), "2.50");
        assertMoney(applied.get("sale").get("sgstAmount"), "2.50");
        assertMoney(applied.get("sale").get("tax"), "5.00");
        assertMoney(applied.get("sale").get("grandTotal"), "105.00");
        // Cash already collected stays as paid; the balance now carries the difference.
        assertMoney(applied.get("sale").get("paidAmount"), "42.00");
        assertMoney(applied.get("sale").get("balanceAmount"), "63.00");
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("5")));

        JsonNode history = call(HttpMethod.GET, "/api/sales/" + saleId + "/modifications",
                null, session.token(), HttpStatus.OK);
        assertEquals(1, history.size());
        assertEquals(applied.get("modificationNumber").asText(),
                history.get(0).get("modificationNumber").asText());
        assertEquals("Customer bought three more", history.get(0).get("reason").asText());
        assertMoney(history.get(0).get("originalTotal"), "42.00");
        assertMoney(history.get(0).get("modifiedTotal"), "105.00");

        // A repeat submission of the same edit is rejected and changes nothing.
        JsonNode noChange = call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Repeat", 1, List.of(modificationLine(item.id(), "5", "20", "0"))),
                session.token(), HttpStatus.BAD_REQUEST);
        assertTrue(noChange.get("message").asText().toLowerCase().contains("matches your edits"));
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("5")));
    }

    @Test
    void modificationAddsAndRemovesLinesAndReleasesStock() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Bill swap");
        ItemRef first = createItem(session, categoryId, "SWA", taxIdByRate(session, "5"));
        ItemRef second = createItem(session, categoryId, "SWB", taxIdByRate(session, "5"));
        long supplierId = createSupplier(session, "SW");
        purchase(session, supplierId, first.id(), "10", "COMPLETED");
        purchase(session, supplierId, second.id(), "10", "COMPLETED");

        JsonNode sale = sale(session, first.id(), "3");
        long saleId = sale.get("id").asLong();
        assertMoney(sale.get("grandTotal"), "63.00");
        assertEquals(0, stockOf(session, first.code()).compareTo(new BigDecimal("7")));

        JsonNode swapped = call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Customer swapped two units", 0, List.of(
                        modificationLine(first.id(), "1", "20", "0"),
                        modificationLine(second.id(), "2", "20", "0"))),
                session.token(), HttpStatus.OK);
        assertEquals(1, swapped.get("itemsAdded").asInt());
        assertEquals(0, swapped.get("itemsRemoved").asInt());
        assertEquals(1, swapped.get("itemsUpdated").asInt());
        assertMoney(swapped.get("sale").get("grandTotal"), "63.00");
        assertEquals(0, stockOf(session, first.code()).compareTo(new BigDecimal("9")));
        assertEquals(0, stockOf(session, second.code()).compareTo(new BigDecimal("8")));

        JsonNode trimmed = call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Kept only the second item", 1,
                        List.of(modificationLine(second.id(), "2", "20", "0"))),
                session.token(), HttpStatus.OK);
        assertEquals(1, trimmed.get("itemsRemoved").asInt());
        assertMoney(trimmed.get("sale").get("grandTotal"), "42.00");
        assertMoney(trimmed.get("refundDue"), "21.00");
        assertMoney(trimmed.get("sale").get("paidAmount"), "42.00");
        assertMoney(trimmed.get("sale").get("balanceAmount"), "0.00");
        assertEquals(2, trimmed.get("sale").get("modificationCount").asInt());
        assertEquals(1, trimmed.get("sale").get("items").size());
        assertEquals(0, stockOf(session, first.code()).compareTo(new BigDecimal("10")));
        assertEquals(0, stockOf(session, second.code()).compareTo(new BigDecimal("8")));

        JsonNode history = call(HttpMethod.GET, "/api/sales/" + saleId + "/modifications",
                null, session.token(), HttpStatus.OK);
        assertEquals(2, history.size());
        assertMoney(history.get(0).get("modifiedTotal"), "42.00");
        assertMoney(history.get(1).get("modifiedTotal"), "63.00");
        boolean removedRecorded = false;
        for (JsonNode line : history.get(0).get("lines")) {
            if (line.get("itemId").asLong() == first.id()
                    && "REMOVED".equals(line.get("changeType").asText())) {
                removedRecorded = true;
                assertMoney(line.get("originalAmount"), "21.00");
                assertMoney(line.get("modifiedAmount"), "0.00");
            }
        }
        assertTrue(removedRecorded, "the removed line must be preserved in the modification history");
    }

    @Test
    void modificationProtectsStockDuplicatesTenancyAndStaleEdits() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Bill guards");
        ItemRef item = createItem(session, categoryId, "GRD", taxIdByRate(session, "5"));
        long supplierId = createSupplier(session, "GRD");
        purchase(session, supplierId, item.id(), "3", "COMPLETED");

        JsonNode sale = sale(session, item.id(), "1");
        long saleId = sale.get("id").asLong();

        JsonNode overdraw = call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Too many", 0, List.of(modificationLine(item.id(), "10", "20", "0"))),
                session.token(), HttpStatus.BAD_REQUEST);
        assertTrue(overdraw.get("message").asText().toLowerCase().contains("insufficient stock"));
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("2")));

        call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Duplicate", 0, List.of(
                        modificationLine(item.id(), "1", "20", "0"),
                        modificationLine(item.id(), "1", "20", "0"))),
                session.token(), HttpStatus.BAD_REQUEST);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("2")));

        call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Discount above line", 0,
                        List.of(modificationLine(item.id(), "1", "20", "999"))),
                session.token(), HttpStatus.BAD_REQUEST);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("2")));

        Session other = registerStore();
        ItemRef foreign = createItem(other, createCategory(other, "Foreign"), "FRN", taxIdByRate(other, "5"));
        call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Foreign item", 0,
                        List.of(modificationLine(foreign.id(), "1", "20", "0"))),
                session.token(), HttpStatus.BAD_REQUEST);
        call(HttpMethod.GET, "/api/sales/" + saleId + "/modifications", null, other.token(), HttpStatus.NOT_FOUND);

        // A stale screen must not overwrite a change made elsewhere.
        call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("First edit", 0, List.of(modificationLine(item.id(), "2", "20", "0"))),
                session.token(), HttpStatus.OK);
        JsonNode stale = call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Second edit from an old screen", 0,
                        List.of(modificationLine(item.id(), "3", "20", "0"))),
                session.token(), HttpStatus.CONFLICT);
        assertTrue(stale.get("message").asText().toLowerCase().contains("changed by someone else"));
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("1")));
        JsonNode current = call(HttpMethod.GET, "/api/sales/" + saleId, null, session.token(), HttpStatus.OK);
        assertEquals(1, current.get("modificationCount").asInt());
        assertMoney(current.get("grandTotal"), "42.00");
    }

    @Test
    void concurrentModificationsApplyExactlyOnce() throws Exception {
        Session session = registerStore();
        long categoryId = createCategory(session, "Bill race");
        ItemRef item = createItem(session, categoryId, "RCE", taxIdByRate(session, "5"));
        long supplierId = createSupplier(session, "RCE");
        purchase(session, supplierId, item.id(), "5", "COMPLETED");
        JsonNode sale = sale(session, item.id(), "1");
        long saleId = sale.get("id").asLong();

        int attempts = 2;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                ResponseEntity<JsonNode> response = rest.exchange("/api/sales/" + saleId + "/modifications",
                        HttpMethod.POST,
                        entity(modificationBody("Concurrent edit", 0,
                                List.of(modificationLine(item.id(), "2", "20", "0"))), session.token()),
                        JsonNode.class);
                return response.getStatusCode().value();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int applied = 0;
        int rejected = 0;
        for (Future<Integer> result : results) {
            int status = result.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                applied++;
            } else if (status == 409) {
                rejected++;
            }
        }
        pool.shutdown();

        assertEquals(1, applied, "exactly one edit may be applied");
        assertEquals(1, rejected, "the duplicate submission must be rejected");
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("3")));
        JsonNode current = call(HttpMethod.GET, "/api/sales/" + saleId, null, session.token(), HttpStatus.OK);
        assertEquals(1, current.get("modificationCount").asInt());
        assertMoney(current.get("grandTotal"), "42.00");
    }

    // ---------- cancellation ----------

    @Test
    void cancellationRestoresStockAndKeepsImmutableHistory() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Cancel");
        ItemRef item = createItem(session, categoryId, "CAN", taxIdByRate(session, "5"));
        long supplierId = createSupplier(session, "CAN");
        purchase(session, supplierId, item.id(), "5", "COMPLETED");

        JsonNode sale = sale(session, item.id(), "2");
        long saleId = sale.get("id").asLong();
        assertMoney(sale.get("grandTotal"), "42.00");
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("3")));

        call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel",
                map("reason", "   "), session.token(), HttpStatus.BAD_REQUEST);
        call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel", map(), session.token(), HttpStatus.BAD_REQUEST);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("3")));

        JsonNode cancelled = call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel",
                map("reason", "Customer cancelled the order"), session.token(), HttpStatus.OK);
        assertTrue(cancelled.get("cancellationNumber").asText().matches("[1-9][0-9]*"));
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals(saleId, cancelled.get("saleId").asLong());
        assertEquals("Customer cancelled the order", cancelled.get("reason").asText());
        assertMoney(cancelled.get("saleTotal"), "42.00");
        assertMoney(cancelled.get("paidAmount"), "42.00");
        assertMoney(cancelled.get("balanceAmount"), "0.00");
        assertEquals(1, cancelled.get("restoredItems").asInt());
        assertNotNull(cancelled.get("cancelledAt"));
        assertNotNull(cancelled.get("cancelledByName"));
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("5")));

        JsonNode duplicate = call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel",
                map("reason", "Second attempt"), session.token(), HttpStatus.CONFLICT);
        assertTrue(duplicate.get("message").asText().toLowerCase().contains("already cancelled"));
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("5")));

        JsonNode record = call(HttpMethod.GET, "/api/sales/" + saleId + "/cancellation",
                null, session.token(), HttpStatus.OK);
        assertEquals(cancelled.get("cancellationNumber").asText(), record.get("cancellationNumber").asText());
        assertEquals("Customer cancelled the order", record.get("reason").asText());

        JsonNode bill = call(HttpMethod.GET, "/api/sales/" + saleId, null, session.token(), HttpStatus.OK);
        assertEquals("CANCELLED", bill.get("status").asText());
        assertEquals("Customer cancelled the order", bill.get("cancellationReason").asText());
        assertNotNull(bill.get("cancelledAt"));
        assertMoney(bill.get("grandTotal"), "42.00");

        JsonNode listed = call(HttpMethod.GET, "/api/sales?branchId=" + session.branchId()
                + "&status=CANCELLED&size=50", null, session.token(), HttpStatus.OK);
        boolean found = false;
        for (JsonNode row : listed.get("content")) {
            if (row.get("id").asLong() == saleId) {
                found = true;
            }
        }
        assertTrue(found, "the cancelled bill must stay visible in the sales history");
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("5")));
    }

    @Test
    void cancelledBillCannotBeModifiedOrCancelledAgain() {
        Session session = registerStore();
        long categoryId = createCategory(session, "Cancel lock");
        ItemRef item = createItem(session, categoryId, "LCK", taxIdByRate(session, "5"));
        long supplierId = createSupplier(session, "LCK");
        purchase(session, supplierId, item.id(), "5", "COMPLETED");
        JsonNode sale = sale(session, item.id(), "1");
        long saleId = sale.get("id").asLong();

        call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel",
                map("reason", "Wrong entry"), session.token(), HttpStatus.OK);

        call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Too late", 0, List.of(modificationLine(item.id(), "2", "20", "0"))),
                session.token(), HttpStatus.BAD_REQUEST);
        call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications/preview",
                modificationBody("Too late", 0, List.of(modificationLine(item.id(), "2", "20", "0"))),
                session.token(), HttpStatus.BAD_REQUEST);
        call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel",
                map("reason", "Wrong entry again"), session.token(), HttpStatus.CONFLICT);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("5")));

        Session other = registerStore();
        call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel",
                map("reason", "Not my bill"), other.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.GET, "/api/sales/" + saleId + "/cancellation", null, other.token(), HttpStatus.NOT_FOUND);

        JsonNode notCancelled = sale(session, item.id(), "1");
        call(HttpMethod.GET, "/api/sales/" + notCancelled.get("id").asLong() + "/cancellation",
                null, session.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.POST, "/api/sales/" + notCancelled.get("id").asLong() + "/cancel",
                map("reason", "Refund requested"), session.token(), HttpStatus.OK);
        assertEquals(0, stockOf(session, item.code()).compareTo(new BigDecimal("5")));
    }

    @Test
    void modificationAndCancellationRequireDedicatedPermissions() {
        Session owner = registerStore();
        long categoryId = createCategory(owner, "Perms");
        ItemRef item = createItem(owner, categoryId, "PRM", taxIdByRate(owner, "5"));
        long supplierId = createSupplier(owner, "PRM");
        purchase(owner, supplierId, item.id(), "5", "COMPLETED");
        JsonNode sale = sale(owner, item.id(), "2");
        long saleId = sale.get("id").asLong();

        long staffRoleId = roleId(owner, "BRANCH_STAFF");
        int seq = SEQ.incrementAndGet();
        call(HttpMethod.POST, "/api/users",
                map("username", "staff" + seq, "email", "staff" + seq + "@test.local",
                        "password", "Staff@123", "fullName", "Branch Staff",
                        "mobile", "9000000012", "roleId", staffRoleId,
                        "branchIds", List.of(owner.branchId())),
                owner.token(), HttpStatus.OK);
        Session staff = loginSession(owner.storeCode(), "staff" + seq, "Staff@123");

        call(HttpMethod.GET, "/api/sales/" + saleId, null, staff.token(), HttpStatus.OK);
        call(HttpMethod.GET, "/api/sales/" + saleId + "/modifications", null, staff.token(), HttpStatus.OK);
        call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Staff edit", 0, List.of(modificationLine(item.id(), "3", "20", "0"))),
                staff.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications/preview",
                modificationBody("Staff preview", 0, List.of(modificationLine(item.id(), "3", "20", "0"))),
                staff.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.POST, "/api/sales/" + saleId + "/cancel",
                map("reason", "Staff cancel"), staff.token(), HttpStatus.FORBIDDEN);
        assertEquals(0, stockOf(owner, item.code()).compareTo(new BigDecimal("3")));

        JsonNode current = call(HttpMethod.GET, "/api/sales/" + saleId, null, owner.token(), HttpStatus.OK);
        assertEquals(0, current.get("modificationCount").asInt());
        assertEquals("COMPLETED", current.get("status").asText());

        // Managers are provisioned with the new permissions.
        long managerRoleId = roleId(owner, "BRANCH_MANAGER");
        String manager = "mgr" + seq;
        call(HttpMethod.POST, "/api/users",
                map("username", manager, "email", manager + "@test.local",
                        "password", "Manager@123", "fullName", "Branch Manager",
                        "mobile", "9000000013", "roleId", managerRoleId,
                        "branchIds", List.of(owner.branchId())),
                owner.token(), HttpStatus.OK);
        Session managerSession = loginSession(owner.storeCode(), manager, "Manager@123");
        call(HttpMethod.POST, "/api/sales/" + saleId + "/modifications",
                modificationBody("Manager edit", 0, List.of(modificationLine(item.id(), "3", "20", "0"))),
                managerSession.token(), HttpStatus.OK);
        assertEquals(0, stockOf(owner, item.code()).compareTo(new BigDecimal("2")));
    }

    // ---------- fixtures ----------

    private record Session(String token, long storeId, long branchId, String storeCode, String username) {
    }

    private record ItemRef(long id, String code) {
    }

    private Session registerStore() {
        int seq = SEQ.incrementAndGet();
        String code = "B" + seq + Integer.toString((int) (System.currentTimeMillis() % 1296), 36).toUpperCase();
        String username = "owner" + seq;
        provisioningService.createStore(new RegisterStoreRequest(code, "Bill Test Store " + code,
                "Store Owner", "owner" + seq + "@test.local", "9000000003", "1 Test Street",
                null, "Karnataka", username, "Secret@123", "Main Branch"), false);
        JsonNode auth = call(HttpMethod.POST, "/api/auth/login",
                map("storeCode", code, "username", username, "password", "Secret@123"),
                null, HttpStatus.OK);
        return new Session(auth.get("token").asText(), auth.at("/store/id").asLong(),
                auth.at("/branches/0/id").asLong(), code, username);
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

    private ItemRef createItem(Session session, long categoryId, String prefix, long taxId) {
        String code = "IT" + prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        JsonNode item = call(HttpMethod.POST, "/api/items",
                map("itemCode", code, "itemName", "Item " + prefix + " " + SEQ.incrementAndGet(),
                        "categoryId", categoryId, "taxId", taxId, "baseUnit", "PIECE",
                        "rate", new BigDecimal("20"),
                        "minStockLevel", new BigDecimal("1"), "status", "ACTIVE"),
                session.token(), HttpStatus.OK);
        return new ItemRef(item.get("id").asLong(), item.get("itemCode").asText());
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
        return call(HttpMethod.POST, "/api/suppliers",
                map("supplierName", "Supplier " + prefix + SEQ.incrementAndGet(),
                        "mobile", "9000000099", "address", "9 Vendor Road",
                        "state", "Karnataka", "status", "ACTIVE"),
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
                map("branchId", session.branchId(), "supplierId", supplierId,
                        "invoiceNumber", "INV-" + SEQ.incrementAndGet(),
                        "purchaseDate", LocalDate.now().toString(),
                        "status", status,
                        "items", List.of(map("itemId", itemId, "quantity", new BigDecimal(quantity),
                                "unit", "PIECE", "purchaseRate", new BigDecimal("10"),
                                "discount", BigDecimal.ZERO))),
                session.token(), HttpStatus.OK);
    }

    private JsonNode sale(Session session, long itemId, String quantity) {
        return call(HttpMethod.POST, "/api/sales",
                map("branchId", session.branchId(), "paymentMethod", "CASH",
                        "items", List.of(map("itemId", itemId, "quantity", new BigDecimal(quantity),
                                "unit", "PIECE", "sellingRate", new BigDecimal("20"),
                                "discount", BigDecimal.ZERO))),
                session.token(), HttpStatus.OK);
    }

    private Map<String, Object> modificationBody(String reason, int baseModificationCount,
                                                 List<Map<String, Object>> items) {
        return map("reason", reason, "baseModificationCount", baseModificationCount, "items", items);
    }

    private Map<String, Object> modificationLine(long itemId, String quantity, String rate, String discount) {
        return map("itemId", itemId, "quantity", new BigDecimal(quantity), "unit", "PIECE",
                "sellingRate", new BigDecimal(rate), "discount", new BigDecimal(discount));
    }

    private void assertMoney(JsonNode node, String expected) {
        assertNotNull(node, "expected an amount but the field was missing");
        assertEquals(0, new BigDecimal(node.asText()).compareTo(new BigDecimal(expected)),
                () -> "expected " + expected + " but was " + node.asText());
    }

    private BigDecimal stockOf(Session session, String itemCode) {
        JsonNode page = call(HttpMethod.GET,
                "/api/inventory?branchId=" + session.branchId() + "&search=" + itemCode + "&size=5",
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
