# DEPLOYMENT_RUNBOOK.md

Production deployment runbook — Silent Thermal Printing release (Flyway **V6**).

> **Approval gate:** this runbook is documentation only. Nothing in it has been executed against
> production. Do not run Section B until the owner gives explicit approval and a fresh database
> backup has been created and verified.

| Item | Value |
|---|---|
| Domain | `hiremathwebstudio.in` |
| Server | Hostinger Ubuntu VPS `201.18.205.217` |
| Backend service | `item-management` |
| Backend port | `8080` |
| Backend jar | `/opt/item-management/backend/item-management.jar` |
| Backend env file | `/etc/item-management.env` |
| Frontend root | `/var/www/item-management` |
| Nginx site | `/etc/nginx/sites-available/item-management` |
| Database | `item_management_prod` |
| Database user | `ims_user` |
| Database version | PostgreSQL 18.6 |
| Java | 17 |
| Current schema | V1–V5 successful, **V6 pending** |
| New migrations | `V6__printing.sql` only |
| Existing verified backup | `/root/item_management_backup_before_v6.dump` (readable copy in `/tmp`) |

Artifacts produced by this release:

| Artifact | Path | Size | SHA-256 |
|---|---|---|---|
| Backend jar | `backend/build/libs/item-management-backend-1.0.0.jar` | 150,400,815 bytes | `192daa4ddf8dc08c3502ef98ecedfb14e37ad8ef6501429e48f066dcec779a59` |
| Frontend bundle | `dist/` | ~2.5 MB | rebuild-dependent; hash the archive you upload |
| Windows installer | `bridges/windows/dist/ItemManagementPrintBridgeSetup.exe` | 54,595,584 bytes | `3f42d910cd693c27a87373d2ef7df0b10288091a0783600f546d65b6e593695e` |
| Windows portable exe | `bridges/windows/dist/ItemManagementPrintBridge.exe` | 61,119,356 bytes | `f00dbb380324aab49db8ff023cf3057932618eadc735244ab5fdcd14072f7f3b` |
| Android APK | `bridges/android/dist/ItemManagementPrintBridge.apk` | 57,137 bytes | `89c42470af1cba5a56dced6da52df89ef8542ade7a5fb3cebb39544f05feda37` |

The bridge artifacts are newer than every bridge source file; **no bridge rebuild or update is
required for this deployment**. Rebuild only if bridge source changes.

---

# A. Windows PowerShell commands (build host)

Run from `D:\vishrut\Clone\ItemManagementSystem`.

## A.1 Navigate to the project

```powershell
Set-Location 'D:\vishrut\Clone\ItemManagementSystem'
```

## A.2 Check toolchain

```powershell
java -version
node --version
npm --version
.\backend\gradlew.bat --version
```

Expected: Java 17, Node 24.x, Gradle 8.10.2. The Gradle wrapper is used; no local Gradle install is required.

## A.3 Backend tests

```powershell
Set-Location .\backend
.\gradlew.bat clean test --console=plain
Set-Location ..
```

Expected this release: **42 tests, 0 failures, 0 errors**
(`ItemImportIntegrationTest` 13, `PrintingIntegrationTest` 8, `SystemInvariantIntegrationTest` 21).
The suite starts the real application against a Flyway-migrated database with
`ddl-auto=validate`, so it also proves the V6 schema matches the JPA entities.

Stop the run if anything other than `BUILD SUCCESSFUL` appears.

## A.4 Backend production jar

```powershell
Set-Location .\backend
.\gradlew.bat clean bootJar --console=plain
Set-Location ..
```

Verify:

```powershell
Get-Item .\backend\build\libs\item-management-backend-1.0.0.jar |
  Select-Object FullName, Length, LastWriteTime
Get-FileHash .\backend\build\libs\item-management-backend-1.0.0.jar -Algorithm SHA256
```

Expected path: `D:\vishrut\Clone\ItemManagementSystem\backend\build\libs\item-management-backend-1.0.0.jar`
Expected length: `150400815`.
Confirm V6 is inside the jar:

```powershell
jar tf .\backend\build\libs\item-management-backend-1.0.0.jar |
  Select-String 'V6__printing.sql'
```

## A.5 Frontend lint and build

```powershell
npm run lint
npm run build
```

Expected this release: lint **0 errors, 10 warnings** (pre-existing React hook/compiler warnings in
files unrelated to printing); build succeeds and writes `D:\vishrut\Clone\ItemManagementSystem\dist`.

Inspect the output:

```powershell
Get-ChildItem .\dist -Recurse |
  Measure-Object -Property Length -Sum |
  Select-Object Count, Sum
Get-ChildItem .\dist | Select-Object Name, Length, LastWriteTime
```

The `dist` directory must contain `index.html` and `assets/`. Create the archive used for upload:

```powershell
if (Test-Path .\dist-upload.zip) { Remove-Item .\dist-upload.zip }
Compress-Archive -Path .\dist\* -DestinationPath .\dist-upload.zip -CompressionLevel Optimal
Get-FileHash .\dist-upload.zip -Algorithm SHA256
```

## A.6 Bridge builds (only if bridge source changed)

**This release: skip.** The checked-in artifacts already contain the current bridge source.
If a future change requires rebuilding:

Windows:

```powershell
Set-Location .\bridges\windows\installer
.\build-installer.ps1
Set-Location ..\..\..
```

Android:

```powershell
Set-Location .\bridges\android
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleRelease
Set-Location ..\..
```

Android release signing reads `bridges\android\keystore.properties`, which must reference the
existing release keystore. Never regenerate or overwrite the keystore for an update: Android only
installs an APK signed with the same key.

Verify bridge artifacts:

```powershell
Get-FileHash .\bridges\windows\dist\ItemManagementPrintBridgeSetup.exe -Algorithm SHA256
Get-FileHash .\bridges\android\dist\ItemManagementPrintBridge.apk -Algorithm SHA256
```

## A.7 Confirm the complete upload set

```powershell
$items = @(
  '.\backend\build\libs\item-management-backend-1.0.0.jar',
  '.\dist\index.html',
  '.\dist-upload.zip',
  '.\bridges\windows\dist\ItemManagementPrintBridgeSetup.exe',
  '.\bridges\android\dist\ItemManagementPrintBridge.apk'
)
$items | ForEach-Object {
  if (Test-Path $_) { Get-Item $_ | Select-Object FullName, Length } else { Write-Warning "MISSING: $_" }
}
```

## A.8 Upload backend jar and frontend bundle

Replace `root` if your SSH account is different.

```powershell
ssh root@201.18.205.217 "rm -f /tmp/item-management.jar; rm -rf /tmp/dist /tmp/item-management-dist /tmp/dist-upload.zip; mkdir -p /tmp/item-management-dist"
scp .\backend\build\libs\item-management-backend-1.0.0.jar root@201.18.205.217:/tmp/item-management.jar
scp .\dist-upload.zip root@201.18.205.217:/tmp/dist-upload.zip
```

## A.9 Upload bridge artifacts (only when distributing new builds)

This release does not require it. If you rebuild the bridges, host the files in a location the
store administrator can download from, for example:

```powershell
scp .\bridges\windows\dist\ItemManagementPrintBridgeSetup.exe root@201.18.205.217:/tmp/
scp .\bridges\android\dist\ItemManagementPrintBridge.apk root@201.18.205.217:/tmp/
```

Do not overwrite an installed bridge's `config.json`; it lives outside the install directory in
`%LOCALAPPDATA%\ItemManagementPrintBridge\`.

## A.10 Optional: add the production origin to an existing Windows bridge

The Windows bridge rejects browser calls from origins that are not in its allowlist. Add the
production origin **once per Windows PC** that prints silently. Close the tray app first.

```powershell
$cfg = Join-Path $env:LOCALAPPDATA 'ItemManagementPrintBridge\config.json'
if (Test-Path $cfg) { Copy-Item $cfg "$cfg.bak-$(Get-Date -Format yyyyMMdd-HHmmss)" }
$j = Get-Content $cfg -Raw | ConvertFrom-Json
$j.allowedOrigins = @($j.allowedOrigins + 'https://hiremathwebstudio.in') | Select-Object -Unique
$j | ConvertTo-Json -Depth 4 | Set-Content $cfg -Encoding UTF8
Start-Process (Join-Path $env:LOCALAPPDATA 'ItemManagementPrintBridge\ItemManagementPrintBridge.exe')
```

This preserves `Key`, `Port` (17777), `PrintMode` and `StartWithWindows`. Add
`https://www.hiremathwebstudio.in` too if that hostname is used. The bridge key is **not**
regenerated.

