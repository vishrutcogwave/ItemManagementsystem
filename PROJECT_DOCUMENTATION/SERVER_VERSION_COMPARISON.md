# Server Version Comparison — Deployed vs Repository

Prepared: 2026-11-01

> **Inspection status: BLOCKED.** No SSH access, credentials, or read-only production connection
> was provided, so the live server was **not** inspected. A live comparison of the running jar,
> frontend bundle, git commit, schema history and configuration **could not be performed**.
> Everything labelled "declared" below comes from the project’s own production runbook
> (`../DEPLOYMENT_RUNBOOK.md`) and is treated as the last known documented state, not as a verified
> live observation. Everything labelled "repository" was verified directly in this working copy.
> Sections 12–13 list exactly what must be checked on the server before deployment.

---

## 1. Executive summary

| Question | Answer |
|---|---|
| Is the live server running the current code? | **Unverified; almost certainly not.** Documented schema is V1–V5; the repository now requires V1–V8. |
| What is missing on the server? | Flyway **V6 (printing), V7 (item barcode), V8 (auth security)**; the barcode backend/frontend and ZXing fallback; Platform Admin store-account management and audit viewer; password change/forced change; cookie sessions and persistent revocation; health/version endpoints; printing UI fixes; the mobile `/sales` layout fix. |
| Are any changes destructive? | **No new destructive migration.** V6, V7 and V8 are additive (tables, columns, indexes, permission rows, data backfill). V3 was destructive but is already applied. |
| Can the database be deployed safely? | Yes, with a verified pre-deployment backup. V7 backfills every existing item deterministically; V8 rewrites no row. |
| Can the old jar be rolled back after V7? | **Not fully by itself.** See Section 9 — V7 makes `items.barcode` `NOT NULL` without a database default, so the old jar’s item create/import would fail; use `docs/sql/07_rollback_v7_compatibility.sql` for an emergency jar rollback. V8 is additive. |
| Are there breaking API changes? | No removed or renamed endpoints/fields. Barcode/auth additions are additive. Login now also sets an `HttpOnly` cookie; clients may still send `Authorization: Bearer`. |
| Are new environment variables required? | **Recommended, not strictly required:** `AUTH_COOKIE_SECURE=true` and the exact HTTPS `CORS_ORIGINS` for production-safe cookie sessions; optional `LOGIN_*` settings. Existing secrets must not be overwritten. |
| New third-party frontend dependency? | `jsbarcode@3.12.3` (+ `@types/jsbarcode` dev dependency) and `@zxing/browser@0.1.5` for the camera fallback. Backend dependencies unchanged. |
| Deployment performed? | **No.** This document is comparison and instruction only, per the approval gate. |

---

## 2. Evidence and method

| Evidence | Source | Verified? |
|---|---|---|
| Deployed host, paths, DB name, Java/PostgreSQL versions, schema level V1–V5, V6 pending | `DEPLOYMENT_RUNBOOK.md` § header and § C | Declared in project docs; **not live-verified** |
| Deployed jar hash `192daa4d…`, 150,400,815 bytes | `DEPLOYMENT_RUNBOOK.md` artifact table | This is the **built V6-release jar**, not proof of what is installed on the server |
| Existing pre-V6 backup `/root/item_management_backup_before_v6.dump` | `DEPLOYMENT_RUNBOOK.md` | Declared; not live-verified |
| Branch, commit, working-tree changes | `git status`, `git log` in this repository | Verified locally |
| Current jar hash/size/build time | `sha256sum`, `ls -l` on `backend/build/libs/` | Verified locally |
| Frontend bundle size | `dist/` on disk | Verified locally |
| Migrations V1–V8, APIs, permissions | Repository source files | Verified locally |
| Test results | `TEST_EXECUTION_REPORT.md` | Executed locally |

Limits:

- The repository has uncommitted changes; the deployed artifact cannot be matched to a commit
  without the live jar hash.
- The live server may already differ from the runbook (manual changes, hotfixes, config edits).
- No claim is made about production data volume, row counts, or whether V6 was applied out-of-band.

---

## 3. Deployed baseline (declared)

