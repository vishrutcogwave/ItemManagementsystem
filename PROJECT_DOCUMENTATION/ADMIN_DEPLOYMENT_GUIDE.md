# Administrator and Deployment Guide

Item Management System (IMS) — full deployment, upgrade, backup, monitoring and troubleshooting guide for administrators.

> **Status of this guide.** Every command below was derived from the actual repository, the verified
> local build, and the recorded production runbook (`DEPLOYMENT_RUNBOOK.md`). Commands marked
> **prepared** have *not* been executed against the live server during this work. The live server was
> not accessed, modified or inspected (no credentials were provided). Nothing here authorises a
> production change on its own; use `DEPLOYMENT_CHECKLIST.md` and obtain owner approval first.

---

## 1. What gets deployed

| Component | Technology | Location on server |
|---|---|---|
| Backend API | Spring Boot 3.3.5, Java 17, executable jar | `/opt/item-management/backend/item-management.jar` |
| Database | PostgreSQL (production 18.6; any 14+ supported) | database `item_management_prod`, user `ims_user` |
| Frontend | React 19 static bundle (Vite) | `/var/www/item-management` |
| Reverse proxy | Nginx, TLS | `/etc/nginx/sites-available/item-management` |
| Service manager | systemd unit | `/etc/systemd/system/item-management.service` |
| Runtime config | environment file | `/etc/item-management.env` |
| Optional clients | Windows print bridge, Android print bridge | customer PCs / Android devices |

The backend serves `/api/**`. The frontend is a single-page application; Nginx must return
`index.html` for any non-file route.

### 1.1 Version and migration state

Schema is managed by Flyway. The repository contains eight migrations:

| Version | File | Purpose | Type |
|---|---|---|---|
| V1 | `V1__init.sql` | Core schema, 22 tables, 26 permissions | Baseline |
| V2 | `V2__tax_master.sql` | Tax masters, tax snapshots, tax permissions | Additive |
| V3 | `V3__item_rate.sql` | Removes `default_purchase_price` / `default_selling_price` | **Destructive (already applied in production)** |
| V4 | `V4__subscriptions.sql` | Subscriptions, history, platform settings | Additive |
| V5 | `V5__item_import.sql` | Import batches/rows, document sequences | Additive |
| V6 | `V6__printing.sql` | 4 printing tables, `PRINTER_VIEW`, `PRINTER_MANAGE` | Additive |
| V7 | `V7__item_barcode.sql` | `items.barcode`, `items.barcode_format`, backfill, unique index | Additive |
| V8 | `V8__auth_security.sql` | `users.must_change_password`, `users.token_version`, `revoked_tokens` | Additive |

Declared production state (from `DEPLOYMENT_RUNBOOK.md`): **V1–V5 applied, V6/V7/V8 pending**. A
deployment from this build applies **V6, V7 and V8 together**. All three are additive and safe on
live data; V8 rewrites no row. See `SERVER_VERSION_COMPARISON.md` for the full comparison.

---

## 2. Prerequisites

### 2.1 Build host (Windows — this project’s developer machine)

| Tool | Required | Verify |
|---|---|---|
| JDK | 17 | `java -version` |
| Node.js | 24.x (20+ works) | `node --version` |
| npm | bundled with Node | `npm --version` |
| Git | current | `git --version` |
| Gradle | not installed; use the wrapper | `.\backend\gradlew.bat --version` |

### 2.2 Production server (Ubuntu 22.04/24.04 VPS)

| Package | Purpose | Install |
|---|---|---|
| OpenJDK 17 | run the jar | `sudo apt install -y openjdk-17-jre-headless` |
| PostgreSQL 14+ | database | `sudo apt install -y postgresql postgresql-contrib` |
| Nginx | TLS termination + static files | `sudo apt install -y nginx` |
| Certbot | Let’s Encrypt TLS | `sudo apt install -y certbot python3-certbot-nginx` |
| unzip, rsync | release handling | `sudo apt install -y unzip rsync` |

Recommended server sizing for a single retail store: 2 vCPU, 4 GB RAM, 40 GB SSD. Increase RAM for
many stores because Hibernate plus PostgreSQL share the host.

### 2.3 Windows vs Ubuntu command equivalents used in this guide

