package com.itemmanagement.dto.imports;

import java.math.BigDecimal;

public record ImportRowResponse(
        Long id,
        int rowNumber,
        String itemCode,
        String itemName,
        String categoryName,
        String unit,
        BigDecimal rate,
        String taxName,
        BigDecimal openingStock,
        BigDecimal minimumStock,
        String description,
        Long itemId,
        String importAction,
        String validationStatus,
        String errorMessage
) {
}
