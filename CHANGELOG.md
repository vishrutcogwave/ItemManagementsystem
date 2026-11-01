# CHANGELOG

All notable changes to the Item Management System. Releases are ordered newest first.
Schema versions refer to `backend/src/main/resources/db/migration`.

---

## 2026-11-01 — Authentication hardening, barcode and platform accounts (schema V8)

> This entry documents the uncommitted hardening work on top of the V6 entry below. Production status
> remains **V1–V5 applied; V6, V7 and V8 pending**. Nothing has been deployed. Full evidence:
> `PROJECT_DOCUMENTATION/KNOWN_ISSUES_AND_RECOMMENDATIONS.md` and
> `PROJECT_DOCUMENTATION/TEST_EXECUTION_REPORT.md`.

### Security (K-01–K-12, K-27)

- Removed the hard-coded platform administrator seeder. `PlatformAdminProvisioner` creates the first
  administrator only with `PROVISION_PLATFORM_ADMIN=true` plus explicit username/password/email, and
  refuses to run when an administrator exists. Demo data remains profile- and `SEED_DEMO`-gated.
- Self-service `POST /api/auth/change-password` for platform and store users (current-password check,
  shared strength policy, BCrypt, `token_version` increment, other sessions invalidated). Forced
  change after administrator reset or provisioning (`403 PASSWORD_CHANGE_REQUIRED`).
- Cookie sessions: `HttpOnly`, `SameSite=Strict`, configurable `Secure`; the SPA no longer persists a
  token and migrates old `localStorage` state. Unsafe cookie requests require an allowlisted
  `Origin`/`Referer` (`403 CSRF_ORIGIN_REJECTED`).
- Persistent JWT revocation in `revoked_tokens` plus per-user `token_version`; scheduled purge of
  expired rows only. Login lockout with HTTP 429 (`LOGIN_*` settings).
- Platform audit viewer with filters and CSV export (platform-only). Reset responses are `no-store`,
  require `confirm=true`, force a change and never audit the secret.
- Public `/api/health` (database probe) and `/api/version` (build/Git commit, no secrets).
- Shared password policy used by registration, provisioning, reset and change.

### Database (V8 — additive)

- `users.must_change_password BOOLEAN NOT NULL DEFAULT FALSE`.
- `users.token_version INTEGER NOT NULL DEFAULT 0`.
- `revoked_tokens(jti PK, expires_at, revoked_at)` and `ix_revoked_tokens_expires`.
- No existing row rewritten, no column/table dropped. See `PROJECT_DOCUMENTATION/ROLLBACK_GUIDE.md` §1.1.

### Barcode and reliability (K-13–K-25)

- Camera scan falls back to `@zxing/browser` when `BarcodeDetector` is unavailable.
- Configurable internal EAN-13 prefix (default `20`), `INTERNAL`/`GS1` classification and UI badge;
  label calibration page and documented 50×30 mm settings.
- Global pagination clamp (`page >= 0`, `size 1..100`); lint reduced to **0 warnings / 0 errors**.
- Documented retention (audit 24 months, print jobs 12 months, manual approval before deletion),
  backup/restore verification, monitoring via `/api/health`, and the V7 rollback compatibility scripts.

### Verification

- Backend: **68 tests passed**, 0 failed (AuthSecurity 14, SystemInvariant 22, ItemImport 13,
  Printing 8, Barcode 6, PlatformAccount 5).
- Frontend: `tsc -b` clean, oxlint 0/0, production build successful, `npm audit` 0 vulnerabilities.
- Hardware (physical printer, USB scanner, phone camera, Android device) and live-server verification
  remain pending; see the K-01–K-27 report.

### Still open

- Rotate the existing production platform administrator password after deployment (K-01/K-02).
- Purchase returns/credit notes (K-16, design only), MFA (K-26, deferred), production verification
  (K-12) and hardware checks.

---

## 2026-11-01 — Silent Thermal Printing (schema V6)

Application version: backend `1.0.0`, Windows bridge `1.0.0`, Android bridge `1.0.0` (versionCode 1).
Production status at the time of writing: **V1–V5 applied, V6 pending**. This release is prepared and
verified locally; production deployment awaits owner approval.

