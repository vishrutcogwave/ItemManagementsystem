-- =====================================================================
-- Tax Master and GST management
-- Store-specific tax slabs, item tax linkage, place-of-supply state and
-- immutable tax snapshots on purchase/sales lines.
-- =====================================================================

ALTER TABLE stores ADD COLUMN tax_inclusive BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE branches ADD COLUMN state VARCHAR(80);
ALTER TABLE suppliers ADD COLUMN state VARCHAR(80);
ALTER TABLE sales ADD COLUMN customer_state VARCHAR(80);

CREATE TABLE tax_master (
    id                BIGSERIAL PRIMARY KEY,
    store_id          BIGINT       NOT NULL REFERENCES stores(id),
    tax_name          VARCHAR(120) NOT NULL,
    description       VARCHAR(255),
    gst_percentage    NUMERIC(5,2) NOT NULL CHECK (gst_percentage >= 0 AND gst_percentage <= 100),
    cgst_percentage   NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (cgst_percentage >= 0 AND cgst_percentage <= 100),
    sgst_percentage   NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (sgst_percentage >= 0 AND sgst_percentage <= 100),
    igst_percentage   NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (igst_percentage >= 0 AND igst_percentage <= 100),
    tax_type          VARCHAR(20)  NOT NULL DEFAULT 'GST' CHECK (tax_type IN ('GST', 'EXEMPT')),
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tax_master_store_name UNIQUE (store_id, tax_name),
    CONSTRAINT uq_tax_master_store_id UNIQUE (store_id, id)
);
CREATE INDEX ix_tax_master_store_status ON tax_master (store_id, status);

-- Standard GST slabs for every existing store.
INSERT INTO tax_master (store_id, tax_name, description, gst_percentage,
                        cgst_percentage, sgst_percentage, igst_percentage, tax_type, status)
SELECT s.id, slab.tax_name, slab.description, slab.gst,
       slab.gst / 2, slab.gst / 2, slab.gst, slab.tax_type, 'ACTIVE'
FROM stores s
CROSS JOIN (VALUES
    ('GST 0% (Exempt)', 'Nil rated or exempt supplies', 0.00::numeric, 'EXEMPT'),
    ('GST 5%', 'Standard 5% GST slab', 5.00::numeric, 'GST'),
    ('GST 12%', 'Standard 12% GST slab', 12.00::numeric, 'GST'),
    ('GST 18%', 'Standard 18% GST slab', 18.00::numeric, 'GST'),
    ('GST 28%', 'Standard 28% GST slab', 28.00::numeric, 'GST')
) AS slab(tax_name, description, gst, tax_type);

ALTER TABLE items ADD COLUMN tax_id BIGINT;

UPDATE items i
SET tax_id = t.id
FROM tax_master t
WHERE t.store_id = i.store_id AND t.tax_type = 'EXEMPT';

ALTER TABLE items ALTER COLUMN tax_id SET NOT NULL;
ALTER TABLE items ADD CONSTRAINT fk_items_tax
    FOREIGN KEY (tax_id, store_id) REFERENCES tax_master(id, store_id);
CREATE INDEX ix_items_tax ON items (store_id, tax_id);

-- Immutable tax snapshots on transaction lines.
ALTER TABLE purchase_details
    ADD COLUMN tax_id BIGINT,
    ADD COLUMN tax_name_snapshot VARCHAR(120) NOT NULL DEFAULT 'GST 0% (Exempt)',
    ADD COLUMN gst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN cgst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN igst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN taxable_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN cgst_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN igst_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN tax_amount NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE purchase_details ADD CONSTRAINT fk_pd_tax
    FOREIGN KEY (tax_id, store_id) REFERENCES tax_master(id, store_id);

ALTER TABLE sales_details
    ADD COLUMN tax_id BIGINT,
    ADD COLUMN tax_name_snapshot VARCHAR(120) NOT NULL DEFAULT 'GST 0% (Exempt)',
    ADD COLUMN gst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN cgst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN igst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN taxable_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN cgst_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN igst_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN tax_amount NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE sales_details ADD CONSTRAINT fk_sd_tax
    FOREIGN KEY (tax_id, store_id) REFERENCES tax_master(id, store_id);

-- Header GST summary, so printed bills and reports never recompute from master data.
ALTER TABLE purchases
    ADD COLUMN taxable_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN cgst_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN igst_amount NUMERIC(14,2) NOT NULL DEFAULT 0;

ALTER TABLE sales
    ADD COLUMN taxable_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN cgst_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN igst_amount NUMERIC(14,2) NOT NULL DEFAULT 0;

-- Backfill legacy lines: the old single tax column becomes a taxable + tax split.
UPDATE purchase_details
SET taxable_amount = GREATEST(total_amount - tax, 0),
    tax_amount = tax,
    cgst_amount = ROUND(tax / 2, 2),
    sgst_amount = tax - ROUND(tax / 2, 2)
WHERE tax > 0;
UPDATE purchase_details SET tax_name_snapshot = 'Legacy tax' WHERE tax > 0;

UPDATE sales_details
SET taxable_amount = GREATEST(total_amount - tax, 0),
    tax_amount = tax,
    cgst_amount = ROUND(tax / 2, 2),
    sgst_amount = tax - ROUND(tax / 2, 2)
WHERE tax > 0;
UPDATE sales_details SET tax_name_snapshot = 'Legacy tax' WHERE tax > 0;

UPDATE purchases
SET taxable_amount = GREATEST(subtotal - discount, 0),
    cgst_amount = ROUND(tax / 2, 2),
    sgst_amount = tax - ROUND(tax / 2, 2)
WHERE tax > 0;

UPDATE sales
SET taxable_amount = GREATEST(subtotal - discount, 0),
    cgst_amount = ROUND(tax / 2, 2),
    sgst_amount = tax - ROUND(tax / 2, 2)
WHERE tax > 0;

-- Tax master permissions for existing stores.
INSERT INTO permissions (code, name, description) VALUES
('TAX_VIEW', 'View taxes', 'View the store tax master'),
('TAX_MANAGE', 'Manage taxes', 'Create and edit tax slabs');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'TAX_VIEW'
WHERE r.store_id IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'TAX_MANAGE'
WHERE r.store_id IS NOT NULL AND r.code IN ('STORE_OWNER', 'STORE_ADMIN')
ON CONFLICT DO NOTHING;
