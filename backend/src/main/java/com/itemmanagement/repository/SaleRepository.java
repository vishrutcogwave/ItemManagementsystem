package com.itemmanagement.repository;

import com.itemmanagement.entity.Sale;
import com.itemmanagement.entity.enums.SaleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    Optional<Sale> findByIdAndStoreId(Long id, Long storeId);

    @Query("""
            select s from Sale s
            where s.storeId = :storeId
              and (:branchId is null or s.branchId = :branchId)
              and (:branchIds is null or s.branchId in :branchIds)
              and (:status is null or s.status = :status)
              and (:from is null or s.salesDate >= :from)
              and (:to is null or s.salesDate <= :to)
              and (:search is null or lower(s.salesNumber) like lower(concat('%', :search, '%'))
                   or lower(coalesce(s.customerName, '')) like lower(concat('%', :search, '%'))
                   or coalesce(s.customerMobile, '') like concat('%', :search, '%'))
            order by s.createdAt desc
            """)
    Page<Sale> search(@Param("storeId") Long storeId, @Param("branchId") Long branchId,
                      @Param("branchIds") Collection<Long> branchIds,
                      @Param("status") SaleStatus status, @Param("from") LocalDate from,
                      @Param("to") LocalDate to, @Param("search") String search, Pageable pageable);

    @Query("""
            select s from Sale s
            where s.storeId = :storeId and s.status = :status
              and s.salesDate between :from and :to
              and (:branchId is null or s.branchId = :branchId)
              and (:branchIds is null or s.branchId in :branchIds)
            order by s.salesDate desc, s.id desc
            """)
    List<Sale> findForPeriod(@Param("storeId") Long storeId, @Param("branchIds") Collection<Long> branchIds,
                             @Param("branchId") Long branchId, @Param("status") SaleStatus status,
                             @Param("from") LocalDate from, @Param("to") LocalDate to);

    List<Sale> findTop5ByStoreIdAndStatusOrderByCreatedAtDesc(Long storeId, SaleStatus status);

    @Query("""
            select coalesce(sum(s.grandTotal), 0) from Sale s
            where s.storeId = :storeId and s.status = :status and s.salesDate = :date
              and (:branchId is null or s.branchId = :branchId)
            """)
    BigDecimal sumForDate(@Param("storeId") Long storeId, @Param("branchId") Long branchId,
                          @Param("status") SaleStatus status, @Param("date") LocalDate date);

    @Query("""
            select coalesce(sum(s.grandTotal), 0) from Sale s
            where s.storeId = :storeId and s.status = :status
              and s.salesDate between :from and :to
              and (:branchId is null or s.branchId = :branchId)
            """)
    BigDecimal sumBetween(@Param("storeId") Long storeId, @Param("branchId") Long branchId,
                          @Param("status") SaleStatus status, @Param("from") LocalDate from,
                          @Param("to") LocalDate to);

    long countByStoreId(Long storeId);
}