### Added — database (V6)

- `printer_configs` — branch LAN/thermal printer name, IPv4/hostname, port (default 9100), paper width
  (58/80 mm), purpose (receipt/purchase/report/all), status, last test result. Unique name per branch;
  composite branch/store foreign key; index on `(store_id, branch_id, status)`.
- `print_pairing_codes` — SHA-256 hash of a one-time 8-character code, 10-minute expiry, `used_at`;
  index on `(store_id, expires_at)`.
- `print_devices` — paired Android bridges, platform, SHA-256 token hash, status, paired-by snapshot,
  last-seen; unique token; index on `(store_id, branch_id, status)`.
- `print_jobs` — job key (`AUTO:SALE_RECEIPT:<channel>:<branchId>:<saleId>` or `MANUAL:<uuid>`),
  document snapshot, printer snapshot, channel, trigger, status, attempts, error, printed time;
  unique `(store_id, job_key)`; history indexes.
- Permissions `PRINTER_VIEW` and `PRINTER_MANAGE`.
- Grants: `PRINTER_VIEW` to all existing store-scoped roles; `PRINTER_MANAGE` to existing
  `STORE_OWNER` / `STORE_ADMIN`.
- No existing table, column, row or permission was altered or removed.

### Added — backend

- `PrinterController` — printer CRUD, status, test result, pairing-code generation, paired-device list
  and revocation.
- `PrintDeviceController` — bridge pairing and configuration pull, authenticated by one-time code and
  opaque device token instead of JWT.
- `PrintJobController` — create job, report `PRINTED`/`FAILED`, paginated history, detail, manual reprint.
- Entities: `PrinterConfig`, `PrintPairingCode`, `PrintDevice`, `PrintJob`; enums `PaperWidth`,
  `PrinterPurpose`, `PrintDevicePlatform`, `PrintDeviceStatus`, `PrintDocumentType`, `PrintJobChannel`,
  `PrintJobStatus`, `PrintJobTrigger`.
- Repositories: `PrinterConfigRepository`, `PrintPairingCodeRepository`, `PrintDeviceRepository`,
  `PrintJobRepository`.
- Services: `PrinterService`, `PrintDeviceService`, `PrintJobService`, `PrintJobWriter` (separate
  transaction so a duplicate-key race returns the winning job), token utility `PrintingTokens`.
- Store provisioning now grants printing permissions to new stores (owner/admin manage, branch manager
  view, branch staff none).
- Audit entries for pairing-code generation, device pairing and revocation.

### Added — backend API (16 endpoints)

| Method | Path | Permission / auth |
|---|---|---|
| GET | `/api/printing/printers?branchId=` | `PRINTER_VIEW` |
| POST | `/api/printing/printers` | `PRINTER_MANAGE` |
| PUT | `/api/printing/printers/{id}` | `PRINTER_MANAGE` |
| PUT | `/api/printing/printers/{id}/status` | `PRINTER_MANAGE` |
| PUT | `/api/printing/printers/{id}/test-result` | `PRINTER_MANAGE` |
| DELETE | `/api/printing/printers/{id}` | `PRINTER_MANAGE` |
| POST | `/api/printing/printers/pairing-code?branchId=` | `PRINTER_MANAGE` |
| GET | `/api/printing/printers/devices?branchId=` | `PRINTER_MANAGE` |
| DELETE | `/api/printing/printers/devices/{id}` | `PRINTER_MANAGE` |
| POST | `/api/printing/devices/pair` | pairing code (no JWT) |
| GET | `/api/printing/device/config` | `X-Device-Token` (no JWT) |
| POST | `/api/printing/jobs` | `SALES_MANAGE` |
| PUT | `/api/printing/jobs/{id}/status` | `SALES_MANAGE` |
| GET | `/api/printing/jobs` | `SALES_VIEW` |
| GET | `/api/printing/jobs/{id}` | `SALES_VIEW` |
| POST | `/api/printing/jobs/{id}/reprint` | `SALES_MANAGE` |

### Added — frontend

