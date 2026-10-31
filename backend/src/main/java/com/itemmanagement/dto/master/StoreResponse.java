package com.itemmanagement.dto.master;

import java.time.Instant;
import java.time.LocalDate;

public record StoreResponse(
        Long id,
        String storeCode,
        String storeName,
        String ownerName,
        String email,
        String mobile,
        String address,
        String gstNumber,
        boolean taxInclusive,
        String status,
        Instant createdAt,
        long branchCount,
        long userCount,
        LocalDate subscriptionStartDate,
        LocalDate subscriptionEndDate,
        int maxBranches,
        long remainingBranches,
        boolean subscriptionExpired
) {
}
