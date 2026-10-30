-- =====================================================================
-- 04_insert_initial_data.sql
-- Item Management System - required reference data only
-- =====================================================================
--
-- PURPOSE
--   Insert the data the application needs before anyone can sign in:
--     1. the 28 permission codes,
--     2. the platform-level role PLATFORM_ADMIN and its grants.
--
--   It deliberately contains NO fake business data (no demo store, branches,
--   items, purchases or sales). Those are created through the application or,
--   for a disposable demo, by 06_sample_business_flow.sql.
--
-- WHEN TO RUN
--   - After 02/03 on a manually created schema, OR
--   - On a Flyway-managed database as a no-op check: every insert uses
--     ON CONFLICT DO NOTHING, so the 28 permissions and platform role that V1
--     already created are left untouched.
--
-- IDEMPOTENCY
--   Safe to re-run. It never updates or deletes existing rows.
--
-- PLATFORM ADMINISTRATOR USER
--   The application's DataSeeder creates a platform user on first start:
--       store code: PLATFORM
--       username:   platform
--       password:   Admin@123
--   That default is for local development only. In production, change the
--   password immediately (or create your own admin and deactivate the default).
--   An optional SQL alternative using pgcrypto is at the bottom of this file.
--
-- STORE-LEVEL DATA
--   Roles, users, the MAIN branch and the five GST slabs are provisioned
--   transactionally by StoreProvisioningService when a store is registered.
--   Do not insert them by hand for a real store.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. Permission catalogue (28 codes)
--    V1 created 26; V2 added TAX_VIEW and TAX_MANAGE.
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, description) VALUES
    ('DASHBOARD_VIEW',   'View dashboard',        'Access the dashboard'),
    ('PLATFORM_MANAGE',  'Manage platform',       'Manage stores and platform settings'),
    ('STORE_VIEW',       'View store',            'View store details'),
    ('STORE_MANAGE',     'Manage store',          'Edit store details and status'),
    ('BRANCH_VIEW',      'View branches',         'View branches'),
    ('BRANCH_MANAGE',    'Manage branches',       'Create and edit branches'),
    ('USER_VIEW',        'View users',            'View users'),
    ('USER_MANAGE',      'Manage users',          'Create and edit users and branch assignments'),
    ('ROLE_VIEW',        'View roles',            'View roles and permissions'),
    ('ROLE_MANAGE',      'Manage roles',          'Create roles and configure permissions'),
    ('CATEGORY_VIEW',    'View categories',       'View categories'),
    ('CATEGORY_MANAGE',  'Manage categories',     'Create, edit and delete categories'),
    ('ITEM_VIEW',        'View items',            'View items'),
    ('ITEM_MANAGE',      'Manage items',          'Create and edit items'),
    ('SUPPLIER_VIEW',    'View suppliers',        'View suppliers'),
    ('SUPPLIER_MANAGE',  'Manage suppliers',      'Create and edit suppliers'),
    ('PURCHASE_VIEW',    'View purchases',        'View purchase history'),
    ('PURCHASE_MANAGE',  'Manage purchases',      'Create, edit and cancel purchases'),
    ('INVENTORY_VIEW',   'View inventory',        'View stock and transactions'),
    ('INVENTORY_ADJUST', 'Adjust inventory',      'Record stock adjustments and edit branch pricing'),
    ('TRANSFER_VIEW',    'View stock transfers',  'View stock transfers'),
    ('TRANSFER_MANAGE',  'Manage stock transfers','Create and cancel transfers'),
    ('TRANSFER_APPROVE', 'Approve stock transfers','Approve, dispatch, reject and receive transfers'),
    ('SALES_VIEW',       'View sales',            'View sales history'),
    ('SALES_MANAGE',     'Manage sales',          'Create and cancel sales'),
    ('REPORT_VIEW',      'View reports',          'Access reports and exports'),
    ('TAX_VIEW',         'View taxes',            'View the store tax master'),
    ('TAX_MANAGE',       'Manage taxes',          'Create and edit tax slabs')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------
-- 2. Platform role (store_id IS NULL)
--    It is the only role with PLATFORM_MANAGE. Platform users have no store
--    and can never read store business data.
-- ---------------------------------------------------------------------
INSERT INTO roles (store_id, code, name, description, is_system)
SELECT NULL, 'PLATFORM_ADMIN', 'Platform Admin',
       'Manages registered stores on the platform', true
WHERE NOT EXISTS (
    SELECT 1 FROM roles WHERE store_id IS NULL AND code = 'PLATFORM_ADMIN'
);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p
  ON p.code IN ('PLATFORM_MANAGE', 'STORE_VIEW', 'STORE_MANAGE')
WHERE r.store_id IS NULL
  AND r.code = 'PLATFORM_ADMIN'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Standard GST slabs for stores that already exist
--    Normally StoreProvisioningService does this at registration. This block
--    is a safe repair/backfill: it inserts only missing slabs.
-- ---------------------------------------------------------------------
INSERT INTO tax_master (store_id, tax_name, description, gst_percentage,
                        cgst_percentage, sgst_percentage, igst_percentage,
                        tax_type, status)
