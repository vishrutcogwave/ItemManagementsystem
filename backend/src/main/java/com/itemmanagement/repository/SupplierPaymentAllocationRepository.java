package com.itemmanagement.repository;

import com.itemmanagement.entity.SupplierPaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

import java.util.Collection;
import java.util.List;

public interface SupplierPaymentAllocationRepository
        extends JpaRepository<SupplierPaymentAllocation, Long> {

    List<SupplierPaymentAllocation> findByPaymentId(Long paymentId);

    List<SupplierPaymentAllocation> findByPaymentIdIn(Collection<Long> paymentIds);

    List<SupplierPaymentAllocation> findByPurchaseId(Long purchaseId);

    interface PurchaseAllocationTotal {
        Long getPurchaseId();
        BigDecimal getAmount();
    }

    @Query("""
            select a.purchaseId as purchaseId, sum(a.amount) as amount
            from SupplierPaymentAllocation a
            where a.storeId = :storeId and a.purchaseId in :purchaseIds
            group by a.purchaseId
            """)
    List<PurchaseAllocationTotal> totalsByPurchase(@Param("storeId") Long storeId,
                                                    @Param("purchaseIds") Collection<Long> purchaseIds);

}
