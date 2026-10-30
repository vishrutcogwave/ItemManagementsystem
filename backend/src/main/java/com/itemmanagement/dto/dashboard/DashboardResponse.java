package com.itemmanagement.dto.dashboard;

import com.itemmanagement.dto.inventory.InventoryRowResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardResponse(
        Summary summary,
        List<BranchSummary> branchSummary,
        List<RecentTransaction> recentPurchases,
        List<RecentTransaction> recentSales,
        List<InventoryRowResponse> lowStock,
        List<TopItem> topSellingItems,
        StockSummary stockSummary
) {
    public record Summary(
            long totalBranches,
            long totalCategories,
            long totalItems,
            long totalSuppliers,
            BigDecimal todayPurchaseAmount,
            BigDecimal todaySalesAmount,
            long totalLowStockItems,
            BigDecimal totalStockValue
    ) {
    }

    public record BranchSummary(
            Long branchId,
            String branchName,
            BigDecimal salesToday,
            BigDecimal purchasesToday,
            BigDecimal stockValue,
            long lowStockItems
    ) {
    }

    public record RecentTransaction(
            Long id,
            String number,
            String partyName,
            String branchName,
            LocalDate date,
            BigDecimal amount,
            String status
    ) {
    }

    public record TopItem(
            Long itemId,
            String itemName,
            String unit,
            BigDecimal quantitySold,
            BigDecimal revenue
    ) {
    }

    public record StockSummary(
            BigDecimal totalQuantity,
            BigDecimal totalValue,
            long itemsInStock,
            long itemsOutOfStock
    ) {
    }
}
