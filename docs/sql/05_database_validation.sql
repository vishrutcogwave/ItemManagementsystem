-- =====================================================================
-- 05_database_validation.sql
-- Item Management System - read-only health and integrity checks
-- =====================================================================
--
-- PURPOSE
--   Verify that the database matches the application schema and that the
--   multi-tenant, inventory and GST invariants hold.
--
-- SAFETY
--   Every statement is read-only (SELECT / WITH). This file changes nothing.
--   Run it as the application user or a read-only DBA account.
--
-- HOW TO READ RESULTS
--   Each check ends with an "expect" comment. Integrity checks return rows only
--   when something is wrong; an empty result means the check passed.
--
-- RUN
--   psql -h localhost -p 5432 -U ims_app -d item_management_db -f 05_database_validation.sql
-- =====================================================================

\echo '=== 1. Runtime identity ==='
SELECT current_database() AS database,
       current_user       AS connected_as,
       current_setting('server_version') AS server_version,
       current_setting('TimeZone') AS timezone;

\echo '=== 2. Flyway history (if Flyway manages this database) ==='
-- Expect three successful versions: 1, 2, 3. Empty if the schema was created
-- manually from 02_create_tables.sql.
SELECT installed_rank, version, description, type, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;

\echo '=== 3. Expected tables ==='
-- Expect 23 application tables (plus flyway_schema_history when present).
SELECT count(*) AS application_table_count
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name <> 'flyway_schema_history';

SELECT expected.table_name AS missing_table
FROM (VALUES
    ('stores'),('branches'),('roles'),('permissions'),('role_permissions'),
    ('users'),('user_roles'),('user_branches'),('categories'),('tax_master'),
    ('items'),('suppliers'),('customers'),('branch_inventory'),
    ('inventory_transactions'),('stock_adjustments'),('purchases'),
    ('purchase_details'),('sales'),('sales_details'),('stock_transfers'),
    ('stock_transfer_details'),('audit_logs')
) AS expected(table_name)
WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.tables t
    WHERE t.table_schema = 'public' AND t.table_name = expected.table_name
);

\echo '=== 4. Schema version markers ==='
-- Expect: items.rate exists; the two legacy price columns are gone;
-- tax/link columns exist; stores.tax_inclusive and branches.state exist.
SELECT 'items.rate' AS expected_column,
       EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name='items' AND column_name='rate') AS present
UNION ALL
SELECT 'items.tax_id',
       EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name='items' AND column_name='tax_id')
UNION ALL
SELECT 'items.default_selling_price (must be ABSENT)',
       NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name='items' AND column_name='default_selling_price')
UNION ALL
SELECT 'items.default_purchase_price (must be ABSENT)',
       NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name='items' AND column_name='default_purchase_price')
UNION ALL
SELECT 'stores.tax_inclusive',
       EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name='stores' AND column_name='tax_inclusive')
UNION ALL
SELECT 'branches.state',
       EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name='branches' AND column_name='state')
UNION ALL
SELECT 'sales.customer_state',
       EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name='sales' AND column_name='customer_state')
UNION ALL
SELECT 'sales_details.tax_name_snapshot',
       EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name='sales_details' AND column_name='tax_name_snapshot');

\echo '=== 5. Constraints and indexes ==='
SELECT contype, count(*) AS count
FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
GROUP BY contype
ORDER BY contype;
-- Expect primary keys (p), foreign keys (f), unique (u), check (c).

SELECT expected.index_name AS missing_index
FROM (VALUES
    ('ix_users_email'),('ix_users_store'),('ix_items_store_category'),
    ('ix_items_name'),('ix_items_tax'),('ix_tax_master_store_status'),
    ('ix_inventory_item'),('ix_inventory_branch'),('ix_txn_store_date'),
    ('ix_txn_branch_item'),('ix_txn_reference'),('ix_purchases_store_date'),
    ('ix_purchases_branch'),('ix_purchase_details_purchase'),
    ('ix_sales_store_date'),('ix_sales_branch'),('ix_sales_details_sale'),
    ('ix_transfers_store'),('ix_audit_store_date'),('uq_roles_store_code')
) AS expected(index_name)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_indexes i
    WHERE i.schemaname = 'public' AND i.indexname = expected.index_name
);