| Item | Declared value |
|---|---|
| Domain | `hiremathwebstudio.in` |
| Server | Hostinger Ubuntu VPS `201.18.205.217` |
| Service | systemd `item-management` |
| Backend port | `8080` |
| Backend jar path | `/opt/item-management/backend/item-management.jar` |
| Env file | `/etc/item-management.env` |
| Frontend root | `/var/www/item-management` |
| Nginx site | `/etc/nginx/sites-available/item-management` |
| Database | `item_management_prod`, user `ims_user` |
| PostgreSQL | 18.6 |
| Java | 17 |
| Schema | **V1–V5 applied, V6 pending** |
| Last documented backup | `/root/item_management_backup_before_v6.dump` |
| Last built release jar (not necessarily installed) | 150,400,815 bytes, SHA-256 `192daa4ddf8dc08c3502ef98ecedfb14e37ad8ef6501429e48f066dcec779a59` |

The documented release at that point already contained the printing feature code, but the runbook
explicitly waits for approval before applying V6. Therefore the **installed** jar is likely the
release **before** printing. Its exact identity is unknown.

---

## 4. Repository target state

| Item | Value |
|---|---|
| Branch | `main` |
| HEAD commit | `01567eb018d8b87f56bda05a6eeb677645eec3ec` — “new code updated”, 2026-11-01 04:33:46 +0530 |
| Working tree | **Dirty** — barcode, platform-account, printing, and auth/security hardening are uncommitted |
| Backend jar | `backend/build/libs/item-management-backend-1.0.0.jar`, 150,463,066 bytes, built 2026-11-01 23:28 |
| Backend jar SHA-256 | `17f9787fe17176e412f824a44248bb1a4bb0ae3de0a01f3b916fbe4a41d7fbf8` |
| Frontend bundle | `dist/` rebuilt 2026-11-01 23:31 — 57 files, ≈ 3.0 MB; archive SHA-256 `600dbe7d156cd4c19388312582205df8a39814c08a0ac2b1d776bd8f8d48fe39` |
| Migrations present | V1–V8 (V6, V7 and V8 are new relative to production) |
| Backend tests | 68 passed, 0 failed |
| Frontend checks | `tsc -b` clean; `oxlint` 0 warnings, 0 errors; production build successful; `npm audit` 0 vulnerabilities |

Because the working tree is dirty, **commit and tag the release before deploying** so the deployed
artifact is reproducible.

---

## 5. Side-by-side comparison

| Dimension | Deployed (declared) | Repository (verified) | Delta |
|---|---|---|---|
| Application version | 1.0.0 (assumed) | 1.0.0 | Same version string |
| Git branch / commit | Unknown | `main` @ `01567eb` + uncommitted work | Cannot map |
| Backend jar hash | Unknown; last built V6 jar `192daa4d…` | `17f9787f…` (150,463,066 bytes) | Different build |
| Backend jar size | 150,400,815 (V6 build) | 150,463,066 | +62,251 bytes |
| Flyway schema | V1–V5 | V1–V8 | **V6, V7, V8 pending** |
| Printing tables | Not created | 4 tables in V6 | Pending |
| Barcode column | Absent | `items.barcode` NOT NULL + unique per store (V7) | Pending |
| Auth hardening | No `token_version`, `must_change_password` or `revoked_tokens` | V8 adds all three | Pending |
| Permissions | 28 (V1 + V2) | 30 (adds `PRINTER_VIEW`, `PRINTER_MANAGE`) | +2 |
| Frontend bundle | Previous release | Rebuilt `dist/`, 57 files, ≈ 3.0 MB, with barcode UI, audit viewer, password screens | Replace |
| Config variables | See runbook § C | Add `AUTH_COOKIE_SECURE=true` and exact `CORS_ORIGINS` (recommended); optional `LOGIN_*` | **Additive; preserve existing secrets** |
| Backend dependencies | Unchanged | Unchanged | **None** |
| Frontend dependencies | No barcode library | `jsbarcode@3.12.3`, `@types/jsbarcode@3.11.4`, `@zxing/browser@0.1.5` | +3 packages |
| Print bridges | Installed artifacts unchanged | Source and artifacts unchanged | **No update required** |
| Windows/Android bridge protocol | v1 | v1 | Unchanged |

### 5.1 Permissions count detail

V1 defines 26 permissions. V2 adds `TAX_VIEW` and `TAX_MANAGE` (28). V6 adds `PRINTER_VIEW` and
`PRINTER_MANAGE` (30). V7 adds no permissions. The Platform Account APIs reuse the existing
`PLATFORM_MANAGE` authority, so no new permission is introduced for them.

