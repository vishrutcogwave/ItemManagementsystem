-- =====================================================================
-- Item Management System - single shared PostgreSQL database schema
-- Multi-tenant by store_id, branch isolation by branch_id.
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
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE branches (
    id             BIGSERIAL PRIMARY KEY,
    store_id       BIGINT       NOT NULL REFERENCES stores(id),
    branch_code    VARCHAR(30)  NOT NULL,
    branch_name    VARCHAR(150) NOT NULL,
    address        TEXT         NOT NULL,
    contact_number VARCHAR(20)  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_branches_code UNIQUE (store_id, branch_code),
    CONSTRAINT uq_branches_store_id UNIQUE (store_id, id)
);

CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT REFERENCES stores(id) ON DELETE CASCADE,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    is_system   BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_roles_store_code ON roles (COALESCE(store_id, 0), code);

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
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_store_username UNIQUE (store_id, username)
);
CREATE INDEX ix_users_email ON users (email);
CREATE INDEX ix_users_store ON users (store_id);

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

CREATE TABLE categories (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT       NOT NULL REFERENCES stores(id),
    category_name VARCHAR(120) NOT NULL,
    description   VARCHAR(255),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_categories_name UNIQUE (store_id, category_name),
    CONSTRAINT uq_categories_store_id UNIQUE (store_id, id)
);

CREATE TABLE items (
    id                     BIGSERIAL PRIMARY KEY,
    store_id               BIGINT        NOT NULL REFERENCES stores(id),
    category_id            BIGINT        NOT NULL,
    item_code              VARCHAR(50)   NOT NULL,
    item_name              VARCHAR(150)  NOT NULL,
    base_unit              VARCHAR(20)   NOT NULL
        CHECK (base_unit IN ('LITER', 'ML', 'KG', 'GRAM', 'PIECE', 'PACKET', 'BOX', 'BOTTLE', 'DOZEN')),
    default_purchase_price NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (default_purchase_price >= 0),
    default_selling_price  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (default_selling_price >= 0),
    min_stock_level        NUMERIC(18,3) NOT NULL DEFAULT 5 CHECK (min_stock_level >= 0),
    status                 VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_items_code UNIQUE (store_id, item_code),
    CONSTRAINT uq_items_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_items_category FOREIGN KEY (category_id, store_id) REFERENCES categories(id, store_id)
);
CREATE INDEX ix_items_store_category ON items (store_id, category_id);
CREATE INDEX ix_items_name ON items (store_id, item_name);

CREATE TABLE suppliers (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT       NOT NULL REFERENCES stores(id),
    supplier_name VARCHAR(150) NOT NULL,
    mobile        VARCHAR(20)  NOT NULL,
    address       TEXT         NOT NULL,
    gst_number    VARCHAR(30),
    email         VARCHAR(150),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_suppliers_store_id UNIQUE (store_id, id)
);

CREATE TABLE customers (
    id         BIGSERIAL PRIMARY KEY,
    store_id   BIGINT       NOT NULL REFERENCES stores(id),
    name       VARCHAR(150) NOT NULL,
    mobile     VARCHAR(20),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_store_id UNIQUE (store_id, id)
);

CREATE TABLE branch_inventory (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT        NOT NULL REFERENCES stores(id),
    branch_id     BIGINT        NOT NULL,
    item_id       BIGINT        NOT NULL,
    current_stock NUMERIC(18,3) NOT NULL DEFAULT 0 CHECK (current_stock >= 0),
    minimum_stock NUMERIC(18,3) NOT NULL DEFAULT 5 CHECK (minimum_stock >= 0),
    purchase_price NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (purchase_price >= 0),
    selling_price  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (selling_price >= 0),
    avg_cost       NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (avg_cost >= 0),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_inventory_branch_item UNIQUE (store_id, branch_id, item_id),
    CONSTRAINT fk_inventory_branch FOREIGN KEY (branch_id, store_id) REFERENCES branches(id, store_id),
    CONSTRAINT fk_inventory_item FOREIGN KEY (item_id, store_id) REFERENCES items(id, store_id)
);
CREATE INDEX ix_inventory_item ON branch_inventory (store_id, item_id);
CREATE INDEX ix_inventory_branch ON branch_inventory (store_id, branch_id);

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
    CONSTRAINT fk_txn_branch FOREIGN KEY (branch_id, store_id) REFERENCES branches(id, store_id),
    CONSTRAINT fk_txn_item FOREIGN KEY (item_id, store_id) REFERENCES items(id, store_id)
);
CREATE INDEX ix_txn_store_date ON inventory_transactions (store_id, transaction_date);
CREATE INDEX ix_txn_branch_item ON inventory_transactions (store_id, branch_id, item_id);
CREATE INDEX ix_txn_reference ON inventory_transactions (reference_type, reference_id);

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
    CONSTRAINT fk_adjustment_branch FOREIGN KEY (branch_id, store_id) REFERENCES branches(id, store_id),
    CONSTRAINT fk_adjustment_item FOREIGN KEY (item_id, store_id) REFERENCES items(id, store_id)
);

