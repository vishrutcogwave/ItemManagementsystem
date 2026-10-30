# 16–21. PostgreSQL Installation, Migration and Operations

This part is an operator/DBA guide. It describes manual actions only; none of the commands below were executed while producing this documentation. Every path, port, credential and version marked **actual** was verified against the running project.

> **Do not use the embedded server for production.** It runs with `trust` authentication, `fsync` disabled (`-F`) and `synchronous_commit=off`. It is a development convenience, not a durable database.

---

## 16. PostgreSQL Installation on Windows

### 16.1 What is already installed, and what is missing

The repository does **not** install PostgreSQL as a Windows service. `EmbeddedPostgresConfig` starts a real PostgreSQL 14.10 server from binaries extracted into the OS temp folder. That distribution contains only the server utilities:

```
initdb.exe  pg_ctl.exe  postgres.exe  + DLLs
```

Verified: it does **not** contain `psql.exe`, `pg_dump.exe`, `pg_restore.exe` or `pgAdmin`. To administer, back up or migrate the embedded database you must install the PostgreSQL client tools separately (the standard EDB installer includes them).

### 16.2 Terminology

| Term | Meaning in this project |
|---|---|
| **Cluster** | One PostgreSQL server installation with one data directory. The embedded server is a cluster; a standalone install is another cluster. |
| **Instance / server** | The running `postgres.exe` process serving a cluster. |
| **Database** | `item_management_db`. There is exactly one for all stores and branches. |
| **Schema** | `public`. All 23 application tables live here; `flyway_schema_history` is also here. |
| **Role / login** | PostgreSQL's account object. `postgres` is the superuser; `ims_app` is the dedicated application login. A "user" in the application is a row in `users`, not a PostgreSQL role. |
| **Superuser** | `postgres`. Can do anything. Must not be used by the application in production. |
| **Data directory** | Where the cluster stores its files. Embedded: `backend/.pgdata`. Standalone: usually `C:\Program Files\PostgreSQL\<major>\data`. |
| **Port** | TCP port of the server. Embedded: **54329**. Standalone default: **5432**. |
| **Service** | Windows service that starts PostgreSQL at boot, e.g. `postgresql-x64-16`. The embedded server has no service; it lives and dies with the JVM. |
| **pgAdmin** | Graphical client shipped with the Windows installer. |
| **psql** | Command-line client. Required for scripts that use `\connect`, `\echo` or `\i`. |
| **pg_dump / pg_restore** | Logical backup and restore utilities. Version must be equal to or newer than the server. |
| **Tablespace** | Physical storage location. Not used here; everything is in the default tablespace. |

### 16.3 Install with the EDB installer

1. Download the official Windows installer from the PostgreSQL site (`https://www.postgresql.org/download/windows/`, which links to EnterpriseDB) or directly from EnterpriseDB.
2. Choose a version:
   - **PostgreSQL 16.x — recommended for a new standalone/production server.** It is supported by the Spring Boot 3.3.5 / Flyway 10 line used here.
   - **PostgreSQL 14.x — choose this if you want the standalone server to match the embedded 14.10 exactly.** PostgreSQL 14 reaches end of life in November 2026, so do not start a long-lived production deployment on it without a plan to upgrade.
   - Do not install a version older than 14. The schema and dumps target 14+.
3. Run the installer as Administrator. Accept the default components:
   - PostgreSQL Server
   - pgAdmin 4
   - Command Line Tools
   - Stack Builder is optional; this project needs no additional Stack Builder packages.
4. Data directory: default is fine (`C:\Program Files\PostgreSQL\<major>\data`). It must be on a local, backed-up volume with enough free space.
5. Port: **5432** unless it is already occupied. If another PostgreSQL instance uses 5432, choose 5433 and use that port consistently in `DB_URL`.
6. Superuser password: set a long random password for `postgres` and store it in a password manager. This password is for administration only; the application must not use it.
7. Locale/encoding: the default is fine. The application database is created explicitly as UTF8 from `template0`.
8. Finish. The installer registers a Windows service named `postgresql-x64-<major>` and starts it.

> The installer's `postgres` operating-system account is not the same thing as the `postgres` database role. The service normally runs as `NT AUTHORITY\NetworkService`; the database role is what you authenticate with.

### 16.4 Verify the installation

Open a new PowerShell window (so the updated `PATH` is visible), or use full paths.

```powershell
psql --version
pg_isready -h localhost -p 5432
psql -h localhost -p 5432 -U postgres -d postgres -c "SELECT version();"
```

Expected: a version line such as `PostgreSQL 16.x`, and `localhost:5432 - accepting connections`.

