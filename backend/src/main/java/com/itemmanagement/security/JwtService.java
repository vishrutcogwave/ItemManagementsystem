package com.itemmanagement.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMs) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = Keys.hmacShaKeyFor(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialise JWT key", e);
        }
        this.expirationMs = expirationMs;
    }

    public String generate(AuthUser user) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("storeId", user.storeId());
        claims.put("username", user.username());
        claims.put("fullName", user.fullName());
        claims.put("email", user.email());
        claims.put("mobile", user.mobile());
        claims.put("storeCode", user.storeCode());
        claims.put("storeName", user.storeName());
        claims.put("roles", user.roles());
        claims.put("permissions", user.permissions());
        claims.put("branchIds", user.branchIds());
        claims.put("allBranches", user.allBranches());
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(user.userId()))
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) throws JwtException {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public AuthUser toAuthUser(Claims claims) {
        return new AuthUser(
                Long.valueOf(claims.getSubject()),
                number(claims.get("storeId")),
                claims.get("username", String.class),
                claims.get("fullName", String.class),
                claims.get("email", String.class),
                claims.get("mobile", String.class),
                claims.get("storeCode", String.class),
                claims.get("storeName", String.class),
                stringSet(claims.get("roles")),
                stringSet(claims.get("permissions")),
                numberSet(claims.get("branchIds")),
                Boolean.TRUE.equals(claims.get("allBranches"))
        );
    }

    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }

    public Instant expiry(Claims claims) {
        return claims.getExpiration().toInstant();
    }

    private Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private Set<String> stringSet(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    @SuppressWarnings("unchecked")
    private Set<Long> numberSet(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(v -> ((Number) v).longValue()).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }
}
