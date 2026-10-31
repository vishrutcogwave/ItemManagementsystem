# DATABASE_SCHEMA.md

Complete PostgreSQL schema reference for the Item Management System.

| Item | Value |
|---|---|
| Snapshot date | 2026-11-01 |
| Schema source of truth | `backend/src/main/resources/db/migration/V1__init.sql` … `V6__printing.sql` |
| ORM mapping | JPA/Hibernate, `spring.jpa.hibernate.ddl-auto=validate` |
| Production database | `item_management_prod` on PostgreSQL 18.6 |
| Production migration state | V1–V5 `success`; **V6 pending** |
| Total tables after V6 | 31 (27 existing + 4 new) |
| Total permissions after V6 | 30 (28 existing + 2 new) |

> This document describes the schema as produced by the migration files. It does not authorise any production change. Production work is gated on owner approval (see `DEPLOYMENT_RUNBOOK.md`).

---

## 1. Tenancy and naming conventions

- One PostgreSQL database serves every store. There are no per-store databases or schemas.
- `store_id` identifies the tenant on every tenant-owned table.
- `branch_id` narrows rows to a branch. Branch-scoped tables use a composite foreign key
  `(branch_id, store_id) → branches(id, store_id)`, which makes a cross-store branch reference impossible.
- `branches` has `UNIQUE (store_id, id)` solely so composite foreign keys can target it.
- The same pattern is used by `items`, `categories`, `suppliers`, `customers`, `purchases`, `sales` and `stock_transfers` (`UNIQUE (store_id, id)`).
- Money is `NUMERIC(14,2)`; quantities are `NUMERIC(18,3)`; average cost is `NUMERIC(18,4)`.
- Timestamps are `TIMESTAMPTZ`; Hibernate is configured with `hibernate.jdbc.time_zone=UTC`.
- `id` columns are `BIGSERIAL` (`IDENTITY` in JPA).
- Enumerations are stored as `VARCHAR` with `CHECK` constraints.
- Platform-level rows (`stores`, `roles`, `users`) allow `store_id IS NULL` for platform administrators.
- Completed business documents are never deleted; they are cancelled/reversed. Detail rows cascade only when the header is deleted while still a draft.

---

## 2. Migration history

| Version | Script | Description | Production |
|---|---|---|---|
| V1 | `V1__init.sql` | Base multi-tenant schema, permissions, platform role, number sequences | Applied |
| V2 | `V2__tax_master.sql` | Tax master, GST snapshots, state columns, tax permissions | Applied |
| V3 | `V3__item_rate.sql` | Single item `rate`; drops the two default price columns | Applied |
| V4 | `V4__subscriptions.sql` | Subscription window, branch limit, change history, platform settings | Applied |
| V5 | `V5__item_import.sql` | Excel import batches/rows, item description, code sequences | Applied |
| V6 | `V6__printing.sql` | Printers, pairing codes, print devices, print jobs, printing permissions | **Pending** |

V1–V5 must never be edited after being applied. V6 is the correct next version: no V6 row exists in
production, and no other migration uses version 6.

### What V6 does

1. Creates `printer_configs`, `print_pairing_codes`, `print_devices`, `print_jobs`.
2. Adds `PRINTER_VIEW` and `PRINTER_MANAGE` to `permissions`.
3. Grants `PRINTER_VIEW` to every existing store-scoped role.
4. Grants `PRINTER_MANAGE` to existing `STORE_OWNER` and `STORE_ADMIN` roles.
5. Does not alter, rename or drop any existing table or column, and does not touch business data.

New stores created after V6 receive the same grants through `StoreProvisioningService`
(owner/admin: view + manage; branch manager: view; branch staff: no printer permission).

---

## 3. Table inventory

### A. Existing tables (V1–V5, unchanged by V6)

| # | Table | Purpose |
|---|---|---|
| 1 | `stores` | Tenant root: store profile, status, GST-inclusive flag, subscription window and branch allowance |
| 2 | `branches` | Store branches; target of every composite branch foreign key |
| 3 | `roles` | System/platform roles and per-store roles |
| 4 | `permissions` | Permission catalogue |
| 5 | `role_permissions` | Role → permission grants |
| 6 | `users` | Login accounts; `store_id NULL` for platform administrators |
| 7 | `user_roles` | User → role assignments |
| 8 | `user_branches` | Branch access for restricted users |
| 9 | `categories` | Store item categories |
| 10 | `items` | Store item master (single rate, tax link, description) |
| 11 | `suppliers` | Store suppliers |
| 12 | `customers` | Store customers |
| 13 | `branch_inventory` | Current stock, pricing and average cost per branch/item |
| 14 | `inventory_transactions` | Immutable stock movement ledger |
| 15 | `stock_adjustments` | Manual stock adjustments |
| 16 | `purchases` | Purchase headers with GST summary |
| 17 | `purchase_details` | Purchase lines with tax snapshots |
| 18 | `sales` | Sale headers with GST summary |
| 19 | `sales_details` | Sale lines with tax and cost snapshots |
| 20 | `stock_transfers` | Inter-branch transfers |
| 21 | `stock_transfer_details` | Transfer lines |
| 22 | `audit_logs` | Security/business audit trail |
| 23 | `tax_master` | Store GST slabs (V2) |
| 24 | `subscription_change_history` | Subscription/branch-limit change log (V4) |
| 25 | `platform_settings` | Single row of platform contact details (V4) |
| 26 | `item_import_batches` | Excel import batch headers (V5) |
| 27 | `item_import_rows` | Excel import preview/result rows (V5) |

