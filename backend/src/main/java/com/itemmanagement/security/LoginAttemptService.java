package com.itemmanagement.security;

import com.itemmanagement.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force protection for the login endpoint and the unauthorised
 * printer-pairing endpoint. Login failures are counted per store code, username
 * and client address; a successful sign-in clears that counter. A second,
 * aggregate counter caps the total number of login failures from a single
 * client address so one host cannot spread guesses across many usernames.
 * Pairing failures are counted per client address only, because the endpoint has
 * no account to key on. Counters are intentionally not persisted so a restart
 * unlocks accounts instead of permanently locking them out.
 */
@Service
public class LoginAttemptService {

    private static final int DEFAULT_MAX_TRACKED_KEYS = 10_000;

    private final int maxAttempts;
    private final Duration window;
    private final Duration lockout;
    private final int ipMaxAttempts;
    private final Duration ipWindow;
    private final Duration ipLockout;
    private final int pairingMaxAttempts;
    private final Duration pairingWindow;
    private final Duration pairingLockout;
    private final int maxTrackedKeys;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Map<String, Attempt> ipAttempts = new ConcurrentHashMap<>();
    private final Map<String, Attempt> pairingAttempts = new ConcurrentHashMap<>();

    public LoginAttemptService(@Value("${app.auth.login-max-attempts:5}") int maxAttempts,
                               @Value("${app.auth.login-window-seconds:900}") long windowSeconds,
                               @Value("${app.auth.login-lockout-seconds:900}") long lockoutSeconds,
                               @Value("${app.auth.login-ip-max-attempts:20}") int ipMaxAttempts,
                               @Value("${app.auth.login-ip-window-seconds:900}") long ipWindowSeconds,
                               @Value("${app.auth.login-ip-lockout-seconds:900}") long ipLockoutSeconds,
                               @Value("${app.auth.login-max-tracked-keys:" + DEFAULT_MAX_TRACKED_KEYS + "}")
                               int maxTrackedKeys,
                               @Value("${app.auth.pairing-max-attempts:10}") int pairingMaxAttempts,
                               @Value("${app.auth.pairing-window-seconds:60}") long pairingWindowSeconds,
                               @Value("${app.auth.pairing-lockout-seconds:300}") long pairingLockoutSeconds) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.window = Duration.ofSeconds(Math.max(30, windowSeconds));
        this.lockout = Duration.ofSeconds(Math.max(30, lockoutSeconds));
        this.ipMaxAttempts = Math.max(1, ipMaxAttempts);
        this.ipWindow = Duration.ofSeconds(Math.max(30, ipWindowSeconds));
        this.ipLockout = Duration.ofSeconds(Math.max(30, ipLockoutSeconds));
        this.maxTrackedKeys = Math.max(100, maxTrackedKeys);
        this.pairingMaxAttempts = Math.max(1, pairingMaxAttempts);
        this.pairingWindow = Duration.ofSeconds(Math.max(30, pairingWindowSeconds));
        this.pairingLockout = Duration.ofSeconds(Math.max(30, pairingLockoutSeconds));
    }

    public String key(String storeCode, String username, String clientAddress) {
        return (storeCode == null ? "" : storeCode.trim().toLowerCase())
                + "|" + (username == null ? "" : username.trim().toLowerCase())
                + "|" + (clientAddress == null ? "" : clientAddress);
    }

    /** Aggregate key for a client address; blank addresses are not tracked. */
    public String clientKey(String clientAddress) {
        return clientAddress == null ? "" : clientAddress.trim();
    }

    public void checkAllowed(String key) {
        check(attempts, key, window, "Too many failed sign-in attempts");
    }

    public void checkClientAllowed(String clientAddress) {
        check(ipAttempts, clientKey(clientAddress), ipWindow, "Too many failed sign-in attempts");
    }

    public void recordFailure(String key) {
        record(attempts, key, window, lockout, maxAttempts);
    }

    public void recordClientFailure(String clientAddress) {
        record(ipAttempts, clientKey(clientAddress), ipWindow, ipLockout, ipMaxAttempts);
    }

    public void checkPairingAllowed(String clientAddress) {
        check(pairingAttempts, clientKey(clientAddress), pairingWindow,
                "Too many pairing attempts");
    }

    public void recordPairingFailure(String clientAddress) {
        record(pairingAttempts, clientKey(clientAddress), pairingWindow, pairingLockout,
                pairingMaxAttempts);
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    public void clearAll() {
        attempts.clear();
        ipAttempts.clear();
        pairingAttempts.clear();
    }

    /**
     * Drops idle counters so the maps cannot grow without bound between logins.
     * Counters are also pruned on insert when the tracked-key cap is reached;
     * active lockouts are never pruned, so a map can stay above its cap until
     * those lockouts elapse.
     */
    @Scheduled(fixedDelayString = "${app.auth.login-state-cleanup-ms:300000}")
    public void purgeExpired() {
        Instant now = Instant.now();
        attempts.entrySet().removeIf(entry -> entry.getValue().isExpired(now, window));
        ipAttempts.entrySet().removeIf(entry -> entry.getValue().isExpired(now, ipWindow));
        pairingAttempts.entrySet()
                .removeIf(entry -> entry.getValue().isExpired(now, pairingWindow));
    }

    private void check(Map<String, Attempt> counters, String key, Duration counterWindow,
                       String message) {
        if (key.isEmpty()) return;
        Attempt attempt = counters.get(key);
        if (attempt == null) return;
        Instant now = Instant.now();
        if (attempt.lockedUntil != null) {
            if (attempt.lockedUntil.isAfter(now)) {
                long minutes = Math.max(1, Duration.between(now, attempt.lockedUntil).toMinutes() + 1);
                throw ApiException.tooManyRequests(
                        message + ". Try again in about " + minutes + " minute(s).");
            }
            counters.remove(key, attempt);
            return;
        }
        if (attempt.firstFailure.plus(counterWindow).isBefore(now)) {
            counters.remove(key, attempt);
        }
    }

    private void record(Map<String, Attempt> counters, String key, Duration counterWindow,
                        Duration counterLockout, int limit) {
        if (key.isEmpty()) return;
        Instant now = Instant.now();
        if (!counters.containsKey(key)) {
            enforceCapacity(counters, counterWindow, now);
        }
        counters.compute(key, (ignored, previous) -> {
            if (previous == null || previous.lockedUntil != null
                    || previous.firstFailure.plus(counterWindow).isBefore(now)) {
                return new Attempt(1, now, null);
            }
            int failures = previous.failures + 1;
            return new Attempt(failures, previous.firstFailure,
                    failures >= limit ? now.plus(counterLockout) : null);
        });
    }

    private void enforceCapacity(Map<String, Attempt> counters, Duration counterWindow, Instant now) {
        if (counters.size() < maxTrackedKeys) return;
        counters.entrySet().removeIf(entry -> entry.getValue().isExpired(now, counterWindow));
        if (counters.size() < maxTrackedKeys) return;
        // Active lockouts are never eviction candidates, even when the map is
        // above maxTrackedKeys. Removing one would silently unlock an account or
        // address, so the map is allowed to overflow while every entry is still
        // locked; the scheduled purge reclaims those entries once they expire.
        counters.entrySet().stream()
                .filter(entry -> !entry.getValue().isLocked(now))
                .sorted(Comparator.comparing(entry -> entry.getValue().firstFailure()))
                .limit(Math.max(1, counters.size() - maxTrackedKeys + 1))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(counters::remove);
    }

    private record Attempt(int failures, Instant firstFailure, Instant lockedUntil) {
        boolean isExpired(Instant now, Duration counterWindow) {
            return lockedUntil != null
                    ? !lockedUntil.isAfter(now)
                    : firstFailure.plus(counterWindow).isBefore(now);
        }

        boolean isLocked(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }
}
