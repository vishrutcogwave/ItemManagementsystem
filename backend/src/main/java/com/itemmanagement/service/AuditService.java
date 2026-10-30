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

    private void persist(Long storeId, Long userId, String username,
                         String action, String entityType, Long entityId, String details) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .storeId(storeId)
                    .userId(userId)
                    .username(username)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .details(details != null && details.length() > 1000 ? details.substring(0, 1000) : details)
                    .build());
        } catch (Exception e) {
            log.warn("Audit log write failed: {}", e.getMessage());
        }
    }
}
