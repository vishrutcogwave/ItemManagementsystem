package com.itemmanagement.repository;

import com.itemmanagement.entity.ItemImportBatch;
import com.itemmanagement.entity.enums.ImportBatchStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface ItemImportBatchRepository extends JpaRepository<ItemImportBatch, Long> {

    Optional<ItemImportBatch> findByIdAndStoreId(Long id, Long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ItemImportBatch b where b.id = :id and b.storeId = :storeId")
    Optional<ItemImportBatch> findForUpdate(@Param("id") Long id, @Param("storeId") Long storeId);

    boolean existsByStoreIdAndBranchIdAndFileHashAndStatusAndIdNot(
            Long storeId, Long branchId, String fileHash, ImportBatchStatus status, Long id);

    Optional<ItemImportBatch> findFirstByStoreIdAndBranchIdAndFileHashAndStatusOrderByIdAsc(
            Long storeId, Long branchId, String fileHash, ImportBatchStatus status);

    Page<ItemImportBatch> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);

    Page<ItemImportBatch> findByStoreIdAndBranchIdInOrderByCreatedAtDesc(
            Long storeId, Collection<Long> branchIds, Pageable pageable);
}
