package com.itemmanagement.repository;

import com.itemmanagement.entity.ItemImportRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItemImportRowRepository extends JpaRepository<ItemImportRow, Long> {

    List<ItemImportRow> findByBatchIdOrderByRowNumber(Long batchId);
}
