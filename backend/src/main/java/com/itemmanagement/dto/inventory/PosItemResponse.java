package com.itemmanagement.dto.inventory;

import java.math.BigDecimal;

public record PosItemResponse(
        Long id,
        String itemCode,
        String barcode,
        String barcodeFormat,
        String itemName,
        Long categoryId,
        String categoryName,
        String unit,
        BigDecimal rate,
        BigDecimal availableStock,
        boolean lowStock,
        Long taxId,
        String taxName,
        BigDecimal gstPercentage,
        String taxType,
        boolean taxInclusive
) {
}
