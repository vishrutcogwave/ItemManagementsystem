package com.itemmanagement.dto.inventory;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryTransactionResponse(
        Long id,
        Long branchId,
        String branchName,
        Long itemId,
        String itemName,
        String transactionType,
        String referenceType,
        String referenceNumber,
        BigDecimal quantityIn,
        BigDecimal quantityOut,
        BigDecimal balanceAfter,
        String unit,
        String notes,
        Instant transactionDate
) {
}