### B. New tables (V6)

| # | Table | Purpose |
|---|---|---|
| 28 | `printer_configs` | Branch receipt/LAN printer configuration |
| 29 | `print_pairing_codes` | Hashed, single-use, 10-minute bridge pairing codes |
| 30 | `print_devices` | Paired Android bridges and their hashed device tokens |
| 31 | `print_jobs` | Print history, idempotency keys and bridge results |

### C. Modified tables (all before V6)

| Table | Versions | Change |
|---|---|---|
| `stores` | V2, V4 | Added `tax_inclusive`; added subscription window and `max_branches` |
| `branches` | V2 | Added `state` |
| `suppliers` | V2 | Added `state` |
| `items` | V2, V3, V5 | Added `tax_id`; replaced default prices with `rate`; added `description` |
| `purchases` | V2 | Added taxable/CGST/SGST/IGST summary columns |
| `purchase_details` | V2 | Added tax link and tax snapshot/amount columns |
| `sales` | V2 | Added `customer_state` and GST summary columns |
| `sales_details` | V2 | Added tax link and tax snapshot/amount columns |
| `permissions` | V2, V6 (data only) | 2 tax permissions, 2 printing permissions |
| `role_permissions` | V2, V6 (data only) | Grants for the new permissions |

### D. Removed tables and columns

- No table has been removed.
- V3 removed `items.default_purchase_price` and `items.default_selling_price` after backfilling `items.rate`.
- V6 removes nothing.

---

## 4. Existing table reference

### 4.1 `stores`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | BIGSERIAL | no | PK |
| `store_code` | VARCHAR(30) | no | UNIQUE |
| `store_name` | VARCHAR(150) | no | |
| `owner_name` | VARCHAR(150) | no | |
| `email` | VARCHAR(150) | no | |
| `mobile` | VARCHAR(20) | no | |
| `address` | TEXT | no | |
| `gst_number` | VARCHAR(30) | yes | |
| `status` | VARCHAR(20) | no | `ACTIVE`; CHECK `ACTIVE`/`INACTIVE` |
| `created_at` | TIMESTAMPTZ | no | `now()` |
| `updated_at` | TIMESTAMPTZ | no | `now()` |
| `tax_inclusive` | BOOLEAN | no | `false` (V2) |
| `subscription_start_date` | DATE | no | backfilled from `created_at::date` (V4) |
| `subscription_end_date` | DATE | no | start + 1 year (V4) |
| `max_branches` | INTEGER | no | `5`; CHECK `>= 1` (V4) |

Constraints: `ck_stores_subscription_dates` (`end >= start`), `ck_stores_max_branches` (`max_branches >= 1`).

### 4.2 `branches`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | BIGSERIAL | no | PK |
| `store_id` | BIGINT | no | FK → `stores(id)` |
| `branch_code` | VARCHAR(30) | no | |
| `branch_name` | VARCHAR(150) | no | |
| `address` | TEXT | no | |
| `contact_number` | VARCHAR(20) | no | |
| `status` | VARCHAR(20) | no | `ACTIVE`; CHECK `ACTIVE`/`INACTIVE` |
| `created_at` | TIMESTAMPTZ | no | `now()` |
| `updated_at` | TIMESTAMPTZ | no | `now()` |
| `state` | VARCHAR(80) | yes | Place of supply (V2) |

Constraints: `uq_branches_code` UNIQUE `(store_id, branch_code)`, `uq_branches_store_id` UNIQUE `(store_id, id)`.

### 4.3 `roles`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | BIGSERIAL | no | PK |
| `store_id` | BIGINT | yes | FK → `stores(id)` ON DELETE CASCADE; NULL = platform role |
| `code` | VARCHAR(50) | no | |
| `name` | VARCHAR(100) | no | |
| `description` | VARCHAR(255) | yes | |
| `is_system` | BOOLEAN | no | `false` |
| `created_at` | TIMESTAMPTZ | no | `now()` |

Index: `uq_roles_store_code` UNIQUE `(COALESCE(store_id, 0), code)`.

System role codes: `PLATFORM_ADMIN` (store_id NULL), `STORE_OWNER`, `STORE_ADMIN`, `BRANCH_MANAGER`, `BRANCH_STAFF`.

### 4.4 `permissions`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | BIGSERIAL | no | PK |
| `code` | VARCHAR(60) | no | UNIQUE |
| `name` | VARCHAR(120) | no | |
| `description` | VARCHAR(255) | yes | |

After V6 the catalogue has 30 rows, including `PRINTER_VIEW` and `PRINTER_MANAGE`.

### 4.5 `role_permissions`

| Column | Type | Null | Constraint |
|---|---|---|---|
| `role_id` | BIGINT | no | PK, FK → `roles(id)` ON DELETE CASCADE |
| `permission_id` | BIGINT | no | PK, FK → `permissions(id)` ON DELETE CASCADE |

