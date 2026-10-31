package com.itemmanagement.repository;

import com.itemmanagement.entity.PrintJob;
import com.itemmanagement.entity.enums.PrintJobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface PrintJobRepository extends JpaRepository<PrintJob, Long> {

    Optional<PrintJob> findByStoreIdAndJobKey(Long storeId, String jobKey);

    Optional<PrintJob> findByIdAndStoreId(Long id, Long storeId);

    Page<PrintJob> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);

    Page<PrintJob> findByStoreIdAndStatusOrderByCreatedAtDesc(Long storeId, PrintJobStatus status, Pageable pageable);

    Page<PrintJob> findByStoreIdAndBranchIdOrderByCreatedAtDesc(Long storeId, Long branchId, Pageable pageable);

    Page<PrintJob> findByStoreIdAndBranchIdAndStatusOrderByCreatedAtDesc(
            Long storeId, Long branchId, PrintJobStatus status, Pageable pageable);

    Page<PrintJob> findByStoreIdAndBranchIdInOrderByCreatedAtDesc(
            Long storeId, Collection<Long> branchIds, Pageable pageable);

    Page<PrintJob> findByStoreIdAndBranchIdInAndStatusOrderByCreatedAtDesc(
            Long storeId, Collection<Long> branchIds, PrintJobStatus status, Pageable pageable);
}
