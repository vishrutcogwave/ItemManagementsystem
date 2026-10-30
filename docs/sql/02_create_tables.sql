-- =====================================================================
-- 02_create_tables.sql
-- Item Management System - consolidated FINAL schema (after V1 + V2 + V3)
-- =====================================================================
--
-- PURPOSE
--   A readable, single-file version of the schema the application actually
--   runs after all three Flyway migrations. It is intended for DBAs, reviews,
--   manual rebuilds and documentation. It contains no data.
--
-- IMPORTANT: FLYWAY OWNS THE REAL SCHEMA
--   The application runs Flyway on startup with
--     spring.flyway.enabled=true
--     spring.flyway.locations=classpath:db/migration
--     spring.jpa.hibernate.ddl-auto=validate
--   The authoritative, incremental files are:
--     backend/src/main/resources/db/migration/V1__init.sql
--     backend/src/main/resources/db/migration/V2__tax_master.sql
--     backend/src/main/resources/db/migration/V3__item_rate.sql
--
--   Choose ONE of these paths on a fresh database:
--     A. RECOMMENDED: start the application with the "postgres" profile and
--        let Flyway create everything. Do not run 02/03/04 manually.
--     B. MANUAL: run 01, 02, 03, 04 yourself and set
--        SPRING_FLYWAY_ENABLED=false (or spring.flyway.enabled=false) before
--        starting the application. Otherwise Flyway will try to run V1 and
--        fail because the tables already exist.
--
--   Do not run this file against a database that Flyway already migrated.
--   Do not baseline a manually created schema at version 1 and then replay
--   V2/V3; that would duplicate the tax/rate changes.
--
-- TARGET
--   PostgreSQL 14+. All timestamps are TIMESTAMPTZ; Hibernate uses UTC.
--
-- TENANCY MODEL
--   One database. Every tenant-owned row carries store_id.
--   Branch-scoped rows also carry branch_id.
--   Composite UNIQUE (store_id, id) keys plus composite foreign keys make it
--   impossible for a row in store A to reference a row in store B.
--
-- CONVENTIONS
--   - Identity: BIGSERIAL / BIGINT.
--   - Money:    NUMERIC(14,2), except item master rate NUMERIC(12,2) and
--               weighted average cost NUMERIC(18,4).
--   - Quantity: NUMERIC(18,3) in the item's base unit.
--   - Status:   VARCHAR + CHECK, mirrored by Java enums.
--   - Soft delete: categories/tax slabs referenced by history cannot be
--     deleted; status is set to INACTIVE instead. Completed purchases and
--     sales are never deleted, only CANCELLED with reversal stock movements.
--
-- RUN ORDER
--   01_create_database.sql -> 02_create_tables.sql -> 03_create_indexes.sql
--   -> 04_insert_initial_data.sql -> (optional) 06_sample_business_flow.sql
--   05_database_validation.sql is read-only and can be run at any time.
-- =====================================================================