| Task | Windows (PowerShell) | Ubuntu (bash) |
|---|---|---|
| Show Java version | `java -version` | `java -version` |
| Gradle wrapper | `.\backend\gradlew.bat` | `./backend/gradlew` |
| Set env var for one command | `$env:NAME='value'; command` | `NAME=value command` |
| Persistent env var | `setx NAME value` | edit `/etc/item-management.env` |
| Hash a file | `Get-FileHash file -Algorithm SHA256` | `sha256sum file` |
| Archive a folder | `Compress-Archive -Path .\dist\* -DestinationPath .\dist-upload.zip` | `tar -czf dist-upload.tar.gz -C dist .` |
| Unarchive | `Expand-Archive file.zip -DestinationPath target` | `unzip file.zip -d target` / `tar -xzf file.tar.gz -C target` |
| Service control | `Get-Service` / GUI | `sudo systemctl start|stop|restart|status item-management` |
| Service logs | Event Viewer | `sudo journalctl -u item-management --no-pager` |
| Copy to server | `scp .\file root@host:/tmp/` | `scp ./file root@host:/tmp/` |
| HTTP check | `curl.exe -sS -o NUL -w "%{http_code}" URL` | `curl -sS -o /dev/null -w '%{http_code}' URL` |

---

## 3. Runtime configuration

All backend settings are environment variables read by `application.yml`. The production profile
`postgres` disables the embedded database.

| Variable | Required production value | Default if unset | Notes |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `postgres` | none | Enables the PostgreSQL datasource |
| `EMBEDDED_POSTGRES` | `false` | `true` | Must be false in production |
| `EMBEDDED_POSTGRES_PORT` | not used | `54329` | Development only |
| `EMBEDDED_POSTGRES_DATA_DIR` | not used | `./.pgdata` | Development only |
| `SERVER_PORT` | `8080` | `8080` | Nginx proxies to this port |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/item_management_prod` | `jdbc:postgresql://localhost:5432/item_management_db` | |
| `DB_USERNAME` | `ims_user` | `postgres` | |
| `DB_PASSWORD` | existing production secret | `postgres` | Never print, log or commit |
| `DB_POOL_SIZE` | `15` | `15` | Raise carefully |
| `JWT_SECRET` | existing production secret (32+ chars) | dev-only default | Changing it invalidates every session |
| `JWT_EXPIRATION_MS` | `43200000` (12 h) | `43200000` | |
| `CORS_ORIGINS` | `https://<public-domain>` | localhost origins | Exact origins, comma-separated. Also used to validate `Origin`/`Referer` on cookie-authenticated writes; a mismatch returns `403 CSRF_ORIGIN_REJECTED`. Add `,https://www.<domain>` if used |
| `AUTH_COOKIE_NAME` | `ims_auth` | `ims_auth` | Session cookie name |
| `AUTH_COOKIE_SECURE` | `true` | `false` | **Must be true behind HTTPS**; false breaks nothing locally but weakens production |
| `AUTH_COOKIE_SAME_SITE` | `Strict` | `Strict` | Keep `Strict` unless a cross-site flow is explicitly required |
| `LOGIN_MAX_ATTEMPTS` | `5` | `5` | Failures per login key before lockout |
| `LOGIN_WINDOW_SECONDS` | `900` | `900` | Counting window |
| `LOGIN_LOCKOUT_SECONDS` | `900` | `900` | Lockout duration |
| `TOKEN_BLACKLIST_CLEANUP_MS` | `3600000` | `3600000` | Purges only expired `revoked_tokens` |
| `SEED_DEMO` | `false` | `false` | Never true in production |
| `PROVISION_PLATFORM_ADMIN` | `false` | `false` | Set to `true` **only** for the single startup that creates the first platform admin |
| `PLATFORM_ADMIN_USERNAME` | chosen username | empty | Required when provisioning; 3–40 `[A-Za-z0-9._-]` |
| `PLATFORM_ADMIN_PASSWORD` | strong secret | empty | Required when provisioning; policy-checked; never commit |
| `PLATFORM_ADMIN_EMAIL` | valid email | empty | Required when provisioning |
| `PLATFORM_ADMIN_FULL_NAME` | display name | `Platform Administrator` | Optional |
| `PLATFORM_ADMIN_MOBILE` | mobile | empty | Optional |

There is **no default platform administrator** in the code. If none exists and provisioning is not
requested, sign-in is impossible until an operator provisions one. Provisioning is refused if any
platform administrator already exists, so it can never overwrite an account.

`application.yml` also fixes:

- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate never changes the schema; a schema/entity
  mismatch stops startup. This is intentional and is why migrations must be deployed with the jar.
- `spring.flyway.enabled=true` — migrations run at startup.
- `spring.servlet.multipart.max-file-size=10MB` / `max-request-size=12MB` — Excel import limit.
- `server.error.include-message=always` — API error bodies contain a readable message.

### 3.1 Writing the env file (Ubuntu)

