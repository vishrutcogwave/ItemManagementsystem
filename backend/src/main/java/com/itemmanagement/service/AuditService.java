package com.itemmanagement.service;

import com.itemmanagement.entity.AuditLog;
import com.itemmanagement.repository.AuditLogRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void log(String action, String entityType, Long entityId, String details) {
        try {
            AuthUser user = TenantContext.user();
            persist(user.storeId(), user.userId(), user.username(), action, entityType, entityId, details);
        } catch (Exception e) {
            log.debug("Unable to resolve audit user: {}", e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFor(Long storeId, Long userId, String username,
                       String action, String entityType, Long entityId, String details) {
        persist(storeId, userId, username, action, entityType, entityId, details);
    }

    /**
     * Writes an audit entry inside the caller's transaction and forces the insert
     * immediately, so a failure propagates and rolls the caller back. Used by
     * destructive operations that must not commit without their audit record.
     */
    public void logRequired(String action, String entityType, Long entityId, String details) {
        AuthUser user = TenantContext.user();
        persistRequired(user.storeId(), user.userId(), user.username(), action, entityType, entityId, details);
    }

    /**
     * Required-audit variant with an explicit actor, used when the audited
     * change belongs to a different scope than the caller (platform
     * administrators managing store accounts, or unauthenticated print bridges
     * pairing with a store). Joins the caller's transaction so the business
     * change and its audit record commit or roll back together.
     */
    public void logRequiredFor(Long storeId, Long userId, String username,
                               String action, String entityType, Long entityId, String details) {
        persistRequired(storeId, userId, username, action, entityType, entityId, details);
    }

    private void persistRequired(Long storeId, Long userId, String username,
                                 String action, String entityType, Long entityId, String details) {
        auditLogRepository.saveAndFlush(entry(storeId, userId, username, action, entityType, entityId, details));
    }

    private void persist(Long storeId, Long userId, String username,
                         String action, String entityType, Long entityId, String details) {
        try {
            auditLogRepository.save(entry(storeId, userId, username, action, entityType, entityId, details));
        } catch (Exception e) {
            log.warn("Audit log write failed: {}", e.getClass().getSimpleName());
        }
    }

    private AuditLog entry(Long storeId, Long userId, String username,
                           String action, String entityType, Long entityId, String details) {
        return AuditLog.builder()
                .storeId(storeId)
                .userId(userId)
                .username(username)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .details(details != null && details.length() > 1000 ? details.substring(0, 1000) : details)
                .build();
    }
}
