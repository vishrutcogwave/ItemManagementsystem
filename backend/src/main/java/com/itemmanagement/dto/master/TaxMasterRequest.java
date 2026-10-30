package com.itemmanagement.dto.master;

import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TaxType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TaxMasterRequest(
        @NotBlank(message = "Tax name is required") @Size(max = 120) String taxName,
        @Size(max = 255) String description,
        @NotNull(message = "GST percentage is required")
        @DecimalMin(value = "0", message = "GST cannot be negative")
        @DecimalMax(value = "100", message = "GST cannot exceed 100")
        BigDecimal gstPercentage,
        TaxType taxType,
        StoreStatus status
) {
}