CREATE TABLE purchases (
    id             BIGSERIAL PRIMARY KEY,
    store_id       BIGINT        NOT NULL REFERENCES stores(id),
    branch_id      BIGINT        NOT NULL,
    supplier_id    BIGINT        NOT NULL,
    purchase_number VARCHAR(50)  NOT NULL,
    invoice_number VARCHAR(80),
    purchase_date  DATE          NOT NULL,
    subtotal       NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax            NUMERIC(14,2) NOT NULL DEFAULT 0,
    grand_total    NUMERIC(14,2) NOT NULL DEFAULT 0,
    paid_amount    NUMERIC(14,2) NOT NULL DEFAULT 0,
    balance_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    notes          VARCHAR(500),
    status         VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('DRAFT', 'COMPLETED', 'CANCELLED')),
    created_by     BIGINT REFERENCES users(id),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_purchases_number UNIQUE (store_id, purchase_number),
    CONSTRAINT uq_purchases_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_purchase_branch FOREIGN KEY (branch_id, store_id) REFERENCES branches(id, store_id),
    CONSTRAINT fk_purchase_supplier FOREIGN KEY (supplier_id, store_id) REFERENCES suppliers(id, store_id)
);
CREATE INDEX ix_purchases_store_date ON purchases (store_id, purchase_date);
CREATE INDEX ix_purchases_branch ON purchases (store_id, branch_id);

CREATE TABLE purchase_details (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT        NOT NULL REFERENCES stores(id),
    purchase_id   BIGINT        NOT NULL,
    item_id       BIGINT        NOT NULL,
    quantity      NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    unit          VARCHAR(20)   NOT NULL,
    base_quantity NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    purchase_rate NUMERIC(14,2) NOT NULL CHECK (purchase_rate >= 0),
    discount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax           NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_amount  NUMERIC(14,2) NOT NULL,
    CONSTRAINT fk_pd_purchase FOREIGN KEY (purchase_id, store_id) REFERENCES purchases(id, store_id) ON DELETE CASCADE,
    CONSTRAINT fk_pd_item FOREIGN KEY (item_id, store_id) REFERENCES items(id, store_id)
);
CREATE INDEX ix_purchase_details_purchase ON purchase_details (purchase_id);

CREATE TABLE sales (
    id             BIGSERIAL PRIMARY KEY,
    store_id       BIGINT        NOT NULL REFERENCES stores(id),
    branch_id      BIGINT        NOT NULL,
    sales_number   VARCHAR(50)   NOT NULL,
    sales_date     DATE          NOT NULL,
    customer_id    BIGINT,
    customer_name  VARCHAR(150),
    customer_mobile VARCHAR(20),
    subtotal       NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax            NUMERIC(14,2) NOT NULL DEFAULT 0,
    grand_total    NUMERIC(14,2) NOT NULL DEFAULT 0,
    paid_amount    NUMERIC(14,2) NOT NULL DEFAULT 0,
    balance_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    payment_method VARCHAR(20)   NOT NULL DEFAULT 'CASH' CHECK (payment_method IN ('CASH', 'UPI', 'CARD', 'CREDIT')),
    status         VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED', 'CANCELLED')),
    created_by     BIGINT REFERENCES users(id),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_sales_number UNIQUE (store_id, sales_number),
    CONSTRAINT uq_sales_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_sale_branch FOREIGN KEY (branch_id, store_id) REFERENCES branches(id, store_id),
    CONSTRAINT fk_sale_customer FOREIGN KEY (customer_id, store_id) REFERENCES customers(id, store_id)
);
CREATE INDEX ix_sales_store_date ON sales (store_id, sales_date);
CREATE INDEX ix_sales_branch ON sales (store_id, branch_id);

