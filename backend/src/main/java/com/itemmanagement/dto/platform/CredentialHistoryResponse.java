package com.itemmanagement.dto.platform;

import java.time.Instant;

public record CredentialHistoryResponse(
        Long id,
        String action,
        String actorUsername,
        String details,
        Instant createdAt
) {
}
