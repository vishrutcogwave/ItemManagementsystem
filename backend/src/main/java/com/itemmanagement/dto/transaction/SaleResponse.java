package com.itemmanagement.dto.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SaleResponse(
        Long id,
        String salesNumber,
        LocalDate salesDate,
        Long branchId,
        String branchName,
        String customerName,
        String customerMobile,
        String customerState,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal taxableAmount,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        BigDecimal igstAmount,
        BigDecimal tax,
        BigDecimal grandTotal,
        BigDecimal paidAmount,
        BigDecimal balanceAmount,
        String paymentMethod,
        String status,
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
            BigDecimal baseQuantity,
            BigDecimal sellingRate,
            BigDecimal costRate,
            BigDecimal discount,
            Long taxId,
            String taxName,
            BigDecimal gstPercentage,
            BigDecimal cgstPercentage,
            BigDecimal sgstPercentage,
            BigDecimal igstPercentage,
            BigDecimal taxableAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal tax,
            BigDecimal totalAmount
    ) {
    }
}
