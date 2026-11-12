package com.itemmanagement.dto.supplier;

import com.itemmanagement.entity.enums.SupplierPaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SupplierPaymentResponse(
        Long id,
        String paymentNumber,
        LocalDate paymentDate,
        Long supplierId,
        String supplierCode,
        String supplierName,
        SupplierPaymentMethod paymentMethod,
        BigDecimal amount,
        String referenceNumber,
        String remarks,
        Long createdBy,
        String createdByName,
        Instant createdAt,
        List<AllocationResponse> allocations
) {
    public record AllocationResponse(
            Long purchaseId,
            String purchaseNumber,
            LocalDate purchaseDate,
            BigDecimal amount
    ) {
    }
}
