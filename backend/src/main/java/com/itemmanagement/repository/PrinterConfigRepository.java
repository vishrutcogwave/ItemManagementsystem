package com.itemmanagement.repository;

import com.itemmanagement.entity.PrinterConfig;
import com.itemmanagement.entity.enums.PrinterPurpose;
import com.itemmanagement.entity.enums.StoreStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PrinterConfigRepository extends JpaRepository<PrinterConfig, Long> {

    List<PrinterConfig> findByStoreIdAndBranchIdOrderByPrinterNameAsc(Long storeId, Long branchId);

    Optional<PrinterConfig> findByIdAndStoreId(Long id, Long storeId);

    boolean existsByStoreIdAndBranchIdAndPrinterNameIgnoreCase(Long storeId, Long branchId, String printerName);

    boolean existsByStoreIdAndBranchIdAndPrinterNameIgnoreCaseAndIdNot(
            Long storeId, Long branchId, String printerName, Long id);

    Optional<PrinterConfig> findFirstByStoreIdAndBranchIdAndStatusAndPurposeInOrderByIdAsc(
            Long storeId, Long branchId, StoreStatus status, Collection<PrinterPurpose> purposes);
}
