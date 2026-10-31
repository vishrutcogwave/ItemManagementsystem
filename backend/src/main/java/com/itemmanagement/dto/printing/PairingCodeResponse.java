package com.itemmanagement.dto.printing;

import java.time.Instant;

public record PairingCodeResponse(
        String code,
        Long branchId,
        Instant expiresAt
) {
}