- **Settings → Printers** tab: branch selector, store printer CRUD (name, IP/hostname, port, paper
  width, purpose, status), test-connection result, Windows default-printer test, bridge key/port card,
  pairing-code generation, paired-bridge list and revoke.
- **Print History** page (`/print-history`) with status filter, pagination, branch/printer/bridge
  columns and reprint action.
- `src/services/printerService.ts` — bridge discovery (port 17777/17778), bridge key header, server
  job creation before printing, automatic/manual print flows, duplicate handling, reprint, history.
- `src/services/thermalReceipt.ts` — 32/48-column ESC/POS receipt text, ASCII-safe currency.
- Automatic receipt printing after POS and Sales Entry completion.
- Silent receipt buttons in POS completion panel, Sales Entry and Sales History; no browser print dialog.
- Navigation entry **Print History**; Settings route now allows `PRINTER_VIEW` users.
- Printing types added to `src/types.ts`.

### Added — Windows print bridge (`bridges/windows`)

- `ItemManagementPrintBridge.exe` — .NET 10 WinForms tray app with Kestrel loopback server on
  `127.0.0.1:17777`.
- `GET /api/bridge/status`, `POST /api/bridge/test`, `POST /api/bridge/print`.
- `X-Bridge-Key` authentication (constant-time compare) and website-origin allowlist with CORS and
  Private Network Access headers.
- Silent raw ESC/POS printing to the Windows default installed printer via the spooler, with GDI
  fallback; 30-minute duplicate cache.
- Per-user install (`%LOCALAPPDATA%\ItemManagementPrintBridge`), Start with Windows (HKCU Run),
  Start Menu shortcut, tray status/logs, editable settings, key display/copy.
- `ItemManagementPrintBridgeSetup.exe` IExpress installer.

### Added — Android print bridge (`bridges/android`)

- Package `com.itemmanagement.printbridge`, version 1.0.0, minSdk 24, targetSdk 34.
- Foreground service, raw loopback HTTP server on `127.0.0.1:17778`, no AndroidX dependency.
- Pairing with a one-time code; device token stored locally; printer list synced every 5 minutes and
  refreshable.
- Raw ESC/POS TCP printing to a branch-configured Wi-Fi/LAN printer on port 9100 (58/80 mm), with
  alignment, emphasis, double-size and cut.
- Bridge-key authentication, optional allowed-website origin, 30-minute duplicate suppression.
- Boot receiver, start/stop service, battery-optimisation guidance.
- Release-signed APK artifact.

### Changed

- POS, Sales Entry and Sales History receipt actions now use the silent print service instead of
  browser/PDF printing.
- `SecurityConfig` permits `/api/printing/devices/pair` and `/api/printing/device/config` (authorised by
  pairing code and device token respectively). All other printing endpoints require JWT plus the
  permissions above.
- `StoreProvisioningService` grants the new permissions at store creation.
- CORS remains configured for `/api/**` from `CORS_ORIGINS`; production is same-origin and needs no
  change.
- `SystemInvariantIntegrationTest` timezone handling made deterministic for UTC test execution.

### Fixed

- Automatic print requests are idempotent under concurrency: unique `(store_id, job_key)` plus the
  separate insert transaction ensures exactly one job, even for two simultaneous requests.
- Cancelled sales and foreign-store sales cannot be printed; foreign-store printer ids return 404.
- Pairing codes are single-use, expire after 10 minutes and are stored only as hashes; revoked devices
  immediately lose configuration access.
- Android bridge rejects jobs whose printer does not match its synced branch configuration.
- Windows jobs in Print History now show "Windows default printer / Silent local print" instead of
  borrowing the Android printer name/IP.
- Bridge-unavailable and print-failure cases show a clear inline/toast error and record a `FAILED`
  job; no browser print dialog is opened.
- Duplicate print suppression verified in both bridges and in the frontend.

### Tests and builds (executed locally, 2026-11-01)

- Backend `clean test`: **42 tests, 0 failures** — `ItemImportIntegrationTest` 13,
  `PrintingIntegrationTest` 8 (CRUD/scoping, test results, single-use pairing/revocation, payload
  validation, AUTO idempotency, cancelled/foreign-store rejection, concurrency, permissions),
  `SystemInvariantIntegrationTest` 21. `BUILD SUCCESSFUL`.
