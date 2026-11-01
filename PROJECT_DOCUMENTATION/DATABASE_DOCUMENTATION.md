# Database Documentation

> Verified against `backend/src/main/resources/db/migration/V1__init.sql` … `V7__item_barcode.sql`
> on **2026-11-01**. Schema is migration-managed; Hibernate runs with `ddl-auto=validate` and never
> creates or alters tables.
>
> **Safety notice:** nothing in this document was executed against production. V3 contains
> destructive `DROP COLUMN` statements; review Section 5 before deploying to an existing database.

---

## 1. Platform and connection

| Item | Value |
|---|---|
| Engine | PostgreSQL 14.10 embedded (development, `localhost:54329`, database `item_management_db`) |
| Production engine (declared) | PostgreSQL 18.6 on the Hostinger VPS (see [`SERVER_VERSION_COMPARISON.md`](SERVER_VERSION_COMPARISON.md)) |
| Minimum supported | PostgreSQL 14+ |
| Schema owner | Flyway (`classpath:db/migration`), enabled on every start |
| Hibernate | `spring.jpa.hibernate.ddl-auto=validate`, `open-in-view=false`, UTC JDBC time zone |
| Isolation model | Shared schema; every tenant row has `store_id`; branch rows also have `branch_id`. No row-level security; the service layer enforces tenancy. |
| Time storage | `TIMESTAMPTZ` (UTC) for audit/created/updated; `DATE` for business dates |
| Money | `NUMERIC(14,2)` (some `NUMERIC(12,2)`); quantities `NUMERIC(18,3)`; average cost `NUMERIC(18,4)` |

Production connection variables (declared): `DB_URL=jdbc:postgresql://127.0.0.1:5432/item_management_prod`,
`DB_USERNAME=ims_user`, `DB_PASSWORD` stored only in the environment file. Do not commit it.

---

## 2. Migration history

| Version | File | Purpose | Notes |
|---|---|---|---|
| V1 | `V1__init.sql` | Core schema: stores, branches, RBAC, masters, inventory ledger, purchases, sales, transfers, audit | 22 base tables, 26 permissions, platform role, indexes |
| V2 | `V2__tax_master.sql` | Tax master, item tax link, GST snapshots, state columns | Adds `stores.tax_inclusive`, `branches.state`, `suppliers.state`, `sales.customer_state`; backfills legacy tax; +2 permissions |
| V3 | `V3__item_rate.sql` | Single item master rate | Backfills `rate` from `default_selling_price`, **drops `default_purchase_price` and `default_selling_price`** |
| V4 | `V4__subscriptions.sql` | Subscription window, branch limit, change history, platform contact | Adds `stores.subscription_*`, `stores.max_branches`; `subscription_change_history`; `platform_settings` singleton |
| V5 | `V5__item_import.sql` | Excel import batches/rows, opening-stock guard | Adds `items.description`; sequences `item_code_seq`, `item_import_number_seq`; partial unique indexes |
| V6 | `V6__printing.sql` | Printers, pairing codes, devices, print jobs | 4 tables; +2 permissions; no existing data changed |
| V7 | `V7__item_barcode.sql` | Item barcodes | Adds `barcode`, `barcode_format`; backfills `'IMS' || LPAD(id,12,'0')`; unique `(store_id,barcode)` |
| V8 | `V8__auth_security.sql` | Authentication hardening | Adds `users.must_change_password` (default false), `users.token_version` (default 0) and `revoked_tokens`; additive, no existing row rewritten |

Expected Flyway rows after a successful production deployment: **8** (plus the baseline/validation
row created by Flyway itself in `flyway_schema_history`).

---

## 3. Tables (32)

### 3.1 Tenancy, identity and RBAC