```bash
sudo install -o root -g root -m 0600 /dev/null /etc/item-management.env
sudo tee /etc/item-management.env >/dev/null <<'EOF'
SPRING_PROFILES_ACTIVE=postgres
EMBEDDED_POSTGRES=false
SERVER_PORT=8080
DB_URL=jdbc:postgresql://127.0.0.1:5432/item_management_prod
DB_USERNAME=ims_user
DB_PASSWORD=REPLACE_WITH_PRODUCTION_SECRET
JWT_SECRET=REPLACE_WITH_PRODUCTION_SECRET
JWT_EXPIRATION_MS=43200000
CORS_ORIGINS=https://REPLACE_WITH_DOMAIN
AUTH_COOKIE_SECURE=true
AUTH_COOKIE_SAME_SITE=Strict
LOGIN_MAX_ATTEMPTS=5
LOGIN_WINDOW_SECONDS=900
LOGIN_LOCKOUT_SECONDS=900
SEED_DEMO=false
PROVISION_PLATFORM_ADMIN=false
EOF
sudo chmod 600 /etc/item-management.env
```

For the **first** startup only, add the provisioning values (then remove them):

```bash
PROVISION_PLATFORM_ADMIN=true
PLATFORM_ADMIN_USERNAME=REPLACE_WITH_ADMIN_USERNAME
PLATFORM_ADMIN_PASSWORD=REPLACE_WITH_STRONG_PASSWORD
PLATFORM_ADMIN_EMAIL=REPLACE_WITH_ADMIN_EMAIL
```

The first sign-in forces a password change. After the account exists, set
`PROVISION_PLATFORM_ADMIN=false` and remove the three `PLATFORM_ADMIN_*` values; the provisioner then
does nothing on future starts.

Rules:

- Never overwrite an existing env file blindly. Back it up first:
  `sudo cp -a /etc/item-management.env /etc/item-management.env.bak-$(date +%Y%m%d_%H%M%S)`.
- Keep the file `600` and root-owned (or readable only by the service user).
- Verify without revealing secrets:
  ```bash
  sudo awk -F= '/^(SPRING_PROFILES_ACTIVE|EMBEDDED_POSTGRES|SERVER_PORT|DB_URL|DB_USERNAME|CORS_ORIGINS|SEED_DEMO)=/{print}' /etc/item-management.env
  sudo awk -F= '/^JWT_SECRET=/{print "JWT_SECRET length:", length($2)}' /etc/item-management.env
  ```

---

## 4. First-time server installation (Ubuntu)

These steps are for a brand-new server. For an existing installation, jump to Section 6.

### 4.1 Create the service user and directories

```bash
sudo useradd --system --create-home --home-dir /opt/item-management --shell /usr/sbin/nologin ims 2>/dev/null || true
sudo mkdir -p /opt/item-management/backend /var/www/item-management
sudo chown -R ims:ims /opt/item-management
```

### 4.2 Create the database

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE ims_user LOGIN PASSWORD 'REPLACE_WITH_STRONG_DB_PASSWORD';
CREATE DATABASE item_management_prod OWNER ims_user;
SQL
```

Do not grant superuser. The application only needs ownership of its database.

### 4.3 Install the backend jar and env file

```bash
sudo install -o ims -g ims -m 0644 /tmp/item-management.jar /opt/item-management/backend/item-management.jar
sudo chown root:root /etc/item-management.env && sudo chmod 600 /etc/item-management.env
```

The systemd unit should run as `ims` with `EnvironmentFile=/etc/item-management.env`. Example unit:

```ini
[Unit]
Description=Item Management System backend
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=ims
Group=ims
EnvironmentFile=/etc/item-management.env
WorkingDirectory=/opt/item-management
ExecStart=/usr/bin/java -Xms256m -Xmx1024m -jar /opt/item-management/backend/item-management.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now item-management
sudo systemctl status item-management --no-pager
sudo journalctl -u item-management --since '5 minutes ago' --no-pager | grep -Ei 'flyway|started|error|exception'
```

On the first start Flyway applies V1–V8. Expected log line ends with
`Successfully applied 8 migrations` and then `Started ItemManagementApplication`.

### 4.4 Nginx and TLS

`/etc/nginx/sites-available/item-management`:

```nginx
server {
    listen 80;
    server_name REPLACE_WITH_DOMAIN;

    root /var/www/item-management;
    index index.html;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }

    client_max_body_size 12m;
}
```

```bash
sudo ln -s /etc/nginx/sites-available/item-management /etc/nginx/sites-enabled/item-management
sudo nginx -t
sudo systemctl reload nginx
sudo certbot --nginx -d REPLACE_WITH_DOMAIN
sudo certbot renew --dry-run
```

Notes:

- `proxy_pass` must have **no** trailing URI after the port, otherwise `/api/...` is rewritten.
- `client_max_body_size` must be at least the backend multipart limit (12 MB).
- `try_files ... /index.html` is required for React Router deep links.

### 4.5 Frontend files

```bash
sudo rsync -a --delete --exclude='.well-known/' /tmp/item-management-dist/ /var/www/item-management/
sudo find /var/www/item-management -type d -exec chmod 755 {} +
sudo find /var/www/item-management -type f -exec chmod 644 {} +
sudo chown -R www-data:www-data /var/www/item-management
```

`rsync --delete` removes stale hashed assets. Keep `.well-known/` if certbot wrote it inside the web
root; on this deployment certbot manages `/etc/letsencrypt`, so excluding it is harmless.

### 4.6 First-run verification

```bash
curl -sS -o /dev/null -w 'frontend HTTP %{http_code}\n' https://REPLACE_WITH_DOMAIN/
curl -sS -o /dev/null -w 'api HTTP %{http_code}\n' https://REPLACE_WITH_DOMAIN/api/auth/login
sudo -u postgres psql -d item_management_prod -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

