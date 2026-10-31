package com.itemmanagement.dto.printing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PrinterConfigRequest(
        @NotBlank(message = "Printer name is required")
        @Size(min = 2, max = 100, message = "Printer name must be 2-100 characters")
        String printerName,

        @NotBlank(message = "Printer IP address or hostname is required")
        @Size(max = 120)
        @Pattern(
                regexp = "^(25[0-5]|2[0-4]\\d|[01]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[01]?\\d?\\d)){3}$"
                        + "|^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
                        + "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$",
                message = "Enter a valid IPv4 address or hostname")
        String ipAddress,

        @NotNull(message = "Port is required")
        @Min(value = 1, message = "Port must be between 1 and 65535")
        @Max(value = 65535, message = "Port must be between 1 and 65535")
        Integer port,

        @NotBlank(message = "Paper width is required")
        String paperWidth,

        @NotBlank(message = "Printer purpose is required")
        String purpose,

        @NotNull(message = "Branch is required")
        Long branchId,

        String status
) {
}
