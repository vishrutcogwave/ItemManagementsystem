package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.util.List;

public record GstReportResponse(
        List<Row> rows,
        Totals totals
) {
    public record Row(
            Long branchId,
            String branchName,
            String taxName,
            BigDecimal gstPercentage,
            BigDecimal cgstPercentage,
            BigDecimal sgstPercentage,
            BigDecimal igstPercentage,
            BigDecimal taxableAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal taxAmount,
            long invoiceCount
    ) {
    }

    public record Totals(
            BigDecimal taxableAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal taxAmount,
            long invoiceCount
    ) {
    }
}