`GET /api/auth/login` is expected to return `401`/`405` (the endpoint is `POST`); that proves the API
is reachable. A `502` means the backend is down. A `V1–V8` history with all `success = t` is the
target state.

---

## 5. Building a release on Windows

Run from the repository root.

```powershell
Set-Location 'D:\vishrut\Clone\ItemManagementSystem'

# 1. Backend tests (starts the real app on an embedded PostgreSQL)
Set-Location .\backend
.\gradlew.bat clean test --console=plain
Set-Location ..

# 2. Production jar
Set-Location .\backend
.\gradlew.bat bootJar --console=plain
Set-Location ..

# 3. Frontend type check, lint and bundle
npm ci
npx tsc -b
npm run lint
npm run build

# 4. Record artifact identity
Get-FileHash .\backend\build\libs\item-management-backend-1.0.0.jar -Algorithm SHA256
Get-ChildItem .\dist -Recurse | Measure-Object -Property Length -Sum | Select-Object Count, Sum
if (Test-Path .\dist-upload.zip) { Remove-Item .\dist-upload.zip }
Compress-Archive -Path .\dist\* -DestinationPath .\dist-upload.zip -CompressionLevel Optimal
Get-FileHash .\dist-upload.zip -Algorithm SHA256
```

Stop if tests, type check, lint (errors) or build fail. Warnings are acceptable only when they are
the known pre-existing set.

### 5.1 Verified artifacts of the current build

| Artifact | Size | SHA-256 |
|---|---|---|
| `backend/build/libs/item-management-backend-1.0.0.jar` | 150,463,066 bytes | `17f9787fe17176e412f824a44248bb1a4bb0ae3de0a01f3b916fbe4a41d7fbf8` |
| `dist/` | ≈ 3.0 MB, 57 files (rebuild-dependent) | archive `600dbe7d…8fe39` for the verified 2026-11-01 build |

The hash in the older runbook (`192daa4d…`) belongs to the V6-only jar, and `ab10662c…` / `ee8efcbb…`
belong to earlier pre-release builds. The current clean-built jar contains V6, V7 and V8 and has the
hash above. Always trust the hash printed during the actual build.

### 5.2 Upload to the server

```powershell
ssh root@REPLACE_WITH_HOST "rm -f /tmp/item-management.jar /tmp/dist-upload.zip; rm -rf /tmp/item-management-dist; mkdir -p /tmp/item-management-dist"
scp .\backend\build\libs\item-management-backend-1.0.0.jar root@REPLACE_WITH_HOST:/tmp/item-management.jar
scp .\dist-upload.zip root@REPLACE_WITH_HOST:/tmp/dist-upload.zip
```

On the server:

```bash
sha256sum /tmp/item-management.jar       # must equal the build-host hash
sudo rm -rf /tmp/item-management-dist && sudo mkdir -p /tmp/item-management-dist
sudo unzip -q /tmp/dist-upload.zip -d /tmp/item-management-dist
test -f /tmp/item-management-dist/index.html || echo 'FRONTEND ARCHIVE INVALID - STOP'
```

---

## 6. Upgrade deployment (current release: V6 + V7 + V8)

Follow `DEPLOYMENT_CHECKLIST.md` for the approval gate and tick list. The ordered procedure:

### 6.1 Pre-flight

