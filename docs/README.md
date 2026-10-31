# Item Management System — Database, Architecture and Manual Implementation Guide

**Document status:** handover documentation, 2026-10-30
**Application state:** implemented and runnable (Spring Boot 3.3.5 + PostgreSQL + React 19)
**This package contains documentation, SQL scripts and manual instructions only.**

No application source, schema, migration, package or configuration was changed to produce this documentation. No SQL in `docs/sql/` was executed. The scripts are for manual use by an operator or DBA.

**Operational quick start:** [09-owner-build-and-hosting-runbook.md](09-owner-build-and-hosting-runbook.md) — owner account and login, separate frontend/backend builds, hosted-server setup and table creation in one document.

**Silent thermal printing:** [10-silent-thermal-printing.md](10-silent-thermal-printing.md) — Windows and Android print bridges, website printer/pairing setup, security model, test results and troubleshooting.

**Full production deployment:** [PRODUCTION-DEPLOYMENT-GUIDE.md](PRODUCTION-DEPLOYMENT-GUIDE.md) — the complete 15-section manual for a new Ubuntu Server (with a Windows Server alternative): downloads, PostgreSQL, exact SQL, embedded-to-standalone migration, builds, NGINX, SSL, Platform Owner creation, startup, first store, backup/restore and the go-live checklist.

---

## 1. What this documentation covers

