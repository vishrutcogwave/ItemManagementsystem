package com.itemmanagement.dto.platform;

import java.time.Instant;

/**
 * The temporary password is returned once so the platform administrator can
 * hand it to the account owner. It is never persisted in plain text, returned
 * again, or written to audit logs.
 */
public record PlatformPasswordResetResponse(
        Long userId,
        String username,
        String storeCode,
        String temporaryPassword,
        Instant generatedAt
) {
}
