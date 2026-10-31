package com.itemmanagement.dto.printing;

import jakarta.validation.constraints.NotBlank;

public record PrintJobRequest(
        @NotBlank(message = "Document type is required")
        String documentType,

        Long documentId,

        @NotBlank(message = "Print channel is required")
        String channel,

        @NotBlank(message = "Print trigger is required")
        String trigger,

        Long printerConfigId
) {
}
