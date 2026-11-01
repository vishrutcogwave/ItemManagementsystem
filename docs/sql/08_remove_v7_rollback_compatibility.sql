-- =====================================================================
-- K-04: Remove the temporary V7 rollback compatibility shim.
--
-- Run this only after the post-V7 backend jar is running again, during a
-- maintenance window. Dropping a column default does not change any stored
-- row, including the 'RB...' barcodes created while the shim was active.
--
-- SAFETY
--   * Does NOT drop or rename any column, index, table or row.
--   * Does NOT modify existing barcode values.
--   * Idempotent: safe to run more than once.
-- =====================================================================

BEGIN;

ALTER TABLE items ALTER COLUMN barcode DROP DEFAULT;

DROP SEQUENCE IF EXISTS items_barcode_rollback_seq;

COMMIT;

-- ---------------------------------------------------------------------
-- Verification (read-only)
-- ---------------------------------------------------------------------
SELECT column_name, column_default, is_nullable
FROM information_schema.columns
WHERE table_name = 'items' AND column_name = 'barcode';

-- Count barcodes created while the shim was active. Review them; keep them,
-- or re-label the items through the normal barcode API. Do NOT delete rows.
SELECT COUNT(*) AS shim_generated_barcodes
FROM items
WHERE barcode LIKE 'RB%' AND length(barcode) = 14;
