package com.itemmanagement.dto.platform;

import java.time.Instant;
import java.util.List;

public record PlatformAccountResponse(
        Long id,
        Long storeId,
        String storeCode,
        String storeName,
        String username,
        String fullName,
        String email,
        String mobile,
        String status,
        List<RoleSummary> roles,
        boolean owner,
        Instant createdAt,
        Instant updatedAt
) {
    public record RoleSummary(Long id, String code, String name) {
    }
}
