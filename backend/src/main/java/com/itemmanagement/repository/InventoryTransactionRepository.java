package com.itemmanagement.repository;

import com.itemmanagement.entity.InventoryTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {

    List<InventoryTransaction> findByStoreIdAndBranchIdAndItemIdOrderByTransactionDateDesc(
            Long storeId, Long branchId, Long itemId);

    List<InventoryTransaction> findByStoreIdAndItemIdOrderByTransactionDateDesc(Long storeId, Long itemId);

    boolean existsByStoreIdAndBranchIdAndItemId(Long storeId, Long branchId, Long itemId);

    @Query("""
            select distinct t.itemId from InventoryTransaction t
            where t.storeId = :storeId and t.branchId = :branchId
            """)
    List<Long> findItemIdsWithTransactions(@Param("storeId") Long storeId,
                                           @Param("branchId") Long branchId);

    @Query("""
            select t from InventoryTransaction t
            where t.storeId = :storeId
              and (:branchId is null or t.branchId = :branchId)
              and (:branchIds is null or t.branchId in :branchIds)
              and (:itemId is null or t.itemId = :itemId)
              and (:search is null or lower(coalesce(t.referenceNumber, '')) like lower(concat('%', :search, '%')))
            order by t.transactionDate desc
            """)
    Page<InventoryTransaction> search(@Param("storeId") Long storeId, @Param("branchId") Long branchId,
                                      @Param("branchIds") Collection<Long> branchIds,
                                      @Param("itemId") Long itemId, @Param("search") String search,
                                      Pageable pageable);

    @Query("""
            select t from InventoryTransaction t
            where t.storeId = :storeId
              and (:branchId is null or t.branchId = :branchId)
              and t.transactionDate >= :from and t.transactionDate < :to
            order by t.transactionDate
            """)
    List<InventoryTransaction> findForPeriod(@Param("storeId") Long storeId, @Param("branchId") Long branchId,
                                             @Param("from") Instant from, @Param("to") Instant to);

    List<InventoryTransaction> findByStoreIdAndTransactionDateGreaterThanEqualAndTransactionDateLessThan(
            Long storeId, Instant from, Instant to);
}
