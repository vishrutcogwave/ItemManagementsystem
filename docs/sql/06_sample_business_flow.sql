-- =====================================================================
-- 06_sample_business_flow.sql
-- Item Management System - end-to-end sample flow (documentation/demo only)
-- =====================================================================
--
-- WHAT THIS SCRIPT DEMONSTRATES
--   store -> branches -> roles -> GST slab -> category -> items -> supplier
--   -> opening stock -> purchase -> normal sale -> POS sale -> transfer
--   -> stock adjustment -> ledger and report-ready totals.
--
-- WHAT IT DOES NOT DO
--   - It does not create users. User passwords must be BCrypt-hashed by the
--     application (StoreProvisioningService / UserService); inserting a fake
--     hash here would create an unusable login. created_by is therefore NULL.
--   - It does not exercise stock locking or concurrency; the application does
--     that with SELECT ... FOR UPDATE. This is a static, realistic data set.
--   - It does not call the API. Every row is written with SQL so the arithmetic
--     is visible.
--
-- SAFETY
--   - Run only on a disposable demo/development database. Never on production.
--   - The script is one-shot: it raises an exception if store DOC001 exists.
--   - Everything runs in one transaction. If any statement fails, nothing is
--     committed.
--   - Completed purchases/sales are never deleted by the application. This demo
--     store is isolated under store_code DOC001; remove it only by dropping a
--     disposable demo database, not by deleting business history in production.
--
-- ARITHMETIC USED (store is tax-EXCLUSIVE: stores.tax_inclusive = false)
--   Purchase: 50 L x 48.00        = 2400.00 taxable; GST 5% = 120.00
--                                 (CGST 60.00 + SGST 60.00), total 2520.00
--   Sale:     10 L x 70.00        =  700.00 taxable; GST 5% =  35.00
--                                 (CGST 17.50 + SGST 17.50), total  735.00
--   POS sale:  5 PKT x 40.00      =  200.00 taxable; GST 5% =  10.00
--                                 (CGST 5.00 + SGST 5.00), total    210.00
--   Transfer: 20 L at source weighted average cost 46.0000 (no money posting)
--   Adjustment: 2 L OUT (spillage)
--
-- FINAL STOCK
--   MAIN milk: 100 opening + 50 purchase - 10 sale - 20 transfer - 2 adjust = 118
--   MAIN curd:  50 opening - 5 POS sale                                   =  45
--   SEC  milk:  20 transfer in                                            =  20
--
-- RUN
--   psql -h localhost -p 5432 -U ims_app -d item_management_db -f 06_sample_business_flow.sql
-- =====================================================================

BEGIN;

DO $$
DECLARE
    v_store             BIGINT;
    v_main              BIGINT;
    v_sec               BIGINT;
    v_tax               BIGINT;
    v_category          BIGINT;
    v_milk              BIGINT;
    v_curd              BIGINT;
    v_supplier          BIGINT;
    v_purchase          BIGINT;
    v_sale              BIGINT;
    v_pos_sale          BIGINT;
    v_transfer          BIGINT;
    v_purchase_number   TEXT;
    v_sale_number       TEXT;
    v_pos_number        TEXT;
    v_transfer_number   TEXT;
    v_adjustment_number TEXT;
    v_stock             NUMERIC(18,3);
