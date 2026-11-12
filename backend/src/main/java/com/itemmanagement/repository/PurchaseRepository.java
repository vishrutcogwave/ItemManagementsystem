package com.itemmanagement.repository;

import com.itemmanagement.entity.Purchase;
import com.itemmanagement.entity.enums.PurchaseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    Optional<Purchase> findByIdAndStoreId(Long id, Long storeId);

    List<Purchase> findTop5ByStoreIdAndStatusOrderByCreatedAtDesc(Long storeId, PurchaseStatus status);

    List<Purchase> findTop20ByStoreIdAndSupplierIdOrderByCreatedAtDesc(Long storeId, Long supplierId);

    @Query("""
            select p from Purchase p
            where p.storeId = :storeId
              and (:branchId is null or p.branchId = :branchId)
              and (:branchIds is null or p.branchId in :branchIds)
              and (:status is null or p.status = :status)
              and (:from is null or p.purchaseDate >= :from)
              and (:to is null or p.purchaseDate <= :to)
              and (:search is null or lower(p.purchaseNumber) like lower(concat('%', :search, '%'))
                   or lower(coalesce(p.invoiceNumber, '')) like lower(concat('%', :search, '%'))
                   or exists (select 1 from Supplier s where s.id = p.supplierId
                              and lower(s.supplierName) like lower(concat('%', :search, '%'))))
            order by p.createdAt desc
            """)
    Page<Purchase> search(@Param("storeId") Long storeId, @Param("branchId") Long branchId,
                          @Param("branchIds") Collection<Long> branchIds,
                          @Param("status") PurchaseStatus status, @Param("from") LocalDate from,
                          @Param("to") LocalDate to, @Param("search") String search, Pageable pageable);

    @Query("""
            select p from Purchase p
            where p.storeId = :storeId and p.status = :status
              and p.purchaseDate between :from and :to
              and (:branchId is null or p.branchId = :branchId)
              and (:branchIds is null or p.branchId in :branchIds)
            order by p.purchaseDate desc, p.id desc
            """)
    List<Purchase> findForPeriod(@Param("storeId") Long storeId, @Param("branchIds") Collection<Long> branchIds,
                                 @Param("branchId") Long branchId, @Param("status") PurchaseStatus status,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select coalesce(sum(p.grandTotal), 0) from Purchase p
            where p.storeId = :storeId and p.status = :status and p.purchaseDate = :date
              and (:branchId is null or p.branchId = :branchId)
            """)
    BigDecimal sumForDate(@Param("storeId") Long storeId, @Param("branchId") Long branchId,
                          @Param("status") PurchaseStatus status, @Param("date") LocalDate date);

    @Query("""
            select coalesce(sum(p.grandTotal), 0) from Purchase p
            where p.storeId = :storeId and p.status = :status
              and p.purchaseDate between :from and :to
              and (:branchId is null or p.branchId = :branchId)
            """)
    BigDecimal sumBetween(@Param("storeId") Long storeId, @Param("branchId") Long branchId,
                          @Param("status") PurchaseStatus status, @Param("from") LocalDate from,
                          @Param("to") LocalDate to);

    long countByStoreId(Long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p from Purchase p
            where p.storeId = :storeId and p.id in :ids
            order by p.id
            """)
    List<Purchase> lockByIds(@Param("storeId") Long storeId, @Param("ids") Collection<Long> ids);

    @Query("""
            select p from Purchase p
            where p.storeId = :storeId and p.status = :status
              and (:branchIds is null or p.branchId in :branchIds)
            order by p.supplierId, p.purchaseDate, p.id
            """)
    List<Purchase> findForSettlement(@Param("storeId") Long storeId,
                                     @Param("branchIds") Collection<Long> branchIds,
                                     @Param("status") PurchaseStatus status);

    @Query("""
            select p from Purchase p
            where p.storeId = :storeId and p.supplierId = :supplierId and p.status = :status
              and (:branchIds is null or p.branchId in :branchIds)
            order by p.purchaseDate, p.id
            """)
    List<Purchase> findSupplierBills(@Param("storeId") Long storeId,
                                     @Param("supplierId") Long supplierId,
                                     @Param("branchIds") Collection<Long> branchIds,
                                     @Param("status") PurchaseStatus status);
}