---

## 6. Backend changes in the repository (relative to HEAD, and relative to V1–V5)

### 6.1 New backend files

| File | Purpose |
|---|---|
| `controller/PlatformAccountController.java` | Platform-only store account listing, lookup, username update, enable/disable, password reset, credential history |
| `service/PlatformAccountService.java` | Tenant-isolated account management, temporary-password generation, audit trail, `confirm` guard |
| `service/BarcodeService.java` | Code 128 / EAN-13 generation, validation (regex + EAN check digit), uniqueness |
| `entity/enums/BarcodeFormat.java` | `CODE128`, `EAN13` |
| `dto/platform/PlatformAccountResponse.java` | Account view without any password material |
| `dto/platform/PlatformPasswordResetResponse.java` | One-time temporary password returned only on explicit reset |
| `dto/platform/CredentialHistoryResponse.java` | Credential-change audit entries |
| `dto/platform/PlatformAccountRequests.java` | Username update and reset-confirmation payloads |
| `resources/db/migration/V6__printing.sql` | Printing schema (from the earlier printing release; still pending on the server) |
| `resources/db/migration/V7__item_barcode.sql` | Barcode column, format check, backfill, unique index |
| `test/.../BarcodeIntegrationTest.java` | 6 barcode tests |
| `test/.../PlatformAccountIntegrationTest.java` | 5 platform-account tests |

### 6.2 Modified backend files

| File | Change |
|---|---|
| `controller/ItemController.java` | Adds `GET /api/items/barcode/{barcode}` and `POST /api/items/{id}/barcode` |
| `service/ItemService.java` | Barcode generation on create, regeneration, lookup by barcode, barcode search in POS |
| `service/ItemImportService.java` | Generates barcodes for imported items |
| `repository/ItemRepository.java` | `findByStoreIdAndBarcode`, `existsByStoreIdAndBarcode`, barcode-aware search |
| `entity/Item.java` | `barcode`, `barcodeFormat` mappings |
| `dto/master/ItemResponse.java` | Barcode fields and `BarcodeRequest` |
| `dto/inventory/PosItemResponse.java` | Barcode included for POS scan integration |
| `repository/UserRepository.java` | Queries used by platform account management |
| `repository/AuditLogRepository.java` | Credential-history query |
| `service/InventoryService.java` | POS item mapping now includes barcode |
| `config/DemoDataSeeder.java` | Demo items receive barcodes |

### 6.3 API delta

New endpoints (all additive):

| Method | Path | Authority | Purpose |
|---|---|---|---|
| GET | `/api/items/barcode/{barcode}` | `ITEM_VIEW` or `SALES_MANAGE` or `PURCHASE_MANAGE` | Resolve an item from a scanned/typed barcode, store-scoped |
| POST | `/api/items/{id}/barcode` | `ITEM_MANAGE` | Regenerate the barcode in `CODE128` or `EAN13` |
| GET | `/api/platform/accounts` | `PLATFORM_MANAGE` | Paginated store-user list, filter by search/status/storeId |
| GET | `/api/platform/accounts/{id}` | `PLATFORM_MANAGE` | Account detail |
| PUT | `/api/platform/accounts/{id}/username` | `PLATFORM_MANAGE` | Rename login account (3–80 chars, `A-Za-z0-9._-`) |
| PUT | `/api/platform/accounts/{id}/status` | `PLATFORM_MANAGE` | Enable/disable account |
| POST | `/api/platform/accounts/{id}/reset-password` | `PLATFORM_MANAGE` | Reset password; requires `{"confirm": true}`; returns a one-time temporary password |
| GET | `/api/platform/accounts/{id}/history` | `PLATFORM_MANAGE` | Credential-change history |

Existing endpoints changed in behaviour (not signature):

| Endpoint | Change |
|---|---|
| `POST /api/items`, `PUT /api/items/{id}` | Barcode is generated when omitted; supplied barcodes are validated and must be unique within the store |
| `GET /api/items`, `GET /api/items/pos` | Search also matches barcode; responses carry `barcode` and `barcodeFormat` |
| `POST /api/items/import/preview`, `.../confirm` | Imported rows get barcodes; duplicate barcodes are rejected |
| `POST /api/sales`, `POST /api/purchases` (indirect) | POS item payloads include barcode; receipt content unchanged apart from existing fields |

