package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.util.List;

public record BranchReportResponse(
        List<Row> rows,
        Totals totals
) {
    public record Row(
            Long branchId,
            String branchName,
            BigDecimal totalPurchases,
            BigDecimal totalSales,
            BigDecimal itemsSold,
            BigDecimal stockValue,
            long lowStockItems
    ) {
    }

    public record Totals(
            BigDecimal totalPurchases,
            BigDecimal totalSales,
            BigDecimal itemsSold,
            BigDecimal stockValue,
            long lowStockItems
    ) {
    }
}
