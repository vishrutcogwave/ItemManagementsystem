package com.itemmanagement.dto.supplier;

import com.itemmanagement.entity.enums.SupplierPaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A payment is always allocated to the selected completed bills; the server
 * decides the per-bill split, oldest bill first. clientReference is supplied by
 * the browser and makes repeated submissions of the same form idempotent.
 */
public record SupplierPaymentRequest(
        @NotNull(message = "Supplier is required") Long supplierId,
        @NotNull(message = "Payment date is required") LocalDate paymentDate,
        SupplierPaymentMethod paymentMethod,
        @NotNull(message = "Payment amount is required")
        @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero") BigDecimal amount,
        @Size(max = 80, message = "Reference number is too long") String referenceNumber,
        @Size(max = 500, message = "Remarks are too long") String remarks,
        @Size(max = 80, message = "Client reference is too long") String clientReference,
        @NotEmpty(message = "Select at least one purchase bill")
        @Size(max = 100, message = "Too many bills selected") List<Long> purchaseIds
) {
}
