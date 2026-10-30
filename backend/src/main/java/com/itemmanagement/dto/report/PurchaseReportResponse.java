package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PurchaseReportResponse(
        List<Row> rows,
        BigDecimal totalBaseQuantity,
        BigDecimal totalAmount,
        long entries
) {
    public record Row(
            String purchaseNumber,
            LocalDate purchaseDate,
            String supplierName,
            String branchName,
            String itemCode,
            String itemName,
            BigDecimal quantity,
            String unit,
            BigDecimal purchaseRate,
            BigDecimal totalAmount
    ) {
    }
}
