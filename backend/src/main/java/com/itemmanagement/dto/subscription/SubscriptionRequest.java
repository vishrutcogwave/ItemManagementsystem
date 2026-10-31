package com.itemmanagement.dto.subscription;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record SubscriptionRequest(
        @NotNull(message = "Subscription start date is required") LocalDate startDate,
        @NotNull(message = "Subscription end date is required") LocalDate endDate,
        @NotNull(message = "Branch limit is required")
        @Min(value = 1, message = "Branch limit must be at least 1")
        @Max(value = 1000, message = "Branch limit cannot exceed 1000")
        Integer maxBranches,
        @Size(max = 255) String note
) {
}
