package com.itemmanagement.dto.supplier;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SupplierBillResponse(
        Long purchaseId,
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