CREATE TABLE sales_details (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT        NOT NULL REFERENCES stores(id),
    sales_id      BIGINT        NOT NULL,
    item_id       BIGINT        NOT NULL,
    quantity      NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    unit          VARCHAR(20)   NOT NULL,
    base_quantity NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    selling_rate  NUMERIC(14,2) NOT NULL CHECK (selling_rate >= 0),
    cost_rate     NUMERIC(18,4) NOT NULL DEFAULT 0,
    discount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax           NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_amount  NUMERIC(14,2) NOT NULL,
    CONSTRAINT fk_sd_sale FOREIGN KEY (sales_id, store_id) REFERENCES sales(id, store_id) ON DELETE CASCADE,
    CONSTRAINT fk_sd_item FOREIGN KEY (item_id, store_id) REFERENCES items(id, store_id)
);
CREATE INDEX ix_sales_details_sale ON sales_details (sales_id);

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
    CONSTRAINT fk_transfer_from FOREIGN KEY (from_branch_id, store_id) REFERENCES branches(id, store_id),
    CONSTRAINT fk_transfer_to FOREIGN KEY (to_branch_id, store_id) REFERENCES branches(id, store_id)
);
CREATE INDEX ix_transfers_store ON stock_transfers (store_id, transfer_date);

CREATE TABLE stock_transfer_details (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT        NOT NULL REFERENCES stores(id),
    transfer_id   BIGINT        NOT NULL,
    item_id       BIGINT        NOT NULL,
    quantity      NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    unit          VARCHAR(20)   NOT NULL,
    base_quantity NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    CONSTRAINT fk_td_transfer FOREIGN KEY (transfer_id, store_id) REFERENCES stock_transfers(id, store_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_item FOREIGN KEY (item_id, store_id) REFERENCES items(id, store_id)
);

CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT,
    user_id     BIGINT,
    username    VARCHAR(80),
    action      VARCHAR(60) NOT NULL,
    entity_type VARCHAR(60) NOT NULL,
    entity_id   BIGINT,
    details     VARCHAR(1000),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_store_date ON audit_logs (store_id, created_at);

CREATE SEQUENCE purchase_number_seq START WITH 1001;
CREATE SEQUENCE sales_number_seq START WITH 1001;
CREATE SEQUENCE transfer_number_seq START WITH 1001;
CREATE SEQUENCE adjustment_number_seq START WITH 1001;

-- ---------------------------------------------------------------------
-- Permissions and the platform-level role
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
('DASHBOARD_VIEW', 'View dashboard', 'Access the dashboard'),
('PLATFORM_MANAGE', 'Manage platform', 'Manage stores and platform settings'),
('STORE_VIEW', 'View store', 'View store details'),
('STORE_MANAGE', 'Manage store', 'Edit store details and status'),
('BRANCH_VIEW', 'View branches', 'View branches'),
('BRANCH_MANAGE', 'Manage branches', 'Create and edit branches'),
('USER_VIEW', 'View users', 'View users'),
('USER_MANAGE', 'Manage users', 'Create and edit users and branch assignments'),
('ROLE_VIEW', 'View roles', 'View roles and permissions'),
('ROLE_MANAGE', 'Manage roles', 'Create roles and configure permissions'),
('CATEGORY_VIEW', 'View categories', 'View categories'),
('CATEGORY_MANAGE', 'Manage categories', 'Create, edit and delete categories'),
('ITEM_VIEW', 'View items', 'View items'),
('ITEM_MANAGE', 'Manage items', 'Create and edit items'),
('SUPPLIER_VIEW', 'View suppliers', 'View suppliers'),
('SUPPLIER_MANAGE', 'Manage suppliers', 'Create and edit suppliers'),
('PURCHASE_VIEW', 'View purchases', 'View purchase history'),
('PURCHASE_MANAGE', 'Manage purchases', 'Create, edit and cancel purchases'),
('INVENTORY_VIEW', 'View inventory', 'View stock and transactions'),
('INVENTORY_ADJUST', 'Adjust inventory', 'Record stock adjustments and edit branch pricing'),
('TRANSFER_VIEW', 'View stock transfers', 'View stock transfers'),
('TRANSFER_MANAGE', 'Manage stock transfers', 'Create and cancel transfers'),
('TRANSFER_APPROVE', 'Approve stock transfers', 'Approve, dispatch, reject and receive transfers'),
('SALES_VIEW', 'View sales', 'View sales history'),
('SALES_MANAGE', 'Manage sales', 'Create and cancel sales'),
('REPORT_VIEW', 'View reports', 'Access reports and exports');

INSERT INTO roles (store_id, code, name, description, is_system)
VALUES (NULL, 'PLATFORM_ADMIN', 'Platform Admin', 'Manages registered stores on the platform', true);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('PLATFORM_MANAGE', 'STORE_VIEW', 'STORE_MANAGE')
WHERE r.store_id IS NULL AND r.code = 'PLATFORM_ADMIN';
