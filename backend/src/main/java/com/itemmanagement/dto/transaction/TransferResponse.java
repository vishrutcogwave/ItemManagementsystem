package com.itemmanagement.dto.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TransferResponse(
        Long id,
        String transferNumber,
        Long fromBranchId,
        String fromBranchName,
        Long toBranchId,
        String toBranchName,
        LocalDate transferDate,
        String status,
        String notes,
        Instant createdAt,
        List<LineResponse> items
) {
    public record LineResponse(
            Long id,
            Long itemId,
            String itemCode,
            String itemName,
            BigDecimal quantity,
            String unit,
            BigDecimal baseQuantity
    ) {
    }
}
