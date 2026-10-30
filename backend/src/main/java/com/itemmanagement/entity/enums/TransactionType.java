package com.itemmanagement.entity.enums;

public enum TransactionType {
    OPENING_STOCK(true, false),
    PURCHASE_IN(true, false),
    SALE_OUT(false, true),
    TRANSFER_IN(true, false),
    TRANSFER_OUT(false, true),
    ADJUSTMENT_IN(true, false),
    ADJUSTMENT_OUT(false, true),
    PURCHASE_REVERSAL(false, true),
    SALE_REVERSAL(true, false);

    private final boolean stockIn;
    private final boolean stockOut;

    TransactionType(boolean stockIn, boolean stockOut) {
        this.stockIn = stockIn;
        this.stockOut = stockOut;
    }

    public boolean isStockIn() {
        return stockIn;
    }

    public boolean isStockOut() {
        return stockOut;
    }
}
