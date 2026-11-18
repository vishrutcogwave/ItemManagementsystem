package com.itemmanagement.dto.transaction;

import java.math.BigDecimal;
import java.time.Instant;

public record SaleCancellationResponse(
        Long saleId,
        String salesNumber,
        String cancellationNumber,
        String reason,
        Instant cancelledAt,
        Long cancelledBy,
        String cancelledByName,
        BigDecimal saleTotal,
        BigDecimal paidAmount,
        BigDecimal balanceAmount,
        int restoredItems,
        String status
) {
}
