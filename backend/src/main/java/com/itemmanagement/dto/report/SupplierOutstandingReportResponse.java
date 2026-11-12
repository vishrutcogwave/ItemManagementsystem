package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.util.List;

public record SupplierOutstandingReportResponse(
        List<Row> rows,
        Totals totals
) {
    public record Row(
            Long supplierId,
            String supplierCode,
            String supplierName,
            BigDecimal totalPurchaseAmount,
            BigDecimal totalPaid,
            BigDecimal outstandingAmount,
            int billCount,
            int pendingBillCount
    ) {
    }

    public record Totals(
            BigDecimal totalPurchaseAmount,
            BigDecimal totalPaid,
            BigDecimal outstandingAmount,
            int supplierCount
    ) {
    }
}
