package com.itemmanagement.repository;

import com.itemmanagement.entity.StockAdjustment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {

    Page<StockAdjustment> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);

    long countByStoreIdAndUnitIgnoreCase(Long storeId, String unit);
}