#### `stores`
| Column | Type | Constraints |
|---|---|---|
| `id` | BIGSERIAL | PK |
| `store_code` | VARCHAR(30) | NOT NULL, UNIQUE, stored upper-case |
| `store_name` | VARCHAR(150) | NOT NULL |
| `owner_name`, `email`, `mobile`, `address` | text types | NOT NULL |
| `gst_number` | VARCHAR(30) | nullable |
| `status` | VARCHAR(20) | `ACTIVE`/`INACTIVE`, default `ACTIVE` |
| `created_at`, `updated_at` | TIMESTAMPTZ | default `now()` |
| V2 | `tax_inclusive` | BOOLEAN NOT NULL DEFAULT false |
| V4 | `subscription_start_date`, `subscription_end_date` | DATE NOT NULL |
| V4 | `max_branches` | INTEGER NOT NULL DEFAULT 5, CHECK ≥ 1 |
| V4 | `ck_stores_subscription_dates` | `end_date >= start_date` |

#### `branches`
`id` PK; `store_id` FK; `branch_code`; `branch_name`; `address`; `contact_number`;
`status`; timestamps; V2 `state VARCHAR(80)`. Unique `(store_id, branch_code)` and
`(store_id, id)`. Deactivation is supported; no delete endpoint.

#### `roles`, `permissions`, `role_permissions`
- `roles`: `id`, nullable `store_id` (`NULL` = platform role), `code`, `name`, `description`,
  `is_system`; unique expression index `(COALESCE(store_id,0), code)`.
- `permissions`: `id`, `code` UNIQUE, `name`, `description`.
- `role_permissions`: `(role_id, permission_id)` PK, both FKs `ON DELETE CASCADE`.

#### `users`, `user_roles`, `user_branches`
- `users`: `id`, nullable `store_id` (`NULL` = platform user), `username`, `email`,
  `password_hash` (BCrypt, never exposed), `full_name`, `mobile`, `status`, timestamps;
  unique `(store_id, username)`; index on `email` and `store_id`. V8 adds
  `must_change_password BOOLEAN NOT NULL DEFAULT FALSE` (forces a change after reset/provisioning) and
  `token_version INTEGER NOT NULL DEFAULT 0` (embedded in JWTs as `ver`; incrementing it invalidates
  every previously issued token for that user).
- `user_roles`: user/role join, cascade.
- `user_branches`: user/branch join, cascade; controls branch access for non-owner roles.

#### `revoked_tokens` (V8)
| Column | Type | Constraints |
|---|---|---|
| `jti` | VARCHAR(64) | PK |
| `expires_at` | TIMESTAMPTZ | NOT NULL |
| `revoked_at` | TIMESTAMPTZ | NOT NULL DEFAULT `now()` |

Index `ix_revoked_tokens_expires`. Logout, refresh and password change insert a row; the auth filter
rejects a presented `jti` that exists. A scheduled job deletes **only expired rows**. Persisting this
table is what makes revocation survive restarts; it replaces the previous in-memory blacklist.

### 3.2 Master data

#### `categories`
`id`, `store_id`, `category_name`, `description`, `status`, timestamps.
Unique `(store_id, category_name)` and `(store_id, id)`. Delete only when no item references it.

#### `items`
| Column | Type | Constraints |
|---|---|---|
| `id` | BIGSERIAL | PK |
| `store_id` | BIGINT | FK stores |
| `category_id` | BIGINT | composite FK `(category_id, store_id)` → categories |
| `item_code` | VARCHAR(50) | NOT NULL; unique `(store_id, item_code)` |
| `item_name` | VARCHAR(150) | NOT NULL |
| `description` | VARCHAR(255) | V5 |
| `base_unit` | VARCHAR(20) | LITER/ML/KG/GRAM/PIECE/PACKET/BOX/BOTTLE/DOZEN |
| `rate` | NUMERIC(12,2) | NOT NULL, CHECK ≥ 0 (V3) |
| `min_stock_level` | NUMERIC(18,3) | NOT NULL DEFAULT 5, CHECK ≥ 0 |
| `status` | VARCHAR(20) | ACTIVE/INACTIVE |
| `tax_id` | BIGINT | NOT NULL, composite FK `(tax_id, store_id)` (V2) |
| `barcode` | VARCHAR(64) | NOT NULL (V7) |
| `barcode_format` | VARCHAR(10) | `CODE128`/`EAN13`, default `CODE128` (V7) |
| `created_at`, `updated_at` | TIMESTAMPTZ | — |

