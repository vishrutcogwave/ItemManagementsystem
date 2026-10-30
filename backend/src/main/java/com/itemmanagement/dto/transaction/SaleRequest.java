package com.itemmanagement.dto.transaction;

import com.itemmanagement.entity.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GST is resolved from each item's tax master. The optional customer state is
 * the place of supply used to choose CGST+SGST or IGST on the server.
 */
public record SaleRequest(
        @NotNull(message = "Branch is required") Long branchId,
        LocalDate salesDate,
        @Size(max = 150, message = "Customer name is too long") String customerName,
        @Size(max = 20, message = "Customer mobile is too long") String customerMobile,
        @Size(max = 80, message = "Customer state is too long") String customerState,
        @DecimalMin(value = "0", message = "Discount cannot be negative") BigDecimal discount,
        PaymentMethod paymentMethod,
        @DecimalMin(value = "0", message = "Paid amount cannot be negative") BigDecimal paidAmount,
        Boolean pos,
        @NotEmpty(message = "Add at least one item") @Valid List<Line> items
) {
    public record Line(
            @NotNull(message = "Item is required") Long itemId,
            @NotNull(message = "Quantity is required")
            @DecimalMin(value = "0.001", message = "Quantity must be greater than zero") BigDecimal quantity,
            String unit,
            @NotNull(message = "Selling rate is required")
            @DecimalMin(value = "0", message = "Selling rate cannot be negative") BigDecimal sellingRate,
            @DecimalMin(value = "0", message = "Discount cannot be negative") BigDecimal discount
    ) {
    }
}
