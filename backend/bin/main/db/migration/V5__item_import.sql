-- =====================================================================
-- Bulk item import through Excel
-- Import batches and rows keep a full, auditable preview/result history.
-- Every store still uses the same shared database and is isolated by
-- store_id; opening stock is recorded per branch as a real inventory
-- transaction so stock balances and history stay consistent.
-- =====================================================================

-- Optional description on item master, exposed by the import template.
ALTER TABLE items ADD COLUMN description VARCHAR(255);

-- Item codes are generated for import rows that leave the code blank.
-- Starting above the demo/manual range keeps generated codes readable.
CREATE SEQUENCE item_code_seq START WITH 1001;
CREATE SEQUENCE item_import_number_seq START WITH 1;

CREATE TABLE item_import_batches (
    id                    BIGSERIAL PRIMARY KEY,
    store_id              BIGINT       NOT NULL REFERENCES stores(id),
    branch_id             BIGINT       NOT NULL,
    import_number         VARCHAR(50)  NOT NULL,
    file_name             VARCHAR(255) NOT NULL,
    file_hash             VARCHAR(64)  NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PREVIEWED'
        CHECK (status IN ('PREVIEWED', 'COMPLETED', 'FAILED')),
    total_rows            INTEGER      NOT NULL DEFAULT 0,
    success_rows          INTEGER      NOT NULL DEFAULT 0,
    failed_rows           INTEGER      NOT NULL DEFAULT 0,
    new_categories        INTEGER      NOT NULL DEFAULT 0,
    new_items             INTEGER      NOT NULL DEFAULT 0,
    existing_items        INTEGER      NOT NULL DEFAULT 0,
    stock_updates         INTEGER      NOT NULL DEFAULT 0,
    duplicate_rows        INTEGER      NOT NULL DEFAULT 0,
    invalid_rows          INTEGER      NOT NULL DEFAULT 0,
    already_imported_rows INTEGER      NOT NULL DEFAULT 0,
    failure_message       VARCHAR(500),
    uploaded_by           BIGINT       REFERENCES users(id),
    uploaded_by_username  VARCHAR(80),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ,
    CONSTRAINT uq_import_batch_number UNIQUE (store_id, import_number),
    CONSTRAINT fk_import_batch_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id)
);

CREATE INDEX ix_import_batches_store ON item_import_batches (store_id, created_at DESC);
CREATE INDEX ix_import_batches_branch ON item_import_batches (store_id, branch_id, created_at DESC);

-- The same file may not be confirmed twice for the same store and branch.
-- Previewed or failed batches do not block a corrected re-upload.
CREATE UNIQUE INDEX uq_import_batch_completed_file
    ON item_import_batches (store_id, branch_id, file_hash)
    WHERE status = 'COMPLETED';

CREATE TABLE item_import_rows (
    id                BIGSERIAL PRIMARY KEY,
    batch_id          BIGINT       NOT NULL REFERENCES item_import_batches(id) ON DELETE CASCADE,
    store_id          BIGINT       NOT NULL REFERENCES stores(id),
    row_number        INTEGER      NOT NULL,
    item_code         VARCHAR(50),
    item_name         VARCHAR(150),
    category_name     VARCHAR(120),
    unit              VARCHAR(20),
    rate              NUMERIC(14,2),
    tax_name          VARCHAR(120),
    opening_stock     NUMERIC(18,3),
    minimum_stock     NUMERIC(18,3),
    description       VARCHAR(255),
    item_id           BIGINT,
    parse_failed      BOOLEAN      NOT NULL DEFAULT false,
    import_action     VARCHAR(40)  NOT NULL,
    validation_status VARCHAR(20)  NOT NULL
        CHECK (validation_status IN ('VALID', 'INVALID')),
    error_message     VARCHAR(500),
    CONSTRAINT uq_import_row_number UNIQUE (batch_id, row_number)
);

CREATE INDEX ix_import_rows_batch ON item_import_rows (batch_id, row_number);

-- Opening stock is a one-time baseline per item and branch. This guards
-- against double imports even if two confirmations overlap. Later stock
-- movements (purchases, sales, transfers, adjustments) are unaffected.
CREATE UNIQUE INDEX uq_txn_single_opening_stock
    ON inventory_transactions (store_id, branch_id, item_id)
    WHERE transaction_type = 'OPENING_STOCK';
