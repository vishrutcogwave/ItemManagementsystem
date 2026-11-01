package com.itemmanagement.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String PASSWORD_CHANGE_CODE = "PASSWORD_CHANGE_REQUIRED";
    public static final String CSRF_CODE = "CSRF_ORIGIN_REJECTED";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> PASSWORD_CHANGE_EXEMPT = Set.of(
            "/api/auth/change-password", "/api/auth/logout", "/api/auth/me",
            "/api/auth/refresh", "/api/health", "/api/version", "/error");

    private final JwtService jwtService;
    private final TokenBlacklistService blacklistService;
    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final AuthTokenResolver tokenResolver;
    private final ObjectMapper objectMapper;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = tokenResolver.resolve(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        boolean cookieSession = tokenResolver.fromCookie(request);
        try {
            if (cookieSession && requiresOriginCheck(request) && !trustedOrigin(request)) {
                writeError(response, 403, CSRF_CODE,
                        "This request was blocked because its origin could not be verified");
                return;
            }
            Claims claims = jwtService.parse(token);
            if (!blacklistService.isRevoked(claims.getId())) {
                User account = activeAccount(claims);
                if (account != null) {
                    if (account.isMustChangePassword() && !isPasswordChangeExempt(request)) {
                        writeError(response, 403, PASSWORD_CHANGE_CODE,
                                "Change your password before continuing");
                        return;
                    }
                    AuthUser user = jwtService.toAuthUser(claims);
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    user.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
                    user.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(user, token, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }

    /**
     * Reloads the account on every request so deactivation, password changes and
     * token-version bumps take effect immediately and across restarts.
     */
    private User activeAccount(Claims claims) {
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (Exception ex) {
            return null;
        }
        User account = userRepository.findById(userId).orElse(null);
        if (account == null || account.getStatus() != StoreStatus.ACTIVE) {
            return null;
        }
        Object versionClaim = claims.get("ver");
        int tokenVersion = versionClaim instanceof Number number ? number.intValue() : 0;
        if (tokenVersion != account.getTokenVersion()) {
            return null;
        }
        Object storeClaim = claims.get("storeId");
        Long tokenStoreId = storeClaim instanceof Number number ? number.longValue() : null;
        if (!Objects.equals(account.getStoreId(), tokenStoreId)) {
            return null;
        }
        boolean storeActive = tokenStoreId == null || storeRepository.findById(tokenStoreId)
                .map(store -> store.getStatus() == StoreStatus.ACTIVE)
                .orElse(false);
        return storeActive ? account : null;
    }

    private boolean isPasswordChangeExempt(HttpServletRequest request) {
        return PASSWORD_CHANGE_EXEMPT.contains(request.getRequestURI());
    }

    private boolean requiresOriginCheck(HttpServletRequest request) {
        return !SAFE_METHODS.contains(request.getMethod().toUpperCase());
    }

    private boolean trustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return allowedOrigins().contains(origin.trim());
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            try {
                URI uri = URI.create(referer.trim());
                String value = uri.getScheme() + "://" + uri.getAuthority();
                return allowedOrigins().contains(value);
            } catch (Exception ignored) {
                return false;
            }
        }
        // Browsers always send Origin for state-changing cookie requests.
        return false;
    }

    private Set<String> allowedOrigins() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("error", status == 401 ? "Unauthorized" : "Forbidden");
        body.put("code", code);
        body.put("message", message);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
