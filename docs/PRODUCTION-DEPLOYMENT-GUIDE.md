# MY ITEM MANAGEMENT SYSTEM – PRODUCTION DEPLOYMENT GUIDE

**Application:** Multi-Store & Multi-Branch Item Management System
**Application version:** 1.0.0
**Document date:** 2026-10-30
**Primary server platform:** Ubuntu Server 24.04 LTS
**Alternative server platform:** Windows Server 2022 / 2025
**Database:** one PostgreSQL database, `item_management_db`
**Status:** deployment manual for the existing, completed application

> **This document is documentation only.** It does not add features, modify application source code, change application configuration, execute SQL scripts, install packages, or run migrations. Every command and SQL statement is for you, the operator, to review and execute deliberately on your own server.

---

## How to use this document

This guide is written so that a completely new server can be prepared from the first command to the first store. Follow the sections in order. Do not skip Section 4 (database) and Section 11 (Platform Owner account), because the application cannot be used without them.

Three URLs are used throughout:

| URL | Purpose |
|---|---|
| `https://admin.mydomain.com` | Platform Owner / Super Admin console |
| `https://app.mydomain.com` | Store owners, managers and staff |
| `https://api.mydomain.com` | Backend API for external clients and diagnostics |

Replace `mydomain.com` with your real registered domain. Replace every placeholder in square brackets, such as `[MY NAME]`, `[MY EMAIL]`, `[MY USERNAME]`, `[MY OWN PASSWORD]`, `[SERVER IP]`, and `[DB PASSWORD]`, before running a command.

**Important architectural fact:** the React application calls the backend with the relative URL `/api` (see `src/lib/api.ts`). It is not configured with an absolute API hostname, and this deployment does not change that. Therefore, `admin.mydomain.com` and `app.mydomain.com` must proxy `/api/` to the Spring Boot service on the same origin. `api.mydomain.com` is provided for direct API access and diagnostics; the browser application does not call it directly. Making the SPA call `api.mydomain.com` directly would require a source-code change, which is outside this document.

**Important build-system fact:** the backend is a **Gradle** project. There is **no `pom.xml`** in this repository and **Maven is not used**. The correct build is `./gradlew clean bootJar`. Do not install Maven and do not run `mvn` commands; they will not work with this project.

---

## Contents

1. Required Downloads
2. Server Installation
3. PostgreSQL Setup
4. Database Creation SQL Scripts
5. Existing Database Migration (Embedded PostgreSQL to Standalone PostgreSQL)
6. Frontend Build
7. Backend Build
8. Server Folder Configuration
9. NGINX Configuration
10. Domain and SSL
11. Super Admin Account Creation
12. Application Startup
13. First Store Creation
14. Backup and Restore
15. Production Verification Checklist

Appendix A – Windows Server alternative
Appendix B – Troubleshooting
Appendix C – Command cheat sheet

---

## 1. Required Downloads

### 1.1 Production topology

```
Internet
   |
   |  443 / 80
   v
NGINX  (one host, three server names)
   |-- admin.mydomain.com  -> /opt/ims/web (React SPA) + /api/ proxy
   |-- app.mydomain.com    -> /opt/ims/web (React SPA) + /api/ proxy
   |-- api.mydomain.com    -> proxy all paths to Spring Boot
   |
   |  127.0.0.1:8080 only
   v
Spring Boot 3.3.5 / Java 17  (item-management-backend-1.0.0.jar)
   |
   |  127.0.0.1:5432 only
   v
PostgreSQL 16  (one database: item_management_db)
```

One server can run all three components. For a larger installation, PostgreSQL can move to a private database host later; that is a configuration change, not a code change.

### 1.2 Minimum server specification

| Resource | Minimum | Recommended |
|---|---|---|
| CPU | 2 vCPU | 4 vCPU |
| RAM | 4 GB | 8 GB |
| Disk | 40 GB SSD | 80 GB SSD or larger |
| Network | Static public IPv4 | Static IPv4 + IPv6 |
| OS | Ubuntu Server 24.04 LTS | Ubuntu Server 24.04 LTS |
| Backup | Local disk | Local disk + off-site copy |

The Java process is started with a modest heap. The database and operating system need headroom. Do not run production on a 1 GB server.

### 1.3 Software to download

Use the official sources below. Versions are the ones compatible with this application.

| Software | Version to use | Why this version | Official download |
|---|---|---|---|
| Ubuntu Server | 24.04 LTS (Noble Numbat) | Primary supported server OS; long-term security updates | https://ubuntu.com/download/server |
| Windows Server (alternative) | 2022 or 2025 | Alternative OS covered in Appendix A | https://www.microsoft.com/en-us/windows-server |
| Eclipse Temurin JDK | 17 (LTS) | The backend's Gradle toolchain is Java 17 (`build.gradle`) | https://adoptium.net/temurin/releases/?version=17 |
| PostgreSQL | 16.x (14.x or newer supported) | Production database; the embedded development server is 14.10. Use 16 for a new production install | https://www.postgresql.org/download/linux/ubuntu/ |
| PostgreSQL Windows installer | 16.x | Alternative OS; use the EDB installer linked by PostgreSQL | https://www.postgresql.org/download/windows/ |
| NGINX | 1.24 or newer (Ubuntu 24.04 package is 1.24.x) | TLS termination, static SPA, reverse proxy | https://nginx.org/en/download.html |
| Certbot | Current version from the official instructions | Free Let's Encrypt certificates and renewal | https://certbot.eff.org/instructions?ws=nginx&os=snap |
| Node.js | 22 LTS (22.12 or newer) | Build-only tool for Vite 8 / TypeScript; not needed at runtime | https://nodejs.org/en/download |
| npm | Bundled with Node.js 22 | Installs frontend dependencies from `package-lock.json` | Included with Node.js |
| Gradle | 8.10.2 | Downloaded automatically by the included Gradle wrapper; do not install separately | https://services.gradle.org/distributions/ (wrapper does this) |
| Git | Current | Optional; only needed to copy/clone the source to a build machine | https://git-scm.com/downloads |
| pgAdmin 4 | Current | Optional GUI for PostgreSQL; psql is sufficient | https://www.pgadmin.org/download/ |
| NSSM | 2.24 | Windows only: run Spring Boot and NGINX as services | https://nssm.cc/download |
| win-acme | Current | Windows only: Let's Encrypt certificates for NGINX | https://www.win-acme.com/ |
| 7-Zip | Current | Windows only: extract NGINX and archives | https://www.7-zip.org/ |
| Web browser | Current Chrome, Edge, or Firefox | Testing the deployed SPA | Vendor website |

**Not required:** Apache Maven. The repository has no `pom.xml`; the Gradle wrapper is included at `backend/gradlew` (Linux/macOS) and `backend/gradlew.bat` (Windows). The wrapper downloads Gradle 8.10.2 from the official Gradle distribution service.

**Build machine vs. server:** Node.js and the JDK are needed to build. The server only needs the JDK to run the JAR. You may build on your workstation and upload `dist/` and the JAR, or build directly on the server. If you build on the server and do not want Node.js there permanently, remove it after the frontend build.

### 1.4 Ports and DNS

| Port | Service | Exposure |
|---|---|---|
| 22 | SSH | Your IP address only, if possible |
| 80 | HTTP (ACME + redirect to HTTPS) | Public |
| 443 | HTTPS | Public |
| 8080 | Spring Boot | `127.0.0.1` only |
| 5432 | PostgreSQL | `127.0.0.1` only (or a private network) |

DNS records to create before Section 10:

| Type | Name | Value |
|---|---|---|
| A | `admin` | `[SERVER IP]` |
| A | `app` | `[SERVER IP]` |
| A | `api` | `[SERVER IP]` |
| AAAA | the same three names | Your IPv6 address, only if the server has one |

Do not expose PostgreSQL port 5432 to the internet. Do not expose Spring Boot port 8080 to the internet.

---

## 2. Server Installation

### 2.1 Install Ubuntu Server 24.04 LTS

1. Download the Ubuntu Server 24.04 LTS ISO from the official link in Section 1.3.
2. Create a bootable USB with Rufus, balenaEtcher, or `dd`.
3. Install with these choices:
   - Language and keyboard as required.
   - Normal installation, not minimal, so common tools are present.
   - Do **not** install a desktop environment.
   - Enable OpenSSH server.
   - Import your SSH public key during installation if offered.
   - Use the entire disk with LVM if you want snapshots, or a simple ext4 layout. Either is acceptable.
   - Set a strong password for the initial administrative user. You will use `sudo` for privileged commands.
4. Reboot and sign in over SSH:

```bash
ssh deploy@[SERVER IP]
```

The examples assume the administrative user is `deploy`. Replace it with your actual user.

### 2.2 First login, updates and time

```bash
sudo apt update
sudo apt full-upgrade -y
sudo apt install -y curl ca-certificates gnupg lsb-release ufw unzip
sudo timedatectl set-timezone UTC
timedatectl
```

Use UTC on the server. The application stores timestamps in UTC and the JVM uses UTC for JDBC. Display in the browser is local to the user.

Set a hostname:

```bash
sudo hostnamectl set-hostname ims-prod
```

### 2.3 Create a swap file (recommended on 4 GB servers)

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

Skip this if your server already has sufficient swap or 8 GB or more RAM.

### 2.4 SSH hardening

Edit `/etc/ssh/sshd_config` only after you have confirmed key-based login works:

```
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
```

Then:

```bash
sudo sshd -t
sudo systemctl reload ssh
```

Keep your current SSH session open until you have tested a new session. Do not lock yourself out.

### 2.5 Firewall

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'
sudo ufw enable
sudo ufw status verbose
```

Ports 8080 and 5432 are deliberately not opened. They will be bound to `127.0.0.1`.

### 2.6 Install NGINX

```bash
sudo apt install -y nginx
sudo systemctl enable --now nginx
sudo systemctl status nginx --no-pager
```

The detailed site configuration is in Section 9. Do not put application files in `/var/www/html`.

### 2.7 Windows Server alternative

A complete Windows Server path is in **Appendix A**. It covers Java 17, PostgreSQL 16, NGINX, NSSM services, folders, SSL with win-acme, backups, and service commands. The application itself is identical; only the operating-system commands differ.

---

## 3. PostgreSQL Setup

### 3.1 Install PostgreSQL 16 from the PostgreSQL APT repository

The Ubuntu 24.04 default repository also contains PostgreSQL. The official PostgreSQL repository gives a current 16.x release and matching client tools. Run:

```bash
sudo apt install -y postgresql-common
sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh
sudo apt update
sudo apt install -y postgresql-16 postgresql-client-16
```

The repository script adds the official PGDG signing key and source. Accept its prompt to create the repository.

Verify:

```bash
psql --version
pg_lsclusters
sudo systemctl status postgresql --no-pager
```

Expected: one cluster, version 16, port 5432, status `online`.

If the cluster is not running:

```bash
sudo systemctl enable --now postgresql
sudo pg_ctlcluster 16 main start
```

### 3.2 Confirm the network binding

```bash
sudo ss -ltnp | grep 5432
```

Expected output contains `127.0.0.1:5432` and/or `[::1]:5432`. It must **not** contain `0.0.0.0:5432` or `[::]:5432`.

Ubuntu's default PostgreSQL configuration listens on localhost. If your installation differs, edit `/etc/postgresql/16/main/postgresql.conf`:

```
listen_addresses = 'localhost'
port = 5432
```

Then restart:

```bash
sudo systemctl restart postgresql
```

Do not open port 5432 in UFW. If you later use a separate database host, use a private network or VPN and restrict the firewall to the application server's private IP.

### 3.3 Authentication

Ubuntu uses `peer` authentication for the local `postgres` operating-system user. Use:

```bash
sudo -u postgres psql
```

You do not need to set a password for the `postgres` superuser for local administration, and you should not use that account for the application.

The application user `ims_app` is created by script 01 in Section 4.1 with a password and limited privileges. Connections from the application use TCP to `127.0.0.1:5432`, which Ubuntu's default `pg_hba.conf` handles with `scram-sha-256`.

Check the relevant lines:

```bash
sudo grep -E '^(local|host)' /etc/postgresql/16/main/pg_hba.conf
```

A normal Ubuntu configuration includes lines equivalent to:

```
local   all             postgres                                peer
local   all             all                                     scram-sha-256
host    all             all             127.0.0.1/32            scram-sha-256
host    all             all             ::1/128                 scram-sha-256
```

Do not change these to `trust` on a production server.

### 3.4 Create the database and the application user

Follow **Section 4.1** now. It creates:

- role `ims_app` — login, no superuser, no createdb, no createrole;
- database `item_management_db` — owned by `ims_app`;
- schema ownership and session defaults.

After running it, test the application connection:

```bash
psql "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -c "SELECT current_user, current_database(), current_setting('timezone');"
```

Enter the `ims_app` password when prompted. Expected: `ims_app`, `item_management_db`, `UTC`.

### 3.5 How Spring Boot connects in production

The backend's `application.yml` has two profiles:

- Default (no profile): starts the **embedded** PostgreSQL 14.10 on port 54329. This is for development.
- `postgres`: disables embedded PostgreSQL and connects to a standalone server using `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `DB_POOL_SIZE`.

Production must use the `postgres` profile. The mapping is:

| Environment variable | Spring property | Production value |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | active profile | `postgres` |
| `EMBEDDED_POSTGRES` | `app.embedded-postgres.enabled` | `false` |
| `DB_URL` | `spring.datasource.url` | `jdbc:postgresql://127.0.0.1:5432/item_management_db` |
| `DB_USERNAME` | `spring.datasource.username` | `ims_app` |
| `DB_PASSWORD` | `spring.datasource.password` | `[DB PASSWORD]` |
| `DB_POOL_SIZE` | Hikari maximum pool size | `15` |
| `SPRING_FLYWAY_ENABLED` | `spring.flyway.enabled` | `true` on the Flyway path |
| `SEED_DEMO` | `app.seed-demo` | `false` |
| `SERVER_ADDRESS` | `server.address` | `127.0.0.1` |
| `SERVER_PORT` | `server.port` | `8080` |
| `JWT_SECRET` | `app.jwt.secret` | a unique production secret |
| `JWT_EXPIRATION_MS` | `app.jwt.expiration-ms` | `43200000` (12 hours) |
| `CORS_ORIGINS` | `app.cors.allowed-origins` | your three HTTPS origins, comma-separated |

The database name is fixed at `item_management_db` in the application's embedded configuration and is the name used by all scripts in this guide. Do not create per-store databases.

### 3.6 Production PostgreSQL notes

- The application connects as `ims_app`, never as `postgres`.
- `ims_app` owns the database and the `public` schema, which is what Flyway needs to create and alter tables.
- The application uses `spring.jpa.hibernate.ddl-auto=validate`. Hibernate validates the schema; it does not create it. Flyway (or scripts 02-04) must create the schema first.
- `statement_timeout` is set to 60 seconds and `idle_in_transaction_session_timeout` to 120 seconds by script 01. These are sensible defaults for this application.
- Keep PostgreSQL on localhost. If you must separate the database host, use a private network/VPN and a firewall rule that allows only the application server.
- Tune memory only after measuring. A reasonable starting point for 8 GB RAM is `shared_buffers = 2GB`, `effective_cache_size = 6GB`, `work_mem = 16MB`, `maintenance_work_mem = 256MB`, and `max_connections = 100`. Do not apply these blindly on a 4 GB server.

---

## 4. Database Creation SQL Scripts

### 4.0 Choose one path, never both

There are two supported ways to create the schema:

| Path | What you run | Flyway setting | When to use |
|---|---|---|---|
| **A. Flyway (recommended)** | Script 01 only. Start the backend; Flyway runs V1, V2, V3. | `SPRING_FLYWAY_ENABLED=true` (default) | New production database, normal deployment |
| **B. Manual SQL** | Scripts 01, 02, 03, 04 in order. | `SPRING_FLYWAY_ENABLED=false` | A DBA must create the schema without starting the application, or you are restoring a manually built database |

**Never run scripts 02-04 on a database that Flyway has already migrated.** The tables already exist and `02_create_tables.sql` has no `IF NOT EXISTS`; the script will fail. **Never enable Flyway on a manually created schema**; Flyway will try to create the same tables and fail. Choose one path and stay on it.

Script 01 is always run manually as the PostgreSQL superuser. Scripts 02-04 are executed as `ims_app` against `item_management_db`.

The four scripts below are reproduced exactly from `docs/sql/`. If you have the repository on the server, you can run the files directly instead of copying from this document. Do not edit the schema statements. The only value you must replace is the `ims_app` password placeholder in script 01.

### 4.1 Script 01 — create the database and application role

Run as the PostgreSQL superuser:

```bash
sudo -u postgres psql -v ON_ERROR_STOP=1
```

Then either paste the script or run:

```sql
\i /path/to/docs/sql/01_create_database.sql
```

Replace `CHANGE_ME_STRONG_PASSWORD` with a long random password before running. A 48-character hexadecimal value is convenient and safe in environment files:

```bash
openssl rand -hex 24
```

Store that value in a password manager. You will put it in `/opt/ims/config/ims.env` as `DB_PASSWORD` and in the backup `.pgpass` file. Do not commit it to source control.

`CREATE DATABASE` cannot run inside a transaction block, and `\connect` is a psql meta-command. Therefore script 01 is a **psql** script, not a single generic SQL batch. For pgAdmin, see Section 4.6.

```sql
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
```

### 4.2 Script 02 — create all tables, primary keys, foreign keys and constraints

This is the consolidated final schema after Flyway V1, V2 and V3. It creates the 23 application tables, all primary keys, composite foreign keys, unique constraints and check constraints, plus the four document-number sequences.

Run it only on the **manual SQL path**, connected to `item_management_db` as `ims_app`:

```bash
psql "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -v ON_ERROR_STOP=1 -f docs/sql/02_create_tables.sql
```

The complete script:

```sql
-- =====================================================================
-- 02_create_tables.sql
-- Item Management System - consolidated FINAL schema (after V1 + V2 + V3)
-- =====================================================================
--
-- PURPOSE
--   A readable, single-file version of the schema the application actually
--   runs after all three Flyway migrations. It is intended for DBAs, reviews,
--   manual rebuilds and documentation. It contains no data.
--
-- IMPORTANT: FLYWAY OWNS THE REAL SCHEMA
--   The application runs Flyway on startup with
--     spring.flyway.enabled=true
--     spring.flyway.locations=classpath:db/migration
--     spring.jpa.hibernate.ddl-auto=validate
--   The authoritative, incremental files are:
--     backend/src/main/resources/db/migration/V1__init.sql
--     backend/src/main/resources/db/migration/V2__tax_master.sql
--     backend/src/main/resources/db/migration/V3__item_rate.sql
--
--   Choose ONE of these paths on a fresh database:
--     A. RECOMMENDED: start the application with the "postgres" profile and
--        let Flyway create everything. Do not run 02/03/04 manually.
--     B. MANUAL: run 01, 02, 03, 04 yourself and set
--        SPRING_FLYWAY_ENABLED=false (or spring.flyway.enabled=false) before
--        starting the application. Otherwise Flyway will try to run V1 and
--        fail because the tables already exist.
--
--   Do not run this file against a database that Flyway already migrated.
--   Do not baseline a manually created schema at version 1 and then replay
--   V2/V3; that would duplicate the tax/rate changes.
--
-- TARGET
--   PostgreSQL 14+. All timestamps are TIMESTAMPTZ; Hibernate uses UTC.
--
-- TENANCY MODEL
--   One database. Every tenant-owned row carries store_id.
--   Branch-scoped rows also carry branch_id.
--   Composite UNIQUE (store_id, id) keys plus composite foreign keys make it
--   impossible for a row in store A to reference a row in store B.
--
-- CONVENTIONS
--   - Identity: BIGSERIAL / BIGINT.
--   - Money:    NUMERIC(14,2), except item master rate NUMERIC(12,2) and
--               weighted average cost NUMERIC(18,4).
--   - Quantity: NUMERIC(18,3) in the item's base unit.
--   - Status:   VARCHAR + CHECK, mirrored by Java enums.
--   - Soft delete: categories/tax slabs referenced by history cannot be
--     deleted; status is set to INACTIVE instead. Completed purchases and
--     sales are never deleted, only CANCELLED with reversal stock movements.
--
-- RUN ORDER
--   01_create_database.sql -> 02_create_tables.sql -> 03_create_indexes.sql
--   -> 04_insert_initial_data.sql -> (optional) 06_sample_business_flow.sql
--   05_database_validation.sql is read-only and can be run at any time.
-- =====================================================================


-- =====================================================================
-- 1. STORE (tenant root)
-- =====================================================================
CREATE TABLE stores (
    id            BIGSERIAL PRIMARY KEY,
    store_code    VARCHAR(30)  NOT NULL UNIQUE,
    store_name    VARCHAR(150) NOT NULL,
    owner_name    VARCHAR(150) NOT NULL,
    email         VARCHAR(150) NOT NULL,
    mobile        VARCHAR(20)  NOT NULL,
    address       TEXT         NOT NULL,
    gst_number    VARCHAR(30),
    tax_inclusive BOOLEAN      NOT NULL DEFAULT false,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE stores IS 'Tenant root. Exactly one row per registered business/store.';
COMMENT ON COLUMN stores.store_code IS 'Login tenant code. Reserved value PLATFORM is rejected by the application.';
COMMENT ON COLUMN stores.tax_inclusive IS 'When true, item rates are treated as GST-inclusive and tax is extracted.';


-- =====================================================================
-- 2. BRANCH (belongs to one store)
-- =====================================================================
CREATE TABLE branches (
    id             BIGSERIAL PRIMARY KEY,
    store_id       BIGINT       NOT NULL REFERENCES stores(id),
    branch_code    VARCHAR(30)  NOT NULL,
    branch_name    VARCHAR(150) NOT NULL,
    address        TEXT         NOT NULL,
    contact_number VARCHAR(20)  NOT NULL,
    state          VARCHAR(80),
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_branches_code UNIQUE (store_id, branch_code),
    CONSTRAINT uq_branches_store_id UNIQUE (store_id, id)
);

COMMENT ON COLUMN branches.state IS 'Registered place of supply. Used to decide CGST+SGST vs IGST.';
COMMENT ON CONSTRAINT uq_branches_store_id ON branches IS
    'Target for composite FKs, preventing cross-store references.';


-- =====================================================================
-- 3. ROLES, PERMISSIONS AND USER LINKS
-- =====================================================================
CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT REFERENCES stores(id) ON DELETE CASCADE,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    is_system   BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Functional unique index because store_id is NULL for the platform role.
-- COALESCE(store_id, 0) lets PostgreSQL enforce uniqueness for NULL tenants.
CREATE UNIQUE INDEX uq_roles_store_code ON roles (COALESCE(store_id, 0), code);

COMMENT ON TABLE roles IS
    'A role belongs to one store. store_id IS NULL identifies the platform role PLATFORM_ADMIN.';
COMMENT ON COLUMN roles.is_system IS 'System roles are provisioned with the store; they are not deletable.';

CREATE TABLE permissions (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(60)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(255)
);

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT REFERENCES stores(id),
    username      VARCHAR(80)  NOT NULL,
    email         VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    mobile        VARCHAR(20),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_store_username UNIQUE (store_id, username)
);

COMMENT ON COLUMN users.store_id IS
    'NULL = platform administrator. Non-null = store user, isolated to that store.';
COMMENT ON COLUMN users.password_hash IS 'BCrypt hash (strength 10). Plain passwords are never stored.';

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE user_branches (
    user_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    branch_id BIGINT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, branch_id)
);

COMMENT ON TABLE user_branches IS
    'Branch assignments for BRANCH_MANAGER / BRANCH_STAFF. Owners and admins get allBranches implicitly.';


-- =====================================================================
-- 4. CATEGORIES
-- =====================================================================
CREATE TABLE categories (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT       NOT NULL REFERENCES stores(id),
    category_name VARCHAR(120) NOT NULL,
    description   VARCHAR(255),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_categories_name UNIQUE (store_id, category_name),
    CONSTRAINT uq_categories_store_id UNIQUE (store_id, id)
);


-- =====================================================================
-- 5. TAX MASTER (store-specific GST slabs)
-- =====================================================================
CREATE TABLE tax_master (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT       NOT NULL REFERENCES stores(id),
    tax_name        VARCHAR(120) NOT NULL,
    description     VARCHAR(255),
    gst_percentage  NUMERIC(5,2) NOT NULL
                    CHECK (gst_percentage >= 0 AND gst_percentage <= 100),
    cgst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0
                    CHECK (cgst_percentage >= 0 AND cgst_percentage <= 100),
    sgst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0
                    CHECK (sgst_percentage >= 0 AND sgst_percentage <= 100),
    igst_percentage NUMERIC(5,2) NOT NULL DEFAULT 0
                    CHECK (igst_percentage >= 0 AND igst_percentage <= 100),
    tax_type        VARCHAR(20)  NOT NULL DEFAULT 'GST'
                    CHECK (tax_type IN ('GST', 'EXEMPT')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tax_master_store_name UNIQUE (store_id, tax_name),
    CONSTRAINT uq_tax_master_store_id UNIQUE (store_id, id)
);

COMMENT ON TABLE tax_master IS
    'Store-owned GST slabs. Every store is seeded with 0% (EXEMPT), 5%, 12%, 18% and 28%.';
COMMENT ON COLUMN tax_master.gst_percentage IS
    'Total GST rate. The application derives cgst = gst/2 (HALF_UP), sgst = remainder, igst = gst.';


-- =====================================================================
-- 6. ITEMS (one master rate, one tax slab)
-- =====================================================================
CREATE TABLE items (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT        NOT NULL REFERENCES stores(id),
    category_id     BIGINT        NOT NULL,
    tax_id          BIGINT        NOT NULL,
    item_code       VARCHAR(50)   NOT NULL,
    item_name       VARCHAR(150)  NOT NULL,
    base_unit       VARCHAR(20)   NOT NULL
                    CHECK (base_unit IN ('LITER','ML','KG','GRAM','PIECE','PACKET','BOX','BOTTLE','DOZEN')),
    rate            NUMERIC(12,2) NOT NULL,
    min_stock_level NUMERIC(18,3) NOT NULL DEFAULT 5 CHECK (min_stock_level >= 0),
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_items_rate_non_negative CHECK (rate >= 0),
    CONSTRAINT uq_items_code UNIQUE (store_id, item_code),
    CONSTRAINT uq_items_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_items_category FOREIGN KEY (category_id, store_id)
        REFERENCES categories(id, store_id),
    CONSTRAINT fk_items_tax FOREIGN KEY (tax_id, store_id)
        REFERENCES tax_master(id, store_id)
);

COMMENT ON COLUMN items.rate IS
    'The single item master rate. POS billing always uses this value server-side. '
    'Normal sales and purchases store their own negotiated per-line rates.';


-- =====================================================================
-- 7. SUPPLIERS AND CUSTOMERS
-- =====================================================================
CREATE TABLE suppliers (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT       NOT NULL REFERENCES stores(id),
    supplier_name VARCHAR(150) NOT NULL,
    mobile        VARCHAR(20)  NOT NULL,
    address       TEXT         NOT NULL,
    gst_number    VARCHAR(30),
    email         VARCHAR(150),
    state         VARCHAR(80),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_suppliers_store_id UNIQUE (store_id, id)
);

COMMENT ON COLUMN suppliers.state IS
    'Supplier place of supply. Compared with the branch state for IGST.';

CREATE TABLE customers (
    id         BIGSERIAL PRIMARY KEY,
    store_id   BIGINT       NOT NULL REFERENCES stores(id),
    name       VARCHAR(150) NOT NULL,
    mobile     VARCHAR(20),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_store_id UNIQUE (store_id, id)
);

COMMENT ON TABLE customers IS
    'Reserved for a future customer master. Sales currently store customer name/mobile/state '
    'as free-text snapshots; no service or controller reads or writes this table.';


-- =====================================================================
-- 8. INVENTORY (per branch + item)
-- =====================================================================
CREATE TABLE branch_inventory (
    id             BIGSERIAL PRIMARY KEY,
    store_id       BIGINT        NOT NULL REFERENCES stores(id),
    branch_id      BIGINT        NOT NULL,
    item_id        BIGINT        NOT NULL,
    current_stock  NUMERIC(18,3) NOT NULL DEFAULT 0 CHECK (current_stock >= 0),
    minimum_stock  NUMERIC(18,3) NOT NULL DEFAULT 5 CHECK (minimum_stock >= 0),
    purchase_price NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (purchase_price >= 0),
    selling_price  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (selling_price >= 0),
    avg_cost       NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (avg_cost >= 0),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_inventory_branch_item UNIQUE (store_id, branch_id, item_id),
    CONSTRAINT fk_inventory_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_inventory_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id)
);

COMMENT ON TABLE branch_inventory IS
    'Current stock and weighted average cost per branch and item. The database CHECK '
    'current_stock >= 0 is the last line of defence against overselling.';
COMMENT ON COLUMN branch_inventory.minimum_stock IS
    'Per-branch low-stock threshold, initialised from items.min_stock_level.';
COMMENT ON COLUMN branch_inventory.avg_cost IS
    'Weighted average purchase cost used for COGS and stock valuation.';


-- =====================================================================
-- 9. INVENTORY LEDGER
-- =====================================================================
CREATE TABLE inventory_transactions (
    id               BIGSERIAL PRIMARY KEY,
    store_id         BIGINT        NOT NULL REFERENCES stores(id),
    branch_id        BIGINT        NOT NULL,
    item_id          BIGINT        NOT NULL,
    transaction_type VARCHAR(30)   NOT NULL CHECK (transaction_type IN (
        'OPENING_STOCK', 'PURCHASE_IN', 'SALE_OUT', 'TRANSFER_IN', 'TRANSFER_OUT',
        'ADJUSTMENT_IN', 'ADJUSTMENT_OUT', 'PURCHASE_REVERSAL', 'SALE_REVERSAL')),
    reference_type   VARCHAR(30),
    reference_id     BIGINT,
    reference_number VARCHAR(50),
    quantity_in      NUMERIC(18,3) NOT NULL DEFAULT 0 CHECK (quantity_in >= 0),
    quantity_out     NUMERIC(18,3) NOT NULL DEFAULT 0 CHECK (quantity_out >= 0),
    balance_after    NUMERIC(18,3) NOT NULL CHECK (balance_after >= 0),
    notes            VARCHAR(255),
    transaction_date TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT REFERENCES users(id),
    CONSTRAINT fk_txn_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_txn_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id)
);

COMMENT ON TABLE inventory_transactions IS
    'Append-only stock ledger. Every stock change writes one row; rows are never updated or deleted.';
COMMENT ON COLUMN inventory_transactions.balance_after IS
    'Stock on hand after this movement; used for audit and opening-stock derivation.';


-- =====================================================================
-- 10. STOCK ADJUSTMENTS (manual IN/OUT)
-- =====================================================================
CREATE TABLE stock_adjustments (
    id                BIGSERIAL PRIMARY KEY,
    store_id          BIGINT        NOT NULL REFERENCES stores(id),
    branch_id         BIGINT        NOT NULL,
    item_id           BIGINT        NOT NULL,
    adjustment_number VARCHAR(50)   NOT NULL,
    adjustment_type   VARCHAR(20)   NOT NULL CHECK (adjustment_type IN ('IN', 'OUT')),
    quantity          NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    base_quantity     NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    unit              VARCHAR(20)   NOT NULL,
    reason            VARCHAR(255)  NOT NULL,
    created_by        BIGINT REFERENCES users(id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_adjustments_number UNIQUE (store_id, adjustment_number),
    CONSTRAINT fk_adjustment_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_adjustment_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id)
);

COMMENT ON COLUMN stock_adjustments.quantity IS 'Quantity in the entered unit.';
COMMENT ON COLUMN stock_adjustments.base_quantity IS 'Converted quantity in the item base unit.';


-- =====================================================================
-- 11. PURCHASES
-- =====================================================================
CREATE TABLE purchases (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT        NOT NULL REFERENCES stores(id),
    branch_id       BIGINT        NOT NULL,
    supplier_id     BIGINT        NOT NULL,
    purchase_number VARCHAR(50)   NOT NULL,
    invoice_number  VARCHAR(80),
    purchase_date   DATE          NOT NULL,
    subtotal        NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax             NUMERIC(14,2) NOT NULL DEFAULT 0,
    grand_total     NUMERIC(14,2) NOT NULL DEFAULT 0,
    taxable_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    cgst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    sgst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    igst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    paid_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    balance_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    notes           VARCHAR(500),
    status          VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN ('DRAFT', 'COMPLETED', 'CANCELLED')),
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_purchases_number UNIQUE (store_id, purchase_number),
    CONSTRAINT uq_purchases_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_purchase_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_purchase_supplier FOREIGN KEY (supplier_id, store_id)
        REFERENCES suppliers(id, store_id)
);

COMMENT ON TABLE purchases IS
    'Purchase header. Completed purchases are reversed with CANCELLED, never deleted.';
COMMENT ON COLUMN purchases.tax IS 'Total GST across all lines, equal to cgst_amount + sgst_amount + igst_amount.';
COMMENT ON COLUMN purchases.balance_amount IS 'grand_total - paid_amount.';


-- =====================================================================
-- 12. PURCHASE LINES (immutable rates and tax snapshots)
-- =====================================================================
CREATE TABLE purchase_details (
    id                BIGSERIAL PRIMARY KEY,
    store_id          BIGINT        NOT NULL REFERENCES stores(id),
    purchase_id       BIGINT        NOT NULL,
    item_id           BIGINT        NOT NULL,
    quantity          NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    unit              VARCHAR(20)   NOT NULL,
    base_quantity     NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    purchase_rate     NUMERIC(14,2) NOT NULL CHECK (purchase_rate >= 0),
    discount          NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax               NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_amount      NUMERIC(14,2) NOT NULL,
    tax_id            BIGINT,
    tax_name_snapshot VARCHAR(120)  NOT NULL DEFAULT 'GST 0% (Exempt)',
    gst_percentage    NUMERIC(5,2)  NOT NULL DEFAULT 0,
    cgst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    sgst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    igst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    taxable_amount    NUMERIC(14,2) NOT NULL DEFAULT 0,
    cgst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    sgst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    igst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_amount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_pd_purchase FOREIGN KEY (purchase_id, store_id)
        REFERENCES purchases(id, store_id) ON DELETE CASCADE,
    CONSTRAINT fk_pd_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id),
    CONSTRAINT fk_pd_tax FOREIGN KEY (tax_id, store_id)
        REFERENCES tax_master(id, store_id)
);

COMMENT ON TABLE purchase_details IS
    'Purchase lines. purchase_rate is entered manually; tax columns are server-computed '
    'snapshots and are never recalculated from the tax master later.';


-- =====================================================================
-- 13. SALES
-- =====================================================================
CREATE TABLE sales (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT        NOT NULL REFERENCES stores(id),
    branch_id       BIGINT        NOT NULL,
    sales_number    VARCHAR(50)   NOT NULL,
    sales_date      DATE          NOT NULL,
    customer_id     BIGINT,
    customer_name   VARCHAR(150),
    customer_mobile VARCHAR(20),
    customer_state  VARCHAR(80),
    subtotal        NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax             NUMERIC(14,2) NOT NULL DEFAULT 0,
    grand_total     NUMERIC(14,2) NOT NULL DEFAULT 0,
    taxable_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    cgst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    sgst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    igst_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    paid_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    balance_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    payment_method  VARCHAR(20)   NOT NULL DEFAULT 'CASH'
                    CHECK (payment_method IN ('CASH', 'UPI', 'CARD', 'CREDIT')),
    status          VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN ('COMPLETED', 'CANCELLED')),
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_sales_number UNIQUE (store_id, sales_number),
    CONSTRAINT uq_sales_store_id UNIQUE (store_id, id),
    CONSTRAINT fk_sale_branch FOREIGN KEY (branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_sale_customer FOREIGN KEY (customer_id, store_id)
        REFERENCES customers(id, store_id)
);

COMMENT ON COLUMN sales.customer_state IS
    'Place of supply snapshot. Null/blank means intra-state (walk-in default).';
COMMENT ON COLUMN sales.payment_method IS
    'CASH, UPI, CARD or CREDIT. There is no separate payments table.';


-- =====================================================================
-- 14. SALES LINES (immutable rates, cost and tax snapshots)
-- =====================================================================
CREATE TABLE sales_details (
    id                BIGSERIAL PRIMARY KEY,
    store_id          BIGINT        NOT NULL REFERENCES stores(id),
    sales_id          BIGINT        NOT NULL,
    item_id           BIGINT        NOT NULL,
    quantity          NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    unit              VARCHAR(20)   NOT NULL,
    base_quantity     NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    selling_rate      NUMERIC(14,2) NOT NULL CHECK (selling_rate >= 0),
    cost_rate         NUMERIC(18,4) NOT NULL DEFAULT 0,
    discount          NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax               NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_amount      NUMERIC(14,2) NOT NULL,
    tax_id            BIGINT,
    tax_name_snapshot VARCHAR(120)  NOT NULL DEFAULT 'GST 0% (Exempt)',
    gst_percentage    NUMERIC(5,2)  NOT NULL DEFAULT 0,
    cgst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    sgst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    igst_percentage   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    taxable_amount    NUMERIC(14,2) NOT NULL DEFAULT 0,
    cgst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    sgst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    igst_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_amount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_sd_sale FOREIGN KEY (sales_id, store_id)
        REFERENCES sales(id, store_id) ON DELETE CASCADE,
    CONSTRAINT fk_sd_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id),
    CONSTRAINT fk_sd_tax FOREIGN KEY (tax_id, store_id)
        REFERENCES tax_master(id, store_id)
);

COMMENT ON COLUMN sales_details.selling_rate IS
    'Manual rate for normal sales; item master rate for POS sales (enforced server-side).';
COMMENT ON COLUMN sales_details.cost_rate IS
    'Weighted average cost at the moment of sale. Frozen for historical COGS/profit.';


-- =====================================================================
-- 15. STOCK TRANSFERS
-- =====================================================================
CREATE TABLE stock_transfers (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT       NOT NULL REFERENCES stores(id),
    transfer_number VARCHAR(50)  NOT NULL,
    from_branch_id  BIGINT       NOT NULL,
    to_branch_id    BIGINT       NOT NULL,
    transfer_date   DATE         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN (
        'DRAFT', 'PENDING', 'APPROVED', 'DISPATCHED', 'RECEIVED', 'REJECTED', 'CANCELLED')),
    notes           VARCHAR(500),
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfers_number UNIQUE (store_id, transfer_number),
    CONSTRAINT uq_transfers_store_id UNIQUE (store_id, id),
    CONSTRAINT ck_transfer_branches CHECK (from_branch_id <> to_branch_id),
    CONSTRAINT fk_transfer_from FOREIGN KEY (from_branch_id, store_id)
        REFERENCES branches(id, store_id),
    CONSTRAINT fk_transfer_to FOREIGN KEY (to_branch_id, store_id)
        REFERENCES branches(id, store_id)
);

COMMENT ON TABLE stock_transfers IS
    'Two-step branch transfer. Stock leaves the source on DISPATCH and enters the destination on RECEIVE.';
COMMENT ON COLUMN stock_transfers.status IS
    'DRAFT exists in the schema/enum but the current service creates PENDING transfers directly.';


CREATE TABLE stock_transfer_details (
    id            BIGSERIAL PRIMARY KEY,
    store_id      BIGINT        NOT NULL REFERENCES stores(id),
    transfer_id   BIGINT        NOT NULL,
    item_id       BIGINT        NOT NULL,
    quantity      NUMERIC(18,3) NOT NULL CHECK (quantity > 0),
    unit          VARCHAR(20)   NOT NULL,
    base_quantity NUMERIC(18,3) NOT NULL CHECK (base_quantity > 0),
    CONSTRAINT fk_td_transfer FOREIGN KEY (transfer_id, store_id)
        REFERENCES stock_transfers(id, store_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_item FOREIGN KEY (item_id, store_id)
        REFERENCES items(id, store_id)
);


-- =====================================================================
-- 16. AUDIT LOG
-- =====================================================================
CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT,
    user_id     BIGINT,
    username    VARCHAR(80),
    action      VARCHAR(60)  NOT NULL,
    entity_type VARCHAR(60)  NOT NULL,
    entity_id   BIGINT,
    details     VARCHAR(1000),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE audit_logs IS
    'Append-only audit trail for sensitive actions (login, store registration, purchases, '
    'sales, cancellations, transfers, adjustments, pricing). No FK on purpose so logs survive '
    'user/store changes. There is no read API; query with SQL.';


-- =====================================================================
-- 17. DOCUMENT NUMBER SEQUENCES
-- =====================================================================
CREATE SEQUENCE purchase_number_seq  START WITH 1001;
CREATE SEQUENCE sales_number_seq     START WITH 1001;
CREATE SEQUENCE transfer_number_seq  START WITH 1001;
CREATE SEQUENCE adjustment_number_seq START WITH 1001;

COMMENT ON SEQUENCE purchase_number_seq IS
    'Global sequence. Application formats PUR-<number>. Numbers are not reused after rollback.';
COMMENT ON SEQUENCE sales_number_seq IS 'Global sequence. Application formats SAL-<number>.';
COMMENT ON SEQUENCE transfer_number_seq IS 'Global sequence. Application formats TRF-<number>.';
COMMENT ON SEQUENCE adjustment_number_seq IS 'Global sequence. Application formats ADJ-<number>.';


-- =====================================================================
-- END OF SCHEMA
-- Next: 03_create_indexes.sql
-- =====================================================================
```

### 4.3 Script 03 — create the 19 performance indexes

The primary-key and unique indexes are created with the table constraints in script 02. Script 03 adds the non-constraint indexes used by tenant-scoped list, report and join queries.

