# Deployment Checklist

Release: Authentication hardening (V8) + Item barcode (V7) + printing (V6) + Platform Admin store
accounts + audit viewer + health/version endpoints + printing/mobile fixes.

> **Approval gate.** This is a checklist, not an authorisation. No production action was taken. Do
> not execute Sections 4–8 until the owner has approved the change window and a fresh database
> backup has been created and verified. Never restore or drop the production database without
> separate written approval.

---

## 0. Release identity

Fill this in before starting. Do not proceed with blanks.

| Field | Value |
|---|---|
| Release commit / tag | ______________________ (repository is currently dirty — commit first) |
| Backend jar SHA-256 | ______________________ (current local build: `17f9787f…7fbf8`) |
| Frontend archive SHA-256 | ______________________ |
| Migrations included | V6 `printing`, V7 `item barcode`, V8 `auth security` |
| Approver | ______________________ |
| Change window (local time) | ______________________ |
| Deployer | ______________________ |
| Rollback owner | ______________________ |

Expected migration delta: **3 additive migrations**. No manual SQL. New optional environment values
(`AUTH_COOKIE_SECURE=true`, exact `CORS_ORIGINS`; optional `LOGIN_*`) should be added to the existing
env file without removing or overwriting other values. No Nginx change is strictly required; no
bridge rebuild.

---

## 1. Go / no-go prerequisites

- [ ] Owner approval recorded in writing (ticket, email, or chat screenshot).
- [ ] Release committed and tagged; build produced from that commit.
- [ ] Backend tests: 68 passed, 0 failed (6 integration classes).
- [ ] Frontend: `tsc -b` clean, `oxlint` 0 warnings / 0 errors, `npm run build` succeeded, `npm audit` 0 vulnerabilities.
- [ ] Local UI verification completed (items, barcode label, POS scan, sale, printing, platform accounts, password change/forced change, audit viewer + CSV, responsive widths).
- [ ] Rollback plan reviewed (`ROLLBACK_GUIDE.md`), including the V7/jar rollback compatibility scripts and the V8 additive rollback note.
- [ ] `AUTH_COOKIE_SECURE=true` and exact `CORS_ORIGINS` prepared for the env file (do not overwrite existing values blindly).
- [ ] Plan confirmed to rotate the existing platform administrator password after deploy (§8.5).
- [ ] Change window scheduled at low traffic (avoid imports and month-end).
- [ ] On-call contact and customer communication prepared.
- [ ] At least 2× the expected database size free on `/` and `/tmp`.

**Stop if any box is unchecked.**

---

## 2. Pre-flight inspection (read-only)

Run and record output. Never print secrets.

```bash
df -h / /tmp
sudo systemctl status item-management --no-pager
sudo systemctl cat item-management
ss -ltnp | grep ':8080' || echo 'port 8080 not listening'
java -version
sudo nginx -t
ls -l /opt/item-management/backend/item-management.jar
sha256sum /opt/item-management/backend/item-management.jar
ls -l /var/www/item-management/index.html
sudo awk -F= '/^(SPRING_PROFILES_ACTIVE|EMBEDDED_POSTGRES|SERVER_PORT|DB_URL|DB_USERNAME|CORS_ORIGINS|SEED_DEMO|AUTH_COOKIE_SECURE|AUTH_COOKIE_SAME_SITE|PROVISION_PLATFORM_ADMIN)=/{print}' /etc/item-management.env
sudo awk -F= '/^JWT_SECRET=/{print "JWT_SECRET length:", length($2)}' /etc/item-management.env
```

Database state:

```bash
sudo -u postgres psql -d item_management_prod -c \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT table_name FROM information_schema.tables
   WHERE table_schema='public'
     AND table_name IN ('printer_configs','print_pairing_codes','print_devices','print_jobs')
   ORDER BY table_name;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT column_name, is_nullable FROM information_schema.columns
   WHERE table_schema='public' AND table_name='items'
     AND column_name IN ('barcode','barcode_format');"

sudo -u postgres psql -d item_management_prod -c "SELECT COUNT(*) AS items FROM items;"
```

- [ ] Schema is exactly V1–V5 with all `success = t`.
- [ ] No printing tables, barcode column or `revoked_tokens` table exist (otherwise stop and reconcile).
- [ ] Service is active, port 8080 listening.
- [ ] Config matches the expected production values.
- [ ] Output copied into the release record.

**Go / no-go:** if the database already shows V6/V7/V8 or partial objects, **NO-GO** until reconciled.

---

## 3. Backup (mandatory)

### 3.1 Database

