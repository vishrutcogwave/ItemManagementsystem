-- =====================================================================
-- Authentication hardening.
--  * must_change_password forces a user to choose a new password after an
--    administrator reset or after initial provisioning.
--  * token_version lets the server invalidate every previously issued JWT
--    for a user (for example after a password change) without touching
--    customers, stores or any business data.
--  * revoked_tokens persists logout/refresh revocations across restarts.
-- Only additive changes are made; existing rows receive safe defaults.
-- =====================================================================

ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

CREATE TABLE revoked_tokens (
    jti        VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_revoked_tokens_expires ON revoked_tokens (expires_at);