Removed endpoints: **none**.

### 6.4 Auth changes

- Login contract is unchanged: `storeCode` + `username` + `password`; `storeCode=PLATFORM` for
  platform admins.
- No change to JWT structure, expiry, or the security filter chain.
- Platform account operations:
  - Class-level `@PreAuthorize("hasAuthority('PLATFORM_MANAGE')")`.
  - Only platform admins (users with `store_id IS NULL`) are eligible operands.
  - Cross-tenant/store isolation is enforced server-side.
  - Passwords remain BCrypt-hashed; no plaintext password is read or returned.
  - Reset returns a 14-character temporary password **once**, requires `confirm=true`, and writes an
    audit entry without the secret.
- No self-service password change endpoint was added.

---

## 7. Database changes since the deployed V1–V5 state

### 7.1 V6 — printing (additive)

- Creates `printer_configs`, `print_pairing_codes`, `print_devices`, `print_jobs`.
- Adds `PRINTER_VIEW` and `PRINTER_MANAGE` permissions.
- Grants both to every store role; grants `PRINTER_MANAGE` to `STORE_OWNER` and `STORE_ADMIN`.
- Adds composite FKs to `branches(id, store_id)` to keep branch/store consistent.
- Adds unique keys: printer name per branch, pairing-code hash, device token hash, and
  `print_jobs(store_id, job_key)` for duplicate-print suppression.
- No changes to existing tables or existing rows.

### 7.2 V7 — item barcode (additive with a mandatory backfill)

| Step | Effect |
|---|---|
| `ALTER TABLE items ADD COLUMN barcode VARCHAR(64)` | Nullable at first |
| `ALTER TABLE items ADD COLUMN barcode_format VARCHAR(10) NOT NULL DEFAULT 'CODE128'` | Existing rows become `CODE128` |
| `CHECK (barcode_format IN ('CODE128','EAN13'))` | Format guard |
| `UPDATE items SET barcode = 'IMS' || LPAD(id,12,'0') WHERE barcode IS NULL` | Deterministic backfill |
| `ALTER TABLE items ALTER COLUMN barcode SET NOT NULL` | All future rows must have one |
| `CREATE UNIQUE INDEX uq_items_store_barcode ON items (store_id, barcode)` | Uniqueness per tenant + lookup index |

Backfill properties:

- Values are derived from the primary key, so they are unique across all stores and deterministic.
- Backfill length is 15 characters (`IMS` + 12 digits), within the 64-character limit.
- The migration touches only the new column and leaves names, codes, prices, stock and tax data
  untouched.
- Expected post-migration check: `SELECT COUNT(*) FROM items WHERE barcode IS NULL` → `0`.

### 7.3 Data-volume / lock considerations

`ALTER TABLE items ADD COLUMN` and `SET NOT NULL` take an `ACCESS EXCLUSIVE` lock briefly. On a
typical single-store dataset this is sub-second. V7 runs inside Flyway’s transaction. Schedule the
deployment during low activity; do not run it during a large import.

---

## 8. Frontend changes since the deployed build

### 8.1 New frontend files

| File | Purpose |
|---|---|
| `src/lib/barcode.ts` | Code 128 / EAN-13 generation and validation helpers |
| `src/components/barcode/BarcodeSvg.tsx` | JsBarcode-based SVG renderer |
| `src/components/barcode/BarcodeLabelDialog.tsx` | 50×30 mm label preview, multi-label print and PNG download |
| `src/components/barcode/BarcodeScanDialog.tsx` | Camera scanning via `BarcodeDetector` with graceful fallback |
| `src/components/barcode/ScanBar.tsx` | Reusable USB keyboard-wedge scan input |
| `src/pages/PlatformAccountsPage.tsx` | Platform Admin store-account management UI |

### 8.2 Modified frontend files

