package com.itemmanagement.repository;

import com.itemmanagement.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDescIdDesc(String entityType, Long entityId);

    @Query("""
            select a from AuditLog a
            where (:storeId is null or a.storeId = :storeId)
              and (:action is null or a.action = :action)
              and (:entityType is null or a.entityType = :entityType)
              and (:username = '' or lower(a.username) like lower(concat('%', :username, '%')))
              and (:search = '' or (lower(a.details) like lower(concat('%', :search, '%'))
                   or lower(a.action) like lower(concat('%', :search, '%'))))
              and a.createdAt >= :from
              and a.createdAt <= :to
            order by a.createdAt desc, a.id desc
            """)    Page<AuditLog> search(@Param("storeId") Long storeId,
                          @Param("action") String action,
                          @Param("entityType") String entityType,
                          @Param("username") String username,
                          @Param("search") String search,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);

    @Query("""
            select distinct a.action from AuditLog a
            order by a.action
            """)
    List<String> distinctActions();

    @Query("""
            select distinct a.entityType from AuditLog a
            order by a.entityType
            """)
    List<String> distinctEntityTypes();
}
