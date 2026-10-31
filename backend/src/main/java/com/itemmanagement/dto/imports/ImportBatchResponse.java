package com.itemmanagement.dto.imports;

import java.time.Instant;

public record ImportBatchResponse(
        Long batchId,
        String importNumber,
        String fileName,
        Long branchId,
        String branchName,
        String status,
        int totalRows,
        int successRows,
        int failedRows,
        int newCategories,
        int newItems,
        int existingItems,
        int stockUpdates,
        int duplicateRows,
        int invalidRows,
        int alreadyImportedRows,
        String failureMessage,
        String uploadedBy,
        Instant createdAt,
        Instant completedAt
) {
}
