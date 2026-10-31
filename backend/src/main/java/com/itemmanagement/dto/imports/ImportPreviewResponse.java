package com.itemmanagement.dto.imports;

import java.time.Instant;
import java.util.List;

public record ImportPreviewResponse(
        Long batchId,
        String importNumber,
        String fileName,
        Long branchId,
        String branchName,
        String status,
        Instant createdAt,
        Instant completedAt,
        String failureMessage,
        ImportSummary summary,
        List<ImportRowResponse> rows
) {
}
