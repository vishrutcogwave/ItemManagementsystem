package com.itemmanagement.repository;

import com.itemmanagement.entity.SaleModificationItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SaleModificationItemRepository extends JpaRepository<SaleModificationItem, Long> {

    List<SaleModificationItem> findByModificationId(Long modificationId);
}
