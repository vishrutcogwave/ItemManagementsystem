package com.itemmanagement.repository;

import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.enums.StoreStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    List<Branch> findByStoreIdOrderByBranchName(Long storeId);

    List<Branch> findByStoreIdAndStatusOrderByBranchName(Long storeId, StoreStatus status);

    List<Branch> findByStoreIdAndIdIn(Long storeId, Collection<Long> ids);

    Optional<Branch> findByIdAndStoreId(Long id, Long storeId);

    boolean existsByStoreIdAndBranchCodeIgnoreCase(Long storeId, String branchCode);

    long countByStoreId(Long storeId);

    long countByStoreIdAndStatus(Long storeId, StoreStatus status);
}
