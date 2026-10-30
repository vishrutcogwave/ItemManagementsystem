package com.itemmanagement.dto.master;

import com.itemmanagement.entity.enums.StoreStatus;

import java.time.Instant;

public record CategoryResponse(
        Long id,
        String categoryName,
        String description,
        String status,
        long itemCount,
        Instant createdAt
) {
}
