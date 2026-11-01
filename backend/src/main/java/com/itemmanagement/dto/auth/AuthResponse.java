package com.itemmanagement.dto.auth;

import java.util.List;
import java.util.Set;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresIn,
        UserProfile user,
        StoreProfile store,
        List<BranchProfile> branches,
        Set<String> roles,
        Set<String> permissions,
        boolean allBranches,
        boolean platformAdmin,
        boolean mustChangePassword
) {
    public record UserProfile(Long id, String username, String fullName, String email, String mobile) {
    }

    public record StoreProfile(Long id, String storeCode, String storeName, String ownerName,
                               String email, String mobile, String address, String gstNumber,
                               boolean taxInclusive, String status) {
    }

    public record BranchProfile(Long id, String branchCode, String branchName, String state) {
    }
}
