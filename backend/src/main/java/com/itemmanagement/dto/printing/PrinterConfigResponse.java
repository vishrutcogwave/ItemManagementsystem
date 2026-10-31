package com.itemmanagement.dto.printing;

import java.time.Instant;

public record PrinterConfigResponse(
        Long id,
        Long storeId,
        Long branchId,
        String printerName,
        String ipAddress,
        int port,
        String paperWidth,
        String purpose,
        String status,
        Instant lastTestAt,
        String lastTestMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