Indexes: `ix_items_store_category`, `ix_items_name`, `ix_items_tax`, unique
`uq_items_store_barcode`. The entity’s `@Column(unique = true)` on `item_code` has no runtime effect
under `ddl-auto=validate`; the real rule is per-store uniqueness.

#### `tax_master`
`id`, `store_id`, `tax_name`, `description`, `gst_percentage`, `cgst_percentage`,
`sgst_percentage`, `igst_percentage` (all NUMERIC(5,2), 0–100), `tax_type` (`GST`/`EXEMPT`),
`status`, timestamps. Unique `(store_id, tax_name)` and `(store_id, id)`; index
`(store_id, status)`. Five default slabs are seeded for every store (0/5/12/18/28).

#### `suppliers`
`id`, `store_id`, `supplier_name`, `mobile`, `address`, `gst_number`, `email`, `state` (V2),
`status`, timestamps; unique `(store_id, id)`. No delete endpoint.

#### `customers`
`id`, `store_id`, `name`, `mobile`, `created_at`; unique `(store_id, id)`. The table exists and
sales can reference a customer id, but there is **no customer API or UI**; normal sales store the
entered customer name/mobile/state directly on the sale header.

### 3.3 Inventory

#### `branch_inventory`
One row per store/branch/item. Columns: `current_stock NUMERIC(18,3) CHECK ≥ 0`,
`minimum_stock NUMERIC(18,3)`, `purchase_price NUMERIC(14,2)`, `selling_price NUMERIC(14,2)`,
`avg_cost NUMERIC(18,4)`, `updated_at`. Unique `(store_id, branch_id, item_id)`; composite FKs to
branches and items; indexes on `(store_id,item_id)` and `(store_id,branch_id)`.
`lockOrCreate` inserts with `ON CONFLICT DO NOTHING` then selects `FOR UPDATE` to serialize
concurrent stock changes.

#### `inventory_transactions` (append-only ledger)
`id`, `store_id`, `branch_id`, `item_id`, `transaction_type` (CHECK: `OPENING_STOCK`,
`PURCHASE_IN`, `SALE_OUT`, `TRANSFER_IN`, `TRANSFER_OUT`, `ADJUSTMENT_IN`, `ADJUSTMENT_OUT`,
`PURCHASE_REVERSAL`, `SALE_REVERSAL`), `reference_type`, `reference_id`, `reference_number`,
`quantity_in`, `quantity_out`, `balance_after` (CHECK ≥ 0), `notes`, `transaction_date`,
`created_by`. Indexes: `(store_id, transaction_date)`, `(store_id, branch_id, item_id)`,
`(reference_type, reference_id)`. Partial unique index `uq_txn_single_opening_stock` prevents a
second opening-stock row per store/branch/item.

#### `stock_adjustments`
`id`, `store_id`, `branch_id`, `item_id`, `adjustment_number`, `adjustment_type` (`IN`/`OUT`),
`quantity` > 0, `base_quantity` > 0, `unit`, `reason`, `created_by`, `created_at`.
Unique `(store_id, adjustment_number)`; composite FKs. Adjustments are immediate and audited.

### 3.4 Purchases and sales

#### `purchases`
`id`, `store_id`, `branch_id`, `supplier_id`, `purchase_number` (unique per store),
`invoice_number`, `purchase_date`, `subtotal`, `discount`, `tax`, `grand_total`, `paid_amount`,
`balance_amount`, `notes`, `status` (`DRAFT`/`COMPLETED`/`CANCELLED`), `created_by`, timestamps.
V2 adds `taxable_amount`, `cgst_amount`, `sgst_amount`, `igst_amount`. Composite FKs to branch and
supplier; indexes on date and branch.