Run it only on the manual SQL path, after script 02:

```bash
psql "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -v ON_ERROR_STOP=1 -f docs/sql/03_create_indexes.sql
```

The complete script:

```sql
-- =====================================================================
-- 03_create_indexes.sql
-- Item Management System - performance indexes
-- =====================================================================
--
-- PURPOSE
--   Recreate exactly the non-constraint indexes that Flyway V1 and V2 create.
--   Unique/PK indexes are already created with the table constraints in 02.
--
-- IMPORTANT
--   - Run only on a schema created manually by 02_create_tables.sql.
--   - Do not run after Flyway has migrated the database; the indexes already
--     exist. Every statement uses IF NOT EXISTS, so accidental re-runs are
--     harmless.
--   - The unique functional index uq_roles_store_code is created in 02 because
--     it enforces correctness, not just performance.
--
-- INDEX STRATEGY
--   All high-volume queries are tenant-scoped by store_id. Most indexes
--   therefore lead with store_id, then the branch/item/date column used by the
--   filter. Foreign-key child columns are indexed to keep joins and deletes
--   fast.
--
-- =====================================================================

-- USERS
CREATE INDEX IF NOT EXISTS ix_users_email ON users (email);
CREATE INDEX IF NOT EXISTS ix_users_store ON users (store_id);

-- ITEMS
CREATE INDEX IF NOT EXISTS ix_items_store_category ON items (store_id, category_id);
CREATE INDEX IF NOT EXISTS ix_items_name ON items (store_id, item_name);
CREATE INDEX IF NOT EXISTS ix_items_tax ON items (store_id, tax_id);

-- TAX MASTER
CREATE INDEX IF NOT EXISTS ix_tax_master_store_status ON tax_master (store_id, status);

-- BRANCH INVENTORY
CREATE INDEX IF NOT EXISTS ix_inventory_item ON branch_inventory (store_id, item_id);
CREATE INDEX IF NOT EXISTS ix_inventory_branch ON branch_inventory (store_id, branch_id);

-- INVENTORY LEDGER
CREATE INDEX IF NOT EXISTS ix_txn_store_date
    ON inventory_transactions (store_id, transaction_date);
CREATE INDEX IF NOT EXISTS ix_txn_branch_item
    ON inventory_transactions (store_id, branch_id, item_id);
CREATE INDEX IF NOT EXISTS ix_txn_reference
    ON inventory_transactions (reference_type, reference_id);

-- PURCHASES
CREATE INDEX IF NOT EXISTS ix_purchases_store_date ON purchases (store_id, purchase_date);
CREATE INDEX IF NOT EXISTS ix_purchases_branch ON purchases (store_id, branch_id);
CREATE INDEX IF NOT EXISTS ix_purchase_details_purchase ON purchase_details (purchase_id);

-- SALES
CREATE INDEX IF NOT EXISTS ix_sales_store_date ON sales (store_id, sales_date);
CREATE INDEX IF NOT EXISTS ix_sales_branch ON sales (store_id, branch_id);
CREATE INDEX IF NOT EXISTS ix_sales_details_sale ON sales_details (sales_id);

-- TRANSFERS
CREATE INDEX IF NOT EXISTS ix_transfers_store ON stock_transfers (store_id, transfer_date);

-- AUDIT
CREATE INDEX IF NOT EXISTS ix_audit_store_date ON audit_logs (store_id, created_at);


-- =====================================================================
-- OPTIONAL RECOMMENDATIONS (NOT part of the current migrations)
-- =====================================================================
-- The indexes below are NOT created by the application. They are suggestions
-- for larger datasets. Review with EXPLAIN (ANALYZE, BUFFERS) before adding
-- them. Uncomment and run manually if the corresponding query is hot.
--
-- Supplier purchase history: SupplierService.purchaseHistory filters by
-- (store_id, supplier_id) ordered by purchase_date desc.
-- CREATE INDEX IF NOT EXISTS ix_purchases_supplier
--     ON purchases (store_id, supplier_id, purchase_date DESC);
--
-- Transfer line lookups by parent are covered by the FK but not indexed in the
-- current schema.
-- CREATE INDEX IF NOT EXISTS ix_transfer_details_transfer
--     ON stock_transfer_details (transfer_id);
-- CREATE INDEX IF NOT EXISTS ix_transfer_details_item
--     ON stock_transfer_details (store_id, item_id);
--
-- Stock report scans the ledger for a store and a date window. The existing
-- (store_id, transaction_date) index covers the range; add item/branch when
-- reports are filtered by item or branch on very large ledgers.
-- CREATE INDEX IF NOT EXISTS ix_txn_store_branch_item_date
--     ON inventory_transactions (store_id, branch_id, item_id, transaction_date);
--
-- Audit review by user (the application has no audit UI yet).
-- CREATE INDEX IF NOT EXISTS ix_audit_user_date
--     ON audit_logs (user_id, created_at DESC);
--
-- Partial indexes for the common status filters. These stay small because only
-- a minority of rows are DRAFT/CANCELLED.
-- CREATE INDEX IF NOT EXISTS ix_purchases_draft
--     ON purchases (store_id, purchase_date) WHERE status = 'DRAFT';
-- CREATE INDEX IF NOT EXISTS ix_transfers_pending
--     ON stock_transfers (store_id, transfer_date) WHERE status IN ('PENDING','APPROVED','DISPATCHED');
--
-- Optional trigram search for item name. Requires:
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- CREATE INDEX IF NOT EXISTS ix_items_name_trgm
--     ON items USING gin (item_name gin_trgm_ops);
```

### 4.4 Script 04 — initial reference data

This script inserts the required reference data:

- 28 permission codes (26 from V1 and 2 added by V2: `TAX_VIEW`, `TAX_MANAGE`);
- the platform-level role `PLATFORM_ADMIN` with three grants: `PLATFORM_MANAGE`, `STORE_VIEW`, `STORE_MANAGE`;
- optional repair/backfill rows for GST slabs and the four standard store roles for stores that already exist.

It does **not** create a store, a business user, or demo transactions. The application creates the platform user on first start (see Section 11), and the platform console creates stores and owners (Section 13).

Run it only on the manual SQL path, after scripts 02 and 03, as `ims_app`:

```bash
psql "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -v ON_ERROR_STOP=1 -f docs/sql/04_insert_initial_data.sql
```

The complete script:

```sql
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
```

### 4.5 What the schema contains

The complete DDL above is authoritative. This summary helps you verify the result.

**23 application tables**

| Group | Tables |
|---|---|
| Tenant and access | `stores`, `branches`, `roles`, `permissions`, `role_permissions`, `users`, `user_roles`, `user_branches` |
| Master data | `categories`, `tax_master`, `items`, `suppliers`, `customers` |
| Inventory | `branch_inventory`, `inventory_transactions`, `stock_adjustments` |
| Purchasing | `purchases`, `purchase_details` |
| Sales | `sales`, `sales_details` |
| Transfers | `stock_transfers`, `stock_transfer_details` |
| Audit | `audit_logs` |

`customers` is reserved and is not currently read or written by the application. Sales store customer name, mobile and state as free-text snapshots.

**Primary keys**

- Every table has an `id BIGSERIAL PRIMARY KEY`, except the three junction tables:
  - `role_permissions (role_id, permission_id)`
  - `user_roles (user_id, role_id)`
  - `user_branches (user_id, branch_id)`

**Tenant and branch keys**

- `stores` is the tenant root.
- Every tenant-owned table has `store_id BIGINT NOT NULL REFERENCES stores(id)`.
- Branch-scoped tables also have `branch_id`.
- Tenant tables that are referenced by history have `UNIQUE (store_id, id)`. This allows composite foreign keys such as `(branch_id, store_id) REFERENCES branches(id, store_id)`, which makes a cross-store reference impossible at the database level.
- `roles.store_id` and `users.store_id` are nullable. `NULL` identifies platform-level records. The platform role `PLATFORM_ADMIN` and the platform user have `store_id IS NULL`.

**Key foreign-key relationships**

| Child | Parent |
|---|---|
| `branches.store_id` | `stores.id` |
| `roles.store_id` | `stores.id` |
| `users.store_id` | `stores.id` |
| `user_roles` | `users.id`, `roles.id` |
| `user_branches` | `users.id`, `branches.id` |
| `items (category_id, store_id)` | `categories (id, store_id)` |
| `items (tax_id, store_id)` | `tax_master (id, store_id)` |
| `branch_inventory (branch_id, store_id)` | `branches (id, store_id)` |
| `branch_inventory (item_id, store_id)` | `items (id, store_id)` |
| `inventory_transactions (branch_id, store_id)` | `branches (id, store_id)` |
| `inventory_transactions (item_id, store_id)` | `items (id, store_id)` |
| `stock_adjustments (branch_id, store_id)` | `branches (id, store_id)` |
| `stock_adjustments (item_id, store_id)` | `items (id, store_id)` |
| `purchases (branch_id, store_id)` | `branches (id, store_id)` |
| `purchases (supplier_id, store_id)` | `suppliers (id, store_id)` |
| `purchase_details (purchase_id, store_id)` | `purchases (id, store_id)` |
| `purchase_details (item_id, store_id)` | `items (id, store_id)` |
| `sales (branch_id, store_id)` | `branches (id, store_id)` |
| `sales_details (sale_id, store_id)` | `sales (id, store_id)` |
| `sales_details (item_id, store_id)` | `items (id, store_id)` |
| `stock_transfers (from_branch_id, store_id)` | `branches (id, store_id)` |
| `stock_transfers (to_branch_id, store_id)` | `branches (id, store_id)` |
| `stock_transfer_details (transfer_id, store_id)` | `stock_transfers (id, store_id)` |
| `stock_transfer_details (item_id, store_id)` | `items (id, store_id)` |
| `audit_logs.store_id` | `stores.id` |
| `audit_logs.user_id` | `users.id` |

The full column-level definitions, `CHECK` constraints and `ON DELETE` behaviour are in script 02. Do not recreate them from this summary.

**Sequences**

Four explicit document-number sequences are created by script 02:

| Sequence | Starts at | Used for |
|---|---|---|
| `purchase_number_seq` | 1001 | `PUR-<number>` |
| `sales_number_seq` | 1001 | `SAL-<number>` |
| `transfer_number_seq` | 1001 | `TRF-<number>` |
| `adjustment_number_seq` | 1001 | `ADJ-<number>` |

Each table with a `BIGSERIAL` primary key also has an internal sequence. Those internal sequences are created automatically with the columns and are not part of the document-numbering scheme.

**Indexes**

Script 03 creates 19 non-constraint indexes. Primary-key and unique-constraint indexes come from script 02. The main patterns are `(store_id, ...)` for tenant isolation, `(store_id, transaction_date)` and `(store_id, branch_id, item_id)` for the inventory ledger, `(store_id, purchase_date)`/`(store_id, sales_date)` for history, and foreign-key child indexes for joins and deletes. Optional indexes are commented out and must not be enabled without measuring the workload.

**Initial rows created by script 04 or Flyway**

| Row set | Expected count |
|---|---|
| Permissions | 28 |
| Platform role `PLATFORM_ADMIN` | 1 (`store_id IS NULL`) |
| Permission grants for `PLATFORM_ADMIN` | 3 (`PLATFORM_MANAGE`, `STORE_VIEW`, `STORE_MANAGE`) |
| Stores, users, items, sales, purchases | 0 on a clean production database |

The platform user is created by the application's `DataSeeder`, not by script 04. Store roles, GST slabs, the `MAIN` branch and the store owner are created by `StoreProvisioningService` when you onboard a store in Section 13.

### 4.6 Running the scripts with psql or pgAdmin

**psql (recommended)**

```bash
# Script 01 as the PostgreSQL superuser
sudo -u postgres psql -v ON_ERROR_STOP=1 -f /path/to/01_create_database.sql

# Scripts 02-04 as the application user, manual-path only
psql "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -v ON_ERROR_STOP=1 -f /path/to/02_create_tables.sql
psql "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -v ON_ERROR_STOP=1 -f /path/to/03_create_indexes.sql
psql "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -v ON_ERROR_STOP=1 -f /path/to/04_insert_initial_data.sql
```

`ON_ERROR_STOP=1` is important: it stops at the first error instead of leaving a half-created schema.

**pgAdmin 4**

Script 01 contains `CREATE DATABASE` and the psql meta-command `\connect`, which pgAdmin's Query Tool does not understand. Split it:

1. Connect to the `postgres` database as the `postgres` superuser.
2. Open Query Tool and execute the `CREATE ROLE ...` statement and the `CREATE DATABASE ...` statement, including the `COMMENT ON DATABASE` statement. Do not include `\connect`.
3. Connect a new Query Tool session to `item_management_db`.
4. Execute the remaining part of script 01: `ALTER SCHEMA public OWNER TO ims_app`, the two `REVOKE` statements, the `GRANT`, the four `ALTER DATABASE` statements, and the verification queries. Remove the `\connect` line.
5. Connect to `item_management_db` as `ims_app`.
6. Execute scripts 02, 03 and 04 in order in Query Tool.

pgAdmin may wrap a large script in a transaction. Script 02 contains only DDL, which is transactional in PostgreSQL, so that is fine. Script 01's `CREATE DATABASE` cannot run in a transaction; execute it by itself with autocommit enabled.

**If a script fails halfway**

- Script 02 is not idempotent. If it fails partway, the safest recovery is to drop the partially created database and start again from script 01 on an empty database. Do not patch a partial schema by hand.
- Scripts 03 and 04 are idempotent (`IF NOT EXISTS`, `ON CONFLICT DO NOTHING`) and can be re-run.
- Record the exact error before changing anything. `relation "..." already exists` usually means you chose the wrong path (manual plus Flyway).

### 4.7 Verify the database after creation

Run these read-only queries after either path:

```sql
-- 23 application tables, plus flyway_schema_history only on the Flyway path
SELECT count(*) AS application_tables
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_type = 'BASE TABLE'
  AND table_name <> 'flyway_schema_history';

SELECT count(*) AS permission_count FROM permissions;                 -- expect 28

SELECT r.code, count(rp.permission_id) AS grants
FROM roles r
LEFT JOIN role_permissions rp ON rp.role_id = r.id
WHERE r.store_id IS NULL
GROUP BY r.code;                                                       -- PLATFORM_ADMIN = 3

SELECT sequence_name
FROM information_schema.sequences
WHERE sequence_schema = 'public'
  AND sequence_name IN (
    'purchase_number_seq', 'sales_number_seq',
    'transfer_number_seq', 'adjustment_number_seq'
  )
ORDER BY sequence_name;                                                -- expect 4 rows
```

On the Flyway path, after the first backend start:

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;                                               -- V1, V2, V3 success
```

On the manual path, `flyway_schema_history` does not exist and must not exist. Keep `SPRING_FLYWAY_ENABLED=false`.

### 4.8 Other SQL files in the repository

`docs/sql/` also contains:

| File | Purpose | Production use |
|---|---|---|
| `05_database_validation.sql` | Read-only schema, constraint and integrity checks | Safe to run at any time as `ims_app` |
| `06_sample_business_flow.sql` | Creates a disposable demo store, purchase, sale, POS transaction and transfer | **Never run on production** |

Run the validation script after deployment if you want a broader check than Section 4.7:

```bash
psql "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -v ON_ERROR_STOP=1 -f docs/sql/05_database_validation.sql
```

It only reads data. The sample business flow script is for a disposable demo database; it creates fake business transactions and must not be executed against a production database.

---

## 5. Existing Database Migration (Embedded PostgreSQL to Standalone PostgreSQL)

This section is for an installation that already has business data in the application's embedded PostgreSQL and must move that data to the standalone PostgreSQL server without loss. If you are starting with a new, empty production database, skip this section and use the Flyway path in Section 4.

### 5.1 What "embedded PostgreSQL" means in this application

The backend includes `io.zonky.test:embedded-postgres:2.0.7`. When the default profile runs, `EmbeddedPostgresConfig` starts a real PostgreSQL 14.10 server:

| Property | Development value |
|---|---|
| Host | `127.0.0.1` |
| Port | `54329` |
| Database | `item_management_db` |
| User | `postgres` |
| Password | `postgres` |
| Authentication | trust, localhost only |
| Data directory | `./.pgdata` by default, relative to the backend working directory |
| Clean on start | `false` (data persists) |

In this repository the data directory is `backend/.pgdata` (on the current development machine: `D:\vishrut\Clone\ItemManagementSystem\backend\.pgdata`). It contains the real database files. The PostgreSQL server executables are extracted to a temporary directory and are **not** a full PostgreSQL installation: there is no `psql`, `pg_dump` or `pg_restore` in that extracted `bin` directory.

The embedded server is a development convenience. Production uses the standalone PostgreSQL installed in Section 3.

### 5.2 Migration principles

1. **Do not copy the `.pgdata` directory into PostgreSQL 16's data directory.** The embedded server is major version 14; PostgreSQL 16 cannot start on 14 data files. Use a logical dump and restore.
2. **Use a `pg_dump` client that is the same version or newer than the source server.** A PostgreSQL 16 `pg_dump` can dump a 14.10 server.
3. **Stop application writes during the final dump.** A dump is transaction-consistent, but writes made after the dump starts are not included. Put the system into a maintenance window, confirm no users are working, take the dump, then cut over.
4. **Keep the old `.pgdata` untouched** until the new database has been verified and the backup/restore process has been tested.
5. **Migrate the Flyway history too.** A full dump includes `flyway_schema_history`, so the new backend sees V1-V3 and does not re-run migrations.
6. **One database.** The target is still `item_management_db`; every store and branch stays in that one database.

### 5.3 Preparation

On the source machine:

- Confirm the existing application version is the same version you will deploy (`1.0.0`). Do not mix a data migration with an application upgrade.
- Install PostgreSQL **client tools** 16 if they are not already present. On Ubuntu: `sudo apt install -y postgresql-client-16`. On Windows: install the PostgreSQL 16 client or the full EDB package, then use `C:\Program Files\PostgreSQL\16\bin\pg_dump.exe`.
- Confirm the data directory and version:

```bash
cat backend/.pgdata/PG_VERSION
# expected: 14
```

- Confirm no process is using port 54329 except the application:

```bash
sudo ss -ltnp | grep 54329
```

On Windows:

```powershell
Get-NetTCPConnection -LocalPort 54329
```

On the target server:

- PostgreSQL 16 is installed and running (Section 3).
- Script 01 has been run, creating `ims_app` and an **empty** `item_management_db`.
- Do **not** run scripts 02-04 on the target before the restore. The dump contains the schema.
- The backend is not started yet, or it is stopped.

### 5.4 Stop the embedded server safely

The embedded PostgreSQL server is owned by the backend process. `EmbeddedPostgresConfig` starts it when the application starts and closes it in `@PreDestroy` when the application shuts down. Whenever you need to stop it — before archiving `.pgdata`, before cutover, or before moving the machine — use this sequence:

1. Stop the backend gracefully:
   - `bootRun` in a terminal: press `Ctrl+C` once and wait for the process to exit;
   - a manually started JAR: send SIGTERM (Linux) or close it from Task Manager/`Stop-Process` (Windows), then wait;
   - a systemd/NSSM service: use the service stop command, not a hard kill.
2. Wait for the JVM to exit. The embedded server performs a normal shutdown as part of `@PreDestroy`.
3. Confirm port 54329 has no listener:

```bash
sudo ss -ltnp | grep 54329
```

Windows:

```powershell
Get-NetTCPConnection -LocalPort 54329 -ErrorAction SilentlyContinue
```

4. Confirm no `postgres`/`postgres.exe` process is still using the `.pgdata` directory. Do not delete, move or copy `.pgdata` while the server is running.

If the application is stuck and cannot stop cleanly, use the `pg_ctl` binary from the extracted embedded PostgreSQL `bin` directory:

```bash
pg_ctl -D backend/.pgdata stop -m fast
```

The extracted binary directory is temporary; if it has already been removed, do not search for and kill arbitrary `postgres` processes. Stop the Java process that owns the server, then verify the port is closed. Never use `kill -9` on PostgreSQL as a routine stop.

An archive of `.pgdata` is a useful rollback copy, but it is **not** the migration mechanism. Stop the server first, then archive (run from the repository root):

```bash
sudo mkdir -p /var/backups/ims
sudo tar -czf /var/backups/ims/embedded-pgdata-pre-migration-$(date +%F).tar.gz -C backend .pgdata
```

Keep this archive until the standalone database has passed verification. Do not copy the directory into a PostgreSQL 16 data directory.

For the dump in Section 5.5 the application stays running; stop it only when you archive `.pgdata` or cut over.

### 5.5 Take the dump

1. Announce a maintenance window. Stop store users from working. The platform console and store SPA should not be used during the final dump.
2. Start the existing application with the embedded database, if it is not already running. Use the existing start method (for example `./gradlew bootRun` from `backend`, or the existing JAR with the default profile). Do not set the `postgres` profile for this step.
3. Wait until the application is healthy and the login page responds.
4. Take the dump from a second terminal. The embedded server uses trust authentication, so the password is not checked, but supplying it avoids prompts:

```bash
export PGPASSWORD=postgres

