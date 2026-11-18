package com.itemmanagement.service;

import com.itemmanagement.entity.Store;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Permanently removes one store and the tenant rows that belong exclusively to
 * it. Every statement is scoped to the selected store id, runs in a single
 * transaction and is ordered child-first for the existing composite foreign
 * keys. Platform rows (users/roles with a null store id), permissions,
 * platform settings, revoked tokens and the platform audit trail are preserved.
 *
 * <p>The store row is locked with {@code SELECT ... FOR UPDATE} before anything
 * is read or removed. Every store-scoped insert validates its foreign key by
 * taking {@code FOR KEY SHARE} on that same row, so it waits for this
 * transaction and then fails once the store is gone instead of leaving orphaned
 * tenant rows behind. A second concurrent deletion waits on the same lock and
 * then finds the store missing.
 */
@Service
@RequiredArgsConstructor
public class StoreDeletionService {

    /**
     * Children before parents. Tables ending in _details and *_payment_allocations
     * reference documents, so they are removed first; created_by references keep
     * users until every document, ledger and printing row is gone.
     *
     * <p>Exposed for the schema-coverage test that asserts every table carrying a
     * store_id column is either deleted here or explicitly retained by policy.
     */
    public static final List<String> STORE_TABLES_IN_DELETE_ORDER = List.of(
            "supplier_payment_allocations",
            "supplier_payments",
            "purchase_details",
            "sale_modification_items",
            "sale_modifications",
            "sale_cancellations",
            "sales_details",
            "stock_transfer_details",
            "item_import_rows",
            "item_import_batches",
            "print_jobs",
            "print_pairing_codes",
            "print_devices",
            "printer_configs",
            "stock_adjustments",
            "inventory_transactions",
            "branch_inventory",
            "purchases",
            "sales",
            "stock_transfers",
            "items",
            "tax_master",
            "categories",
            "suppliers",
            "customers",
            "subscription_change_history",
            "users",
            "roles",
            "branches",
            "unit_master"
    );

    private final StoreRepository storeRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    @Transactional
    public void deleteStore(Long storeId, String confirmedStoreName) {
        // Serialises deletion with every concurrent store-scoped write.
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> ApiException.notFound("Store not found"));

        String confirmation = confirmedStoreName == null ? "" : confirmedStoreName.trim();
        if (!store.getStoreName().equals(confirmation)) {
            throw ApiException.badRequest("Enter the exact store name to confirm permanent deletion");
        }

        for (String table : STORE_TABLES_IN_DELETE_ORDER) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE store_id = ?", storeId);
        }

        assertNoTenantRowsRemain(storeId);

        int deleted = jdbcTemplate.update("DELETE FROM stores WHERE id = ?", storeId);
        if (deleted != 1) {
            throw ApiException.notFound("Store not found");
        }

        auditService.logRequired("DELETE", "STORE", storeId,
                "Permanently deleted store " + store.getStoreName() + " (" + store.getStoreCode()
                        + ") and all of its tenant data");
    }

    private void assertNoTenantRowsRemain(Long storeId) {
        for (String table : STORE_TABLES_IN_DELETE_ORDER) {
            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE store_id = ?", Integer.class, storeId);
            if (remaining != null && remaining > 0) {
                throw new IllegalStateException(
                        "Store deletion left " + remaining + " rows in " + table + "; rolling back");
            }
        }
    }
}