```bash
df -h / /tmp
sudo systemctl status item-management --no-pager
sudo systemctl cat item-management
ss -ltnp | grep ':8080' || echo 'port 8080 not listening'
java -version
sudo nginx -t
sudo -u postgres psql -d item_management_prod -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Record the current state. If the history is not V1–V5 (or later), stop and reconcile before deploying.

### 6.2 Backup (mandatory)

```bash
TS=$(date +%Y%m%d_%H%M%S)
DUMP=/tmp/item_management_pre_deploy_${TS}.dump
sudo -u postgres pg_dump -F c -d item_management_prod -f "$DUMP"
test -s "$DUMP" || { echo 'BACKUP EMPTY - STOP'; exit 1; }
sudo -u postgres pg_restore --list "$DUMP" | wc -l
sudo cp -a "$DUMP" "/root/item_management_pre_deploy_${TS}.dump"
sudo chmod 600 "/root/item_management_pre_deploy_${TS}.dump"
sudo sha256sum "$DUMP" "/root/item_management_pre_deploy_${TS}.dump"
```

Both hashes must match and the TOC listing must be non-empty. Do not overwrite older backups.

### 6.3 Back up the current application files

```bash
TS=$(date +%Y%m%d_%H%M%S)
sudo cp -a /opt/item-management/backend/item-management.jar "/opt/item-management/backend/item-management.jar.bak-${TS}"
sudo tar -czf "/root/item-management-frontend-${TS}.tgz" -C /var/www/item-management .
echo "Rollback files: jar.bak-${TS}, item-management-frontend-${TS}.tgz"
```

### 6.4 Deploy backend (Flyway applies V6 + V7 + V8 at startup)

```bash
sudo systemctl stop item-management
sudo install -o root -g root -m 0644 /tmp/item-management.jar /opt/item-management/backend/item-management.jar
sudo systemctl start item-management
sleep 8
sudo systemctl status item-management --no-pager
ss -ltnp | grep ':8080' || echo 'port 8080 not listening'
sudo journalctl -u item-management --since '10 minutes ago' --no-pager | \
  grep -Ei 'flyway|migrat|schema|started|error|exception' | tail -n 60
```

Expected: `Migrating schema "public" to version "6 - printing"`, then
`to version "7 - item barcode"`, then `to version "8 - auth security"`,
`Successfully applied 3 migrations`, and `Started ItemManagementApplication`.

If startup fails, stop and go to Section 9. Do not re-deploy repeatedly and do not modify the
database manually.

### 6.5 Deploy frontend

```bash
sudo rsync -a --delete --exclude='.well-known/' /tmp/item-management-dist/ /var/www/item-management/
sudo find /var/www/item-management -type d -exec chmod 755 {} +
sudo find /var/www/item-management -type f -exec chmod 644 {} +
sudo chown -R www-data:www-data /var/www/item-management
sudo nginx -t
```

No Nginx reload is needed when the site file did not change. Reload only after a verified config
change: `sudo nginx -t && sudo systemctl reload nginx`.

### 6.6 Verify the migration result

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
     AND column_name IN ('barcode','barcode_format')
   ORDER BY column_name;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT COUNT(*) AS items_missing_barcode FROM items WHERE barcode IS NULL;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT indexname, indexdef FROM pg_indexes
   WHERE schemaname='public' AND tablename='items' AND indexname='uq_items_store_barcode';"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT code FROM permissions WHERE code IN ('PRINTER_VIEW','PRINTER_MANAGE') ORDER BY code;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT column_name, data_type, is_nullable, column_default
   FROM information_schema.columns
   WHERE table_schema='public' AND table_name='users'
     AND column_name IN ('must_change_password','token_version')
   ORDER BY column_name;"

sudo -u postgres psql -d item_management_prod -c \
  "SELECT COUNT(*) AS revoked_tokens_table FROM information_schema.tables
   WHERE table_schema='public' AND table_name='revoked_tokens';"

curl -sS -o /tmp/ims-health.json -w 'health HTTP %{http_code}\n' \
  https://REPLACE_WITH_DOMAIN/api/health
cat /tmp/ims-health.json; echo
curl -sS https://REPLACE_WITH_DOMAIN/api/version; echo
sudo rm -f /tmp/ims-health.json
```

Expected: V1–V8 all successful; four printing tables; `barcode` NOT NULL; `items_missing_barcode = 0`;
the unique index present; both printer permissions present; both new user columns present
(`must_change_password` NOT NULL default false, `token_version` NOT NULL default 0); the
`revoked_tokens` table present; `/api/health` returns `200` with `"status":"UP"` and the expected
`gitCommit` in `/api/version`.

### 6.7 API and UI smoke tests

```bash
read -r -p 'IMS username: ' IMS_USER
read -r -s -p 'IMS password: ' IMS_PASS; echo
LOGIN_STATUS=$(printf '{"username":"%s","password":"%s"}' "$IMS_USER" "$IMS_PASS" |
  curl -sS -o /tmp/ims-login.json -w '%{http_code}' \
    -X POST https://REPLACE_WITH_DOMAIN/api/auth/login \
    -H 'Content-Type: application/json' --data-binary @-)
unset IMS_PASS
echo "Login HTTP: $LOGIN_STATUS"
sudo chmod 600 /tmp/ims-login.json
TOKEN=$(sudo sed -n 's/.*"token":"\([^"]*\)".*/\1/p' /tmp/ims-login.json | head -n1)
test -n "$TOKEN" && echo 'Token received' || echo 'LOGIN FAILED'
```