If `psql` is not on `PATH`, use:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" --version
```

Add `C:\Program Files\PostgreSQL\16\bin` to the system `PATH` if you want the short commands to work. This is optional and does not affect the application.

### 16.5 Connect with pgAdmin

1. Start **pgAdmin 4** from the Start menu.
2. Right-click **Servers → Register → Server**.
3. General tab: name it `IMS local`.
4. Connection tab:
   - Host: `localhost`
   - Port: `5432`
   - Maintenance database: `postgres`
   - Username: `postgres`
   - Password: the superuser password; enable **Save password** only on a trusted workstation.
5. Save. Expand **Databases** to confirm the server is reachable.
6. For day-to-day application work, register a second connection using `ims_app` and database `item_management_db`, not the superuser.

pgAdmin's Query Tool does **not** understand psql meta-commands. Script `01_create_database.sql` uses `\connect` and script `05_database_validation.sql` uses `\echo`; run those with `psql`, or execute their statements manually in the correct database.

### 16.6 Useful psql commands

```text
\l                 list databases
\c dbname          connect to a database
\dt                list tables
\d+ tablename      describe a table, indexes and constraints
\du                list roles
\df                list functions
\dn                list schemas
\x                 toggle expanded output
\q                 quit
```

Run a file:

```powershell
psql -h localhost -p 5432 -U postgres -d postgres -v ON_ERROR_STOP=1 -f "D:\vishrut\Clone\ItemManagementSystem\docs\sql\01_create_database.sql"
```

`ON_ERROR_STOP=1` makes psql stop at the first error, which is what you want for a schema build. Do **not** add `--single-transaction` to script 01: `CREATE DATABASE` cannot run inside a transaction block.

### 16.7 Service and configuration files

| Item | Typical value |
|---|---|
| Service name | `postgresql-x64-16` (or `...-14`) |
| Main configuration | `C:\Program Files\PostgreSQL\16\data\postgresql.conf` |
| Client authentication | `C:\Program Files\PostgreSQL\16\data\pg_hba.conf` |
| Server log | `C:\Program Files\PostgreSQL\16\data\log\` |
| Default listen address | `localhost` |
| Default port | `5432` |

Windows service control (Administrator PowerShell):

```powershell
Get-Service postgresql-x64-16
Stop-Service postgresql-x64-16
Start-Service postgresql-x64-16
Restart-Service postgresql-x64-16
```

Reload configuration after editing `pg_hba.conf` (superuser connection):

```sql
SELECT pg_reload_conf();
```

A restart is required for changes to `listen_addresses`, `port` and `ssl`.

### 16.8 Where the embedded database actually lives

These values were inspected on this machine; they are not assumptions.

| Item | Actual value |
|---|---|
| Server version | **PostgreSQL 14.10** |
| Port | **54329** (`EMBEDDED_POSTGRES_PORT`) |
| Data directory | `D:\vishrut\Clone\ItemManagementSystem\backend\.pgdata` (resolved from `./.pgdata`) |
| `PG_VERSION` | `14` |
| Data directory size | ~53 MB |
| Database | `item_management_db` (created by the config if absent) |
| Superuser | `postgres` / `postgres` |
| Authentication | `trust` for local, 127.0.0.1/32 and ::1/128 |
| Listen address | localhost only |
| Server flags | `-F` (fsync off), `synchronous_commit=off`, `max_connections=300`, `timezone=UTC` |
| Extracted binaries | `C:\Users\ss\AppData\Local\Temp\embedded-pg\PG-d82e1c10a286067facbb1bf90aa50494\bin` |
| Lock file | `backend\.pgdata\epg-lock` |

How to confirm these facts yourself, without changing anything:

```powershell
Get-Content "D:\vishrut\Clone\ItemManagementSystem\backend\.pgdata\PG_VERSION"
Get-Content "D:\vishrut\Clone\ItemManagementSystem\backend\.pgdata\postmaster.opts"
Get-Content "D:\vishrut\Clone\ItemManagementSystem\backend\.pgdata\postmaster.pid"
Get-ChildItem "$env:TEMP\embedded-pg"
Get-Process postgres | Select-Object Id, Path
```

`postmaster.opts` contains the exact binary path, data directory and port. The second line of `postmaster.pid` is the data directory. The hash in the temp path (`PG-d82e1c...`) is machine-specific; do not copy it into scripts.

**The data directory is persistent and project-relative; the binaries are in a temp folder.** Deleting the temp binaries does not delete data. Deleting `backend/.pgdata` destroys all embedded data. Never commit `.pgdata` to source control.

### 16.9 Embedded versus standalone

| Aspect | Embedded (default) | Standalone (`postgres` profile) |
|---|---|---|
| Install required | No | Yes |
| Server lifecycle | Started/stopped by the JVM | Windows service |
| Port | 54329 | 5432 (typical) |
| Data directory | `backend/.pgdata` | `C:\Program Files\PostgreSQL\<major>\data` |
| Authentication | `trust` | `scram-sha-256` |
| Durability | **fsync off, synchronous_commit off** | Durable defaults |
| Client tools | Server utilities only | `psql`, `pg_dump`, `pg_restore`, pgAdmin |
| Network exposure | localhost only | localhost or private network/VPN |
| Multiple app instances | Not safe against one data dir | Supported with connection limits |
| Backups | Logical dump on 54329 | Logical/physical, WAL archiving |
| Production use | **No** | Yes |

### 16.10 Preserve the embedded data before any change

1. Stop the backend cleanly (Ctrl+C in its terminal, or stop the process). Confirm no `postgres.exe` is using `.pgdata`.
2. Copy the whole `backend/.pgdata` directory to a safe location, e.g. `D:\backups\pgdata-2026-10-30`. Use a copy that preserves ACLs (`robocopy`), not a live sync.
3. Do not delete or rename `.pgdata` until the standalone database has been verified and accepted.
4. Do not start two servers against the same data directory. The `epg-lock` and `postmaster.pid` protect you only partially.

A physical copy of `.pgdata` can only be started by the **same major version** (14). If you need to inspect it later, install PostgreSQL 14 and point `pg_ctl -D` at the copy; otherwise migrate with `pg_dump` as described in section 19.

---

## 17. Create the Application Database

### 17.1 The one-database rule

The application uses exactly one database:

```text
item_management_db
```

All stores and branches share it. Tenants are separated by `store_id`, and branch access is separated by `branch_id` plus `user_branches`. Never create `item_management_db_store1`, `item_management_db_branch2` or a database per tenant.

### 17.2 Run script 01 as the superuser

Connect as `postgres` to the maintenance database, then run the script with psql:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" `
  -h localhost -p 5432 -U postgres -d postgres `
  -v ON_ERROR_STOP=1 `
  -f "D:\vishrut\Clone\ItemManagementSystem\docs\sql\01_create_database.sql"
```

The script:

1. creates the login `ims_app` (`LOGIN`, `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOREPLICATION`, `NOBYPASSRLS`);
2. creates `item_management_db` owned by `ims_app`, UTF8, from `template0`;
3. connects to the new database, makes `ims_app` the owner of schema `public`, and revokes `CREATE`/`ALL` on `public` from `PUBLIC`;
4. sets database defaults: `timezone=UTC`, `statement_timeout=60s`, `idle_in_transaction_session_timeout=120s`;
5. prints read-only verification queries.

Before running, replace `CHANGE_ME_STRONG_PASSWORD` in the script with a long random secret **or** create the role without a literal password and set it interactively:

```sql
CREATE ROLE ims_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS;
\password ims_app
```

`CREATE DATABASE` cannot be wrapped in a transaction. Run the file with psql in autocommit mode, as shown. It is intentionally not idempotent for `CREATE DATABASE`: if the role or database already exists, skip that statement instead of forcing a re-run.

### 17.3 Create the same objects with pgAdmin

If you cannot use psql:

1. Connect as `postgres`.
2. **Login/Group Roles → Create → Login/Group Role**:
   - Name `ims_app`
   - Definition: Password = strong secret
   - Privileges: Can login = Yes; Superuser = No; Create databases = No; Create roles = No; Replication = No; Bypass RLS = No.
3. **Databases → Create**:
   - Database `item_management_db`
   - Owner `ims_app`
   - Encoding `UTF8`
   - Template `template0`
4. Connect to `item_management_db` and open Query Tool:
   ```sql
   ALTER SCHEMA public OWNER TO ims_app;
   REVOKE CREATE ON SCHEMA public FROM PUBLIC;
   REVOKE ALL ON SCHEMA public FROM PUBLIC;
   GRANT USAGE, CREATE ON SCHEMA public TO ims_app;
   ALTER DATABASE item_management_db SET timezone TO 'UTC';
   ALTER DATABASE item_management_db SET statement_timeout TO '60s';
   ALTER DATABASE item_management_db SET idle_in_transaction_session_timeout TO '120s';
   ```
   Run these statements one at a time or as a single script; none of them is `CREATE DATABASE`, so a transaction is acceptable here.

### 17.4 Password handling

- Do not put the real password in `application.yml`, source control, documentation, chat or a screenshot.
- Supply it at runtime through `DB_PASSWORD`, a secret manager, or a restricted `pgpass` file.
- On Windows, psql/pg_dump read `%APPDATA%\postgresql\pgpass.conf` by default. The file must be readable only by the service account that runs the backup. Format:
  ```text
  localhost:5432:item_management_db:ims_app:<password>
  ```
- Prefer `\password ims_app` (interactive, never echoed) over a literal in a SQL file.
- Rotate the password with:
  ```sql
  ALTER ROLE ims_app PASSWORD '<new-secret>';
  ```
  Then update the secret store and restart the application pool.

### 17.5 Verify the database and role

Connect as `postgres` and run the checks in `01_create_database.sql`, or run these directly:

```sql
SELECT current_setting('server_version') AS server_version;

