package com.itemmanagement.dto.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record StockAdjustmentRequest(
        @NotNull(message = "Branch is required") Long branchId,
        @NotNull(message = "Item is required") Long itemId,
        @NotBlank(message = "Adjustment type is required")
        @Pattern(regexp = "IN|OUT", message = "Adjustment type must be IN or OUT") String type,
        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity,
        String unit,
        @NotBlank(message = "Reason is required")
        @Size(max = 255, message = "Reason is too long") String reason
) {
}
