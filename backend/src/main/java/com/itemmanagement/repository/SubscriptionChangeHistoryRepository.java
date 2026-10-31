package com.itemmanagement.repository;

import com.itemmanagement.entity.SubscriptionChangeHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionChangeHistoryRepository extends JpaRepository<SubscriptionChangeHistory, Long> {

    List<SubscriptionChangeHistory> findByStoreIdOrderByChangedAtDesc(Long storeId);
}
