ALTER TABLE sales
    ADD COLUMN payments jsonb CHECK (jsonb_typeof(payments) = 'array'),
    DROP CONSTRAINT sales_payment_method_check,
    ADD CONSTRAINT sales_payment_method_check CHECK (payment_method IN ('CASH', 'UPI', 'CARD', 'BANK', 'CREDIT'));

ALTER TABLE sale_modifications
    ADD COLUMN payments_before jsonb CHECK (jsonb_typeof(payments_before) = 'array'),
    ADD COLUMN payments_after jsonb CHECK (jsonb_typeof(payments_after) = 'array');
