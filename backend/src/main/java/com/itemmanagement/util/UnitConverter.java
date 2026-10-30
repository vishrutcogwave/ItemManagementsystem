package com.itemmanagement.util;

import com.itemmanagement.entity.enums.Unit;
import com.itemmanagement.exception.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class UnitConverter {

    private UnitConverter() {
    }

    public static BigDecimal toBase(BigDecimal quantity, Unit from, Unit base) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("Quantity must be greater than zero");
        }
        if (from == null) {
            from = base;
        }
        if (from == base) {
            return quantity.setScale(3, RoundingMode.HALF_UP);
        }
        String fromFamily = family(from);
        String baseFamily = family(base);
        if (!fromFamily.equals(baseFamily)) {
            throw ApiException.badRequest("Unit " + from + " cannot be converted to " + base);
        }
        BigDecimal factorFrom = factor(from);
        BigDecimal factorBase = factor(base);
        return quantity.multiply(factorFrom)
                .divide(factorBase, 3, RoundingMode.HALF_UP);
    }

    public static BigDecimal toBase(BigDecimal quantity, String from, Unit base) {
        return toBase(quantity, parse(from), base);
    }

    public static Unit parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Unit.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unsupported unit: " + value);
        }
    }

    private static String family(Unit unit) {
        return switch (unit) {
            case LITER, ML -> "VOLUME";
            case KG, GRAM -> "MASS";
            case PIECE, DOZEN -> "COUNT";
            case PACKET -> "PACKET";
            case BOX -> "BOX";
            case BOTTLE -> "BOTTLE";
        };
    }

    private static BigDecimal factor(Unit unit) {
        return switch (unit) {
            case LITER -> new BigDecimal("1000");
            case ML -> BigDecimal.ONE;
            case KG -> new BigDecimal("1000");
            case GRAM -> BigDecimal.ONE;
            case DOZEN -> new BigDecimal("12");
            case PIECE, PACKET, BOX, BOTTLE -> BigDecimal.ONE;
        };
    }
}
