package com.itemmanagement.dto.report;

import com.itemmanagement.dto.inventory.InventoryRowResponse;

import java.util.List;

public record LowStockReportResponse(
        List<InventoryRowResponse> rows,
        long total
) {
}
