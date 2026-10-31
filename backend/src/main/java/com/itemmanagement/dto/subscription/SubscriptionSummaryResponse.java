package com.itemmanagement.dto.subscription;

import java.time.LocalDate;

public record SubscriptionSummaryResponse(
        Long storeId,
        String storeCode,
        String storeName,
        LocalDate startDate,
        LocalDate endDate,
        long daysRemaining,
        int maxBranches,
        long branchCount,
        long remainingBranches,
        boolean expired,
        String platformContactEmail,
        String platformContactPhone
) {
}
