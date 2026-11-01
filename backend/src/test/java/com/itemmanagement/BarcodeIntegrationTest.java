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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end barcode tests: automatic generation, manual Code 128 / EAN-13
 * validation, per-store uniqueness, scanning lookup, search and permissions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.embedded-postgres.port=54332",
        "app.embedded-postgres.data-dir=build/pgdata-barcode-test",
        "app.embedded-postgres.clean=true",
        "app.seed-demo=false",
        "logging.level.com.itemmanagement=WARN"
})
class BarcodeIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String VALID_EAN13 = "2000000000008";
    private static final String INVALID_EAN13 = "2000000000009";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void creatingAnItemGeneratesAUniqueCode128BarcodeAndLookupFindsIt() {
        Session session = registerStore();
        JsonNode item = createItem(session, "AUTO" + SEQ.incrementAndGet(), null, null);

        String barcode = item.get("barcode").asText();
        assertEquals("CODE128", item.get("barcodeFormat").asText());
        assertEquals("INTERNAL", item.get("barcodeSource").asText());
        assertTrue(barcode.startsWith("IMS"), "generated barcode should use the IMS prefix: " + barcode);
        assertEquals(13, barcode.length());

        JsonNode found = call(HttpMethod.GET, "/api/items/barcode/" + barcode, null,
                session.token(), HttpStatus.OK);
        assertEquals(item.get("id").asLong(), found.get("id").asLong());
        assertEquals(barcode, found.get("barcode").asText());

        JsonNode generatedEan = createItem(session, "EAN" + SEQ.incrementAndGet(), null, "EAN13");
        String ean = generatedEan.get("barcode").asText();
        assertEquals("EAN13", generatedEan.get("barcodeFormat").asText());
        assertEquals("INTERNAL", generatedEan.get("barcodeSource").asText());
        assertTrue(ean.startsWith("20"), "generated EAN-13 must use the internal 20 prefix: " + ean);
        assertTrue(isValidEan13(ean), "generated EAN-13 must have a valid check digit: " + ean);
    }

    @Test
    void manualBarcodesAreValidatedAndUniqueInsideAStoreOnly() {
        Session storeA = registerStore();
        Session storeB = registerStore();
        String manual = "SUPPLIER-CODE-1";

        JsonNode created = createItem(storeA, "MAN" + SEQ.incrementAndGet(), manual, "CODE128");
        assertEquals(manual, created.get("barcode").asText());

        // Same barcode in the same store is rejected ...
        ResponseEntity<JsonNode> duplicate = rest.exchange("/api/items", HttpMethod.POST,
                entity(itemRequest(storeA, "DUP" + SEQ.incrementAndGet(), manual, "CODE128"),
                        storeA.token()), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT.value(), duplicate.getStatusCode().value());

        // ... but tenants are isolated, so another store may use it.
        JsonNode otherStore = createItem(storeB, "MAN" + SEQ.incrementAndGet(), manual, "CODE128");
        assertEquals(manual, otherStore.get("barcode").asText());

        // A barcode that exists only in store A is invisible to store B.
        String uniqueToA = "ONLY-A-" + SEQ.incrementAndGet();
        JsonNode aItem = createItem(storeA, "OA" + SEQ.incrementAndGet(), uniqueToA, "CODE128");
        ResponseEntity<JsonNode> hidden = rest.exchange(
                "/api/items/barcode/" + aItem.get("barcode").asText(), HttpMethod.GET,
                bearerOnly(storeB.token()), JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND.value(), hidden.getStatusCode().value());
    }

    @Test
    void ean13ChecksumAndCharacterRulesAreEnforced() {
        Session session = registerStore();

        JsonNode valid = createItem(session, "EANOK" + SEQ.incrementAndGet(), VALID_EAN13, "EAN13");
        assertEquals(VALID_EAN13, valid.get("barcode").asText());
        assertEquals("INTERNAL", valid.get("barcodeSource").asText());

        // A manufacturer-registered GTIN is kept as supplied and marked as GS1.
        String gs1Gtin = "8901234567890";
        assertTrue(isValidEan13(gs1Gtin));
        JsonNode registered = createItem(session, "EANGS1" + SEQ.incrementAndGet(), gs1Gtin, "EAN13");
        assertEquals(gs1Gtin, registered.get("barcode").asText());
        assertEquals("GS1", registered.get("barcodeSource").asText());

        ResponseEntity<JsonNode> badChecksum = rest.exchange("/api/items", HttpMethod.POST,
                entity(itemRequest(session, "EANBAD" + SEQ.incrementAndGet(), INVALID_EAN13, "EAN13"),
                        session.token()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), badChecksum.getStatusCode().value());

        ResponseEntity<JsonNode> badLength = rest.exchange("/api/items", HttpMethod.POST,
                entity(itemRequest(session, "EANLEN" + SEQ.incrementAndGet(), "12345", "EAN13"),
                        session.token()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), badLength.getStatusCode().value());

        ResponseEntity<JsonNode> badCode128 = rest.exchange("/api/items", HttpMethod.POST,
                entity(itemRequest(session, "BAD128" + SEQ.incrementAndGet(), "HAS SPACE", "CODE128"),
                        session.token()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), badCode128.getStatusCode().value());

        ResponseEntity<JsonNode> badFormat = rest.exchange("/api/items", HttpMethod.POST,
                entity(itemRequest(session, "BADFMT" + SEQ.incrementAndGet(), "ABC123", "QR"),
                        session.token()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), badFormat.getStatusCode().value());
    }

    @Test
    void regeneratePreviewAndSearchWork() {
        Session session = registerStore();
        JsonNode item = createItem(session, "REGEN" + SEQ.incrementAndGet(), null, null);
        long itemId = item.get("id").asLong();
        String original = item.get("barcode").asText();

        JsonNode regenerated = call(HttpMethod.POST, "/api/items/" + itemId + "/barcode",
                map("format", "EAN13"), session.token(), HttpStatus.OK);
        assertEquals("EAN13", regenerated.get("barcodeFormat").asText());
        assertTrue(isValidEan13(regenerated.get("barcode").asText()));
        assertNotEquals(original, regenerated.get("barcode").asText());

        String barcode = regenerated.get("barcode").asText();
        JsonNode search = call(HttpMethod.GET, "/api/items?search=" + barcode, null,
                session.token(), HttpStatus.OK);
        assertEquals(1, search.get("content").size());
        assertEquals(itemId, search.at("/content/0/id").asLong());

        JsonNode pos = call(HttpMethod.GET,
                "/api/items/pos?branchId=" + session.branchId() + "&search=" + barcode, null,
                session.token(), HttpStatus.OK);
        assertEquals(1, pos.size());
        assertEquals(barcode, pos.get(0).get("barcode").asText());

        // Updating without a barcode keeps the existing value.
        JsonNode update = call(HttpMethod.PUT, "/api/items/" + itemId,
                itemRequest(session, "REGEN" + SEQ.incrementAndGet(), null, null),
                session.token(), HttpStatus.OK);
        // The item code is immutable in the UI but the API accepts the new code.
        assertEquals(barcode, update.get("barcode").asText());
        assertEquals("EAN13", update.get("barcodeFormat").asText());
    }

    @Test
    void barcodeChangesRequireItemManagePermission() {
        Session owner = registerStore();
        JsonNode item = createItem(owner, "PERM" + SEQ.incrementAndGet(), null, null);
        Session staff = createUserSession(owner, "BRANCH_STAFF");

        ResponseEntity<JsonNode> denied = rest.exchange(
                "/api/items/" + item.get("id").asLong() + "/barcode", HttpMethod.POST,
                entity(map("format", "CODE128"), staff.token()), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN.value(), denied.getStatusCode().value());

        JsonNode lookup = call(HttpMethod.GET,
                "/api/items/barcode/" + item.get("barcode").asText(), null,
                staff.token(), HttpStatus.OK);
        assertEquals(item.get("id").asLong(), lookup.get("id").asLong());
    }

    @Test
    void oldItemRowsWereBackfilledByTheMigration() {
        Integer missing = jdbc.queryForObject(
                "SELECT count(*) FROM items WHERE barcode IS NULL OR barcode = ''", Integer.class);
        assertEquals(0, missing);
        Integer invalidFormat = jdbc.queryForObject(
                "SELECT count(*) FROM items WHERE barcode_format NOT IN ('CODE128','EAN13')",
                Integer.class);
        assertEquals(0, invalidFormat);
    }

    // ---------- fixtures ----------

    private record Session(String token, long userId, long storeId, long branchId,
                           String storeCode, String username) {
    }

    private Session registerStore() {
        int seq = SEQ.incrementAndGet();
        String code = "B" + seq + Integer.toString((int) (System.currentTimeMillis() % 1296), 36)
                .toUpperCase();
        String username = "owner" + seq;
        call(HttpMethod.POST, "/api/auth/register-store",
                map("storeCode", code,
                        "storeName", "Barcode Store " + code,
                        "ownerName", "Store Owner",
                        "email", "owner" + seq + "@test.local",
                        "mobile", "9000000005",
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

    private Session createUserSession(Session owner, String roleCode) {
        int seq = SEQ.incrementAndGet();
        String username = "user" + seq;
        call(HttpMethod.POST, "/api/users",
                map("username", username,
                        "email", username + "@test.local",
                        "password", "Secret@123",
                        "fullName", "User " + username,
                        "mobile", "9000000012",
                        "roleId", roleId(owner, roleCode),
                        "branchIds", List.of(owner.branchId())),
                owner.token(), HttpStatus.OK);
        return loginSession(owner.storeCode(), username, "Secret@123");
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

    private JsonNode createItem(Session session, String code, String barcode, String format) {
        return call(HttpMethod.POST, "/api/items",
                itemRequest(session, code, barcode, format), session.token(), HttpStatus.OK);
    }

    private Map<String, Object> itemRequest(Session session, String code, String barcode,
                                            String format) {
        int seq = SEQ.incrementAndGet();
        long categoryId = call(HttpMethod.POST, "/api/categories",
                map("categoryName", "Barcode Category " + seq, "status", "ACTIVE"),
                session.token(), HttpStatus.OK).get("id").asLong();
        JsonNode tax = exemptTax(session);
        Map<String, Object> body = map("itemCode", code, "itemName", "Barcode Item " + seq,
                "categoryId", categoryId, "taxId", tax.get("id").asLong(),
                "baseUnit", "PIECE", "rate", new BigDecimal("25"),
                "minStockLevel", new BigDecimal("1"), "status", "ACTIVE");
        if (barcode != null) {
            body.put("barcode", barcode);
        }
        if (format != null) {
            body.put("barcodeFormat", format);
        }
        return body;
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

    private boolean isValidEan13(String value) {
        if (value == null || !value.matches("\\d{13}")) {
            return false;
        }
        int sum = 0;
        for (int index = 0; index < 12; index++) {
            int digit = value.charAt(index) - '0';
            sum += index % 2 == 0 ? digit : digit * 3;
        }
        return (10 - sum % 10) % 10 == value.charAt(12) - '0';
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