SELECT datname, pg_get_userbyid(datdba) AS owner,
       pg_encoding_to_char(encoding) AS encoding
FROM pg_database
WHERE datname = 'item_management_db';

SELECT rolname, rolsuper, rolcanlogin, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'ims_app';

SELECT nspname, pg_get_userbyid(nspowner) AS owner
FROM pg_namespace
WHERE nspname = 'public';
```

Expected: `item_management_db` owned by `ims_app`, encoding `UTF8`; `ims_app` with `rolsuper = false`, `rolcanlogin = true`, `rolcreatedb = false`, `rolcreaterole = false`; `public` owned by `ims_app`.

Then test the application login:

```powershell
psql -h localhost -p 5432 -U ims_app -d item_management_db -c "SELECT current_user, current_database();"
```

It must succeed without the superuser password.

### 17.6 Point the application at the standalone server

The `postgres` Spring profile disables embedded PostgreSQL and reads the connection from environment variables:

| Variable | Default in `application.yml` | Recommended |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | none (embedded) | `postgres` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/item_management_db` | explicit URL, host/port |
| `DB_USERNAME` | `postgres` | `ims_app` |
| `DB_PASSWORD` | `postgres` | strong secret, not in source |
| `DB_POOL_SIZE` | `15` | sized to the server |
| `JWT_SECRET` | development secret | long random production secret |
| `JWT_EXPIRATION_MS` | `43200000` (12 h) | keep or shorten |

PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE = "postgres"
$env:DB_URL = "jdbc:postgresql://localhost:5432/item_management_db"
$env:DB_USERNAME = "ims_app"
$env:DB_PASSWORD = "<read-from-secret-store>"
$env:DB_POOL_SIZE = "15"
$env:JWT_SECRET = "<read-from-secret-store>"
java -jar build\libs\item-management-backend-1.0.0.jar
```

Bash (Git Bash):

```bash
SPRING_PROFILES_ACTIVE=postgres \
DB_URL='jdbc:postgresql://localhost:5432/item_management_db' \
DB_USERNAME=ims_app \
DB_PASSWORD='<read-from-secret-store>' \
DB_POOL_SIZE=15 \
JWT_SECRET='<read-from-secret-store>' \
java -jar build/libs/item-management-backend-1.0.0.jar
```

Start from the `backend` directory (the jar path is relative to it). With the `postgres` profile, `app.embedded-postgres.enabled` is forced to `false`; no embedded server is started.

### 17.7 First start: let Flyway build the schema

On an **empty** `item_management_db`, start the application with the `postgres` profile and Flyway enabled (the default). Flyway applies:

| Version | File | Content |
|---|---|---|
| 1 | `V1__init.sql` | 23 tables, constraints, indexes, sequences, 26 permissions, `PLATFORM_ADMIN` role and grants |
| 2 | `V2__tax_master.sql` | tax master, item tax link, tax snapshots, `TAX_VIEW`/`TAX_MANAGE`, GST backfill |
| 3 | `V3__item_rate.sql` | single `items.rate`; removes the two legacy price columns |

Hibernate runs with `ddl-auto=validate`, so a schema mismatch fails startup instead of silently altering tables.

Do **not** run `02_create_tables.sql` on a database that Flyway manages. See section 18 for the manual path.

### 17.8 Common problems

| Symptom | Cause | Fix |
|---|---|---|
| `password authentication failed for user "ims_app"` | Wrong password, or `pg_hba.conf` still uses a conflicting rule | Re-set with `\password`; inspect `pg_hba.conf`; reload |
| `FATAL: role "ims_app" does not exist` | Script 01 skipped or run against the wrong cluster | Create the role on the server you actually connect to |
| `database "item_management_db" does not exist` | `CREATE DATABASE` failed or wrong port | Create it as `postgres`; verify `DB_URL` port |
| `permission denied for schema public` | Schema owner/grant missing | Run the grants from 17.3 as superuser |
| `Connection refused` | Service stopped, wrong port, or `listen_addresses` excludes the interface | Start service; check `port` and `listen_addresses` |
| Flyway `found non-empty schema ... without schema history` | Tables were created manually while Flyway was enabled | Choose one path; see 18.4; do not baseline blindly |
| `Unable to obtain connection from pool` | DB reachable but credentials/pool exhausted | Check `pg_stat_activity`, pool size and network |

---

## 18. SQL Scripts

All scripts are in `docs/sql/`. They are for manual use; the application does not read or execute them.

### 18.1 Script inventory

| Script | What it does | Modifies data? | Prerequisites |
|---|---|---|---|
| `01_create_database.sql` | Creates `ims_app`, `item_management_db`, schema ownership, database defaults | Creates cluster/database objects | Superuser, empty target, psql (uses `\connect`) |
| `02_create_tables.sql` | Final schema after V1+V2+V3: 23 tables, constraints, 4 number sequences | Yes (DDL) | Connected to `item_management_db`; empty `public` schema; Flyway disabled |
| `03_create_indexes.sql` | Recreates the 19 non-constraint indexes | Yes (DDL) | After 02 |
| `04_insert_initial_data.sql` | 28 permissions, `PLATFORM_ADMIN` + 3 grants, backfills GST slabs and inserts missing standard store roles (without permission grants) for existing stores | Yes (reference data only) | After 02/03 |
| `05_database_validation.sql` | Read-only schema, tenancy, inventory, GST, purchase/sale/transfer checks | **No** | Any time; psql for `\echo` |
| `06_sample_business_flow.sql` | One-shot demo tenant `DOC001`: store, branches, items, supplier, opening stock, purchase, sale, POS, transfer, adjustment | Yes (demo data) | After 04; disposable database only |

### 18.2 Choose exactly one schema path

**Path A — Flyway (recommended)**

1. Run `01_create_database.sql` as superuser.
2. Start the application with the `postgres` profile. For anything other than a local demo, set `SEED_DEMO=false`: the default `true` creates the `ABC001` / ABC Dairy demo tenant.
3. Flyway applies V1–V3 automatically. Do not run 02 or 03.
4. `04_insert_initial_data.sql` is not required: V1/V2 already insert the permission catalogue, platform role and tax permissions, and V2 backfills GST slabs for existing stores. Script 04 is a safe repair/backfill only.

**Path B — Manual DBA build**

1. Run `01_create_database.sql` as superuser.
2. Run `02_create_tables.sql` as `ims_app` (or the database owner).
3. Run `03_create_indexes.sql`.
4. Run `04_insert_initial_data.sql`.
5. Start the application with **Flyway disabled**:
   ```powershell
   $env:SPRING_PROFILES_ACTIVE = "postgres"
   $env:SPRING_FLYWAY_ENABLED = "false"
   $env:SEED_DEMO = "false"
   ```
   or pass `--spring.flyway.enabled=false --app.seed-demo=false`. Leave `SEED_DEMO=true` only if you deliberately want the ABC Dairy demo tenant.
6. The platform administrator is created by `DataSeeder` on first start if absent (`PLATFORM` / `platform` / `Admin@123`). Change that password immediately. The optional pgcrypto block at the bottom of script 04 is an alternative that bypasses application audit logging.

Never mix the paths. Running 02 against a Flyway database fails because the tables exist. Baselining a manual schema at version 1 and then replaying V2/V3 duplicates changes.

### 18.3 Run scripts safely

From the `docs/sql` directory, with psql:

```powershell
# 01 must be run as the superuser and uses \connect
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -h localhost -p 5432 -U postgres -d postgres `
  -v ON_ERROR_STOP=1 -f "01_create_database.sql"

# 02, 03, 04 run as the owner of the application schema
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -h localhost -p 5432 -U ims_app -d item_management_db `
  -v ON_ERROR_STOP=1 -f "02_create_tables.sql"

& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -h localhost -p 5432 -U ims_app -d item_management_db `
  -v ON_ERROR_STOP=1 -f "03_create_indexes.sql"

& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -h localhost -p 5432 -U ims_app -d item_management_db `
  -v ON_ERROR_STOP=1 -f "04_insert_initial_data.sql"
```

Notes:

- Do not use `--single-transaction` for 01 (`CREATE DATABASE`). It is safe for 02/03; script 04 has its own `BEGIN/COMMIT`, so do not wrap it again.
- Use `-v ON_ERROR_STOP=1` so a partial build stops immediately.
- The files are UTF-8. If your terminal renders non-ASCII characters incorrectly, set `PGCLIENTENCODING=UTF8`.
- `05` and `06` also use psql-specific features; run them with psql, or adapt them for pgAdmin by removing `\echo` and executing the `SELECT`s in order.

### 18.4 Script 05 — validation

`05_database_validation.sql` is read-only. Run it after any build, restore or migration:

```powershell
psql -h localhost -p 5432 -U ims_app -d item_management_db `
  -v ON_ERROR_STOP=1 -f "05_database_validation.sql"
```

It checks:

- runtime version, user, timezone;
- Flyway history (three successful versions when Flyway managed the schema);
- all 23 expected tables and the 19 indexes plus `uq_roles_store_code`;
- schema markers (`items.rate`, no legacy price columns, tax snapshot columns);
- sequence values (purchase/sales/transfer/adjustment, start 1001);
- 28 permissions and the platform role with 3 grants;
- cross-tenant references in users/roles/branches/items/purchases/sales;
- negative stock, ledger balance, inventory snapshot vs ledger, zero-quantity movements;
- purchase/sales header arithmetic and GST snapshot sums;
- tax master split integrity;
- transfer line movements;
- master-data sanity and an operational row-count summary.

Integrity sections print rows only when something is wrong. An empty result means the check passed. Section 15 always prints counts.

### 18.5 Script 06 — demo flow

`06_sample_business_flow.sql` creates a complete demo tenant `DOC001` on a **disposable** database. It is not production seed data and must never be run against a live database.

What it demonstrates:

- store, two branches, roles, GST slab, category, two items, supplier;
- opening stock with `OPENING_STOCK` ledger rows;
- a completed purchase: 50 L × 48.00 = 2,400.00 taxable + 120.00 GST = 2,520.00;
- a normal sale: 10 L × 70.00 = 700.00 + 35.00 GST = 735.00;
- a POS sale: 5 PKT × 40.00 = 200.00 + 10.00 GST = 210.00;
- a transfer of 20 L at source average cost 46.0000;
- a 2 L `ADJUSTMENT_OUT` for spillage;
- final stock: MAIN milk 118, MAIN curd 45, SEC milk 20.

It does **not** create users (BCrypt hashes must come from the application), call the API, or exercise row locking. It runs in one transaction and aborts if `DOC001` already exists. Do not delete a demo tenant by deleting business history in a real database; drop the disposable database instead.

### 18.6 Post-build checklist

1. Run `05_database_validation.sql`; investigate every non-empty integrity result.
2. Confirm `items.rate` exists and `default_selling_price` / `default_purchase_price` do not.
3. Confirm 28 permissions and `PLATFORM_ADMIN` with 3 grants.
4. Confirm sequences start at 1001 (before first use they may show `last_value = 1000` or `NULL` depending on version).
5. Start the application and sign in. With Flyway, `flyway_schema_history` must show versions 1, 2, 3 as successful.
6. Change the seeded platform password before any shared or production use.

---

## 19. Migrate Embedded PostgreSQL to Standalone

This is the cutover from the development cluster on port **54329** / `backend/.pgdata` to a standalone server. The recommended method is a **logical dump and restore**, not copying the data directory.

### 19.1 Preconditions

- The standalone PostgreSQL server is installed and running (section 16).
- The PostgreSQL **client tools** (`pg_dump`, `pg_restore`, `psql`) are installed. The embedded distribution does not include them.
- `pg_dump --version` is **14.10 or newer** and is at least the source server major version. A PostgreSQL 16 `pg_dump` can dump a 14.10 server; a PostgreSQL 13 `pg_dump` cannot.
- You have enough free disk space for the dump plus the target database.
- You have a maintenance window or can stop writes. A dump is transactionally consistent, but stopping writes makes the cutover easier to reason about.
- You have tested the procedure on a copy first. Do not experiment on the only copy of production data.

### 19.2 Step 1 — identify the source runtime

```powershell
Get-Content "D:\vishrut\Clone\ItemManagementSystem\backend\.pgdata\PG_VERSION"
Get-Content "D:\vishrut\Clone\ItemManagementSystem\backend\.pgdata\postmaster.opts"
Get-Process postgres | Select-Object Id, Path
```

Expected: `14`, a `postgres.exe` path under `%TEMP%\embedded-pg\PG-<hash>\bin`, data directory `...\backend\.pgdata`, port `54329`.

### 19.3 Step 2 — stop the application cleanly

Stop the backend (Ctrl+C or stop the process). The `@PreDestroy` hook calls `EmbeddedPostgres.close()`, which performs a clean shutdown. Wait until no `postgres.exe` is serving `.pgdata`.

If the process crashed and a stale `postmaster.pid` remains, first confirm that no live process uses the directory. Do not delete the PID file while the server is running.

### 19.4 Step 3 — create the target database and application login

On the standalone server, run `01_create_database.sql` as the superuser. This creates `ims_app` and an **empty** `item_management_db` owned by `ims_app`. Do not run Flyway yet.

