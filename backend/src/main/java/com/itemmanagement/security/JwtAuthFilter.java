package com.itemmanagement.security;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenBlacklistService blacklistService;
    private final UserRepository userRepository;
    private final StoreRepository storeRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(7).trim();
        try {
            Claims claims = jwtService.parse(token);
            if (!blacklistService.isRevoked(claims.getId())) {
                AuthUser user = jwtService.toAuthUser(claims);
                if (isActive(user)) {
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

    // Rejects tokens for users or stores that were deactivated after the token was issued.
    private boolean isActive(AuthUser user) {
        User account = userRepository.findById(user.userId()).orElse(null);
        if (account == null || account.getStatus() != StoreStatus.ACTIVE) {
            return false;
        }
        if (!Objects.equals(account.getStoreId(), user.storeId())) {
            return false;
        }
        return user.storeId() == null || storeRepository.findById(user.storeId())
                .map(store -> store.getStatus() == StoreStatus.ACTIVE)
                .orElse(false);
    }
}
