-- =====================================================================
-- 01_create_database.sql
-- Item Management System - database and application role creation
-- =====================================================================
--
-- PURPOSE
--   Create the single shared database used by every store and branch and a
--   dedicated, least-privilege login for the Spring Boot application.
--
-- TARGET
--   PostgreSQL 14 or newer. The embedded development runtime is PostgreSQL
--   14.10; a standalone production server should use 14.x or 16.x.
--
-- WHEN TO RUN
--   Once, manually, as a PostgreSQL superuser, before the application starts
--   for the first time against a standalone server. This file is NEVER run by
--   the application.
--
-- WHAT THIS SCRIPT DOES
--   1. Creates role ims_app (LOGIN, NOSUPERUSER, NOCREATEDB, NOCREATEROLE).
--   2. Creates database item_management_db owned by ims_app.
--   3. Makes the public schema usable only by ims_app and the superuser.
--   4. Sets the database timezone to UTC.
--
-- WHAT THIS SCRIPT DOES NOT DO
--   - It does not create tables, indexes or rows. Use 02, 03 and 04, or let
--     Flyway create them (recommended; see docs/06-postgresql-setup.md).
--   - It does not install PostgreSQL. See docs/06-postgresql-setup.md.
--   - It does not create a platform administrator user. The application's
--     DataSeeder creates one on first start, or use the optional block at the
--     bottom of 04_insert_initial_data.sql.
--
-- SAFETY
--   - Replace CHANGE_ME_STRONG_PASSWORD before running. A strong password is
--     16+ random characters. Never commit the real value to source control.
--   - Do not use the postgres superuser for the application connection.
--   - Do not expose port 5432 to the public internet. Use a private network or
--     VPN, or keep the server bound to localhost.
--   - CREATE DATABASE cannot run inside a transaction block. Run the commands
--     one at a time (psql does this by default; do not wrap this file in
--     BEGIN/COMMIT).
--   - This script is intentionally not idempotent for CREATE DATABASE. If the
--     role or database already exists, skip the corresponding statement.
--
-- CONNECT AS SUPERUSER (example)
--   psql -h localhost -p 5432 -U postgres -d postgres
--   \i 01_create_database.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Application login (cluster-wide object)
-- ---------------------------------------------------------------------
-- Replace the placeholder with a long random secret, or create the role
-- without a password and then run:  \password ims_app
CREATE ROLE ims_app
    LOGIN
    PASSWORD 'CHANGE_ME_STRONG_PASSWORD'
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS;

-- Never grant superuser to the application role.
-- The application needs CONNECT and ownership of its own schema objects only.

-- ---------------------------------------------------------------------
-- 2. The one shared database for all stores and branches
-- ---------------------------------------------------------------------
-- There must be exactly one database for the whole application. Do not create
-- item_management_db_store1, item_management_db_branch2, etc.
CREATE DATABASE item_management_db
    OWNER ims_app
    ENCODING 'UTF8'
    TEMPLATE template0;

COMMENT ON DATABASE item_management_db IS
    'Single shared database for the Item Management System. Tenants are separated by store_id.';

-- ---------------------------------------------------------------------
-- 3. Schema permissions
-- ---------------------------------------------------------------------
\connect item_management_db

-- The application owns the schema so Flyway can create and alter objects.
ALTER SCHEMA public OWNER TO ims_app;

-- PostgreSQL 14 grants CREATE on public to PUBLIC by default. Remove it so no
-- other login can create objects in the application schema.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO ims_app;

-- Identity/sequence/table privileges are inherited through ownership of the
-- schema and of the objects Flyway creates. No global grants are required.

-- ---------------------------------------------------------------------
-- 4. Session defaults
-- ---------------------------------------------------------------------
ALTER DATABASE item_management_db SET timezone TO 'UTC';
ALTER DATABASE item_management_db SET statement_timeout TO '60s';
ALTER DATABASE item_management_db SET idle_in_transaction_session_timeout TO '120s';

-- ---------------------------------------------------------------------
-- 5. Verification (read-only)
-- ---------------------------------------------------------------------
SELECT current_setting('server_version') AS server_version;

SELECT datname,
       pg_get_userbyid(datdba) AS owner,
       pg_encoding_to_char(encoding) AS encoding
FROM pg_database
WHERE datname = 'item_management_db';

SELECT rolname, rolsuper, rolcanlogin, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'ims_app';

SELECT nspname, pg_get_userbyid(nspowner) AS owner
FROM pg_namespace
WHERE nspname = 'public';

-- ---------------------------------------------------------------------
-- OPTIONAL: pgcrypto for the alternative platform-admin insert in script 04.
-- pgcrypto is a trusted extension on PostgreSQL 13+, so the database owner can
-- install it. The application itself does NOT require this extension; BCrypt
-- hashing is performed in Java.
-- ---------------------------------------------------------------------
-- CREATE EXTENSION IF NOT EXISTS pgcrypto;
