package com.itemmanagement.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

@Service
public class NumberGenerator {

    @PersistenceContext
    private EntityManager entityManager;

    private String next(String sequence) {
        Number value = (Number) entityManager
                .createNativeQuery("select nextval(cast(:sequence as regclass))")
                .setParameter("sequence", sequence)
                .getSingleResult();
        return Long.toString(value.longValue());
    }

    public String nextPurchase() {
        return next("purchase_document_seq");
    }

    public String nextSale() {
        return next("sale_document_seq");
    }

    public String nextTransfer() {
        return next("transfer_document_seq");
    }

    public String nextAdjustment() {
        return next("adjustment_document_seq");
    }

    public String nextItemCode() {
        return "ITM-" + next("item_code_seq");
    }

    public String nextImportNumber() {
        return next("item_import_document_seq");
    }

    public String nextSupplierPayment() {
        return next("supplier_payment_document_seq");
    }

    public String nextSaleModification() {
        return next("sale_modification_document_seq");
    }

    public String nextSaleCancellation() {
        return next("sale_cancellation_document_seq");
    }
}
