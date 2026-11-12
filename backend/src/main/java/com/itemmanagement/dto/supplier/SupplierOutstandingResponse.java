package com.itemmanagement.dto.supplier;

import java.math.BigDecimal;

public record SupplierOutstandingResponse(
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
