# Owner Account, Separate Builds and Hosted Deployment — One Runbook

**Audience:** the store owner and the person deploying the system
**Application:** Item Management System (Spring Boot 3.3.5 + PostgreSQL + React 19)
**Status of this document:** operational instructions only. No application code, schema, configuration or migration was changed to produce it.

This is the only document you need for: your owner login, the two separate builds, what to install on the hosted server, and the scripts that create every table.

**The short path**

1. Record your store/owner details in 1.2.
2. Build the frontend (`dist/`) and the backend jar separately — section 2.
3. Install Java, PostgreSQL and Nginx on the server — section 3.1.
4. Create the database, then create all tables with Flyway or scripts `02`–`04` — section 3.3 and 3.5.
5. Start the backend behind Nginx, register your owner account, and log in — sections 3.6–3.9 and 1.3–1.4.

---

## 1. Your owner account

### 1.1 What "owner" means here

There are two kinds of accounts:

| Account | Store code | Scope |
|---|---|---|
| **Store owner** (you) | your store code, e.g. `MYSTORE` | Your store and all its branches only. Cannot see or change any other store. |
| Platform administrator | `PLATFORM` | All stores. Intended for the software operator, not for day-to-day store work. |

You are the **store owner**. Your account is created when the store is registered and receives the `STORE_OWNER` role with all store-level permissions (dashboard, store settings, branches, users, roles, categories, items, tax, suppliers, purchases, inventory, transfers, sales, reports). It does **not** receive `PLATFORM_MANAGE`.

### 1.2 Record your details before registering

Fill this in and use exactly the same values in the registration call in 1.3. Store codes are stored upper-cased and usernames are kept as entered; both are matched case-insensitively at login. Use simple ASCII.

| Field | Your value | Rules |
|---|---|---|
| Store code | `________________` | 3–30 letters, numbers, `-`, `_`; cannot be `PLATFORM` |
| Store name | `________________` | up to 150 characters |
| Owner full name | `________________` | up to 150 characters |
| Login username | `________________` | 3–40 letters, numbers, `.`, `_`, `-` |
| Email | `________________` | valid email; also usable as login |
| Mobile | `________________` | up to 20 characters |
| Store address | `________________` | required |
| State | `________________` | e.g. `Karnataka`; used to decide CGST+SGST vs IGST |
| GSTIN | `________________` | optional |
| Main branch name | `________________` | optional; defaults to `Main Branch` |
| Password | set in 1.3; keep in a password manager | 8–72 characters (use 12+) |

**Do not write the real password in this file.** Keep it in a password manager and in the server secret store.

### 1.3 Create the owner account (production)

The owner is created by the public store-registration endpoint. It creates, in one transaction:

- the store (`stores`),
- the main branch (`branches`, code `MAIN`),
- the four standard roles (`STORE_OWNER`, `STORE_ADMIN`, `BRANCH_MANAGER`, `BRANCH_STAFF`),
- the five GST slabs (0%, 5%, 12%, 18%, 28%),
- your owner user with the `STORE_OWNER` role.

Run this after the backend is running (section 3) and before anyone else uses the system:

```bash
curl -sS -X POST https://ims.example.com/api/auth/register-store \
  -H 'Content-Type: application/json' \
  -d '{
    "storeCode": "MYSTORE",
    "storeName": "My Store",
    "ownerName": "Your Full Name",
    "email": "you@example.com",
    "mobile": "9876543210",
    "address": "Your store address",
    "gstNumber": "29ABCDE1234F1Z5",
    "state": "Karnataka",
    "username": "owner",
    "password": "ReplaceWithAStrongPassword",
    "branchName": "Main Branch"
  }'
```

Expected response:

```json
{ "message": "Store registered successfully. You can now sign in with store code MYSTORE." }
```

If you get `409`, the store code already exists. If you get `400`, a validation rule failed (check store code/username/password lengths).

