package com.itemmanagement.dto.printing;

import java.time.Instant;

public record PrintJobResponse(
        Long id,
        String jobKey,
        String documentType,
        Long documentId,
        String documentNumber,
        Long branchId,
        String branchName,
        Long printerConfigId,
        String printerName,
        String printerIpAddress,
        Integer printerPort,
        String paperWidth,
        String printerPurpose,
        String channel,
        String trigger,
        String status,
        int attempts,
        String errorMessage,
        String createdBy,
        Instant createdAt,
        Instant printedAt,
        boolean duplicate
) {
}