### 4.6 `users`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | BIGSERIAL | no | PK |
| `store_id` | BIGINT | yes | FK → `stores(id)`; NULL for platform admin |
| `username` | VARCHAR(80) | no | |
| `email` | VARCHAR(150) | no | |
| `password_hash` | VARCHAR(255) | no | BCrypt |
| `full_name` | VARCHAR(150) | no | |
| `mobile` | VARCHAR(20) | yes | |
| `status` | VARCHAR(20) | no | `ACTIVE`; CHECK `ACTIVE`/`INACTIVE` |
| `created_at` | TIMESTAMPTZ | no | `now()` |
| `updated_at` | TIMESTAMPTZ | no | `now()` |

Constraints/indexes: `uq_users_store_username` UNIQUE `(store_id, username)`, `ix_users_email`, `ix_users_store`.

### 4.7 `user_roles`

`user_id` BIGINT PK/FK → `users(id)` ON DELETE CASCADE; `role_id` BIGINT PK/FK → `roles(id)` ON DELETE CASCADE.

### 4.8 `user_branches`

`user_id` BIGINT PK/FK → `users(id)` ON DELETE CASCADE; `branch_id` BIGINT PK/FK → `branches(id)` ON DELETE CASCADE.

### 4.9 `categories`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | BIGSERIAL | no | PK |
| `store_id` | BIGINT | no | FK → `stores(id)` |
| `category_name` | VARCHAR(120) | no | |
| `description` | VARCHAR(255) | yes | |
| `status` | VARCHAR(20) | no | `ACTIVE`; CHECK `ACTIVE`/`INACTIVE` |
| `created_at` | TIMESTAMPTZ | no | `now()` |
| `updated_at` | TIMESTAMPTZ | no | `now()` |

Constraints: UNIQUE `(store_id, category_name)`, UNIQUE `(store_id, id)`.

### 4.10 `items`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | BIGSERIAL | no | PK |
| `store_id` | BIGINT | no | FK → `stores(id)` |
| `category_id` | BIGINT | no | composite FK `(category_id, store_id)` → `categories(id, store_id)` |
| `item_code` | VARCHAR(50) | no | |
| `item_name` | VARCHAR(150) | no | |
| `base_unit` | VARCHAR(20) | no | CHECK `LITER`, `ML`, `KG`, `GRAM`, `PIECE`, `PACKET`, `BOX`, `BOTTLE`, `DOZEN` |
| `rate` | NUMERIC(12,2) | no | CHECK `>= 0`; replaces the dropped default prices (V3) |
| `tax_id` | BIGINT | no | composite FK `(tax_id, store_id)` → `tax_master(id, store_id)` (V2) |
| `min_stock_level` | NUMERIC(18,3) | no | `5`; CHECK `>= 0` |
| `status` | VARCHAR(20) | no | `ACTIVE`; CHECK `ACTIVE`/`INACTIVE` |
| `created_at` | TIMESTAMPTZ | no | `now()` |
| `updated_at` | TIMESTAMPTZ | no | `now()` |
| `description` | VARCHAR(255) | yes | V5, used by the import template |

Removed in V3: `default_purchase_price NUMERIC(14,2)`, `default_selling_price NUMERIC(14,2)`.

Constraints/indexes: UNIQUE `(store_id, item_code)`, UNIQUE `(store_id, id)`, `ix_items_store_category`, `ix_items_name`, `ix_items_tax`.

### 4.11 `suppliers`

`id` PK; `store_id` FK; `supplier_name` VARCHAR(150) NOT NULL; `mobile` VARCHAR(20) NOT NULL; `address` TEXT NOT NULL; `gst_number` VARCHAR(30); `email` VARCHAR(150); `state` VARCHAR(80) (V2); `status` VARCHAR(20) DEFAULT `ACTIVE` CHECK; `created_at`, `updated_at` TIMESTAMPTZ. UNIQUE `(store_id, id)`.

### 4.12 `customers`

`id` PK; `store_id` FK; `name` VARCHAR(150) NOT NULL; `mobile` VARCHAR(20); `created_at` TIMESTAMPTZ NOT NULL DEFAULT `now()`; UNIQUE `(store_id, id)`.

### 4.13 `branch_inventory`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | BIGSERIAL | no | PK |
| `store_id` | BIGINT | no | FK → `stores(id)` |
| `branch_id` | BIGINT | no | composite FK → `branches(id, store_id)` |
| `item_id` | BIGINT | no | composite FK → `items(id, store_id)` |
| `current_stock` | NUMERIC(18,3) | no | `0`; CHECK `>= 0` |
| `minimum_stock` | NUMERIC(18,3) | no | `5`; CHECK `>= 0` |
| `purchase_price` | NUMERIC(14,2) | no | `0`; CHECK `>= 0` |
| `selling_price` | NUMERIC(14,2) | no | `0`; CHECK `>= 0` |
| `avg_cost` | NUMERIC(18,4) | no | `0`; CHECK `>= 0` |
| `updated_at` | TIMESTAMPTZ | no | `now()` |

Constraints/indexes: UNIQUE `(store_id, branch_id, item_id)`, `ix_inventory_item`, `ix_inventory_branch`.

### 4.14 `inventory_transactions`

