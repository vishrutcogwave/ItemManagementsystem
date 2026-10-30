package com.itemmanagement.dto.inventory;

import java.math.BigDecimal;

public record BranchStockResponse(
        Long branchId,
        String branchCode,
        String branchName,
        BigDecimal currentStock,
        BigDecimal minimumStock,
        BigDecimal purchasePrice,
        BigDecimal sellingPrice,
        String unit,
        boolean lowStock
) {
}
