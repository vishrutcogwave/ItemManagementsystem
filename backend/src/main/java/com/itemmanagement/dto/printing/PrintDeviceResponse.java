package com.itemmanagement.dto.printing;

import java.time.Instant;

public record PrintDeviceResponse(
        Long id,
        String deviceName,
        String platform,
        String status,
        String pairedBy,
        Instant createdAt,
        Instant lastSeenAt
) {
}