`id` PK; `store_id` FK; `branch_id` composite FK; `item_id` composite FK; `transaction_type` VARCHAR(30) CHECK in `OPENING_STOCK`, `PURCHASE_IN`, `SALE_OUT`, `TRANSFER_IN`, `TRANSFER_OUT`, `ADJUSTMENT_IN`, `ADJUSTMENT_OUT`, `PURCHASE_REVERSAL`, `SALE_REVERSAL`; `reference_type` VARCHAR(30); `reference_id` BIGINT; `reference_number` VARCHAR(50); `quantity_in` NUMERIC(18,3) DEFAULT 0 CHECK `>= 0`; `quantity_out` NUMERIC(18,3) DEFAULT 0 CHECK `>= 0`; `balance_after` NUMERIC(18,3) NOT NULL CHECK `>= 0`; `notes` VARCHAR(255); `transaction_date` TIMESTAMPTZ DEFAULT `now()`; `created_by` FK → `users(id)`.

Indexes: `ix_txn_store_date`, `ix_txn_branch_item`, `ix_txn_reference`, plus partial UNIQUE `uq_txn_single_opening_stock (store_id, branch_id, item_id) WHERE transaction_type = 'OPENING_STOCK'` (V5).

### 4.15 `stock_adjustments`

`id` PK; `store_id` FK; `branch_id` composite FK; `item_id` composite FK; `adjustment_number` VARCHAR(50); `adjustment_type` CHECK `IN`/`OUT`; `quantity` NUMERIC(18,3) CHECK `> 0`; `base_quantity` NUMERIC(18,3) CHECK `> 0`; `unit` VARCHAR(20); `reason` VARCHAR(255); `created_by` FK; `created_at`. UNIQUE `(store_id, adjustment_number)`.

### 4.16 `purchases`