pg_dump \
  --host=127.0.0.1 \
  --port=54329 \
  --username=postgres \
  --dbname=item_management_db \
  --format=custom \
  --compress=9 \
  --no-owner \
  --no-privileges \
  --verbose \
  --file=/tmp/item_management_db_embedded_$(date +%F_%H%M%S).dump

unset PGPASSWORD
```

On Windows PowerShell:

```powershell
$env:PGPASSWORD = "postgres"
& "C:\Program Files\PostgreSQL\16\bin\pg_dump.exe" `
  --host=127.0.0.1 --port=54329 --username=postgres --dbname=item_management_db `
  --format=custom --compress=9 --no-owner --no-privileges --verbose `
  --file="C:\ims-backup\item_management_db_embedded.dump"
Remove-Item Env:PGPASSWORD
```

The `pg_dump` warning that the server version is 14.10 and the client is 16 is expected and safe. A dump made by a newer client is supported; a dump made by an older client against a newer server is not.

5. Stop using the old application. Do not create, edit, cancel or receive anything after the dump. If someone writes after the dump, take a new dump before cutover.

### 5.6 Verify the dump before moving it

```bash
DUMP=/tmp/item_management_db_embedded_YYYY-MM-DD_HHMMSS.dump

pg_restore --list "$DUMP" | head -40
pg_restore --list "$DUMP" | grep -c 'TABLE DATA'
sha256sum "$DUMP"
```

Expected:

- the listing shows the application tables and `flyway_schema_history`;
- the `TABLE DATA` count is 24 (23 application tables plus Flyway history);
- `sha256sum` prints a checksum. Record it.

Copy the dump to the target server over SSH:

```bash
scp "$DUMP" deploy@[SERVER IP]:/tmp/
```

Verify the checksum on the target:

```bash
sha256sum /tmp/item_management_db_embedded_YYYY-MM-DD_HHMMSS.dump
```

### 5.7 Restore into the standalone database

Connect as `ims_app` to the empty `item_management_db`. Use `--single-transaction` so a failure rolls back the whole restore instead of leaving a partial database:

```bash
pg_restore \
  --host=127.0.0.1 \
  --port=5432 \
  --username=ims_app \
  --dbname=item_management_db \
  --no-owner \
  --no-privileges \
  --single-transaction \
  --exit-on-error \
  --verbose \
  /tmp/item_management_db_embedded_YYYY-MM-DD_HHMMSS.dump
```

Enter the `ims_app` password when prompted. If you use a `.pgpass` file, set `PGPASSFILE`.

Why these flags:

- `--no-owner` and `--no-privileges`: the objects are created and owned by the connecting role `ims_app`, which is the correct production owner. The old `postgres` owner is not carried over.
- `--single-transaction`: all-or-nothing.
- `--exit-on-error`: stop at the first error; do not hide a partial restore.

If the restore reports that a schema or object already exists, the target was not empty. Stop and investigate. Do not use `--clean` on a database that may contain production data.

### 5.8 Verify the restored database

```sql
-- Flyway history must be present and successful
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;

-- Reference data
SELECT count(*) AS permissions FROM permissions;                       -- 28
SELECT count(*) AS platform_roles FROM roles WHERE store_id IS NULL;    -- 1
SELECT count(*) AS platform_grants
FROM role_permissions rp
JOIN roles r ON r.id = rp.role_id
WHERE r.store_id IS NULL AND r.code = 'PLATFORM_ADMIN';                 -- 3

-- Business row counts: compare with the source, not with a fixed number
SELECT 'stores' AS object, count(*) FROM stores
UNION ALL SELECT 'users', count(*) FROM users
UNION ALL SELECT 'items', count(*) FROM items
UNION ALL SELECT 'purchases', count(*) FROM purchases
UNION ALL SELECT 'sales', count(*) FROM sales
UNION ALL SELECT 'inventory_transactions', count(*) FROM inventory_transactions
UNION ALL SELECT 'audit_logs', count(*) FROM audit_logs;
```

Run the same count query against the source on port 54329 and compare every number:

```bash
psql "host=127.0.0.1 port=54329 dbname=item_management_db user=postgres" -c "
SELECT 'stores' AS object, count(*) FROM stores
UNION ALL SELECT 'users', count(*) FROM users
UNION ALL SELECT 'items', count(*) FROM items
UNION ALL SELECT 'purchases', count(*) FROM purchases
UNION ALL SELECT 'sales', count(*) FROM sales
UNION ALL SELECT 'inventory_transactions', count(*) FROM inventory_transactions
UNION ALL SELECT 'audit_logs', count(*) FROM audit_logs;"
```

Then verify financial and stock totals, not just row counts:

```sql
SELECT sum(grand_total) AS sales_total FROM sales WHERE status = 'COMPLETED';
SELECT sum(grand_total) AS purchase_total FROM purchases WHERE status = 'COMPLETED';
SELECT sum(current_stock) AS stock_units FROM branch_inventory;
```

Compare the source and target values. If they differ, do not proceed.

### 5.9 Cut over the backend

1. Stop the old backend (the one using embedded PostgreSQL).
2. Confirm port 8080 is free and port 54329 is closed.
3. Create `/opt/ims/config/ims.env` from Section 8 with:

```
SPRING_PROFILES_ACTIVE=postgres
EMBEDDED_POSTGRES=false
DB_URL=jdbc:postgresql://127.0.0.1:5432/item_management_db
DB_USERNAME=ims_app
DB_PASSWORD=[DB PASSWORD]
DB_POOL_SIZE=15
SPRING_FLYWAY_ENABLED=true
SEED_DEMO=false
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080
JWT_SECRET=[PRODUCTION JWT SECRET]
JWT_EXPIRATION_MS=43200000
CORS_ORIGINS=https://admin.mydomain.com,https://app.mydomain.com,https://api.mydomain.com
```

4. Start the new backend (Section 12).
5. Confirm the log says Flyway validated the existing history and did not run new migrations. You should see V1, V2 and V3 as already applied, not "Migrating schema".
6. Log in with an existing store account and the existing platform account. Verify data.

### 5.10 Rollback

The old `.pgdata` is still on disk and unchanged. To roll back:

1. Stop the new backend.
2. Start the old backend with the default profile (embedded PostgreSQL) and the old JAR.
3. Do not point the old backend at the new PostgreSQL database and do not enable embedded PostgreSQL in the production environment file.

Rollback is only lossless for data written **before** the cutover. If users wrote data on the new system after cutover, that data is only in the new database. In that case, take a new dump from the new database and restore it according to this procedure instead of rolling back to the old one.

### 5.11 Decommission the embedded database

Only after the new deployment has passed Section 15 and you have kept it stable for an agreed period:

```bash
# On the old host, stop the old application first
tar -czf /var/backups/ims/embedded-pgdata-final-$(date +%F).tar.gz -C backend .pgdata
sha256sum /var/backups/ims/embedded-pgdata-final-*.tar.gz
```

Keep the archive according to your retention policy. Do not delete the `.pgdata` directory until the archive has been verified and the new database has a tested backup. The extracted embedded server binaries in the temporary directory are disposable; the `.pgdata` directory is the data.

### 5.12 If the old database contains demo data

If the source was seeded with `SEED_DEMO=true`, the dump contains the demo store `ABC001`, its demo users and demo transactions. A migration copies them. They are real rows in the target database after the restore. For a production system:

- The correct prevention is to set `SEED_DEMO=false` **before** first start on a clean database, so the demo data is never created.
- If demo data was already created in a database you are migrating, do not delete business history casually. The application does not provide a "delete store" operation. Deactivate the demo store in the platform console and keep it isolated, or start production on a fresh database with `SEED_DEMO=false` and onboard real stores.
- Do not run `06_sample_business_flow.sql` on production. It is for disposable demos only.

### 5.13 Migration checklist

- [ ] Old and new application versions are the same (1.0.0).
- [ ] Maintenance window announced; no users writing.
- [ ] Source `.pgdata/PG_VERSION` is 14.
- [ ] `pg_dump` client is 16.
- [ ] Dump created with `--format=custom`, `--no-owner`, `--no-privileges`.
- [ ] `pg_restore --list` shows 24 `TABLE DATA` entries and `flyway_schema_history`.
- [ ] Dump checksum verified after copying.
- [ ] Target database was empty before restore.
- [ ] Restore used `--single-transaction --exit-on-error`.
- [ ] Flyway history V1-V3 is present and successful.
- [ ] Row counts and financial/stock totals match the source.
- [ ] Production env uses the `postgres` profile and `EMBEDDED_POSTGRES=false`.
- [ ] `SEED_DEMO=false`.
- [ ] Old `.pgdata` archived and retained.
- [ ] Rollback procedure understood and tested on a non-production copy.

---
## 6. Frontend Build

### 6.1 What the frontend is

- React 19, TypeScript, Vite 8, Tailwind CSS 4, React Router 7, Zustand, Axios.
- One single-page application serves both `admin.mydomain.com` and `app.mydomain.com`.
- There is **no separate admin build**. The same `dist/` is deployed to both hostnames. After login, the API response determines whether the user is a platform administrator; the SPA routes a platform administrator to `/platform/stores`. Store users see their store workspace.
- The API base URL is the relative path `/api` in `src/lib/api.ts`. There is no `VITE_API_URL` setting in this project. The SPA therefore requires a same-origin `/api` proxy, which Section 9 configures on both the admin and app hostnames.

### 6.2 Build prerequisites

- Node.js 22 LTS (22.12 or newer) and the bundled npm.
- Network access to the npm registry and to `https://cdn.sheetjs.com/` for the `xlsx` dependency in `package.json`.
- At least 2 GB of free RAM for the TypeScript and Vite build.
- `package-lock.json` is present in the repository root. Use `npm ci`, not `npm install`, so the build uses the locked dependency versions.

Check:

```bash
node --version
npm --version
```

### 6.3 Build on Ubuntu

From the repository root (the directory containing `package.json`):

```bash
npm ci
npm run build
```

The actual `package.json` build script is:

```json
"build": "tsc -b && vite build"
```

TypeScript checks the project first; Vite then writes the production bundle. Do not replace this with a different command.

If the build runs out of memory on a small machine:

```bash
NODE_OPTIONS=--max-old-space-size=2048 npm run build
```

Expected output: a `dist/` directory containing at least:

```
dist/
  index.html
  assets/
    index-<hash>.js
    index-<hash>.css
    ...other hashed assets...
```

The hash in filenames is normal and supports long-lived caching.

### 6.4 Build on Windows

In PowerShell at the repository root:

```powershell
npm ci
npm run build
```

The output is the same `dist\` directory.

### 6.5 Verify the build locally (optional)

```bash
npm run preview
```

Open the printed local URL. The login page should render. API calls will fail unless a backend is running and proxied; that is expected. `npm run preview` is a local check only. Production is served by NGINX.

### 6.6 Upload the frontend

Create the web directory first (Section 8), then copy the **contents** of `dist/`, not the `dist` folder itself:

```bash
rsync -avz --delete dist/ deploy@[SERVER IP]:/tmp/ims-web/
```

On the server:

```bash
sudo rsync -a --delete /tmp/ims-web/ /opt/ims/web/
sudo chown -R root:www-data /opt/ims/web
sudo find /opt/ims/web -type d -exec chmod 755 {} \;
sudo find /opt/ims/web -type f -exec chmod 644 {} \;
```

`--delete` removes stale hashed assets from the previous release. It only touches `/opt/ims/web`, which is a build-output directory. Do not point it at a directory containing source or user files.

On Windows, upload the contents of `dist\` with WinSCP, SCP, or `scp -r .\dist\* deploy@[SERVER IP]:/tmp/ims-web/`, then run the same server-side copy.

### 6.7 What not to upload

Do not upload:

- `node_modules/`;
- the `src/` directory;
- `package.json` or `package-lock.json` (they are build inputs, not runtime files);
- `.env` files or secrets;
- the repository `.git/` directory.

Only `dist/` contents belong in `/opt/ims/web`.

### 6.8 Caching rules

NGINX in Section 9 serves:

- `index.html` with `Cache-Control: no-store`, so a new release is picked up immediately;
- hashed files under `/assets/` with a long cache and `immutable`, because their names change when their content changes.

If users see an old version after a deployment, confirm `index.html` is not cached and that the browser is not holding an old service worker. This application does not register a service worker.

### 6.9 API URL summary

| Question | Answer for this application |
|---|---|
| Where is the API URL configured? | `src/lib/api.ts`, `baseURL: '/api'` |
| Is there a production `.env` API URL? | No |
| Can the SPA call `https://api.mydomain.com` directly without a code change? | No; it would require changing the base URL and configuring CORS for browser requests |
| How does the SPA reach the API in production? | NGINX on `admin` and `app` proxies `/api/` to `127.0.0.1:8080` |
| What is `api.mydomain.com` for? | Direct API clients, health checks, integrations, and diagnostics |

---

## 7. Backend Build

### 7.1 Actual build system: Gradle, not Maven

The backend is a Gradle project:

- `backend/build.gradle`
- `backend/settings.gradle`
- `backend/gradlew` and `backend/gradlew.bat`
- `backend/gradle/wrapper/gradle-wrapper.properties` pins **Gradle 8.10.2**
- project version **1.0.0**
- Java toolchain **17**
- Spring Boot **3.3.5**

There is **no `pom.xml`**. Maven is not used. The correct production build command is `./gradlew clean bootJar`, not `mvn package`. Do not install Maven for this project.

### 7.2 Install Java 17 (Eclipse Temurin) on Ubuntu

Use the official Adoptium repository:

```bash
sudo apt install -y apt-transport-https gnupg curl
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-17-jdk
```

Verify:

```bash
java -version
javac -version
readlink -f "$(command -v java)"
```

Expected: Java 17 from Eclipse Adoptium. The runtime only needs the JDK/JRE 17; the build needs the JDK.

If you prefer a manual installation, download the Temurin 17 Linux x64 `.tar.gz` from the official page in Section 1.3, extract it under `/opt`, and set `JAVA_HOME`. Do not mix Java 8 or 21 with this build; the toolchain requests 17.

### 7.3 Build the JAR on Ubuntu

From the repository root:

```bash
cd backend
chmod +x gradlew
./gradlew --version
./gradlew clean bootJar -x test
```

- The wrapper downloads Gradle 8.10.2 on first use. The server needs HTTPS access to the Gradle distribution service. If your network blocks it, download the Gradle 8.10.2 binary distribution from the official Gradle site and run the build with that Gradle, but keep the wrapper files in the project.
- `-x test` skips the integration test class for the production build. The tests start an embedded PostgreSQL server and are not required to produce the JAR. See Section 7.6 if you want to run them.

Expected output:

```bash
ls -lh backend/build/libs/
# item-management-backend-1.0.0.jar
# item-management-backend-1.0.0-plain.jar
```

The executable Spring Boot JAR is:

```
backend/build/libs/item-management-backend-1.0.0.jar
```

The `-plain.jar` is not executable as a Spring Boot application. Do not deploy it.

### 7.4 Verify the JAR

```bash
unzip -p backend/build/libs/item-management-backend-1.0.0.jar META-INF/MANIFEST.MF
```

Expected entries include:

```
Main-Class: org.springframework.boot.loader.launch.JarLauncher
Start-Class: com.itemmanagement.ItemManagementApplication
```

The exact launcher package depends on Spring Boot 3.3.x, but `Main-Class` must be a Spring Boot launcher, not the application class directly.

```bash
sha256sum backend/build/libs/item-management-backend-1.0.0.jar
```

Record the checksum and compare it after upload.

### 7.5 Copy the JAR to the server

```bash
scp backend/build/libs/item-management-backend-1.0.0.jar deploy@[SERVER IP]:/tmp/
```

On the server:

```bash
sudo install -o ims -g ims -m 640 \
  /tmp/item-management-backend-1.0.0.jar \
  /opt/ims/app/item-management-backend-1.0.0.jar

sha256sum /opt/ims/app/item-management-backend-1.0.0.jar
```

The service runs as `ims`; mode `640` with owner `ims` is sufficient. The file does not need to be executable.

### 7.6 Running the tests (optional)

The test class is `SystemInvariantIntegrationTest`. It uses its own embedded PostgreSQL on port **54330**, data directory `build/pgdata-test`, clean-on-start, and `seed-demo=false`. It does not touch the production database.

```bash
cd backend
./gradlew test
```

The first run downloads embedded PostgreSQL 14.10 binaries and may take several minutes. These tests are useful before a release, but they are not part of the production runtime. Never point the tests at the production database.

### 7.7 Build on Windows

In PowerShell, from the repository root:

```powershell
cd backend
.\gradlew.bat clean bootJar -x test
```

Output:

```
backend\build\libs\item-management-backend-1.0.0.jar
```

Install Temurin 17 for Windows from the official Adoptium page if it is not already installed, and set `JAVA_HOME` to the JDK directory.

### 7.8 Backend configuration is external

Do not edit `application.yml` for production. The production values are supplied through environment variables in `/opt/ims/config/ims.env` (Section 8). This keeps secrets out of the JAR and out of source control, and it means the same JAR can be promoted between environments.

The critical production values are:

```
SPRING_PROFILES_ACTIVE=postgres
EMBEDDED_POSTGRES=false
SEED_DEMO=false
DB_URL=jdbc:postgresql://127.0.0.1:5432/item_management_db
DB_USERNAME=ims_app
DB_PASSWORD=...
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080
JWT_SECRET=...
```

If `SPRING_PROFILES_ACTIVE=postgres` is missing, the datasource settings from the `postgres` profile are not applied. With `EMBEDDED_POSTGRES=false`, the application then falls back to the built-in defaults (`jdbc:postgresql://localhost:5432/item_management_db` as user `postgres`), and with no `EMBEDDED_POSTGRES` setting at all it starts its own embedded PostgreSQL on port 54329. Either way it is not using your production configuration. If `SEED_DEMO` is missing, its default is `true`. Both mistakes are serious in production; Section 15 checks them.

---

## 8. Server Folder Configuration

### 8.1 Create the service user

```bash
sudo useradd --system --create-home --home-dir /home/ims \
  --shell /usr/sbin/nologin ims
sudo passwd -l ims
```

The application runs as `ims`. It does not need `sudo`, a login shell, or membership in the `sudo` group. Do not run the backend as `root`.

### 8.2 Create the directory layout

```bash
sudo mkdir -p /opt/ims/app
sudo mkdir -p /opt/ims/web
sudo mkdir -p /opt/ims/config
sudo mkdir -p /opt/ims/logs
sudo mkdir -p /opt/ims/backups

sudo chown -R ims:ims /opt/ims
sudo chmod 750 /opt/ims /opt/ims/app /opt/ims/config /opt/ims/logs /opt/ims/backups
sudo chmod 755 /opt/ims/web
```

Final layout:

```
/opt/ims/
├── app/
│   └── item-management-backend-1.0.0.jar     ims:ims       640
├── web/
│   ├── index.html                            root:www-data 644
│   └── assets/...                            root:www-data 644
├── config/
│   ├── ims.env                               root:ims      640
│   └── pgpass                                ims:ims       600
├── logs/
│   └── ims-backend.log                       ims:ims       640
└── backups/
    └── item_management_db_YYYY-MM-DD.dump    ims:ims       600
```

`/opt/ims/web` must be readable by the NGINX worker user (`www-data`). The other directories are private to `ims` and root. Do not put `.pgdata`, source code, `node_modules`, or the repository in `/opt/ims/web`.

### 8.3 Create the environment file

```bash
sudo touch /opt/ims/config/ims.env
sudo chown root:ims /opt/ims/config/ims.env
sudo chmod 640 /opt/ims/config/ims.env
sudo nano /opt/ims/config/ims.env
```

Contents (replace every placeholder):

```ini
# Spring Boot runtime
SPRING_PROFILES_ACTIVE=postgres
EMBEDDED_POSTGRES=false
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080

# PostgreSQL
DB_URL=jdbc:postgresql://127.0.0.1:5432/item_management_db
DB_USERNAME=ims_app
DB_PASSWORD=[DB PASSWORD]
DB_POOL_SIZE=15
SPRING_FLYWAY_ENABLED=true
SEED_DEMO=false

# Security
JWT_SECRET=[PRODUCTION JWT SECRET]
JWT_EXPIRATION_MS=43200000
CORS_ORIGINS=https://admin.mydomain.com,https://app.mydomain.com,https://api.mydomain.com

# Logging (optional; journald also captures stdout)
LOGGING_FILE_NAME=/opt/ims/logs/ims-backend.log
```

Generate the secrets with hex output so the file needs no quoting:

```bash
openssl rand -hex 24     # DB_PASSWORD: 48 hex characters
openssl rand -hex 48     # JWT_SECRET: 96 hex characters (well above the 256-bit HS256 minimum)
```

Rules:

- `DB_PASSWORD` must match the password given to `ims_app` in script 01.
- `JWT_SECRET` must be unique to production and must never be the development default `ims-dev-secret-key-change-me-...`.
- Changing `JWT_SECRET` invalidates every existing session; users sign in again. That is the intended effect if a secret is compromised.
- `SEED_DEMO=false` is mandatory. The platform user is still created by `DataSeeder` on first start; Section 11 handles it.
- `SPRING_FLYWAY_ENABLED=true` is for the Flyway path. On the manual SQL path, set it to `false`.
- Do not put spaces around `=` and do not wrap values in quotes unless they contain spaces. Hex/base64 secrets are safe unquoted.
- If a value contains `#`, quote it or avoid it; systemd may treat it as a comment.

Restrict access:

```bash
sudo chown root:ims /opt/ims/config/ims.env
sudo chmod 640 /opt/ims/config/ims.env
sudo -u ims test -r /opt/ims/config/ims.env && echo "ims can read the env file"
```

### 8.4 Create the backup password file

`pg_dump` should read the password from a file, not from a command line or an interactive prompt in cron:

```bash
sudo touch /opt/ims/config/pgpass
sudo chown ims:ims /opt/ims/config/pgpass
sudo chmod 600 /opt/ims/config/pgpass
```

Format (one line, no spaces):

```
127.0.0.1:5432:item_management_db:ims_app:[DB PASSWORD]
```

Test:

```bash
sudo -u ims env PGPASSFILE=/opt/ims/config/pgpass \
  psql "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -c "SELECT current_user, current_database();"
```

The application itself does **not** use this file; it uses `DB_PASSWORD` from `ims.env`. The `.pgpass` file is only for backups and manual administration.

### 8.5 Create the systemd service unit

Create `/etc/systemd/system/ims-backend.service`:

```ini
[Unit]
Description=Item Management System backend
After=network-online.target postgresql.service
Wants=network-online.target
Requires=postgresql.service

[Service]
Type=simple
User=ims
Group=ims
WorkingDirectory=/opt/ims/app
EnvironmentFile=/opt/ims/config/ims.env
ExecStart=/usr/bin/java -Xms256m -Xmx1024m -jar /opt/ims/app/item-management-backend-1.0.0.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
TimeoutStopSec=30
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true
ReadWritePaths=/opt/ims/logs

[Install]
WantedBy=multi-user.target
```

Notes:

- `EnvironmentFile` must be readable by the `ims` user. The `root:ims` / `640` ownership in Section 8.3 satisfies this.
- `-Xms256m -Xmx1024m` is appropriate for a 4 GB server. On an 8 GB server you may raise `-Xmx` to `2048m` after measuring memory use.
- `SuccessExitStatus=143` treats the normal SIGTERM stop as success, so `systemctl stop` does not record a failure.
- `Requires=postgresql.service` makes the database a hard dependency. If PostgreSQL is stopped, systemd also stops the backend rather than leaving it in a broken retry loop.
- `ProtectSystem=full` makes the operating-system directories read-only to the service. `/opt/ims/logs` remains writable through `ReadWritePaths`.
- If you change `LOGGING_FILE_NAME`, keep it under `/opt/ims/logs` or add the new path to `ReadWritePaths`.

Do not start the service yet if you have not completed the database steps. It is started in Section 11 for the bootstrap and in Section 12 for normal operation.

### 8.6 Logs

The backend writes to standard output, which systemd captures in the journal. View it with:

```bash
sudo journalctl -u ims-backend -f
```

If `LOGGING_FILE_NAME` is set, Spring Boot also writes to `/opt/ims/logs/ims-backend.log`. Add log rotation so the disk does not fill:

```bash
sudo tee /etc/logrotate.d/ims-backend >/dev/null <<'EOF'
/opt/ims/logs/ims-backend.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    su ims ims
}
EOF
sudo logrotate -d /etc/logrotate.d/ims-backend
```

`copytruncate` avoids restarting the backend. The journal has its own rotation controlled by systemd.

### 8.7 Directory permissions summary

| Path | Owner | Mode | Why |
|---|---|---|---|
| `/opt/ims` | `ims:ims` | 750 | Private application root |
| `/opt/ims/app` | `ims:ims` | 750 | JAR, private |
| `/opt/ims/config` | `ims:ims` | 750 | Secrets, private |
| `/opt/ims/config/ims.env` | `root:ims` | 640 | Readable by service, writable only by root |
| `/opt/ims/config/pgpass` | `ims:ims` | 600 | Backup credential, private |
| `/opt/ims/logs` | `ims:ims` | 750 | Writable by service |
| `/opt/ims/backups` | `ims:ims` | 750 | Dumps, private |
| `/opt/ims/web` | `root:www-data` | 755 | Readable by NGINX |
| `/opt/ims/web/*` | `root:www-data` | 644/755 | Static files |

Change `www-data` if your NGINX package uses a different worker user. Check with:

```bash
grep '^user' /etc/nginx/nginx.conf
```

---

## 9. NGINX Configuration

### 9.1 Install and prepare

```bash
sudo apt install -y nginx
sudo systemctl enable --now nginx
sudo rm -f /etc/nginx/sites-enabled/default
```

### 9.2 Design

- `admin.mydomain.com` and `app.mydomain.com`: serve the SPA from `/opt/ims/web`; proxy `/api/` to `127.0.0.1:8080`.
- `api.mydomain.com`: proxy every path to `127.0.0.1:8080`; do not serve the SPA.
- HTTP is configured first so Certbot can validate the domains. Section 10 adds TLS and redirects.
- NGINX is the only public entry point. Spring Boot binds to `127.0.0.1:8080`; PostgreSQL binds to `127.0.0.1:5432`.

**Security note:** both `admin` and `app` serve the same SPA and the same API. The hostname is not an authorization boundary. A store user who signs in at `admin.mydomain.com` with a valid store code can use the store workspace, and a platform administrator who signs in at `app.mydomain.com` is redirected to `/platform/stores`. Authorization is enforced by the backend from the JWT, roles and permissions, not by the domain name. Do not rely on the subdomain to separate users.

### 9.3 Create the HTTP site

```bash
sudo tee /etc/nginx/sites-available/ims >/dev/null <<'EOF'
upstream ims_backend {
    server 127.0.0.1:8080;
    keepalive 32;
}

# ---------------------------------------------------------------
# admin.mydomain.com - Platform Owner / Super Admin
# ---------------------------------------------------------------
server {
    listen 80;
    listen [::]:80;
    server_name admin.mydomain.com;

    root /opt/ims/web;
    index index.html;
    server_tokens off;
    client_max_body_size 10m;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location /api/ {
        proxy_pass http://ims_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }

    location /assets/ {
        try_files $uri =404;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    location = /index.html {
        add_header Cache-Control "no-store";
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}

# ---------------------------------------------------------------
# app.mydomain.com - Store login
# ---------------------------------------------------------------
server {
    listen 80;
    listen [::]:80;
    server_name app.mydomain.com;

    root /opt/ims/web;
    index index.html;
    server_tokens off;
    client_max_body_size 10m;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location /api/ {
        proxy_pass http://ims_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }

    location /assets/ {
        try_files $uri =404;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    location = /index.html {
        add_header Cache-Control "no-store";
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}

# ---------------------------------------------------------------
# api.mydomain.com - Backend API only
# ---------------------------------------------------------------
server {
    listen 80;
    listen [::]:80;
    server_name api.mydomain.com;

    server_tokens off;
    client_max_body_size 10m;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location / {
        proxy_pass http://ims_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }
}
EOF
```

Enable and test:

```bash
sudo ln -sf /etc/nginx/sites-available/ims /etc/nginx/sites-enabled/ims
sudo nginx -t
sudo systemctl reload nginx
```

`nginx -t` must print `syntax is ok` and `test is successful`. Do not reload if it fails.

### 9.4 Test before DNS or TLS

```bash
curl -I -H "Host: app.mydomain.com" http://127.0.0.1/
curl -I -H "Host: admin.mydomain.com" http://127.0.0.1/
curl -i -H "Host: api.mydomain.com" http://127.0.0.1/api/auth/me
```

Expected:

- the first two return the SPA `index.html` (HTTP 200) once `/opt/ims/web` is populated;
- the API request returns `401 Unauthorized` with a JSON body once the backend is running. Before Section 12 it may return `502 Bad Gateway`; that is expected while the backend is stopped.

### 9.5 Why `/api/` is proxied on the SPA hostnames

The browser requests `https://app.mydomain.com/api/auth/login`, not `https://api.mydomain.com/api/auth/login`. NGINX matches `location /api/` and forwards the request to `http://127.0.0.1:8080/api/auth/login`. The `Host` header sent to Spring Boot is the public hostname, but Spring Security does not depend on it. No CORS preflight is involved because the browser sees a same-origin request.

`api.mydomain.com` proxies all paths so an external client can call `https://api.mydomain.com/api/...` directly. The backend still requires a JWT for every path except login and store registration. CORS applies only to browser clients that call the API from a different origin; configure `CORS_ORIGINS` if you add such a client.

### 9.6 Common NGINX mistakes

| Mistake | Symptom | Correction |
|---|---|---|
| `proxy_pass http://ims_backend/;` with a trailing slash | `/api/...` is rewritten and may become `/...` | Use no URI part: `proxy_pass http://ims_backend;` |
| Missing `try_files ... /index.html` | Deep links such as `/items` return 404 | Keep the SPA fallback in `location /` |
| Serving the SPA from `/var/www/html` | Blank page or old files | `root /opt/ims/web;` |
| `root /opt/ims/web/dist;` | 404 | Upload the **contents** of `dist/` to `/opt/ims/web` |
| Port 8080 exposed publicly | Security exposure | Bind Spring Boot to `127.0.0.1` and only proxy through NGINX |
| `location /api` without a trailing slash | Prefix match still works, but be consistent | Use `location /api/` |
| Only configuring `admin` | Store users cannot reach the API | Configure both `admin` and `app` with the `/api/` proxy |

### 9.7 Do not add a separate admin build or path

There is no `/admin` route in the SPA and no separate admin bundle. The platform console is `/platform/stores`, reached after a platform login. Do not rewrite `/admin` to a different directory; it would produce a 404. Use the `admin.mydomain.com` hostname if you want a distinct URL for the platform team, but serve the same `dist/` and the same API proxy.

---
## 10. Domain and SSL

### 10.1 DNS records

Create these records at your domain registrar or DNS provider:

| Type | Host | Value | TTL |
|---|---|---|---|
| A | `admin` | `[SERVER IP]` | 300 during setup, 3600 later |
| A | `app` | `[SERVER IP]` | 300 during setup |
| A | `api` | `[SERVER IP]` | 300 during setup |
| AAAA | the same three | IPv6 address, only if configured | 300 |

Verify from the server:

```bash
dig +short admin.mydomain.com
dig +short app.mydomain.com
dig +short api.mydomain.com
```

All three must return the server's public IP. DNS propagation can take minutes to hours. Do not request a certificate until all three resolve correctly.

If you use Cloudflare, set the records to **DNS only** (grey cloud) for the initial certificate issuance, or use a DNS-01 challenge. A proxied orange-cloud record can prevent the HTTP-01 challenge from reaching your server. You can enable proxying after the certificate is issued, but keep the SSL mode at Full (strict).

### 10.2 Install Certbot

Follow the official instructions for your OS, or on Ubuntu:

```bash
sudo apt update
sudo apt install -y certbot python3-certbot-nginx
certbot --version
```

The NGINX plugin obtains a certificate, edits the NGINX configuration to add TLS, and installs a renewal timer.

### 10.3 Obtain one certificate for all three hostnames

`admin.mydomain.com` is the first name, so it becomes the certificate's primary name and the directory name under `/etc/letsencrypt/live/`. The certificate includes all three names as Subject Alternative Names.

```bash
sudo certbot --nginx \
  -d admin.mydomain.com \
  -d app.mydomain.com \
  -d api.mydomain.com \
  --redirect \
  --agree-tos \
  --no-eff-email \
  -m [MY EMAIL]
```

Certbot will:

- validate each hostname over HTTP port 80;
- write the certificate and key under `/etc/letsencrypt/live/admin.mydomain.com/`;
- add `listen 443 ssl` server blocks;
- add an HTTP-to-HTTPS redirect;
- install or enable the renewal timer.

If validation fails, check DNS, port 80, the UFW rule, and that NGINX is running. Do not retry repeatedly in a way that hits Let's Encrypt rate limits; fix the cause first.

### 10.4 Final NGINX configuration with TLS

After Certbot succeeds, the effective configuration should be equivalent to the following. Certbot may format it slightly differently and may add comments; the important parts are the certificate paths, the `options-ssl-nginx.conf` include, the redirect, and the same proxy/SPA rules from Section 9.

```nginx
upstream ims_backend {
    server 127.0.0.1:8080;
    keepalive 32;
}

# ---------- admin.mydomain.com ----------
server {
    listen 80;
    listen [::]:80;
    server_name admin.mydomain.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name admin.mydomain.com;

    ssl_certificate     /etc/letsencrypt/live/admin.mydomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/admin.mydomain.com/privkey.pem;
    include             /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam         /etc/letsencrypt/ssl-dhparams.pem;

    root /opt/ims/web;
    index index.html;
    server_tokens off;
    client_max_body_size 10m;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location /api/ {
        proxy_pass http://ims_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }

    location /assets/ {
        try_files $uri =404;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    location = /index.html {
        add_header Cache-Control "no-store";
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}

# ---------- app.mydomain.com ----------
server {
    listen 80;
    listen [::]:80;
    server_name app.mydomain.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name app.mydomain.com;

    ssl_certificate     /etc/letsencrypt/live/admin.mydomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/admin.mydomain.com/privkey.pem;
    include             /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam         /etc/letsencrypt/ssl-dhparams.pem;

    root /opt/ims/web;
    index index.html;
    server_tokens off;
    client_max_body_size 10m;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location /api/ {
        proxy_pass http://ims_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }

    location /assets/ {
        try_files $uri =404;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    location = /index.html {
        add_header Cache-Control "no-store";
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}

# ---------- api.mydomain.com ----------
server {
    listen 80;
    listen [::]:80;
    server_name api.mydomain.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name api.mydomain.com;

    ssl_certificate     /etc/letsencrypt/live/admin.mydomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/admin.mydomain.com/privkey.pem;
    include             /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam         /etc/letsencrypt/ssl-dhparams.pem;

    server_tokens off;
    client_max_body_size 10m;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location / {
        proxy_pass http://ims_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }
}
```

If you write this file yourself instead of letting Certbot edit the HTTP configuration, obtain the certificate first with:

```bash
sudo certbot certonly --nginx \
  -d admin.mydomain.com -d app.mydomain.com -d api.mydomain.com \
  --agree-tos --no-eff-email -m [MY EMAIL]
```

Then write the TLS configuration above and run `sudo nginx -t` before reloading.

### 10.5 Verify TLS

```bash
sudo nginx -t
sudo systemctl reload nginx

curl -I http://admin.mydomain.com
curl -I https://admin.mydomain.com
curl -I https://app.mydomain.com
curl -i https://api.mydomain.com/api/auth/me
```

Expected:

- HTTP returns `301` to HTTPS.
- HTTPS returns `200` for the SPA and `401` JSON for `/api/auth/me` once the backend is running.
- The certificate is valid for the requested hostname.

Inspect the certificate names:

```bash
sudo certbot certificates
```

Expected: one certificate with domains `admin.mydomain.com`, `app.mydomain.com`, `api.mydomain.com`, and an expiry date about 90 days out.

Test from an external network, not only from the server. A local `curl` can succeed even when DNS or the firewall is wrong for the public.

### 10.6 Renewal

Certbot installs a systemd timer. Check it:

```bash
systemctl list-timers --all | grep -i certbot
sudo certbot renew --dry-run
```

The dry run must succeed. Renewal is automatic; do not schedule a second renewal job. After a renewal, NGINX is reloaded by the deploy hook installed with the package. If you use a custom certificate path, confirm the reload hook still points to the right server.

### 10.7 HSTS (only after HTTPS is proven)

After all three hostnames work over HTTPS and you do not need to fall back to HTTP, add HSTS to each TLS server block:

```nginx
add_header Strict-Transport-Security "max-age=31536000" always;
```

Use `includeSubDomains` only if every subdomain of `mydomain.com` is HTTPS. HSTS is difficult to undo in browsers; enable it deliberately.

### 10.8 CORS and the domains

`CORS_ORIGINS` in `ims.env` must contain the exact HTTPS origins, comma-separated, with no trailing slash:

```
CORS_ORIGINS=https://admin.mydomain.com,https://app.mydomain.com,https://api.mydomain.com
```

The SPA on `admin` and `app` uses same-origin `/api`, so it does not need CORS. The setting matters for external browser clients and for any future cross-origin use. Do not set `*` in production.

After changing `CORS_ORIGINS`, restart the backend.

---

## 11. Super Admin Account Creation

### 11.1 How the platform administrator is modelled

The Platform Owner is a **platform user**, not a store user:

- `users.store_id IS NULL`;
- assigned the role `PLATFORM_ADMIN` (`roles.store_id IS NULL`);
- signs in with store code **`PLATFORM`** (the login page uppercases it);
- may use the username or email in the "Username or email" field;
- is redirected to `/platform/stores` after login;
- can manage stores only. The backend rejects platform access to store business data with HTTP 403 (`TenantContext` throws for platform users).

The role has exactly three permissions: `PLATFORM_MANAGE`, `STORE_VIEW`, `STORE_MANAGE`. It does not have store-level permissions such as `ITEM_VIEW` or `SALES_MANAGE`.

### 11.2 The seeder behaviour you must understand

On every startup, `DataSeeder` checks whether a platform user named `platform` exists (`store_id IS NULL`, username compared case-insensitively):

- If it does **not** exist, it creates one with:
  - username `platform`
  - email `platform@ims.local`
  - password `Admin@123`
  - full name `Platform Administrator`
  - status `ACTIVE`
  - role `PLATFORM_ADMIN`
- If it exists, the seeder does nothing, regardless of its status.

Consequences:

1. **Deleting the `platform` row does not remove the default account.** The next startup recreates it with the default password. Never delete it.
2. **Setting the `platform` row to `INACTIVE` prevents recreation** and prevents login. Keep it inactive if you use a different username.
3. The default password is a known value. It must not remain active on a production system.
4. `SEED_DEMO` controls only the demo business data (`ABC001`). It does **not** disable the platform user creation.

### 11.3 BCrypt hash generation and password rules

The application stores BCrypt hashes (strength 10) in `users.password_hash VARCHAR(255)`. It never stores or logs plaintext passwords. The seeded user is hashed with Spring Security's `BCryptPasswordEncoder`.

For SQL-created platform accounts, use PostgreSQL's `pgcrypto` extension:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SELECT crypt('the-password-you-typed', gen_salt('bf', 10));
```

- `gen_salt('bf', 10)` selects BCrypt with cost 10, matching the application.
- `crypt(...)` returns a value beginning `$2a$10$...`. Spring Security's BCrypt encoder accepts this format.
- The hash is safe to store; the plaintext is not.
- BCrypt considers only the first 72 bytes of a password. Use a password of 16-64 characters to stay within the limit.
- Use a unique, randomly generated password. Do not reuse the database password or the JWT secret.
- Do not put the plaintext password in shell history, a SQL file committed to source control, or a chat message.

In the account commands below, `[MY OWN PASSWORD]` stands for the password you choose. It is never typed literally and is never written into the SQL text; the psql `\prompt` command asks for it interactively.

### 11.4 Bootstrap sequence

You need the application to run once so Flyway creates the schema and the seeder creates the role's user. Do this before exposing the service publicly:

1. Complete Sections 3, 4 (Flyway path: script 01 only), 8 and the service unit in Section 8.5.
2. Confirm `SEED_DEMO=false` in `/opt/ims/config/ims.env`.
3. Start the backend and wait for a clean startup. See Section 12 for the service commands. The first start runs Flyway V1-V3 and creates the `platform` user.
4. Watch the log:

```bash
sudo journalctl -u ims-backend -n 200 --no-pager
```

Expected: Flyway migrates V1, V2, V3 successfully, Hibernate validates the schema, the application starts on port 8080, and the seeder logs that it created the platform administrator.

5. Stop the backend before changing the platform account:

```bash
sudo systemctl stop ims-backend
```

6. Choose **one** of the two account paths below.

### 11.5 Option A — keep the username `platform`, replace its details and password

This is the simplest path and avoids a second platform account. The username remains `platform`; the full name, email, mobile and password become yours.

Connect to the database as a PostgreSQL administrator:

```bash
sudo -u postgres psql -d item_management_db
```

Then:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

\set admin_fullname '[MY NAME]'
\set admin_email    '[MY EMAIL]'
\set admin_mobile   '[MY MOBILE]'
-- [MY OWN PASSWORD] is typed only at the prompt below, never pasted into this file.
\prompt 'Enter the platform password: ' admin_password

UPDATE users
SET full_name     = :'admin_fullname',
    email         = :'admin_email',
    mobile        = :'admin_mobile',
    password_hash = crypt(:'admin_password', gen_salt('bf', 10)),
    status        = 'ACTIVE',
    updated_at    = now()
WHERE store_id IS NULL
  AND lower(username) = 'platform';

\q
```

