package com.itemmanagement.security;

import java.util.Locale;
import java.util.Set;

/**
 * Platform identity and authority rules shared by the security filter, the
 * service layer and controller authorization expressions. Store-scoped roles
 * can never hold the reserved PLATFORM_* namespace or platform-only
 * permissions, so platform access is derived only from a global account with a
 * global PLATFORM_ADMIN role.
 */
public final class PlatformPolicy {

    public static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    public static final String PLATFORM_MANAGE_PERMISSION = "PLATFORM_MANAGE";

    /** Granted by JwtAuthFilter from server-side identity, never from token claims. */
    public static final String TRUSTED_AUTHORITY = "PLATFORM_ADMIN_TRUSTED";

    /** Authorization expression for every platform-only endpoint. */
    public static final String PLATFORM_ADMIN_ONLY =
            "hasAuthority('" + PLATFORM_MANAGE_PERMISSION + "') and @platformAccess.isPlatformAdmin(authentication)";

    private static final String RESERVED_ROLE_PREFIX = "PLATFORM_";
    private static final Set<String> PLATFORM_ONLY_PERMISSIONS = Set.of(PLATFORM_MANAGE_PERMISSION);

    private PlatformPolicy() {
    }

    public static boolean isReservedRoleCode(String code) {
        return code != null && code.trim().toUpperCase(Locale.ROOT).startsWith(RESERVED_ROLE_PREFIX);
    }

    public static boolean isPlatformOnlyPermission(String code) {
        return code != null && PLATFORM_ONLY_PERMISSIONS.contains(code.trim().toUpperCase(Locale.ROOT));
    }
}