> Registration is public in the current code (`/api/auth/register-store` is `permitAll`). After you create your own store, you may block that exact path at the reverse proxy (section 3.7) if you do not want anyone else registering stores on your server. Blocking it at the proxy does not change the application; the API itself still allows registration on localhost.

### 1.4 Log in

**In the browser:** open `https://ims.example.com/login?store=MYSTORE` and enter:

| Field | Value |
|---|---|
| Store code | your store code, e.g. `MYSTORE` |
| Username or email | your username or the owner email |
| Password | the password from 1.3 |

**By API:**

```bash
curl -sS -X POST https://ims.example.com/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"storeCode":"MYSTORE","username":"owner","password":"ReplaceWithAStrongPassword"}'
```

The response contains:

- `token` — send it as `Authorization: Bearer <token>` on every request,
- `tokenType` = `Bearer`,
- `expiresIn` = `43200` **seconds** (12 hours),
- `user`, `store`, `branches`, `roles`, `permissions` in the response,
- `allBranches` = `true` for the owner (this is specific to `STORE_OWNER` and `STORE_ADMIN`).

As owner, `allBranches` is `true`, so you can see every active branch without assigning yourself to each one.

### 1.5 Change the owner password

**From the UI (recommended):** Settings → Users → edit your own user → enter a value in **New password** → Save. Leaving the field blank keeps the current password. You cannot deactivate your own account.

**By API:** you need your user id and your role id.

```bash
# 1. Get your user record (replace 1 with your user id from login or /api/auth/me)
curl -sS https://ims.example.com/api/users/1 \
  -H 'Authorization: Bearer <owner-token>'

# 2. Update yourself; keep the same roleId and status
curl -sS -X PUT https://ims.example.com/api/users/1 \
  -H 'Authorization: Bearer <owner-token>' \
  -H 'Content-Type: application/json' \
  -d '{
    "fullName": "Your Full Name",
    "email": "you@example.com",
    "mobile": "9876543210",
    "roleId": 1,
    "branchIds": [],
    "status": "ACTIVE",
    "password": "NewStrongPassword"
  }'
```

`roleId` must be the id of your `STORE_OWNER` role for this store. The response from step 1 shows it under `roles[0].id`.

**SQL fallback** (only if the API is unavailable; requires the `pgcrypto` extension):

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE users
SET password_hash = crypt('NewStrongPassword', gen_salt('bf', 10)),
    updated_at = now()
WHERE store_id = (SELECT id FROM stores WHERE store_code = 'MYSTORE')
  AND username = 'owner';
```

Use the same `crypt(..., gen_salt('bf', 10))` form; it produces a BCrypt hash compatible with the application.

**Important:** changing a password does **not** cancel tokens that were already issued. They remain valid until their 12-hour expiry. If a token may have been stolen, change the password and then rotate `JWT_SECRET` and restart the backend (section 3.4); that invalidates every token immediately.

### 1.6 Platform administrator (optional)

The application always creates a platform user if it is absent:

| Field | Value |
|---|---|
| Store code | `PLATFORM` |
| Username | `platform` |
| Password | `Admin@123` |

Change it before the server is reachable by anyone else. There is no API to edit a platform user (store user endpoints reject platform accounts), so use SQL:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE users
SET password_hash = crypt('ReplaceWithAStrongPlatformPassword', gen_salt('bf', 10)),
    updated_at = now()
WHERE store_id IS NULL AND username = 'platform';
```

Then log in with store code `PLATFORM`. Never use the platform account for daily store work.

### 1.7 Existing development/demo login

If this is the local demo database (`SEED_DEMO=true`), the seeded owner is:

| Field | Value |
|---|---|
| Store code | `ABC001` |
| Username | `owner` |
| Password | `Owner@123` |

This is development data only. In production set `SEED_DEMO=false` (section 3.4) and register your real store as in 1.3.

---

## 2. Take the two builds separately

There are exactly two deployable artifacts:

1. **Frontend build** — static HTML/CSS/JS in `dist/`.
2. **Backend build** — one executable Spring Boot jar.