`\prompt` reads the password into a psql variable instead of embedding it in the SQL text. The characters you type may be visible on the terminal; run this on a trusted console or SSH session, clear the screen afterward, and do not use a shared terminal. If you prefer not to type it at all, generate the hash on a trusted machine with the same `crypt(..., gen_salt('bf', 10))` call and paste only the hash into the `UPDATE`.

Verify:

```sql
SELECT username, email, full_name, status, left(password_hash, 7) AS bcrypt_prefix
FROM users
WHERE store_id IS NULL;
```

Expected: `platform`, your email/name, `ACTIVE`, and a prefix such as `$2a$10$`. If the prefix is not a BCrypt prefix, the password will not work.

Start the backend again:

```bash
sudo systemctl start ims-backend
```

### 11.6 Option B — create your own username and retire the seeded account

Use this if you want `[MY USERNAME]` to be your own name rather than `platform`.

The bootstrap start in Section 11.4 has already created `platform`. Stop the backend, then connect:

```bash
sudo systemctl stop ims-backend
sudo -u postgres psql -d item_management_db
```

Run:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

\set admin_username '[MY USERNAME]'
\set admin_fullname '[MY NAME]'
\set admin_email    '[MY EMAIL]'
\set admin_mobile   '[MY MOBILE]'
-- [MY OWN PASSWORD] is typed only at the prompt below, never pasted into this file.
\prompt 'Enter the platform password: ' admin_password

-- Create the platform user only if the username does not already exist.
INSERT INTO users
    (store_id, username, email, password_hash, full_name, mobile, status, created_at, updated_at)
SELECT
    NULL,
    :'admin_username',
    :'admin_email',
    crypt(:'admin_password', gen_salt('bf', 10)),
    :'admin_fullname',
    :'admin_mobile',
    'ACTIVE',
    now(),
    now()
WHERE NOT EXISTS (
    SELECT 1
    FROM users
    WHERE store_id IS NULL
      AND lower(username) = lower(:'admin_username')
);

-- Assign the platform role.
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
CROSS JOIN roles r
WHERE u.store_id IS NULL
  AND lower(u.username) = lower(:'admin_username')
  AND r.store_id IS NULL
  AND r.code = 'PLATFORM_ADMIN'
ON CONFLICT DO NOTHING;

-- Keep the seeded default account, but make it impossible to log in.
UPDATE users
SET status = 'INACTIVE', updated_at = now()
WHERE store_id IS NULL
  AND lower(username) = 'platform'
  AND lower(username) <> lower(:'admin_username');

\q
```

Verify both accounts and the role grant:

```sql
SELECT u.id, u.username, u.email, u.status, r.code AS role
FROM users u
JOIN user_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id
WHERE u.store_id IS NULL
ORDER BY u.id;
```

Expected:

- your username is `ACTIVE` with role `PLATFORM_ADMIN`;
- `platform` is `INACTIVE` (unless you chose `platform` as your username, in which case there is only one row and this update does nothing);
- each platform user has the `PLATFORM_ADMIN` role.

Start the backend:

```bash
sudo systemctl start ims-backend
```

On startup the seeder sees that `platform` still exists (inactive) and does not recreate it. The default password remains unusable because the row is inactive.

### 11.7 Verify the password hash was generated correctly

```sql
SELECT username,
       status,
       left(password_hash, 7) AS prefix,
       length(password_hash)  AS hash_length
FROM users
WHERE store_id IS NULL;
```

- `prefix` should be `$2a$10$` or another BCrypt variant such as `$2b$10$`.
- `hash_length` should be 60 characters.
- If the value looks like your plaintext password or a 32-character MD5 hex string, stop. Delete the incorrect row and repeat with `crypt(..., gen_salt('bf', 10))`.

### 11.8 Log in as Platform Owner

1. Open `https://admin.mydomain.com`.
2. Store code: `PLATFORM`.
3. Username or email: `[MY USERNAME]` or `[MY EMAIL]`, or `platform` if you chose Option A.
4. Password: the value you entered when generating the hash.
5. Submit.

Expected: a welcome message and a redirect to `https://admin.mydomain.com/platform/stores`. The page lists registered stores (empty on a new installation) and shows the "Onboard store" button.

If login fails:

| Symptom | Check |
|---|---|
| "Invalid store code" | Use `PLATFORM` exactly; the UI uppercases it |
| "Invalid username or password" | Confirm the username/email, the BCrypt prefix and the password; check for trailing spaces |
| Account inactive | `SELECT status FROM users WHERE store_id IS NULL;` must be `ACTIVE` |
| Role missing | Confirm `user_roles` contains the `PLATFORM_ADMIN` role for the user |
| 403 after login | The user may be a store user, or the role lacks `PLATFORM_MANAGE`; check the grants |
| 502 from NGINX | The backend is not running or is not on `127.0.0.1:8080` |
| No stores page | Confirm the URL is `/platform/stores`; a platform login redirects there automatically |

### 11.9 Password reset and session revocation

To reset the platform password, stop the backend, repeat the `UPDATE ... crypt(...)` statement, and start it again. There is no change-password screen in this application for platform users.

Changing the password does **not** invalidate JWTs that were already issued. Existing sessions remain valid until they expire (12 hours by default). If you need to revoke all sessions immediately, rotate `JWT_SECRET` in `ims.env` and restart the backend. Every user must sign in again.

### 11.10 Audit note

Creating or updating a platform account with SQL bypasses the application's audit log. This is acceptable for initial provisioning, but record the change in your operations log. Normal logins are audited by `AuthService`. Do not create production users by SQL as a routine practice; use the platform/store APIs for normal administration.

---

## 12. Application Startup

### 12.1 Service unit and startup order

The systemd unit is defined in Section 8.5. The startup order is:

1. PostgreSQL (`postgresql.service`) — required by the backend unit.
2. Spring Boot (`ims-backend.service`) — connects to PostgreSQL, validates/migrates the schema, listens on `127.0.0.1:8080`.
3. NGINX (`nginx.service`) — serves the SPA and proxies `/api/` to the backend.

Enable the backend so it starts after a reboot:

```bash
sudo systemctl daemon-reload
sudo systemctl enable ims-backend
sudo systemctl start ims-backend
sudo systemctl status ims-backend --no-pager
```

`daemon-reload` is needed after creating or editing the unit file. It is not needed after editing `ims.env`.

### 12.2 What a successful first start looks like

```bash
sudo journalctl -u ims-backend -n 200 --no-pager
```

A healthy first start includes:

- the Spring Boot banner;
- Hikari connecting to `jdbc:postgresql://127.0.0.1:5432/item_management_db` as `ims_app`;
- Flyway validating and applying V1, V2, V3 (on a new database);
- Hibernate validating the schema (no `Schema-validation` errors);
- Tomcat starting on port 8080;
- the `DataSeeder` message about the platform administrator;
- `Started ItemManagementApplication` with no exception after it.

On the manual SQL path, Flyway is disabled and there is no Flyway output. Hibernate must still validate successfully.

### 12.3 Health checks

Local backend:

```bash
curl -i http://127.0.0.1:8080/api/auth/me
```

Expected: `401 Unauthorized` with a JSON error body. A 401 proves the HTTP server and security filter are working. A connection refused means the backend is not listening.

Through NGINX (once TLS is configured):

```bash
curl -i https://api.mydomain.com/api/auth/me
curl -I https://app.mydomain.com/
curl -I https://admin.mydomain.com/
```

Expected: 401 JSON for the API, 200 for both SPA pages.

Check the listening sockets:

```bash
sudo ss -ltnp | grep -E ':(8080|5432)\b'
```

Expected: `127.0.0.1:8080` for Java and `127.0.0.1:5432` for PostgreSQL. Neither should show `0.0.0.0` or `[::]`.

### 12.4 Start, stop, restart, status and logs

Spring Boot:

```bash
sudo systemctl start ims-backend
sudo systemctl stop ims-backend
sudo systemctl restart ims-backend
sudo systemctl status ims-backend --no-pager
sudo systemctl is-enabled ims-backend
```

Logs:

```bash
sudo journalctl -u ims-backend -f
sudo journalctl -u ims-backend -n 200 --no-pager
sudo journalctl -u ims-backend --since "1 hour ago"
sudo journalctl -u ims-backend -p err --since today
```

If `LOGGING_FILE_NAME=/opt/ims/logs/ims-backend.log` is set:

```bash
sudo -u ims tail -f /opt/ims/logs/ims-backend.log
```

PostgreSQL:

```bash
sudo systemctl start postgresql
sudo systemctl stop postgresql
sudo systemctl restart postgresql
sudo systemctl status postgresql --no-pager
sudo systemctl is-enabled postgresql
sudo pg_lsclusters
```

NGINX:

```bash
sudo systemctl start nginx
sudo systemctl stop nginx
sudo systemctl restart nginx
sudo systemctl reload nginx
sudo systemctl status nginx --no-pager
sudo nginx -t
```

Use `reload` after a configuration change that passes `nginx -t`; use `restart` only when a reload is not sufficient. Reload does not drop active connections.

### 12.5 Restart after a configuration change

After editing `/opt/ims/config/ims.env`:

```bash
sudo systemctl restart ims-backend
sudo systemctl status ims-backend --no-pager
sudo journalctl -u ims-backend -n 100 --no-pager
```

Environment files are read at process start, so a restart is required. Changing `JWT_SECRET` signs everyone out. Changing `DB_URL` or `DB_PASSWORD` requires the new database to be reachable before restarting.

After editing an NGINX file:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

After editing the systemd unit:

```bash
sudo systemctl daemon-reload
sudo systemctl restart ims-backend
```

### 12.6 Deploying a new build (same version or a new version)

Frontend only:

```bash
# Build locally, upload dist/ contents to /tmp/ims-web, then:
sudo rsync -a --delete /tmp/ims-web/ /opt/ims/web/
sudo chown -R root:www-data /opt/ims/web
sudo find /opt/ims/web -type d -exec chmod 755 {} \;
sudo find /opt/ims/web -type f -exec chmod 644 {} \;
```

No NGINX reload is required for static file replacement, but `index.html` is served with `no-store`, so users get the new version on refresh.

Backend:

```bash
sudo systemctl stop ims-backend
sudo install -o ims -g ims -m 640 \
  /tmp/item-management-backend-<version>.jar \
  /opt/ims/app/item-management-backend-<version>.jar
# Update the ExecStart path if the filename/version changed:
sudo systemctl edit --full ims-backend
sudo systemctl daemon-reload
sudo systemctl start ims-backend
sudo journalctl -u ims-backend -n 100 --no-pager
```

If the version changes, update the unit's `ExecStart` path or keep a stable copy/symlink. This document deploys version `1.0.0`, so the path is `item-management-backend-1.0.0.jar`.

Database migrations are applied by Flyway on startup when `SPRING_FLYWAY_ENABLED=true`. Never run a manual schema change against a Flyway-managed database. Back up the database before deploying a build that contains new migrations.

### 12.7 Stopping for maintenance

Recommended order:

```bash
sudo systemctl stop nginx          # optional; stops public access
sudo systemctl stop ims-backend
sudo systemctl stop postgresql     # only if the database must be offline
```

Start in reverse order:

```bash
sudo systemctl start postgresql
sudo systemctl start ims-backend
sudo systemctl start nginx
```

For a database backup, stop the backend (or at least stop writes), take the dump, then start it again. Do not stop PostgreSQL while the backend is running unless you intend to interrupt service.

### 12.8 Reboot test

After the first successful deployment:

```bash
sudo reboot
```

Reconnect and verify:

```bash
systemctl is-active postgresql ims-backend nginx
curl -I https://app.mydomain.com/
curl -i https://api.mydomain.com/api/auth/me
```

All three services must be `active`, the SPA must return 200, and the API must return 401 JSON. If the backend does not start after reboot, check `journalctl -u ims-backend -b` and the `After`/`Requires` ordering.

### 12.9 Do not run the JAR manually as the permanent service

Running `java -jar` in a terminal is useful for a one-off diagnostic, but it bypasses systemd, does not restart on failure, and does not capture logs in the journal. Use the service. If you must run it manually for troubleshooting, stop the service first to avoid a port conflict, load the same environment file, and stop the manual process before restarting the service.

---
## 13. First Store Creation

### 13.1 Sign in to the platform console

1. Open `https://admin.mydomain.com`.
2. Store code: `PLATFORM`.
3. Username or email: your platform username or email.
4. Password: your platform password.
5. You are redirected to `/platform/stores`.

The page shows platform statistics and a table of registered stores. On a new installation the table is empty.

### 13.2 Onboard the first store

Click **Onboard store** and fill the form:

| Field | Required | Rules and meaning |
|---|---|---|
| Store code | Yes | 3-30 letters, numbers, hyphen or underscore; stored uppercase; used at every store login; cannot be changed after creation |
| Store name | Yes | Business name shown in the console and on documents |
| Owner name | Yes | Full name of the store owner |
| Owner email | Yes | Valid email; used as a login identifier and contact |
| Mobile number | Yes | Contact number for the store/owner |
| GSTIN | No | Tax registration number; stored uppercase |
| Store address | Yes | Full address, including city and state where possible |
| First branch name | No | Defaults to `Main Branch` if left blank |
| Owner username | Yes | 3-40 letters, numbers, dot, underscore or hyphen |
| Owner password | Yes | Minimum 8 characters; use a strong unique value of 16+ characters |

The UI submits `POST /api/stores` with the profile, store code, owner username/password and optional first branch name. The request is authorized by `PLATFORM_MANAGE`, which only the platform role has.

The UI does not send a branch `state`. After onboarding, open the store's **Settings → Branches**, edit `MAIN`, and set the **State**. The branch state is the place of supply used by GST to decide CGST+SGST versus IGST. Do this before recording interstate purchases or sales.

### 13.3 What the backend creates, transactionally

`StoreProvisioningService.createStore` creates, in one transaction:

1. the `stores` row;
2. the first branch, code `MAIN` (or the supplied name as the branch name; the branch code is `MAIN`);
3. four system roles for the store:
   - `STORE_OWNER`
   - `STORE_ADMIN`
   - `BRANCH_MANAGER`
   - `BRANCH_STAFF`
4. the permission grants for those roles (the owner receives all 27 store-level permissions; `PLATFORM_MANAGE` is platform-only);
5. five GST slabs in `tax_master`: 0% (EXEMPT), 5%, 12%, 18%, 28%;
6. the owner user with the supplied username and BCrypt-hashed password, assigned `STORE_OWNER` with `allBranches=true`.

If any step fails, the transaction rolls back. You should not see a store without its branch, roles or owner.

The store owner's `allBranches` flag is true because the owner role is store-wide. Branch managers and branch staff can be restricted to assigned branches when you create them in **Settings → Users**.

### 13.4 Verify the store in the database

```sql
SELECT s.id, s.store_code, s.store_name, s.status,
       b.branch_code, b.branch_name, b.state
FROM stores s
JOIN branches b ON b.store_id = s.id
WHERE s.store_code = '[STORE CODE]';

SELECT r.code, count(rp.permission_id) AS grants
FROM roles r
LEFT JOIN role_permissions rp ON rp.role_id = r.id
WHERE r.store_id = (SELECT id FROM stores WHERE store_code = '[STORE CODE]')
GROUP BY r.code
ORDER BY r.code;

SELECT tax_name, gst_percentage, tax_type
FROM tax_master
WHERE store_id = (SELECT id FROM stores WHERE store_code = '[STORE CODE]')
ORDER BY gst_percentage;

SELECT u.username, u.email, u.status
FROM users u
WHERE u.store_id = (SELECT id FROM stores WHERE store_code = '[STORE CODE]');
```

Expected on a new store:

- one branch, code `MAIN`;
- four roles;
- five tax slabs: 0, 5, 12, 18, 28;
- one user (the owner), status `ACTIVE`;
- no items, categories, suppliers, purchases or sales yet.

### 13.5 Sign in as the store owner

1. Open `https://app.mydomain.com`.
2. Store code: the code you created.
3. Username or email: the owner username or owner email.
4. Password: the owner password.

Expected: the store dashboard loads at `/`. The owner can see dashboard, POS, sales, items, categories, taxes, purchases, inventory, transfers, suppliers and reports according to the 27 granted permissions.

The same owner can sign in at `admin.mydomain.com` with the store code; the hostname is not a role boundary. The platform administrator, conversely, cannot access store business pages because the backend rejects platform users in `TenantContext`.

### 13.6 First configuration steps for the store

Do these in the store workspace before billing:

1. **Settings → Store**: confirm the store name, address, GSTIN and tax-inclusive setting.
2. **Settings → Branches**: edit `MAIN`, set the state (place of supply), and confirm the address/contact.
3. **Categories**: create at least one category.
4. **Taxes**: the five standard slabs already exist. Add or edit slabs only if the business needs different rates. Do not delete a slab that is referenced by an item.
5. **Items**: create items with a unique item code, category, tax slab, base unit and master rate. The item master rate is the POS rate.
6. **Suppliers**: create suppliers with their state for interstate tax decisions.
7. **Opening stock**: use the inventory opening-stock or adjustment flow before the first sale. The application prevents stock from going negative; a sale cannot exceed available stock.
8. **Users**: create managers/staff and assign branches as needed. The owner has all branches.
9. **POS**: perform a small test sale, verify the stock decrement and the sale document, then cancel the test sale if it should not remain in reporting. Cancellation creates a reversal; completed rows are not deleted.

### 13.7 Platform isolation checks

Perform these checks with two stores:

1. Create store A and store B with different owners.
2. Sign in as A's owner and note an item ID from A.
3. Sign in as B's owner in a different browser profile and request A's item through the API or UI. The request must fail (404/403), not return A's data.
4. Sign in as the platform administrator and attempt to open a store business page or call a store business endpoint. The backend must return 403 with a platform-access message.
5. Confirm the platform console shows both stores but no store sales/items data.

The backend derives the store from the JWT and database records. Do not test isolation only by hiding UI elements; test the API response.

### 13.8 Common onboarding problems

| Symptom | Cause | Fix |
|---|---|---|
| Store code rejected | Not 3-30 characters or contains unsupported characters | Use letters, numbers, hyphen or underscore |
| Owner username rejected | Not 3-40 characters or contains unsupported characters | Use letters, numbers, dot, underscore or hyphen |
| Password rejected | Fewer than 8 characters | Use a stronger password of at least 8, preferably 16+ |
| "Store code already exists" | The code is used by another store | Choose a different code |
| Owner cannot log in | Wrong store code, wrong username, or inactive store/user | Check the store status and user status; use the exact store code |
| GST is always intra-state | Branch state is blank | Set the branch state in Settings → Branches |
| Onboarding fails with a database error | Database constraint or connection problem | Check `journalctl -u ims-backend` and the PostgreSQL log; the transaction rolled back |

---

## 14. Backup and Restore

### 14.1 What to back up

| Item | Why | Frequency |
|---|---|---|
| PostgreSQL database `item_management_db` | All stores, users, items, stock, sales, purchases, audits | Daily minimum; more often for high transaction volume |
| `/opt/ims/config/ims.env` | Database/JWT/CORS configuration | On every change, encrypted |
| `/etc/nginx/sites-available/ims` | Reverse proxy, TLS and SPA configuration | On every change |
| `/etc/systemd/system/ims-backend.service` | Service definition | On every change |
| `/etc/letsencrypt/` | Certificates and renewal configuration | Included in server backup; Certbot can reissue |
| Application JAR and `dist/` | Rebuildable from source, but useful for rollback | Each release |
| Old embedded `.pgdata` (during migration) | Rollback copy | Until migration sign-off |

The database is the only irreplaceable business asset. The rest can be rebuilt or reissued, but having it shortens recovery.

### 14.2 Daily database backup script

Create `/opt/ims/backups/backup-ims.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="/opt/ims/backups"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$BACKUP_DIR/item_management_db_$STAMP.dump"
LIST="$BACKUP_DIR/item_management_db_$STAMP.list"

export PGPASSFILE="/opt/ims/config/pgpass"

pg_dump \
  --host=127.0.0.1 \
  --port=5432 \
  --username=ims_app \
  --dbname=item_management_db \
  --format=custom \
  --compress=9 \
  --no-owner \
  --no-privileges \
  --file="$FILE"

pg_restore --list "$FILE" > "$LIST"
sha256sum "$FILE" > "$FILE.sha256"

# Keep 30 days. Adjust the retention to your policy.
find "$BACKUP_DIR" -type f -name 'item_management_db_*.dump' -mtime +30 -delete
find "$BACKUP_DIR" -type f -name 'item_management_db_*.list' -mtime +30 -delete
find "$BACKUP_DIR" -type f -name 'item_management_db_*.dump.sha256' -mtime +30 -delete

echo "$(date -u +%FT%TZ) backup ok: $FILE"
```

Install and test:

```bash
sudo chown ims:ims /opt/ims/backups/backup-ims.sh
sudo chmod 750 /opt/ims/backups/backup-ims.sh
sudo -u ims /opt/ims/backups/backup-ims.sh
ls -lh /opt/ims/backups/
```

