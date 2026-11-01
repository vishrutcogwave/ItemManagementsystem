package com.itemmanagement.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Issues the browser session as an HttpOnly cookie so JavaScript (and any XSS
 * payload) cannot read the JWT. Bearer tokens remain supported for API clients.
 */
@Service
public class AuthCookieService {

    private final String cookieName;
    private final boolean secure;
    private final String sameSite;
    private final long maxAgeSeconds;

    public AuthCookieService(@Value("${app.auth.cookie-name:ims_auth}") String cookieName,
                             @Value("${app.auth.cookie-secure:false}") boolean secure,
                             @Value("${app.auth.cookie-same-site:Strict}") String sameSite,
                             @Value("${app.jwt.expiration-ms:43200000}") long expirationMs) {
        this.cookieName = cookieName;
        this.secure = secure;
        this.sameSite = sameSite;
        this.maxAgeSeconds = Math.max(60, expirationMs / 1000);
    }

    public String cookieName() {
        return cookieName;
    }

    public ResponseCookie issue(String token) {
        return ResponseCookie.from(cookieName, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    public ResponseCookie clear() {
        return ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }
}
