package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SupplierLedgerReportResponse(
        Long supplierId,
        String supplierCode,
        String supplierName,
        BigDecimal openingBalance,
        List<Row> rows,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal closingBalance
) {
    public record Row(
            LocalDate date,
            String type,
            String reference,
            String description,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal balance
    ) {
    }
}