Android: open the bridge app, set **Allowed website** to `https://hiremathwebstudio.in`, save, and
keep port `17778`.

---

# B. Ubuntu VPS SSH commands

Connect from PowerShell or a terminal:

```powershell
ssh root@201.18.205.217
```

If you connect as a sudo-capable non-root user, prefix privileged commands with `sudo`; the
commands below already use `sudo` where needed.

## B.1 Preflight: disk, service and deployment state

```bash
df -h / /tmp
sudo du -sh /var/www/item-management /opt/item-management 2>/dev/null
sudo systemctl status item-management --no-pager
sudo systemctl cat item-management
ss -ltnp | grep ':8080' || echo 'port 8080 not listening'
java -version
```

Record the `ExecStart`, `User`, `Group` and `EnvironmentFile` lines from `systemctl cat`.
The jar must be readable by the service user. Do not edit the unit unless a path is wrong.

```bash
ls -l /opt/item-management/backend/item-management.jar
ls -l /etc/item-management.env
readlink -f /etc/nginx/sites-enabled/item-management
sudo nginx -t
```

## B.2 Confirm the existing verified backup still exists

Do **not** overwrite, move or modify these files:

```bash
sudo ls -l /root/item_management_backup_before_v6.dump /tmp/item_management_backup_before_v6.dump
sudo -u postgres pg_restore --list /tmp/item_management_backup_before_v6.dump | head -n 10
sudo -u postgres pg_restore --list /tmp/item_management_backup_before_v6.dump | wc -l
sudo sha256sum /root/item_management_backup_before_v6.dump /tmp/item_management_backup_before_v6.dump
```

If either file is missing or unreadable, **stop** and report it; do not start the deployment.

## B.3 Create and verify a fresh backup (required before any change)

PostgreSQL runs as `postgres`, so write the dump to `/tmp`, verify it there, then copy it to `/root`.

```bash
TS=$(date +%Y%m%d_%H%M%S)
DUMP=/tmp/item_management_pre_v6_${TS}.dump

sudo -u postgres pg_dump -F c -d item_management_prod -f "$DUMP"
ls -l "$DUMP"
test -s "$DUMP" || { echo 'BACKUP IS EMPTY - STOP'; exit 1; }

TOC_COUNT=$(sudo -u postgres pg_restore --list "$DUMP" | wc -l)
echo "TOC entries: $TOC_COUNT"
test "$TOC_COUNT" -gt 0 || { echo 'BACKUP IS UNREADABLE - STOP'; exit 1; }

sudo cp -a "$DUMP" "/root/item_management_pre_v6_${TS}.dump"
sudo chmod 600 "/root/item_management_pre_v6_${TS}.dump"
sudo sha256sum "$DUMP" "/root/item_management_pre_v6_${TS}.dump"
echo "Fresh backup: /root/item_management_pre_v6_${TS}.dump"
```

Both hashes must match. Keep the existing `item_management_backup_before_v6.dump` untouched.

Optional integrity check into a scratch database (does not touch production):

```bash
sudo -u postgres dropdb --if-exists ims_restore_check
sudo -u postgres createdb ims_restore_check
sudo -u postgres pg_restore -d ims_restore_check "$DUMP"
sudo -u postgres psql -d ims_restore_check -c 'SELECT COUNT(*) AS tables FROM information_schema.tables WHERE table_schema = $$public$$ AND table_type = $$BASE TABLE$$;'
sudo -u postgres dropdb ims_restore_check
```

