package com.itemmanagement.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenBlacklistService {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    public void revoke(String tokenId, Instant expiresAt) {
        if (tokenId != null && expiresAt != null) {
            revoked.put(tokenId, expiresAt);
        }
    }

    public boolean isRevoked(String tokenId) {
        if (tokenId == null) return false;
        Instant expiry = revoked.get(tokenId);
        if (expiry == null) return false;
        if (expiry.isBefore(Instant.now())) {
            revoked.remove(tokenId);
            return false;
        }
        return true;
    }

    public void purgeExpired() {
        Instant now = Instant.now();
        revoked.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }
}
