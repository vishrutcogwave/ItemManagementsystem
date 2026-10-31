package com.itemmanagement.dto.printing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PrintJobStatusRequest(
        @NotBlank(message = "Print status is required")
        String status,

        @Size(max = 500, message = "Error message must be 500 characters or less")
        String errorMessage
) {
}