Base columns: `id` PK; `store_id` FK; `branch_id` composite FK; `supplier_id` composite FK `(supplier_id, store_id)`; `purchase_number` VARCHAR(50); `invoice_number` VARCHAR(80)`; `purchase_date` DATE; `subtotal`, `discount`, `tax`, `grand_total`, `paid_amount`, `balance_amount` NUMERIC(14,2) DEFAULT 0; `notes` VARCHAR(500); `status` CHECK `DRAFT`/`COMPLETED`/`CANCELLED` DEFAULT `COMPLETED`; `created_by` FK; `created_at`, `updated_at`.

V2 additions: `taxable_amount`, `cgst_amount`, `sgst_amount`, `igst_amount` NUMERIC(14,2) DEFAULT 0.

Constraints/indexes: UNIQUE `(store_id, purchase_number)`, UNIQUE `(store_id, id)`, `ix_purchases_store_date`, `ix_purchases_branch`.

### 4.17 `purchase_details`

Base columns: `id` PK; `store_id` FK; `purchase_id` composite FK → `purchases(id, store_id)` ON DELETE CASCADE; `item_id` composite FK; `quantity` NUMERIC(18,3) CHECK `> 0`; `unit` VARCHAR(20); `base_quantity` NUMERIC(18,3) CHECK `> 0`; `purchase_rate` NUMERIC(14,2) CHECK `>= 0`; `discount`, `tax` NUMERIC(14,2) DEFAULT 0; `total_amount` NUMERIC(14,2) NOT NULL.

V2 additions: `tax_id` BIGINT (composite FK `(tax_id, store_id)`); `tax_name_snapshot` VARCHAR(120) NOT NULL DEFAULT `'GST 0% (Exempt)'`; `gst_percentage`, `cgst_percentage`, `sgst_percentage`, `igst_percentage` NUMERIC(5,2) DEFAULT 0; `taxable_amount`, `cgst_amount`, `sgst_amount`, `igst_amount`, `tax_amount` NUMERIC(14,2) DEFAULT 0.

Index: `ix_purchase_details_purchase`.

### 4.18 `sales`

Base columns: `id` PK; `store_id` FK; `branch_id` composite FK; `sales_number` VARCHAR(50); `sales_date` DATE; `customer_id` composite FK `(customer_id, store_id)`; `customer_name` VARCHAR(150); `customer_mobile` VARCHAR(20); `subtotal`, `discount`, `tax`, `grand_total`, `paid_amount`, `balance_amount` NUMERIC(14,2) DEFAULT 0; `payment_method` CHECK `CASH`/`UPI`/`CARD`/`CREDIT` DEFAULT `CASH`; `status` CHECK `COMPLETED`/`CANCELLED` DEFAULT `COMPLETED`; `created_by` FK; `created_at`.

V2 additions: `customer_state` VARCHAR(80); `taxable_amount`, `cgst_amount`, `sgst_amount`, `igst_amount` NUMERIC(14,2) DEFAULT 0.

Constraints/indexes: UNIQUE `(store_id, sales_number)`, UNIQUE `(store_id, id)`, `ix_sales_store_date`, `ix_sales_branch`.

### 4.19 `sales_details`

Base columns: `id` PK; `store_id` FK; `sales_id` composite FK → `sales(id, store_id)` ON DELETE CASCADE; `item_id` composite FK; `quantity` NUMERIC(18,3) CHECK `> 0`; `unit` VARCHAR(20); `base_quantity` NUMERIC(18,3) CHECK `> 0`; `selling_rate` NUMERIC(14,2) CHECK `>= 0`; `cost_rate` NUMERIC(18,4) DEFAULT 0; `discount`, `tax` NUMERIC(14,2) DEFAULT 0; `total_amount` NUMERIC(14,2) NOT NULL.

V2 additions: same tax snapshot and amount columns as `purchase_details`.

Index: `ix_sales_details_sale`.

### 4.20 `stock_transfers`

`id` PK; `store_id` FK; `transfer_number` VARCHAR(50); `from_branch_id`, `to_branch_id` composite FKs → `branches(id, store_id)`; `transfer_date` DATE; `status` CHECK `DRAFT`/`PENDING`/`APPROVED`/`DISPATCHED`/`RECEIVED`/`REJECTED`/`CANCELLED` DEFAULT `PENDING`; `notes` VARCHAR(500); `created_by` FK; `created_at`, `updated_at`; CHECK `from_branch_id <> to_branch_id`; UNIQUE `(store_id, transfer_number)`, UNIQUE `(store_id, id)`; `ix_transfers_store`.

### 4.21 `stock_transfer_details`

`id` PK; `store_id` FK; `transfer_id` composite FK → `stock_transfers(id, store_id)` ON DELETE CASCADE; `item_id` composite FK; `quantity`, `base_quantity` NUMERIC(18,3) CHECK `> 0`; `unit` VARCHAR(20).

### 4.22 `audit_logs`

`id` PK; `store_id` BIGINT; `user_id` BIGINT; `username` VARCHAR(80); `action` VARCHAR(60) NOT NULL; `entity_type` VARCHAR(60) NOT NULL; `entity_id` BIGINT; `details` VARCHAR(1000); `created_at` TIMESTAMPTZ DEFAULT `now()`; `ix_audit_store_date`.

### 4.23 `tax_master` (V2)

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | BIGSERIAL | no | PK |
| `store_id` | BIGINT | no | FK → `stores(id)` |
| `tax_name` | VARCHAR(120) | no | |
| `description` | VARCHAR(255) | yes | |
| `gst_percentage` | NUMERIC(5,2) | no | CHECK 0–100 |
| `cgst_percentage` | NUMERIC(5,2) | no | `0`; CHECK 0–100 |
| `sgst_percentage` | NUMERIC(5,2) | no | `0`; CHECK 0–100 |
| `igst_percentage` | NUMERIC(5,2) | no | `0`; CHECK 0–100 |
| `tax_type` | VARCHAR(20) | no | `GST`; CHECK `GST`/`EXEMPT` |
| `status` | VARCHAR(20) | no | `ACTIVE`; CHECK |
| `created_at`, `updated_at` | TIMESTAMPTZ | no | `now()` |

Constraints/indexes: UNIQUE `(store_id, tax_name)`, UNIQUE `(store_id, id)`, `ix_tax_master_store_status`.
V2 seeded five slabs per existing store: GST 0% Exempt, 5%, 12%, 18%, 28%.

### 4.24 `subscription_change_history` (V4)

`id` PK; `store_id` FK; `changed_by` FK → `users(id)`; `changed_by_username` VARCHAR(80); `changed_at` TIMESTAMPTZ DEFAULT `now()`; `previous_start_date`, `new_start_date`, `previous_end_date`, `new_end_date` DATE; `previous_max_branches`, `new_max_branches` INTEGER; `note` VARCHAR(255); index `ix_subscription_history_store (store_id, changed_at DESC)`.

### 4.25 `platform_settings` (V4)

Singleton row: `id` INTEGER PK CHECK `= 1`; `contact_email` VARCHAR(150); `contact_phone` VARCHAR(20); `updated_at` TIMESTAMPTZ DEFAULT `now()`. V4 inserts `id = 1`.

### 4.26 `item_import_batches` (V5)

`id` PK; `store_id` FK; `branch_id` composite FK → `branches(id, store_id)`; `import_number` VARCHAR(50); `file_name` VARCHAR(255); `file_hash` VARCHAR(64); `status` CHECK `PREVIEWED`/`COMPLETED`/`FAILED` DEFAULT `PREVIEWED`; `total_rows`, `success_rows`, `failed_rows`, `new_categories`, `new_items`, `existing_items`, `stock_updates`, `duplicate_rows`, `invalid_rows`, `already_imported_rows` INTEGER DEFAULT 0; `failure_message` VARCHAR(500); `uploaded_by` FK → `users(id)`; `uploaded_by_username` VARCHAR(80); `created_at` TIMESTAMPTZ DEFAULT `now()`; `completed_at` TIMESTAMPTZ.

Constraints/indexes: UNIQUE `(store_id, import_number)`; partial UNIQUE `(store_id, branch_id, file_hash) WHERE status = 'COMPLETED'`; `ix_import_batches_store`, `ix_import_batches_branch`.

### 4.27 `item_import_rows` (V5)

`id` PK; `batch_id` FK → `item_import_batches(id)` ON DELETE CASCADE; `store_id` FK; `row_number` INTEGER; `item_code` VARCHAR(50); `item_name` VARCHAR(150); `category_name` VARCHAR(120); `unit` VARCHAR(20); `rate` NUMERIC(14,2); `tax_name` VARCHAR(120); `opening_stock`, `minimum_stock` NUMERIC(18,3); `description` VARCHAR(255); `item_id` BIGINT; `parse_failed` BOOLEAN DEFAULT `false`; `import_action` VARCHAR(40); `validation_status` CHECK `VALID`/`INVALID`; `error_message` VARCHAR(500).

Constraints/indexes: UNIQUE `(batch_id, row_number)`, `ix_import_rows_batch`.

### 4.28 Sequences

`purchase_number_seq`, `sales_number_seq`, `transfer_number_seq`, `adjustment_number_seq` (V1, start 1001); `item_code_seq` (start 1001), `item_import_number_seq` (start 1) (V5).

---

## 5. New V6 tables — full detail

### 5.1 `printer_configs`

Branch-level LAN/thermal printer settings used by the Android bridge and as receipt metadata for
Windows jobs.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | no | | PK |
| `store_id` | BIGINT | no | | FK → `stores(id)` |
| `branch_id` | BIGINT | no | | composite FK → `branches(id, store_id)` |
| `printer_name` | VARCHAR(100) | no | | Unique per branch |
| `ip_address` | VARCHAR(120) | no | | IPv4 or hostname, validated by the API |
| `port` | INTEGER | no | `9100` | CHECK 1–65535 |
| `paper_width` | VARCHAR(8) | no | `MM80` | CHECK `MM58`/`MM80` |
| `purpose` | VARCHAR(16) | no | `RECEIPT` | CHECK `RECEIPT`/`PURCHASE`/`REPORT`/`ALL` |
| `status` | VARCHAR(10) | no | `ACTIVE` | CHECK `ACTIVE`/`INACTIVE` |
| `last_test_at` | TIMESTAMPTZ | yes | | Last bridge test result time |
| `last_test_message` | VARCHAR(255) | yes | | Last test message |
| `created_at` | TIMESTAMPTZ | no | `now()` | |
| `updated_at` | TIMESTAMPTZ | no | `now()` | |

Constraints: `uq_printer_config_name` UNIQUE `(store_id, branch_id, printer_name)`, `fk_printer_branch`.
Index: `ix_printer_configs_store (store_id, branch_id, status)`.

A branch may have several printers; printing resolves an active `RECEIPT`/`ALL` printer, or the
explicit `printerConfigId` passed with the job (which must belong to the same store and branch).

### 5.2 `print_pairing_codes`

One-time codes typed into an Android bridge.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | no | | PK |
| `store_id` | BIGINT | no | | FK → `stores(id)` |
| `branch_id` | BIGINT | no | | composite FK → `branches(id, store_id)` |
| `code_hash` | VARCHAR(64) | no | | SHA-256 hex of the normalised code; UNIQUE |
| `created_by` | BIGINT | yes | | FK → `users(id)` |
| `expires_at` | TIMESTAMPTZ | no | | Issue time + 10 minutes |
| `used_at` | TIMESTAMPTZ | yes | | Set on successful pairing |
| `created_at` | TIMESTAMPTZ | no | `now()` | |

Constraints: UNIQUE `code_hash`, `fk_pairing_branch`.
Index: `ix_print_pairing_codes_store (store_id, expires_at)`.

The plain code exists only in the API response that created it. Pairing validates expiry, use and
branch, then marks `used_at`.

### 5.3 `print_devices`

Paired Android bridges. Windows bridges do not pair with the backend.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | no | | PK |
| `store_id` | BIGINT | no | | FK → `stores(id)` |
| `branch_id` | BIGINT | no | | composite FK → `branches(id, store_id)` |
| `device_name` | VARCHAR(100) | no | | |
| `platform` | VARCHAR(16) | no | | CHECK `ANDROID`/`WINDOWS` |
| `token_hash` | VARCHAR(64) | no | | SHA-256 of the opaque device token; UNIQUE |
| `status` | VARCHAR(10) | no | `ACTIVE` | CHECK `ACTIVE`/`REVOKED` |
| `paired_by` | BIGINT | yes | | FK → `users(id)` |
| `paired_by_username` | VARCHAR(80) | yes | | Snapshot for display |
| `created_at` | TIMESTAMPTZ | no | `now()` | |
| `last_seen_at` | TIMESTAMPTZ | yes | | Updated on each config pull |

Constraints: UNIQUE `token_hash`, `fk_device_branch`.
Index: `ix_print_devices_store (store_id, branch_id, status)`.

Revoking a device sets `status = REVOKED`; the next `/api/printing/device/config` call returns 401.

### 5.4 `print_jobs`

Print history and idempotency guard.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | no | | PK |
| `store_id` | BIGINT | no | | FK → `stores(id)` |
| `branch_id` | BIGINT | no | | composite FK → `branches(id, store_id)` |
| `job_key` | VARCHAR(150) | no | | `AUTO:SALE_RECEIPT:<channel>:<branchId>:<saleId>` or `MANUAL:<uuid>` |
| `document_type` | VARCHAR(20) | no | | CHECK `SALE_RECEIPT`/`PURCHASE`/`TEST` |
| `document_id` | BIGINT | yes | | Sale id for receipt jobs |
| `document_number` | VARCHAR(60) | yes | | Sale number snapshot |
| `printer_config_id` | BIGINT | yes | | FK → `printer_configs(id)` ON DELETE SET NULL |
| `printer_name` | VARCHAR(100) | yes | | Snapshot; NULL for Windows default printer |
| `channel` | VARCHAR(16) | no | | CHECK `WINDOWS`/`ANDROID` |
| `trigger_type` | VARCHAR(10) | no | | CHECK `AUTO`/`MANUAL` |
| `status` | VARCHAR(16) | no | `PENDING` | CHECK `PENDING`/`PRINTED`/`FAILED` |
| `attempts` | INTEGER | no | `0` | Incremented when the bridge reports a result |
| `error_message` | VARCHAR(500) | yes | | Failure detail |
| `created_by` | BIGINT | yes | | FK → `users(id)` |
| `created_by_username` | VARCHAR(80) | yes | | Snapshot |
| `created_at` | TIMESTAMPTZ | no | `now()` | |
| `updated_at` | TIMESTAMPTZ | no | `now()` | |
| `printed_at` | TIMESTAMPTZ | yes | | Set on `PRINTED` |

Constraints: `uq_print_job_store_key` UNIQUE `(store_id, job_key)`, `fk_print_job_branch`,
`print_jobs_printer_config_id_fkey` (ON DELETE SET NULL).
Indexes: `ix_print_jobs_store (store_id, created_at DESC)`,
`ix_print_jobs_branch (store_id, branch_id, created_at DESC)`.

#### Idempotency and isolation rules enforced by the schema + service

- The unique `(store_id, job_key)` makes two concurrent `AUTO` requests for the same sale produce
  exactly one row; the loser returns the existing job with `duplicate = true`.
- `MANUAL:<uuid>` keeps reprints separate from the original automatic job.
- `document_id` must reference a `COMPLETED` sale in the same store (`sales.status`), and the
  request user must be allowed the sale's branch. Cancelled and cross-store sales are rejected.
- Android jobs need an active receipt printer; Windows jobs may omit one and use the PC default.
- Deleting a printer keeps history: `printer_config_id` becomes NULL while `printer_name` remains.

---

## 6. Relationships added by V6

```
stores (1) ──< printer_configs (N)
stores (1) ──< print_pairing_codes (N)
stores (1) ──< print_devices (N)
stores (1) ──< print_jobs (N)