Verify:

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database WHERE datname = 'item_management_db';
```

### 19.5 Step 4 — start the embedded server for the dump

The embedded server has no Windows service. Choose one of these:

**Option A — let the application start it, then dump while it runs.** This is the simplest option. The dump uses a repeatable-read snapshot. New writes after the snapshot are not included; for a clean cutover, stop application writes first, or run the dump immediately before the maintenance window.

**Option B — start the embedded server without the application**, using `pg_ctl` from the extracted binaries:

```powershell
# Find the current extracted binary directory (hash varies by machine)
Get-ChildItem "$env:TEMP\embedded-pg"

$pgBin = (Get-ChildItem "$env:TEMP\embedded-pg" -Directory | Select-Object -First 1).FullName + "\bin"
$pgData = "D:\vishrut\Clone\ItemManagementSystem\backend\.pgdata"

& "$pgBin\pg_ctl.exe" -D $pgData `
  -o "-p 54329" `
  -l "$pgData\pg_ctl.log" `
  start
```

Do not run this while the backend is running: two servers must never share one data directory. To stop it:

```powershell
& "$pgBin\pg_ctl.exe" -D $pgData stop -m fast
```

`pg_ctl start` may fail if the data directory is locked or if a stale PID file exists. Fix the cause; do not force-start.

### 19.6 Step 5 — dump the embedded database

Run as any user; the embedded cluster uses `trust` authentication, so no password is required:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\pg_dump.exe" `
  -h localhost -p 54329 -U postgres -d item_management_db `
  -Fc -Z 6 `
  -f "D:\backups\ims_embedded_2026-10-30.dump"
```

- `-Fc` = custom archive, suitable for `pg_restore` and selective restore.
- `-Z 6` = compression.
- The dump includes the schema, all data, sequence values, indexes, constraints and `flyway_schema_history`.
- It does **not** include cluster-level roles or database-level `ALTER DATABASE` settings. The target role is created by script 01; the timeouts are applied there too.
- Roles are cluster objects. If you added roles inside the embedded cluster beyond `postgres`, export them separately with `pg_dumpall --globals-only` as superuser. The application itself creates no PostgreSQL roles.

Verify the archive without restoring:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\pg_restore.exe" --list "D:\backups\ims_embedded_2026-10-30.dump" |
  Select-Object -First 40
```

You should see `flyway_schema_history`, the 23 tables, 4 sequences, indexes and constraints.

### 19.7 Step 6 — restore into the standalone database

Restore as `ims_app` into the empty database created in step 3:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\pg_restore.exe" `
  -h localhost -p 5432 -U ims_app -d item_management_db `
  --no-owner --no-privileges --exit-on-error --verbose `
  "D:\backups\ims_embedded_2026-10-30.dump"
```

- `--no-owner` avoids trying to assign objects to the embedded `postgres` role; objects become owned by `ims_app`.
- `--no-privileges` avoids restoring ACLs that reference roles absent from the target cluster.
- `--exit-on-error` stops at the first failure; do not ignore errors and continue.
- Do not use `--clean` against a database you care about. The target should be empty.
- The restore reads `PGPASSWORD` or prompts. Prefer a `pgpass` file or an interactive prompt; do not put the password on the command line.

If the target database is not empty, stop and decide whether to drop/recreate it. `pg_restore` into a partially populated schema produces confusing errors.

### 19.8 Step 7 — verify the restored database

Run script 05:

```powershell
psql -h localhost -p 5432 -U ims_app -d item_management_db `
  -v ON_ERROR_STOP=1 `
  -f "D:\vishrut\Clone\ItemManagementSystem\docs\sql\05_database_validation.sql"
```

Check specifically:

1. `flyway_schema_history` shows versions 1, 2 and 3 with `success = true`.
2. All 23 tables exist.
3. Row counts match the source for `stores`, `branches`, `users`, `items`, `purchases`, `sales`, `inventory_transactions` and `audit_logs`.
4. Sequence values are at least as high as the source. `pg_dump` restores sequence state; verify rather than assume.
5. No cross-tenant references, negative stock or ledger mismatches.
6. The application login can read and write (run a harmless transaction in a scratch database, not production):
   ```sql
   BEGIN;
   SELECT count(*) FROM items;
   ROLLBACK;
   ```

Compare source and target counts directly if both servers are reachable:

```sql
-- Run on the source (port 54329) and on the target (port 5432), then compare.
SELECT 'stores' AS table_name, count(*) FROM stores
UNION ALL SELECT 'branches', count(*) FROM branches
UNION ALL SELECT 'users', count(*) FROM users
UNION ALL SELECT 'items', count(*) FROM items
UNION ALL SELECT 'purchases', count(*) FROM purchases
UNION ALL SELECT 'sales', count(*) FROM sales
UNION ALL SELECT 'stock_transfers', count(*) FROM stock_transfers
UNION ALL SELECT 'inventory_transactions', count(*) FROM inventory_transactions
UNION ALL SELECT 'audit_logs', count(*) FROM audit_logs;
```

Run `ANALYZE;` on the restored database so the planner has statistics. This is a normal maintenance action, not a migration.

### 19.9 Step 8 — cut the application over

Stop the application if it is still running against the embedded server. Start it with the `postgres` profile and the target credentials (section 17.6). Flyway sees the restored `flyway_schema_history` at version 3 and performs no migration; Hibernate validates the schema.

Do not run with both the embedded config and the `postgres` profile. The profile disables embedded, but the explicit `SPRING_PROFILES_ACTIVE=postgres` is what selects the standalone datasource.

### 19.10 Step 9 — smoke test

1. Sign in as a store user (not the platform user) and load the dashboard.
2. Open items, inventory, purchases and sales; confirm historical rows and totals.
3. Create a **draft** purchase; confirm it does not change stock.
4. Complete a small purchase in a test branch; confirm stock, average cost and ledger.
5. Make a normal sale and a POS sale; confirm rates, GST and stock.
6. Cancel the test sale/purchase only if that is part of the test plan; cancellation creates reversal rows, it does not delete history.
7. Check reports for a date range that includes historical data.
8. Sign in as the platform user and confirm store list behavior.

Record the results. A cutover is not complete until the smoke test passes on the standalone server.

### 19.11 Rollback

The embedded data directory is untouched by the logical migration. To roll back:

1. Stop the standalone-backed application.
2. Unset `SPRING_PROFILES_ACTIVE` (or set it to a profile that does not disable embedded PostgreSQL).
3. Start the application normally; it reopens `backend/.pgdata` on 54329.

Any writes made on the standalone server after cutover are **not** in the embedded database. If you need to preserve them, dump the standalone database before rolling back and reconcile deliberately. Do not assume the two clusters are in sync.

### 19.12 Version compatibility

| Source | Target | Supported? | Notes |
|---|---|---|---|
| 14.10 | 14.x (≥ 14.10) | Yes | Same major version; lowest risk |
| 14.10 | 16.x | Yes | Logical dump/restore upgrades the data; test the application |
| 14.10 | 13.x or older | **No** | Target older than source |
| 14.10 | 17.x | Not validated | Verify Flyway/JDBC support before choosing this |

Use a `pg_dump` binary whose major version is **equal to or newer than** the source. Do not use an older client to dump a newer server.

### 19.13 Alternative — Flyway schema plus data-only restore

Use this only if you want the target schema created by Flyway rather than restored from the dump:

1. Create the empty database with script 01.
2. Start the application once with Flyway enabled to create V1–V3, then stop it.
3. Dump **data only** from the embedded database, excluding Flyway's history:
   ```powershell
   pg_dump -h localhost -p 54329 -U postgres -d item_management_db `
     --data-only --exclude-table=flyway_schema_history `
     -Fc -f "D:\backups\ims_data_only.dump"
   ```
4. Restore with `pg_restore --data-only` into the Flyway-built target.
5. Verify sequence values. If a sequence is behind, set it with `setval` to `max(id)`; never set it lower than existing data.
6. Run script 05.

This path is more fragile than the full dump because it depends on the manual schema and the Flyway schema being identical, and on sequence state being restored correctly. Prefer 19.6–19.8 unless you have a specific reason.

Foreign keys make a data-only load order-sensitive. If the restore reports FK violations, do not disable constraints casually. Either use `--disable-triggers` in a **maintenance session as a superuser** (it is not available to `ims_app`), then re-enable and validate, or load tables in dependency order. A full dump avoids this problem because `pg_restore` creates constraints after the data.

### 19.14 What not to do

- Do not copy a **running** data directory. The files are not a consistent backup while PostgreSQL is writing.
- Do not copy `.pgdata` to a server with a different PostgreSQL major version and expect it to start.
- Do not edit `PG_VERSION`, `postmaster.pid` or `pg_control` to force compatibility.
- Do not restore with `--clean` into a database that contains data you have not backed up.
- Do not run the embedded server and the standalone server against the same data directory.
- Do not delete `.pgdata` after cutover until the standalone database has been verified and backed up.

---

## 20. Production PostgreSQL

### 20.1 Network placement

The database must not be reachable from the public internet.

- **Same host as the application:** set `listen_addresses = 'localhost'` and do not open port 5432 in the firewall.
- **Separate host:** bind to a private interface (e.g. `listen_addresses = '10.0.1.10'`), allow TCP 5432 only from the application host or subnet, and use a private network/VPN. Do not port-forward 5432 from a router or cloud load balancer.
- **Cloud:** put the database in a private subnet with a security group that allows only the application security group. Disable public IP assignment.
- **TLS:** use `hostssl` rules and require `sslmode=verify-full` from the application when the database is not on localhost.

Windows Firewall example (Administrator PowerShell; adjust the subnet):

```powershell
New-NetFirewallRule `
  -DisplayName "PostgreSQL 5432 from app subnet" `
  -Direction Inbound -Protocol TCP -LocalPort 5432 `
  -RemoteAddress 10.0.1.0/24 -Action Allow
```

