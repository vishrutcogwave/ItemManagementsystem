package com.itemmanagement.dto.printing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DevicePairRequest(
        @NotBlank(message = "Pairing code is required")
        @Size(min = 6, max = 12, message = "Pairing code must be 6-12 characters")
        String code,

        @NotBlank(message = "Device name is required")
        @Size(min = 2, max = 100, message = "Device name must be 2-100 characters")
        String deviceName,

        @NotBlank(message = "Platform is required")
        String platform
) {
}