\echo '=== 6. Document number sequences ==='
SELECT sequencename, last_value, increment_by
FROM pg_sequences
WHERE schemaname = 'public'
  AND sequencename IN ('purchase_number_seq','sales_number_seq',
                       'transfer_number_seq','adjustment_number_seq')
ORDER BY sequencename;
-- Expect last_value >= 1001 after first use, or NULL/1000 before first nextval
-- depending on PostgreSQL version. Never reset these to reuse numbers.

\echo '=== 7. Permissions and platform role ==='
SELECT count(*) AS permission_count FROM permissions;              -- expect 28
SELECT count(*) AS missing_permissions
FROM (VALUES
    ('DASHBOARD_VIEW'),('PLATFORM_MANAGE'),('STORE_VIEW'),('STORE_MANAGE'),
    ('BRANCH_VIEW'),('BRANCH_MANAGE'),('USER_VIEW'),('USER_MANAGE'),
    ('ROLE_VIEW'),('ROLE_MANAGE'),('CATEGORY_VIEW'),('CATEGORY_MANAGE'),
    ('ITEM_VIEW'),('ITEM_MANAGE'),('SUPPLIER_VIEW'),('SUPPLIER_MANAGE'),
    ('PURCHASE_VIEW'),('PURCHASE_MANAGE'),('INVENTORY_VIEW'),('INVENTORY_ADJUST'),
    ('TRANSFER_VIEW'),('TRANSFER_MANAGE'),('TRANSFER_APPROVE'),('SALES_VIEW'),
    ('SALES_MANAGE'),('REPORT_VIEW'),('TAX_VIEW'),('TAX_MANAGE')
) AS expected(code)
WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.code = expected.code);

SELECT r.code AS platform_role, count(rp.permission_id) AS grants
FROM roles r
LEFT JOIN role_permissions rp ON rp.role_id = r.id
WHERE r.store_id IS NULL
GROUP BY r.code;
-- Expect exactly PLATFORM_ADMIN with 3 grants.

SELECT r.code AS store_role, count(rp.permission_id) AS grants
FROM roles r
LEFT JOIN role_permissions rp ON rp.role_id = r.id
WHERE r.store_id IS NOT NULL
GROUP BY r.store_id, r.code
ORDER BY r.store_id, r.code;

\echo '=== 8. Tenant integrity ==='
-- Users assigned to a branch of another store (must be empty).
SELECT u.id AS user_id, u.store_id AS user_store, b.store_id AS branch_store
FROM user_branches ub
JOIN users u    ON u.id = ub.user_id
JOIN branches b ON b.id = ub.branch_id
WHERE u.store_id IS DISTINCT FROM b.store_id;

-- Users linked to a role of another store (must be empty).
SELECT u.id AS user_id, u.store_id AS user_store, r.store_id AS role_store, r.code
FROM user_roles ur
JOIN users u ON u.id = ur.user_id
JOIN roles r ON r.id = ur.role_id
WHERE r.store_id IS NOT NULL
  AND u.store_id IS DISTINCT FROM r.store_id;

-- Items whose category or tax belongs to another store (must be empty; the
-- composite FKs should make this impossible).
SELECT i.id, i.store_id, c.store_id AS category_store, t.store_id AS tax_store
FROM items i
LEFT JOIN categories c ON c.id = i.category_id
LEFT JOIN tax_master t ON t.id = i.tax_id
WHERE i.category_id IS NULL OR i.tax_id IS NULL
   OR c.store_id IS DISTINCT FROM i.store_id
   OR t.store_id IS DISTINCT FROM i.store_id;

-- Purchase supplier/branch of another store (must be empty).
SELECT p.id, p.store_id, s.store_id AS supplier_store, b.store_id AS branch_store
FROM purchases p
JOIN suppliers s ON s.id = p.supplier_id
JOIN branches b  ON b.id = p.branch_id
WHERE s.store_id IS DISTINCT FROM p.store_id
   OR b.store_id IS DISTINCT FROM p.store_id;

