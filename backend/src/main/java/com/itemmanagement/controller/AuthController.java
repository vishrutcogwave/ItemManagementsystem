package com.itemmanagement.controller;

import com.itemmanagement.dto.auth.AuthResponse;
import com.itemmanagement.dto.auth.ChangePasswordRequest;
import com.itemmanagement.dto.auth.LoginRequest;
import com.itemmanagement.dto.common.MessageResponse;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.security.AuthCookieService;
import com.itemmanagement.security.AuthTokenResolver;
import com.itemmanagement.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookieService cookieService;
    private final AuthTokenResolver tokenResolver;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        AuthResponse response = authService.login(request, clientAddress(httpRequest),
                tokenResolver.resolve(httpRequest));
        return sessionResponse(response);
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(authService.me());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        return sessionResponse(authService.refresh(tokenResolver.resolve(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(HttpServletRequest request) {
        authService.logout(tokenResolver.resolve(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieService.clear().toString())
                .cacheControl(CacheControl.noStore())
                .body(new MessageResponse("Signed out"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                       HttpServletRequest httpRequest) {
        return sessionResponse(authService.changePassword(request, tokenResolver.resolve(httpRequest)));
    }

    /**
     * Public self-registration is permanently disabled. Stores are created only
     * by a Platform Administrator through POST /api/stores.
     */
    @PostMapping("/register-store")
    public MessageResponse registerStore() {
        throw ApiException.forbidden(
                "Public store registration is disabled. Contact the platform administrator to create your business account.");
    }

    /**
     * The browser always receives the token as an HttpOnly cookie so JavaScript
     * cannot read it. The token field is retained in the body for API clients and
     * automated tests; browser code never persists it.
     */
    private ResponseEntity<AuthResponse> sessionResponse(AuthResponse response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache");
        if (response.token() != null && !response.token().isBlank()) {
            builder.header(HttpHeaders.SET_COOKIE, cookieService.issue(response.token()).toString());
        }
        return builder.body(response);
    }

    /**
     * Resolved by Tomcat's RemoteIpValve: X-Forwarded-For is honoured only when
     * the connection comes from the loopback proxy, and the visitor address is
     * then the rightmost entry NGINX appended. Every other peer is keyed on its
     * own socket address.
     */
    private String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
