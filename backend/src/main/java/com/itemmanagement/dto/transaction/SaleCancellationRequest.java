package com.itemmanagement.dto.transaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaleCancellationRequest(
        @NotBlank(message = "Cancellation reason is required")
        @Size(max = 500, message = "Reason is too long")
        String reason
) {
}