BEGIN
    IF EXISTS (SELECT 1 FROM stores WHERE store_code = 'DOC001') THEN
        RAISE EXCEPTION 'Store DOC001 already exists. Use a disposable database or remove the demo store first.';
    END IF;

    -- -----------------------------------------------------------------
    -- 1. Store (tenant root)
    -- -----------------------------------------------------------------
    INSERT INTO stores (store_code, store_name, owner_name, email, mobile,
                        address, gst_number, tax_inclusive, status)
    VALUES ('DOC001', 'Documentation Demo Store', 'Asha Rao',
            'demo@example.com', '9876543210',
            '12 Market Road, Bengaluru, Karnataka 560001',
            '29ABCDE1234F1Z5', false, 'ACTIVE')
    RETURNING id INTO v_store;

    -- -----------------------------------------------------------------
    -- 2. Branches (MAIN is the registration branch; SEC is a second branch)
    -- -----------------------------------------------------------------
    INSERT INTO branches (store_id, branch_code, branch_name, address,
                          contact_number, state, status)
    VALUES (v_store, 'MAIN', 'Main Branch',
            '12 Market Road, Bengaluru, Karnataka 560001',
            '9876543210', 'Karnataka', 'ACTIVE')
    RETURNING id INTO v_main;

    INSERT INTO branches (store_id, branch_code, branch_name, address,
                          contact_number, state, status)
    VALUES (v_store, 'SEC', 'Second Branch',
            '88 Ring Road, Mysuru, Karnataka 570001',
            '9876543211', 'Karnataka', 'ACTIVE')
    RETURNING id INTO v_sec;

    -- -----------------------------------------------------------------
    -- 3. Standard store roles (mirrors StoreProvisioningService)
    --    Permissions are granted below from the catalogue in 04.
    -- -----------------------------------------------------------------
    INSERT INTO roles (store_id, code, name, description, is_system) VALUES
        (v_store, 'STORE_OWNER',    'Store Owner',
         'Owns the store and can manage every branch and setting', true),
        (v_store, 'STORE_ADMIN',    'Store Admin',
         'Handles day-to-day store operations across branches', true),
        (v_store, 'BRANCH_MANAGER', 'Branch Manager',
         'Manages inventory, purchases and sales for assigned branches', true),
        (v_store, 'BRANCH_STAFF',   'Branch Staff',
         'Bills sales and views inventory for assigned branches', true)
    ON CONFLICT DO NOTHING;

    -- STORE_OWNER: every permission except the platform-only one.
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    JOIN permissions p ON p.code <> 'PLATFORM_MANAGE'
    WHERE r.store_id = v_store AND r.code = 'STORE_OWNER'
    ON CONFLICT DO NOTHING;

    -- STORE_ADMIN: everything operational, no role/status administration.
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    JOIN permissions p ON p.code = ANY (ARRAY[
        'DASHBOARD_VIEW','STORE_VIEW','BRANCH_VIEW','USER_VIEW','USER_MANAGE',
        'ROLE_VIEW','CATEGORY_VIEW','CATEGORY_MANAGE','ITEM_VIEW','ITEM_MANAGE',
        'TAX_VIEW','TAX_MANAGE','SUPPLIER_VIEW','SUPPLIER_MANAGE',
        'PURCHASE_VIEW','PURCHASE_MANAGE','INVENTORY_VIEW','INVENTORY_ADJUST',
        'TRANSFER_VIEW','TRANSFER_MANAGE','TRANSFER_APPROVE',
        'SALES_VIEW','SALES_MANAGE','REPORT_VIEW'])
    WHERE r.store_id = v_store AND r.code = 'STORE_ADMIN'
    ON CONFLICT DO NOTHING;

    -- BRANCH_MANAGER: purchasing, stock and sales for assigned branches.
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    JOIN permissions p ON p.code = ANY (ARRAY[
        'DASHBOARD_VIEW','CATEGORY_VIEW','ITEM_VIEW','TAX_VIEW','SUPPLIER_VIEW',
        'PURCHASE_VIEW','PURCHASE_MANAGE','INVENTORY_VIEW','INVENTORY_ADJUST',
        'TRANSFER_VIEW','TRANSFER_MANAGE','SALES_VIEW','SALES_MANAGE','REPORT_VIEW'])
    WHERE r.store_id = v_store AND r.code = 'BRANCH_MANAGER'
    ON CONFLICT DO NOTHING;

    -- BRANCH_STAFF: billing and read-only catalogue/stock.
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    JOIN permissions p ON p.code = ANY (ARRAY[
        'DASHBOARD_VIEW','CATEGORY_VIEW','ITEM_VIEW','TAX_VIEW',
        'INVENTORY_VIEW','SALES_VIEW','SALES_MANAGE'])
    WHERE r.store_id = v_store AND r.code = 'BRANCH_STAFF'
    ON CONFLICT DO NOTHING;

    -- -----------------------------------------------------------------
    -- 4. Tax master: GST 5% (CGST 2.50 + SGST 2.50, IGST 5.00)
    -- -----------------------------------------------------------------
    INSERT INTO tax_master (store_id, tax_name, description, gst_percentage,
                            cgst_percentage, sgst_percentage, igst_percentage,
                            tax_type, status)
    VALUES (v_store, 'GST 5%', 'Standard 5% GST slab',
            5.00, 2.50, 2.50, 5.00, 'GST', 'ACTIVE')
    RETURNING id INTO v_tax;

    -- -----------------------------------------------------------------
    -- 5. Category and items (ONE master rate each)
    --    items.rate is the POS rate. Purchases/sales capture their own rates.
    -- -----------------------------------------------------------------
    INSERT INTO categories (store_id, category_name, description, status)
    VALUES (v_store, 'Dairy', 'Milk, curd and chilled dairy', 'ACTIVE')
    RETURNING id INTO v_category;

    INSERT INTO items (store_id, category_id, tax_id, item_code, item_name,
                       base_unit, rate, min_stock_level, status)
    VALUES (v_store, v_category, v_tax, 'DOC-MILK', 'Demo Milk 1L',
            'LITER', 60.00, 10, 'ACTIVE')
    RETURNING id INTO v_milk;

    INSERT INTO items (store_id, category_id, tax_id, item_code, item_name,
                       base_unit, rate, min_stock_level, status)
    VALUES (v_store, v_category, v_tax, 'DOC-CURD', 'Demo Curd 500g',
            'PACKET', 40.00, 20, 'ACTIVE')
    RETURNING id INTO v_curd;

    -- -----------------------------------------------------------------
    -- 6. Supplier in Karnataka (intra-state -> CGST + SGST)
    -- -----------------------------------------------------------------
    INSERT INTO suppliers (store_id, supplier_name, mobile, address, gst_number,
                           email, state, status)
    VALUES (v_store, 'Demo Dairy Supplier', '9988776655',
            'Industrial Area, Bengaluru, Karnataka', '29AAACD1234E1Z2',
            'supplier@example.com', 'Karnataka', 'ACTIVE')
    RETURNING id INTO v_supplier;

    -- -----------------------------------------------------------------
    -- 7. Opening stock (quantity, average cost, branch prices)
    --    Opening stock is a ledger entry, not a purchase.
    -- -----------------------------------------------------------------
    INSERT INTO branch_inventory (store_id, branch_id, item_id, current_stock,
                                  minimum_stock, purchase_price, selling_price,
                                  avg_cost, updated_at)
    VALUES (v_store, v_main, v_milk, 100, 10, 45.00, 60.00, 45.0000, now()),
           (v_store, v_main, v_curd,  50, 20, 28.00, 40.00, 28.0000, now());

    INSERT INTO inventory_transactions
        (store_id, branch_id, item_id, transaction_type, reference_type,
         reference_id, reference_number, quantity_in, quantity_out,
         balance_after, notes, created_by)
    VALUES
        (v_store, v_main, v_milk, 'OPENING_STOCK', 'OPENING', NULL, NULL,
         100, 0, 100, 'Opening stock', NULL),
        (v_store, v_main, v_curd, 'OPENING_STOCK', 'OPENING', NULL, NULL,
          50, 0,  50, 'Opening stock', NULL);

    -- -----------------------------------------------------------------
    -- 8. PURCHASE (COMPLETED): 50 L milk at a manually entered 48.00
    --    Header discount 0. Supplier state = branch state -> CGST + SGST.
    -- -----------------------------------------------------------------
    v_purchase_number := 'PUR-' || nextval('purchase_number_seq');

    INSERT INTO purchases
        (store_id, branch_id, supplier_id, purchase_number, invoice_number,
         purchase_date, subtotal, discount, tax, grand_total,
         taxable_amount, cgst_amount, sgst_amount, igst_amount,
         paid_amount, balance_amount, notes, status, created_by)
    VALUES
        (v_store, v_main, v_supplier, v_purchase_number, 'INV-DOC-001',
         CURRENT_DATE, 2400.00, 0.00, 120.00, 2520.00,
         2400.00, 60.00, 60.00, 0.00,
         2520.00, 0.00, 'Opening purchase for documentation demo',
         'COMPLETED', NULL)
    RETURNING id INTO v_purchase;

    INSERT INTO purchase_details
        (store_id, purchase_id, item_id, quantity, unit, base_quantity,
         purchase_rate, discount, tax, total_amount, tax_id, tax_name_snapshot,
         gst_percentage, cgst_percentage, sgst_percentage, igst_percentage,
         taxable_amount, cgst_amount, sgst_amount, igst_amount, tax_amount)
    VALUES
        (v_store, v_purchase, v_milk, 50, 'LITER', 50,
         48.00, 0.00, 120.00, 2520.00, v_tax, 'GST 5%',
         5.00, 2.50, 2.50, 5.00,
         2400.00, 60.00, 60.00, 0.00, 120.00);

    -- Weighted average cost: (100 x 45.0000 + 50 x 48.00) / 150 = 46.0000
    UPDATE branch_inventory
    SET current_stock = current_stock + 50,
        avg_cost = ROUND(((current_stock * avg_cost) + (50 * 48.00))
                         / (current_stock + 50), 4),
        purchase_price = 48.00,
        updated_at = now()
    WHERE store_id = v_store AND branch_id = v_main AND item_id = v_milk
    RETURNING current_stock INTO v_stock;

    INSERT INTO inventory_transactions
        (store_id, branch_id, item_id, transaction_type, reference_type,
         reference_id, reference_number, quantity_in, quantity_out,
         balance_after, notes, created_by)
    VALUES
        (v_store, v_main, v_milk, 'PURCHASE_IN', 'PURCHASE', v_purchase,
         v_purchase_number, 50, 0, v_stock, 'Purchase received', NULL);

    -- -----------------------------------------------------------------
    -- 9. NORMAL SALE: 10 L milk at a manually entered 70.00
    --    Revenue is tax-exclusive: 700.00 + 35.00 GST = 735.00.
    -- -----------------------------------------------------------------
    v_sale_number := 'SAL-' || nextval('sales_number_seq');

    INSERT INTO sales
        (store_id, branch_id, sales_number, sales_date, customer_name,
         customer_mobile, customer_state, subtotal, discount, tax, grand_total,
         taxable_amount, cgst_amount, sgst_amount, igst_amount,
         paid_amount, balance_amount, payment_method, status, created_by)
    VALUES
        (v_store, v_main, v_sale_number, CURRENT_DATE, 'Walk-in Customer',
         NULL, 'Karnataka', 700.00, 0.00, 35.00, 735.00,
         700.00, 17.50, 17.50, 0.00,
         735.00, 0.00, 'CASH', 'COMPLETED', NULL)
    RETURNING id INTO v_sale;

    INSERT INTO sales_details
        (store_id, sales_id, item_id, quantity, unit, base_quantity,
         selling_rate, cost_rate, discount, tax, total_amount,
         tax_id, tax_name_snapshot, gst_percentage, cgst_percentage,
         sgst_percentage, igst_percentage, taxable_amount, cgst_amount,
         sgst_amount, igst_amount, tax_amount)
    VALUES
        (v_store, v_sale, v_milk, 10, 'LITER', 10,
         70.00, 46.0000, 0.00, 35.00, 735.00,
         v_tax, 'GST 5%', 5.00, 2.50, 2.50, 5.00,
         700.00, 17.50, 17.50, 0.00, 35.00);

    UPDATE branch_inventory
    SET current_stock = current_stock - 10, updated_at = now()
    WHERE store_id = v_store AND branch_id = v_main AND item_id = v_milk
    RETURNING current_stock INTO v_stock;

    INSERT INTO inventory_transactions
        (store_id, branch_id, item_id, transaction_type, reference_type,
         reference_id, reference_number, quantity_in, quantity_out,
         balance_after, notes, created_by)
    VALUES
        (v_store, v_main, v_milk, 'SALE_OUT', 'SALE', v_sale,
         v_sale_number, 0, 10, v_stock, 'Normal sale', NULL);

    -- -----------------------------------------------------------------
    -- 10. POS SALE: 5 packets curd at the ITEM MASTER rate (40.00)
    --     A POS request never supplies a trusted rate; the server uses
    --     items.rate. Tax-exclusive total: 200 + 10 = 210.
    -- -----------------------------------------------------------------
    v_pos_number := 'SAL-' || nextval('sales_number_seq');

    INSERT INTO sales
        (store_id, branch_id, sales_number, sales_date, customer_name,
         customer_mobile, customer_state, subtotal, discount, tax, grand_total,
         taxable_amount, cgst_amount, sgst_amount, igst_amount,
         paid_amount, balance_amount, payment_method, status, created_by)
    VALUES
        (v_store, v_main, v_pos_number, CURRENT_DATE, 'POS Customer',
         NULL, 'Karnataka', 200.00, 0.00, 10.00, 210.00,
         200.00, 5.00, 5.00, 0.00,
         210.00, 0.00, 'UPI', 'COMPLETED', NULL)
    RETURNING id INTO v_pos_sale;

    INSERT INTO sales_details
        (store_id, sales_id, item_id, quantity, unit, base_quantity,
         selling_rate, cost_rate, discount, tax, total_amount,
         tax_id, tax_name_snapshot, gst_percentage, cgst_percentage,
         sgst_percentage, igst_percentage, taxable_amount, cgst_amount,
         sgst_amount, igst_amount, tax_amount)
    VALUES
        (v_store, v_pos_sale, v_curd, 5, 'PACKET', 5,
         40.00, 28.0000, 0.00, 10.00, 210.00,
         v_tax, 'GST 5%', 5.00, 2.50, 2.50, 5.00,
         200.00, 5.00, 5.00, 0.00, 10.00);

    UPDATE branch_inventory
    SET current_stock = current_stock - 5, updated_at = now()
    WHERE store_id = v_store AND branch_id = v_main AND item_id = v_curd
    RETURNING current_stock INTO v_stock;

    INSERT INTO inventory_transactions
        (store_id, branch_id, item_id, transaction_type, reference_type,
         reference_id, reference_number, quantity_in, quantity_out,
         balance_after, notes, created_by)
    VALUES
        (v_store, v_main, v_curd, 'SALE_OUT', 'SALE', v_pos_sale,
         v_pos_number, 0, 5, v_stock, 'POS sale at item master rate', NULL);

    -- -----------------------------------------------------------------
    -- 11. TRANSFER: 20 L milk MAIN -> SEC, already RECEIVED.
    --     Source leaves at dispatch, destination enters at source avg_cost.
    -- -----------------------------------------------------------------
    v_transfer_number := 'TRF-' || nextval('transfer_number_seq');

    INSERT INTO stock_transfers
        (store_id, transfer_number, from_branch_id, to_branch_id,
         transfer_date, status, notes, created_by)
    VALUES
        (v_store, v_transfer_number, v_main, v_sec,
         CURRENT_DATE, 'RECEIVED', 'Rebalance stock to second branch', NULL)
    RETURNING id INTO v_transfer;

    INSERT INTO stock_transfer_details
        (store_id, transfer_id, item_id, quantity, unit, base_quantity)
    VALUES
        (v_store, v_transfer, v_milk, 20, 'LITER', 20);

    UPDATE branch_inventory
    SET current_stock = current_stock - 20, updated_at = now()
    WHERE store_id = v_store AND branch_id = v_main AND item_id = v_milk
    RETURNING current_stock INTO v_stock;

    INSERT INTO inventory_transactions
        (store_id, branch_id, item_id, transaction_type, reference_type,
         reference_id, reference_number, quantity_in, quantity_out,
         balance_after, notes, created_by)
    VALUES
        (v_store, v_main, v_milk, 'TRANSFER_OUT', 'TRANSFER', v_transfer,
         v_transfer_number, 0, 20, v_stock, 'Transfer to SEC', NULL);

    INSERT INTO branch_inventory
        (store_id, branch_id, item_id, current_stock, minimum_stock,
         purchase_price, selling_price, avg_cost, updated_at)
    VALUES
        (v_store, v_sec, v_milk, 20, 10, 46.00, 60.00, 46.0000, now());

    INSERT INTO inventory_transactions
        (store_id, branch_id, item_id, transaction_type, reference_type,
         reference_id, reference_number, quantity_in, quantity_out,
         balance_after, notes, created_by)
    VALUES
        (v_store, v_sec, v_milk, 'TRANSFER_IN', 'TRANSFER', v_transfer,
         v_transfer_number, 20, 0, 20, 'Transfer from MAIN', NULL);

    -- -----------------------------------------------------------------
    -- 12. MANUAL ADJUSTMENT OUT: 2 L milk spillage in MAIN
    -- -----------------------------------------------------------------
    v_adjustment_number := 'ADJ-' || nextval('adjustment_number_seq');

    UPDATE branch_inventory
    SET current_stock = current_stock - 2, updated_at = now()
    WHERE store_id = v_store AND branch_id = v_main AND item_id = v_milk
    RETURNING current_stock INTO v_stock;

    INSERT INTO stock_adjustments
        (store_id, branch_id, item_id, adjustment_number, adjustment_type,
         quantity, base_quantity, unit, reason, created_by)
    VALUES
        (v_store, v_main, v_milk, v_adjustment_number, 'OUT',
         2, 2, 'LITER', 'Spillage during handling', NULL);

    INSERT INTO inventory_transactions
        (store_id, branch_id, item_id, transaction_type, reference_type,
         reference_id, reference_number, quantity_in, quantity_out,
         balance_after, notes, created_by)
    VALUES
        (v_store, v_main, v_milk, 'ADJUSTMENT_OUT', 'ADJUSTMENT', NULL,
         v_adjustment_number, 0, 2, v_stock, 'Spillage during handling', NULL);

    RAISE NOTICE 'Demo store DOC001 created (store id %, main branch %, second branch %)',
        v_store, v_main, v_sec;
