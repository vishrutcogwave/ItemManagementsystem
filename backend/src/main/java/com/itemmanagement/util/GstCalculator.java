package com.itemmanagement.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single source of truth for GST maths. All amounts are rounded to paise so a
 * line's taxable + tax always adds up to the amount the customer pays.
 */
public final class GstCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private GstCalculator() {
    }

    public record LineTax(
            BigDecimal taxableAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal taxAmount
    ) {
        public BigDecimal payableAmount() {
            return taxableAmount.add(taxAmount).setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static LineTax calculate(BigDecimal gross, BigDecimal discount, BigDecimal gstPercentage,
                                    boolean inclusive, boolean interState) {
        BigDecimal net = money(gross).subtract(money(discount));
        if (net.compareTo(BigDecimal.ZERO) < 0) {
            net = BigDecimal.ZERO;
        }
        BigDecimal gst = gstPercentage == null ? BigDecimal.ZERO : gstPercentage;
        BigDecimal taxable;
        BigDecimal tax;
        if (gst.compareTo(BigDecimal.ZERO) <= 0) {
            taxable = net.setScale(2, RoundingMode.HALF_UP);
            tax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else if (inclusive) {
            BigDecimal divisor = BigDecimal.ONE.add(gst.divide(HUNDRED, 8, RoundingMode.HALF_UP));
            taxable = net.divide(divisor, 2, RoundingMode.HALF_UP);
            tax = net.subtract(taxable).setScale(2, RoundingMode.HALF_UP);
        } else {
            taxable = net.setScale(2, RoundingMode.HALF_UP);
            tax = taxable.multiply(gst).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        BigDecimal cgst = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sgst = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal igst = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (interState) {
            igst = tax;
        } else {
            cgst = tax.divide(TWO, 2, RoundingMode.HALF_UP);
            sgst = tax.subtract(cgst).setScale(2, RoundingMode.HALF_UP);
        }
        return new LineTax(taxable, cgst, sgst, igst, tax);
    }

    /**
     * Inter-state supply is decided by comparing the branch's registered state
     * with the supplier/customer place of supply. Missing state data is treated
     * as intra-state, which is the safe default for walk-in customers.
     */
    public static boolean isInterState(String branchState, String partyState) {
        String left = normalizeState(branchState);
        String right = normalizeState(partyState);
        return left != null && right != null && !left.equals(right);
    }

    public static BigDecimal gstForType(BigDecimal gstPercentage, boolean exempt) {
        if (exempt) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return gstPercentage == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : gstPercentage.setScale(2, RoundingMode.HALF_UP);
    }

    private static String normalizeState(String state) {
        if (state == null) {
            return null;
        }
        String normalized = state.trim().replaceAll("\\s+", " ").toLowerCase();
        return normalized.isEmpty() ? null : normalized;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
