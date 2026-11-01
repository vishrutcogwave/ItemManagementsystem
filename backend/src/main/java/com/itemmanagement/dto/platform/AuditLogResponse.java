package com.itemmanagement.dto.platform;

import java.time.Instant;

public record AuditLogResponse(
        Long id,
        Long storeId,
        String storeCode,
        String storeName,
        Long userId,
        String username,
        String action,
        String entityType,
        Long entityId,
        String details,
        Instant createdAt
) {
}
