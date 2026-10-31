package com.itemmanagement.dto.printing;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PrinterTestResultRequest(
        @NotNull(message = "Test result is required")
        Boolean success,

        @Size(max = 255, message = "Message must be 255 characters or less")
        String message
) {
}
