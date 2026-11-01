package com.itemmanagement.service;

import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.enums.BarcodeFormat;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Barcode values are the scannable identifier only. The visual Code 128 /
 * EAN-13 symbol is rendered by the browser (JsBarcode) from this value.
 */
@Service
@RequiredArgsConstructor
public class BarcodeService {

    public static final String CODE128_PREFIX = "IMS";
    public static final String DEFAULT_EAN13_PREFIX = "20";

    private static final Pattern CODE128_VALUE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern EAN13_VALUE = Pattern.compile("^\\d{13}$");
    private static final Pattern DIGITS = Pattern.compile("^\\d{1,11}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_GENERATION_ATTEMPTS = 20;

    private final ItemRepository itemRepository;

    /**
     * GS1 reserves prefixes 02 and 20-29 for internal/restricted-distribution
     * numbering, so a barcode generated here can never collide with a
     * manufacturer-registered GTIN.
     */
    @Value("${app.barcode.ean13-prefix:" + DEFAULT_EAN13_PREFIX + "}")
    private String ean13Prefix;

    public record ResolvedBarcode(String value, BarcodeFormat format) {
    }

    public ResolvedBarcode resolveForCreate(Long storeId, String rawValue, String rawFormat) {
        BarcodeFormat format = parseFormat(rawFormat, BarcodeFormat.CODE128);
        if (rawValue == null || rawValue.isBlank()) {
            return generate(storeId, format);
        }
        return new ResolvedBarcode(validate(storeId, rawValue, format, null), format);
    }

    public ResolvedBarcode resolveForUpdate(Long storeId, String rawValue, String rawFormat,
                                            Item current) {
        BarcodeFormat format = parseFormat(rawFormat, current.getBarcodeFormat());
        if (rawValue == null || rawValue.isBlank()) {
            if (format != current.getBarcodeFormat()) {
                throw ApiException.badRequest(
                        "Enter or generate a barcode for the selected format");
            }
            return new ResolvedBarcode(current.getBarcode(), current.getBarcodeFormat());
        }
        return new ResolvedBarcode(validate(storeId, rawValue, format, current.getId()), format);
    }

    public ResolvedBarcode generate(Long storeId, BarcodeFormat format) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = format == BarcodeFormat.EAN13 ? randomEan13() : randomCode128();
            if (!itemRepository.existsByStoreIdAndBarcode(storeId, candidate)) {
                return new ResolvedBarcode(candidate, format);
            }
        }
        throw ApiException.conflict("A unique barcode could not be generated. Please retry.");
    }

    public String validate(Long storeId, String rawValue, BarcodeFormat format, Long currentItemId) {
        String value = rawValue.trim();
        if (format == BarcodeFormat.EAN13) {
            if (!EAN13_VALUE.matcher(value).matches()) {
                throw ApiException.badRequest("EAN-13 barcodes must contain exactly 13 digits");
            }
            if (!isValidEan13(value)) {
                throw ApiException.badRequest("This EAN-13 barcode has an invalid check digit");
            }
        } else if (!CODE128_VALUE.matcher(value).matches()) {
            throw ApiException.badRequest(
                    "Code 128 barcodes can use 1-64 letters, numbers, dot, underscore or hyphen");
        }
        Optional<Item> existing = itemRepository.findByStoreIdAndBarcode(storeId, value);
        if (existing.isPresent() && !existing.get().getId().equals(currentItemId)) {
            throw ApiException.conflict("This barcode is already used by another item");
        }
        return value;
    }

    public BarcodeFormat parseFormat(String raw, BarcodeFormat fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return BarcodeFormat.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Select a valid barcode format (CODE128 or EAN13)");
        }
    }

    public static boolean isValidEan13(String value) {
        if (value == null || !EAN13_VALUE.matcher(value).matches()) {
            return false;
        }
        return ean13CheckDigit(value.substring(0, 12)) == value.charAt(12) - '0';
    }

    public static int ean13CheckDigit(String twelveDigits) {
        int sum = 0;
        for (int index = 0; index < 12; index++) {
            int digit = twelveDigits.charAt(index) - '0';
            sum += index % 2 == 0 ? digit : digit * 3;
        }
        return (10 - sum % 10) % 10;
    }

    /**
     * INTERNAL barcodes are generated by this application for in-store use.
     * GS1 means the value looks like a retail GTIN registered with GS1 and
     * must be scanned as supplied by the manufacturer.
     */
    public enum BarcodeSource {
        INTERNAL,
        GS1
    }

    public static BarcodeSource source(String value, BarcodeFormat format) {
        if (format != BarcodeFormat.EAN13 || value == null || !EAN13_VALUE.matcher(value).matches()) {
            return BarcodeSource.INTERNAL;
        }
        String prefix = value.substring(0, 2);
        return "02".equals(prefix) || (prefix.compareTo("20") >= 0 && prefix.compareTo("29") <= 0)
                ? BarcodeSource.INTERNAL
                : BarcodeSource.GS1;
    }

    private String effectiveEan13Prefix() {
        return ean13Prefix != null && DIGITS.matcher(ean13Prefix).matches()
                ? ean13Prefix
                : DEFAULT_EAN13_PREFIX;
    }

    private String randomCode128() {
        StringBuilder value = new StringBuilder(CODE128_PREFIX);
        for (int index = 0; index < 10; index++) {
            value.append(RANDOM.nextInt(10));
        }
        return value.toString();
    }

    private String randomEan13() {
        String prefix = effectiveEan13Prefix();
        StringBuilder base = new StringBuilder(prefix);
        while (base.length() < 12) {
            base.append(RANDOM.nextInt(10));
        }
        String twelve = base.toString();
        return twelve + ean13CheckDigit(twelve);
    }
}
