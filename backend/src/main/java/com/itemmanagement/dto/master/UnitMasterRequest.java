package com.itemmanagement.dto.master;

import com.itemmanagement.entity.enums.StoreStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UnitMasterRequest(
        @NotBlank(message = "Unit code is required")
        @Size(max = 20)
        @Pattern(regexp = "^[A-Za-z0-9._-]{1,20}$",
                message = "Use 1-20 letters, numbers, dot, underscore or hyphen")
        String unitCode,
        @NotBlank(message = "Unit name is required") @Size(max = 80) String unitName,
        @Size(max = 255) String description,
        StoreStatus status
) {
}
