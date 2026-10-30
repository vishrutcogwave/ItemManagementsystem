package com.itemmanagement.repository;

import com.itemmanagement.entity.StockTransferDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockTransferDetailRepository extends JpaRepository<StockTransferDetail, Long> {

    List<StockTransferDetail> findByTransferIdOrderByItemId(Long transferId);
}
