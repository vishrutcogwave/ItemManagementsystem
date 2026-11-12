package com.itemmanagement.security;

import com.itemmanagement.entity.RevokedToken;
import com.itemmanagement.repository.RevokedTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persistent JWT revocation. Entries are written to the database so a logout
 * or a rotated token stays invalid after a restart or on another instance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenBlacklistService {

    private final RevokedTokenRepository repository;

    @Transactional
    public void revoke(String tokenId, Instant expiresAt) {
        if (tokenId == null || expiresAt == null || expiresAt.isBefore(Instant.now())) {
            return;
        }
        try {
            repository.save(RevokedToken.builder()
                    .jti(tokenId)
                    .expiresAt(expiresAt)
                    .revokedAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException duplicate) {
            // Revoking the same token twice is a no-op.
        }
    }

    /**
     * Revokes a token in a transaction of its own so the entry survives an
     * outer transaction that rolls back, such as a rejected login attempt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeInNewTransaction(String tokenId, Instant expiresAt) {
        revoke(tokenId, expiresAt);
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(String tokenId) {
        if (tokenId == null) return false;
        try {
            Instant expiry = repository.findExpiry(tokenId);
            if (expiry == null) return false;
            return !expiry.isBefore(Instant.now());
        } catch (Exception e) {
            // Fail closed: an unreadable blacklist must not authenticate a token.
            log.warn("Unable to read the token revocation list: {}", e.getMessage());
            return true;
        }
    }

    @Scheduled(fixedDelayString = "${app.auth.blacklist-cleanup-ms:3600000}")
    @Transactional
    public void purgeExpired() {
        int removed = repository.deleteExpiredBefore(Instant.now());
        if (removed > 0) {
            log.debug("Removed {} expired token revocation entries", removed);
        }
    }
}