branches (1) ──< printer_configs (N)   via (branch_id, store_id)
branches (1) ──< print_pairing_codes (N)
branches (1) ──< print_devices (N)
branches (1) ──< print_jobs (N)

users (1) ──< print_pairing_codes.created_by (N)
users (1) ──< print_devices.paired_by (N)
users (1) ──< print_jobs.created_by (N)

printer_configs (1) ──< print_jobs (N)  ON DELETE SET NULL

sales (1) ──< print_jobs (N)            logical link via document_id + document_number
```

All V6 tables are tenant-owned and cannot reference a branch from another store. Windows print
jobs may have no printer configuration; the sale branch still scopes them.

---

## 7. Permissions introduced by V6

| Code | Name | Granted by V6 to existing roles | Granted to new stores |
|---|---|---|---|
| `PRINTER_VIEW` | View printers | Every role with `store_id IS NOT NULL` | Owner, admin, branch manager |
| `PRINTER_MANAGE` | Manage printers | `STORE_OWNER`, `STORE_ADMIN` | Owner, admin |

API implications:

| Permission | Endpoints |
|---|---|
| `PRINTER_VIEW` | `GET /api/printing/printers`, `GET /api/printing/jobs`, `GET /api/printing/jobs/{id}` |
| `PRINTER_MANAGE` | Printer create/update/status/test/delete, pairing-code generation, paired-device list/revoke |
| `SALES_MANAGE` | Create job, report job status, reprint |
| `SALES_VIEW` | Print History page/route and job listing |
| none (code/token instead) | `POST /api/printing/devices/pair`, `GET /api/printing/device/config` |

Existing custom roles can be granted either permission from **Settings → Roles** without data
migration. V6 does not grant printer permissions to the platform administrator role because
printing is always store/branch scoped.

---

## 8. Verification SQL (read-only)

Run as the **database owner or a read-only operator**, never in the preparation phase against
production. Replace nothing; all queries are read-only.

### 8.1 Flyway history

```sql
SELECT installed_rank, version, description, type, script, checksum, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