Then, with `BRANCH_ID` from `SELECT id FROM branches ORDER BY id LIMIT 1;`:

```bash
curl -sS -o /tmp/items.json -w 'items HTTP %{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  "https://REPLACE_WITH_DOMAIN/api/items?page=0&size=5"
curl -sS -o /tmp/printers.json -w 'printers HTTP %{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  "https://REPLACE_WITH_DOMAIN/api/printing/printers?branchId=${BRANCH_ID}"
curl -sS -o /tmp/jobs.json -w 'print jobs HTTP %{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  'https://REPLACE_WITH_DOMAIN/api/printing/jobs?page=0&size=5'
sudo rm -f /tmp/ims-login.json /tmp/items.json /tmp/printers.json /tmp/jobs.json
unset TOKEN IMS_USER
```

`403` means the signed-in role lacks the permission (expected for restricted roles). `404` on a
printing endpoint means V6 did not apply. In the browser: sign in, open **Items** (barcode column and
label action present), **Settings → Printers**, **Print History**, and complete one small sale.

### 6.8 Barcode-specific post-deploy checks

```bash
# Every item has a barcode, and no store has duplicates (both must return 0)
sudo -u postgres psql -d item_management_prod -c \
  "SELECT COUNT(*) AS missing FROM items WHERE barcode IS NULL OR barcode = '';"
sudo -u postgres psql -d item_management_prod -c \
  "SELECT COUNT(*) AS duplicate_groups FROM (
     SELECT store_id, barcode FROM items GROUP BY store_id, barcode HAVING COUNT(*) > 1
   ) d;"
```

In the UI: open an item, print/download a label, use a USB scanner on the POS search field, and use
the camera scan dialog. Mobile camera scanning requires HTTPS (provided) and a Chromium-based browser
or Safari with `BarcodeDetector` support.

### 6.9 Platform account checks

Sign in with the platform admin account (store code `PLATFORM`), open **Platform → Store Accounts**,
search a store, open its accounts, and verify that only masked/absent password state is shown. Do not
reset a real customer password during a deployment test. Password reset returns a one-time temporary
password and must be confirmed in the UI. Cross-tenant access is blocked server-side.

Also open **Platform → Audit log** and confirm the page loads with filters and that the CSV export
returns a file. Performing one harmless filtered query is enough; do not export data unnecessarily.

### 6.10 Rotate the platform administrator password (required)

If a platform administrator was created by an older build with a known password, rotate it now:

1. Sign in with store code `PLATFORM`.
2. Open **Settings → Profile → Change password**.
3. Enter the current password, then a new password meeting the policy (8–72 characters, upper,
   lower, digit, special).
4. Confirm. Other sessions are invalidated immediately.
5. Verify the old password no longer signs in and the new one does.

Never delete or recreate the platform administrator to achieve a reset. If the current password is
unknown, follow an approved break-glass procedure with database access and owner approval — do not
edit password hashes by hand.

---

## 7. Client print bridges

### 7.1 Windows

1. Install `ItemManagementPrintBridgeSetup.exe` (or run the portable exe).
2. The bridge listens on `127.0.0.1:17777` and prints to the Windows default printer.
3. Add the production origin to `%LOCALAPPDATA%\ItemManagementPrintBridge\config.json` (see
   `DEPLOYMENT_RUNBOOK.md` A.10). Never overwrite `Key`, `Port`, `PrintMode` or `StartWithWindows`.
4. In the website: **Settings → Printers**, enter the bridge key, port `17777`, save, check status,
   run a test print.

### 7.2 Android

1. Install the signed APK. The bridge listens on `127.0.0.1:17778` and prints raw ESC/POS to the LAN
   printer.
2. In the app, set **Allowed website** to the production origin and keep port `17778`.
3. In the website: generate a pairing code, pair the device, add the printer IP/port `9100`, choose
   paper width, run a test connection.
4. Never re-sign the APK with a new key. Android refuses updates signed by a different key.

Bridge keys and device tokens do not need to be regenerated for V6/V7.

---

## 8. Backup policy

| What | How | Recommended frequency | Retention |
|---|---|---|---|
| Database | `pg_dump -F c` verified with `pg_restore --list` | nightly + before every deploy | 30 days + pre-deploy keep |
| Env file | copy to a protected location | after any change | last 5 versions |
| Backend jar | copy before replacement | every deploy | last 2 versions |
| Frontend | tar archive before replacement | every deploy | last 2 versions |
| Nginx/site config | copy of `sites-available/item-management` | after any change | last 5 versions |
| TLS certificates | certbot manages `/etc/letsencrypt` | automatic | certbot |