Windows Firewall blocks inbound traffic by default unless a rule allows it. Do **not** add a broad "Block 5432 from Any" rule alongside the specific allow: block rules take precedence and would also block the application subnet. Review the effective rules with `Get-NetFirewallRule` and test from both an allowed and a disallowed host.

### 20.2 Server hardening checklist

- Install a supported major version (16.x recommended for this application line; 14.x only with an upgrade plan).
- Apply OS and PostgreSQL security updates on a schedule.
- Run the service under a dedicated low-privilege OS account where possible; restrict the data directory ACL to that account and administrators.
- Encrypt the data volume (BitLocker or storage-level encryption).
- Disable unused extensions and services.
- Set `password_encryption = 'scram-sha-256'`.
- Do not use `trust` authentication anywhere in production.
- Do not share the `postgres` superuser. Use it only for administration and break-glass.
- Keep the application role `ims_app` `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOREPLICATION`, `NOBYPASSRLS`.
- Keep `public` schema `CREATE` revoked from `PUBLIC`; only the owner and explicitly granted roles may create objects.
- Configure logging: `log_connections = on`, `log_disconnections = on`, `log_checkpoints = on`, `log_lock_waits = on`, `log_min_duration_statement = 500` (adjust), `deadlock_timeout = '1s'`.
- Set a maintenance window for `VACUUM`/`ANALYZE` and monitor autovacuum.
- Back up before every schema migration and verify the backup.

### 20.3 Roles and privileges

Recommended minimum:

| Role | Purpose | Attributes |
|---|---|---|
| `postgres` | Administration only | Superuser; not used by the app or backups |
| `ims_app` | Application connection and schema owner | LOGIN, NOSUPERUSER, NOCREATEDB, NOCREATEROLE, password, `CONNECT` on the database, owner of `public` and application objects |
| `ims_backup` (optional) | `pg_dump`/backup jobs | LOGIN, `CONNECT`, `USAGE` on schema, `SELECT` on tables, `SELECT` on sequences; no DDL |
| `ims_migrator` (optional) | CI/CD Flyway migrations | LOGIN, schema owner or DDL grants; not used by the runtime application |

If you separate migration and runtime roles:

1. `ims_migrator` owns the schema and runs Flyway.
2. `ims_app` receives `CONNECT`, `USAGE` on `public`, `SELECT/INSERT/UPDATE/DELETE` on tables, and `USAGE/SELECT` on sequences.
3. Run the application with `SPRING_FLYWAY_ENABLED=false`, because `ims_app` cannot create/alter tables.
4. Keep `ddl-auto=validate`; it needs catalog read access, which `ims_app` has.

For this project the simplest safe configuration is: `ims_app` owns the schema, runs Flyway, and is the runtime login. It is not a superuser. The optional split is for organizations that forbid runtime DDL.

Grant examples (run as the schema owner, not as `postgres` where possible):

```sql
GRANT CONNECT ON DATABASE item_management_db TO ims_backup;
GRANT USAGE ON SCHEMA public TO ims_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO ims_backup;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO ims_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO ims_backup;
```

### 20.4 Authentication and TLS

`pg_hba.conf` should be specific and ordered. Sketch for a private subnet:

```text
# TYPE      DATABASE              USER        ADDRESS           METHOD
hostssl    item_management_db    ims_app     10.0.1.0/24       scram-sha-256
hostssl    all                   ims_backup  10.0.1.0/24       scram-sha-256
host       all                   all         127.0.0.1/32      scram-sha-256
host       all                   all         ::1/128           scram-sha-256
```

Do not leave `trust` or `md5` lines for remote hosts. Keep a local administrative path appropriate to your environment; do not expose it to the network.

TLS in `postgresql.conf`:

```ini
ssl = on
ssl_cert_file = 'server.crt'
ssl_key_file = 'server.key'
ssl_min_protocol_version = 'TLSv1.2'
```

The certificate must match the hostname the application uses. Restrict the key file's ACL to the PostgreSQL service account. Restart after changing `ssl`.

JDBC URL with verification:

```text
jdbc:postgresql://db.internal.example:5432/item_management_db?sslmode=verify-full&sslrootcert=C:/certs/root.crt
```

