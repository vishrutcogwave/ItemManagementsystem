package com.itemmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.service.ItemImportExcelService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the Excel bulk item import against a real embedded
 * PostgreSQL instance. Every scenario goes through the HTTP API exactly as the
 * frontend does, including multipart uploads and confirmation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54330",
        "app.embedded-postgres.data-dir=build/pgdata-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "logging.level.com.itemmanagement=WARN"
})
class ItemImportIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final List<String> HEADERS = ItemImportExcelService.HEADERS;
    private static final String EXEMPT = "GST 0% (Exempt)";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private volatile String cachedPlatformToken;

    // ---------- template and workbook validation ----------

    @Test
    void templateDownloadsWithExactColumns() throws Exception {
        Session session = registerStore();
        ResponseEntity<byte[]> response = rest.exchange("/api/item-imports/template",
                HttpMethod.GET, bearerOnly(session.token()), byte[].class);

        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        assertNotNull(response.getBody());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.contains("item-import-template.xlsx"));
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                response.getHeaders().getContentType().toString());

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(response.getBody()))) {
            Sheet items = workbook.getSheet("Items");
            assertNotNull(items, "the template must contain an Items sheet");
            Row header = items.getRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                assertEquals(HEADERS.get(i), header.getCell(i).getStringCellValue());
            }
            assertNotNull(workbook.getSheet("Instructions"));
        }
    }

    @Test
    void rejectsInvalidWorkbooksAndMissingColumns() {
        Session session = registerStore();

        ResponseEntity<JsonNode> notExcel = upload("/api/item-imports/preview",
                "this is not a workbook".getBytes(StandardCharsets.UTF_8),
                "notes.txt", session.branchId(), session.token());
        assertEquals(HttpStatus.BAD_REQUEST.value(), notExcel.getStatusCode().value());
        assertTrue(notExcel.getBody().get("message").asText().contains("not a valid Excel workbook"));

        List<String> missingRate = new ArrayList<>(HEADERS);
        missingRate.remove("Rate");
        ResponseEntity<JsonNode> missingColumn = upload("/api/item-imports/preview",
                workbookWithHeaders(missingRate, List.<Object[]>of(new Object[]{
                        null, "Milk", "Dairy", "LITER", EXEMPT, "1", "1", null})),
                "missing.xlsx", session.branchId(), session.token());
        assertEquals(HttpStatus.BAD_REQUEST.value(), missingColumn.getStatusCode().value());
        assertTrue(missingColumn.getBody().get("message").asText().contains("missing required columns"));
        assertTrue(missingColumn.getBody().get("message").asText().contains("Rate"));

        ResponseEntity<JsonNode> noRows = upload("/api/item-imports/preview",
                workbook(List.of()), "empty.xlsx", session.branchId(), session.token());
        assertEquals(HttpStatus.BAD_REQUEST.value(), noRows.getStatusCode().value());
        assertTrue(noRows.getBody().get("message").asText().contains("does not contain any item rows"));
    }

    // ---------- successful import ----------

    @Test
    void validFileCreatesCategoriesItemsAndOpeningStock() {
        Session session = registerStore();
        String suffix = "V" + SEQ.incrementAndGet();
        String dairy = "Dairy" + suffix;
        String beverages = "Beverages" + suffix;
        String milk = "Milk" + suffix;
        String juice = "Juice" + suffix;
        String juiceCode = "JUICE" + suffix;

        byte[] file = workbook(List.of(
                new Object[]{null, milk, dairy, "LITER", "60", EXEMPT, "40", "10", "Fresh milk"},
                new Object[]{juiceCode, juice, beverages, "BOTTLE", "90", "GST 5%", "12", "5", null}));

        JsonNode preview = preview(session, session.branchId(), file, HttpStatus.OK);
        assertEquals("PREVIEWED", preview.get("status").asText());
        assertSummary(preview.get("summary"), "totalRows", 2);
        assertSummary(preview.get("summary"), "newCategories", 2);
        assertSummary(preview.get("summary"), "newItems", 2);
        assertSummary(preview.get("summary"), "existingItems", 0);
        assertSummary(preview.get("summary"), "stockUpdates", 2);
        assertSummary(preview.get("summary"), "invalidRows", 0);
        assertEquals("NEW_ITEM", preview.at("/rows/0/importAction").asText());
        assertEquals("VALID", preview.at("/rows/0/validationStatus").asText());

        JsonNode result = confirm(session, preview.get("batchId").asLong(), HttpStatus.OK);
        assertEquals("COMPLETED", result.get("status").asText());
        assertSummary(result.get("summary"), "newItems", 2);
        assertSummary(result.get("summary"), "newCategories", 2);
        assertSummary(result.get("summary"), "stockUpdates", 2);
        String generatedCode = result.at("/rows/0/itemCode").asText();
        assertTrue(generatedCode.startsWith("ITM-"), "blank item codes must be generated");
        assertEquals(juiceCode, result.at("/rows/1/itemCode").asText());

        JsonNode categories = categoriesSearch(session, dairy);
        assertEquals(1, categories.get("content").size());
        assertEquals(dairy, categories.at("/content/0/categoryName").asText());

        JsonNode items = itemsSearch(session, milk);
        assertEquals(1, items.get("content").size());
        JsonNode item = items.get("content").get(0);
        long itemId = item.get("id").asLong();
        assertEquals("Fresh milk", item.get("description").asText());
        assertEquals("LITER", item.get("baseUnit").asText());
        assertEquals(0, item.get("rate").decimalValue().compareTo(new BigDecimal("60")));
        assertEquals(dairy, item.get("categoryName").asText());
        assertEquals(EXEMPT, item.get("taxName").asText());

        JsonNode inventory = call(HttpMethod.GET, "/api/inventory?branchId=" + session.branchId()
                + "&search=" + encode(generatedCode) + "&size=10", null, session.token(), HttpStatus.OK);
        assertEquals(1, inventory.get("content").size());
        assertEquals(0, inventory.at("/content/0/currentStock").decimalValue()
                .compareTo(new BigDecimal("40")));
        assertEquals(0, inventory.at("/content/0/minimumStock").decimalValue()
                .compareTo(new BigDecimal("10")));

        JsonNode transactions = call(HttpMethod.GET, "/api/inventory/transactions?branchId="
                + session.branchId() + "&itemId=" + itemId, null, session.token(), HttpStatus.OK);
        JsonNode opening = transactions.get("content").get(0);
        assertEquals("OPENING_STOCK", opening.get("transactionType").asText());
        assertEquals(0, opening.get("quantityIn").decimalValue().compareTo(new BigDecimal("40")));
        assertEquals("ITEM_IMPORT", opening.get("referenceType").asText());
        assertEquals(result.get("importNumber").asText(), opening.get("referenceNumber").asText());

        // Existing purchase and sales flows keep working with imported items.
        long supplier = createSupplier(session, suffix);
        purchase(session, supplier, itemId, "5", "LITER");
        sale(session, itemId, "3", "LITER");
        assertEquals(0, stockOf(session, generatedCode).compareTo(new BigDecimal("42")));
    }

    @Test
    void existingCategoryAndItemAreReusedWithoutDuplicates() {
        Session session = registerStore();
        String suffix = "E" + SEQ.incrementAndGet();
        String categoryName = "Grocery" + suffix;
        String itemName = "Rice" + suffix;
        String itemCode = "RICE" + suffix;

        long categoryId = createCategory(session, categoryName);
        ItemRef item = createItem(session, categoryId, itemCode, itemName, "KG", "50");

        byte[] file = workbook(List.<Object[]>of(new Object[]{
                itemCode, itemName.toUpperCase(), "  " + categoryName.toLowerCase() + "  ",
                "KG", "50", EXEMPT, "25", "7", "Imported stock"}));

        JsonNode preview = preview(session, session.branchId(), file, HttpStatus.OK);
        assertEquals("EXISTING_ITEM_OPENING_STOCK", preview.at("/rows/0/importAction").asText());
        assertSummary(preview.get("summary"), "newCategories", 0);
        assertSummary(preview.get("summary"), "newItems", 0);
        assertSummary(preview.get("summary"), "existingItems", 1);
        assertSummary(preview.get("summary"), "stockUpdates", 1);

        JsonNode result = confirm(session, preview.get("batchId").asLong(), HttpStatus.OK);
        assertEquals("COMPLETED", result.get("status").asText());
        assertEquals(item.id(), result.at("/rows/0/itemId").asLong());

        assertEquals(1, categoriesSearch(session, categoryName).get("content").size());
        assertEquals(1, itemsSearch(session, itemName).get("content").size());
        assertEquals(0, stockOf(session, itemCode).compareTo(new BigDecimal("25")));

        JsonNode reloaded = call(HttpMethod.GET, "/api/items/" + item.id(), null,
                session.token(), HttpStatus.OK);
        // Existing item master is not overwritten by the import file.
        assertEquals(itemName, reloaded.get("itemName").asText());
        assertEquals(0, reloaded.get("rate").decimalValue().compareTo(new BigDecimal("50")));
        assertEquals(0, reloaded.get("minStockLevel").decimalValue().compareTo(new BigDecimal("5")));

        JsonNode inventory = call(HttpMethod.GET, "/api/inventory?branchId=" + session.branchId()
                + "&search=" + encode(itemCode), null, session.token(), HttpStatus.OK);
        assertEquals(0, inventory.at("/content/0/minimumStock").decimalValue()
                .compareTo(new BigDecimal("5")));
    }

    @Test
    void existingItemIsMatchedByNameAndCategoryWithoutCode() {
        Session session = registerStore();
        String suffix = "N" + SEQ.incrementAndGet();
        String categoryName = "Staples" + suffix;
        String itemName = "Flour" + suffix;
        String itemCode = "FLOUR" + suffix;

        long categoryId = createCategory(session, categoryName);
        createItem(session, categoryId, itemCode, itemName, "KG", "40");

        byte[] file = workbook(List.<Object[]>of(new Object[]{
                null, itemName.toLowerCase(), " " + categoryName.toLowerCase() + " ",
                "kg", "40", EXEMPT, "15", "5", null}));

        JsonNode preview = preview(session, session.branchId(), file, HttpStatus.OK);
        assertEquals("EXISTING_ITEM_OPENING_STOCK", preview.at("/rows/0/importAction").asText());
        assertEquals(itemCode, preview.at("/rows/0/itemCode").asText(),
                "the matched existing item code must be shown");
        assertSummary(preview.get("summary"), "newItems", 0);
        assertSummary(preview.get("summary"), "newCategories", 0);
        assertSummary(preview.get("summary"), "existingItems", 1);

        confirm(session, preview.get("batchId").asLong(), HttpStatus.OK);

        assertEquals(0, stockOf(session, itemCode).compareTo(new BigDecimal("15")));
        assertEquals(1, itemsSearch(session, itemName).get("content").size());
        assertEquals(1, categoriesSearch(session, categoryName).get("content").size());
    }

    // ---------- duplicate protection ----------

    @Test
    void sameFileCannotBeImportedTwice() {
        Session session = registerStore();
        String suffix = "D" + SEQ.incrementAndGet();
        String code = "ONCE" + suffix;
        byte[] file = workbook(List.<Object[]>of(new Object[]{
                code, "Single" + suffix, "Once" + suffix, "PIECE", "10", EXEMPT, "8", "2", null}));

        JsonNode first = preview(session, session.branchId(), file, HttpStatus.OK);
        JsonNode completed = confirm(session, first.get("batchId").asLong(), HttpStatus.OK);
        assertEquals("COMPLETED", completed.get("status").asText());

        JsonNode repeat = preview(session, session.branchId(), file, HttpStatus.OK);
        assertSummary(repeat.get("summary"), "alreadyImportedRows", 1);
        assertSummary(repeat.get("summary"), "newItems", 0);
        assertEquals("ALREADY_IMPORTED", repeat.at("/rows/0/importAction").asText());
        assertTrue(repeat.at("/rows/0/errorMessage").asText()
                .contains(completed.get("importNumber").asText()));

        JsonNode rejected = call(HttpMethod.POST, "/api/item-imports/"
                        + repeat.get("batchId").asLong() + "/confirm", null,
                session.token(), HttpStatus.CONFLICT);
        assertTrue(rejected.get("message").asText().contains("already been imported"));
        assertEquals(0, stockOf(session, code).compareTo(new BigDecimal("8")));
        assertEquals(1, itemsSearch(session, code).get("content").size());
    }

    @Test
    void duplicateRowsBlockTheWholeImport() {
        Session session = registerStore();
        String suffix = "P" + SEQ.incrementAndGet();
        String categoryName = "Duplicate" + suffix;
        String code = "DUP" + suffix;

        byte[] file = workbook(List.of(
                new Object[]{code, "First" + suffix, categoryName, "PIECE", "10", EXEMPT, "1", "1", null},
                new Object[]{code, "Second" + suffix, categoryName, "PIECE", "10", EXEMPT, "2", "1", null}));

        JsonNode preview = preview(session, session.branchId(), file, HttpStatus.OK);
        assertEquals("NEW_ITEM", preview.at("/rows/0/importAction").asText());
        assertEquals("DUPLICATE_ROW", preview.at("/rows/1/importAction").asText(), preview.toString());
        assertTrue(preview.at("/rows/1/errorMessage").asText().contains("Duplicate of row 2"),
                preview.toString());
        assertSummary(preview.get("summary"), "duplicateRows", 1);
        assertSummary(preview.get("summary"), "newItems", 1);

        JsonNode rejected = call(HttpMethod.POST, "/api/item-imports/"
                        + preview.get("batchId").asLong() + "/confirm", null,
                session.token(), HttpStatus.BAD_REQUEST);
        assertTrue(rejected.get("message").asText().contains("Duplicate rows"));
        assertTrue(rejected.get("message").asText().contains("row 3"));
        assertEquals(0, itemsSearch(session, code).get("content").size());
        assertEquals(0, categoriesSearch(session, categoryName).get("content").size());
    }

    @Test
    void duplicateNamesWithoutCodesBlockTheImport() {
        Session session = registerStore();
        String suffix = "Q" + SEQ.incrementAndGet();
        String categoryName = "NameDuplicate" + suffix;
        String name = "Same" + suffix;

        byte[] file = workbook(List.of(
                new Object[]{null, name, categoryName, "PIECE", "10", EXEMPT, "1", "1", null},
                new Object[]{null, name, categoryName, "PIECE", "10", EXEMPT, "2", "1", null}));

        JsonNode preview = preview(session, session.branchId(), file, HttpStatus.OK);
        assertEquals("DUPLICATE_ROW", preview.at("/rows/1/importAction").asText());
        assertSummary(preview.get("summary"), "duplicateRows", 1);

        call(HttpMethod.POST, "/api/item-imports/" + preview.get("batchId").asLong() + "/confirm",
                null, session.token(), HttpStatus.BAD_REQUEST);
        assertEquals(0, itemsSearch(session, name).get("content").size());
    }

    // ---------- validation ----------

    @Test
    void invalidRowsBlockTheWholeImport() {
        Session session = registerStore();
        String suffix = "X" + SEQ.incrementAndGet();
        String category = "Invalid" + suffix;

        byte[] file = workbook(List.of(
                new Object[]{null, "BadUnit" + suffix, category, "STONE", "10", "GST 5%", "1", "1", null},
                new Object[]{null, "BadTax" + suffix, category, "PIECE", "10", "No Such Tax", "1", "1", null},
                new Object[]{null, null, category, "PIECE", "10", "GST 5%", "1", "1", null},
                new Object[]{null, "NegativeStock" + suffix, category, "PIECE", "10", "GST 5%", "-5", "1", null},
                new Object[]{null, "ZeroRate" + suffix, category, "PIECE", "0", "GST 5%", "1", "1", null},
                new Object[]{null, "TextRate" + suffix, category, "PIECE", "abc", "GST 5%", "1", "1", null},
                new Object[]{null, "NoUnit" + suffix, category, null, "10", "GST 5%", "1", "1", null},
                new Object[]{null, "NoCategory" + suffix, null, "PIECE", "10", "GST 5%", "1", "1", null}));

        JsonNode preview = preview(session, session.branchId(), file, HttpStatus.OK);
        assertSummary(preview.get("summary"), "invalidRows", 8);
        assertSummary(preview.get("summary"), "newItems", 0);
        for (JsonNode row : preview.get("rows")) {
            assertEquals("VALIDATION_ERROR", row.get("importAction").asText());
            assertEquals("INVALID", row.get("validationStatus").asText());
            assertTrue(row.get("errorMessage").asText().length() > 0);
        }
        assertTrue(preview.at("/rows/0/errorMessage").asText().contains("Invalid unit"));
        assertTrue(preview.at("/rows/1/errorMessage").asText().contains("not available"));
        assertTrue(preview.at("/rows/2/errorMessage").asText().contains("Item name is required"));
        assertTrue(preview.at("/rows/3/errorMessage").asText().contains("cannot be negative"));
        assertTrue(preview.at("/rows/4/errorMessage").asText().contains("greater than zero"));
        assertTrue(preview.at("/rows/5/errorMessage").asText().contains("must be a number"));
        assertTrue(preview.at("/rows/6/errorMessage").asText().contains("Unit is required"));
        assertTrue(preview.at("/rows/7/errorMessage").asText().contains("Category name is required"));

        JsonNode rejected = call(HttpMethod.POST, "/api/item-imports/"
                        + preview.get("batchId").asLong() + "/confirm", null,
                session.token(), HttpStatus.BAD_REQUEST);
        assertTrue(rejected.get("message").asText().contains("Invalid rows"));
        assertEquals(0, categoriesSearch(session, category).get("content").size());
    }

    // ---------- branches and store isolation ----------

    @Test
    void openingStockIsBranchSpecificAndStoresStayIsolated() {
        Session owner = registerStore();
        long otherBranch = createBranch(owner, "B" + SEQ.incrementAndGet());
        String suffix = "BR" + SEQ.incrementAndGet();
        String code = "BRANCH" + suffix;
        byte[] file = workbook(List.<Object[]>of(new Object[]{
                code, "BranchItem" + suffix, "BranchCat" + suffix, "PIECE", "10", EXEMPT, "7", "1", null}));

        JsonNode preview = preview(owner, otherBranch, file, HttpStatus.OK);
        JsonNode completed = confirm(owner, preview.get("batchId").asLong(), HttpStatus.OK);
        assertEquals(otherBranch, completed.get("branchId").asLong());

        assertEquals(0, stockAt(owner, otherBranch, code).compareTo(new BigDecimal("7")));
        assertEquals(0, stockOf(owner, code).compareTo(BigDecimal.ZERO));

        Session stranger = registerStore();
        ResponseEntity<JsonNode> denied = upload("/api/item-imports/preview", file,
                "items.xlsx", otherBranch, stranger.token());
        assertEquals(HttpStatus.NOT_FOUND.value(), denied.getStatusCode().value(),
                "another store's branch must not be visible");

        call(HttpMethod.GET, "/api/item-imports/" + completed.get("batchId").asLong(),
                null, stranger.token(), HttpStatus.NOT_FOUND);
        call(HttpMethod.POST, "/api/item-imports/" + completed.get("batchId").asLong() + "/confirm",
                null, stranger.token(), HttpStatus.NOT_FOUND);
        JsonNode strangerHistory = call(HttpMethod.GET, "/api/item-imports", null,
                stranger.token(), HttpStatus.OK);
        assertEquals(0, strangerHistory.get("content").size());

        JsonNode detail = call(HttpMethod.GET, "/api/item-imports/"
                + completed.get("batchId").asLong(), null, owner.token(), HttpStatus.OK);
        assertEquals("COMPLETED", detail.get("status").asText());
        assertFalse(detail.get("branchName").asText().isEmpty());
    }

    // ---------- history ----------

    @Test
    void importHistoryAndDetailShowResults() {
        Session session = registerStore();
        String suffix = "H" + SEQ.incrementAndGet();
        String code = "HIST" + suffix;
        byte[] completedFile = workbook(List.<Object[]>of(new Object[]{
                code, "History" + suffix, "HistoryCat" + suffix, "PIECE", "10", EXEMPT, "3", "1", null}));
        JsonNode first = preview(session, session.branchId(), completedFile, HttpStatus.OK);
        JsonNode completed = confirm(session, first.get("batchId").asLong(), HttpStatus.OK);

        String previewOnlyCode = "PREVIEW" + suffix;
        byte[] previewFile = workbook(List.<Object[]>of(new Object[]{
                previewOnlyCode, "Preview" + suffix, "PreviewCat" + suffix,
                "PIECE", "10", EXEMPT, "1", "1", null}));
        JsonNode previewOnly = preview(session, session.branchId(), previewFile, HttpStatus.OK);

        JsonNode history = call(HttpMethod.GET, "/api/item-imports?page=0&size=20", null,
                session.token(), HttpStatus.OK);
        assertEquals(2, history.get("content").size());
        JsonNode completedEntry = findBatch(history, completed.get("importNumber").asText());
        assertNotNull(completedEntry);
        assertEquals("COMPLETED", completedEntry.get("status").asText());
        assertEquals(1, completedEntry.get("successRows").asInt());
        assertEquals(1, completedEntry.get("stockUpdates").asInt());
        assertEquals(session.username(), completedEntry.get("uploadedBy").asText());
        assertNotNull(completedEntry.get("completedAt"));

        JsonNode previewEntry = findBatch(history, previewOnly.get("importNumber").asText());
        assertNotNull(previewEntry);
        assertEquals("PREVIEWED", previewEntry.get("status").asText());

        JsonNode detail = call(HttpMethod.GET, "/api/item-imports/" + completed.get("batchId").asLong(),
                null, session.token(), HttpStatus.OK);
        assertEquals("NEW_ITEM", detail.at("/rows/0/importAction").asText());
        assertEquals(code, detail.at("/rows/0/itemCode").asText());

        JsonNode previewDetail = call(HttpMethod.GET,
                "/api/item-imports/" + previewOnly.get("batchId").asLong(),
                null, session.token(), HttpStatus.OK);
        assertEquals("PREVIEWED", previewDetail.get("status").asText());
        assertEquals(1, previewDetail.get("rows").size());
    }

    // ---------- permissions ----------

    @Test
    void importRequiresItemManageAndInventoryAdjustPermissions() {
        Session owner = registerStore();
        Session manager = createUserSession(owner, "BRANCH_MANAGER", "Manager@123");
        Session staff = createUserSession(owner, "BRANCH_STAFF", "Staff@123");
        String suffix = "AU" + SEQ.incrementAndGet();
        byte[] file = workbook(List.<Object[]>of(new Object[]{
                "PERM" + suffix, "Perm" + suffix, "PermCat" + suffix,
                "PIECE", "10", EXEMPT, "1", "1", null}));

        JsonNode preview = preview(owner, owner.branchId(), file, HttpStatus.OK);
        long batchId = preview.get("batchId").asLong();

        assertEquals(HttpStatus.OK.value(), rest.exchange("/api/item-imports/template",
                HttpMethod.GET, bearerOnly(manager.token()), byte[].class).getStatusCode().value());
        assertEquals(HttpStatus.OK.value(), rest.exchange("/api/item-imports/template",
                HttpMethod.GET, bearerOnly(staff.token()), byte[].class).getStatusCode().value());

        call(HttpMethod.GET, "/api/item-imports", null, manager.token(), HttpStatus.OK);
        call(HttpMethod.GET, "/api/item-imports", null, staff.token(), HttpStatus.OK);

        assertEquals(HttpStatus.FORBIDDEN.value(), upload("/api/item-imports/preview", file,
                "items.xlsx", owner.branchId(), manager.token()).getStatusCode().value());
        assertEquals(HttpStatus.FORBIDDEN.value(), upload("/api/item-imports/preview", file,
                "items.xlsx", owner.branchId(), staff.token()).getStatusCode().value());
        call(HttpMethod.POST, "/api/item-imports/" + batchId + "/confirm", null,
                manager.token(), HttpStatus.FORBIDDEN);
        call(HttpMethod.POST, "/api/item-imports/" + batchId + "/confirm", null,
                staff.token(), HttpStatus.FORBIDDEN);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), upload("/api/item-imports/preview", file,
                "items.xlsx", owner.branchId(), null).getStatusCode().value());
        call(HttpMethod.GET, "/api/item-imports", null, null, HttpStatus.UNAUTHORIZED);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), rest.exchange("/api/item-imports/template",
                HttpMethod.GET, bearerOnly(null), byte[].class).getStatusCode().value());
    }

    // ---------- failure and retry ----------

    @Test
    void databaseFailureRollsBackEverythingAndMarksFailed() {
        Session session = registerStore();
        String suffix = "R" + SEQ.incrementAndGet();
        String safeName = "RollbackSafe" + suffix;
        String categoryName = "RollbackCat" + suffix;
        byte[] file = workbook(List.of(
                new Object[]{null, safeName, categoryName, "PIECE", "10", EXEMPT, "3", "1", null},
                new Object[]{null, "Trigger Failure Item", categoryName,
                        "PIECE", "10", EXEMPT, "4", "1", null}));

        JsonNode preview = preview(session, session.branchId(), file, HttpStatus.OK);
        assertSummary(preview.get("summary"), "newItems", 2);

        createFailureTrigger();
        try {
            JsonNode failed = call(HttpMethod.POST, "/api/item-imports/"
                            + preview.get("batchId").asLong() + "/confirm", null,
                    session.token(), HttpStatus.BAD_REQUEST);
            assertTrue(failed.get("message").asText().contains("rolled back"));
        } finally {
            dropFailureTrigger();
        }

        JsonNode detail = call(HttpMethod.GET, "/api/item-imports/" + preview.get("batchId").asLong(),
                null, session.token(), HttpStatus.OK);
        assertEquals("FAILED", detail.get("status").asText());
        assertNotNull(detail.get("failureMessage"));

        assertEquals(0, itemsSearch(session, safeName).get("content").size());
        assertEquals(0, itemsSearch(session, "Trigger Failure Item").get("content").size());
        assertEquals(0, categoriesSearch(session, categoryName).get("content").size());
        assertEquals(0, call(HttpMethod.GET, "/api/inventory?branchId=" + session.branchId()
                + "&search=" + encode(safeName), null, session.token(), HttpStatus.OK)
                .get("content").size());

        // A failed batch does not block a corrected retry of the same file.
        JsonNode retry = preview(session, session.branchId(), file, HttpStatus.OK);
        assertEquals("NEW_ITEM", retry.at("/rows/0/importAction").asText());
        JsonNode completed = confirm(session, retry.get("batchId").asLong(), HttpStatus.OK);
        assertEquals("COMPLETED", completed.get("status").asText());
        assertEquals(1, itemsSearch(session, safeName).get("content").size());
    }

    // ---------- fixtures ----------

    private record Session(String token, long userId, long storeId, long branchId,
                           String storeCode, String username) {
    }

    private record ItemRef(long id, String code) {
    }

    private Session registerStore() {
        int seq = SEQ.incrementAndGet();
        String code = "I" + seq + Integer.toString((int) (System.currentTimeMillis() % 1296), 36)
                .toUpperCase();
        String username = "owner" + seq;
        call(HttpMethod.POST, "/api/auth/register-store",
                map("storeCode", code,
                        "storeName", "Import Store " + code,
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

    private Session createUserSession(Session owner, String roleCode, String password) {
        int seq = SEQ.incrementAndGet();
        String username = "user" + seq;
        JsonNode user = call(HttpMethod.POST, "/api/users",
                map("username", username,
                        "email", username + "@test.local",
                        "password", password,
                        "fullName", "User " + username,
                        "mobile", "9000000011",
                        "roleId", roleId(owner, roleCode),
                        "branchIds", List.of(owner.branchId())),
                owner.token(), HttpStatus.OK);
        return loginSession(owner.storeCode(), user.get("username").asText(), password);
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

    private long createCategory(Session session, String name) {
        return call(HttpMethod.POST, "/api/categories",
                map("categoryName", name, "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();
    }

    private ItemRef createItem(Session session, long categoryId, String code, String name,
                               String unit, String rate) {
        JsonNode item = call(HttpMethod.POST, "/api/items",
                map("itemCode", code, "itemName", name, "categoryId", categoryId,
                        "taxId", exemptTaxId(session), "baseUnit", unit,
                        "rate", new BigDecimal(rate), "minStockLevel", new BigDecimal("5"),
                        "status", "ACTIVE"),
                session.token(), HttpStatus.OK);
        return new ItemRef(item.get("id").asLong(), item.get("itemCode").asText());
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

    private long createSupplier(Session session, String suffix) {
        return call(HttpMethod.POST, "/api/suppliers",
                map("supplierName", "Supplier " + suffix,
                        "mobile", "9000000099", "address", "9 Vendor Road",
                        "state", "Karnataka", "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();
    }

    private long createBranch(Session session, String code) {
        return call(HttpMethod.POST, "/api/branches",
                map("branchCode", code, "branchName", "Branch " + code,
                        "address", "2 Branch Road", "contactNumber", "9000000004",
                        "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();
    }

    private void purchase(Session session, long supplierId, long itemId, String quantity) {
        purchase(session, supplierId, itemId, quantity, "PIECE");
    }

    private void purchase(Session session, long supplierId, long itemId, String quantity, String unit) {
        call(HttpMethod.POST, "/api/purchases",
                map("branchId", session.branchId(), "supplierId", supplierId,
                        "invoiceNumber", "INV-" + SEQ.incrementAndGet(),
                        "purchaseDate", LocalDate.now().toString(),
                        "status", "COMPLETED",
                        "items", List.of(map("itemId", itemId,
                                "quantity", new BigDecimal(quantity),
                                "unit", unit,
                                "purchaseRate", new BigDecimal("10"),
                                "discount", BigDecimal.ZERO))),
                session.token(), HttpStatus.OK);
    }

    private void sale(Session session, long itemId, String quantity) {
        sale(session, itemId, quantity, "PIECE");
    }

    private void sale(Session session, long itemId, String quantity, String unit) {
        call(HttpMethod.POST, "/api/sales",
                map("branchId", session.branchId(), "paymentMethod", "CASH",
                        "items", List.of(map("itemId", itemId,
                                "quantity", new BigDecimal(quantity),
                                "unit", unit,
                                "sellingRate", new BigDecimal("20"),
                                "discount", BigDecimal.ZERO))),
                session.token(), HttpStatus.OK);
    }

    private BigDecimal stockOf(Session session, String itemCode) {
        return stockAt(session, session.branchId(), itemCode);
    }

    private BigDecimal stockAt(Session session, long branchId, String itemCode) {
        JsonNode page = call(HttpMethod.GET,
                "/api/inventory?branchId=" + branchId + "&search=" + encode(itemCode) + "&size=5",
                null, session.token(), HttpStatus.OK);
        JsonNode content = page.get("content");
        return content.isEmpty() ? BigDecimal.ZERO : content.get(0).get("currentStock").decimalValue();
    }

    private JsonNode itemsSearch(Session session, String search) {
        return call(HttpMethod.GET, "/api/items?size=50&search=" + encode(search),
                null, session.token(), HttpStatus.OK);
    }

    private JsonNode categoriesSearch(Session session, String search) {
        return call(HttpMethod.GET, "/api/categories?size=50&search=" + encode(search),
                null, session.token(), HttpStatus.OK);
    }

    private String encode(String value) {
        return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
    }

    private JsonNode findBatch(JsonNode history, String importNumber) {
        for (JsonNode item : history.get("content")) {
            if (importNumber.equals(item.get("importNumber").asText())) {
                return item;
            }
        }
        return null;
    }

    private JsonNode preview(Session session, long branchId, byte[] file, HttpStatus expected) {
        ResponseEntity<JsonNode> response = upload("/api/item-imports/preview", file,
                "items-" + UUID.randomUUID() + ".xlsx", branchId, session.token());
        assertEquals(expected.value(), response.getStatusCode().value(),
                () -> "preview expected " + expected + " but got "
                        + response.getStatusCode() + " " + response.getBody());
        return response.getBody();
    }

    private JsonNode confirm(Session session, long batchId, HttpStatus expected) {
        return call(HttpMethod.POST, "/api/item-imports/" + batchId + "/confirm",
                null, session.token(), expected);
    }

    private ResponseEntity<JsonNode> upload(String path, byte[] content, String fileName,
                                            long branchId, String token) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        body.add("branchId", String.valueOf(branchId));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private byte[] workbook(List<Object[]> rows) {
        return workbookWithHeaders(HEADERS, rows);
    }

    private byte[] workbookWithHeaders(List<String> headers, List<Object[]> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Items");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            int rowIndex = 1;
            for (Object[] values : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int c = 0; c < values.length; c++) {
                    Object value = values[c];
                    if (value == null) {
                        continue;
                    }
                    if (value instanceof Number number) {
                        row.createCell(c).setCellValue(number.doubleValue());
                    } else {
                        row.createCell(c).setCellValue(value.toString());
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void createFailureTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_item_fn() RETURNS trigger AS $$
                BEGIN
                    IF NEW.item_name = 'Trigger Failure Item' THEN
                        RAISE EXCEPTION 'forced import failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_item_trg ON items");
        jdbc.execute("""
                CREATE TRIGGER test_fail_item_trg BEFORE INSERT ON items
                FOR EACH ROW EXECUTE FUNCTION test_fail_item_fn()
                """);
    }

    private void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_item_trg ON items");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_item_fn()");
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

    private void assertSummary(JsonNode summary, String field, int expected) {
        assertNotNull(summary, "missing import summary");
        assertEquals(expected, summary.get(field).asInt(),
                () -> "expected summary." + field + "=" + expected + " but was " + summary.get(field));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }
}
