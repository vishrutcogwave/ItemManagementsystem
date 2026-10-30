package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SalesReportResponse(
        List<Row> rows,
        BigDecimal totalBaseQuantity,
        BigDecimal totalAmount,
        long entries
) {
    public record Row(
            String salesNumber,
            LocalDate salesDate,
            String branchName,
            String customerName,
            String itemCode,
            String itemName,
            BigDecimal quantity,
            String unit,
            BigDecimal sellingRate,
            BigDecimal totalAmount
    ) {
    }
}
