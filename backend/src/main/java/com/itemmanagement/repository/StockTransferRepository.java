package com.itemmanagement.repository;

import com.itemmanagement.entity.StockTransfer;
import com.itemmanagement.entity.enums.TransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    Optional<StockTransfer> findByIdAndStoreId(Long id, Long storeId);

    @Query("""
            select t from StockTransfer t
            where t.storeId = :storeId
              and (:branchId is null or t.fromBranchId = :branchId or t.toBranchId = :branchId)
              and (:branchIds is null or t.fromBranchId in :branchIds or t.toBranchId in :branchIds)
              and (:status is null or t.status = :status)
              and (:from is null or t.transferDate >= :from)
              and (:to is null or t.transferDate <= :to)
              and (:search is null or lower(t.transferNumber) like lower(concat('%', :search, '%')))
            order by t.createdAt desc
            """)
    Page<StockTransfer> search(@Param("storeId") Long storeId, @Param("branchId") Long branchId,
                               @Param("branchIds") Collection<Long> branchIds,
                               @Param("status") TransferStatus status, @Param("from") LocalDate from,
                               @Param("to") LocalDate to, @Param("search") String search, Pageable pageable);

    long countByStoreId(Long storeId);
}
