-- =====================================================================
-- K-04: Non-destructive V7 compatibility shim for a backend-jar rollback.
--
-- PURPOSE
--   V7 added items.barcode as NOT NULL without a database default. A jar
--   built before V7 does not know the column and INSERTs items without a
--   barcode, which PostgreSQL rejects. This script adds a temporary database
--   default so the previous jar can create items again.
--
-- SAFETY
--   * Does NOT drop or rename any column, index, table or row.
--   * Does NOT modify existing barcode values or any other customer data.
--   * Does NOT touch Flyway history.
--   * Can run while the application is stopped; it is short and takes only
--     a brief ACCESS EXCLUSIVE lock to set the default.
--   * Idempotent: safe to run more than once.
--
-- WHAT IT CHANGES
--   New rows written by an older jar receive a unique 'RB' + 12-digit
--   barcode (Code 128 compatible). Existing items keep their barcodes.
--
-- FOLLOW-UP (forward fix, after the new jar is running again)
--   Run 08_remove_v7_rollback_compatibility.sql during a maintenance window.
--   Dropping the default never changes stored rows.
--
-- REQUIRE RUNBOOK: PROJECT_DOCUMENTATION/ROLLBACK_GUIDE.md section 1.1
-- =====================================================================

BEGIN;

-- A dedicated sequence guarantees a globally unique value, which is also
-- unique within every store, so uq_items_store_barcode cannot be violated.
CREATE SEQUENCE IF NOT EXISTS items_barcode_rollback_seq
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;

ALTER TABLE items
    ALTER COLUMN barcode
    SET DEFAULT ('RB' || LPAD(nextval('items_barcode_rollback_seq')::text, 12, '0'));

COMMIT;

-- ---------------------------------------------------------------------
-- Verification (read-only)
-- ---------------------------------------------------------------------
-- 1. The default is present and no existing barcode was rewritten:
SELECT column_name, column_default, is_nullable
FROM information_schema.columns
WHERE table_name = 'items' AND column_name IN ('barcode', 'barcode_format');

-- 2. Every existing item still has a barcode:
SELECT COUNT(*) AS items_without_barcode FROM items WHERE barcode IS NULL;
