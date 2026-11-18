package com.itemmanagement.repository;

import com.itemmanagement.entity.SaleCancellation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SaleCancellationRepository extends JpaRepository<SaleCancellation, Long> {

    Optional<SaleCancellation> findByStoreIdAndSaleId(Long storeId, Long saleId);
}
