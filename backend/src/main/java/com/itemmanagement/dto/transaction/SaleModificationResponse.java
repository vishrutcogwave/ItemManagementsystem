package com.itemmanagement.dto.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SaleModificationResponse(
        Long modificationId,
        String modificationNumber,
        Instant modifiedAt,
        String reason,
        BigDecimal originalTotal,
        BigDecimal modifiedTotal,
        BigDecimal differenceAmount,
        BigDecimal additionalPayable,
        BigDecimal refundDue,
        BigDecimal originalQuantity,
        BigDecimal modifiedQuantity,
        BigDecimal quantityIncreased,
        BigDecimal quantityDecreased,
        int itemsAdded,
        int itemsRemoved,
        int itemsUpdated,
        List<LineDiff> lines,
        Long modifiedBy,
        List<SalePayment> paymentsBefore,
        List<SalePayment> paymentsAfter,
        SaleResponse sale
) {
    public record LineDiff(
            Long itemId,
            String itemCode,
            String itemName,
            String unit,
            String changeType,
            BigDecimal originalQuantity,
            BigDecimal modifiedQuantity,
            BigDecimal originalRate,
            BigDecimal modifiedRate,
            BigDecimal originalAmount,
            BigDecimal modifiedAmount,
            BigDecimal differenceAmount,
            BigDecimal taxAmount
    ) {
    }
}
