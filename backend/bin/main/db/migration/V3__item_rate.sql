-- Single item master rate used for POS billing. Purchase and normal sales
-- entries capture their own negotiated rates in transaction detail tables.
ALTER TABLE items ADD COLUMN rate NUMERIC(12,2);

UPDATE items SET rate = default_selling_price;

ALTER TABLE items ALTER COLUMN rate SET NOT NULL;
ALTER TABLE items ADD CONSTRAINT ck_items_rate_non_negative CHECK (rate >= 0);

ALTER TABLE items DROP COLUMN default_purchase_price;
ALTER TABLE items DROP COLUMN default_selling_price;
