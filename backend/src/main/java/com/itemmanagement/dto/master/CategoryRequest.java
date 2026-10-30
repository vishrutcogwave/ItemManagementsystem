package com.itemmanagement.dto.master;

import com.itemmanagement.entity.enums.StoreStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
        @NotBlank(message = "Category name is required") @Size(max = 120) String categoryName,
        @Size(max = 255) String description,
        StoreStatus status
) {
}