Use `sslmode=require` only when you cannot verify the certificate (e.g. a self-signed certificate on a trusted private network); `verify-full` is preferred. Do not use `sslmode=disable` for remote connections.

### 20.5 Connection pooling

The runtime uses HikariCP. In the `postgres` profile the pool size comes from `DB_POOL_SIZE` (default 15). PostgreSQL's `max_connections` is 100 by default on a standalone install (the embedded server uses 300).

Size the pool with the server in mind:

```text
sum(pool size of every application instance)
  + migration/backup/monitoring connections
  + superuser reserve
  < max_connections
```

Example: 4 application instances × 15 = 60, plus 10 operational connections, against `max_connections = 100`, leaves 30 spare. Do not set a large pool "just in case"; it increases lock contention, memory use and connection overhead.

If you add PgBouncer:

- Use **session pooling** unless you have validated transaction pooling with the driver.
- With transaction pooling, server-side prepared statements can break; set `prepareThreshold=0` in the JDBC URL and validate Hibernate behavior.
- `SELECT ... FOR UPDATE` and `INSERT ... ON CONFLICT` remain correct inside a transaction because the transaction is pinned to one server connection.
- Do not put a transaction pooler between the application and Flyway migrations unless you understand the session-state implications.

### 20.6 PostgreSQL parameters

Baseline suggestions (adjust to hardware and workload):

| Parameter | Suggestion | Why |
|---|---|---|
| `timezone` | `UTC` | The app stores `TIMESTAMPTZ` and Hibernate uses UTC |
| `statement_timeout` | `60s` (database default) | Prevents runaway queries; reports may need a higher per-role value |
| `idle_in_transaction_session_timeout` | `120s` | Kills abandoned transactions that hold locks |
| `lock_timeout` | `5s`–`10s` for the app role | Fails fast instead of queuing behind a lock |
| `deadlock_timeout` | `1s` | Faster deadlock detection |
| `work_mem` | `4MB`–`16MB` per operation | Tune with `EXPLAIN (ANALYZE, BUFFERS)` |
| `maintenance_work_mem` | `256MB`–`1GB` | Vacuum/index builds |
| `shared_buffers` | 25% of RAM (typical) | Cache |
| `effective_cache_size` | 50–75% of RAM | Planner hint |
| `max_connections` | 100–200, sized to pools | Each connection costs memory |
| `log_min_duration_statement` | `500ms` | Slow-query visibility |
| `log_lock_waits` | `on` | Diagnose blocking |
| `log_checkpoints` | `on` | I/O visibility |
| `ssl` | `on` for remote | Encryption |

`statement_timeout=60s` is set at the database level by script 01. Long reports or maintenance jobs may need `SET statement_timeout` for their session; do not disable it globally.

### 20.7 Monitoring

Minimum production monitoring:

| Area | What to watch |
|---|---|
| Availability | `pg_isready`, service state, connection failures |
| Connections | `pg_stat_activity` count by state/user/application; pool saturation |
| Locks | `pg_locks`, `pg_blocking_pids()`, long waits, deadlocks (`pg_stat_database.deadlocks`) |
| Query performance | `pg_stat_statements` (requires `shared_preload_libraries` and `CREATE EXTENSION`), slow-query log |
| Cache/IO | `pg_stat_database.blks_hit/blks_read`, checkpoint stats |
| Table health | `pg_stat_user_tables` sequential scans, dead tuples, last vacuum/analyze |
| Disk | Data directory free space, WAL growth, archive failures |
| Replication | Lag, if using a replica |
| Backups | Last successful dump/base backup, restore drill date |
| Application | Audit log growth, login failures, 5xx rate |

Example connection snapshot:

```sql
SELECT usename, application_name, state, count(*)
FROM pg_stat_activity
WHERE datname = 'item_management_db'
GROUP BY usename, application_name, state
ORDER BY count(*) DESC;
```

The application itself writes `audit_logs` for sensitive actions, but the database has no audit UI; see section 24.

### 20.8 Patching and upgrades

- Test minor upgrades on a copy; PostgreSQL minor versions are binary-compatible within a major version.
- Major upgrades require `pg_upgrade` or dump/restore, plus application regression testing.
- Back up before upgrading; verify the backup can be restored.
- Keep the JDBC driver and Flyway compatible with the server major version.
- After an upgrade, run script 05 and the smoke tests from 19.10.

### 20.9 Go-live checklist

- [ ] Standalone PostgreSQL installed, patched and running as a service.
- [ ] `item_management_db` exists, owned by `ims_app`; one database only.
- [ ] `ims_app` is not a superuser and has no `CREATEDB`/`CREATEROLE`.
- [ ] `public` schema `CREATE` is revoked from `PUBLIC`.
- [ ] Port 5432 is not publicly reachable; firewall/VPN rules tested from outside.
- [ ] TLS enabled and the application verifies the certificate (remote deployments).
- [ ] Strong passwords in a secret store; no credentials in source or scripts.
- [ ] `SPRING_PROFILES_ACTIVE=postgres`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` set.
- [ ] `JWT_SECRET` is a strong production secret; the development default is not used.
- [ ] Flyway applied V1–V3 successfully; `flyway_schema_history` shows three successful rows.
- [ ] Hibernate `validate` passes at startup.
- [ ] Script 05 returns no integrity rows.
- [ ] Backup job runs, is monitored, and a restore has been tested.
- [ ] `SEED_DEMO=false`; no demo tenant (`ABC001`) or demo transactions in production.
- [ ] Default platform password changed before the system is used by anyone else.
- [ ] Monitoring and log retention configured.
- [ ] Smoke test (19.10) passed.

---

## 21. Backup and Restore

### 21.1 What must be protected

- The single database `item_management_db` (all tenants, all history).
- Cluster-level roles and authentication configuration (`pg_hba.conf`, role passwords) if you use a standalone cluster.
- Application secrets (`JWT_SECRET`, DB password) are not in the database but are required for recovery.
- Uploaded files: this application has no file uploads; exports are generated client-side. If that changes, back up the storage separately.

### 21.2 Logical backups

Use the PostgreSQL client tools that match or exceed the server version.

**Daily custom-format dump (recommended):**

```powershell
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$backupDir = "D:\backups\ims"
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null

& "C:\Program Files\PostgreSQL\16\bin\pg_dump.exe" `
  -h localhost -p 5432 -U ims_backup -d item_management_db `
  -Fc -Z 6 `
  -f "$backupDir\item_management_db-$stamp.dump"
```

- `-Fc` is required for `pg_restore` and parallel/selective restore.
- `-Z 6` balances size and CPU; use `-Z 9` for slower, smaller archives.
- Omit `-U` if the job runs as the database owner; use a dedicated backup login with `SELECT` only.
- Do not pass the password on the command line. Use `pgpass.conf` or a service-account secret.

**Plain SQL dump (readable, larger):**

```powershell
& "C:\Program Files\PostgreSQL\16\bin\pg_dump.exe" `
  -h localhost -p 5432 -U ims_backup -d item_management_db `
  -Fp -f "D:\backups\ims\item_management_db-$stamp.sql"
```