| File | Change |
|---|---|
| `src/App.tsx` | Platform accounts route |
| `src/components/layout/nav.tsx` | Platform accounts navigation |
| `src/components/ui/Modal.tsx` | Mobile-friendly sizing/scrolling |
| `src/pages/ItemsPage.tsx` | Barcode column, label dialog, regenerate action |
| `src/pages/PosPage.tsx` | Barcode scan integration and cart search |
| `src/pages/SalesEntryPage.tsx` | Barcode scan integration |
| `src/pages/PurchasesPage.tsx` | Barcode scan integration |
| `src/pages/InventoryPage.tsx` | Minor integration |
| `src/pages/SalesPage.tsx` | Fixes 4 px horizontal overflow at 320 px |
| `src/pages/settings/PrintersSettings.tsx` | Printing button overlap/state fixes |
| `src/types.ts` | Barcode and platform-account types |
| `package.json`, `package-lock.json` | `jsbarcode`, `@types/jsbarcode` |

### 8.3 Printing / mobile fixes

- Removed overlapping print actions in the mobile layout; actions now wrap with consistent spacing.
- Improved click handling and loading/error state so a second tap cannot fire a second job.
- Print job lifecycle and duplicate suppression unchanged (`(store_id, job_key)` unique).
- Windows bridge (127.0.0.1:17777) and Android bridge (127.0.0.1:17778) protocols unchanged; no bridge
  rebuild or re-pairing required.
- Responsive matrix (50 viewports, 2 platform + 8 store routes × 5 widths): 0 horizontal overflow
  after the `/sales` 320 px fix.

---

## 9. Compatibility and breaking changes

### 9.1 Forward compatibility (current code on server data)

| Area | Assessment |
|---|---|
| Existing rows | Safe. V6/V7 do not delete or rewrite existing business data. V7 adds one derived column. |
| Existing sessions | JWT format unchanged; already-issued tokens remain valid. |
| Existing print bridges | Unchanged contract; no re-pairing. |
| Existing API clients | New fields only; no removed/renamed JSON fields. |
| Existing frontend caches | Hashed assets change; a hard refresh or normal reload picks them up. |

### 9.2 Rollback compatibility — important

The previous jar **cannot create or import items once V7 is applied**, because V7 makes
`items.barcode` `NOT NULL` without a database default, and the older Item entity does not set it.
Reads, sales and purchases against existing items are unaffected.

| Scenario | Result |
|---|---|
| Roll back jar only, schema stays V7 | Service starts; reads/reports work; **item create and Excel import fail** with a NOT NULL violation |
| Roll back jar only, schema stays V7, no new items needed | Acceptable short-term emergency measure only |
| Restore the pre-deployment database dump | Full rollback; **destructive**, loses all data written after the backup; requires written approval |
| Forward fix | Preferred: ship a corrective release while keeping V6/V7 |

Consequences for the deployment plan:

1. Treat the backend deployment as effectively **one-way** unless a destructive restore is approved.
2. Test item creation and Excel import immediately after deployment.
3. If the new jar must be withdrawn, first add a safe database default or a compensating migration —
   do not simply swap the jar.

### 9.3 Other caveats

- `spring.jpa.hibernate.ddl-auto=validate`: the deployed jar and schema must match exactly. A jar
  containing V7 against a V5 database cannot start.
- Re-running Flyway is idempotent only for already-applied versions. Never edit an applied migration.
- Platform account reset returns a temporary password in the HTTP response by design; it must never
  be logged. See `KNOWN_ISSUES_AND_RECOMMENDATIONS.md`.

---

## 10. Customer impact

| Customer group | Impact |
|---|---|
| Store owners / admins | New barcode column, label printing, scan-to-add in POS/purchases; fixed mobile sales layout; fixed printing buttons; no credential changes. |
| Cashiers | Can scan barcodes into POS; existing keyboard/USB workflows preserved. |
| Store managers | Barcode labels and scan workflows; printer settings unchanged. |
| Platform admins | New Store Accounts screen to find a store’s login, rename accounts, enable/disable, reset passwords, and view credential history. |
| End customers | Receipts unchanged in content; label format is new (50×30 mm with name, item code, barcode, selling price). |
| Stores with expired subscriptions | Still blocked with `403 SUBSCRIPTION_EXPIRED`, as before. |

Operational impact:

- Short downtime while the service restarts (seconds).
- V7 backfill runs once at startup; time scales with item count, negligible for typical stores.
- No client-side bridge upgrade, no re-pairing, no printer reconfiguration required.
- A hard refresh is recommended after the frontend deploy.

---

## 11. Exact server changes required

1. **Database**: apply Flyway V6 and V7 by starting the new jar. No manual SQL.
2. **Backend**: replace `/opt/item-management/backend/item-management.jar` with the new artifact and
   restart `item-management`.