-- =====================================================================
-- 1. STORE (tenant root)
-- =====================================================================
CREATE TABLE stores (
    id            BIGSERIAL PRIMARY KEY,
    store_code    VARCHAR(30)  NOT NULL UNIQUE,
    store_name    VARCHAR(150) NOT NULL,
    owner_name    VARCHAR(150) NOT NULL,
    email         VARCHAR(150) NOT NULL,
    mobile        VARCHAR(20)  NOT NULL,
    address       TEXT         NOT NULL,
    gst_number    VARCHAR(30),
    tax_inclusive BOOLEAN      NOT NULL DEFAULT false,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE stores IS 'Tenant root. Exactly one row per registered business/store.';
COMMENT ON COLUMN stores.store_code IS 'Login tenant code. Reserved value PLATFORM is rejected by the application.';
COMMENT ON COLUMN stores.tax_inclusive IS 'When true, item rates are treated as GST-inclusive and tax is extracted.';


-- =====================================================================
-- 2. BRANCH (belongs to one store)
-- =====================================================================
CREATE TABLE branches (
    id             BIGSERIAL PRIMARY KEY,
    store_id       BIGINT       NOT NULL REFERENCES stores(id),
    branch_code    VARCHAR(30)  NOT NULL,
    branch_name    VARCHAR(150) NOT NULL,
    address        TEXT         NOT NULL,
    contact_number VARCHAR(20)  NOT NULL,
    state          VARCHAR(80),
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_branches_code UNIQUE (store_id, branch_code),
    CONSTRAINT uq_branches_store_id UNIQUE (store_id, id)
);

COMMENT ON COLUMN branches.state IS 'Registered place of supply. Used to decide CGST+SGST vs IGST.';
COMMENT ON CONSTRAINT uq_branches_store_id ON branches IS
    'Target for composite FKs, preventing cross-store references.';


-- =====================================================================
-- 3. ROLES, PERMISSIONS AND USER LINKS
-- =====================================================================
CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT REFERENCES stores(id) ON DELETE CASCADE,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    is_system   BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Functional unique index because store_id is NULL for the platform role.
-- COALESCE(store_id, 0) lets PostgreSQL enforce uniqueness for NULL tenants.
CREATE UNIQUE INDEX uq_roles_store_code ON roles (COALESCE(store_id, 0), code);

COMMENT ON TABLE roles IS
    'A role belongs to one store. store_id IS NULL identifies the platform role PLATFORM_ADMIN.';
COMMENT ON COLUMN roles.is_system IS 'System roles are provisioned with the store; they are not deletable.';

CREATE TABLE permissions (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(60)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(255)
);

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT REFERENCES stores(id),
    username      VARCHAR(80)  NOT NULL,
    email         VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    mobile        VARCHAR(20),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_store_username UNIQUE (store_id, username)
);

COMMENT ON COLUMN users.store_id IS
    'NULL = platform administrator. Non-null = store user, isolated to that store.';
COMMENT ON COLUMN users.password_hash IS 'BCrypt hash (strength 10). Plain passwords are never stored.';

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE user_branches (
    user_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    branch_id BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, branch_id)
);

COMMENT ON TABLE user_branches IS
    'Branch assignments for BRANCH_MANAGER / BRANCH_STAFF. Owners and admins get allBranches implicitly.';


-- =====================================================================
-- 4. CATEGORIES
-- =====================================================================
CREATE TABLE categories (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT       NOT NULL REFERENCES stores(id),
    category_name VARCHAR(120) NOT NULL,
    description   VARCHAR(255),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_categories_name UNIQUE (store_id, category_name),
    CONSTRAINT uq_categories_store_id UNIQUE (store_id, id)
);


