package com.itemmanagement.dto.subscription;

import java.time.Instant;
import java.time.LocalDate;

public record SubscriptionHistoryResponse(
        Long id,
        Instant changedAt,
        Long changedBy,
        String changedByUsername,
        LocalDate previousStartDate,
        LocalDate newStartDate,
        LocalDate previousEndDate,
        LocalDate newEndDate,
        Integer previousMaxBranches,
        Integer newMaxBranches,
        String note
) {
}