END $$;

COMMIT;

-- =====================================================================
-- VERIFICATION QUERIES (read-only)
-- =====================================================================

\echo '=== Store and branches ==='
SELECT s.id, s.store_code, s.store_name, s.tax_inclusive,
       b.branch_code, b.branch_name, b.state
FROM stores s
JOIN branches b ON b.store_id = s.id
WHERE s.store_code = 'DOC001'
ORDER BY b.branch_code;

\echo '=== Items and master rates ==='
SELECT item_code, item_name, base_unit, rate, min_stock_level
FROM items
WHERE store_id = (SELECT id FROM stores WHERE store_code = 'DOC001')
ORDER BY item_code;

\echo '=== Current stock by branch ==='
SELECT b.branch_code, i.item_code, bi.current_stock, bi.minimum_stock,
       bi.avg_cost, bi.purchase_price, bi.selling_price,
       ROUND(bi.current_stock * bi.avg_cost, 2) AS stock_value
FROM branch_inventory bi
JOIN branches b ON b.id = bi.branch_id
JOIN items i ON i.id = bi.item_id
JOIN stores s ON s.id = bi.store_id
WHERE s.store_code = 'DOC001'
ORDER BY b.branch_code, i.item_code;
-- Expect MAIN/DOC-MILK 118, MAIN/DOC-CURD 45, SEC/DOC-MILK 20.