- Backend `clean bootJar`: `backend/build/libs/item-management-backend-1.0.0.jar`, 150,400,815 bytes;
  contains `BOOT-INF/classes/db/migration/V6__printing.sql`.
- Frontend `npm run lint`: 0 errors, 10 pre-existing warnings. `npm run build`: success, `dist`
  regenerated (~2.5 MB).
- Printing verified live with a Windows PC (`POS-80C` default printer) and an Android API 33 emulator
  against a mock ESC/POS TCP printer on port 9100, including automatic and manual jobs, duplicate
  suppression, bridge-off failure and retry, pairing, revocation, origin/key rejection and the 10
  requested viewport sizes.
- **Not verified:** physical paper appearance/legibility on actual thermal hardware. Windows testing
  confirms spooler acceptance; Android testing confirms exact bytes captured by the mock printer.

### Deployment notes

- Apply V6 by starting the new backend; Flyway runs it transactionally. Do not run destructive SQL.
- No new environment variables are required. Confirm Java 17, PostgreSQL 18.6, `postgres` profile,
  `DB_URL`/`DB_USERNAME`, existing `JWT_SECRET`, and `SEED_DEMO=false`.
- Create and verify a fresh production backup before deployment; keep the existing pre-V6 backup.
- Rebuilding/updating the Windows bridge or Android APK is **not** required for this release.
- Existing bridge keys and Android device pairings remain valid. Add
  `https://hiremathwebstudio.in` to the Windows bridge allowlist and to the Android app's allowed
  website. Re-pair only if a device was revoked or its app data was cleared.
- See `DEPLOYMENT_RUNBOOK.md` for exact PowerShell/SSH commands and rollback.
- Physical printer hardware must be tested on site after deployment.

---

## 2026-10-31 — Bulk Item Import (schema V5)

- Excel import batches and row-level preview/result history (`item_import_batches`,
  `item_import_rows`).
- Automatic category creation, item code generation, duplicate detection, existing-item reuse.
- Opening stock recorded as a single `OPENING_STOCK` ledger transaction per branch/item (partial unique
  index prevents double imports).
- Item `description` column and import template columns.
- Import preview, confirm, template download, history and detail APIs and UI.
- File-level duplicate confirmation guard for completed batches.

---

## 2026-10-31 — Subscriptions and Branch Limits (schema V4)

- Store subscription start/end dates and `max_branches` with server-side enforcement.
- `subscription_change_history` audit table.
- `platform_settings` singleton for the expired-subscription contact.
- Platform console and expired-subscription UI.
- Branch creation limit enforced in the backend, including concurrency handling.

---

## 2026-10-30 — Single Item Rate (schema V3)

- `items.rate` added and backfilled from the default selling price.
- `default_purchase_price` and `default_selling_price` removed from `items`.
- POS uses the item rate; purchase/sales entries capture their negotiated rates per line.

---

## 2026-10-30 — Tax Master and GST (schema V2)

- Store tax slabs (`tax_master`) with CGST/SGST/IGST split and interstate place-of-supply.
- Standard slabs seeded for every existing store.
- Item tax link; immutable tax snapshots on purchase and sale lines; header GST summaries.
- GST report and tax master UI.
- `TAX_VIEW` / `TAX_MANAGE` permissions.

---

## 2026-10-30 — Initial Release (schema V1)

- Multi-tenant store/branch model on a single PostgreSQL database.
- JWT authentication, BCrypt passwords, roles, permissions, branch assignment, audit log.
- Item, category, supplier, customer and branch inventory masters.
- Purchase, sale, stock adjustment and inter-branch transfer workflows with an immutable stock ledger.
- No-negative-stock and concurrency guarantees; cancellation/reversal instead of deletion.
- Dashboard, reports, CSV/PDF/Excel exports.
- React 19 + TypeScript + Vite frontend with role-aware navigation and responsive layout.
- Flyway baseline schema `V1__init.sql` with 26 permissions and the platform administrator role.