Expected after deployment: six rows, V1–V6, every `success = true`. V6 description `printing`.

### 8.2 Printing tables

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('printer_configs', 'print_pairing_codes', 'print_devices', 'print_jobs')
ORDER BY table_name;
```

Expected: exactly four rows.

### 8.3 Columns, types and nullability

```sql
SELECT table_name,
       ordinal_position,
       column_name,
       data_type,
       character_maximum_length,
       numeric_precision,
       numeric_scale,
       is_nullable,
       column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('printer_configs', 'print_pairing_codes', 'print_devices', 'print_jobs')
ORDER BY table_name, ordinal_position;
```

Expected column counts: `printer_configs` 14, `print_pairing_codes` 8, `print_devices` 11,
`print_jobs` 19.

### 8.4 Constraints

```sql
SELECT conrelid::regclass AS table_name,
       conname,
       CASE contype WHEN 'p' THEN 'PRIMARY KEY' WHEN 'u' THEN 'UNIQUE'
                    WHEN 'f' THEN 'FOREIGN KEY' WHEN 'c' THEN 'CHECK' END AS kind,
       pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid IN ('printer_configs'::regclass, 'print_pairing_codes'::regclass,
                   'print_devices'::regclass, 'print_jobs'::regclass)
ORDER BY table_name, kind, conname;
```

Expected joins: each table has `store_id → stores(id)` and `(branch_id, store_id) → branches(id, store_id)`;
`print_jobs.printer_config_id → printer_configs(id) ON DELETE SET NULL`; unique keys listed in section 5.

### 8.5 Indexes

```sql
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN ('printer_configs', 'print_pairing_codes', 'print_devices', 'print_jobs')
ORDER BY tablename, indexname;
```

Expected non-constraint indexes: `ix_printer_configs_store`, `ix_print_pairing_codes_store`,
`ix_print_devices_store`, `ix_print_jobs_store`, `ix_print_jobs_branch`.

### 8.6 Permissions and role assignments

```sql
SELECT code, name, description
FROM permissions
WHERE code LIKE 'PRINTER%'
ORDER BY code;
```

```sql
SELECT r.id                AS role_id,
       COALESCE(s.store_code, 'PLATFORM') AS store,
       r.code              AS role_code,
       p.code              AS permission_code
