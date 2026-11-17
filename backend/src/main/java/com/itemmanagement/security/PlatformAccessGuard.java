package com.itemmanagement.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Method-security expression target. The principal is rebuilt from the
 * database on every request by JwtAuthFilter, so the platform flag reflects
 * the account's real store association and its global role, not editable role
 * names or the caller's token claims.
 */
@Component("platformAccess")
public class PlatformAccessGuard {

    public boolean isPlatformAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getPrincipal() instanceof AuthUser user
                && user.platformAdmin();
    }
}
