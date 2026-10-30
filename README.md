# Item Management System

A multi-store, multi-branch item management and point-of-sale platform. React + TypeScript on the front end, Spring Boot + PostgreSQL on the back end, with a shared-schema multi-tenant database.

The UI follows the Petpooja POS design language: a persistent left navigation, a dense dashboard with branch KPIs, a fast keyboard-friendly billing screen, and consistent list/detail workflows with inline export and print.

## Feature overview

- **Authentication & sessions** — store-code + username/email login, BCrypt passwords, JWT access tokens, logout with server-side token revocation, automatic session expiry handling in the UI.
- **Store onboarding** — a business self-registers with a store code, owner account, and first branch; platform administrator approval is not required, but the platform can suspend/reactivate stores.
- **Platform console** — platform administrator manages stores, views store metrics, and edits onboarding details. Platform users cannot read store operational data.
- **Roles & permissions** — predefined `STORE_OWNER`, `STORE_ADMIN`, `BRANCH_MANAGER`, `BRANCH_STAFF` roles plus custom roles. Branches are assigned per user; store-wide roles see all branches, branch roles only their assigned branches.
- **Master data** — categories, items (units, pack sizes, purchase/selling prices, minimum stock), suppliers, branches, users.
- **Purchases** — draft → completed → cancelled lifecycle; completing a purchase atomically receives stock and updates weighted-average cost; cancellation reverses stock with an audit movement.
- **Branch inventory** — stock per branch, stock adjustments (in/out), transaction ledger, per-branch selling/purchase price overrides, low-stock alerts.
- **Stock transfers** — draft → approved → dispatched → received (or rejected/cancelled); stock moves out of the source only on dispatch and into the destination only on receipt.
- **POS & sales** — searchable item grid, cart with quantity/price editing, stock guard, payment mode, optional customer details, receipt print. Sales decrement stock atomically; cancellations restore stock as reversal entries.
- **Dashboard** — today's sales/purchases, inventory value, low-stock count, branch performance chart, top sellers, branch stock table, recent activity.
- **Reports** — sales, purchases, stock movement, profit, branch performance, store summary, low stock; filters by date, branch, category, item and text; Excel, PDF and print export.
- **Audit trail** — sensitive operations (auth, master-data changes, stock movements, status changes) are written to `audit_logs`.
- **Tenant isolation** — every query is scoped by the authenticated store; branch users are restricted to assigned branches. The frontend never supplies a trusted `store_id`.

## Tech stack

| Layer | Stack |
| --- | --- |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS v4, React Router, Zustand, Axios, Recharts, lucide-react |
| Exports | SheetJS (Excel), jsPDF + AutoTable (PDF), styled iframe printing |
| Backend | Java 17, Spring Boot 3.3, Spring Security, Spring Data JPA, Flyway, JJWT |
| Database | PostgreSQL (single database `item_management_db`) |
| Build | Gradle wrapper (8.10.2), npm |

## Repository layout

```
.
├── src/                     React application
│   ├── components/          Layout, shared UI, report export toolbar
│   ├── pages/               Route-level screens (lazy loaded)
│   ├── store/               Auth and toast state (Zustand)
│   └── lib/                 API client, formatting, documents, exports
├── backend/
│   ├── src/main/java/       Controllers, services, security, entities, repositories
│   ├── src/main/resources/  application.yml, Flyway migrations
│   └── src/test/java/       End-to-end invariant tests
├── public/
└── vite.config.ts           Dev proxy `/api` → `localhost:8080`
```

## Quick start (development)

Prerequisites: Node.js 20+ and JDK 17. PostgreSQL is **not** required for development — the backend starts an embedded PostgreSQL server automatically.

### 1. Backend

```bash
cd backend
./gradlew bootRun          # Windows: gradlew.bat bootRun
```

On first start the backend:

1. Starts embedded PostgreSQL on port `54329` (data in `backend/.pgdata`).
2. Creates the `item_management_db` database if missing.
3. Runs Flyway migrations from `src/main/resources/db/migration`.
4. Seeds the platform administrator and demo store data (unless `SEED_DEMO=false`).