FROM role_permissions rp
JOIN roles r       ON r.id = rp.role_id
JOIN permissions p ON p.id = rp.permission_id
LEFT JOIN stores s ON s.id = r.store_id
WHERE p.code IN ('PRINTER_VIEW', 'PRINTER_MANAGE')
ORDER BY store, role_code, permission_code;
```

```sql
SELECT p.code, COUNT(*) AS role_count
FROM role_permissions rp
JOIN permissions p ON p.id = rp.permission_id
WHERE p.code IN ('PRINTER_VIEW', 'PRINTER_MANAGE')
GROUP BY p.code
ORDER BY p.code;
```

Expected: 2 permission rows; `PRINTER_MANAGE` only for store owner/admin roles;
`PRINTER_VIEW` for every store-scoped role (including roles created by V6 time).

### 8.7 Cross-store and null checks

```sql
-- Must return 0: jobs whose branch belongs to a different store
SELECT COUNT(*) AS cross_store_jobs
FROM print_jobs j
JOIN branches b ON b.id = j.branch_id
WHERE b.store_id <> j.store_id;

-- Must return 0: printers whose branch belongs to a different store
SELECT COUNT(*) AS cross_store_printers
FROM printer_configs p
JOIN branches b ON b.id = p.branch_id
WHERE b.store_id <> p.store_id;

-- Must return 0: duplicate automatic job keys (unique index should already prevent this)
SELECT store_id, job_key, COUNT(*)
FROM print_jobs
GROUP BY store_id, job_key
HAVING COUNT(*) > 1;
```

### 8.8 Row counts after deployment

```sql
SELECT 'printer_configs' AS table_name, COUNT(*) FROM printer_configs
UNION ALL SELECT 'print_pairing_codes', COUNT(*) FROM print_pairing_codes
UNION ALL SELECT 'print_devices', COUNT(*) FROM print_devices
UNION ALL SELECT 'print_jobs', COUNT(*) FROM print_jobs;
```

Expected immediately after deployment: all zero (V6 creates structure only). Counts grow as stores
configure printers and print receipts.

### 8.9 Table inventory after V6

```sql
SELECT COUNT(*) AS public_tables
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
```

Expected: 31 (includes Flyway's `flyway_schema_history`? No — that table is also in `public`, so the
count with Flyway is 32). Verify by listing names:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;
```

---

## 9. Hibernate entity ↔ schema validation

`spring.jpa.hibernate.ddl-auto=validate` makes application startup fail if an entity column or
table is missing or has an incompatible type. The backend integration suite (`clean test`) starts
the full application against a Flyway-migrated database, so a green suite is direct evidence that:

- `PrinterConfig`, `PrintPairingCode`, `PrintDevice` and `PrintJob` match V6.
- Every pre-existing entity still matches V1–V5.
- Enum `@Enumerated(STRING)` values fit the `VARCHAR` lengths and CHECK constraints.
- `Instant` fields map to `TIMESTAMPTZ`.

Entity-to-table map:

| Entity | Table |
|---|---|
| `PrinterConfig` | `printer_configs` |
| `PrintPairingCode` | `print_pairing_codes` |
| `PrintDevice` | `print_devices` |
| `PrintJob` | `print_jobs` |

---

## 10. PostgreSQL 18.6 compatibility notes

V6 uses only features supported by PostgreSQL 18.6 (and by PostgreSQL 14+):

- `BIGSERIAL`, `TIMESTAMPTZ`, `CHECK`, composite `FOREIGN KEY`, `UNIQUE`, `CREATE INDEX`.
- `INSERT … SELECT … ON CONFLICT DO NOTHING` (requires the existing primary key on `role_permissions`, which V1 provides).
- No extensions, triggers, generated columns, partitions, `CREATE INDEX CONCURRENTLY` or other
  operationally sensitive constructs.
- No existing row is updated and no `NOT NULL` column is added to a table with existing rows.

V6 runs inside Flyway's transaction. If any statement fails, the whole migration rolls back and the
service start fails; the database remains at V5.

---

## 11. Safety summary

| Check | Result |
|---|---|
| Existing tables dropped or renamed by V6 | No |
| Existing columns altered by V6 | No |
| Existing rows updated/deleted by V6 | No |
| Backfill required for NOT NULL additions | No |
| Duplicate migration version | No (V6 is unused in production) |
| Applied migrations edited | No (V1–V5 untouched) |
| Production data preserved | Yes — additive DDL and permission inserts only |
| Rollback of V6 | Restore the pre-deployment backup; V6 itself has no down script |
