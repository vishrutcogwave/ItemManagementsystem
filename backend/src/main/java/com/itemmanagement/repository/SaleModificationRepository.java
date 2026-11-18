package com.itemmanagement.repository;

import com.itemmanagement.entity.SaleModification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SaleModificationRepository extends JpaRepository<SaleModification, Long> {

    List<SaleModification> findByStoreIdAndSaleIdOrderByModifiedAtDesc(Long storeId, Long saleId);

    long countByStoreIdAndSaleId(Long storeId, Long saleId);
}
