package com.itemmanagement.util;

import com.itemmanagement.exception.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Converts quantities between units of the same legacy family. Units created in
 * the Unit Master are treated as their own family with a factor of one, so they
 * only convert to themselves.
 */
public final class UnitConverter {

    private static final Map<String, String> FAMILIES = Map.of(
            "LITER", "VOLUME",
            "ML", "VOLUME",
            "KG", "MASS",
            "GRAM", "MASS",
            "PIECE", "COUNT",
            "DOZEN", "COUNT",
            "PACKET", "PACKET",
            "BOX", "BOX",
            "BOTTLE", "BOTTLE");

    private static final Map<String, BigDecimal> FACTORS = Map.of(
            "LITER", new BigDecimal("1000"),
            "ML", BigDecimal.ONE,
            "KG", new BigDecimal("1000"),
            "GRAM", BigDecimal.ONE,
            "PIECE", BigDecimal.ONE,
            "DOZEN", new BigDecimal("12"),
            "PACKET", BigDecimal.ONE,
            "BOX", BigDecimal.ONE,
            "BOTTLE", BigDecimal.ONE);

    private UnitConverter() {
    }

    public static BigDecimal toBase(BigDecimal quantity, String from, String base) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("Quantity must be greater than zero");
        }
        String unit = from == null || from.isBlank() ? base : normalize(from);
        String baseUnit = normalize(base);
        if (unit.equals(baseUnit)) {
            return quantity.setScale(3, RoundingMode.HALF_UP);
        }
        String fromFamily = FAMILIES.getOrDefault(unit, unit);
        String baseFamily = FAMILIES.getOrDefault(baseUnit, baseUnit);
        if (!fromFamily.equals(baseFamily)) {
            throw ApiException.badRequest("Unit " + unit + " cannot be converted to " + baseUnit);
        }
        BigDecimal factorFrom = FACTORS.getOrDefault(unit, BigDecimal.ONE);
        BigDecimal factorBase = FACTORS.getOrDefault(baseUnit, BigDecimal.ONE);
        return quantity.multiply(factorFrom)
                .divide(factorBase, 3, RoundingMode.HALF_UP);
    }

    public static String parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalize(value);
    }

    public static String normalize(String value) {
        if (value == null) {
            throw ApiException.badRequest("Unit is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("Unit is required");
        }
        return normalized;
    }
}