3. **Frontend**: replace the contents of `/var/www/item-management` with the new `dist/`.
4. **Nginx**: **no change required.** Verify `nginx -t` only.
5. **Environment**: **no new variables.** Do not overwrite `/etc/item-management.env`.
6. **Bridges**: **no change required.** Optionally confirm the production origin is allowlisted.
7. **Backups**: create and verify a fresh dump before step 1; keep the previous jar and frontend
   archive.

Nothing else on the server should change.

---

## 12. What must be checked on the live server (not done)

Run these read-only checks before deployment and record the output:

```bash
# 1. Identity of the installed backend artifact
ls -l /opt/item-management/backend/item-management.jar
sha256sum /opt/item-management/backend/item-management.jar

# 2. Are the new migrations already inside it?
unzip -l /opt/item-management/backend/item-management.jar | grep -E 'V[67]__'

# 3. Actual schema history
sudo -u postgres psql -d item_management_prod -c \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

# 4. Does V6/V7 already partly exist?
sudo -u postgres psql -d item_management_prod -c \
  "SELECT table_name FROM information_schema.tables
   WHERE table_schema='public'
     AND table_name IN ('printer_configs','print_pairing_codes','print_devices','print_jobs');"
sudo -u postgres psql -d item_management_prod -c \
  "SELECT column_name FROM information_schema.columns
   WHERE table_schema='public' AND table_name='items' AND column_name IN ('barcode','barcode_format');"

# 5. Deployed frontend identity
ls -l /var/www/item-management/index.html
grep -o 'assets/index-[^"]*\.js' /var/www/item-management/index.html

# 6. Config presence (never print secrets)
sudo awk -F= '/^(SPRING_PROFILES_ACTIVE|EMBEDDED_POSTGRES|SERVER_PORT|DB_URL|DB_USERNAME|CORS_ORIGINS|SEED_DEMO)=/{print}' /etc/item-management.env
sudo awk -F= '/^JWT_SECRET=/{print "JWT_SECRET length:", length($2)}' /etc/item-management.env

# 7. Current service state
sudo systemctl status item-management --no-pager
sudo journalctl -u item-management --since '1 day ago' --no-pager | grep -Ei 'flyway|error|exception' | tail -n 40
```

Interpretation:

- If `flyway_schema_history` already shows V6 and/or V7, **stop** and reconcile: the migration may
  have been applied out-of-band, and the jar must match.
- If the printing tables exist without a V6 history row, the database was modified manually. Do not
  let Flyway guess; investigate before deploying.
- If `items.barcode` exists but is nullable or lacks the unique index, V7 is partially applied. Do not
  re-run V7 blindly; treat it as an incident.

---

## 13. Deployment order (summary)

Full procedure and checks: `DEPLOYMENT_CHECKLIST.md` and `ADMIN_DEPLOYMENT_GUIDE.md` § 6.

1. Owner approval + change window.
2. Read-only live inspection (Section 12) and record results.
3. Fresh verified backup of `item_management_prod`; keep the old one untouched.
4. Back up the current jar and frontend.
5. Upload and hash-verify the new jar and frontend archive.
6. Stop service → replace jar → start service.
7. Confirm `Successfully applied 3 migrations` and `Started ItemManagementApplication`.
8. Verify V1–V8 history, printing tables, barcode column/index, permissions, and the V8 auth
   columns/`revoked_tokens` table.
9. Deploy frontend; verify `index.html` and HTTPS.
10. Smoke test login, items (create + barcode + label), POS scan, a sale, printing, platform accounts.
11. Monitor logs for 30–60 minutes; close the change window.

Rollback: see `ROLLBACK_GUIDE.md`. Do not restore the database without written approval.

---

## 14. Open questions for the owner

1. What is the SHA-256 of the currently installed jar? (Determines the true baseline.)
2. Has V6 been applied manually or on a staging copy?
3. Is the production database still on PostgreSQL 18.6, and is `ims_user` the only application role?
4. Are there custom Nginx rules, WAF rules, or reverse-proxy timeouts that affect 12 MB uploads?
5. Is a maintenance window acceptable, and who signs off on a destructive restore if one is needed?
6. Are default/seed accounts still present in production? (Security follow-up — see
   `KNOWN_ISSUES_AND_RECOMMENDATIONS.md`.)