-- Sales customer/branch of another store (must be empty).
SELECT s.id, s.store_id, b.store_id AS branch_store, c.store_id AS customer_store
FROM sales s
JOIN branches b ON b.id = s.branch_id
LEFT JOIN customers c ON c.id = s.customer_id
WHERE b.store_id IS DISTINCT FROM s.store_id
   OR (c.id IS NOT NULL AND c.store_id IS DISTINCT FROM s.store_id);

\echo '=== 9. Inventory invariants ==='
-- Negative stock (must be empty; also blocked by CHECK).
SELECT * FROM branch_inventory WHERE current_stock < 0;

-- Zero-quantity ledger rows (must be empty).
SELECT * FROM inventory_transactions
WHERE quantity_in = 0 AND quantity_out = 0;

-- Running ledger balance must equal balance_after for every movement.
WITH running AS (
    SELECT id, store_id, branch_id, item_id, quantity_in, quantity_out, balance_after,
           SUM(quantity_in - quantity_out) OVER (
               PARTITION BY store_id, branch_id, item_id
               ORDER BY id
           ) AS computed_balance
    FROM inventory_transactions
)
SELECT * FROM running
WHERE balance_after <> computed_balance;

-- Inventory snapshot must equal the ledger total and the last balance.
WITH ledger AS (
    SELECT store_id, branch_id, item_id,
           SUM(quantity_in - quantity_out) AS net_quantity,
           (ARRAY_AGG(balance_after ORDER BY id DESC))[1] AS last_balance
    FROM inventory_transactions
    GROUP BY store_id, branch_id, item_id
)
SELECT bi.store_id, bi.branch_id, bi.item_id,
       bi.current_stock, l.net_quantity, l.last_balance
FROM branch_inventory bi
JOIN ledger l
  ON l.store_id = bi.store_id AND l.branch_id = bi.branch_id AND l.item_id = bi.item_id
WHERE bi.current_stock <> l.net_quantity
   OR bi.current_stock <> l.last_balance;

-- Inventory rows with no ledger history (possible only for a zero-stock row
-- created by pricing edits; current_stock must be 0).
SELECT bi.*
FROM branch_inventory bi
WHERE bi.current_stock <> 0
  AND NOT EXISTS (
      SELECT 1 FROM inventory_transactions t
      WHERE t.store_id = bi.store_id AND t.branch_id = bi.branch_id
        AND t.item_id = bi.item_id
  );

-- Negative-cost or negative-price rows (must be empty; CHECKs also block).
SELECT * FROM branch_inventory
WHERE avg_cost < 0 OR purchase_price < 0 OR selling_price < 0;

\echo '=== 10. Purchase integrity ==='
-- Completed purchases with no lines (must be empty).
SELECT p.id, p.purchase_number
FROM purchases p
WHERE p.status = 'COMPLETED'
  AND NOT EXISTS (SELECT 1 FROM purchase_details d WHERE d.purchase_id = p.id);

-- Header GST totals must equal line sums (must be empty).
SELECT p.id, p.purchase_number,
       p.tax, l.tax_sum,
       p.taxable_amount, l.taxable_sum,
       p.cgst_amount, l.cgst_sum,
       p.sgst_amount, l.sgst_sum,
       p.igst_amount, l.igst_sum
FROM purchases p
JOIN (
    SELECT purchase_id,
           SUM(tax_amount) AS tax_sum,
           SUM(taxable_amount) AS taxable_sum,
           SUM(cgst_amount) AS cgst_sum,
           SUM(sgst_amount) AS sgst_sum,
           SUM(igst_amount) AS igst_sum
    FROM purchase_details
    GROUP BY purchase_id
) l ON l.purchase_id = p.id
WHERE abs(p.tax - l.tax_sum) > 0.01
   OR abs(p.taxable_amount - l.taxable_sum) > 0.01
   OR abs(p.cgst_amount - l.cgst_sum) > 0.01
   OR abs(p.sgst_amount - l.sgst_sum) > 0.01
   OR abs(p.igst_amount - l.igst_sum) > 0.01;

