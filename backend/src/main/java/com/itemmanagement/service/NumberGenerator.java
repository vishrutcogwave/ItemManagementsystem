package com.itemmanagement.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

@Service
public class NumberGenerator {

    @PersistenceContext
    private EntityManager entityManager;

    public String next(String sequence, String prefix) {
        Number value = (Number) entityManager
                .createNativeQuery("select nextval('" + sequence + "')")
                .getSingleResult();
        return prefix + "-" + value.longValue();
    }

    public String nextPurchase() {
        return next("purchase_number_seq", "PUR");
    }

    public String nextSale() {
        return next("sales_number_seq", "SAL");
    }

    public String nextTransfer() {
        return next("transfer_number_seq", "TRF");
    }

    public String nextAdjustment() {
        return next("adjustment_number_seq", "ADJ");
    }

    public String nextItemCode() {
        return next("item_code_seq", "ITM");
    }

    public String nextImportNumber() {
        return next("item_import_number_seq", "IMP");
    }
}