\echo '=== Purchases ==='
SELECT purchase_number, purchase_date, subtotal, taxable_amount, tax,
       grand_total, paid_amount, balance_amount, status
FROM purchases
WHERE store_id = (SELECT id FROM stores WHERE store_code = 'DOC001');

\echo '=== Sales ==='
SELECT sales_number, sales_date, customer_name, payment_method,
       taxable_amount, tax, grand_total, status
FROM sales
WHERE store_id = (SELECT id FROM stores WHERE store_code = 'DOC001')
ORDER BY id;

\echo '=== Transfer and adjustment ==='
SELECT transfer_number, from_branch_id, to_branch_id, status
FROM stock_transfers
WHERE store_id = (SELECT id FROM stores WHERE store_code = 'DOC001');

SELECT adjustment_number, adjustment_type, quantity, unit, reason
FROM stock_adjustments
WHERE store_id = (SELECT id FROM stores WHERE store_code = 'DOC001');

\echo '=== Ledger for MAIN milk (should end at 118) ==='
SELECT t.transaction_type, t.reference_number, t.quantity_in, t.quantity_out,
       t.balance_after, t.notes
FROM inventory_transactions t
JOIN stores s ON s.id = t.store_id
JOIN branches b ON b.id = t.branch_id
JOIN items i ON i.id = t.item_id
WHERE s.store_code = 'DOC001' AND b.branch_code = 'MAIN' AND i.item_code = 'DOC-MILK'
ORDER BY t.id;

