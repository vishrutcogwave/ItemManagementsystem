package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.util.List;

public record StoreReportResponse(
        BigDecimal totalPurchaseAmount,
        BigDecimal totalSalesAmount,
        BigDecimal totalItemsSold,
        BigDecimal totalInventoryQuantity,
        BigDecimal totalInventoryValue,
        long totalLowStockItems,
        List<BranchReportResponse.Row> branches
) {
}
