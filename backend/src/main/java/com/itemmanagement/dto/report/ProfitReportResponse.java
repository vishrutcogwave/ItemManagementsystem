package com.itemmanagement.dto.report;

import java.math.BigDecimal;
import java.util.List;

public record ProfitReportResponse(
        BigDecimal totalSales,
        BigDecimal costOfGoodsSold,
        BigDecimal grossProfit,
        List<ItemProfit> byItem,
        List<BranchProfit> byBranch
) {
    public record ItemProfit(
            Long itemId,
            String itemName,
            BigDecimal quantitySold,
            BigDecimal revenue,
            BigDecimal cogs,
            BigDecimal profit
    ) {
    }

    public record BranchProfit(
            Long branchId,
            String branchName,
            BigDecimal revenue,
            BigDecimal cogs,
            BigDecimal profit
    ) {
    }
}