Build them on your development machine or CI, then upload the artifacts. You do **not** need Node.js or Gradle on the hosted server if you do this.

### 2.1 Frontend build (React + Vite)

Requirements on the build machine: **Node.js 22 LTS** (or 20.19+), npm.

```bash
# from the project root (the folder containing package.json)
npm ci
npm run build
```

Output: the `dist/` folder. It contains `index.html` and `assets/`. That folder is the whole frontend.

Create an upload archive:

```powershell
# Windows PowerShell
Compress-Archive -Path dist\* -DestinationPath ims-frontend.zip
```

```bash
# Git Bash / Linux / macOS
tar -czf ims-frontend.tgz -C dist .
```

Notes:

- The frontend calls the API with the relative base URL `/api`. It must be served from the **same domain** as the backend, with `/api` proxied (section 3.7). Do not open `dist/index.html` from the filesystem.
- There is no frontend `.env` file to configure; the API URL is compiled as `/api`.
- Do not upload `node_modules/` or `src/`.

### 2.2 Backend build (Spring Boot)

Requirements on the build machine: **JDK 17** (the Gradle toolchain targets Java 17). The Gradle wrapper is included; no separate Gradle installation is needed.

```bash
cd backend

# Windows
.\gradlew.bat clean bootJar

# Git Bash / Linux / macOS
./gradlew clean bootJar
```

Output:

```
backend/build/libs/item-management-backend-1.0.0.jar
```

That single jar is the backend. It contains the application and all Java dependencies. Do **not** upload `item-management-backend-1.0.0-plain.jar`; the `-plain` jar is not executable.

Optional, recommended before releasing:

```bash
cd backend
./gradlew test          # 16 integration tests; starts its own PostgreSQL on port 54330
```

If the version in `backend/build.gradle` changes, the jar name changes; update the systemd unit accordingly.

### 2.3 What to upload to the hosted server

| Artifact | From | Goes to |
|---|---|---|
| `item-management-backend-1.0.0.jar` | `backend/build/libs/` | `/opt/ims/app/` |
| `ims-frontend.zip` (contents of `dist/`) | `npm run build` | `/opt/ims/web/` |
| `01`–`05` SQL scripts | `docs/sql/` | `/opt/ims/sql/` |
| `09-owner-build-and-hosting-runbook.md` (this file) | `docs/` | `/opt/ims/` (reference) |

Never upload: `.pgdata/`, `node_modules/`, `src/`, `backend/build/` (except the executable jar), database dumps containing real data, or environment files with real secrets.

---

## 3. Hosted server setup

The commands below assume **Ubuntu 24.04 LTS** (or 22.04) with root/sudo access. A VPS is required; shared cPanel-style hosting cannot run a Spring Boot service.

### 3.1 What to install/download on the server

| Software | Package / source | Why |
|---|---|---|
| Java 17 runtime | `openjdk-17-jre-headless` | runs the Spring Boot jar |
| PostgreSQL 14 or newer | `postgresql`, `postgresql-client` (Ubuntu 24.04 installs 16) | the single application database |
| Nginx | `nginx` | serves the frontend and proxies `/api` |
| TLS certificate | `certbot`, `python3-certbot-nginx` | HTTPS |
| `unzip`, `curl`, `openssl` | `unzip`, `curl`, `openssl` | deployment, health checks, secret generation |
| Firewall | `ufw` | restrict public ports |
| Node.js 22 | only if you choose to build the frontend on the server | optional; not needed when you upload `dist/` |
| JDK 17 | only if you choose to build the backend on the server | optional; a JRE is enough to run the jar |

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless postgresql postgresql-client \
                    nginx certbot python3-certbot-nginx unzip curl openssl ufw
