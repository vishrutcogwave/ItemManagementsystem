package com.itemmanagement.repository;

import com.itemmanagement.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);
}
