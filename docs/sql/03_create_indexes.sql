-- =====================================================================
-- 03_create_indexes.sql
-- Item Management System - performance indexes
-- =====================================================================
--
-- PURPOSE
--   Recreate exactly the non-constraint indexes that Flyway V1 and V2 create.
--   Unique/PK indexes are already created with the table constraints in 02.
--
-- IMPORTANT
--   - Run only on a schema created manually by 02_create_tables.sql.
--   - Do not run after Flyway has migrated the database; the indexes already
--     exist. Every statement uses IF NOT EXISTS, so accidental re-runs are
--     harmless.
--   - The unique functional index uq_roles_store_code is created in 02 because
--     it enforces correctness, not just performance.
--
-- INDEX STRATEGY
--   All high-volume queries are tenant-scoped by store_id. Most indexes
--   therefore lead with store_id, then the branch/item/date column used by the
--   filter. Foreign-key child columns are indexed to keep joins and deletes
--   fast.
--
-- =====================================================================

-- USERS
CREATE INDEX IF NOT EXISTS ix_users_email ON users (email);
CREATE INDEX IF NOT EXISTS ix_users_store ON users (store_id);

-- ITEMS
CREATE INDEX IF NOT EXISTS ix_items_store_category ON items (store_id, category_id);
CREATE INDEX IF NOT EXISTS ix_items_name ON items (store_id, item_name);
CREATE INDEX IF NOT EXISTS ix_items_tax ON items (store_id, tax_id);

-- TAX MASTER
CREATE INDEX IF NOT EXISTS ix_tax_master_store_status ON tax_master (store_id, status);

-- BRANCH INVENTORY
CREATE INDEX IF NOT EXISTS ix_inventory_item ON branch_inventory (store_id, item_id);
CREATE INDEX IF NOT EXISTS ix_inventory_branch ON branch_inventory (store_id, branch_id);

-- INVENTORY LEDGER
CREATE INDEX IF NOT EXISTS ix_txn_store_date
    ON inventory_transactions (store_id, transaction_date);
CREATE INDEX IF NOT EXISTS ix_txn_branch_item
    ON inventory_transactions (store_id, branch_id, item_id);
CREATE INDEX IF NOT EXISTS ix_txn_reference
    ON inventory_transactions (reference_type, reference_id);

-- PURCHASES
CREATE INDEX IF NOT EXISTS ix_purchases_store_date ON purchases (store_id, purchase_date);
CREATE INDEX IF NOT EXISTS ix_purchases_branch ON purchases (store_id, branch_id);
CREATE INDEX IF NOT EXISTS ix_purchase_details_purchase ON purchase_details (purchase_id);

-- SALES
CREATE INDEX IF NOT EXISTS ix_sales_store_date ON sales (store_id, sales_date);
CREATE INDEX IF NOT EXISTS ix_sales_branch ON sales (store_id, branch_id);
CREATE INDEX IF NOT EXISTS ix_sales_details_sale ON sales_details (sales_id);

-- TRANSFERS
CREATE INDEX IF NOT EXISTS ix_transfers_store ON stock_transfers (store_id, transfer_date);

-- AUDIT
CREATE INDEX IF NOT EXISTS ix_audit_store_date ON audit_logs (store_id, created_at);


-- =====================================================================
-- OPTIONAL RECOMMENDATIONS (NOT part of the current migrations)
-- =====================================================================
-- The indexes below are NOT created by the application. They are suggestions
-- for larger datasets. Review with EXPLAIN (ANALYZE, BUFFERS) before adding
-- them. Uncomment and run manually if the corresponding query is hot.
--
-- Supplier purchase history: SupplierService.purchaseHistory filters by
-- (store_id, supplier_id) ordered by purchase_date desc.
-- CREATE INDEX IF NOT EXISTS ix_purchases_supplier
--     ON purchases (store_id, supplier_id, purchase_date DESC);
--
-- Transfer line lookups by parent are covered by the FK but not indexed in the
-- current schema.
-- CREATE INDEX IF NOT EXISTS ix_transfer_details_transfer
--     ON stock_transfer_details (transfer_id);
-- CREATE INDEX IF NOT EXISTS ix_transfer_details_item
--     ON stock_transfer_details (store_id, item_id);
--
-- Stock report scans the ledger for a store and a date window. The existing
-- (store_id, transaction_date) index covers the range; add item/branch when
-- reports are filtered by item or branch on very large ledgers.
-- CREATE INDEX IF NOT EXISTS ix_txn_store_branch_item_date
--     ON inventory_transactions (store_id, branch_id, item_id, transaction_date);
--
-- Audit review by user (the application has no audit UI yet).
-- CREATE INDEX IF NOT EXISTS ix_audit_user_date
--     ON audit_logs (user_id, created_at DESC);
--
-- Partial indexes for the common status filters. These stay small because only
-- a minority of rows are DRAFT/CANCELLED.
-- CREATE INDEX IF NOT EXISTS ix_purchases_draft
--     ON purchases (store_id, purchase_date) WHERE status = 'DRAFT';
-- CREATE INDEX IF NOT EXISTS ix_transfers_pending
--     ON stock_transfers (store_id, transfer_date) WHERE status IN ('PENDING','APPROVED','DISPATCHED');
--
-- Optional trigram search for item name. Requires:
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- CREATE INDEX IF NOT EXISTS ix_items_name_trgm
--     ON items USING gin (item_name gin_trgm_ops);