\echo '=== GST summary (output tax) ==='
SELECT s.sales_number, i.item_code, d.tax_name_snapshot, d.gst_percentage,
       d.taxable_amount, d.cgst_amount, d.sgst_amount, d.igst_amount,
       d.tax_amount, d.total_amount
FROM sales s
JOIN sales_details d ON d.sales_id = s.id
JOIN items i ON i.id = d.item_id
JOIN stores st ON st.id = s.store_id
WHERE st.store_code = 'DOC001'
ORDER BY s.id;

\echo '=== Profit snapshot ==='
SELECT i.item_code,
       SUM(d.base_quantity) AS quantity_sold,
       SUM(d.total_amount - d.tax) AS revenue,
       SUM(d.base_quantity * d.cost_rate) AS cogs,
       SUM(d.total_amount - d.tax) - SUM(d.base_quantity * d.cost_rate) AS gross_profit
FROM sales_details d
JOIN sales s ON s.id = d.sales_id
JOIN items i ON i.id = d.item_id
JOIN stores st ON st.id = d.store_id
WHERE st.store_code = 'DOC001' AND s.status = 'COMPLETED'
GROUP BY i.item_code
ORDER BY i.item_code;
-- Milk: 10 x 70 = 700 revenue, 10 x 46 = 460 COGS, 240 profit
-- Curd:  5 x 40 = 200 revenue,  5 x 28 = 140 COGS,  60 profit

-- =====================================================================
-- END. The application's report endpoints produce the same figures for this
-- store because they read these tables, not the demo constants above.
-- =====================================================================
