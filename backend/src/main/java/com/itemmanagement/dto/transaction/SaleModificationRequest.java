package com.itemmanagement.dto.transaction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full replacement of a bill's lines. Lines that are absent are removed, new
 * item ids are added, and the remaining lines are updated in place. The base
 * modification count protects against a second submission of the same edit.
 */
public record SaleModificationRequest(
        @Size(max = 500, message = "Reason is too long") String reason,
        @NotNull(message = "Reload the bill and try again") Integer baseModificationCount,
        @NotEmpty(message = "A bill must keep at least one item") @Valid List<Line> items,
        @Size(max = 4) List<@NotNull @Valid SalePayment> payments
) {
    public record Line(
            @NotNull(message = "Item is required") Long itemId,
            @NotNull(message = "Quantity is required")
            @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity,
            String unit,
            @NotNull(message = "Rate is required")
            @DecimalMin(value = "0", message = "Rate cannot be negative") BigDecimal sellingRate,
            @DecimalMin(value = "0", message = "Discount cannot be negative") BigDecimal discount
    ) {
    }
}