```bash
TS=$(date +%Y%m%d_%H%M%S)
DUMP=/tmp/item_management_pre_deploy_${TS}.dump
sudo -u postgres pg_dump -F c -d item_management_prod -f "$DUMP"
test -s "$DUMP" || { echo 'BACKUP EMPTY - STOP'; exit 1; }
sudo -u postgres pg_restore --list "$DUMP" | wc -l
sudo cp -a "$DUMP" "/root/item_management_pre_deploy_${TS}.dump"
sudo chmod 600 "/root/item_management_pre_deploy_${TS}.dump"
sudo sha256sum "$DUMP" "/root/item_management_pre_deploy_${TS}.dump"
echo "BACKUP: /root/item_management_pre_deploy_${TS}.dump"
```

- [ ] Dump is non-empty.
- [ ] `pg_restore --list` returns a non-empty TOC.
- [ ] `/tmp` and `/root` hashes match.
- [ ] Older backups were **not** overwritten.
- [ ] Backup path recorded: ______________________

Optional integrity check into a scratch database:

```bash
sudo -u postgres dropdb --if-exists ims_restore_check
sudo -u postgres createdb ims_restore_check
sudo -u postgres pg_restore -d ims_restore_check "/root/item_management_pre_deploy_${TS}.dump"
sudo -u postgres psql -d ims_restore_check -c "SELECT COUNT(*) FROM items;"
sudo -u postgres dropdb ims_restore_check
```

### 3.2 Application files

```bash
TS=$(date +%Y%m%d_%H%M%S)
sudo cp -a /opt/item-management/backend/item-management.jar \
  "/opt/item-management/backend/item-management.jar.bak-${TS}"
sudo tar -czf "/root/item-management-frontend-${TS}.tgz" -C /var/www/item-management .
ls -l "/opt/item-management/backend/item-management.jar.bak-${TS}" "/root/item-management-frontend-${TS}.tgz"
echo "ROLLBACK: jar.bak-${TS}; item-management-frontend-${TS}.tgz"
```

- [ ] Jar backup exists.
- [ ] Frontend archive exists.
- [ ] Rollback file names recorded: ______________________

---

## 4. Upload and verify artifacts

Build host:

```powershell
Get-FileHash .\backend\build\libs\item-management-backend-1.0.0.jar -Algorithm SHA256
Get-FileHash .\dist-upload.zip -Algorithm SHA256
ssh root@SERVER "rm -f /tmp/item-management.jar /tmp/dist-upload.zip; rm -rf /tmp/item-management-dist; mkdir -p /tmp/item-management-dist"
scp .\backend\build\libs\item-management-backend-1.0.0.jar root@SERVER:/tmp/item-management.jar
scp .\dist-upload.zip root@SERVER:/tmp/dist-upload.zip
```

Server:

```bash
sha256sum /tmp/item-management.jar
sudo rm -rf /tmp/item-management-dist && sudo mkdir -p /tmp/item-management-dist
sudo unzip -q /tmp/dist-upload.zip -d /tmp/item-management-dist
test -f /tmp/item-management-dist/index.html || { echo 'FRONTEND ARCHIVE INVALID - STOP'; exit 1; }
unzip -l /tmp/item-management.jar | grep -E 'V[678]__' || { echo 'MIGRATIONS MISSING - STOP'; exit 1; }
```

- [ ] Jar hash equals the build-host hash.
- [ ] Frontend archive contains `index.html` and `assets/`.
- [ ] Jar contains `V6__printing.sql`, `V7__item_barcode.sql` and `V8__auth_security.sql`.

---

## 5. Deploy backend

```bash
sudo systemctl stop item-management
sudo systemctl is-active item-management || true
sudo install -o ims -g ims -m 0644 /tmp/item-management.jar \
  /opt/item-management/backend/item-management.jar
sudo systemctl start item-management
sleep 8
sudo systemctl status item-management --no-pager
ss -ltnp | grep ':8080' || echo 'port 8080 not listening'
sudo journalctl -u item-management --since '10 minutes ago' --no-pager | \
  grep -Ei 'flyway|migrat|schema|started|error|exception' | tail -n 80
```

- [ ] Service is `active (running)`.
- [ ] Port 8080 is listening.
- [ ] Log shows V6 applied, V7 applied, V8 applied, `Successfully applied 3 migrations`.
- [ ] Log shows `Started ItemManagementApplication`.
- [ ] No `checksum mismatch`, no failed migration, no schema-validation error.

**If startup fails:** do not restart repeatedly, do not edit the database. Go to Section 9 and
`ROLLBACK_GUIDE.md`.

---

## 6. Deploy frontend

