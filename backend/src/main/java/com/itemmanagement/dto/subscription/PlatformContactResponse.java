package com.itemmanagement.dto.subscription;

import java.time.Instant;

public record PlatformContactResponse(
        String contactEmail,
        String contactPhone,
        Instant updatedAt
) {
}