-- Header money identity (must be empty).
SELECT id, purchase_number, taxable_amount, tax, grand_total
FROM purchases
WHERE abs((taxable_amount + tax) - grand_total) > 0.01;

-- tax = cgst + sgst + igst (must be empty).
SELECT id, purchase_number, tax, cgst_amount, sgst_amount, igst_amount
FROM purchases
WHERE abs(tax - (cgst_amount + sgst_amount + igst_amount)) > 0.01;

-- paid + balance = grand total (must be empty).
SELECT id, purchase_number, paid_amount, balance_amount, grand_total
FROM purchases
WHERE abs((paid_amount + balance_amount) - grand_total) > 0.01;

\echo '=== 11. Sales integrity ==='
SELECT s.id, s.sales_number
FROM sales s
WHERE s.status = 'COMPLETED'
  AND NOT EXISTS (SELECT 1 FROM sales_details d WHERE d.sales_id = s.id);

SELECT s.id, s.sales_number,
       s.tax, l.tax_sum,
       s.taxable_amount, l.taxable_sum,
       s.cgst_amount, l.cgst_sum,
       s.sgst_amount, l.sgst_sum,
       s.igst_amount, l.igst_sum
FROM sales s
JOIN (
    SELECT sales_id,
           SUM(tax_amount) AS tax_sum,
           SUM(taxable_amount) AS taxable_sum,
           SUM(cgst_amount) AS cgst_sum,
           SUM(sgst_amount) AS sgst_sum,
           SUM(igst_amount) AS igst_sum
    FROM sales_details
    GROUP BY sales_id
) l ON l.sales_id = s.id
WHERE abs(s.tax - l.tax_sum) > 0.01
   OR abs(s.taxable_amount - l.taxable_sum) > 0.01
   OR abs(s.cgst_amount - l.cgst_sum) > 0.01
   OR abs(s.sgst_amount - l.sgst_sum) > 0.01
   OR abs(s.igst_amount - l.igst_sum) > 0.01;

SELECT id, sales_number, taxable_amount, tax, grand_total
FROM sales
WHERE abs((taxable_amount + tax) - grand_total) > 0.01;

SELECT id, sales_number, tax, cgst_amount, sgst_amount, igst_amount
FROM sales
WHERE abs(tax - (cgst_amount + sgst_amount + igst_amount)) > 0.01;

SELECT id, sales_number, paid_amount, balance_amount, grand_total
FROM sales
WHERE abs((paid_amount + balance_amount) - grand_total) > 0.01;

-- CREDIT sales must carry the unpaid balance; non-credit sales with a balance
-- are suspicious (informational, not necessarily invalid if a partial payment
-- was accepted by the API).
SELECT id, sales_number, payment_method, paid_amount, balance_amount
FROM sales
WHERE payment_method = 'CREDIT' AND balance_amount = 0
   OR payment_method <> 'CREDIT' AND balance_amount > 0;

-- Cost rate must be a positive snapshot for sales of stock received at a cost.
SELECT id, sales_id, item_id, base_quantity, cost_rate
FROM sales_details
WHERE cost_rate < 0;

\echo '=== 12. GST master integrity ==='
-- CGST + SGST must equal GST, and IGST must equal GST.
SELECT id, tax_name, gst_percentage, cgst_percentage, sgst_percentage, igst_percentage
FROM tax_master
WHERE abs((cgst_percentage + sgst_percentage) - gst_percentage) > 0.01
   OR abs(igst_percentage - gst_percentage) > 0.01;

-- EXEMPT slabs must be zero-rated.
SELECT id, tax_name, gst_percentage, cgst_percentage, sgst_percentage, igst_percentage
FROM tax_master
WHERE tax_type = 'EXEMPT'
  AND (gst_percentage <> 0 OR cgst_percentage <> 0
       OR sgst_percentage <> 0 OR igst_percentage <> 0);