## B.4 Confirm uploaded artifacts

```bash
ls -l /tmp/item-management.jar /tmp/dist-upload.zip
sha256sum /tmp/item-management.jar
```

The jar hash must match Section A.4 (`192daa4d…`). Unpack the frontend bundle:

```bash
rm -rf /tmp/item-management-dist
mkdir -p /tmp/item-management-dist
sudo unzip -q /tmp/dist-upload.zip -d /tmp/item-management-dist
test -f /tmp/item-management-dist/index.html || { echo 'FRONTEND ARCHIVE INVALID - STOP'; exit 1; }
ls -l /tmp/item-management-dist | head
```

## B.5 Back up the current application files

```bash
TS=$(date +%Y%m%d_%H%M%S)
sudo cp -a /opt/item-management/backend/item-management.jar \
  "/opt/item-management/backend/item-management.jar.bak-${TS}"
sudo tar -czf "/root/item-management-frontend-${TS}.tgz" -C /var/www/item-management .
ls -l "/opt/item-management/backend/item-management.jar.bak-${TS}" "/root/item-management-frontend-${TS}.tgz"
echo "Rollback files: jar.bak-${TS}, item-management-frontend-${TS}.tgz"
```

## B.6 Deploy the backend (Flyway applies V6 on startup)

Downtime is the few seconds while the service is stopped and the jar replaced.

```bash
sudo systemctl stop item-management
sudo systemctl is-active item-management || true

sudo install -o root -g root -m 0644 /tmp/item-management.jar \
  /opt/item-management/backend/item-management.jar

sudo systemctl start item-management
sleep 8
sudo systemctl status item-management --no-pager
ss -ltnp | grep ':8080' || echo 'port 8080 not listening'
```

Check startup and Flyway:

```bash
sudo journalctl -u item-management --since '10 minutes ago' --no-pager |
  grep -Ei 'flyway|migrat|schema|started|error|exception' | tail -n 60
```

A successful V6 run logs `Successfully applied 1 migration` (description `printing`) and
`Started ItemManagementApplication`. If the service failed, go to Section B.10 immediately; do not
re-deploy or alter the database.

## B.7 Deploy the frontend

```bash
sudo rsync -a --delete --exclude='.well-known/' /tmp/item-management-dist/ /var/www/item-management/
sudo find /var/www/item-management -type d -exec chmod 755 {} +
sudo find /var/www/item-management -type f -exec chmod 644 {} +
NGINX_USER=$(ps -o user= -C nginx | head -n1 | tr -d ' ')
sudo chown -R "${NGINX_USER:-www-data}:${NGINX_USER:-www-data}" /var/www/item-management
ls -l /var/www/item-management/index.html
```

No Nginx configuration change is required for this release. Do not reload or edit Nginx unless it
was actually changed. Verify only:

```bash
sudo nginx -t
```

If (and only if) you changed the site file:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

## B.8 Verify Flyway history

```bash
sudo -u postgres psql -d item_management_prod -c \
"SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Expected: V1–V5 `t`, V6 `printing` `t`, no failed row.

## B.9 Verify tables, columns, constraints and permissions

Tables:

```bash
sudo -u postgres psql -d item_management_prod -c \
"SELECT table_name FROM information_schema.tables
 WHERE table_schema = 'public'
   AND table_name IN ('printer_configs','print_pairing_codes','print_devices','print_jobs')
 ORDER BY table_name;"
```

Expected exactly four rows.

Columns and nullability:

```bash
sudo -u postgres psql -d item_management_prod -c \
"SELECT table_name, ordinal_position, column_name, data_type,
        character_maximum_length, is_nullable, column_default
 FROM information_schema.columns
 WHERE table_schema = 'public'
   AND table_name IN ('printer_configs','print_pairing_codes','print_devices','print_jobs')
 ORDER BY table_name, ordinal_position;"
```

Constraints:

```bash
sudo -u postgres psql -d item_management_prod -c \
"SELECT conrelid::regclass AS table_name, conname, contype, pg_get_constraintdef(oid) AS definition
 FROM pg_constraint
 WHERE conrelid IN ('printer_configs'::regclass,'print_pairing_codes'::regclass,
                    'print_devices'::regclass,'print_jobs'::regclass)
 ORDER BY 1, 2;"
