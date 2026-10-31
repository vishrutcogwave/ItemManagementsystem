package com.itemmanagement.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itemmanagement.dto.subscription.PlatformContactResponse;
import com.itemmanagement.service.SubscriptionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blocks store users whose subscription has expired. The store is read from the
 * database on every request so renewing the end date restores access immediately.
 * Platform administrators (storeId == null) are never blocked.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionAccessFilter extends OncePerRequestFilter {

    public static final String EXPIRED_CODE = "SUBSCRIPTION_EXPIRED";

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isExempt(request)) {
            chain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUser user)
                || user.storeId() == null) {
            chain.doFilter(request, response);
            return;
        }
        if (subscriptionService.isExpired(user.storeId())) {
            log.info("Blocked request from expired store {}: {} {}", user.storeId(),
                    request.getMethod(), request.getRequestURI());
            writeExpired(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isExempt(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return path.startsWith("/api/auth/")
                || path.equals("/api/auth")
                || path.equals("/api/subscription/summary")
                || path.equals("/error");
    }

    private void writeExpired(HttpServletResponse response) throws IOException {
        PlatformContactResponse contact = subscriptionService.contact();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpServletResponse.SC_FORBIDDEN);
        body.put("error", "Forbidden");
        body.put("code", EXPIRED_CODE);
        body.put("message", SubscriptionService.EXPIRED_MESSAGE);
        body.put("contactEmail", contact.contactEmail());
        body.put("contactPhone", contact.contactPhone());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