-- Tax snapshots must add up on every line.
SELECT 'purchase' AS source, id, tax_amount, cgst_amount, sgst_amount, igst_amount
FROM purchase_details
WHERE abs(tax_amount - (cgst_amount + sgst_amount + igst_amount)) > 0.01
UNION ALL
SELECT 'sales', id, tax_amount, cgst_amount, sgst_amount, igst_amount
FROM sales_details
WHERE abs(tax_amount - (cgst_amount + sgst_amount + igst_amount)) > 0.01;

\echo '=== 13. Transfer integrity ==='
SELECT id, transfer_number
FROM stock_transfers
WHERE from_branch_id = to_branch_id;

SELECT t.id, t.transfer_number, t.status
FROM stock_transfers t
WHERE NOT EXISTS (SELECT 1 FROM stock_transfer_details d WHERE d.transfer_id = t.id);

-- Received transfers must have exactly one TRANSFER_OUT at source and one
-- TRANSFER_IN at destination per line (informational; should return no rows).
SELECT t.id, t.transfer_number, d.item_id, d.base_quantity,
       (SELECT coalesce(sum(x.quantity_out),0) FROM inventory_transactions x
        WHERE x.reference_type='TRANSFER' AND x.reference_id=t.id
          AND x.branch_id=t.from_branch_id AND x.item_id=d.item_id
          AND x.transaction_type='TRANSFER_OUT') AS out_qty,
       (SELECT coalesce(sum(x.quantity_in),0) FROM inventory_transactions x
        WHERE x.reference_type='TRANSFER' AND x.reference_id=t.id
          AND x.branch_id=t.to_branch_id AND x.item_id=d.item_id
          AND x.transaction_type='TRANSFER_IN') AS in_qty
FROM stock_transfers t
JOIN stock_transfer_details d ON d.transfer_id = t.id
WHERE t.status = 'RECEIVED'
  AND (
      (SELECT coalesce(sum(x.quantity_out),0) FROM inventory_transactions x
       WHERE x.reference_type='TRANSFER' AND x.reference_id=t.id
         AND x.branch_id=t.from_branch_id AND x.item_id=d.item_id
         AND x.transaction_type='TRANSFER_OUT') <> d.base_quantity
      OR
      (SELECT coalesce(sum(x.quantity_in),0) FROM inventory_transactions x
       WHERE x.reference_type='TRANSFER' AND x.reference_id=t.id
         AND x.branch_id=t.to_branch_id AND x.item_id=d.item_id
         AND x.transaction_type='TRANSFER_IN') <> d.base_quantity
  );

\echo '=== 14. Master data sanity ==='
SELECT store_id, item_code, count(*) AS duplicates
FROM items
GROUP BY store_id, item_code
HAVING count(*) > 1;

SELECT id, item_code, item_name
FROM items
WHERE rate < 0 OR min_stock_level < 0;

SELECT count(*) AS items_without_tax FROM items WHERE tax_id IS NULL;

SELECT count(*) AS stores_without_tax_slabs
FROM stores s
WHERE NOT EXISTS (SELECT 1 FROM tax_master t WHERE t.store_id = s.id);

SELECT count(*) AS stores_without_main_branch
FROM stores s
WHERE NOT EXISTS (
    SELECT 1 FROM branches b WHERE b.store_id = s.id AND b.branch_code = 'MAIN'
);

\echo '=== 15. Operational summary ==='
SELECT (SELECT count(*) FROM stores)                AS stores,
       (SELECT count(*) FROM branches)              AS branches,
       (SELECT count(*) FROM users)                 AS users,
       (SELECT count(*) FROM items)                 AS items,
       (SELECT count(*) FROM suppliers)             AS suppliers,
       (SELECT count(*) FROM purchases)              AS purchases,
       (SELECT count(*) FROM sales)                 AS sales,
       (SELECT count(*) FROM stock_transfers)        AS transfers,
       (SELECT count(*) FROM inventory_transactions) AS inventory_movements,
       (SELECT count(*) FROM audit_logs)            AS audit_rows;

-- Low-stock rows across all branches (stock <= minimum).
SELECT count(*) AS low_stock_rows
FROM branch_inventory
WHERE current_stock <= minimum_stock;

-- =====================================================================
-- END. No rows in sections 8-14 means all integrity checks passed.
-- =====================================================================
