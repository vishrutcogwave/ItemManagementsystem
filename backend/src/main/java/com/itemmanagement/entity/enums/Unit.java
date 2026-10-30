package com.itemmanagement.entity.enums;

public enum Unit {
    LITER, ML, KG, GRAM, PIECE, PACKET, BOX, BOTTLE, DOZEN;

    public static Unit from(String value) {
        try {
            return Unit.valueOf(value.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported unit: " + value);
        }
    }
}