-- =====================================================================
-- 5. TAX MASTER (store-specific GST slabs)
-- =====================================================================
CREATE TABLE tax_master (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT       NOT NULL REFERENCES stores(id),
    tax_name        VARCHAR(120) NOT NULL,
    description     VARCHAR(255),
    gst_percentage  NUMERIC(5,2) NOT NULL
                    CHECK (gst_percentage >= 0 AND gst_percentage <= 100),
    cgst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0
                    CHECK (cgst_percentage >= 0 AND cgst_percentage <= 100),
    sgst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0
                    CHECK (sgst_percentage >= 0 AND sgst_percentage <= 100),
    igst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0
                    CHECK (igst_percentage >= 0 AND igst_percentage <= 100),
    tax_type        VARCHAR(20)  NOT NULL DEFAULT 'GST'
                    CHECK (tax_type IN ('GST', 'EXEMPT')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tax_master_store_name UNIQUE (store_id, tax_name),
    CONSTRAINT uq_tax_master_store_id UNIQUE (store_id, id)
);

COMMENT ON TABLE tax_master IS
    'Store-owned GST slabs. Every store is seeded with 0% (EXEMPT), 5%, 12%, 18% and 28%.';
COMMENT ON COLUMN tax_master.gst_percentage IS
    'Total GST rate. The application derives cgst = gst/2 (HALF_UP), sgst = remainder, igst = gst.';


-- =====================================================================
-- 6. ITEMS (one master rate, one tax slab)
-- =====================================================================
CREATE TABLE items (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT        NOT NULL REFERENCES stores(id),
    category_id     BIGINT        NOT NULL,
    tax_id          BIGINT        NOT NULL,
    item_code       VARCHAR(50)   NOT NULL,
    item_name       VARCHAR(150)  NOT NULL,
    base_unit       VARCHAR(20)   NOT NULL
                    CHECK (base_unit IN ('LITER','ML','KG','GRAM','PIECE','PACKET','BOX','BOTTLE','DOZEN')),
    rate            NUMERIC(12,2) NOT NULL,
    min_stock_level NUMERIC(18,3) NOT NULL DEFAULT 5 CHECK (min_stock_level >= 0),
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_items_rate_non_negative CHECK (rate >= 0),
    CONSTRAINT uq_items_code UNIQUE (store_id, item_code),
    CONSTRAINT uq_items_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_items_category FOREIGN KEY (category_id, store_id)
        REFERENCES categories(id, store_id),
    CONSTRAINT fk_items_tax FOREIGN KEY (tax_id, store_id)
        REFERENCES tax_master(id, store_id)
);

COMMENT ON COLUMN items.rate IS
    'The single item master rate. POS billing always uses this value server-side. '
    'Normal sales and purchases store their own negotiated per-line rates.';


-- =====================================================================
-- 7. SUPPLIERS AND CUSTOMERS
-- =====================================================================
CREATE TABLE suppliers (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT       NOT NULL REFERENCES stores(id),
    supplier_name VARCHAR(150) NOT NULL,
    mobile        VARCHAR(20)  NOT NULL,
    address       TEXT         NOT NULL,
    gst_number    VARCHAR(30),
    email         VARCHAR(150),
    state         VARCHAR(80),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_suppliers_store_id UNIQUE (store_id, id)
);

COMMENT ON COLUMN suppliers.state IS
    'Supplier place of supply. Compared with the branch state for IGST.';

CREATE TABLE customers (
    id         BIGSERIAL PRIMARY KEY,
    store_id   BIGINT       NOT NULL REFERENCES stores(id),
    name       VARCHAR(150) NOT NULL,
    mobile     VARCHAR(20),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_store_id UNIQUE (store_id, id)
);

COMMENT ON TABLE customers IS
    'Reserved for a future customer master. Sales currently store customer name/mobile/state '
    'as free-text snapshots; no service or controller reads or writes this table.';


-- =====================================================================
-- 8. INVENTORY (per branch + item)
-- =====================================================================
CREATE TABLE branch_inventory (
    id             BIGSERIAL PRIMARY KEY,
    store_id       BIGINT        NOT NULL REFERENCES stores(id),
    branch_id      BIGINT        NOT NULL,
    item_id        BIGINT        NOT NULL,
    current_stock  NUMERIC(18,3) NOT NULL DEFAULT 0 CHECK (current_stock >= 0),
    minimum_stock  NUMERIC(18,3) NOT NULL DEFAULT 5 CHECK (minimum_stock >= 0),
    purchase_price NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (purchase_price >= 0),
    selling_price  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (selling_price >= 0),
    avg_cost       NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (avg_cost >= 0),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_inventory_branch_item UNIQUE (store_id, branch_id, item_id),
    CONSTRAINT fk_inventory_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_inventory_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id)
);

COMMENT ON TABLE branch_inventory IS
    'Current stock and weighted average cost per branch and item. The database CHECK '
    'current_stock >= 0 is the last line of defence against overselling.';
COMMENT ON COLUMN branch_inventory.minimum_stock IS
    'Per-branch low-stock threshold, initialised from items.min_stock_level.';
COMMENT ON COLUMN branch_inventory.avg_cost IS
    'Weighted average purchase cost used for COGS and stock valuation.';