The script uses `/opt/ims/config/pgpass` (created in Section 8.4) so no password appears in the cron command or process list. The dump is a custom-format PostgreSQL archive containing schema, data, sequences and `flyway_schema_history`. `--no-owner`/`--no-privileges` make it portable to the `ims_app` owner on restore.

### 14.3 Schedule the backup with cron

Edit the `ims` user's crontab:

```bash
sudo crontab -u ims -e
```

Add:

```cron
0 2 * * * /opt/ims/backups/backup-ims.sh >> /opt/ims/logs/backup.log 2>&1
```

This runs daily at 02:00 server time (UTC if you followed Section 2.2). Confirm:

```bash
sudo crontab -u ims -l
sudo -u ims tail -n 20 /opt/ims/logs/backup.log
```

The log directory must be writable by `ims`; Section 8 sets that. Add a logrotate rule if the log grows, or send the output to journald with `logger` instead.

### 14.4 Verify every backup

A dump that has never been listed or restored is not a verified backup. For each run:

```bash
LATEST="$(ls -1t /opt/ims/backups/item_management_db_*.dump | head -1)"
echo "$LATEST"
pg_restore --list "$LATEST" | head -30
pg_restore --list "$LATEST" | grep -c 'TABLE DATA'   # expect 24
sha256sum -c "$LATEST.sha256"
```

`24` is 23 application tables plus `flyway_schema_history`. If the count is lower, the dump is incomplete; investigate before relying on it.

### 14.5 Monthly restore test

Restore the latest dump into a separate database and verify it. This does not touch production.

```bash
LATEST="$(ls -1t /opt/ims/backups/item_management_db_*.dump | head -1)"

sudo -u postgres dropdb --if-exists item_management_db_restoretest
sudo -u postgres createdb -O ims_app item_management_db_restoretest

PGPASSFILE=/opt/ims/config/pgpass pg_restore \
  --host=127.0.0.1 --port=5432 --username=ims_app \
  --dbname=item_management_db_restoretest \
  --no-owner --no-privileges --single-transaction --exit-on-error \
  "$LATEST"

PGPASSFILE=/opt/ims/config/pgpass psql \
  "host=127.0.0.1 port=5432 dbname=item_management_db_restoretest user=ims_app" \
  -c "SELECT count(*) AS stores FROM stores;" \
  -c "SELECT count(*) AS sales FROM sales;" \
  -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"

sudo -u postgres dropdb item_management_db_restoretest
```

Record the counts and the date. A monthly automated test that reports row counts is better than a manual check that is skipped.

### 14.6 Production restore procedure

This is destructive to the current database. Use it only after a failure or during an approved recovery, and take a safety dump first if the database is still reachable.

1. Stop the backend so no new writes occur:

```bash
sudo systemctl stop ims-backend
```

2. If the database is reachable, take an emergency dump before replacing it:

```bash
sudo -u ims /opt/ims/backups/backup-ims.sh
```

3. Terminate remaining connections and recreate the database. `dropdb` deletes the current database; confirm the dump you are restoring is valid first.

```bash
sudo -u postgres psql -d postgres -c "
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'item_management_db'
  AND pid <> pg_backend_pid();"

sudo -u postgres dropdb --if-exists item_management_db
sudo -u postgres createdb -O ims_app item_management_db
```

4. Restore the schema owner and session defaults that script 01 sets:

```bash
sudo -u postgres psql -d item_management_db -c "
ALTER SCHEMA public OWNER TO ims_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO ims_app;
ALTER DATABASE item_management_db SET timezone TO 'UTC';
ALTER DATABASE item_management_db SET statement_timeout TO '60s';
ALTER DATABASE item_management_db SET idle_in_transaction_session_timeout TO '120s';"
```

5. Restore the dump:

```bash
RESTORE="/opt/ims/backups/item_management_db_YYYYMMDDTHHMMSSZ.dump"

PGPASSFILE=/opt/ims/config/pgpass pg_restore \
  --host=127.0.0.1 --port=5432 --username=ims_app \
  --dbname=item_management_db \
  --no-owner --no-privileges --single-transaction --exit-on-error \
  "$RESTORE"
```

6. Verify before starting the application:

```bash
PGPASSFILE=/opt/ims/config/pgpass psql \
  "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;" \
  -c "SELECT count(*) FROM stores;" \
  -c "SELECT count(*) FROM sales;"
```

7. Start the backend and check the log:

```bash
sudo systemctl start ims-backend
sudo journalctl -u ims-backend -n 100 --no-pager
```

8. Sign in as Platform Owner and as a store owner, and verify a recent sale/purchase and stock figure.

If the restore fails, `--single-transaction` rolls back. Do not start the backend on a partial database. Restore the emergency dump or fix the cause and retry.

### 14.7 Off-site copies and encryption

A backup on the same disk does not survive disk loss, ransomware, or a deleted server. Copy dumps off the server:

```bash
# Example: copy to another host you control
rsync -avz /opt/ims/backups/ backup@backup-host:/srv/ims-backups/
```

For cloud storage, use your provider's CLI or `rclone`, with a credential that can write only to the backup bucket. Encrypt dumps before they leave the server if the destination is not fully trusted:

```bash
gpg --symmetric --cipher-algo AES256 "$DUMP"
```

Keep the passphrase in a password manager, not next to the encrypted file. Test decrypting and restoring an off-site copy periodically.

### 14.8 Windows Server backups

Use `C:\Program Files\PostgreSQL\16\bin\pg_dump.exe` and a PowerShell script scheduled with Task Scheduler:

```powershell
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$file  = "C:\ims\backups\item_management_db_$stamp.dump"
$env:PGPASSFILE = "C:\ims\config\pgpass"
& "C:\Program Files\PostgreSQL\16\bin\pg_dump.exe" `
  --host=127.0.0.1 --port=5432 --username=ims_app --dbname=item_management_db `
  --format=custom --compress=9 --no-owner --no-privileges --file=$file
& "C:\Program Files\PostgreSQL\16\bin\pg_restore.exe" --list $file > "$file.list"
$hash = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
"$hash  $([System.IO.Path]::GetFileName($file))" | Out-File "$file.sha256" -Encoding ascii
```

Schedule it as the `ims` user (or an account with read access to `pgpass` and write access to `C:\ims\backups`). Keep the same retention and restore-test discipline.

### 14.9 Backup checklist

- [ ] `pgpass` exists, mode 600, owned by `ims`, and contains the correct password.
- [ ] The backup script runs successfully as `ims`.
- [ ] A fresh dump lists 24 `TABLE DATA` entries.
- [ ] The checksum verifies.
- [ ] Cron or Task Scheduler runs daily and the log shows success.
- [ ] Retention deletes old dumps but keeps at least 30 days.
- [ ] An off-site copy exists.
- [ ] A restore into a scratch database succeeds and row counts match.
- [ ] The restore procedure is documented and someone other than the author has followed it.
- [ ] Database backups are taken before every release that includes a migration.

---
## 15. Production Verification Checklist

Run every check on the production server after deployment and before handing the system to stores. Do not mark the deployment complete based only on the application starting.

### 15.1 Server and network

- [ ] Ubuntu 24.04 LTS is fully updated (`sudo apt update && sudo apt full-upgrade`).
- [ ] Timezone is UTC (`timedatectl`).
- [ ] SSH key login works; root login and password login are disabled.
- [ ] UFW allows only 22, 80 and 443 (`sudo ufw status verbose`).
- [ ] Port 8080 is bound to `127.0.0.1`, not `0.0.0.0`:

```bash
sudo ss -ltnp | grep ':8080'
```

- [ ] Port 5432 is bound to `127.0.0.1`, not `0.0.0.0`:

```bash
sudo ss -ltnp | grep ':5432'
```

- [ ] PostgreSQL is not reachable from an external machine (`nc -zv [SERVER IP] 5432` must fail).
- [ ] Spring Boot is not reachable from an external machine on 8080 (`nc -zv [SERVER IP] 8080` must fail).
- [ ] DNS resolves all three names to the server:

```bash
dig +short admin.mydomain.com app.mydomain.com api.mydomain.com
```

- [ ] The server has sufficient free disk (`df -h`) and memory (`free -h`).

### 15.2 Database

- [ ] PostgreSQL 16 is running and enabled:

```bash
systemctl is-active postgresql
systemctl is-enabled postgresql
pg_lsclusters
```

- [ ] The application connects as `ims_app`, not `postgres`:

```sql
SELECT usename, application_name, client_addr
FROM pg_stat_activity
WHERE datname = 'item_management_db';
```

Expected: `ims_app` with `127.0.0.1`. You should not see the application connected as `postgres`.

- [ ] `ims_app` is not a superuser:

```sql
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'ims_app';
```

Expected: all three privilege columns `false`.

- [ ] The schema has 23 application tables:

```sql
SELECT count(*) AS application_tables
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_type = 'BASE TABLE'
  AND table_name <> 'flyway_schema_history';
```

Expected: `23`.

- [ ] The four document sequences exist:

```sql
SELECT sequence_name FROM information_schema.sequences
WHERE sequence_schema = 'public'
  AND sequence_name IN (
    'purchase_number_seq','sales_number_seq',
    'transfer_number_seq','adjustment_number_seq'
  )
ORDER BY sequence_name;
```

Expected: `4` rows. Every `BIGSERIAL` primary key also creates its own internal sequence, so do not expect the total sequence count to be 4.

- [ ] The 19 non-constraint indexes exist. Use the exact names from script 03, for example:

```sql
SELECT indexname FROM pg_indexes
WHERE schemaname = 'public'
  AND indexname IN (
    'ix_users_email','ix_users_store','ix_items_store_category','ix_items_name',
    'ix_items_tax','ix_tax_master_store_status','ix_inventory_item','ix_inventory_branch',
    'ix_txn_store_date','ix_txn_branch_item','ix_txn_reference','ix_purchases_store_date',
    'ix_purchases_branch','ix_purchase_details_purchase','ix_sales_store_date',
    'ix_sales_branch','ix_sales_details_sale','ix_transfers_store','ix_audit_store_date'
  )
ORDER BY indexname;
```

Expected: `19` rows.

- [ ] Permissions are 28:

```sql
SELECT count(*) FROM permissions;   -- 28
```

- [ ] The platform role has exactly three grants:

```sql
SELECT r.code, count(rp.permission_id) AS grants
FROM roles r
LEFT JOIN role_permissions rp ON rp.role_id = r.id
WHERE r.store_id IS NULL
GROUP BY r.code;                    -- PLATFORM_ADMIN = 3
```

- [ ] On the Flyway path, V1-V3 are recorded and successful:

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

- [ ] On the manual path, Flyway is disabled and `flyway_schema_history` does not exist. Confirm `SPRING_FLYWAY_ENABLED=false` in `ims.env`.
- [ ] The database name is exactly `item_management_db`; there are no per-store databases:

```sql
SELECT datname FROM pg_database WHERE datname LIKE 'item_management%';
```

- [ ] A backup has run and a restore test has succeeded (Section 14).

### 15.3 Backend service and configuration

- [ ] The service is active and enabled:

```bash
systemctl is-active ims-backend
systemctl is-enabled ims-backend
sudo systemctl status ims-backend --no-pager
```

- [ ] The environment file has the correct ownership and mode:

```bash
sudo stat -c '%U:%G %a %n' /opt/ims/config/ims.env
```

Expected: `root:ims 640`.

- [ ] The production profile and database settings are loaded:

```bash
PID="$(pgrep -f 'item-management-backend-1.0.0.jar' | head -1)"
sudo tr '\0' '\n' < "/proc/$PID/environ" \
  | grep -E '^(SPRING_PROFILES_ACTIVE|EMBEDDED_POSTGRES|SEED_DEMO|SERVER_ADDRESS|SERVER_PORT|DB_URL|DB_USERNAME)='
```

Expected:

```
SPRING_PROFILES_ACTIVE=postgres
EMBEDDED_POSTGRES=false
SEED_DEMO=false
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080
DB_URL=jdbc:postgresql://127.0.0.1:5432/item_management_db
DB_USERNAME=ims_app
```

Do not print `DB_PASSWORD` or `JWT_SECRET` to the terminal or to a ticket.

- [ ] `JWT_SECRET` is not the development default. Check its length without printing it:

```bash
awk -F= '/^JWT_SECRET=/{print "JWT_SECRET length:", length($2)}' /opt/ims/config/ims.env
```

Expected: well above 32 characters; the recommended value is 96 hex characters.

- [ ] `SEED_DEMO=false` is present:

```bash
grep -E '^SEED_DEMO=' /opt/ims/config/ims.env
```

- [ ] The local health endpoint returns 401 JSON:

```bash
curl -i http://127.0.0.1:8080/api/auth/me
```

- [ ] The log has no startup errors:

```bash
sudo journalctl -u ims-backend --since today -p err --no-pager
```

- [ ] Hibernate schema validation succeeded; there is no `Schema-validation` error.
- [ ] Flyway either applied V1-V3 successfully on a new database or validated an existing history without re-running migrations.

### 15.4 Frontend, NGINX and TLS

- [ ] `dist/` contents are in `/opt/ims/web`, with `index.html` at the root:

```bash
ls -l /opt/ims/web/index.html
ls -ld /opt/ims/web/assets
```

- [ ] NGINX configuration passes:

```bash
sudo nginx -t
```

- [ ] NGINX is active and enabled:

```bash
systemctl is-active nginx
systemctl is-enabled nginx
```

- [ ] `http://admin.mydomain.com` and `http://app.mydomain.com` redirect to HTTPS.
- [ ] `https://admin.mydomain.com` and `https://app.mydomain.com` load the login page.
- [ ] `https://api.mydomain.com/api/auth/me` returns 401 JSON.
- [ ] Deep links work; for example, opening `https://app.mydomain.com/items` directly loads the SPA, not a 404. Sign in first or expect the login redirect.
- [ ] The certificate is valid for all three hostnames:

```bash
sudo certbot certificates
```

- [ ] Renewal dry run succeeds:

```bash
sudo certbot renew --dry-run
```

- [ ] The certificate expiry is monitored, either by Certbot's timer or by your monitoring system.
- [ ] `index.html` is served with `Cache-Control: no-store`, and hashed assets are cached:

```bash
curl -I https://app.mydomain.com/index.html
curl -I https://app.mydomain.com/assets/$(basename "$(ls /opt/ims/web/assets/*.js | head -1)")
```

- [ ] The SPA's API requests go to `/api/` on the same hostname. In the browser developer tools, the login request is `https://app.mydomain.com/api/auth/login`, not `https://api.mydomain.com/...`.

### 15.5 Platform Owner account

- [ ] The platform account uses a strong, unique password that is stored in a password manager.
- [ ] If you chose a custom username, the seeded `platform` row is `INACTIVE`:

```sql
SELECT username, status FROM users WHERE store_id IS NULL ORDER BY username;
```

- [ ] The default `platform` / `Admin@123` login fails.
- [ ] The platform user has the `PLATFORM_ADMIN` role and exactly the three platform permissions.
- [ ] Login at `https://admin.mydomain.com` with store code `PLATFORM` redirects to `/platform/stores`.
- [ ] A store user cannot see the platform console; the backend returns 403 for platform-only operations.
- [ ] The platform administrator cannot read store business data; a direct store business API call returns 403.

### 15.6 First store and functional smoke test

- [ ] A test store has been onboarded through the platform console.
- [ ] The database contains one `MAIN` branch, four store roles, five GST slabs and one owner user for that store.
- [ ] The owner can sign in at `https://app.mydomain.com` with the store code.
- [ ] The owner can create a category, a supplier and an item.
- [ ] The item uses a valid tax slab and a non-negative master rate.
- [ ] Opening stock or an inventory adjustment increases the branch stock.
- [ ] A POS sale succeeds and decreases stock by the sold quantity.
- [ ] A sale for more than the available stock is rejected by the backend, and the stock is unchanged.
- [ ] A normal sale uses the manually entered selling rate; a POS sale uses the item master rate.
- [ ] A purchase increases stock and records the purchase total and GST.
- [ ] Cancelling a sale restores the stock and marks the sale `CANCELLED`; the completed sale is not deleted.
- [ ] A purchase or sale failure does not leave a partial stock movement.
- [ ] Reports show the test data and GST totals without errors.
- [ ] The dashboard loads without a 500 error.
- [ ] A low-stock item appears in the low-stock view when stock is at or below the threshold.

### 15.7 Tenant and branch isolation

- [ ] Two stores exist, each with its own owner.
- [ ] Store A's owner cannot read or modify Store B's items, branches, sales, purchases, stock or users through the API.
- [ ] A branch-restricted user can access only assigned branches. A request for an unassigned branch is rejected.
- [ ] A store user cannot call platform endpoints (`/api/stores` management) even if the UI is bypassed.
- [ ] A platform user cannot call store business endpoints; the response is 403.
- [ ] The browser-supplied `storeId` or `branchId` cannot override the JWT identity. Test by changing a request parameter; the backend must ignore or reject it.
- [ ] An inactive store's users cannot sign in, and an inactive user cannot sign in.

### 15.8 Operations

- [ ] A daily backup is scheduled and the latest dump is non-empty.
- [ ] The dump lists 24 `TABLE DATA` entries and the checksum verifies.
- [ ] A restore into a scratch database succeeded and the row counts matched.
- [ ] An off-site copy exists.
- [ ] Log rotation is configured for the backend file log, if used.
- [ ] The server has been rebooted and PostgreSQL, the backend and NGINX all returned automatically.
- [ ] `journalctl -u ims-backend --since "24 hours ago" -p err` shows no unresolved errors.
- [ ] Disk usage is below 80 percent (`df -h`).
- [ ] Memory usage leaves headroom for the JVM, PostgreSQL and NGINX (`free -h`).
- [ ] Monitoring or an external uptime check watches `https://api.mydomain.com/api/auth/me` and the SPA URLs.
- [ ] The rollback procedure (previous JAR, previous `dist/`, database backup) is documented and available.
- [ ] Application-level known issues in `docs/08-known-issues.md` have been reviewed and accepted for this release.

### 15.9 Go/no-go sign-off

| Area | Checked by | Date | Result |
|---|---|---|---|
| Server, firewall, DNS | | | |
| PostgreSQL, schema, reference data | | | |
| Backend service and configuration | | | |
| Frontend, NGINX, TLS | | | |
| Platform Owner account | | | |
| First store and functional flows | | | |
| Tenant/branch isolation | | | |
| Backup and restore | | | |
| Reboot and monitoring | | | |

Do not declare the deployment complete until every row has a result and every failed item has an owner and a resolution date.

---

## Appendix A – Windows Server alternative

This appendix is a complete alternative to the Ubuntu path. The application, database schema and configuration keys are identical. Only operating-system commands and paths differ.

### A.1 Install Windows Server and prerequisites

1. Install Windows Server 2022 or 2025.
2. Install all Windows Updates and reboot.
3. Restrict RDP to your IP or a VPN. Do not expose RDP to the internet.
4. Install the current Chrome, Edge or Firefox for testing.
5. Create `C:\ims` with subdirectories `app`, `web`, `config`, `logs`, `backups`.
6. Create a local user `ims` with a strong password. This account runs the backend and NGINX services. Do not run production services as a domain administrator.
7. Grant `ims` the "Log on as a service" right (`secpol.msc` → Local Policies → User Rights Assignment → Log on as a service), or set it through the NSSM service logon account.

### A.2 Install Java 17

Download the Eclipse Temurin 17 Windows x64 `.msi` from the official Adoptium page. Install it and set a system environment variable:

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-17.0.x-hotspot", "Machine")
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

Use the actual JDK directory name from your installation. Java 17 is required; do not use Java 8.

### A.3 Install PostgreSQL 16

1. Download the PostgreSQL 16 Windows installer from the official PostgreSQL download page (the EDB installer).
2. Install the database server and pgAdmin/command-line tools.
3. Set a strong password for the `postgres` superuser. Store it in a password manager; do not use it for the application.
4. Keep port 5432. The installer creates the `postgresql-x64-16` Windows service.
5. Confirm the service:

```powershell
Get-Service postgresql-x64-16
Start-Service postgresql-x64-16
```

6. Confirm `postgresql.conf` has `listen_addresses = 'localhost'` and `port = 5432`.
7. Confirm `pg_hba.conf` uses `scram-sha-256` for local TCP connections, not `trust`.
8. Restart the service after configuration changes:

```powershell
Restart-Service postgresql-x64-16
```

Create the application role and database with script 01. Edit the password placeholder first:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U postgres -v ON_ERROR_STOP=1 -f C:\ims\sql\01_create_database.sql
```

Script 01 contains `\connect`, which psql understands. For pgAdmin, use the split instructions in Section 4.6. Scripts 02-04 are only for the manual path; on the Flyway path, run only script 01.

Test:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -h 127.0.0.1 -U ims_app -d item_management_db -c "SELECT current_user, current_database();"
```

### A.4 Build or copy the application