The API is available at `http://localhost:8080`.

### 2. Frontend

```bash
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` to the backend, so no CORS setup is needed in development.

### Demo accounts

| Scope | Store code | Username | Password |
| --- | --- | --- | --- |
| Platform administrator | `PLATFORM` | `platform` | `Admin@123` |
| Store owner (ABC Dairy) | `ABC001` | `owner` | `Owner@123` |
| Branch manager | `ABC001` | `manager` | `Manager@123` |
| Branch staff | `ABC001` | `staff` | `Staff@123` |
| Second store owner (Sunrise Mart) | `SUN001` | `sunrise.owner` | `Sunrise@123` |

Demo data exists only when `SEED_DEMO=true` (the default in development). Set `SEED_DEMO=false` for a clean production database.

## Configuration

All settings can be provided as environment variables.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port |
| `EMBEDDED_POSTGRES` | `true` | Start the bundled PostgreSQL server (development only) |
| `EMBEDDED_POSTGRES_PORT` | `54329` | Embedded PostgreSQL port |
| `EMBEDDED_POSTGRES_DATA_DIR` | `./.pgdata` | Embedded data directory |
| `EMBEDDED_POSTGRES_CLEAN` | `false` | Wipe the embedded data directory on startup |
| `JWT_SECRET` | dev-only value | **Must be set in production** (32+ random bytes) |
| `JWT_EXPIRATION_MS` | `43200000` (12 h) | Token lifetime |
| `SEED_DEMO` | `true` | Seed demo store/users |
| `CORS_ORIGINS` | `http://localhost:5173,...` | Comma-separated allowed origins |
| `DB_URL` | `jdbc:postgresql://localhost:5432/item_management_db` | External database URL (`postgres` profile) |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | External database credentials |
| `DB_POOL_SIZE` | `15` | Hikari maximum pool size |

## Production deployment

### Database

Create **one** database named `item_management_db`. Do not create a database per store or per branch. Flyway applies migrations automatically on startup.

### Build

```bash
# Backend
cd backend
./gradlew clean bootJar
# → backend/build/libs/item-management-backend-1.0.0.jar

# Frontend
npm ci
npm run build
# → dist/
```

### Run

```bash
SPRING_PROFILES_ACTIVE=postgres \
EMBEDDED_POSTGRES=false \
SEED_DEMO=false \
JWT_SECRET="$(openssl rand -base64 48)" \
DB_URL="jdbc:postgresql://db-host:5432/item_management_db" \
DB_USERNAME=ims \
DB_PASSWORD=change-me \
CORS_ORIGINS="https://ims.example.com" \
java -jar backend/build/libs/item-management-backend-1.0.0.jar
```

Serve `dist/` with any static host or reverse proxy. Route `/api` to the backend and fall back to `index.html` for client-side routes (React Router). Serving the frontend and API on the same origin avoids CORS entirely.

### Operational notes

- Set a strong, unique `JWT_SECRET`; rotating it invalidates all sessions.
- Disable `SEED_DEMO` and `EMBEDDED_POSTGRES` in production.
- Back up the single PostgreSQL database with `pg_dump`; restoration restores all stores.
- Tokens expire after 12 hours and are also rejected immediately when the user or store is deactivated.
- `backend/.pgdata` contains development data. Delete it to reset local demo state.

## Security model

- Passwords are hashed with BCrypt; JWTs are signed with HMAC-SHA and validated on every request.
- The authenticated store and branch scope are derived from the JWT and re-validated against the database. Client-supplied store IDs are ignored.
- Store A can never read or reference Store B data (not-found semantics are used for foreign records).
- Branch-scoped users can only access assigned branches; the API returns 403 for other branches.
- Platform administrators can manage stores but receive 403 on store operational endpoints.
- Method-level permission checks mirror the frontend menu guards; frontend filtering is never the only control.
- Deactivating a user or store revokes existing tokens immediately (checked per request).
- Sensitive operations write audit records containing the actor, store, action and details.

## Inventory & transactional rules

