package com.itemmanagement.dto.master;

import java.math.BigDecimal;
import java.time.Instant;

public record TaxMasterResponse(
        Long id,
        Long storeId,
        String taxName,
        String description,
        BigDecimal gstPercentage,
        BigDecimal cgstPercentage,
        BigDecimal sgstPercentage,
        BigDecimal igstPercentage,
        String taxType,
        String status,
        long itemCount,
        Instant createdAt,
        Instant updatedAt
) {
}