On a Windows build machine with Node.js 22 and JDK 17:

```powershell
# Frontend, from the repository root
npm ci
npm run build

# Backend, from the backend directory
cd backend
.\gradlew.bat clean bootJar -x test
```

Copy:

- the contents of `dist\` to `C:\ims\web\`;
- `backend\build\libs\item-management-backend-1.0.0.jar` to `C:\ims\app\`;
- `docs\sql\` to `C:\ims\sql\` if you want the scripts locally on the server.

Do not copy `node_modules`, `src`, `.git`, or `build` intermediates to `C:\ims\web`.

### A.5 Environment file and permissions

Create `C:\ims\config\ims.env` with the same keys as Section 8.3. Use forward slashes or escaped backslashes only where required; the values themselves do not contain paths except the log file:

```ini
SPRING_PROFILES_ACTIVE=postgres
EMBEDDED_POSTGRES=false
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080
DB_URL=jdbc:postgresql://127.0.0.1:5432/item_management_db
DB_USERNAME=ims_app
DB_PASSWORD=[DB PASSWORD]
DB_POOL_SIZE=15
SPRING_FLYWAY_ENABLED=true
SEED_DEMO=false
JWT_SECRET=[JWT SECRET]
JWT_EXPIRATION_MS=43200000
CORS_ORIGINS=https://admin.mydomain.com,https://app.mydomain.com,https://api.mydomain.com
LOGGING_FILE_NAME=C:/ims/logs/ims-backend.log
```

Generate secrets with PowerShell or OpenSSL:

```powershell
# 24 random bytes rendered as 48 hex characters (database password)
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 24
$rng.GetBytes($bytes)
($bytes | ForEach-Object { $_.ToString("x2") }) -join ''
```

Change `24` to `48` for the JWT secret, which produces 96 hex characters. A simpler method is Git Bash or OpenSSL:

```bash
openssl rand -hex 24   # DB password
openssl rand -hex 48   # JWT secret
```

Restrict the file:

```powershell
icacls C:\ims\config\ims.env /inheritance:r
icacls C:\ims\config\ims.env /grant:r "ims:(R)" "Administrators:(F)"
```

Create `C:\ims\config\pgpass` with the same content as Section 8.4 and restrict it the same way. Set `PGPASSFILE=C:\ims\config\pgpass` for backup jobs.

### A.6 Install the backend as a Windows service with NSSM

Download NSSM 2.24 from the official site and extract `nssm.exe` to `C:\tools\nssm\`. Run PowerShell as Administrator.

```powershell
$java = "C:\Program Files\Eclipse Adoptium\jdk-17.0.x-hotspot\bin\java.exe"

nssm install IMSBackend $java "-Xms256m -Xmx1024m -jar C:\ims\app\item-management-backend-1.0.0.jar"
nssm set IMSBackend AppDirectory "C:\ims\app"
nssm set IMSBackend Start SERVICE_AUTO_START
nssm set IMSBackend AppStdout "C:\ims\logs\ims-backend.out.log"
nssm set IMSBackend AppStderr "C:\ims\logs\ims-backend.err.log"
nssm set IMSBackend AppRotateFiles 1
nssm set IMSBackend AppRotateOnline 1
nssm set IMSBackend AppRotateBytes 10485760
```

Set the environment. NSSM accepts multiple `KEY=VALUE` arguments:

```powershell
nssm set IMSBackend AppEnvironmentExtra `
  "SPRING_PROFILES_ACTIVE=postgres" `
  "EMBEDDED_POSTGRES=false" `
  "SERVER_ADDRESS=127.0.0.1" `
  "SERVER_PORT=8080" `
  "DB_URL=jdbc:postgresql://127.0.0.1:5432/item_management_db" `
  "DB_USERNAME=ims_app" `
  "DB_PASSWORD=[DB PASSWORD]" `
  "DB_POOL_SIZE=15" `
  "SPRING_FLYWAY_ENABLED=true" `
  "SEED_DEMO=false" `
  "JWT_SECRET=[JWT SECRET]" `
  "JWT_EXPIRATION_MS=43200000" `
  "CORS_ORIGINS=https://admin.mydomain.com,https://app.mydomain.com,https://api.mydomain.com" `
  "LOGGING_FILE_NAME=C:/ims/logs/ims-backend.log"
```

NSSM stores these values in the service configuration, readable by local administrators. Restrict administrator access and do not copy the service configuration to an untrusted machine. An alternative is a `start-ims.cmd` wrapper with `set` lines and `java -jar`; NSSM runs the wrapper. The wrapper must also be ACL-restricted.

Set the logon account and start:

```powershell
nssm set IMSBackend ObjectName ".\ims" "[IMS USER PASSWORD]"
nssm start IMSBackend
Get-Service IMSBackend
```

If the service fails to start, check:

```powershell
nssm status IMSBackend
Get-Content C:\ims\logs\ims-backend.err.log -Tail 100
Get-Content C:\ims\logs\ims-backend.out.log -Tail 100
```

The `ims` account needs read access to `C:\ims\app`, `C:\ims\config`, and write access to `C:\ims\logs`. Grant it explicitly:

```powershell
icacls C:\ims\app /grant "ims:(OI)(CI)(RX)"
icacls C:\ims\config /grant "ims:(OI)(CI)(RX)"
icacls C:\ims\logs /grant "ims:(OI)(CI)(M)"
```

### A.7 Install NGINX on Windows

1. Download the Windows ZIP from the official NGINX download page.
2. Extract it to `C:\nginx`.
3. Replace `C:\nginx\conf\nginx.conf` with a configuration equivalent to Section 9/10, using Windows paths and forward slashes:

```nginx
worker_processes 1;
events { worker_connections 1024; }

http {
    include       mime.types;
    default_type  application/octet-stream;
    sendfile      on;
    keepalive_timeout 65;

    upstream ims_backend {
        server 127.0.0.1:8080;
    }

    server {
        listen 80;
        server_name admin.mydomain.com app.mydomain.com;

        root C:/ims/web;
        index index.html;

        location /api/ {
            proxy_pass http://ims_backend;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        location / {
            try_files $uri $uri/ /index.html;
        }
    }

    server {
        listen 80;
        server_name api.mydomain.com;

        location / {
            proxy_pass http://ims_backend;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
    }
}
```

This is the HTTP bootstrap configuration. After win-acme issues certificates, add `listen 443 ssl;` server blocks with the certificate paths and keep the HTTP blocks as redirects. Use the same `/api/` proxy and SPA `try_files` rules as Section 10.

4. Test:

```powershell
cd C:\nginx
.\nginx.exe -t
```

5. Run NGINX as a service with NSSM:

```powershell
nssm install IMSNginx "C:\nginx\nginx.exe"
nssm set IMSNginx AppDirectory "C:\nginx"
nssm set IMSNginx Start SERVICE_AUTO_START
nssm start IMSNginx
```

NGINX on Windows is not a native Windows service; NSSM supervises the process. Use:

```powershell
C:\nginx\nginx.exe -s reload
C:\nginx\nginx.exe -s quit
```

after configuration changes. `nginx -t` must pass before reload.

### A.8 SSL with win-acme

1. Download win-acme from the official site.
2. Run `wacs.exe` as Administrator.
3. Choose **Create certificate (full options)**.
4. Enter the three hostnames: `admin.mydomain.com`, `app.mydomain.com`, `api.mydomain.com`.
5. Choose HTTP validation (self-hosting). Port 80 must be open and NGINX must be running.
6. Choose **PEM encoded files** as the certificate output, or the NGINX installation option if your win-acme version provides it.
7. Save the certificate and key to a protected directory, for example `C:\certs\`.
8. Point the NGINX `ssl_certificate` and `ssl_certificate_key` directives at those files.
9. Configure win-acme to run a post-renewal script:

```bat
C:\nginx\nginx.exe -s reload
```

10. Confirm a renewal task exists in Task Scheduler and run a test renewal.

Windows paths in NGINX use forward slashes:

```nginx
ssl_certificate     C:/certs/fullchain.pem;
ssl_certificate_key C:/certs/privkey.pem;
```

Do not expose port 8080 or 5432 in Windows Firewall. Allow only 80 and 443:

```powershell
New-NetFirewallRule -DisplayName "IMS HTTP"  -Direction Inbound -Protocol TCP -LocalPort 80  -Action Allow
New-NetFirewallRule -DisplayName "IMS HTTPS" -Direction Inbound -Protocol TCP -LocalPort 443 -Action Allow
```

### A.9 Windows service commands

```powershell
Get-Service postgresql-x64-16, IMSBackend, IMSNginx
Start-Service IMSBackend
Stop-Service IMSBackend
Restart-Service IMSBackend
Set-Service IMSBackend -StartupType Automatic

Get-Content C:\ims\logs\ims-backend.out.log -Tail 100 -Wait
Get-Content C:\ims\logs\ims-backend.err.log -Tail 100
```

Health check:

```powershell
Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/auth/me" -UseBasicParsing
```

Expected: HTTP 401. `Invoke-WebRequest` throws on non-2xx in older PowerShell versions; use `try/catch` or `curl.exe -i` to see the status.

### A.10 Windows backups

Use Task Scheduler with a PowerShell script (Section 14.8). Run it as `ims`, set `PGPASSFILE`, and verify:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\pg_restore.exe" --list $file | Select-String "TABLE DATA"
```

Test a restore into a scratch database before relying on the task. Keep an off-site copy.

### A.11 Windows differences to remember

| Topic | Linux | Windows |
|---|---|---|
| Service manager | systemd | Windows Services + NSSM |
| Java path | `/usr/bin/java` | `C:\Program Files\Eclipse Adoptium\...\bin\java.exe` |
| App path | `/opt/ims` | `C:\ims` |
| NGINX config | `/etc/nginx/sites-available/ims` | `C:\nginx\conf\nginx.conf` |
| PostgreSQL tools | `/usr/bin/pg_dump` | `C:\Program Files\PostgreSQL\16\bin\pg_dump.exe` |
| TLS | Certbot | win-acme |
| Backups | cron | Task Scheduler |
| Logs | journald/file | NSSM stdout/stderr files + Spring file log |
| Permissions | `chown`/`chmod` | `icacls` + service logon account |

---

## Appendix B – Troubleshooting

### B.1 HTTP 502 Bad Gateway

The browser reaches NGINX, but NGINX cannot reach the backend.

```bash
sudo systemctl status ims-backend --no-pager
sudo journalctl -u ims-backend -n 100 --no-pager
sudo ss -ltnp | grep ':8080'
curl -i http://127.0.0.1:8080/api/auth/me
```

Common causes:

- backend stopped or crash-looping;
- backend bound to a different port;
- `SERVER_ADDRESS` set to something other than `127.0.0.1`/`0.0.0.0`;
- NGINX upstream points to the wrong port;
- SELinux/AppArmor or a firewall rule blocking localhost (unusual on Ubuntu).

Fix the backend first; NGINX cannot compensate for a down service.

### B.2 HTTP 401 on every API request

- A 401 on `/api/auth/me` without a token is expected and is the health check.
- A 401 after login means the token is missing, expired or invalid. The session lifetime is 12 hours by default. Sign in again.
- Changing `JWT_SECRET` invalidates all existing tokens. This is expected.
- The frontend stores the session in browser storage. Clear the site data only if the stored session is corrupt; be aware that this signs the user out.

### B.3 HTTP 403 "Platform administrators cannot access store business data"

This is expected when a platform user calls a store business endpoint. Platform users manage stores, not store data. Use a store account for store operations.

A 403 for a store user usually means the role lacks the required permission or the user is not assigned to the branch. Check the role grants and `user_branches`.

### B.4 Flyway says a table already exists

```
Found non-empty schema "public" without schema history table
```

or

```
relation "stores" already exists
```

You ran scripts 02-04 on a database and then started the backend with Flyway enabled, or you restored a manual schema. Choose one path:

- **Flyway path:** use an empty database, run only script 01, and let Flyway create the schema.
- **Manual path:** keep `SPRING_FLYWAY_ENABLED=false`.

Do not drop a production database to "fix" this without a verified backup and an approved change.

### B.5 Hibernate schema validation fails

```
Schema-validation: missing table [stores]
```

The database is empty or the manual scripts did not complete. Run Flyway (empty DB, Flyway enabled) or scripts 02-04 (manual path), then restart. Do not set `ddl-auto=update`; the application is configured for `validate`, and changing it would create an unmanaged schema.

### B.6 `password authentication failed for user "ims_app"`

- The password in `ims.env` does not match the database role.
- Reset it as the PostgreSQL superuser:

```bash
sudo -u postgres psql -c "ALTER ROLE ims_app WITH PASSWORD '[NEW DB PASSWORD]';"
```

Then update `DB_PASSWORD` in `ims.env` and the `pgpass` file, and restart the backend. Use a password without shell-special characters, or quote it correctly.

- Confirm `pg_hba.conf` uses `scram-sha-256` for `127.0.0.1/32`, and reload PostgreSQL.
- Confirm you are connecting to `127.0.0.1`, not an old PostgreSQL instance on another port.

### B.7 `Connection refused` to PostgreSQL

```bash
sudo systemctl status postgresql --no-pager
sudo pg_lsclusters
sudo ss -ltnp | grep 5432
```

Start the cluster, fix `listen_addresses`, or correct `DB_URL`. The application uses `127.0.0.1:5432`; if PostgreSQL listens only on a different address, the connection fails.

### B.8 Backend will not start: `Port 8080 was already in use`

```bash
sudo ss -ltnp | grep ':8080'
```

Stop the other process. It may be an old manually started JAR or a second service instance. Do not kill an unknown process without identifying it. If it is the service, use `systemctl restart ims-backend`; if it is a manual `java -jar`, stop that process.

### B.9 `status=203/EXEC` or service cannot find Java

- `ExecStart` points to a Java path that does not exist. Check `readlink -f "$(command -v java)"` and update the unit.
- The JAR path is wrong or the file is not readable by `ims`. Check:

```bash
sudo -u ims test -r /opt/ims/app/item-management-backend-1.0.0.jar && echo readable
```

- After editing the unit: `sudo systemctl daemon-reload`.

### B.10 Service starts but cannot write the log file

`ProtectSystem=full` and `ReadWritePaths` restrict writes. Confirm the log path is under `/opt/ims/logs` and that the directory is owned by `ims`. If you change `LOGGING_FILE_NAME`, add the new directory to `ReadWritePaths` and run `daemon-reload`.

### B.11 Blank page or old frontend after deployment

- Confirm `/opt/ims/web/index.html` exists.
- Confirm NGINX `root` is `/opt/ims/web`, not `/opt/ims/web/dist`.
- Confirm `try_files $uri $uri/ /index.html;` is present.
- Check the browser console and the Network tab for 404s on `/assets/...`.
- Hard-refresh or clear the site cache. `index.html` is `no-store`, but a proxy or browser can still cache it incorrectly.
- Confirm you uploaded the **contents** of `dist/`, not the folder.
- Confirm the SPA calls `/api/...` on the same hostname; a CORS or mixed-content error indicates a different base URL or an HTTP page loading HTTPS resources.

### B.12 Deep link returns 404

The SPA fallback is missing. Add or correct:

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

Then `sudo nginx -t && sudo systemctl reload nginx`.

### B.13 Certificate issuance or renewal fails

- All three DNS names must resolve to the server.
- Port 80 must be open in UFW and reachable from the internet.
- Cloudflare proxying can block HTTP-01; use DNS-only during issuance or DNS-01.
- Another server block may be answering the challenge; `nginx -T` shows the effective configuration.
- Check `/var/log/letsencrypt/letsencrypt.log`.
- Do not retry blindly if Let's Encrypt reports a rate limit; wait and fix the cause.

### B.14 Login works on `localhost` but not through the domain

- Check the browser request URL and status in developer tools.
- Check NGINX access/error logs: `/var/log/nginx/access.log`, `/var/log/nginx/error.log`.
- Confirm `/api/` is proxied on the exact hostname.
- Confirm the backend's `CORS_ORIGINS` includes the HTTPS origin if a cross-origin client is used.
- Confirm the page is HTTPS and the API URL is relative, avoiding mixed content.

### B.15 Demo store `ABC001` appears in production

`SEED_DEMO` was `true` (the default) during the first start. Set `SEED_DEMO=false` and restart. The existing demo rows remain; the seeder does not delete data. Decide with the business whether to deactivate the demo store or rebuild production from a clean database. Do not delete business history casually.

### B.16 The default `platform` account reappears

You deleted the row. `DataSeeder` recreates it on every startup if it is absent. Do not delete it. Set it to `INACTIVE` and use your own platform account, or keep the username `platform` and change its password/details (Section 11).

### B.17 `pg_restore` fails with an unsupported version

Use a `pg_restore` client that is the same version or newer than the dump's server. For a PostgreSQL 16 target, use the 16 client tools. Do not use an older client to restore a newer dump.

### B.18 Disk fills up

Check:

```bash
df -h
sudo du -sh /opt/ims/backups /opt/ims/logs /var/log 2>/dev/null
```

Reduce backup retention, rotate logs, and move off-site copies off the server. Do not delete the current database files to free space.

### B.19 Time or dates are wrong

```bash
timedatectl
```

Set UTC on the server. The application stores UTC timestamps and formats them for display. A server timezone change does not rewrite existing timestamps; do not attempt to "fix" historical data by changing the database timezone.

### B.20 Where to look first

| Component | Command |
|---|---|
| Backend application log | `sudo journalctl -u ims-backend -n 200 --no-pager` |
| Backend errors only | `sudo journalctl -u ims-backend -p err --since today` |
| PostgreSQL log | `/var/log/postgresql/postgresql-16-main.log` |
| NGINX access/error | `/var/log/nginx/access.log`, `/var/log/nginx/error.log` |
| Certificate issuance | `/var/log/letsencrypt/letsencrypt.log` |
| Service state | `systemctl status postgresql ims-backend nginx` |
| Ports | `sudo ss -ltnp` |
| Database connectivity | `psql "host=127.0.0.1 dbname=item_management_db user=ims_app" -c "select 1"` |

---

## Appendix C – Command cheat sheet

### C.1 Build and upload

```bash
# Frontend
npm ci
npm run build
rsync -avz --delete dist/ deploy@[SERVER IP]:/tmp/ims-web/

# Backend (Gradle, not Maven)
cd backend
./gradlew clean bootJar -x test
scp build/libs/item-management-backend-1.0.0.jar deploy@[SERVER IP]:/tmp/
```

### C.2 Install artifacts on the server

```bash
sudo rsync -a --delete /tmp/ims-web/ /opt/ims/web/
sudo chown -R root:www-data /opt/ims/web
sudo find /opt/ims/web -type d -exec chmod 755 {} \;
sudo find /opt/ims/web -type f -exec chmod 644 {} \;

sudo install -o ims -g ims -m 640 \
  /tmp/item-management-backend-1.0.0.jar \
  /opt/ims/app/item-management-backend-1.0.0.jar
```

### C.3 Services

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now postgresql
sudo systemctl enable --now ims-backend
sudo systemctl enable --now nginx

sudo systemctl status postgresql ims-backend nginx --no-pager
sudo systemctl restart ims-backend
sudo systemctl reload nginx
```

### C.4 Health and logs

```bash
curl -i http://127.0.0.1:8080/api/auth/me
curl -I https://app.mydomain.com/
sudo journalctl -u ims-backend -f
sudo journalctl -u ims-backend -p err --since today
sudo tail -f /var/log/nginx/error.log
```

### C.5 Database

```bash
sudo -u postgres psql -d item_management_db
sudo -u postgres pg_dump -Fc -f /tmp/ims.dump item_management_db
sudo -u postgres pg_restore -l /tmp/ims.dump | head
```

### C.6 Backup

```bash
sudo -u ims /opt/ims/backups/backup-ims.sh
ls -lh /opt/ims/backups/
sudo -u ims crontab -l
```

### C.7 TLS

```bash
sudo certbot certificates
sudo certbot renew --dry-run
sudo nginx -t
```

### C.8 Ports

```bash
sudo ss -ltnp | grep -E ':(22|80|443|8080|5432)\b'
sudo ufw status verbose
```

### C.9 Secrets

```bash
openssl rand -hex 24   # database password
openssl rand -hex 48   # JWT secret
sudo stat -c '%U:%G %a %n' /opt/ims/config/ims.env
```

### C.10 Database sanity queries

```sql
SELECT count(*) FROM permissions;                                      -- 28
SELECT count(*) FROM roles WHERE store_id IS NULL;                      -- 1
SELECT count(*) FROM stores;                                           -- your stores
SELECT count(*) FROM information_schema.tables
 WHERE table_schema='public' AND table_type='BASE TABLE'
   AND table_name <> 'flyway_schema_history';                          -- 23
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT username, status FROM users WHERE store_id IS NULL;
```

---

**End of document.**

This guide describes deployment of the existing application version 1.0.0. It does not replace application source code, change the schema, or add features. If the application is changed in the future, re-verify the JAR name, version, migration files, environment keys and build commands against the updated repository before reusing these instructions.
