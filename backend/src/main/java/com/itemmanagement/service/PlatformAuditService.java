package com.itemmanagement.service;

import com.itemmanagement.dto.platform.AuditLogResponse;
import com.itemmanagement.entity.AuditLog;
import com.itemmanagement.entity.Store;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.AuditLogRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import com.itemmanagement.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only audit-log access for platform administrators. Rows are never
 * edited or deleted from this service; retention remains an explicit operator
 * policy.
 */
@Service
@RequiredArgsConstructor
public class PlatformAuditService {

    private static final int MAX_EXPORT_ROWS = 10_000;
    private static final Instant MIN_TIME = Instant.EPOCH;
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59Z");

    private final AuditLogRepository auditLogRepository;
    private final StoreRepository storeRepository;

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> list(Long storeId, String action, String entityType,
                                               String username, String search,
                                               Instant from, Instant to, int page, int size) {
        requirePlatformAdmin();
        Page<AuditLog> logs = auditLogRepository.search(storeId, blankToNull(action),
                blankToNull(entityType), emptyIfNull(username), emptyIfNull(search),
                from == null ? MIN_TIME : from, to == null ? MAX_TIME : to,
                Pagination.of(page, size));
        Map<Long, Store> stores = stores(logs.getContent());
        return PageResponse.of(logs, log -> toResponse(log, stores.get(log.getStoreId())));
    }

    @Transactional(readOnly = true)
    public List<String> actions() {
        requirePlatformAdmin();
        return auditLogRepository.distinctActions();
    }

    @Transactional(readOnly = true)
    public List<String> entityTypes() {
        requirePlatformAdmin();
        return auditLogRepository.distinctEntityTypes();
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(Long storeId, String action, String entityType, String username,
                            String search, Instant from, Instant to) {
        requirePlatformAdmin();
        Pageable pageable = PageRequest.of(0, MAX_EXPORT_ROWS);
        List<AuditLog> logs = auditLogRepository.search(storeId, blankToNull(action),
                blankToNull(entityType), emptyIfNull(username), emptyIfNull(search),
                from == null ? MIN_TIME : from, to == null ? MAX_TIME : to,
                pageable).getContent();
        Map<Long, Store> stores = stores(logs);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("Timestamp,Store code,Store name,Store id,Actor,Action,Entity type,Entity id,Details\r\n");
        for (AuditLog log : logs) {
            Store store = stores.get(log.getStoreId());
            csv.append(cell(log.getCreatedAt() == null ? "" : log.getCreatedAt().toString())).append(',')
                    .append(cell(store == null ? "" : store.getStoreCode())).append(',')
                    .append(cell(store == null ? "" : store.getStoreName())).append(',')
                    .append(cell(log.getStoreId() == null ? "" : log.getStoreId().toString())).append(',')
                    .append(cell(log.getUsername())).append(',')
                    .append(cell(log.getAction())).append(',')
                    .append(cell(log.getEntityType())).append(',')
                    .append(cell(log.getEntityId() == null ? "" : log.getEntityId().toString())).append(',')
                    .append(cell(log.getDetails())).append("\r\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public String exportFileName() {
        return "audit-logs-" + LocalDate.now(ZoneOffset.UTC) + ".csv";
    }

    private AuthUser requirePlatformAdmin() {
        AuthUser actor = TenantContext.user();
        if (actor == null || !actor.isPlatformAdmin()) {
            throw ApiException.forbidden("Only platform administrators can view audit logs");
        }
        return actor;
    }

    private Map<Long, Store> stores(List<AuditLog> logs) {
        List<Long> ids = logs.stream().map(AuditLog::getStoreId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        return storeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Store::getId, Function.identity()));
    }

    private AuditLogResponse toResponse(AuditLog log, Store store) {
        return new AuditLogResponse(log.getId(), log.getStoreId(),
                store == null ? null : store.getStoreCode(),
                store == null ? null : store.getStoreName(),
                log.getUserId(), log.getUsername(), log.getAction(), log.getEntityType(),
                log.getEntityId(), log.getDetails(), log.getCreatedAt());
    }

    private String cell(String value) {
        if (value == null) return "";
        String safe = value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        safe = safe.replace("\"", "\"\"");
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe + "\"";
        }
        return safe;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value.trim();
    }
}
