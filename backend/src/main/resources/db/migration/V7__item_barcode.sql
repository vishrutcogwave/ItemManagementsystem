-- =====================================================================
-- Item barcodes. Every item receives one unique barcode inside its store.
-- Existing items are backfilled deterministically from their id, so the
-- migration is safe to run on a live database without touching other data.
-- =====================================================================

ALTER TABLE items ADD COLUMN barcode VARCHAR(64);
ALTER TABLE items ADD COLUMN barcode_format VARCHAR(10) NOT NULL DEFAULT 'CODE128';
ALTER TABLE items ADD CONSTRAINT ck_items_barcode_format
    CHECK (barcode_format IN ('CODE128', 'EAN13'));

UPDATE items
SET barcode = 'IMS' || LPAD(id::text, 12, '0')
WHERE barcode IS NULL;

ALTER TABLE items ALTER COLUMN barcode SET NOT NULL;

-- A barcode is unique per store (tenants may independently use the same
-- supplier barcode). The unique index doubles as the lookup index.
CREATE UNIQUE INDEX uq_items_store_barcode ON items (store_id, barcode);
