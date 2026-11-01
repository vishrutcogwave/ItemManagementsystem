package com.itemmanagement.security;

import com.itemmanagement.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force protection for the login endpoint. Failures are counted
 * per store code, username and client address; a successful sign-in clears the
 * counter. Counters are intentionally not persisted so a restart unlocks
 * accounts instead of permanently locking them out.
 */
@Service
public class LoginAttemptService {

    private final int maxAttempts;
    private final Duration window;
    private final Duration lockout;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(@Value("${app.auth.login-max-attempts:5}") int maxAttempts,
                               @Value("${app.auth.login-window-seconds:900}") long windowSeconds,
                               @Value("${app.auth.login-lockout-seconds:900}") long lockoutSeconds) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.window = Duration.ofSeconds(Math.max(30, windowSeconds));
        this.lockout = Duration.ofSeconds(Math.max(30, lockoutSeconds));
    }

    public String key(String storeCode, String username, String clientAddress) {
        return (storeCode == null ? "" : storeCode.trim().toLowerCase())
                + "|" + (username == null ? "" : username.trim().toLowerCase())
                + "|" + (clientAddress == null ? "" : clientAddress);
    }

    public void checkAllowed(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) return;
        Instant now = Instant.now();
        if (attempt.lockedUntil != null && attempt.lockedUntil.isAfter(now)) {
            long minutes = Math.max(1, Duration.between(now, attempt.lockedUntil).toMinutes() + 1);
            throw ApiException.tooManyRequests(
                    "Too many failed sign-in attempts. Try again in about " + minutes + " minute(s).");
        }
        if (attempt.lockedUntil != null) {
            attempts.remove(key);
            return;
        }
        if (attempt.firstFailure.plus(window).isBefore(now)) {
            attempts.remove(key);
        }
    }

    public void recordFailure(String key) {
        Instant now = Instant.now();
        attempts.compute(key, (ignored, previous) -> {
            if (previous == null || previous.lockedUntil != null
                    || previous.firstFailure.plus(window).isBefore(now)) {
                return new Attempt(1, now, null);
            }
            int failures = previous.failures + 1;
            return new Attempt(failures, previous.firstFailure,
                    failures >= maxAttempts ? now.plus(lockout) : null);
        });
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    public void clearAll() {
        attempts.clear();
    }

    private record Attempt(int failures, Instant firstFailure, Instant lockedUntil) {
    }
}
