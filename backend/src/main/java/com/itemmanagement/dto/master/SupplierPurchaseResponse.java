package com.itemmanagement.dto.master;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record SupplierPurchaseResponse(
        Long id,
        String purchaseNumber,
        LocalDate purchaseDate,
        String branchName,
        BigDecimal grandTotal,
        String status,
        Instant createdAt
) {
}
