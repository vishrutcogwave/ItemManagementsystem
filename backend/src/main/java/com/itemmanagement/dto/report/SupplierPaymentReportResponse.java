package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SupplierPaymentReportResponse(
        List<Row> rows,
        Totals totals
) {
    public record Row(
            String paymentNumber,
            LocalDate paymentDate,
            String supplierCode,
            String supplierName,
            String bills,
            String paymentMethod,
            BigDecimal amount,
            String referenceNumber,
            String remarks,
            String createdByName
    ) {
    }

    public record Totals(
            BigDecimal amount,
            int payments
    ) {
    }
}