```bash
sudo rsync -a --delete --exclude='.well-known/' /tmp/item-management-dist/ /var/www/item-management/
sudo find /var/www/item-management -type d -exec chmod 755 {} +
sudo find /var/www/item-management -type f -exec chmod 644 {} +
sudo chown -R www-data:www-data /var/www/item-management
sudo nginx -t
ls -l /var/www/item-management/index.html
```

- [ ] `index.html` replaced (check mtime).
- [ ] Hashed assets present under `assets/`.
- [ ] `nginx -t` succeeds.
- [ ] Nginx site file was **not** changed; no reload performed unless it was.

---

## 7. Database and schema verification

```bash
sudo -u postgres psql -d item_management_prod -c \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT table_name FROM information_schema.tables
   WHERE table_schema='public'
     AND table_name IN ('printer_configs','print_pairing_codes','print_devices','print_jobs')
   ORDER BY table_name;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT column_name, data_type, is_nullable, column_default
   FROM information_schema.columns
   WHERE table_schema='public' AND table_name='items'
     AND column_name IN ('barcode','barcode_format') ORDER BY column_name;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT COUNT(*) AS missing_barcodes FROM items WHERE barcode IS NULL OR barcode = '';"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT COUNT(*) AS duplicate_groups FROM (
     SELECT store_id, barcode FROM items GROUP BY store_id, barcode HAVING COUNT(*) > 1
   ) d;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND indexname='uq_items_store_barcode';"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT code FROM permissions WHERE code IN ('PRINTER_VIEW','PRINTER_MANAGE') ORDER BY code;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT COUNT(*) AS cross_store_jobs FROM print_jobs j
   JOIN branches b ON b.id = j.branch_id WHERE b.store_id <> j.store_id;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT COUNT(*) AS cross_store_printers FROM printer_configs p
   JOIN branches b ON b.id = p.branch_id WHERE b.store_id <> p.store_id;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT column_name, is_nullable, column_default FROM information_schema.columns
   WHERE table_schema='public' AND table_name='users'
     AND column_name IN ('must_change_password','token_version') ORDER BY column_name;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT COUNT(*) AS revoked_tokens_table FROM information_schema.tables
   WHERE table_schema='public' AND table_name='revoked_tokens';"
```

- [ ] V1–V8 all present and successful.
- [ ] Four printing tables exist.
- [ ] `barcode` is `NO` (not nullable); `barcode_format` defaults to `CODE128`.
- [ ] `missing_barcodes = 0`.
- [ ] `duplicate_groups = 0`.
- [ ] Unique index exists.
- [ ] Both printer permissions exist.
- [ ] Cross-store counts are 0.
- [ ] `must_change_password` is NOT NULL default false; `token_version` is NOT NULL default 0.
- [ ] `revoked_tokens` table exists.

---

## 8. Functional smoke tests

### 8.1 API

- [ ] `POST /api/auth/login` returns a token and sets an `HttpOnly` `ims_auth` cookie.
- [ ] `GET /api/health` returns 200 `{"status":"UP"}`; `GET /api/version` shows the deployed commit.
- [ ] `GET /api/items?page=0&size=5` returns 200 and items include `barcode` and `barcodeSource`.
- [ ] `GET /api/items/barcode/{known-barcode}` returns the item.
- [ ] `POST /api/items/{id}/barcode` with `{"format":"EAN13"}` returns a valid internal EAN-13.
- [ ] `GET /api/printing/printers?branchId=…` returns 200 for an authorised role.
- [ ] `GET /api/printing/jobs?page=0&size=5` returns 200.
- [ ] `GET /api/platform/accounts` returns 200 only for a platform admin; store users get 403.
- [ ] `GET /api/platform/audit-logs?page=0&size=5` and `/export` return data only for a platform admin.
- [ ] Platform reset without `confirm` returns 400.
- [ ] Cross-store barcode lookup returns 404/403, never another store’s item.
- [ ] An unsafe cookie call with a foreign `Origin` returns `403 CSRF_ORIGIN_REJECTED`.

### 8.2 UI

- [ ] Hard refresh; sign in with a store owner account.
- [ ] **Items**: barcode column visible; filter/search by barcode works.
- [ ] **Items → label**: preview shows name, item code, barcode symbol, selling price; download PNG and print work.
- [ ] **POS**: USB keyboard-wedge scan adds the correct item; camera scan button present; ZXing fallback message appears in a browser without `BarcodeDetector`.
- [ ] **Sales entry / Purchases**: scan adds the correct line.
- [ ] Complete one small sale; stock decreases; receipt prints.
- [ ] **Print History** shows the job (WINDOWS/ANDROID, AUTO/MANUAL, PRINTED).
- [ ] **Settings → Printers** opens without overlapping buttons at desktop and phone widths.
- [ ] **Settings → Profile → Change password** works for a store user and a platform admin (test with a disposable account, not a real customer).
- [ ] Platform sign-in (`storeCode=PLATFORM`) → **Store Accounts**: search by store, open account, see status; no password is displayed anywhere.
- [ ] **Platform → Audit log**: filters load and CSV export downloads.
- [ ] `/sales` at 320 px has no horizontal scroll.

