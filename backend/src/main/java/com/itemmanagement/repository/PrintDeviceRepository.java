package com.itemmanagement.repository;

import com.itemmanagement.entity.PrintDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrintDeviceRepository extends JpaRepository<PrintDevice, Long> {

    Optional<PrintDevice> findByTokenHash(String tokenHash);

    Optional<PrintDevice> findByIdAndStoreId(Long id, Long storeId);

    List<PrintDevice> findByStoreIdAndBranchIdOrderByCreatedAtDesc(Long storeId, Long branchId);
}
