package com.itemmanagement.repository;

import com.itemmanagement.entity.PurchaseDetail;
import com.itemmanagement.entity.enums.PurchaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PurchaseDetailRepository extends JpaRepository<PurchaseDetail, Long> {

    List<PurchaseDetail> findByPurchaseIdOrderByItemId(Long purchaseId);

    List<PurchaseDetail> findByPurchaseIdIn(List<Long> purchaseIds);

    void deleteByPurchaseId(Long purchaseId);

    long countByStoreIdAndTaxId(Long storeId, Long taxId);

    long countByStoreIdAndUnitIgnoreCase(Long storeId, String unit);

    @Query("""
            select pd from PurchaseDetail pd, Purchase p
            where pd.purchaseId = p.id and pd.storeId = :storeId
              and p.status = :status and p.purchaseDate between :from and :to
              and (:branchId is null or p.branchId = :branchId)
              and (:branchIds is null or p.branchId in :branchIds)
            order by p.purchaseDate desc, pd.purchaseId desc
            """)
    List<PurchaseDetail> findForPeriod(@Param("storeId") Long storeId,
                                       @Param("branchIds") Collection<Long> branchIds,
                                       @Param("branchId") Long branchId,
                                       @Param("status") PurchaseStatus status,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);
}