### 8.3 Printing

- [ ] Windows bridge: status OK, default printer shown, test print, automatic sale print.
- [ ] Android bridge: paired device visible, test print, automatic sale print.
- [ ] Bridge stopped or origin missing → clear UI error, job recorded `FAILED`, no browser print dialog.

### 8.4 Regression spot checks

- [ ] Purchase entry and inventory update.
- [ ] Stock transfer between branches.
- [ ] Inventory adjustment.
- [ ] Excel import preview + confirm (an imported item gets a barcode).
- [ ] GST/tax report and sales report.
- [ ] Subscription-expired store still receives `403 SUBSCRIPTION_EXPIRED`.

### 8.5 Credential rotation (required)

- [ ] Sign in with the existing platform administrator (`PLATFORM`) and change its password via Settings → Profile.
- [ ] Confirm the old password no longer authenticates and the new one does.
- [ ] Confirm other sessions were invalidated.
- [ ] Ensure `PROVISION_PLATFORM_ADMIN=false` and no `PLATFORM_ADMIN_*` secrets remain in the env file.

---

## 9. Failure handling

| Failure | Immediate action |
|---|---|
| Backend will not start, Flyway error | Stop. Capture `journalctl` output. Do **not** edit the DB. Follow `ROLLBACK_GUIDE.md`. |
| Backend starts but API 500s | Capture logs; check schema verification queries; consider forward fix or jar rollback (item creation will be broken under V7 — see warning). |
| Frontend broken | Restore the frontend archive from Section 3.2; keep the backend. |
| Printing regressed | Verify bridge/origin; no server change is normally needed. |
| Data integrity check fails | Stop serving writes; escalate to owner; do not delete rows. |

**Rollback warning:** after V7, replacing the jar with the previous release allows reads but breaks
item creation and Excel import (NOT NULL `items.barcode`). Use
`docs/sql/07_rollback_v7_compatibility.sql` for a temporary jar rollback, then run
`docs/sql/08_remove_v7_rollback_compatibility.sql` after restoring the new jar. This shim is tested
locally (`v7RollbackShimAllowsItemInsertWithoutBarcodeAndChangesNoExistingRows`). V8 is additive; an
old jar ignores `must_change_password`, `token_version` and `revoked_tokens`, so rolling back the jar
does not corrupt data, but the old jar will not enforce forced password changes or database-backed
revocation. A full rollback still requires restoring the pre-deployment dump and owner approval.

---

## 10. Post-deployment monitoring

Monitor for 30–60 minutes, then daily for one week.

- [ ] `systemctl status item-management` stays active.
- [ ] `journalctl -u item-management` has no repeating errors/exceptions.
- [ ] `/api/health` returns `UP` from the external monitor.
- [ ] Nginx error log has no new 5xx pattern.
- [ ] Login and dashboard work for at least one store user and one platform admin.
- [ ] Forced password change works for a freshly reset account.
- [ ] One sale and one purchase complete successfully.
- [ ] Print jobs move to `PRINTED` (no stuck `PENDING`).
- [ ] No `429` lockouts caused by client retry loops.
- [ ] No sudden growth in disk usage.
- [ ] Owner informed of completion with the release identity and backup path.

---

## 11. Close-out record

| Item | Value |
|---|---|
| Deployment started / finished | ______________________ |
| Migrations applied | V6, V7, V8 |
| Platform admin password rotated | YES / NO (must be YES) |
| Backend jar hash deployed | ______________________ |
| Frontend archive hash deployed | ______________________ |
| Database backup path | ______________________ |
| Rollback files | ______________________ |
| Smoke test result | PASS / FAIL |
| Incidents | ______________________ |
| Approver sign-off | ______________________ |

Keep this record with the backup hashes until the next release.

---

## 12. Explicitly out of scope

- No database drop, truncate, or manual row deletion.
- No production database recreation or restore without separate written approval.
- No change to `/etc/item-management.env` secrets (`JWT_SECRET`, `DB_PASSWORD`) or to bridge keys/device
  tokens. Only the documented additive settings (`AUTH_COOKIE_SECURE`, exact `CORS_ORIGINS`) may be
  appended with owner approval, after backing up the file.
- No Nginx rewrite or TLS re-issue unless separately approved.
- No bridge rebuild or re-signing.
- No deployment claim until Section 10 is complete and verified.
