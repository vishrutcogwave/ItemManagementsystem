package com.itemmanagement.repository;

import com.itemmanagement.entity.BranchInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BranchInventoryRepository extends JpaRepository<BranchInventory, Long> {

    Optional<BranchInventory> findByStoreIdAndBranchIdAndItemId(Long storeId, Long branchId, Long itemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select bi from BranchInventory bi
            where bi.storeId = :storeId and bi.branchId = :branchId and bi.itemId = :itemId
            """)
    Optional<BranchInventory> findForUpdate(@Param("storeId") Long storeId,
                                            @Param("branchId") Long branchId,
                                            @Param("itemId") Long itemId);

    List<BranchInventory> findByStoreId(Long storeId);

    List<BranchInventory> findByStoreIdAndBranchId(Long storeId, Long branchId);

    List<BranchInventory> findByStoreIdAndBranchIdIn(Long storeId, Collection<Long> branchIds);

    List<BranchInventory> findByStoreIdAndItemId(Long storeId, Long itemId);

    long countByStoreId(Long storeId);

    long countByStoreIdAndBranchId(Long storeId, Long branchId);
}
