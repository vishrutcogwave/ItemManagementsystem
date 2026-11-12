-- =====================================================================
-- Unit Master
-- Store-specific unit codes replace the fixed base_unit list on items.
-- Existing item unit codes are kept and seeded into the new master so no
-- historical record changes.
-- =====================================================================

CREATE TABLE unit_master (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT       NOT NULL REFERENCES stores(id),
    unit_code   VARCHAR(20)  NOT NULL,
    unit_name   VARCHAR(80)  NOT NULL,
    description VARCHAR(255),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_unit_master_store_code UNIQUE (store_id, unit_code),
    CONSTRAINT uq_unit_master_store_id UNIQUE (store_id, id)
);
CREATE INDEX ix_unit_master_store_status ON unit_master (store_id, status);

-- Seed the units that were previously fixed in code for every existing store.
INSERT INTO unit_master (store_id, unit_code, unit_name, description)
SELECT s.id, u.unit_code, u.unit_name, u.description
FROM stores s
CROSS JOIN (VALUES
    ('LITER', 'Litre', 'Volume in litres'),
    ('ML', 'Millilitre', 'Volume in millilitres'),
    ('KG', 'Kilogram', 'Weight in kilograms'),
    ('GRAM', 'Gram', 'Weight in grams'),
    ('PIECE', 'Piece', 'Individual countable item'),
    ('DOZEN', 'Dozen', 'Count in dozens of pieces'),
    ('PACKET', 'Packet', 'Sealed packet or pouch'),
    ('BOX', 'Box', 'Carton or box'),
    ('BOTTLE', 'Bottle', 'Sealed bottle')
) AS u(unit_code, unit_name, description);

-- Units are now validated against the store's unit master instead of this list.
ALTER TABLE items DROP CONSTRAINT IF EXISTS items_base_unit_check;
CREATE INDEX ix_items_store_unit ON items (store_id, base_unit);

-- Unit master permissions for existing stores.
INSERT INTO permissions (code, name, description) VALUES
('UNIT_VIEW', 'View units', 'View the store unit master'),
('UNIT_MANAGE', 'Manage units', 'Create and edit units');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'UNIT_VIEW'
WHERE r.store_id IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'UNIT_MANAGE'
WHERE r.store_id IS NOT NULL AND r.code IN ('STORE_OWNER', 'STORE_ADMIN')
ON CONFLICT DO NOTHING;
