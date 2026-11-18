-- Bill modification history and bill cancellation records.
-- The original sale row is never replaced: modifications update the live bill
-- while every applied change is snapshotted here, and cancellation keeps the
-- original bill with an audit trail of who cancelled it and why.

ALTER TABLE sales
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by BIGINT REFERENCES users(id),
    ADD COLUMN cancellation_reason VARCHAR(500),
    ADD COLUMN modification_count INTEGER NOT NULL DEFAULT 0;

CREATE SEQUENCE sale_modification_number_seq;
CREATE SEQUENCE sale_cancellation_number_seq;

CREATE TABLE sale_modifications (
    id                  BIGSERIAL PRIMARY KEY,
    store_id            BIGINT        NOT NULL REFERENCES stores(id),
    sale_id             BIGINT        NOT NULL,
    modification_number VARCHAR(50)   NOT NULL,
    modified_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by         BIGINT REFERENCES users(id),
    reason              VARCHAR(500),
    original_total      NUMERIC(14,2) NOT NULL,
    modified_total      NUMERIC(14,2) NOT NULL,
    difference_amount   NUMERIC(14,2) NOT NULL,
    original_quantity   NUMERIC(18,3) NOT NULL DEFAULT 0,
    modified_quantity   NUMERIC(18,3) NOT NULL DEFAULT 0,
    quantity_increased  NUMERIC(18,3) NOT NULL DEFAULT 0,
    quantity_decreased  NUMERIC(18,3) NOT NULL DEFAULT 0,
    items_added         INTEGER       NOT NULL DEFAULT 0,
    items_removed       INTEGER       NOT NULL DEFAULT 0,
    additional_payable  NUMERIC(14,2) NOT NULL DEFAULT 0,
    refund_due          NUMERIC(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT uq_sale_modification_number UNIQUE (store_id, modification_number),
    CONSTRAINT fk_sale_mod_sale FOREIGN KEY (sale_id, store_id)
        REFERENCES sales(id, store_id) ON DELETE CASCADE
);
CREATE INDEX ix_sale_modifications_sale ON sale_modifications (store_id, sale_id, modified_at DESC);

CREATE TABLE sale_modification_items (
    id                BIGSERIAL PRIMARY KEY,
    store_id          BIGINT        NOT NULL REFERENCES stores(id),
    modification_id   BIGINT        NOT NULL,
    item_id           BIGINT        NOT NULL,
    item_code         VARCHAR(120),
    item_name         VARCHAR(200),
    unit              VARCHAR(20),
    change_type       VARCHAR(20)   NOT NULL CHECK (change_type IN ('ADDED', 'REMOVED', 'UPDATED')),
    original_quantity NUMERIC(18,3) NOT NULL DEFAULT 0,
    modified_quantity NUMERIC(18,3) NOT NULL DEFAULT 0,
    original_rate     NUMERIC(14,2) NOT NULL DEFAULT 0,
    modified_rate     NUMERIC(14,2) NOT NULL DEFAULT 0,
    original_amount   NUMERIC(14,2) NOT NULL DEFAULT 0,
    modified_amount   NUMERIC(14,2) NOT NULL DEFAULT 0,
    difference_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_amount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_sale_mod_item_mod FOREIGN KEY (modification_id)
        REFERENCES sale_modifications(id) ON DELETE CASCADE
);
CREATE INDEX ix_sale_modification_items_mod ON sale_modification_items (modification_id);

CREATE TABLE sale_cancellations (
    id                  BIGSERIAL PRIMARY KEY,
    store_id            BIGINT        NOT NULL REFERENCES stores(id),
    sale_id             BIGINT        NOT NULL,
    cancellation_number VARCHAR(50)   NOT NULL,
    reason              VARCHAR(500)  NOT NULL,
    cancelled_by        BIGINT REFERENCES users(id),
    cancelled_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    sale_total          NUMERIC(14,2) NOT NULL,
    paid_amount         NUMERIC(14,2) NOT NULL DEFAULT 0,
    restored_items      INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT uq_sale_cancellation_number UNIQUE (store_id, cancellation_number),
    CONSTRAINT uq_sale_cancellation_sale UNIQUE (sale_id),
    CONSTRAINT fk_sale_cancel_sale FOREIGN KEY (sale_id, store_id)
        REFERENCES sales(id, store_id) ON DELETE CASCADE
);

INSERT INTO permissions (code, name, description) VALUES
('SALES_MODIFY', 'Modify bills', 'Adjust quantities, rates and items on a completed bill'),
('SALES_CANCEL', 'Cancel bills', 'Cancel a completed bill with a recorded reason')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN ('SALES_MODIFY', 'SALES_CANCEL')
WHERE r.store_id IS NOT NULL AND r.code IN ('STORE_OWNER', 'STORE_ADMIN', 'BRANCH_MANAGER')
ON CONFLICT DO NOTHING;