sudo systemctl enable --now postgresql
sudo systemctl enable --now nginx
```

Check:

```bash
java -version          # expect 17.x
psql --version         # expect 14.x or 16.x
nginx -v
```

Do **not** open PostgreSQL port 5432 to the internet. Keep it on localhost/private network (section 3.7).

### 3.2 Create the directory layout and service user

```bash
sudo useradd --system --home /opt/ims --shell /usr/sbin/nologin ims
sudo mkdir -p /opt/ims/app /opt/ims/web /opt/ims/sql
sudo chown -R ims:ims /opt/ims
```

Upload from your development machine (run there, not on the server):

```bash
scp backend/build/libs/item-management-backend-1.0.0.jar deploy@YOUR_SERVER:/tmp/
scp ims-frontend.zip deploy@YOUR_SERVER:/tmp/
scp docs/sql/0*.sql deploy@YOUR_SERVER:/tmp/
```

On the server:

```bash
sudo mv /tmp/item-management-backend-1.0.0.jar /opt/ims/app/
sudo rm -rf /opt/ims/web/*
sudo unzip /tmp/ims-frontend.zip -d /opt/ims/web
sudo mv /tmp/0*.sql /opt/ims/sql/
sudo chown -R ims:ims /opt/ims
sudo chmod -R 755 /opt/ims/web          # Nginx must be able to read the files
```

### 3.3 Create the one database and the application login

**One database for all stores and branches.** Never create a database per store or per branch.

Edit `/opt/ims/sql/01_create_database.sql` and replace `CHANGE_ME_STRONG_PASSWORD` with a long random password. Then run it **as the PostgreSQL superuser**:

```bash
sudo -u postgres psql -f /opt/ims/sql/01_create_database.sql
```

It creates:

- login `ims_app` (not superuser, no `CREATEDB`, no `CREATEROLE`),
- database `item_management_db` owned by `ims_app`,
- `public` schema ownership for `ims_app` and removal of `PUBLIC` create rights,
- UTC timezone and statement timeouts.

Verify:

```bash
sudo -u postgres psql -c "\du ims_app"
sudo -u postgres psql -c "\l item_management_db"
```

The application never connects as `postgres`.

### 3.4 Create the environment file

```bash
sudo mkdir -p /etc/ims
sudo install -m 640 -o root -g ims /dev/null /etc/ims/ims.env
sudo nano /etc/ims/ims.env
```

Content (replace every value in angle brackets):

```dotenv
SPRING_PROFILES_ACTIVE=postgres
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080

DB_URL=jdbc:postgresql://127.0.0.1:5432/item_management_db
DB_USERNAME=ims_app
DB_PASSWORD=<the password you put in script 01>
DB_POOL_SIZE=15

JWT_SECRET=<output of: openssl rand -base64 48>
JWT_EXPIRATION_MS=43200000

SEED_DEMO=false
CORS_ORIGINS=https://ims.example.com
```

Explanation of the critical values:

- `SPRING_PROFILES_ACTIVE=postgres` disables the embedded database and uses `DB_URL`.
- `SERVER_ADDRESS=127.0.0.1` keeps the backend reachable only through Nginx.
- `SEED_DEMO=false` prevents the `ABC001` demo store, demo users and fake purchases/sales from being created.
- `JWT_SECRET` must be a strong secret; the development default must never be used in production.
- `CORS_ORIGINS` is only needed if the frontend and API are on different origins. With the Nginx setup below they are the same origin.

Generate the JWT secret on the server:

```bash
openssl rand -base64 48
```

Protect the file:

```bash
sudo chmod 640 /etc/ims/ims.env
sudo chown root:ims /etc/ims/ims.env
```

If a value contains spaces, `#`, a double quote or a backslash, wrap it in double quotes, for example `DB_PASSWORD="a b#c"`. Systemd reads this file literally and does not expand shell variables.

Never commit this file to source control. The same applies to the database password.

### 3.5 Create all tables

You have two mutually exclusive paths. **Choose one.**

#### Path A — Flyway creates the schema automatically (recommended)

You do **not** run `02`, `03` or `04` in this path. On first start, Flyway applies:

| Migration | Creates |
|---|---|
| `V1__init.sql` | 23 tables, constraints, indexes, 4 sequences, 26 permissions, `PLATFORM_ADMIN` |
| `V2__tax_master.sql` | tax master, GST snapshot columns, 2 more permissions, GST slabs for existing stores |
| `V3__item_rate.sql` | single `items.rate`, removes legacy price columns |

Start the backend (3.6) and confirm:

```bash
sudo journalctl -u ims-backend -n 100 --no-pager
sudo -u postgres psql -d item_management_db -c "\dt"
sudo -u postgres psql -d item_management_db \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Expected: three successful Flyway rows, 23 application tables plus `flyway_schema_history`, and the reference data.

Then optionally run the read-only validation script:

```bash
sudo -u postgres psql -d item_management_db -f /opt/ims/sql/05_database_validation.sql
```

`05` only reads; it does not create or change anything.

#### Path B — create the schema manually with the SQL scripts

Use this only if a DBA cannot let Flyway manage the schema. The database must be **empty**.

```bash
psql "host=127.0.0.1 port=5432 dbname=item_management_db user=ims_app" \
  -v ON_ERROR_STOP=1 <<'SQL'
\i /opt/ims/sql/02_create_tables.sql
\i /opt/ims/sql/03_create_indexes.sql
\i /opt/ims/sql/04_insert_initial_data.sql
SQL
```

Enter the `ims_app` password when prompted. Run this as `ims_app`, not as `postgres`, so the application owns the objects it must later alter.

What each script does:

| Script | Creates | Rows |
|---|---|---|
| `02_create_tables.sql` | **all 23 application tables**, primary keys, foreign keys, CHECK constraints, 4 number sequences | none |
| `03_create_indexes.sql` | 19 performance indexes | none |
| `04_insert_initial_data.sql` | 28 permission codes, `PLATFORM_ADMIN` + 3 grants, standard store roles and GST slabs for stores that already exist | reference data only |
| `05_database_validation.sql` | read-only validation of tables, columns, indexes, permissions and data integrity | none |
| `06_sample_business_flow.sql` | demo store/purchase/sale/transfer data | **never run in production** |

The 23 application tables are:

```
stores, branches, roles, permissions, role_permissions, users, user_roles,
user_branches, categories, tax_master, items, suppliers, customers,
branch_inventory, inventory_transactions, stock_adjustments, purchases,
purchase_details, sales, sales_details, stock_transfers,
stock_transfer_details, audit_logs
```

`flyway_schema_history` exists only in Path A.

Because Path B does not write Flyway history, the backend must start with Flyway disabled:

```dotenv
SPRING_FLYWAY_ENABLED=false
```

Add that line to `/etc/ims/ims.env`. If you later want Flyway to manage the schema, do not simply re-enable it against this database; it will try to apply V1 again. Either keep Path B frozen and apply future DDL manually, or recreate the database with Path A (section 19 of `06-postgresql-setup.md` covers migration).

#### Do not mix the paths

- Flyway database + running `02` → "relation already exists" errors.
- Manual database + Flyway enabled → "found non-empty schema without schema history" error.
- Never run `03`/`04` before `02`.

#### Verify the table count

```bash
sudo -u postgres psql -d item_management_db -c "
SELECT count(*) AS application_tables
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name NOT IN ('flyway_schema_history');"
```

Expected: `23`.

### 3.6 Run the backend as a service

Create `/etc/systemd/system/ims-backend.service`:

```ini
[Unit]
Description=Item Management System backend
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
User=ims
Group=ims
WorkingDirectory=/opt/ims/app
EnvironmentFile=/etc/ims/ims.env
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/ims/app/item-management-backend-1.0.0.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ims-backend
sudo systemctl status ims-backend
sudo journalctl -u ims-backend -f
```

Health check:

```bash
curl -i http://127.0.0.1:8080/api/auth/me
```

Expected: `401 Unauthorized` with a JSON error envelope. A `401` proves the backend is running and the security filter is active. A connection error means the service is down or listening on the wrong port.

### 3.7 Nginx: serve the frontend and proxy `/api`

Create `/etc/nginx/sites-available/ims`:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name ims.example.com;

    root /opt/ims/web;
    index index.html;

    # React single-page app: unknown paths fall back to index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Backend API (same origin, so no CORS is needed)
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }

    client_max_body_size 10m;

    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;
}
```

Enable it and test:

```bash
sudo ln -s /etc/nginx/sites-available/ims /etc/nginx/sites-enabled/ims
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

Then enable HTTPS:

```bash
sudo certbot --nginx -d ims.example.com
```

Certbot modifies the Nginx block to listen on 443 and redirect 80 → 443. Renewal is automatic; test it with:

```bash
sudo certbot renew --dry-run
```

Open `https://ims.example.com` — the login page must load. Then create the owner (section 1.3).

Optional: block public store registration after your account exists. Add **before** the `/api/` location:

```nginx
location = /api/auth/register-store {
    return 403;
}
```

Reload Nginx. This blocks the path through the public domain only; the backend still allows it on localhost, so use this only if you do not need the Register page.

### 3.8 Firewall and network

Only these ports should be reachable from the internet:

| Port | Purpose | Exposure |
|---|---|---|
| 22 | SSH | your IP only, if possible |
| 80 | HTTP (redirects to HTTPS) | public |
| 443 | HTTPS | public |
| 8080 | Spring Boot | **localhost only** (`SERVER_ADDRESS=127.0.0.1`) |
| 5432 | PostgreSQL | **localhost/private only**, never public |

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'
sudo ufw enable
sudo ufw status
```

Check that PostgreSQL is not listening publicly:

```bash
sudo ss -ltnp | grep 5432
```

It should show `127.0.0.1:5432` (or a private address), not `0.0.0.0:5432`/`[::]:5432`.

### 3.9 Verify the deployment

1. `https://ims.example.com` loads the SPA.
2. `https://ims.example.com/api/auth/me` returns `401` JSON.
3. Register the owner once (section 1.3).
4. Log in with your store code, username and password.
5. The dashboard loads; Settings → Store shows your store; Settings → Branches shows `MAIN`.
6. Create a test category, tax, item and branch; confirm they persist after a page refresh.
7. `sudo -u postgres psql -d item_management_db -c "SELECT store_code, store_name, status FROM stores;"` shows your store.

### 3.10 Security checklist before handover

- [ ] `SEED_DEMO=false`; no `ABC001` store or demo users.
- [ ] Platform password changed; `platform` / `Admin@123` no longer works.
- [ ] Owner password is strong and stored in a password manager.
- [ ] `JWT_SECRET` is a production secret, not the development default.
- [ ] `ims_app` is not a superuser and has no `CREATEDB`/`CREATEROLE`.
- [ ] PostgreSQL 5432 is not publicly reachable.
- [ ] Backend 8080 is bound to `127.0.0.1`.
- [ ] HTTPS is active; HTTP redirects to HTTPS.
- [ ] `/etc/ims/ims.env` is mode `640`, owned by `root:ims`.
- [ ] A backup job exists and a restore has been tested (3.11).
- [ ] Public registration is either accepted or blocked at Nginx.
- [ ] Only the executable jar and `dist/` are on the server; no source, no `.pgdata`.

### 3.11 Backup and restore (minimum)

Daily logical backup:

```bash
sudo mkdir -p /var/backups/ims
sudo chown postgres:postgres /var/backups/ims
sudo -u postgres pg_dump -Fc item_management_db \
  -f /var/backups/ims/item_management_db_$(date +%F).dump
```

Keep at least 14 daily archives off the server. Test a restore into a scratch database; never test a restore over the live database.

Restore:

```bash
sudo -u postgres createdb -O ims_app ims_restore_test
sudo -u postgres pg_restore --no-owner --no-privileges --exit-on-error \
  -d ims_restore_test /var/backups/ims/item_management_db_YYYY-MM-DD.dump
```

Stop the backend before restoring the live database. PostgreSQL must never be restored while the application is writing to it.

### 3.12 Updating a release

```bash
# upload the new jar and frontend archive, then on the server:
sudo systemctl stop ims-backend

sudo cp /tmp/item-management-backend-1.0.0.jar /opt/ims/app/
sudo rm -rf /opt/ims/web/*
sudo unzip /tmp/ims-frontend.zip -d /opt/ims/web
sudo chown -R ims:ims /opt/ims
sudo chmod -R 755 /opt/ims/web

sudo systemctl start ims-backend
sudo nginx -t && sudo systemctl reload nginx
```

New Flyway migrations (V4, V5, …) run automatically on backend start in Path A. In Path B, apply the equivalent DDL manually before starting the new jar. Always take a backup first.

---

## 4. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `502 Bad Gateway` | backend not running or wrong port | `systemctl status ims-backend`; check `SERVER_PORT` and the Nginx `proxy_pass` port |
| Blank page at `/` or a route refresh 404 | SPA fallback missing | ensure `try_files $uri $uri/ /index.html;` |
| `401` on every API call | missing/expired token | log in again; token lifetime is 12 h |
| `401` at login | wrong store code, username or password | store code is not the store name; use the code recorded in 1.2 |
| `403` "Platform administrators cannot access store business data" | you logged in with the platform account | use the store code and owner account |
| `403` on a store feature | role lacks the permission | check Settings → Roles |
| `409` on registration | store code already registered | choose a different code or log in with the existing one |
| `password authentication failed for user ims_app` | `DB_PASSWORD` differs from script 01 | reset the role password and update `/etc/ims/ims.env` |
| `Connection refused` to 5432 | PostgreSQL not running / not listening | `systemctl status postgresql`; check `listen_addresses` |
| Flyway "non-empty schema without schema history" | manual schema and Flyway both used | do not mix; use Path A on an empty database or keep Flyway disabled |
| App starts but tables are missing | Path B started without running `02` | run `02`, `03`, `04` in order, or switch to Path A |
| `JWT signature does not match` after a restart | `JWT_SECRET` changed | log in again; all old tokens are invalid by design |
| Owner cannot deactivate own account | intentional guard | ask another owner/admin, or use SQL for a planned ownership transfer |

---

## 5. Quick command summary

```bash
# ---------- build on the development machine ----------
npm ci && npm run build
cd backend && ./gradlew clean bootJar

# ---------- server: install ----------
sudo apt update
sudo apt install -y openjdk-17-jre-headless postgresql postgresql-client \
                    nginx certbot python3-certbot-nginx unzip curl openssl ufw

# ---------- server: database ----------
sudo -u postgres psql -f /opt/ims/sql/01_create_database.sql
# Path A: start the backend and let Flyway create all 23 tables
# Path B: psql ... -f 02; -f 03; -f 04  (and set SPRING_FLYWAY_ENABLED=false)

# ---------- server: backend ----------
sudo systemctl daemon-reload
sudo systemctl enable --now ims-backend
sudo journalctl -u ims-backend -f

# ---------- server: frontend ----------
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d ims.example.com

# ---------- owner ----------
# POST https://ims.example.com/api/auth/register-store   (once)
# open https://ims.example.com/login?store=MYSTORE
```

---

## 6. Where the authoritative details live

This runbook is complete for setup. For deeper reference, the repository contains:

| Topic | File |
|---|---|
| All tables, columns, ERD | `docs/02-database.md` |
| Business flows and SQL | `docs/03-business-flows.md` |
| Security model and gaps | `docs/04-security.md` |
| All API endpoints | `docs/05-api-reference.md` |
| PostgreSQL install, migration, production hardening, backup | `docs/06-postgresql-setup.md` |
| Module-by-module development guide | `docs/07-manual-development.md` |
| Known issues and remediation roadmap | `docs/08-known-issues.md` |
| Canonical SQL scripts | `docs/sql/01`–`06` |

The canonical table DDL is **`docs/sql/02_create_tables.sql`**. Do not rewrite it from this document; upload and run that file (Path B) or let Flyway apply V1–V3 (Path A).