```

Indexes:

```bash
sudo -u postgres psql -d item_management_prod -c \
"SELECT tablename, indexname, indexdef FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename IN ('printer_configs','print_pairing_codes','print_devices','print_jobs')
 ORDER BY tablename, indexname;"
```

Permissions:

```bash
sudo -u postgres psql -d item_management_prod -c \
"SELECT code, name FROM permissions WHERE code LIKE 'PRINTER%' ORDER BY code;"

sudo -u postgres psql -d item_management_prod -c \
"SELECT COALESCE(s.store_code,'PLATFORM') AS store, r.code AS role_code, p.code AS permission_code
 FROM role_permissions rp
 JOIN roles r ON r.id = rp.role_id
 JOIN permissions p ON p.id = rp.permission_id
 LEFT JOIN stores s ON s.id = r.store_id
 WHERE p.code IN ('PRINTER_VIEW','PRINTER_MANAGE')
 ORDER BY store, role_code, permission_code;"
```

Cross-store integrity (both must return 0):

```bash
sudo -u postgres psql -d item_management_prod -c \
"SELECT COUNT(*) AS cross_store_jobs FROM print_jobs j
 JOIN branches b ON b.id = j.branch_id WHERE b.store_id <> j.store_id;"

sudo -u postgres psql -d item_management_prod -c \
"SELECT COUNT(*) AS cross_store_printers FROM printer_configs p
 JOIN branches b ON b.id = p.branch_id WHERE b.store_id <> p.store_id;"
```

Row counts (expected all zero on first deploy):

```bash
sudo -u postgres psql -d item_management_prod -c \
"SELECT 'printer_configs' AS t, COUNT(*) FROM printer_configs
 UNION ALL SELECT 'print_pairing_codes', COUNT(*) FROM print_pairing_codes
 UNION ALL SELECT 'print_devices', COUNT(*) FROM print_devices
 UNION ALL SELECT 'print_jobs', COUNT(*) FROM print_jobs;"
```

## B.10 Rollback (only if deployment verification fails)

Read Section D first. Do not restore the database unless the schema itself is broken and the owner
has explicitly approved a destructive restore.

Quick application rollback (schema stays at V6; V6 is additive and the previous jar ignores the
extra tables):

```bash
TS=<timestamp printed in B.5>
sudo systemctl stop item-management
sudo cp -a "/opt/item-management/backend/item-management.jar.bak-${TS}" \
  /opt/item-management/backend/item-management.jar
sudo systemctl start item-management
sudo systemctl status item-management --no-pager
```

Frontend rollback:

```bash
TS=<timestamp printed in B.5>
sudo rm -rf /var/www/item-management/*
sudo tar -xzf "/root/item-management-frontend-${TS}.tgz" -C /var/www/item-management
sudo nginx -t
```

If the service cannot start because Flyway rejects the newer V6 history, keep the new jar and fix
forward; a database restore is the last resort.

## B.11 API smoke tests

Get a branch id (read-only):

```bash
BRANCH_ID=$(sudo -u postgres psql -d item_management_prod -Atc \
"SELECT id FROM branches ORDER BY id LIMIT 1;")
echo "Branch: $BRANCH_ID"
```

Login without writing the password to shell history:

```bash
read -r -p 'IMS username: ' IMS_USER
read -r -s -p 'IMS password: ' IMS_PASS; echo
LOGIN_STATUS=$(printf '{"username":"%s","password":"%s"}' "$IMS_USER" "$IMS_PASS" |
  curl -sS -o /tmp/ims-login.json -w '%{http_code}' \
    -X POST https://hiremathwebstudio.in/api/auth/login \
    -H 'Content-Type: application/json' --data-binary @-)
unset IMS_PASS
echo "Login HTTP: $LOGIN_STATUS"
sudo chmod 600 /tmp/ims-login.json

TOKEN=$(sudo sed -n 's/.*"token":"\([^"]*\)".*/\1/p' /tmp/ims-login.json | head -n1)
test -n "$TOKEN" && echo 'Token received' || { echo 'LOGIN FAILED'; }
```

Protected endpoints (HTTP 200 expected for a user whose role has the permission):

```bash
curl -sS -o /tmp/printers.json -w 'printers HTTP %{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  "https://hiremathwebstudio.in/api/printing/printers?branchId=${BRANCH_ID}"