#### `purchase_details`
`id`, `store_id`, `purchase_id` (cascade), `item_id`, `quantity`, `unit`, `base_quantity`,
`purchase_rate`, `discount`, `tax`, `total_amount`.
V2 adds `tax_id`, `tax_name_snapshot`, `gst_percentage`, `cgst_percentage`, `sgst_percentage`,
`igst_percentage`, `taxable_amount`, `cgst_amount`, `sgst_amount`, `igst_amount`, `tax_amount`.
Snapshots make posted documents immutable when the tax master changes.

#### `sales`
`id`, `store_id`, `branch_id`, `sales_number` (unique per store), `sales_date`, `customer_id`,
`customer_name`, `customer_mobile`, `subtotal`, `discount`, `tax`, `grand_total`, `paid_amount`,
`balance_amount`, `payment_method` (`CASH`/`UPI`/`CARD`/`CREDIT`), `status`
(`COMPLETED`/`CANCELLED`), `created_by`, `created_at`.
V2 adds `customer_state` and the four GST header amounts. Composite FKs to branch and customer.

#### `sales_details`
`id`, `store_id`, `sales_id` (cascade), `item_id`, `quantity`, `unit`, `base_quantity`,
`selling_rate`, `discount`, `tax`, `total_amount`, plus V2 tax snapshot columns and `cost_rate`
(snapshot of `branch_inventory.avg_cost` at posting time, used by the profit report).

### 3.5 Transfers

- `stock_transfers`: `id`, `store_id`, `transfer_number`, `from_branch_id`, `to_branch_id`,
  `transfer_date`, `notes`, `status` (`PENDING`/`APPROVED`/`DISPATCHED`/`RECEIVED`/`REJECTED`/
  `CANCELLED`; the check constraint also permits an unused `DRAFT`), `created_by`, timestamps.
  CHECK requires source ≠ destination.
- `stock_transfer_details`: `id`, `store_id`, `transfer_id` (cascade), `item_id`, `quantity`,
  `unit`, `base_quantity`.
Indexes on store and transfer date.

### 3.6 Audit and platform

#### `audit_logs`
`id`, `store_id` (nullable for platform actions), `user_id`, `username`, `action`,
`entity_type`, `entity_id`, `details VARCHAR(1000)`, `created_at`; index
`(store_id, created_at)`. Write-only through `AuditService`; the only read API is the platform
credential-history endpoint. No retention policy is implemented.

#### `subscription_change_history`
`id`, `store_id`, `changed_by`, `changed_by_username`, `changed_at`, previous/new start/end dates,
previous/new max branches, `note`; index `(store_id, changed_at DESC)`.

#### `platform_settings`
Singleton row (`id = 1`), `contact_email`, `contact_phone`, `updated_at`; CHECK `id = 1`.
Shown on the expired-subscription screen and editable by the platform admin.

### 3.7 Item import

#### `item_import_batches`
`id`, `store_id`, `branch_id`, `import_number` (unique per store), `file_name`, `file_hash`,
`status` (`PREVIEWED`/`COMPLETED`/`FAILED`), total/success/failed/new-category/new-item/
existing-item/stock-update/duplicate/invalid/already-imported counts, `failure_message`,
`uploaded_by`, `uploaded_by_username`, `created_at`, `completed_at`. Unique
`(store_id, branch_id, file_hash) WHERE status = 'COMPLETED'` prevents re-confirming the same file.
Indexes on store/branch + created date.

#### `item_import_rows`
`id`, `batch_id` (cascade), `store_id`, `row_number` (unique per batch), parsed columns
(`item_code`, `item_name`, `category_name`, `unit`, `rate`, `tax_name`, `opening_stock`,
`minimum_stock`, `description`), `item_id`, `parse_failed`, `import_action`,
`validation_status` (`VALID`/`INVALID`), `error_message`.

### 3.8 Printing

#### `printer_configs`
`id`, `store_id`, `branch_id`, `printer_name`, `ip_address` (IPv4/hostname), `port` (1–65535,
default 9100), `paper_width` (`MM58`/`MM80`), `purpose` (`RECEIPT`/`PURCHASE`/`REPORT`/`ALL`),
`status`, `last_test_at`, `last_test_message`, timestamps. Unique `(store_id,branch_id,printer_name)`;
index `(store_id,branch_id,status)`.