-- =====================================================================
-- 9. INVENTORY LEDGER
-- =====================================================================
CREATE TABLE inventory_transactions (
    id               BIGSERIAL PRIMARY KEY,
    store_id         BIGINT        NOT NULL REFERENCES stores(id),
    branch_id        BIGINT        NOT NULL,
    item_id          BIGINT        NOT NULL,
    transaction_type VARCHAR(30)   NOT NULL CHECK (transaction_type IN (
        'OPENING_STOCK', 'PURCHASE_IN', 'SALE_OUT', 'TRANSFER_IN', 'TRANSFER_OUT',
        'ADJUSTMENT_IN', 'ADJUSTMENT_OUT', 'PURCHASE_REVERSAL', 'SALE_REVERSAL')),
    reference_type   VARCHAR(30),
    reference_id     BIGINT,
    reference_number VARCHAR(50),
    quantity_in      NUMERIC(18,3) NOT NULL DEFAULT 0 CHECK (quantity_in >= 0),
    quantity_out     NUMERIC(18,3) NOT NULL DEFAULT 0 CHECK (quantity_out >= 0),
    balance_after    NUMERIC(18,3) NOT NULL CHECK (balance_after >= 0),
    notes            VARCHAR(255),
    transaction_date TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT REFERENCES users(id),
    CONSTRAINT fk_txn_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_txn_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id)
);

COMMENT ON TABLE inventory_transactions IS
    'Append-only stock ledger. Every stock change writes one row; rows are never updated or deleted.';
COMMENT ON COLUMN inventory_transactions.balance_after IS
    'Stock on hand after this movement; used for audit and opening-stock derivation.';


-- =====================================================================
-- 10. STOCK ADJUSTMENTS (manual IN/OUT)
-- =====================================================================
CREATE TABLE stock_adjustments (
    id                BIGSERIAL PRIMARY KEY,
    store_id          BIGINT        NOT NULL REFERENCES stores(id),
    branch_id         BIGINT        NOT NULL,
    item_id           BIGINT        NOT NULL,
    adjustment_number VARCHAR(50)   NOT NULL,
    adjustment_type   VARCHAR(20)   NOT NULL CHECK (adjustment_type IN ('IN', 'OUT')),
    quantity          NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    base_quantity     NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    unit              VARCHAR(20)   NOT NULL,
    reason            VARCHAR(255)  NOT NULL,
    created_by        BIGINT REFERENCES users(id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_adjustments_number UNIQUE (store_id, adjustment_number),
    CONSTRAINT fk_adjustment_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_adjustment_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id)
);

COMMENT ON COLUMN stock_adjustments.quantity IS 'Quantity in the entered unit.';
COMMENT ON COLUMN stock_adjustments.base_quantity IS 'Converted quantity in the item base unit.';


-- =====================================================================
-- 11. PURCHASES
-- =====================================================================
CREATE TABLE purchases (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT        NOT NULL REFERENCES stores(id),
    branch_id       BIGINT        NOT NULL,
    supplier_id     BIGINT        NOT NULL,
    purchase_number VARCHAR(50)   NOT NULL,
    invoice_number  VARCHAR(80),
    purchase_date   DATE          NOT NULL,
    subtotal        NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax             NUMERIC(14,2) NOT NULL DEFAULT 0,
    grand_total     NUMERIC(14,2) NOT NULL DEFAULT 0,
    taxable_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    cgst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    sgst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    igst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    paid_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    balance_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    notes           VARCHAR(500),
    status          VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN ('DRAFT', 'COMPLETED', 'CANCELLED')),
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_purchases_number UNIQUE (store_id, purchase_number),
    CONSTRAINT uq_purchases_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_purchase_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_purchase_supplier FOREIGN KEY (supplier_id, store_id)
        REFERENCES suppliers(id, store_id)
);

COMMENT ON TABLE purchases IS
    'Purchase header. Completed purchases are reversed with CANCELLED, never deleted.';
COMMENT ON COLUMN purchases.tax IS 'Total GST across all lines, equal to cgst_amount + sgst_amount + igst_amount.';
COMMENT ON COLUMN purchases.balance_amount IS 'grand_total - paid_amount.';