| # | Section | File |
|---|---|---|
| 1 | Project Overview | [01-overview-architecture.md](01-overview-architecture.md#1-project-overview) |
| 2 | Existing Architecture | [01-overview-architecture.md](01-overview-architecture.md#2-existing-architecture) |
| 3 | Frontend | [01-overview-architecture.md](01-overview-architecture.md#3-frontend) |
| 4 | Backend | [01-overview-architecture.md](01-overview-architecture.md#4-backend) |
| 5 | Embedded PostgreSQL | [01-overview-architecture.md](01-overview-architecture.md#5-embedded-postgresql) |
| 6 | Database Tables | [02-database.md](02-database.md#6-database-tables) |
| 7 | ERD | [02-database.md](02-database.md#7-entity-relationship-diagram) |
| 8 | Relationships | [02-database.md](02-database.md#8-relationships) |
| 9 | Business Flow | [03-business-flows.md](03-business-flows.md#9-business-flows) |
| 10 | Stock Calculation | [03-business-flows.md](03-business-flows.md#10-stock-calculation) |
| 11 | Purchase and Sales Workflows | [03-business-flows.md](03-business-flows.md#11-purchase-and-sales-arithmetic) |
| 12 | POS and Billing | [03-business-flows.md](03-business-flows.md#12-pos-and-billing-behaviour) |
| 13 | GST | [03-business-flows.md](03-business-flows.md#13-gst-calculation) |
| 14 | Multi-Store and Branch Security | [04-security.md](04-security.md#14-multi-store-and-branch-security) |
| 15 | API Reference | [05-api-reference.md](05-api-reference.md#15-api-reference) |
| 16 | PostgreSQL Installation (Windows) | [06-postgresql-setup.md](06-postgresql-setup.md#16-postgresql-installation-on-windows) |
| 17 | Database Creation | [06-postgresql-setup.md](06-postgresql-setup.md#17-create-the-application-database) |
| 18 | SQL Scripts | [06-postgresql-setup.md](06-postgresql-setup.md#18-sql-scripts) |
| 19 | Embedded to Standalone Migration | [06-postgresql-setup.md](06-postgresql-setup.md#19-migrate-embedded-postgresql-to-standalone) |
| 20 | Production PostgreSQL | [06-postgresql-setup.md](06-postgresql-setup.md#20-production-postgresql) |
| 21 | Backup and Restore | [06-postgresql-setup.md](06-postgresql-setup.md#21-backup-and-restore) |
| 22 | Manual Development Guide | [07-manual-development.md](07-manual-development.md#22-manual-development-guide) |
| 23 | Testing | [07-manual-development.md](07-manual-development.md#23-testing) |
| 24 | Known Issues and Missing Features | [08-known-issues.md](08-known-issues.md#24-known-issues-and-missing-features) |

SQL scripts:

| Script | Purpose |
|---|---|
| [sql/01_create_database.sql](sql/01_create_database.sql) | Create `item_management_db` and the dedicated `ims_app` login |
| [sql/02_create_tables.sql](sql/02_create_tables.sql) | Consolidated final schema (V1 + V2 + V3) |
| [sql/03_create_indexes.sql](sql/03_create_indexes.sql) | Recreate the 19 performance indexes; optional recommendations |
| [sql/04_insert_initial_data.sql](sql/04_insert_initial_data.sql) | 28 permissions, `PLATFORM_ADMIN` + 3 grants, standard store roles (no grants) and GST slabs for stores that already exist |
| [sql/05_database_validation.sql](sql/05_database_validation.sql) | Read-only schema and integrity checks |
| [sql/06_sample_business_flow.sql](sql/06_sample_business_flow.sql) | One-shot realistic store/purchase/sale/POS/transfer demo |

---

## 2. Ground truth used for this documentation

Every statement below was verified against the current repository, not assumed:

- `backend/src/main/resources/db/migration/V1__init.sql`, `V2__tax_master.sql`, `V3__item_rate.sql`
- every JPA entity, repository, service, controller, DTO and security class
- `backend/src/main/resources/application.yml`, `backend/build.gradle`
- `backend/src/main/java/com/itemmanagement/config/EmbeddedPostgresConfig.java`
- the live embedded data directory `backend/.pgdata` (`PG_VERSION` = 14, 53 MB)
- `src/` (React routes, navigation, API client, store, export helpers)
- `package.json`, `README.md`
- the integration test names and fixture setup

Where the code and an older README disagree, the code and migrations win; discrepancies are listed in [08-known-issues.md](08-known-issues.md).

---

## 3. Feature status legend

| Status | Meaning |
|---|---|
| **Implemented** | Present end-to-end: database, backend API, permission, frontend page/flow, and exercised by tests or manual verification. |
| **Partially implemented** | Core path exists but a meaningful part is missing (read API, UI, historical handling, edge case). |
| **Needs correction** | The feature exists but has a defect or a mismatch that should be fixed before production. |
| **Not implemented** | No code path exists. |

A feature-by-feature matrix is in [01-overview-architecture.md](01-overview-architecture.md#feature-status-matrix).

---

## 4. Three ways to run the database

### A. Development with embedded PostgreSQL (current default)

The backend starts a real PostgreSQL 14.10 server itself on port **54329**, with data in `backend/.pgdata` and database `item_management_db`. No PostgreSQL installation is required. This is the state this repository is currently in.

```bash
cd backend
./gradlew bootRun          # or: java -jar build/libs/item-management-backend-1.0.0.jar
```

- Connection: `jdbc:postgresql://localhost:54329/item_management_db`, user `postgres`, password `postgres`, trust authentication, localhost only.
- Flyway creates the schema automatically on first start.
- Demo data: `ABC001` / `ABC Dairy` is seeded when `app.seed-demo=true` (default). See [05-embedded-postgresql](01-overview-architecture.md#5-embedded-postgresql).

### B. Standalone PostgreSQL for development or production

Install PostgreSQL, create the database and a dedicated user (scripts 01–04 or Flyway), then start with the `postgres` profile:

```bash
cd backend
SPRING_PROFILES_ACTIVE=postgres \
DB_URL='jdbc:postgresql://localhost:5432/item_management_db' \
DB_USERNAME=ims_app \
DB_PASSWORD='<strong-password>' \
java -jar build/libs/item-management-backend-1.0.0.jar
```

Full instructions: [06-postgresql-setup.md](06-postgresql-setup.md).

### C. Manual schema from the SQL scripts

Use this only when a DBA must create the schema without the application. Run `01`, `02`, `03`, `04` in order and then start the application with Flyway disabled. **Do not mix this with Flyway.** The reasons and exact procedure are in [18-sql-scripts](06-postgresql-setup.md#18-sql-scripts).

---

## 5. Non-negotiable operating rules

1. **One database for all stores and branches.** `item_management_db`. No per-store databases.
2. **Never connect the application as `postgres` superuser in production.** Use a dedicated login such as `ims_app`.
3. **Never expose port 5432 publicly.** Bind to localhost or a private network/VPN.
4. **Never store database passwords in source control.** Use environment variables or a secret manager.
5. **Never delete completed purchases, sales or transfers.** Cancel/reverse them; the ledger and snapshots must remain.
6. **Never trust `storeId`, `branchId`, roles or rates supplied by the browser.** The server derives identity from the JWT and re-validates it on every request; POS rates come from the item master.
7. **Do not run manual migrations while Flyway is enabled** on the same schema.
8. **Change the seeded platform password (`platform` / `Admin@123`) before any non-local use.** The application always creates the `platform` user if it is absent; only demo data is controlled by `SEED_DEMO`.

---

## 6. Scope boundaries of this package

- This is not a replacement for application source code. To change behaviour, edit the code and add a new Flyway migration; do not edit `02_create_tables.sql` and expect the running application to follow.
- The sample data in `06_sample_business_flow.sql` is for a disposable demo database. The production application must not be seeded with fake business transactions.
- API examples show the current contract. Endpoints marked **Missing** or **Partial** in the API reference must be designed and implemented before use; they are not hidden routes.