#### `print_pairing_codes`
`id`, `store_id`, `branch_id`, `code_hash` (SHA-256, unique), `created_by`, `expires_at`,
`used_at`, `created_at`; index `(store_id, expires_at)`.

#### `print_devices`
`id`, `store_id`, `branch_id`, `device_name`, `platform` (`ANDROID`/`WINDOWS`), `token_hash`
(unique), `status` (`ACTIVE`/`REVOKED`), `paired_by`, `paired_by_username`, `created_at`,
`last_seen_at`; index `(store_id,branch_id,status)`.

#### `print_jobs`
`id`, `store_id`, `branch_id`, `job_key`, `document_type` (`SALE_RECEIPT`/`PURCHASE`/`TEST`),
`document_id`, `document_number`, `printer_config_id`, `printer_name`, `channel`
(`WINDOWS`/`ANDROID`), `trigger_type` (`AUTO`/`MANUAL`), `status`
(`PENDING`/`PRINTED`/`FAILED`), `attempts`, `error_message`, `created_by`,
`created_by_username`, `created_at`, `updated_at`, `printed_at`.
Unique `(store_id, job_key)`; indexes on store and branch + created date.

---

## 4. Permissions and default roles

30 permission codes:

- Core (26): `DASHBOARD_VIEW`, `PLATFORM_MANAGE`, `STORE_VIEW`, `STORE_MANAGE`, `BRANCH_VIEW`,
  `BRANCH_MANAGE`, `USER_VIEW`, `USER_MANAGE`, `ROLE_VIEW`, `ROLE_MANAGE`, `CATEGORY_VIEW`,
  `CATEGORY_MANAGE`, `ITEM_VIEW`, `ITEM_MANAGE`, `SUPPLIER_VIEW`, `SUPPLIER_MANAGE`,
  `PURCHASE_VIEW`, `PURCHASE_MANAGE`, `INVENTORY_VIEW`, `INVENTORY_ADJUST`, `TRANSFER_VIEW`,
  `TRANSFER_MANAGE`, `TRANSFER_APPROVE`, `SALES_VIEW`, `SALES_MANAGE`, `REPORT_VIEW`
- V2: `TAX_VIEW`, `TAX_MANAGE`
- V6: `PRINTER_VIEW`, `PRINTER_MANAGE`

System roles (`is_system = true`, seeded per store by `StoreProvisioningService`):

| Role | Permissions |
|---|---|
| `STORE_OWNER` | all store permissions (including users, roles, store profile, printer management) |
| `STORE_ADMIN` | all operational permissions except `STORE_MANAGE` and `ROLE_MANAGE` |
| `BRANCH_MANAGER` | dashboard, catalogue/tax/supplier view, purchase manage, inventory adjust, transfer manage/approve, sales manage/view, reports, printer view |
| `BRANCH_STAFF` | dashboard, catalogue/tax/inventory/sales view, sales manage |

The platform role `PLATFORM_ADMIN` (`store_id = NULL`) has `PLATFORM_MANAGE`, `STORE_VIEW`,
`STORE_MANAGE`. The platform-account API additionally re-checks `AuthUser.isPlatformAdmin()`.

---

## 5. Destructive / risky migration notes

1. **V3 drops two columns.** `default_purchase_price` and `default_selling_price` are removed after
   `rate` is backfilled from `default_selling_price`. Any external report/query that references
   those columns breaks. A rollback to a pre-V3 jar against a V3 database fails Hibernate
   validation; rollback requires restoring the pre-deployment backup (see
   [`ROLLBACK_GUIDE.md`](ROLLBACK_GUIDE.md)).
2. **V2 backfills GST.** Legacy lines with `tax > 0` are split into taxable + tax amounts and the
   snapshot name `'Legacy tax'`. The numbers are derived; they do not reconstruct original
   CGST/SGST invoices exactly.