curl -sS -o /tmp/jobs.json -w 'print jobs HTTP %{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  'https://hiremathwebstudio.in/api/printing/jobs?page=0&size=5'
```

`403` means the signed-in role lacks `PRINTER_VIEW`; grant it in **Settings → Roles** or use an
owner/admin account. `404` for the printers endpoint means V6 did not apply.

Clean up:

```bash
unset TOKEN IMS_USER
sudo rm -f /tmp/ims-login.json /tmp/printers.json /tmp/jobs.json
```

## B.12 Frontend and HTTPS checks

```bash
curl -sS -o /dev/null -w 'frontend HTTP %{http_code}\n' https://hiremathwebstudio.in/
# 401/403 here means the API is reachable and the endpoint is protected (expected without a token)
curl -sS -o /dev/null -w 'api reachable HTTP %{http_code}\n' https://hiremathwebstudio.in/api/printing/printers
curl -sSI https://hiremathwebstudio.in/ | head -n 20
echo | openssl s_client -servername hiremathwebstudio.in \
  -connect hiremathwebstudio.in:443 2>/dev/null | openssl x509 -noout -subject -dates
```

In a browser (hard refresh once): sign in, confirm **Print History** appears in the Sales menu, and
**Settings → Printers** opens at desktop and phone widths.

## B.13 Printer functional checks (on site, after deployment)

Windows PC:
1. Tray app running; port `17777`; default printer shown.
2. Website **Settings → Printers**: paste bridge key, port `17777`, Save bridge, Check status, Test default printer.
3. Complete a small sale; receipt prints with no dialog; Print History shows `WINDOWS / AUTO / PRINTED`.

Android tablet/phone:
1. APK 1.0.0 installed; service notification visible; port `17778`.
2. **Allowed website** set to `https://hiremathwebstudio.in`.
3. Generate a pairing code in **Settings → Printers**, pair the device, confirm branch printers sync.
4. Add the receipt printer IP/port `9100`, paper width, purpose; Test connection.
5. Complete a sale and a manual reprint; Print History shows `ANDROID / AUTO|MANUAL / PRINTED`.

If no bridge is running, the UI must show a clear error and the job must be recorded as `FAILED`
after reporting; it must not open a browser print dialog.

---

# C. Production configuration requirements

No new environment variables are required for V6. Confirm the existing file without printing
secrets:

```bash
sudo awk -F= '/^(SPRING_PROFILES_ACTIVE|EMBEDDED_POSTGRES|SERVER_ADDRESS|SERVER_PORT|DB_URL|DB_USERNAME|CORS_ORIGINS|SEED_DEMO)=/{print}' /etc/item-management.env
sudo awk -F= '/^JWT_SECRET=/{print "JWT_SECRET length:", length($2)}' /etc/item-management.env
sudo stat -c '%U:%G %a %n' /etc/item-management.env /opt/item-management/backend/item-management.jar
```

Expected:

| Setting | Required value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `postgres` |
| `EMBEDDED_POSTGRES` | `false` |
| `SERVER_PORT` | `8080` |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/item_management_prod` |
| `DB_USERNAME` | `ims_user` |
| `DB_PASSWORD` | existing production secret — **do not print or replace** |
| `JWT_SECRET` | existing production secret; length well above 32 characters — **do not print or replace** |
| `JWT_EXPIRATION_MS` | existing value; default `43200000` (12 h) |
| `CORS_ORIGINS` | `https://hiremathwebstudio.in` (add `,https://www.hiremathwebstudio.in` if used) |
| `SEED_DEMO` | `false` |
| Java | 17 (`java -version`) |
| Env file permissions | readable by root and the service group; not world-readable |
| Nginx | proxies `/api/` to `127.0.0.1:8080`; serves `index.html` for SPA routes |

