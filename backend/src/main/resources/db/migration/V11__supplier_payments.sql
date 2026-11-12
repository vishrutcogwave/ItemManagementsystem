-- =====================================================================
-- Supplier payments.
-- A payment records money actually handed to a supplier and how it is
-- allocated across one or more completed purchase bills. Bill balances
-- on purchases keep being the authoritative outstanding amounts; every
-- allocation updates them in the same transaction.
-- Additive only: existing purchases, tax snapshots and history are kept.
-- =====================================================================

CREATE SEQUENCE supplier_payment_number_seq START WITH 1001;

CREATE TABLE supplier_payments (
    id               BIGSERIAL PRIMARY KEY,
    store_id         BIGINT        NOT NULL REFERENCES stores(id),
    supplier_id      BIGINT        NOT NULL,
    payment_number   VARCHAR(50)   NOT NULL,
    payment_date     DATE          NOT NULL,
    payment_method   VARCHAR(20)   NOT NULL CHECK (payment_method IN ('CASH', 'BANK', 'UPI', 'CARD')),
    amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    reference_number VARCHAR(80),
    remarks          VARCHAR(500),
    client_reference VARCHAR(80),
    created_by       BIGINT REFERENCES users(id),
    created_by_name  VARCHAR(150),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_supplier_payments_number UNIQUE (store_id, payment_number),
    CONSTRAINT uq_supplier_payments_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_supplier_payments_supplier FOREIGN KEY (supplier_id, store_id)
        REFERENCES suppliers(id, store_id)
);

CREATE UNIQUE INDEX uq_supplier_payments_client_reference
    ON supplier_payments (store_id, client_reference)
    WHERE client_reference IS NOT NULL;
CREATE INDEX ix_supplier_payments_supplier_date
    ON supplier_payments (store_id, supplier_id, payment_date);

CREATE TABLE supplier_payment_allocations (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT        NOT NULL REFERENCES stores(id),
    payment_id  BIGINT        NOT NULL,
    purchase_id BIGINT        NOT NULL,
    amount      NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_spa_payment FOREIGN KEY (payment_id, store_id)
        REFERENCES supplier_payments(id, store_id) ON DELETE CASCADE,
    CONSTRAINT fk_spa_purchase FOREIGN KEY (purchase_id, store_id)
        REFERENCES purchases(id, store_id)
);

CREATE INDEX ix_spa_payment ON supplier_payment_allocations (payment_id);
CREATE INDEX ix_spa_purchase ON supplier_payment_allocations (purchase_id);
CREATE INDEX ix_purchases_supplier_status ON purchases (store_id, supplier_id, status);
