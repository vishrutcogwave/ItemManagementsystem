package com.itemmanagement.dto.transaction;

import com.itemmanagement.entity.enums.PurchaseStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tax is never accepted from the client: it is resolved from each item's tax
 * master and the supplier/branch place of supply on the server.
 */
public record PurchaseRequest(
        @NotNull(message = "Branch is required") Long branchId,
        @NotNull(message = "Supplier is required") Long supplierId,
        @Size(max = 80, message = "Invoice number is too long") String invoiceNumber,
        @NotNull(message = "Purchase date is required") LocalDate purchaseDate,
        @DecimalMin(value = "0", message = "Discount cannot be negative") BigDecimal discount,
        @DecimalMin(value = "0", message = "Paid amount cannot be negative") BigDecimal paidAmount,
        @Size(max = 500, message = "Notes are too long") String notes,
        PurchaseStatus status,
        @NotEmpty(message = "Add at least one item") @Valid List<Line> items
) {
    public record Line(
            @NotNull(message = "Item is required") Long itemId,
            @NotNull(message = "Quantity is required")
            @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity,
            String unit,
            @NotNull(message = "Purchase rate is required")
            @DecimalMin(value = "0", message = "Purchase rate cannot be negative") BigDecimal purchaseRate,
            @DecimalMin(value = "0", message = "Discount cannot be negative") BigDecimal discount
    ) {
    }
}
