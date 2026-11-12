package com.itemmanagement.dto.master;

import java.time.Instant;

public record UnitMasterResponse(
        Long id,
        Long storeId,
        String unitCode,
        String unitName,
        String description,
        String status,
        long itemCount,
        Instant createdAt,
        Instant updatedAt
) {
}