3. **V7 barcode backfill** is deterministic (`IMS` + zero-padded id). It is safe to run live, but
   barcodes printed or registered elsewhere are not preserved because none existed before V7.
4. **V5 partial unique index** blocks a second `OPENING_STOCK` transaction per store/branch/item.
   If a legacy database already contains duplicates, V5 fails; inspect before deploying.
5. **V6/V7/V8 are additive.** V8 adds two columns with safe defaults and one new table; it rewrites
   no existing row. After V8, rolling back only the jar is safe for authentication data (the columns
   are ignored by the old jar), but users' forced-change flags and token versions remain in the
   database; re-deploying the hardened jar restores enforcement.
6. **V7 rollback shim is optional and non-destructive.** Only needed if the application jar is rolled
   back while V7 stays applied: run `docs/sql/07_rollback_v7_compatibility.sql`, then
   `docs/sql/08_remove_v7_rollback_compatibility.sql` after restoring the new jar. Existing barcodes
   are never deleted.
7. **No migration deletes business rows.** `V1` and `V2` create/alter; `V3` only drops the two
   default-price columns; all later migrations are additive. No migration removes audit or print-job
   history; retention is a manual, approved operation.

---

## 6. Sequences

| Sequence | Start | Used by |
|---|---|---|
| `item_code_seq` | 1001 | Generated item codes for import rows with a blank code |
| `item_import_number_seq` | 1 | `IMP-<n>` import numbers |
| Table `BIGSERIAL`s | 1 | All primary keys |

Business document numbers (`PUR-`, `SAL-`, `TRF-`, `ADJ-`) are generated by the numbering service
inside the transaction; uniqueness is enforced by `(store_id, number)` constraints.

---

## 7. Integrity rules worth knowing

- `branch_inventory.current_stock >= 0`; sale/transfer/adjustment-out fail rather than go negative.
- `inventory_transactions.balance_after >= 0`.
- Sales and purchases only move stock when `status = COMPLETED`; drafts write no ledger rows.
- Purchase/sale lines store their own rate and GST snapshot; editing masters never rewrites history.
- `(store_id, id)` composite unique keys exist on tenant tables so composite foreign keys can
  prevent cross-store references (e.g., a sale line cannot point at another store's item).
- A barcode is unique **per store**, so two tenants may use the same supplier barcode.
- Platform users (`store_id IS NULL`) are excluded from `PlatformAccountService.findStoreAccount`
  and from the store-account list query.

---

## 8. Backup, restore and verification

### Ubuntu (production-style)

```bash
# Custom-format backup (recommended)
sudo -u postgres pg_dump -F c -d item_management_prod \
  -f /var/backups/ims/item_management_prod_$(date +%F_%H%M).dump

# Verify the archive is readable and count objects
sudo -u postgres pg_restore --list /var/backups/ims/item_management_prod_YYYY-MM-DD_HHMM.dump | head
sudo -u postgres pg_restore --list /var/backups/ims/item_management_prod_YYYY-MM-DD_HHMM.dump | wc -l

# Restore into a NEW database for validation (never overwrite production without approval)
sudo -u postgres createdb ims_restore_test
sudo -u postgres pg_restore -d ims_restore_test /var/backups/ims/…dump
sudo -u postgres psql -d ims_restore_test -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
sudo -u postgres dropdb ims_restore_test
```

### Windows (development / test)

```powershell
# From a PostgreSQL bin directory on PATH
pg_dump -F c -h localhost -p 5432 -U postgres -d item_management_db -f backup.dump
pg_restore -l backup.dump | Select-Object -First 10
```

### Schema verification after deployment

```sql
SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';      -- 31 + flyway table
SELECT count(*) FROM permissions;                                                   -- 30
SELECT count(*) FROM items WHERE barcode IS NULL;                                    -- 0
SELECT store_id, barcode, count(*) FROM items GROUP BY store_id, barcode HAVING count(*) > 1; -- 0 rows
```

Do **not** run `DROP DATABASE`, `TRUNCATE`, `DELETE` or a Flyway `clean` against production. Flyway
clean is disabled by configuration and is not part of any runbook.
