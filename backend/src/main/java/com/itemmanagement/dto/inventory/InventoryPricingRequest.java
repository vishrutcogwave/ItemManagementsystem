package com.itemmanagement.dto.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record InventoryPricingRequest(
        @NotNull(message = "Minimum stock is required")
        @DecimalMin(value = "0", message = "Minimum stock cannot be negative") BigDecimal minimumStock,
        @NotNull(message = "Purchase price is required")
        @DecimalMin(value = "0", message = "Purchase price cannot be negative") BigDecimal purchasePrice,
        @NotNull(message = "Selling price is required")
        @DecimalMin(value = "0", message = "Selling price cannot be negative") BigDecimal sellingPrice
) {
}
