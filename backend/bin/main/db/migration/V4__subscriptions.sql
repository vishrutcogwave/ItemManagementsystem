-- =====================================================================
-- Subscription management and branch limits
-- Per-store subscription window and branch allowance, change history and
-- the platform contact shown on the expired-subscription screen.
-- =====================================================================

ALTER TABLE stores ADD COLUMN subscription_start_date DATE;
ALTER TABLE stores ADD COLUMN subscription_end_date DATE;
ALTER TABLE stores ADD COLUMN max_branches INTEGER NOT NULL DEFAULT 5;

-- Existing stores keep one year of access from the day they were created and
-- a branch allowance that covers the branches they already have.
UPDATE stores
SET subscription_start_date = created_at::date,
    subscription_end_date = (created_at::date + INTERVAL '1 year')::date
WHERE subscription_start_date IS NULL;

UPDATE stores
SET max_branches = GREATEST(max_branches, (SELECT count(*) FROM branches b WHERE b.store_id = stores.id))
WHERE max_branches < (SELECT count(*) FROM branches b WHERE b.store_id = stores.id);

ALTER TABLE stores ALTER COLUMN subscription_start_date SET NOT NULL;
ALTER TABLE stores ALTER COLUMN subscription_end_date SET NOT NULL;

ALTER TABLE stores
    ADD CONSTRAINT ck_stores_subscription_dates
    CHECK (subscription_end_date >= subscription_start_date);

ALTER TABLE stores
    ADD CONSTRAINT ck_stores_max_branches
    CHECK (max_branches >= 1);

CREATE TABLE subscription_change_history (
    id                   BIGSERIAL PRIMARY KEY,
    store_id             BIGINT       NOT NULL REFERENCES stores(id),
    changed_by           BIGINT       REFERENCES users(id),
    changed_by_username  VARCHAR(80),
    changed_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    previous_start_date  DATE,
    new_start_date       DATE,
    previous_end_date    DATE,
    new_end_date         DATE,
    previous_max_branches INTEGER,
    new_max_branches     INTEGER,
    note                 VARCHAR(255)
);

CREATE INDEX ix_subscription_history_store
    ON subscription_change_history (store_id, changed_at DESC);

-- Single platform-wide contact row, editable from the platform console.
CREATE TABLE platform_settings (
    id             INTEGER      PRIMARY KEY,
    contact_email  VARCHAR(150),
    contact_phone  VARCHAR(20),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_platform_settings_singleton CHECK (id = 1)
);

INSERT INTO platform_settings (id) VALUES (1);

-- Seed from the existing platform account when one is present. On a fresh
-- database the account is created by DataSeeder after migrations run; the
-- service then falls back to that account until the platform admin saves a
-- contact in the console.
UPDATE platform_settings ps
SET contact_email = seed.email,
    contact_phone = seed.mobile
FROM (
    SELECT u.email, u.mobile
    FROM users u
    JOIN user_roles ur ON ur.user_id = u.id
    JOIN roles r ON r.id = ur.role_id
    WHERE u.store_id IS NULL
      AND r.store_id IS NULL
      AND r.code = 'PLATFORM_ADMIN'
    ORDER BY u.id
    LIMIT 1
) AS seed
WHERE ps.id = 1;
