package com.itemmanagement.security;

import java.util.Set;

public record AuthUser(
        Long userId,
        Long storeId,
        String username,
        String fullName,
        String email,
        String mobile,
        String storeCode,
        String storeName,
        Set<String> roles,
        Set<String> permissions,
        Set<Long> branchIds,
        boolean allBranches
) {
    public boolean isPlatformAdmin() {
        return roles.contains("PLATFORM_ADMIN");
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public boolean canAccessBranch(Long branchId) {
        return allBranches || branchIds.contains(branchId);
    }
}