Nightly backup example (root cron, `sudo crontab -e`):

```cron
15 2 * * * TS=$(date +\%Y\%m\%d); pg_dump -F c -U postgres -d item_management_prod -f /root/backups/ims_${TS}.dump && find /root/backups -name 'ims_*.dump' -mtime +30 -delete
```

Test a restore into a scratch database at least once a quarter (non-destructive):

```bash
sudo -u postgres dropdb --if-exists ims_restore_check
sudo -u postgres createdb ims_restore_check
sudo -u postgres pg_restore -d ims_restore_check /root/item_management_pre_deploy_<TS>.dump
sudo -u postgres psql -d ims_restore_check -c "SELECT COUNT(*) FROM items;"
sudo -u postgres dropdb ims_restore_check
```

---

## 9. Monitoring and operations

### 9.1 Health checks

`GET /api/health` is public and checks the database. Use it for the systemd/uptime probe:

```bash
sudo systemctl is-active item-management
ss -ltnp | grep ':8080'
curl -fsS https://REPLACE_WITH_DOMAIN/api/health            # 200 + {"status":"UP"}
curl -fsS https://REPLACE_WITH_DOMAIN/api/version           # 200 + build/git commit
sudo -u postgres psql -d item_management_prod -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false;"
```

`503` with `"database":"DOWN"` means the application is running but PostgreSQL is unreachable — fix
the database, not the proxy. The two endpoints never expose version-internal secrets, host names or
credentials. Example external probe (cron or an uptime service):

```bash
curl -fsS --max-time 5 https://REPLACE_WITH_DOMAIN/api/health >/dev/null || echo 'IMS HEALTH ALERT'
```

### 9.2 Logs

```bash
sudo journalctl -u item-management -f
sudo journalctl -u item-management --since '1 hour ago' --no-pager | tail -n 200
sudo journalctl -u item-management | grep -Ei 'error|exception' | tail -n 50
sudo tail -f /var/log/nginx/access.log /var/log/nginx/error.log
```

The application logs at `INFO` for `com.itemmanagement` and `WARN` for `io.zonky`. Passwords, JWT
secrets, bridge keys and database passwords must never appear in logs. If a secret is found in a log,
rotate it and restrict log access.

### 9.3 Disk and database housekeeping

```bash
df -h
sudo du -sh /opt/item-management /var/www/item-management /var/lib/postgresql
sudo -u postgres psql -d item_management_prod -c "SELECT pg_size_pretty(pg_database_size('item_management_prod'));"
sudo -u postgres psql -d item_management_prod -c "VACUUM (ANALYZE);"
```

`audit_logs` and `print_jobs` grow over time. There is no automatic retention job. Documented policy:

| Data | Keep | Action |
|---|---|---|
| `audit_logs` | 24 months | Archive to cold storage; delete only with written approval and an audit entry |
| `print_jobs` | 12 months | Archive; delete only with written approval |
| `revoked_tokens` | until expiry | The application purges expired rows automatically (no business data) |

Never delete audit, print or business history to free space without owner approval. See
`KNOWN_ISSUES_AND_RECOMMENDATIONS.md` K-21.

---

## 10. Troubleshooting

