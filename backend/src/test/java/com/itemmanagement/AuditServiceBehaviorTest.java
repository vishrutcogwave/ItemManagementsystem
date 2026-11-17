package com.itemmanagement;

import com.itemmanagement.entity.AuditLog;
import com.itemmanagement.repository.AuditLogRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Separates the two auditing contracts: informational events stay best-effort,
 * while required events must surface persistence failures to the caller so the
 * business transaction rolls back.
 */
class AuditServiceBehaviorTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AuditService service = new AuditService(repository);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bestEffortLogSwallowsWriteFailures() {
        signIn();
        when(repository.save(any(AuditLog.class)))
                .thenThrow(new RuntimeException("forced audit failure"));

        assertDoesNotThrow(() -> service.log("LOGIN", "USER", 1L, "Successful login"));
        verify(repository).save(any(AuditLog.class));
    }

    @Test
    void bestEffortExplicitActorLogSwallowsWriteFailures() {
        when(repository.save(any(AuditLog.class)))
                .thenThrow(new RuntimeException("forced audit failure"));

        assertDoesNotThrow(() -> service.logFor(1L, 2L, "actor", "LOGIN", "USER", 2L,
                "Successful login"));
        verify(repository).save(any(AuditLog.class));
    }

    @Test
    void requiredAuditPropagatesWriteFailures() {
        signIn();
        when(repository.saveAndFlush(any(AuditLog.class)))
                .thenThrow(new RuntimeException("forced audit failure"));

        assertThrows(RuntimeException.class,
                () -> service.logRequired("PASSWORD_CHANGE", "USER", 1L, "changed"));
        verify(repository).saveAndFlush(any(AuditLog.class));
    }

    @Test
    void requiredExplicitActorAuditPropagatesWriteFailures() {
        when(repository.saveAndFlush(any(AuditLog.class)))
                .thenThrow(new RuntimeException("forced audit failure"));

        assertThrows(RuntimeException.class,
                () -> service.logRequiredFor(1L, 2L, "actor", "PAIR", "PRINT_DEVICE", 3L,
                        "paired"));
        verify(repository).saveAndFlush(any(AuditLog.class));
    }

    private void signIn() {
        AuthUser user = new AuthUser(1L, 1L, "actor", "Actor", "actor@test.local", null,
                "STORE", "Store", Set.of(), Set.of(), Set.of(), true, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a"));
    }
}
