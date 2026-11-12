-- =====================================================================
-- Item import: unit summary counts
-- The Excel import can create missing Unit Master records. Persisting how
-- many units were reused and created keeps the batch detail accurate after
-- the import completes.
-- =====================================================================

ALTER TABLE item_import_batches
    ADD COLUMN new_units    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reused_units INTEGER NOT NULL DEFAULT 0;
