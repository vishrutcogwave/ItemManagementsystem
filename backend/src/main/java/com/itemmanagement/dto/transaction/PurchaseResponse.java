package com.itemmanagement.dto.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PurchaseResponse(
        Long id,
        String purchaseNumber,
        String invoiceNumber,
        LocalDate purchaseDate,
        Long branchId,
        String branchName,
        Long supplierId,
        String supplierName,
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
        String paymentStatus,
        String notes,
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
            BigDecimal purchaseRate,
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
