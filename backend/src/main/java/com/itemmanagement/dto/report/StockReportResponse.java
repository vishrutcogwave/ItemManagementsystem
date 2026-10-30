package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.util.List;

public record StockReportResponse(
        List<Row> rows,
        BigDecimal totalCurrentStock,
        BigDecimal totalStockValue
) {
    public record Row(
            String itemCode,
            String itemName,
            String categoryName,
            String branchName,
            BigDecimal openingStock,
            BigDecimal purchased,
            BigDecimal sold,
            BigDecimal transferIn,
            BigDecimal transferOut,
            BigDecimal adjustmentIn,
            BigDecimal adjustmentOut,
            BigDecimal currentStock,
            String unit
    ) {
    }
}
