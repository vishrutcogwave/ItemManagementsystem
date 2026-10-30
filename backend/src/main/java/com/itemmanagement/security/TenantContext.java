package com.itemmanagement.security;

import com.itemmanagement.exception.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class TenantContext {

    private TenantContext() {
    }

    public static AuthUser user() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUser authUser)) {
            throw ApiException.unauthorized("Authentication required");
        }
        return authUser;
    }

    public static Long storeId() {
        AuthUser user = user();
        if (user.storeId() == null) {
            throw ApiException.forbidden("Platform administrators cannot access store business data");
        }
        return user.storeId();
    }

    public static Long userId() {
        return user().userId();
    }

    public static void requireBranch(Long branchId) {
        if (branchId == null) {
            throw ApiException.badRequest("Branch is required");
        }
        AuthUser user = user();
        if (!user.canAccessBranch(branchId)) {
            throw ApiException.forbidden("You do not have access to this branch");
        }
    }

    public static boolean canAccessAllBranches() {
        return user().allBranches();
    }
}