-- =====================================================================
-- 12. PURCHASE LINES (immutable rates and tax snapshots)
-- =====================================================================
CREATE TABLE purchase_details (
    id                BIGSERIAL PRIMARY KEY,
    store_id          BIGINT        NOT NULL REFERENCES stores(id),
    purchase_id       BIGINT        NOT NULL,
    item_id           BIGINT        NOT NULL,
    quantity          NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    unit              VARCHAR(20)   NOT NULL,
    base_quantity     NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    purchase_rate     NUMERIC(14,2) NOT NULL CHECK (purchase_rate >= 0),
    discount          NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax               NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_amount      NUMERIC(14,2) NOT NULL,
    tax_id            BIGINT,
    tax_name_snapshot VARCHAR(120)  NOT NULL DEFAULT 'GST 0% (Exempt)',
    gst_percentage    NUMERIC(5,2)  NOT NULL DEFAULT 0,
    cgst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    sgst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    igst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    taxable_amount    NUMERIC(14,2) NOT NULL DEFAULT 0,
    cgst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    sgst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    igst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_amount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_pd_purchase FOREIGN KEY (purchase_id, store_id)
        REFERENCES purchases(id, store_id) ON DELETE CASCADE,
    CONSTRAINT fk_pd_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id),
    CONSTRAINT fk_pd_tax FOREIGN KEY (tax_id, store_id)
        REFERENCES tax_master(id, store_id)
);

COMMENT ON TABLE purchase_details IS
    'Purchase lines. purchase_rate is entered manually; tax columns are server-computed '
    'snapshots and are never recalculated from the tax master later.';


-- =====================================================================
-- 13. SALES
-- =====================================================================
CREATE TABLE sales (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT        NOT NULL REFERENCES stores(id),
    branch_id       BIGINT        NOT NULL,
    sales_number    VARCHAR(50)   NOT NULL,
    sales_date      DATE          NOT NULL,
    customer_id     BIGINT,
    customer_name   VARCHAR(150),
    customer_mobile VARCHAR(20),
    customer_state  VARCHAR(80),
    subtotal        NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax             NUMERIC(14,2) NOT NULL DEFAULT 0,
    grand_total     NUMERIC(14,2) NOT NULL DEFAULT 0,
    taxable_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    cgst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    sgst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    igst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    paid_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    balance_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    payment_method  VARCHAR(20)   NOT NULL DEFAULT 'CASH'
                    CHECK (payment_method IN ('CASH', 'UPI', 'CARD', 'CREDIT')),
    status          VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN ('COMPLETED', 'CANCELLED')),
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_sales_number UNIQUE (store_id, sales_number),
    CONSTRAINT uq_sales_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_sale_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_sale_customer FOREIGN KEY (customer_id, store_id)
        REFERENCES customers(id, store_id)
);

COMMENT ON COLUMN sales.customer_state IS
    'Place of supply snapshot. Null/blank means intra-state (walk-in default).';
COMMENT ON COLUMN sales.payment_method IS
    'CASH, UPI, CARD or CREDIT. There is no separate payments table.';


-- =====================================================================
-- 14. SALES LINES (immutable rates, cost and tax snapshots)
-- =====================================================================
CREATE TABLE sales_details (
    id                BIGSERIAL PRIMARY KEY,
    store_id          BIGINT        NOT NULL REFERENCES stores(id),
    sales_id          BIGINT        NOT NULL,
    item_id           BIGINT        NOT NULL,
    quantity          NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    unit              VARCHAR(20)   NOT NULL,
    base_quantity     NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    selling_rate      NUMERIC(14,2) NOT NULL CHECK (selling_rate >= 0),
    cost_rate         NUMERIC(18,4) NOT NULL DEFAULT 0,
    discount          NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax               NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_amount      NUMERIC(14,2) NOT NULL,
    tax_id            BIGINT,
    tax_name_snapshot VARCHAR(120)  NOT NULL DEFAULT 'GST 0% (Exempt)',
    gst_percentage    NUMERIC(5,2)  NOT NULL DEFAULT 0,
    cgst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    sgst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    igst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    taxable_amount    NUMERIC(14,2) NOT NULL DEFAULT 0,
    cgst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    sgst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    igst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_amount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_sd_sale FOREIGN KEY (sales_id, store_id)
        REFERENCES sales(id, store_id) ON DELETE CASCADE,
    CONSTRAINT fk_sd_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id),
    CONSTRAINT fk_sd_tax FOREIGN KEY (tax_id, store_id)
        REFERENCES tax_master(id, store_id)
);

