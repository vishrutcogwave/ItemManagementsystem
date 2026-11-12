package com.itemmanagement.repository;

import com.itemmanagement.entity.SupplierPaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SupplierPaymentAllocationRepository
        extends JpaRepository<SupplierPaymentAllocation, Long> {

    List<SupplierPaymentAllocation> findByPaymentId(Long paymentId);

    List<SupplierPaymentAllocation> findByPaymentIdIn(Collection<Long> paymentIds);

    List<SupplierPaymentAllocation> findByPurchaseId(Long purchaseId);
}
