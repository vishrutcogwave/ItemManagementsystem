package com.itemmanagement.dto.inventory;

import java.math.BigDecimal;

public record InventoryRowResponse(
        Long id,
        Long branchId,
        String branchCode,
        String branchName,
        Long itemId,
        String itemCode,
        String itemName,
        Long categoryId,
        String categoryName,
        String unit,
        BigDecimal currentStock,
        BigDecimal minimumStock,
        BigDecimal purchasePrice,
        BigDecimal sellingPrice,
        BigDecimal stockValue,
        boolean lowStock
) {
}