CORS is only relevant to browsers calling the API from a different origin. The production frontend
and API share `https://hiremathwebstudio.in`, so a missing CORS entry does not break the site. If
another origin is added later, append it to `CORS_ORIGINS`; never remove existing values and never
replace secrets.

Windows bridge allowlist is client-side (`%LOCALAPPDATA%\ItemManagementPrintBridge\config.json`) and
must include the production origin. Android's allowlist is set in the app. Bridge keys and device
tokens do **not** need to be regenerated for this release.

---

# D. Rollback procedure

| Component | Rollback | Notes |
|---|---|---|
| Backend jar | Restore `item-management.jar.bak-<TS>` and restart | Does **not** reverse V6 |
| Frontend | Extract `item-management-frontend-<TS>.tgz` over `/var/www/item-management` | No cache purge needed; keep index.html fresh |
| Nginx | Restore site backup and `nginx -t && systemctl reload nginx` | Only if the site file was changed; it should not be |
| Database | Restore the fresh pre-deployment dump | Destructive; loses post-deployment data; explicit approval required |

> **A jar rollback does not reverse a database migration.** V6 is additive (four new tables, two
> permission rows, role grants). The previous jar can start against a V6 database because it does
> not reference the new tables and Flyway ignores a later applied version. Prefer a forward fix
> (V7) over a restore. Restore only when the owner explicitly approves it.

Destructive restore — **do not run without written approval**:

```bash
# WARNING: drops and recreates item_management_prod.
# All data written after the backup is permanently lost.
sudo systemctl stop item-management

sudo -u postgres psql -d postgres -c \
"SELECT pg_terminate_backend(pid) FROM pg_stat_activity
 WHERE datname = 'item_management_prod' AND pid <> pg_backend_pid();"

sudo -u postgres dropdb --if-exists item_management_prod
sudo -u postgres createdb -O ims_user item_management_prod
sudo -u postgres pg_restore -d item_management_prod --no-owner --role=ims_user \
  /root/item_management_pre_v6_<TS>.dump

sudo systemctl start item-management
sudo -u postgres psql -d item_management_prod -c \
"SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Before restoring, take another backup of the current broken state if it is readable. Keep all
backups until the deployment and rollback window are closed.

Non-destructive alternative (preferred): leave V6 applied, restore only the previous jar/frontend,
and ship a V7 correction if needed.

---

# E. Post-deployment checklist

- [ ] Fresh backup created in `/tmp`, verified with `pg_restore --list`, copied to `/root` with `600`.
- [ ] Original `item_management_backup_before_v6.dump` untouched.
- [ ] Backend jar replaced; service `active (running)`; port 8080 listening.
- [ ] `journalctl` shows V6 applied and application started; no Flyway checksum error.
- [ ] `flyway_schema_history` shows V1–V6 all successful.
- [ ] Four printing tables exist with expected columns, FKs, unique keys and indexes.
- [ ] `PRINTER_VIEW` and `PRINTER_MANAGE` exist; owner/admin grants present.
- [ ] Cross-store integrity queries return 0.
- [ ] Frontend `index.html` and hashed assets updated; HTTPS 200; certificate valid.
- [ ] Login succeeds; `/api/printing/printers` and `/api/printing/jobs` return 200 for an authorised user.
- [ ] Print History page and Settings → Printers tab visible to authorised roles.
- [ ] Windows bridge: status, default printer, test slip, automatic sale print, manual reprint.
- [ ] Android bridge: pairing/refresh, test slip, automatic sale print, duplicate suppression.
- [ ] Bridge origin allowlist contains `https://hiremathwebstudio.in`.
- [ ] A failed/unavailable bridge produces a clear UI error and a `FAILED` job, not a print dialog.
- [ ] Rollback jar, frontend archive and database dump paths recorded.
- [ ] Existing business workflows (POS, sales, purchases, inventory, transfers, imports, tax, subscriptions) spot-checked.