SELECT s.id, slab.tax_name, slab.description, slab.gst,
       slab.gst / 2, slab.gst / 2, slab.gst, slab.tax_type, 'ACTIVE'
FROM stores s
CROSS JOIN (VALUES
    ('GST 0% (Exempt)', 'Nil rated or exempt supplies', 0.00::numeric, 'EXEMPT'),
    ('GST 5%',          'Standard 5% GST slab',        5.00::numeric, 'GST'),
    ('GST 12%',         'Standard 12% GST slab',      12.00::numeric, 'GST'),
    ('GST 18%',         'Standard 18% GST slab',      18.00::numeric, 'GST'),
    ('GST 28%',         'Standard 28% GST slab',      28.00::numeric, 'GST')
) AS slab(tax_name, description, gst, tax_type)
ON CONFLICT (store_id, tax_name) DO NOTHING;

-- ---------------------------------------------------------------------
-- 4. Standard store roles for stores that already exist
--    Normally created by StoreProvisioningService. These inserts are a repair
--    path only; they do not attach permissions (use the application so the
--    permission matrix stays consistent).
-- ---------------------------------------------------------------------
INSERT INTO roles (store_id, code, name, description, is_system)
SELECT s.id, r.code, r.name, r.description, true
FROM stores s
CROSS JOIN (VALUES
    ('STORE_OWNER',    'Store Owner',    'Owns the store and can manage every branch and setting'),
    ('STORE_ADMIN',    'Store Admin',    'Handles day-to-day store operations across branches'),
    ('BRANCH_MANAGER', 'Branch Manager', 'Manages inventory, purchases and sales for assigned branches'),
    ('BRANCH_STAFF',   'Branch Staff',   'Bills sales and views inventory for assigned branches')
) AS r(code, name, description)
WHERE NOT EXISTS (
    SELECT 1 FROM roles existing
    WHERE existing.store_id = s.id AND existing.code = r.code
);

COMMIT;

-- ---------------------------------------------------------------------
-- 5. Verification
-- ---------------------------------------------------------------------
SELECT count(*) AS permission_count FROM permissions;              -- expect 28

SELECT r.code, count(rp.permission_id) AS granted_permissions
FROM roles r
LEFT JOIN role_permissions rp ON rp.role_id = r.id
WHERE r.store_id IS NULL
GROUP BY r.code;
-- expect PLATFORM_ADMIN with 3 grants

SELECT count(*) AS missing_expected_permissions
FROM (VALUES
    ('DASHBOARD_VIEW'),('PLATFORM_MANAGE'),('STORE_VIEW'),('STORE_MANAGE'),
    ('BRANCH_VIEW'),('BRANCH_MANAGE'),('USER_VIEW'),('USER_MANAGE'),
    ('ROLE_VIEW'),('ROLE_MANAGE'),('CATEGORY_VIEW'),('CATEGORY_MANAGE'),
    ('ITEM_VIEW'),('ITEM_MANAGE'),('SUPPLIER_VIEW'),('SUPPLIER_MANAGE'),
    ('PURCHASE_VIEW'),('PURCHASE_MANAGE'),('INVENTORY_VIEW'),('INVENTORY_ADJUST'),
    ('TRANSFER_VIEW'),('TRANSFER_MANAGE'),('TRANSFER_APPROVE'),('SALES_VIEW'),
    ('SALES_MANAGE'),('REPORT_VIEW'),('TAX_VIEW'),('TAX_MANAGE')
) AS expected(code)
WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.code = expected.code);
-- expect 0


-- =====================================================================
-- OPTIONAL: create the platform administrator with SQL
-- =====================================================================
-- Prefer the application seeder or the platform UI. If you need an admin
-- without starting the application:
--   1. The database owner installs pgcrypto (trusted extension):
--        CREATE EXTENSION IF NOT EXISTS pgcrypto;
--   2. Replace ReplaceMe@2026 with a strong password and run the block.
--   3. Sign in with store code PLATFORM and change the password immediately.
--   4. If the seeder already created "platform", this block does nothing.
--
-- WARNING: creating users outside the application bypasses audit logging.
--
-- BEGIN;
-- INSERT INTO users (store_id, username, email, password_hash, full_name, mobile, status)
-- SELECT NULL, 'platform', 'platform@ims.local',
--        crypt('ReplaceMe@2026', gen_salt('bf', 10)),
--        'Platform Administrator', '9000000000', 'ACTIVE'
-- WHERE NOT EXISTS (
--     SELECT 1 FROM users WHERE store_id IS NULL AND username = 'platform'
-- );
--
-- INSERT INTO user_roles (user_id, role_id)
-- SELECT u.id, r.id
-- FROM users u
-- JOIN roles r ON r.store_id IS NULL AND r.code = 'PLATFORM_ADMIN'
-- WHERE u.store_id IS NULL AND u.username = 'platform'
-- ON CONFLICT DO NOTHING;
-- COMMIT;
