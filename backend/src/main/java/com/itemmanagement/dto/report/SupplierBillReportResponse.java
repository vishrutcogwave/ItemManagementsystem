package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SupplierBillReportResponse(
        List<Row> rows,
        Totals totals
) {
    public record Row(
            Long supplierId,
            String supplierCode,
            String supplierName,
            String purchaseNumber,
            String invoiceNumber,
            LocalDate purchaseDate,
            String branchName,
            BigDecimal grandTotal,
            BigDecimal paidAmount,
            BigDecimal balanceAmount,
            String settlementStatus
    ) {
    }

    public record Totals(
            BigDecimal grandTotal,
            BigDecimal paidAmount,
            BigDecimal balanceAmount,
            int bills
    ) {
    }
}