**Cluster roles and configuration:**

```powershell
& "C:\Program Files\PostgreSQL\16\bin\pg_dumpall.exe" `
  -h localhost -p 5432 -U postgres --globals-only `
  -f "D:\backups\ims\globals-$stamp.sql"
```

`pg_dumpall --globals-only` contains role definitions and password hashes. Treat it as a secret. It is not needed if you recreate roles from script 01 and keep no custom roles.

### 21.3 Daily schedule

Use Windows Task Scheduler (or your organization's scheduler) to run the dump script as a dedicated service account. Example trigger: daily at 01:00, with "Run whether user is logged on or not" and "Run with highest privileges" only if required by the backup path ACL.

A simple retention policy:

| Tier | Retention | Example |
|---|---|---|
| Daily | 7–14 days | `item_management_db-20261030-0100.dump` |
| Weekly | 4–8 weeks | Copy Sunday's dump to weekly storage |
| Monthly | 12 months | Copy the first dump of the month |
| Pre-migration | Until the next successful migration + 30 days | Named with the change ticket |

Store at least one copy off the database host (network share, object storage with encryption, or tape). A backup on the same disk as the database is not a backup.

Encrypt backups at rest and in transit. Restrict the backup directory ACL to the backup service account and administrators. Do not email dumps or place them in a public share.

### 21.4 Verify backups

A backup is not valid until restored.

```powershell
# List the archive contents
& "C:\Program Files\PostgreSQL\16\bin\pg_restore.exe" --list "D:\backups\ims\item_management_db-20261030-0100.dump" |
  Select-Object -First 60
```

Restore into a scratch database at least monthly (or per release):

```powershell
# As superuser or a role with CREATEDB, create an empty scratch database
psql -h localhost -p 5432 -U postgres -d postgres `
  -c "CREATE DATABASE item_management_db_restore OWNER ims_app;"

& "C:\Program Files\PostgreSQL\16\bin\pg_restore.exe" `
  -h localhost -p 5432 -U ims_app -d item_management_db_restore `
  --no-owner --no-privileges --exit-on-error --verbose `
  "D:\backups\ims\item_management_db-20261030-0100.dump"
```

Run script 05 against the scratch database, compare key row counts, then drop the scratch database only after verification. Do not restore test data into production.

### 21.5 Restore procedure

**Restore into an empty database (safest):**

1. Stop the application (or route traffic away).
2. Confirm no active connections to the target:
   ```sql
   SELECT pid, usename, state, query
   FROM pg_stat_activity
   WHERE datname = 'item_management_db';
   ```
3. Create a new empty database owned by `ims_app` (or restore into `item_management_db_restore` first and verify there).
4. Run `pg_restore` with `--no-owner --no-privileges --exit-on-error`.
5. Run `ANALYZE`.
6. Run script 05 and the smoke test.
7. Point the application at the restored database and start it.

**Restore over an existing database (destructive; requires approval):**

- Take a backup of the current database first.
- Terminate application connections (as a superuser or a role with `pg_signal_backend`):
  ```sql
  SELECT pg_terminate_backend(pid)
  FROM pg_stat_activity
  WHERE datname = 'item_management_db' AND pid <> pg_backend_pid();
  ```
- Drop and recreate the database, or use `pg_restore --clean --if-exists`.
- `--clean` drops objects; it is not reversible. Never use it without a verified backup and explicit authorization.

**Point-in-time recovery (PITR):**

Logical dumps restore to the dump time only. For RPO measured in minutes, configure:

```ini
wal_level = replica
archive_mode = on
archive_command = 'copy "%p" "D:\\pg_wal_archive\\%f"'
```

Use `pg_basebackup` for a base backup and retain WAL archives according to the recovery window. Test `restore_command` and recovery targets on a scratch server. Managed PostgreSQL services usually provide PITR; use their console and retention settings instead of hand-built archiving.

### 21.6 Back up the embedded cluster

- **Logical dump (preferred):** section 19.6. This is portable and version-tolerant.
- **Physical copy:** stop the backend cleanly, then copy `backend/.pgdata` to a backup location. The copy can only be started by PostgreSQL **14**; it is not usable by 16 without an upgrade. Never copy a live data directory.
- Do not rely on the temp binaries being present. Keep an installer or installer media for the matching PostgreSQL major version so the physical copy can be started later.

### 21.7 RPO, RTO and drills

| Strategy | RPO | RTO (typical) | Notes |
|---|---|---|---|
| Daily logical dump only | Up to 24 h | Minutes to hours (restore + verify) | Simple; acceptable for small deployments |
| Dump every 1–6 h + offsite | 1–6 h | Minutes to hours | Better; more storage |
| Base backup + WAL archiving | Minutes (or seconds) | Minutes | PITR; more operational complexity |
| Managed service with PITR | Configured window | Provider-dependent | Verify restore procedure |

Define RPO/RTO with the business, not by assumption. Run a restore drill at least quarterly and after any major upgrade. Record the date, backup used, restore duration, validation results and any issues.

### 21.8 Backup security

- Backups contain all tenant data, password hashes, GST data, supplier and customer information. Treat them as confidential.
- Encrypt at rest (BitLocker, storage encryption) and in transit (HTTPS/SFTP).
- Restrict access to the backup service account and DBAs.
- Do not commit dumps or `.pgdata` to source control.
- Delete expired backups securely according to policy.
- Keep the database password and `JWT_SECRET` in a secret manager; a database restore alone does not restore application availability if secrets are lost.

### 21.9 Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `pg_dump: server version: 14.10; pg_dump version: 13.x` | Client older than server | Use a 14+ (or 16) client; match or exceed the source major version |
| `pg_dump: error: connection to server ... failed: FATAL: password authentication failed` | Wrong `pgpass`/password or `pg_hba` rule | Fix credentials; test with `psql` as the same role |
| `pg_restore: error: role "postgres" does not exist` | Restoring ownership/ACLs into a cluster without that role | Use `--no-owner --no-privileges` |
| `pg_restore: error: database ... is being accessed by other users` | Active connections | Stop the app and terminate sessions (with approval) |
| Restored sequences produce duplicate key errors | Sequence state not restored or manually reset too low | `setval` to at least `max(id)`; investigate why |
| Flyway fails after restore | History table missing/inconsistent, or schema does not match | Restore a full dump including `flyway_schema_history`, or repair deliberately; do not baseline blindly |
| Application starts but `ddl-auto=validate` fails | Restored schema differs (manual build, partial restore) | Compare with script 02; restore the correct dump |
| Backup job succeeds but restore fails | Corrupt/truncated dump, wrong version, disk full | Verify with `pg_restore --list`; test restores; check free space |
| WAL archive failures | `archive_command` path/ACL, disk full | Fix immediately; WAL accumulates and can fill the disk |

---

**End of sections 16–21.** The application source, schema, migrations and configuration were not modified. No SQL, migration, installation or configuration change was executed while producing this guide.
