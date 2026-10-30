package com.itemmanagement.dto.transaction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TransferRequest(
        @NotNull(message = "Source branch is required") Long fromBranchId,
        @NotNull(message = "Destination branch is required") Long toBranchId,
        LocalDate transferDate,
        @Size(max = 500, message = "Notes are too long") String notes,
        @NotEmpty(message = "Add at least one item") @Valid List<Line> items
) {
    public record Line(
            @NotNull(message = "Item is required") Long itemId,
            @NotNull(message = "Quantity is required")
            @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity,
            String unit
    ) {
    }
}
