package com.itemmanagement.security;

import com.itemmanagement.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptServiceTest {

    private static final int ACCOUNT_LIMIT = 5;
    private static final int IP_LIMIT = 5;

    private LoginAttemptService service() {
        return new LoginAttemptService(
                ACCOUNT_LIMIT, 900, 900, IP_LIMIT, 900, 900, 1_000, 10, 60, 300);
    }

    @Test
    void aggregateLimitLocksAnAddressThatSpreadsFailuresAcrossUsernames() {
        LoginAttemptService service = service();
        for (int i = 0; i < IP_LIMIT; i++) {
            service.recordFailure(service.key("SHOP", "ghost" + i, "198.51.100.1"));
            service.recordClientFailure("198.51.100.1");
        }

        ApiException locked = assertThrows(ApiException.class,
                () -> service.checkClientAllowed("198.51.100.1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, locked.getStatus());

        // A different client address keeps its own aggregate budget.
        assertDoesNotThrow(() -> service.checkClientAllowed("198.51.100.2"));
    }

    @Test
    void perAccountLimitStillAppliesIndependentlyOfTheAggregateLimit() {
        LoginAttemptService service = new LoginAttemptService(
                ACCOUNT_LIMIT, 900, 900, 100, 900, 900, 1_000, 10, 60, 300);
        String key = service.key("SHOP", "owner", "198.51.100.1");
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            service.recordFailure(key);
            service.recordClientFailure("198.51.100.1");
        }

        ApiException locked = assertThrows(ApiException.class,
                () -> service.checkAllowed(key));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, locked.getStatus());

        assertDoesNotThrow(() -> service.checkAllowed(
                service.key("SHOP", "other", "198.51.100.1")));
        assertDoesNotThrow(() -> service.checkClientAllowed("198.51.100.1"));
    }

    @Test
    void aSuccessfulSignInClearsOnlyThatAccountCounter() {
        LoginAttemptService service = service();
        String key = service.key("SHOP", "owner", "198.51.100.1");
        service.recordFailure(key);
        service.recordFailure(key);
        service.recordClientFailure("198.51.100.1");

        service.recordSuccess(key);
        assertDoesNotThrow(() -> service.checkAllowed(key));

        // Aggregate failures are not erased by a successful login, otherwise an
        // attacker with one valid account could reset the address budget.
        service.recordClientFailure("198.51.100.1");
        service.recordClientFailure("198.51.100.1");
        service.recordClientFailure("198.51.100.1");
        service.recordClientFailure("198.51.100.1");
        assertThrows(ApiException.class, () -> service.checkClientAllowed("198.51.100.1"));
    }

    @Test
    void blankClientAddressIsNotTracked() {
        LoginAttemptService service = service();
        service.recordClientFailure(null);
        service.recordClientFailure("  ");
        assertDoesNotThrow(() -> service.checkClientAllowed(null));
        assertDoesNotThrow(() -> service.checkClientAllowed(""));
    }

    @Test
    void clearAllResetsBothAccountAndAddressState() {
        LoginAttemptService service = service();
        String key = service.key("SHOP", "owner", "198.51.100.1");
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            service.recordFailure(key);
            service.recordClientFailure("198.51.100.1");
        }
        assertThrows(ApiException.class, () -> service.checkClientAllowed("198.51.100.1"));

        service.clearAll();
        assertDoesNotThrow(() -> service.checkClientAllowed("198.51.100.1"));
        assertDoesNotThrow(() -> service.checkAllowed(key));
    }

    @Test
    void pairingFailuresAreTrackedPerAddressIndependentlyOfLoginState() {
        LoginAttemptService service = new LoginAttemptService(
                ACCOUNT_LIMIT, 900, 900, IP_LIMIT, 900, 900, 1_000, 2, 60, 300);
        service.recordPairingFailure("198.51.100.4");
        service.recordPairingFailure("198.51.100.4");

        assertThrows(ApiException.class, () -> service.checkPairingAllowed("198.51.100.4"));
        assertDoesNotThrow(() -> service.checkPairingAllowed("198.51.100.5"));
        // Pairing counters must not spend the login address budget.
        assertDoesNotThrow(() -> service.checkClientAllowed("198.51.100.4"));
    }

    @Test
    void blankClientAddressIsNotTrackedForPairing() {
        LoginAttemptService service = service();
        for (int i = 0; i < 50; i++) {
            service.recordPairingFailure(null);
            service.recordPairingFailure("  ");
        }
        assertDoesNotThrow(() -> service.checkPairingAllowed(null));
    }

    @Test
    void pairingCountersExpireAndAreCleared() throws Exception {
        LoginAttemptService service = new LoginAttemptService(
                ACCOUNT_LIMIT, 900, 900, IP_LIMIT, 900, 900, 100, 2, 60, 300);
        service.recordPairingFailure("198.51.100.8");
        service.recordPairingFailure("198.51.100.8");
        assertEquals(1, trackedCount(service, "pairingAttempts"));

        expireEveryLockout(service, "pairingAttempts");
        service.purgeExpired();
        assertEquals(0, trackedCount(service, "pairingAttempts"));
        assertDoesNotThrow(() -> service.checkPairingAllowed("198.51.100.8"));

        service.recordPairingFailure("198.51.100.8");
        service.clearAll();
        assertEquals(0, trackedCount(service, "pairingAttempts"));
    }

    @Test
    void activePairingLockoutsSurviveCapacityPressure() {
        LoginAttemptService service = new LoginAttemptService(
                ACCOUNT_LIMIT, 900, 900, IP_LIMIT, 900, 900, 100, 2, 60, 300);
        String lockedIp = "198.51.100.7";
        service.recordPairingFailure(lockedIp);
        service.recordPairingFailure(lockedIp);

        for (int i = 0; i < 250; i++) {
            service.recordPairingFailure("203.0.113." + (i % 250));
        }

        assertThrows(ApiException.class, () -> service.checkPairingAllowed(lockedIp),
                "capacity churn must not evict an active pairing lockout");
    }

    @Test
    void activeAccountLockoutsSurviveCapacityPressure() {
        LoginAttemptService service = new LoginAttemptService(
                ACCOUNT_LIMIT, 900, 900, 100, 900, 900, 100, 10, 60, 300);
        String locked = service.key("SHOP", "owner", "198.51.100.1");
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            service.recordFailure(locked);
        }

        for (int i = 0; i < 250; i++) {
            service.recordFailure(service.key("SHOP", "flood" + i, "203.0.113.7"));
        }

        assertThrows(ApiException.class, () -> service.checkAllowed(locked),
                "capacity churn must not evict an active account lockout");
    }

    @Test
    void activeClientLockoutsSurviveCapacityPressure() {
        LoginAttemptService service = new LoginAttemptService(
                ACCOUNT_LIMIT, 900, 900, 2, 900, 900, 100, 10, 60, 300);
        String lockedIp = "198.51.100.9";
        service.recordClientFailure(lockedIp);
        service.recordClientFailure(lockedIp);

        for (int i = 0; i < 250; i++) {
            service.recordClientFailure("2001:db8::" + i);
        }

        assertThrows(ApiException.class, () -> service.checkClientAllowed(lockedIp),
                "capacity churn must not evict an active address lockout");
    }

    @Test
    void expiredCountersAreEvictedBeforeInactiveOnes() throws Exception {
        LoginAttemptService service = new LoginAttemptService(
                2, 900, 900, 100, 900, 900, 100, 10, 60, 300);
        String locked = service.key("SHOP", "owner", "198.51.100.1");
        service.recordFailure(locked);
        service.recordFailure(locked);

        String stale = service.key("SHOP", "stale", "198.51.100.1");
        service.recordFailure(stale);
        replaceAttempt(service, "attempts", stale,
                Instant.now().minus(Duration.ofHours(1)), null);

        List<String> kept = new ArrayList<>();
        for (int i = 0; i < 98; i++) {
            String key = service.key("SHOP", "kept" + i, "198.51.100.1");
            service.recordFailure(key);
            kept.add(key);
        }
        assertEquals(100, trackedCount(service, "attempts"));

        String newcomer = service.key("SHOP", "newcomer", "198.51.100.1");
        service.recordFailure(newcomer);

        Set<String> keys = trackedKeys(service, "attempts");
        assertFalse(keys.contains(stale), "an expired counter must be reclaimed first");
        assertTrue(keys.contains(locked), "the active lockout must survive");
        assertTrue(keys.containsAll(kept), "unexpired inactive counters keep their slots");
        assertTrue(keys.contains(newcomer));
        assertEquals(100, keys.size());
    }

    @Test
    void newKeysOverflowSafelyWhenEveryEntryIsAnActiveLockout() throws Exception {
        LoginAttemptService service = new LoginAttemptService(
                2, 900, 900, 2, 900, 900, 100, 10, 60, 300);
        List<String> lockedKeys = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String key = service.key("SHOP", "user" + i, "198.51.100.1");
            service.recordFailure(key);
            service.recordFailure(key);
            lockedKeys.add(key);
        }
        assertEquals(100, trackedCount(service, "attempts"));

        String newcomer = service.key("SHOP", "newcomer", "198.51.100.1");
        service.recordFailure(newcomer);

        assertEquals(101, trackedCount(service, "attempts"),
                "the map may overflow rather than evict an active lockout");
        for (String key : lockedKeys) {
            assertThrows(ApiException.class, () -> service.checkAllowed(key));
        }
        assertTrue(trackedKeys(service, "attempts").contains(newcomer));

        // Once the lockouts lapse, the scheduled purge reclaims the overflow.
        expireEveryLockout(service, "attempts");
        service.purgeExpired();
        assertEquals(0, trackedCount(service, "attempts"));
    }

    @Test
    void bothMapsRecoverCapacityAfterLockoutsExpire() throws Exception {
        LoginAttemptService service = new LoginAttemptService(
                2, 900, 900, 2, 900, 900, 100, 10, 60, 300);
        String account = service.key("SHOP", "owner", "198.51.100.1");
        service.recordFailure(account);
        service.recordFailure(account);
        service.recordClientFailure("198.51.100.1");
        service.recordClientFailure("198.51.100.1");

        assertThrows(ApiException.class, () -> service.checkAllowed(account));
        assertThrows(ApiException.class, () -> service.checkClientAllowed("198.51.100.1"));

        expireEveryLockout(service, "attempts");
        expireEveryLockout(service, "ipAttempts");
        service.purgeExpired();

        assertEquals(0, trackedCount(service, "attempts"));
        assertEquals(0, trackedCount(service, "ipAttempts"));
        assertDoesNotThrow(() -> service.checkAllowed(account));
        assertDoesNotThrow(() -> service.checkClientAllowed("198.51.100.1"));

        service.recordFailure(account);
        service.recordClientFailure("198.51.100.1");
        assertEquals(1, trackedCount(service, "attempts"));
        assertEquals(1, trackedCount(service, "ipAttempts"));
    }

    // The service reads Instant.now() directly; these helpers simulate elapsed
    // time in tests without adding a Clock dependency to production code.

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attemptMap(LoginAttemptService service, String field)
            throws ReflectiveOperationException {
        Field mapField = LoginAttemptService.class.getDeclaredField(field);
        mapField.setAccessible(true);
        return (Map<String, Object>) mapField.get(service);
    }

    private static int trackedCount(LoginAttemptService service, String field)
            throws ReflectiveOperationException {
        return attemptMap(service, field).size();
    }

    private static Set<String> trackedKeys(LoginAttemptService service, String field)
            throws ReflectiveOperationException {
        return Set.copyOf(attemptMap(service, field).keySet());
    }

    private static void replaceAttempt(LoginAttemptService service, String field, String key,
                                       Instant firstFailure, Instant lockedUntil)
            throws ReflectiveOperationException {
        Class<?> attemptType = Class.forName(LoginAttemptService.class.getName() + "$Attempt");
        Constructor<?> constructor = attemptType.getDeclaredConstructor(
                int.class, Instant.class, Instant.class);
        constructor.setAccessible(true);
        attemptMap(service, field).put(key, constructor.newInstance(1, firstFailure, lockedUntil));
    }

    private static void expireEveryLockout(LoginAttemptService service, String field)
            throws ReflectiveOperationException {
        Instant longAgo = Instant.now().minus(Duration.ofHours(1));
        Instant expired = Instant.now().minusSeconds(1);
        for (String key : trackedKeys(service, field)) {
            replaceAttempt(service, field, key, longAgo, expired);
        }
    }
}