COMMENT ON COLUMN sales_details.selling_rate IS
    'Manual rate for normal sales; item master rate for POS sales (enforced server-side).';
COMMENT ON COLUMN sales_details.cost_rate IS
    'Weighted average cost at the moment of sale. Frozen for historical COGS/profit.';


-- =====================================================================
-- 15. STOCK TRANSFERS
-- =====================================================================
CREATE TABLE stock_transfers (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT       NOT NULL REFERENCES stores(id),
    transfer_number VARCHAR(50)  NOT NULL,
    from_branch_id  BIGINT       NOT NULL,
    to_branch_id    BIGINT       NOT NULL,
    transfer_date   DATE         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN (
        'DRAFT', 'PENDING', 'APPROVED', 'DISPATCHED', 'RECEIVED', 'REJECTED', 'CANCELLED')),
    notes           VARCHAR(500),
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfers_number UNIQUE (store_id, transfer_number),
    CONSTRAINT uq_transfers_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_transfer_branches CHECK (from_branch_id <> to_branch_id),
    CONSTRAINT fk_transfer_from FOREIGN KEY (from_branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_transfer_to FOREIGN KEY (to_branch_id, store_id)
        REFERENCES branches(id, store_id)
);

COMMENT ON TABLE stock_transfers IS
    'Two-step branch transfer. Stock leaves the source on DISPATCH and enters the destination on RECEIVE.';
COMMENT ON COLUMN stock_transfers.status IS
    'DRAFT exists in the schema/enum but the current service creates PENDING transfers directly.';


CREATE TABLE stock_transfer_details (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT        NOT NULL REFERENCES stores(id),
    transfer_id   BIGINT        NOT NULL,
    item_id       BIGINT        NOT NULL,
    quantity      NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    unit          VARCHAR(20)   NOT NULL,
    base_quantity NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    CONSTRAINT fk_td_transfer FOREIGN KEY (transfer_id, store_id)
        REFERENCES stock_transfers(id, store_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id)
);


-- =====================================================================
-- 16. AUDIT LOG
-- =====================================================================
CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT,
    user_id     BIGINT,
    username    VARCHAR(80),
    action      VARCHAR(60)  NOT NULL,
    entity_type VARCHAR(60)  NOT NULL,
    entity_id   BIGINT,
    details     VARCHAR(1000),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE audit_logs IS
    'Append-only audit trail for sensitive actions (login, store registration, purchases, '
    'sales, cancellations, transfers, adjustments, pricing). No FK on purpose so logs survive '
    'user/store changes. There is no read API; query with SQL.';


-- =====================================================================
-- 17. DOCUMENT NUMBER SEQUENCES
-- =====================================================================
CREATE SEQUENCE purchase_number_seq  START WITH 1001;
CREATE SEQUENCE sales_number_seq     START WITH 1001;
CREATE SEQUENCE transfer_number_seq  START WITH 1001;
CREATE SEQUENCE adjustment_number_seq START WITH 1001;

COMMENT ON SEQUENCE purchase_number_seq IS
    'Global sequence. Application formats PUR-<number>. Numbers are not reused after rollback.';
COMMENT ON SEQUENCE sales_number_seq IS 'Global sequence. Application formats SAL-<number>.';
COMMENT ON SEQUENCE transfer_number_seq IS 'Global sequence. Application formats TRF-<number>.';
COMMENT ON SEQUENCE adjustment_number_seq IS 'Global sequence. Application formats ADJ-<number>.';


-- =====================================================================
-- END OF SCHEMA
-- Next: 03_create_indexes.sql
-- =====================================================================
