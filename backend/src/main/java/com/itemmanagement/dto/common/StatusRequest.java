package com.itemmanagement.dto.common;

import com.itemmanagement.entity.enums.StoreStatus;
import jakarta.validation.constraints.NotNull;

public record StatusRequest(@NotNull(message = "Status is required") StoreStatus status) {
}
