package com.itemmanagement.dto.master;

import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.Unit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record ItemResponse(
        Long id,
        String itemCode,
        String itemName,
        String description,
        Long categoryId,
        String categoryName,
        Long taxId,
        String taxName,
        BigDecimal gstPercentage,
        String taxType,
        String baseUnit,
        BigDecimal rate,
        BigDecimal minStockLevel,
        String status,
        Instant createdAt
) {
    public record Request(
            @NotBlank(message = "Item code is required")
            @Pattern(regexp = "^[A-Za-z0-9._-]{2,50}$", message = "Use 2-50 letters, numbers, dot, underscore or hyphen")
            String itemCode,
            @NotBlank(message = "Item name is required") @Size(max = 150) String itemName,
            @Size(max = 255) String description,
            @NotNull(message = "Category is required") Long categoryId,
            @NotNull(message = "Tax is required. Select an exempt tax for tax-free items") Long taxId,
            @NotNull(message = "Unit is required") Unit baseUnit,
            @NotNull(message = "Rate is required")
            @DecimalMin(value = "0.01", message = "Rate must be greater than zero")
            BigDecimal rate,
            @NotNull(message = "Minimum stock is required") @DecimalMin(value = "0", message = "Cannot be negative")
            BigDecimal minStockLevel,
            StoreStatus status
    ) {
    }
}