| Symptom | Likely cause | Diagnosis | Safe fix |
|---|---|---|---|
| `502 Bad Gateway` | backend down / wrong port | `systemctl status item-management`, `ss -ltnp \| grep 8080` | start service; verify `SERVER_PORT` and `proxy_pass` |
| Frontend loads, every API returns 404 | Nginx `location /api/` missing or rewritten | `sudo nginx -T \| grep -A5 'location /api'` | fix `proxy_pass http://127.0.0.1:8080;` (no trailing URI), `nginx -t`, reload |
| Deep link returns Nginx 404 | missing SPA fallback | inspect `location /` | add `try_files $uri $uri/ /index.html;` |
| App fails to start with `Schema-validation` error | jar/migration mismatch | `journalctl` shows the missing column/table | deploy the matching jar; never switch `ddl-auto` to `update` in production |
| Flyway `checksum mismatch` | a migration file was edited after being applied | `flyway_schema_history` vs jar | restore the original migration; write a new version instead of editing history |
| Flyway `Detected failed migration` | interrupted migration | `success = false` row | restore the pre-deploy backup after approval; do not delete history manually |
| Login fails with valid credentials | wrong `storeCode`, disabled account, expired subscription | check API message; platform admin can see status | re-enable account / renew subscription; never reset a password without confirmation |
| Everything returns 403 `SUBSCRIPTION_EXPIRED` | store subscription expired | response body `code` | renew in Platform → Stores |
| Excel import rejected | file over 10 MB or wrong template | UI message | shrink file / use the template |
| Barcode not shown on old items | V7 not applied or caches | check DB and hard-refresh | apply V7 through a normal deploy |
| Label prints blank barcode | printer resolution/contrast | print a PNG label instead | raise printer DPI; use 50×30 mm labels |
| USB scanner types into the wrong field | focus not on the scan field | — | click the scan field or use the scan dialog |
| Camera scan unavailable | unsupported browser or non-HTTPS | `BarcodeDetector` support | use Chrome/Edge on Android or Safari iOS; keep HTTPS |
| Silent print opens a dialog | bridge not running / origin not allowlisted / job failed | Print History status | start bridge, add origin, retry manual print |
| Print job stuck `PENDING` | bridge online but printer unreachable | Print History error message | fix printer IP/port; retry |
| Android print fails after update | APK signed with a different key | install error | reinstall using the original release keystore |
| Password reset “fails” | request sent without confirmation flag | API 400 | confirm the action in the UI |
| `403 PASSWORD_CHANGE_REQUIRED` on every page | user was reset/provisioned and must choose a new password | response `code` | complete the forced password change screen; do not reset again |
| `429 TOO_MANY_REQUESTS` at login | lockout after repeated failures | `LOGIN_*` settings | wait for the lockout window; check for a stale client retry loop |
| `403 CSRF_ORIGIN_REJECTED` on a write | `Origin`/`Referer` not in `CORS_ORIGINS` | browser dev tools | add the exact production origin to `CORS_ORIGINS`, restart |
| Signed-in requests fail only in one browser | stale token in `localStorage` from an older build | dev tools → Application | clear site data and sign in again (the app migrates and removes the old token) |
| `/api/health` returns 503 | database down or wrong credentials | `"database":"DOWN"` | restore PostgreSQL/connectivity; do not change the proxy |

Never fix an incident by editing the production database directly. Prefer a forward migration or a
verified restore with owner approval.

---

## 11. Security operations checklist

- [ ] `/etc/item-management.env` is `600`, root-owned.
- [ ] `JWT_SECRET` is long, random and unique to production; never committed.
- [ ] Database password is unique; `ims_user` is not a superuser.
- [ ] PostgreSQL listens on `127.0.0.1` only (check `listen_addresses` in `postgresql.conf`).
- [ ] Firewall exposes only 22/80/443: `sudo ufw status`.
- [ ] HTTPS certificate valid and auto-renewing.
- [ ] `AUTH_COOKIE_SECURE=true`; `CORS_ORIGINS` contains only the real HTTPS origins.
- [ ] No secrets in logs, API responses or the frontend bundle.
- [ ] Platform admin accounts use strong unique passwords; default/seed credentials are disabled.
- [ ] The legacy seeded platform administrator password was rotated through Settings → Profile.
- [ ] `PROVISION_PLATFORM_ADMIN=false` and no `PLATFORM_ADMIN_*` values remain in the env file.
- [ ] `SEED_DEMO=false` and no demo store exists in production.
- [ ] Password resets are performed only through the platform UI with confirmation and audit logging.
- [ ] `/api/health` is monitored externally and returns `UP`.
- [ ] `audit_logs`/`print_jobs` retention is reviewed manually; no unapproved deletion.
- [ ] Backups are stored outside the web root and are not world-readable.
- [ ] Server packages patched monthly; reboot window agreed with the owner.

See `ROLLBACK_GUIDE.md` for recovery procedures and `KNOWN_ISSUES_AND_RECOMMENDATIONS.md` for known
gaps.

---

## 12. Related documents

| Document | Contents |
|---|---|
| `SYSTEM_ARCHITECTURE.md` | Components, tenancy, request lifecycle |
| `DATABASE_DOCUMENTATION.md` | Tables, migrations, SQL verification |
| `API_DOCUMENTATION.md` | Every endpoint and permission |
| `END_TO_END_WORKFLOW.md` | Operational workflows |
| `CUSTOMER_USER_MANUAL.md` | End-user instructions |
| `SERVER_VERSION_COMPARISON.md` | Deployed vs repository |
| `DEPLOYMENT_CHECKLIST.md` | Approval gate and ordered steps |
| `ROLLBACK_GUIDE.md` | Recovery procedures |
| `COMPLETE_TEST_PLAN.md` / `TEST_EXECUTION_REPORT.md` | Test coverage and results |
| `KNOWN_ISSUES_AND_RECOMMENDATIONS.md` | Gaps and follow-ups |
| `../DEPLOYMENT_RUNBOOK.md` | Original V6 preparation runbook with host-specific values |