- Stock writes use PostgreSQL transactions; a failure rolls back every related row.
- Branch stock rows are created and locked with `SELECT ... FOR UPDATE` to serialize concurrent updates.
- Sales validate available stock inside the transaction and reject overselling; concurrent tests confirm only one of several competing sales succeeds when stock is low.
- Purchases receive stock only on completion; failed or cancelled drafts never change stock.
- Completed purchases and sales are never deleted; cancellations create reversal movements (`PURCHASE_REVERSAL`, `SALE_REVERSAL`).
- Transfers move stock only on dispatch (out of source) and receive (into destination).
- Negative adjustments are rejected when they would push stock below zero.
- Weighted-average cost is recalculated on purchase completion.

## API overview

All endpoints are under `/api` and require `Authorization: Bearer <token>` except login and store registration.

| Area | Endpoints |
| --- | --- |
| Auth | `POST /auth/login`, `POST /auth/register-store`, `GET /auth/me`, `POST /auth/refresh`, `POST /auth/logout` |
| Stores | `GET /stores`, `POST /stores`, `GET/PUT /stores/current`, `GET/PUT /stores/{id}`, `PUT /stores/{id}/status` |
| Branches | `GET /branches`, `GET /branches/my-branches`, `GET /branches/all`, `GET /branches/{id}`, `POST /branches`, `PUT /branches/{id}`, `PUT /branches/{id}/status` |
| Users | `GET/POST /users`, `GET/PUT /users/{id}`, `PUT /users/{id}/status`, `POST /users/{id}/branches`, `GET /users/roles/available` |
| Roles | `GET/POST /roles`, `PUT /roles/{id}`, `GET /permissions` |
| Categories | `GET/POST /categories`, `PUT /categories/{id}`, `PUT /categories/{id}/status`, `GET /categories/options` |
| Items | `GET/POST /items`, `GET/PUT /items/{id}`, `PUT /items/{id}/status`, `GET /items/options`, `GET /items/pos`, `GET /items/{id}/stock`, `GET /items/{id}/transactions` |
| Suppliers | `GET/POST /suppliers`, `GET/PUT /suppliers/{id}`, `PUT /suppliers/{id}/status`, `GET /suppliers/options`, `GET /suppliers/{id}/purchases` |
| Purchases | `GET/POST /purchases`, `GET/PUT /purchases/{id}`, `POST /purchases/{id}/complete`, `POST /purchases/{id}/cancel` |
| Inventory | `GET /inventory`, `GET /inventory/low-stock`, `GET /inventory/transactions`, `POST /inventory/adjustments`, `PUT /inventory/{branchId}/items/{itemId}/pricing` |
| Transfers | `GET/POST /transfers`, `GET /transfers/{id}`, `POST /transfers/{id}/approve|dispatch|receive|reject|cancel` |
| Sales | `GET/POST /sales`, `GET /sales/{id}`, `POST /sales/{id}/cancel` |
| Reports | `GET /reports/{sales|purchases|stock|low-stock|branches|store|profit}` |
| Dashboard | `GET /dashboard` |

List endpoints accept pagination (`page`, `size`) and module-specific filters; responses use `{ content, page, size, totalElements, totalPages }`.

## Testing

The backend test suite boots the application against embedded PostgreSQL on port `54330` and exercises real HTTP endpoints.

```bash
cd backend
./gradlew test
```

Coverage includes authentication failures, cross-store isolation, platform boundaries, purchase/sale/transfer lifecycles, overselling protection under concurrent sales, negative-stock rejection, duplicate line validation, payment limits, branch/permission scoping, and immediate token revocation after deactivation.

Frontend checks:

```bash
npm run lint
npm run build      # TypeScript project build + production bundle
```

## Troubleshooting

- **Port 8080 or 54329 already in use** — stop the previous backend process or override `SERVER_PORT` / `EMBEDDED_POSTGRES_PORT`.
- **Flyway validation failure** — the schema must match the migrations; do not point `ddl-auto` at `update`. Restore from backup or recreate a development database (`EMBEDDED_POSTGRES_CLEAN=true`).
- **Login says store is inactive** — a platform administrator must reactivate the store in the platform console.
- **Session expires immediately after deactivation** — expected: tokens are re-validated against user/store status on every request.