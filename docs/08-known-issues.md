# 24. Known Issues and Missing Features

> **Superseded 2026-11-01.** Many issues listed below (hard-coded admin seeding, in-memory JWT
> revocation, no login throttling, `localStorage` tokens, no self-service password change, no audit
> viewer, no health endpoint, lint warnings, paging bounds) have been implemented or explicitly
> deferred. Use `PROJECT_DOCUMENTATION/KNOWN_ISSUES_AND_RECOMMENDATIONS.md` for the current
> K-01–K-27 status and evidence. This file is retained for historical context.

**Document status:** review of the current repository as of 2026-10-30.
**Scope:** defects, security gaps, accounting limitations and missing capabilities found by reading the source, migrations, configuration and tests. No source, schema or configuration was changed to produce this list.

This section separates:

- **Defect** — the code does something unsafe, incorrect or inconsistent with the stated requirement.
- **Limitation** — the behaviour is deliberate but narrower than a production operator might expect.
- **Missing feature** — no code path exists; do not assume a hidden endpoint.

Severity is about production risk, not implementation effort.

| Severity | Meaning |
|---|---|
| **P0 — Blocker** | Must be fixed before any non-local or multi-user deployment. Security or financial integrity is at risk. |
| **P1 — High** | Fix before production or before relying on the affected workflow. |
| **P2 — Medium** | Fix in the first hardening/feature release; workarounds exist. |
| **P3 — Low** | Quality, usability or operational improvement. |

---

## 24.1 Production-blocking security issues (P0)

### SEC-01 — A store role can grant itself `PLATFORM_MANAGE` and reach other tenants

**Evidence**

- `UserService.createRole` and `updateRole` accept any permission codes and attach whatever exists in the catalogue: `backend/src/main/java/com/itemmanagement/service/UserService.java:151-180`.
- `GET /api/permissions` returns the whole catalogue, including `PLATFORM_MANAGE`: `UserService.permissions()` at `UserService.java:144`.
- Store platform endpoints check only the permission, not whether the caller is a platform user: `backend/src/main/java/com/itemmanagement/controller/StoreController.java:22-64`.
- `StoreService.list/getForPlatform/platformUpdate/updateStatus` are not tenant-scoped; they operate on all stores by design for platform users.

**Attack path**

1. A store owner (who has `ROLE_MANAGE`) creates a role with `PLATFORM_MANAGE` (or adds it to an existing role).
2. Assigns that role to a user via `POST/PUT /api/users`.
3. Refreshes/logs in so the JWT contains `PLATFORM_MANAGE`.
4. Calls `GET /api/stores`, `PUT /api/stores/{id}`, `PUT /api/stores/{id}/status` and reads or disables **other tenants'** stores.

The seeded store roles do not include `PLATFORM_MANAGE`, so the default configuration is not directly exposed. The defect is that the API allows the grant and the store endpoints do not verify the platform boundary.

**Impact:** cross-tenant data exposure and denial of service (disabling another store). This contradicts the SRS requirement "Store A must never access Store B data."

**Remediation**

1. Reject `PLATFORM_MANAGE` (and any future platform-only code) when creating/updating a store role; allow it only for `store_id IS NULL` roles.
2. Filter the permission catalogue returned to store administrators, or mark platform-only codes and hide them.
3. In every platform store endpoint, require `TenantContext.user().storeId() == null` in addition to `@PreAuthorize("hasAuthority('PLATFORM_MANAGE')")`. Do not rely on the permission alone.
4. Add an integration test: store owner creates a role with `PLATFORM_MANAGE` → `400`; direct `GET /api/stores` with a store token → `403` even if the permission was somehow granted.

**Do not** simply remove the permission from the UI; the API must enforce it.

### SEC-02 — No role rank: `USER_MANAGE` can assign `STORE_OWNER`

**Evidence**

- `UserService.create`/`update` resolve the requested role with `role(storeId, request.roleId())`, which only checks that the role belongs to the store: `UserService.java:61,82,202-204`.
- There is no comparison between the actor's role and the target role, and no allow-list of assignable roles.

**Impact:** a `STORE_ADMIN` or any custom role with `USER_MANAGE` can assign `STORE_OWNER` to itself or another user, then use owner-only permissions. This is privilege escalation inside a tenant.

**Remediation**

1. Define a role rank (`PLATFORM_ADMIN` > `STORE_OWNER` > `STORE_ADMIN` > `BRANCH_MANAGER` > `BRANCH_STAFF`; custom roles need an explicit rank or "cannot exceed creator").
2. An actor may assign only roles at or below their rank, and may not edit a user whose rank is above theirs.
3. Prevent removing the last owner / last `ROLE_MANAGE` holder.
4. Add tests for self-promotion, promoting another user above the actor, and demoting the last owner.

### SEC-03 — Default database credentials are `postgres` / `postgres`

**Evidence:** `backend/src/main/resources/application.yml:50-53`:

```yaml
url: ${DB_URL:jdbc:postgresql://localhost:5432/item_management_db}
username: ${DB_USERNAME:postgres}
password: ${DB_PASSWORD:postgres}
```

The `postgres` profile therefore starts as the superuser with a known password if the environment variables are not set. This directly violates the operating rule "Do not use the PostgreSQL superuser account for the application."

**Impact:** if a deployment forgets `DB_USERNAME`/`DB_PASSWORD`, the application runs with superuser privileges and a trivial password. A network-exposed misconfiguration becomes a full cluster compromise.

**Remediation**

1. Remove the credential defaults from the `postgres` profile. Make `DB_USERNAME` and `DB_PASSWORD` required (fail fast at startup), or default to `ims_app` with no password and fail if absent.
2. Use `ims_app` (created by `docs/sql/01_create_database.sql`), never `postgres`.
3. Add a startup check that rejects `postgres` as the application user in the `postgres` profile.
4. Store the password in a secret manager or environment injected by the deployment system; never in source.

### SEC-04 — Development JWT secret is the default

**Evidence:** `application.yml:28`:

```yaml
secret: ${JWT_SECRET:ims-dev-secret-key-change-me-0123456789-abcdefghijklmnopqrstuvwxyz}
```

If `JWT_SECRET` is not set, every deployment uses the same publicly known secret. An attacker can forge tokens with arbitrary `storeId`, roles and permissions. `JwtService` derives the HMAC key from this string, so the default is directly usable.

**Impact:** complete authentication bypass, including cross-tenant access.

**Remediation**

1. Remove the default; require `JWT_SECRET` in non-development profiles and fail startup if missing or shorter than 32 bytes.
2. Generate a random secret per environment and store it in a secret manager; rotate on a schedule and after suspected compromise.
3. Consider key rotation with a `kid` header; the current implementation has a single key.
4. Add a test that the `postgres` profile refuses to start without `JWT_SECRET`.

### SEC-05 — Seeded platform administrator with a known password

**Evidence:** `backend/src/main/java/com/itemmanagement/config/DataSeeder.java:31-45` creates `PLATFORM` / `platform` / `Admin@123` if the user is absent. The root `README.md` documents the same credentials.

**Impact:** anyone who knows the default can sign in as the platform administrator on a fresh deployment and manage/disable stores. Changing the password after the first login is a manual step that is easy to miss.

**Remediation**

1. Require a `PLATFORM_ADMIN_PASSWORD` (or a one-time setup token) on first start; do not hardcode a password.
2. Or create the platform administrator only through a deliberate CLI/SQL step, with `SEED_DEMO=false` and a forced password change.
3. Add a "must change password" flag and block other platform actions until it is cleared.
4. Document the change in the deployment checklist and test that a fresh database has no known default password.

### SEC-06 — Demo data is seeded by default

**Evidence:** `application.yml:36` sets `seed-demo: ${SEED_DEMO:true}`. `DemoDataSeeder` creates `ABC001` / ABC Dairy, branches, users, categories, items, suppliers, opening stock and ledger rows.

**Impact:** a production deployment that forgets `SEED_DEMO=false` contains a working demo tenant with known credentials (`owner` / `Owner@123`, `manager` / `Manager@123`, `staff` / `Staff@123`) and fake business history. It pollutes reports and is a security hole.

**Remediation**

1. Change the default to `false`; require an explicit opt-in for demos.
2. Refuse to seed demo data when a non-development profile is active.
3. Add a deployment test that asserts `SELECT count(*) FROM stores WHERE store_code = 'ABC001'` is zero in production.

### SEC-07 — Access token in `localStorage`; no explicit CSP/security headers

**Evidence**

- The frontend persists the auth state (including token) under `ims-auth` in `localStorage`: `src/store/auth.ts`, `src/lib/api.ts`.
- `SecurityConfig` disables CSRF and configures CORS but does not add a Content-Security-Policy or other explicit security headers: `backend/src/main/java/com/itemmanagement/config/SecurityConfig.java:42-57`.

**Impact:** any XSS in the app can read the token and impersonate the user for up to 12 hours. `localStorage` is accessible to all scripts on the origin; CSP is the main mitigation and is not configured here.

**Remediation**

1. Move the token to an `HttpOnly`, `Secure`, `SameSite` cookie, or keep `localStorage` and add a strict CSP (`default-src 'self'`, no inline scripts, explicit connect-src), `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'` and HSTS at the reverse proxy.
2. Shorten the access-token lifetime (e.g. 15–30 minutes) and add a refresh-token flow with rotation; the current "refresh" requires a still-valid access token and issues another 12-hour token.
3. Sanitize/escape all rendered user data; audit any `dangerouslySetInnerHTML` usage (none was found, but keep it that way).
4. Add a CSP report-only rollout and test the POS/print flows.

### SEC-08 — In-memory token blacklist; no multi-instance logout

**Evidence:** `backend/src/main/java/com/itemmanagement/security/TokenBlacklistService.java:12` stores revoked `jti` values in a `ConcurrentHashMap`. There is no scheduled purge; `purgeExpired()` is never called from the application. On restart the map is empty. With more than one application instance, a logout on instance A is unknown to instance B.

**Impact:** a "logged out" token can still be accepted after a restart or on another node; memory grows with revoked tokens until the JVM restarts; horizontal scaling weakens logout/refresh revocation.

**Remediation**

1. Store revocations in PostgreSQL (a `revoked_tokens` table with `jti`, `expires_at`, indexed) or a shared cache (Redis) with TTL.
2. Or add a `token_version`/`auth_version` column to `users`; include it in the JWT and compare on each request. Changing the password or forcing logout increments the version.
3. Schedule `purgeExpired()` or rely on the store's TTL.
4. Add a two-instance test: logout on A, call B with the same token → `401`.

### SEC-09 — No rate limiting, account lockout or CAPTCHA

**Evidence:** `AuthController` and `SecurityConfig` have no throttling; there is no failed-attempt counter. Login failures return a generic `401` but can be retried indefinitely. Store registration is public (`SecurityConfig.java:48`) and unauthenticated.

**Impact:** password spraying/brute force against `PLATFORM` or store users; automated creation of many stores; resource exhaustion via repeated registration (each registration creates roles, tax slabs, a branch and a user).

**Remediation**

1. Add per-IP and per-account rate limits at the reverse proxy/gateway, plus an application-level login attempt counter with exponential backoff and temporary lockout.
2. Add CAPTCHA or an invitation-only flow for public store registration; consider disabling public registration entirely for private deployments.
3. Log failed logins (see SEC-13) and alert on spikes.
4. Test that `429`/lockout is returned after the configured threshold and that a successful login resets the counter.

### SEC-10 — Weak password lifecycle

**Evidence**

- Password validation is length 8–72 only (DTO/service); no complexity, breached-password check or history.
- `BCryptPasswordEncoder()` uses the default strength 10 (`SecurityConfig.java:60-62`), which is acceptable but should be reviewed for the deployment's threat model.
- There is no self-service password change or reset endpoint; an administrator edits the user. The seeded platform password is known (SEC-05).
- Changing a password does not revoke existing JWTs (see SEC-11).

**Impact:** weak passwords are accepted; users cannot rotate credentials without an administrator; a compromised password remains usable in existing tokens.

**Remediation**

1. Add `POST /api/auth/change-password` (current password + new password) and a secure reset flow (single-use, expiring token; no password in e-mail).
2. Enforce a minimum length (12+), a breached-password check and optionally MFA for platform/owner accounts.
3. On password change, invalidate all tokens via `token_version` or a revocation store.
4. Add tests for wrong current password, weak new password, reuse/history policy and token invalidation.

---

## 24.2 Authorization and session limitations (P1/P2)

### SEC-11 — Stale authorization claims in existing JWTs

**Evidence:** `JwtAuthFilter.isActive` reloads the user and store to check active status and store id, but authorities are built from the token's `roles`/`permissions` (`JwtAuthFilter.java:44-55,64-76`). Branch ids also come from the token; `AuthUser.canAccessBranch` uses them. `refresh` re-reads roles/permissions/branches, but only if the user calls it.

**Impact**

- Removing a permission/role or a branch assignment does not take effect until login/refresh; the old token retains access.
- Deactivating the user or store is immediate (checked on every request).
- A long 12-hour expiry widens the window.

**Remediation:** re-read roles/permissions/branches from the database on each request (costly but simple), or add an `auth_version` claim and bump it when roles/branches change. Shorten the access-token TTL and use refresh rotation. Add tests for role removal and branch-assignment removal with an existing token.

### SEC-12 — Password change does not revoke tokens

**Evidence:** `UserService.update` re-hashes a supplied password but does not touch the blacklist or any token version. `JwtAuthFilter` does not compare a password-change timestamp.

**Impact:** a stolen token remains valid after the victim changes their password (until the 12-hour expiry).

**Remediation:** include `passwordChangedAt` or `authVersion` in the token and reject tokens issued before the last change; or revoke all of the user's active `jti`s (requires a persistent revocation store, SEC-08).

### SEC-13 — No audit read API, no failed-login audit

**Evidence**

- `AuditService.logFor` is called on successful login (`AuthService.java:83`), registration and business changes. Failed logins return before the audit call.
- `AuditLogRepository` only exposes `findByStoreIdOrderByCreatedAtDesc`; no controller uses it. There is no audit page or export.
- `AuditService.persist` catches and logs failures (`AuditService.java:35-49`), so an audit write can be lost silently; `logFor` uses `REQUIRES_NEW`, so an audit row may survive an outer transaction rollback.

**Impact:** no in-app investigation trail; brute-force attempts are invisible in the application audit; audit completeness is not guaranteed; direct SQL access is required to read logs.

**Remediation**

1. Add `GET /api/audit-logs` (paged, store-scoped, `AUDIT_VIEW` permission) and optionally a CSV export.
2. Audit failed logins (with a hashed/truncated username or at least a reason), permission denials and token refusals, without logging passwords/tokens.
3. Decide whether audit must be transactional with the business change; if yes, remove the catch or write to an outbox. If best-effort is acceptable, document it and monitor the warning log.
4. Protect audit rows from deletion/update (append-only grants or a separate schema) and define retention.

### SEC-14 — CORS and error-detail defaults

**Evidence**

- Allowed origins default to `localhost:5173/127.0.0.1:5173/localhost:4173` (`application.yml:38`). A production frontend on another origin fails until `CORS_ORIGINS` is set.
- `server.error.include-message: always` (`application.yml:24`) can expose framework error text. `GlobalExceptionHandler` returns a generic 500 message for unhandled exceptions, but the setting should still be reviewed.
- `SecurityConfig` exposes the `Authorization` response header unnecessarily (`SecurityConfig.java:74`).

**Impact:** misconfigured production CORS breaks the UI; error details may leak internals; a broad `*` origin set by an operator would be dangerous with bearer tokens.

**Remediation:** require an explicit production `CORS_ORIGINS` allow-list (no wildcard), set `include-message: never` in production, remove the unnecessary exposed header, and add a startup check/warning for default origins.

### SEC-15 — No database row-level security / defense in depth

**Evidence:** tenant isolation is enforced by application queries plus composite unique/foreign keys `(store_id, id)`. PostgreSQL RLS is not enabled. The application role owns the schema, so a SQL injection or a missed `storeId` filter is not contained by the database.

**Impact:** a single missed filter becomes a cross-tenant leak (see the escalation in SEC-01 for a related path).

**Remediation (recommended, after fixing application gaps):** add RLS policies keyed on a session variable set from the authenticated store, and a separate role for reporting. This is a defense-in-depth measure, not a substitute for correct queries. Test with a deliberately unfiltered query.

### SEC-16 — No TLS, HSTS or reverse-proxy configuration in the repository

**Evidence:** the Spring Boot app serves HTTP on 8080; there is no TLS configuration or sample reverse proxy. The SRS requires secure token handling, but transport security is a deployment concern.

**Impact:** bearer tokens and passwords travel in clear text if deployed without TLS.

**Remediation:** terminate TLS at a reverse proxy or load balancer, redirect HTTP to HTTPS, enable HSTS, and ensure the frontend uses HTTPS. If TLS terminates at Spring Boot, configure a keystore and `server.ssl.*`. Document the chosen topology in the deployment runbook.

### SEC-17 — Long-lived access token / no separate refresh token

**Evidence:** `JWT_EXPIRATION_MS` defaults to `43200000` (12 hours); `POST /api/auth/refresh` requires a valid bearer token and issues another access token. There is no refresh-token table, rotation or reuse detection.

**Impact:** a stolen token is usable for 12 hours; refresh cannot extend a session after expiry (the user must log in again); logout is the only revocation.

**Remediation:** short-lived access tokens (15–30 min) plus a rotating refresh token stored server-side (hashed), absolute and idle timeouts, reuse detection, and revocation on password change/logout. Keep the JWT small; do not put the full permission list in a long-lived token.

### SEC-18 — Public store registration can be abused

**Evidence:** `POST /api/auth/register-store` is `permitAll` (`SecurityConfig.java:48`) and performs a multi-table provisioning transaction. There is no invitation, domain restriction, CAPTCHA or rate limit.

**Impact:** automated creation of many tenants (storage/CPU), fake stores, and potential confusion in the platform store list.

**Remediation:** make registration invitation-only or platform-admin-only for private deployments; otherwise add rate limiting, CAPTCHA, e-mail verification and a per-IP/tenant quota. Audit the source IP.

---

## 24.3 Data integrity and accounting limitations (P1/P2)

### DATA-01 — Completed purchase cancellation leaves the weighted average cost inconsistent

**Evidence:** `PurchaseService.cancel` calls `applyStockOut(..., PURCHASE_REVERSAL, ...)` (`PurchaseService.java:190-218`). `applyStockOut` decreases quantity but does **not** recompute `avg_cost` (`InventoryService.java:230-247`). At completion, `applyStockIn` set `avg_cost` to the weighted average including the purchase.

**Example:** stock 10 @ 40 (value 400); purchase 10 @ 60 → stock 20, avg 50. Sell none; cancel the purchase → stock 10, avg **still 50**, value implied 500 instead of 400. The inventory valuation is wrong even though the quantity is right.

**Impact:** profit reports (COGS = quantity × costRate), stock value reports and subsequent purchase averages are distorted after a purchase cancellation. This is a real accounting defect, not just a UX issue.

**Remediation**

1. Decide the intended costing policy. With weighted average, a reversal must restore the prior average or recompute from the remaining layers.
2. Implement either (a) a cost layer/ledger that can reverse the exact receipt, or (b) a documented "reverse at current average" policy with a compensating adjustment and clear reporting. Do not silently leave the average wrong.
3. Add a test: purchase at two different costs, cancel the second, assert quantity **and** average cost/value.
4. Until fixed, restrict purchase cancellation to drafts (which have no stock impact) or require a manager override with an explicit warning.

### DATA-02 — Transfer lines have no cost snapshot; receive uses the source average at receive time

**Evidence:** `StockTransferDetail` has no cost/rate column. `TransferService.receive` reads the source `BranchInventory.avgCost` at receive time (`TransferService.java:186-202`) and passes it to `applyStockIn` at the destination.

**Impact:** if the source receives another purchase between dispatch and receive, the destination is valued at the new average, not the cost at dispatch. The transfer is not a faithful movement of value. A source with zero stock/avg cost can transfer at cost 0.

**Remediation:** snapshot the source average cost (and ideally the valuation) on each transfer line at dispatch; receive at that snapshot. Add a column via a new migration (`V4`), backfill where possible, and test a cost change between dispatch and receive.

### DATA-03 — No in-transit loss, partial receipt or line-level resolution for transfers

**Evidence:** the state machine allows `DISPATCHED → RECEIVED` only; `receive` processes every line fully. There is no "lost/damaged in transit" adjustment tied to the transfer. A dispatched transfer cannot be cancelled (`TransferService.cancel` rejects it).

**Impact:** if goods are lost or partially received, the operator must use a separate adjustment, which is not linked to the transfer; stock and accountability diverge. The destination cannot reject a partial shipment.

**Remediation:** add partial receipt quantities, a `LOST_IN_TRANSIT` adjustment type linked to the transfer, and a resolution state. Keep the ledger immutable and audit the resolution. Test over/under receipt and loss.

### DATA-04 — No purchase/sales returns, refunds or credit notes

**Evidence:** only whole-document cancellation exists (`POST /api/purchases/{id}/cancel`, `POST /api/sales/{id}/cancel`). There is no return document, no partial return, no refund method, no credit note, and no link from a return to the original line.

**Impact:** partial returns and refunds cannot be represented correctly; cancelling a whole sale restores all stock and reverses the entire revenue, which is often wrong. Customer/supplier balances are not tracked.

**Remediation:** design return documents (header + lines + reason + stock disposition + refund/credit), with links to the original document and immutable snapshots. Add permissions (`SALES_RETURN`, `PURCHASE_RETURN`), UI, reports and tests. Do not implement returns by editing the original document.

### DATA-05 — No payments, receipts or settlement

**Evidence**

- `sales` has `payment_method`, `paid_amount`, `balance_amount`; `purchases` has `paid_amount`/`balance_amount`. There is no `payments` table, no payment date/reference, no split tender, no change, no cash drawer, no settlement.
- The SRS documentation asks to explain the absence of a payments table; this is that explanation: the current model stores only summary amounts on the document.

**Impact:** partial payments, multiple tender types, refunds, supplier payments and reconciliation cannot be represented. `balance_amount` is a derived field, not a ledger.

**Remediation:** add a `payments` table (store, branch, document type/id, method, amount, reference, received_at, created_by) and a payment service. For POS, support split tender and change; for purchases, support supplier payments. Reports should reconcile payments to documents. This is a significant feature, not a schema tweak.

### DATA-06 — No opening stock API

**Evidence:** opening stock exists only in the demo seeder and `docs/sql/06_sample_business_flow.sql` (transaction type `OPENING_STOCK`). There is no endpoint or UI to enter opening balances.

**Impact:** a real store cannot initialize inventory through the application. Operators may be tempted to edit `branch_inventory` directly, which breaks the ledger invariant (script 05 would flag it).

**Remediation:** add an opening-stock document/import with a date, branch, item, quantity, unit cost and reason; post `OPENING_STOCK` ledger rows and set the average cost. Restrict it to a one-time/approved permission and audit it. Until then, use a controlled adjustment with a clear reason and a documented procedure.

### DATA-07 — Branch purchase/selling prices are informational and not used by transactions

**Evidence:** `branch_inventory` stores `purchase_price`, `selling_price` and `minimum_stock`; `PUT /api/inventory/{branchId}/items/{itemId}/pricing` updates them. Normal sales use the request's manual `sellingRate`; POS uses `items.rate`; purchases use the request's `purchaseRate`; weighted cost uses `avg_cost`. Only `minimum_stock` affects low-stock queries.

**Impact:** users may believe branch pricing controls billing, but it does not. Changing it has no effect on sales or POS. Reports may show a price that differs from the posted rate.

**Remediation:** either remove the fields from the UI/API or make the semantics explicit ("reference prices only") and use them for defaults/validation. If branch pricing should be authoritative, define precedence (branch > item master) and apply it server-side in normal sales/POS, with tests.

### DATA-08 — GST report is output-only; no input tax credit or purchase GST report

**Evidence:** `ReportService.gstReport` aggregates sales line snapshots. There is no purchase GST/ITC report, no HSN summary, no taxable/non-taxable reconciliation and no filing export.

**Impact:** the report supports output-tax review, not GST return preparation or ITC reconciliation.

**Remediation:** add a purchase GST/ITC report (input CGST/SGST/IGST), a combined output-input summary, HSN/SAC summary, and a filing-period view. Keep using snapshots. Clarify in the UI that the current report is sales-only.

### DATA-09 — No HSN/SAC, customer GSTIN, e-invoice or B2B invoice fields

**Evidence:** `items` has no HSN/SAC column; `sales` has customer name/mobile/state but no customer GSTIN or address; there is no e-invoice IRN/QR field. Supplier GSTIN exists but is only length-validated.

**Impact:** B2B GST invoices and e-invoicing requirements cannot be met with the current data model.

**Remediation:** add HSN/SAC to items, customer legal name/GSTIN/address to sales (or a customer master), and e-invoice fields if required by turnover. Validate GSTIN format/checksum. This needs legal/accounting input, not just code.

### DATA-10 — Profit report is a snapshot estimate, not an accounting valuation

**Evidence:** revenue = `totalAmount − tax`; COGS = `baseQuantity × costRate` (`sales_details.cost_rate` snapshot). `costRate` is the branch average cost at sale time. Opening stock without a cost, or stock received via a path that leaves average cost zero, produces zero COGS and overstated profit.

**Impact:** profit is directional, not a substitute for accounting. Cancelled documents are excluded, but cost layers/FIFO and writedowns are not modeled.

**Remediation:** document the definition in the report UI; ensure every inbound movement sets a defensible cost (including opening stock); consider cost layers/FIFO if the business requires it. Add a reconciliation report between inventory value and ledger cost.

### DATA-11 — No idempotency; double submission creates duplicate documents

**Evidence:** none of the create endpoints accept an idempotency key or enforce a unique client request id. A double-click/retry of `POST /api/purchases` or `/api/sales` creates two documents and two stock movements. The numbers are unique, so the duplicate is not detectable by number.

**Impact:** duplicate purchases inflate stock and payables; duplicate sales reduce stock and inflate revenue. Mobile/flaky networks make this likely.

**Remediation:** accept an `Idempotency-Key` header, store it with the response (per tenant/user) and return the original response on replay. Enforce a unique constraint on `(store_id, idempotency_key)`. For POS, disable the button while posting and show a clear retry state, but do not rely on the UI.

### DATA-12 — Inconsistent lock ordering can deadlock multi-line purchases/sales

**Evidence**

- `PurchaseService.applyStock` iterates request lines in request order and calls `lockOrCreate` per item (`PurchaseService.java:220-233`); `create` also locks in request order (`:200-210`).
- `SaleService.create` iterates request lines (`SaleService.java:97-...`) and locks in request order (`:220`).
- `TransferService` explicitly sorts details by item id before locking (`TransferService.java:159,186`), which is the correct pattern.

**Impact:** two concurrent documents with items A,B and B,A can each hold one row lock and wait for the other → PostgreSQL deadlock (`40P01`), one request fails with a 500/500-style error, and retries can duplicate work if not idempotent.

**Remediation:** sort lines by item id (and branch, if multiple) before acquiring locks in every service, or acquire all locks in a canonical order. Add a deadlock retry with a bounded number of attempts and a clear error. Test with two threads and reversed line order.

### DATA-13 — No optimistic locking on master/document edits

**Evidence:** entities do not use `@Version`. Two users editing the same draft purchase, item, category or store can overwrite each other (last write wins).

**Impact:** silent data loss on concurrent edits; the stock path is protected by row locks, but master data and draft documents are not.

**Remediation:** add `@Version` to editable aggregates (or a `updated_at` conditional update), return `409` on conflict, and refresh the form. Test two concurrent updates.

### DATA-14 — `items.item_code` annotation says globally unique, database says per store

**Evidence:** `Item.java:33` declares `@Column(..., unique = true)`, while `V1__init.sql:112` defines `CONSTRAINT uq_items_code UNIQUE (store_id, item_code)`.

**Impact:** `ddl-auto=validate` does not check unique constraints, so the mismatch is invisible today. If anyone changes `ddl-auto` to `create/create-drop` or generates DDL from the entity, the schema becomes globally unique and two stores cannot use the same code. It also misleads reviewers.

**Remediation:** remove `unique = true` from the entity column and express the per-store uniqueness with a table-level unique constraint if the entity is used for DDL. Keep the database constraint as the source of truth. Add a test that two stores can use the same item code and the same store cannot duplicate it.

### DATA-15 — `customers` table and `sales.customer_id` are unused

**Evidence:** `Customer`/`CustomerRepository` exist and `V1__init.sql` creates `customers`; `Sale` has a nullable `customerId`, but `SaleService` sets only `customerName`, `customerMobile` and `customerState` (`SaleService.java:201-203`). `CustomerRepository` is not used by the sale flow.

**Impact:** there is no customer master, purchase history, loyalty or repeat-customer lookup. The table is dead weight and can confuse schema readers. `sales.customer_id` is always null.

**Remediation:** either implement customer lookup/create (by mobile) and set `customer_id`, with a UI and privacy policy, or remove the table/column in a deliberate migration. Do not leave a half-modeled relationship.

### DATA-16 — Purchase invoice numbers are not unique

**Evidence:** `purchases.invoice_number` is nullable `VARCHAR(80)` with no unique constraint (`V1__init.sql:208`); only `purchase_number` is unique per store.

**Impact:** the same supplier invoice can be entered twice, causing duplicate stock/payables without detection.

**Remediation:** add a per-store (and ideally per-supplier) uniqueness rule after cleaning existing duplicates, or warn on duplicate invoice numbers at entry. A unique constraint needs a data cleanup and a business decision about credit notes.

### DATA-17 — Transfer does not update the destination `purchase_price`

**Evidence:** `applyStockIn` sets `purchase_price` only for `PURCHASE_IN` (`InventoryService.java:220-222`). `TRANSFER_IN` updates quantity and average cost but leaves the destination's informational `purchase_price` unchanged.

**Impact:** the destination's displayed purchase price can be stale, misleading users even though `avg_cost` is correct for COGS.

**Remediation:** either update the reference price on transfer-in (documented as a reference, not the valuation) or remove the field from the UI. Do not use it for costing.

### DATA-18 — Cancellation is a status reversal, not a financial reversal

**Evidence:** purchase/sale cancellation changes status and posts `PURCHASE_REVERSAL`/`SALE_REVERSAL` stock movements. There is no accounts-receivable/payable ledger, no payment reversal, no refund, no credit note, and no cancellation reason/approver field.

**Impact:** the stock quantity is corrected, but money owed/paid and the audit narrative are not. A cancelled credit sale still has no payment trail; a cancelled purchase has no supplier debit note.

**Remediation:** add cancellation reason, approver and timestamp; link to payment/refund records; decide whether cancellation is allowed after payment and what the financial entries are. Keep the stock reversal separate from the financial reversal.

### DATA-19 — No batch/expiry/serial tracking

**Evidence:** `items` has no batch, expiry or serial fields; inventory and sale lines are item-level only. For a dairy-inspired demo this is a material gap.

**Impact:** FEFO/FIFO by batch, expiry alerts, recalls and serialized goods cannot be supported. Average cost can hide expired stock.

**Remediation:** add item tracking policy (`NONE`/`BATCH`/`SERIAL`), batch tables, expiry dates, and batch-aware stock/ledger/sale allocation. This is a substantial design change; do it before importing real batch data.

### DATA-20 — Single item rate; no price tiers or customer-specific pricing

**Evidence:** `items.rate` is the only master price. Purchases/sales accept manual rates; POS forces `items.rate`. There is no wholesale/retail tier, no customer price list, no effective-dated price.

**Impact:** businesses with multiple price levels must enter rates manually for normal sales; POS cannot apply a customer tier.

**Remediation:** add price lists/effective dates or customer-specific rates, define precedence and snapshot the chosen rate on the line. Keep the POS server-side rate rule (never trust the client).

### DATA-21 — No store timezone/business-day configuration

**Evidence:** the app uses UTC (`hibernate.jdbc.time_zone: UTC`); "today" in the dashboard and default report dates use the server date. Stores have no timezone field.

**Impact:** a store in a non-UTC timezone sees today's KPIs/reports shifted; a sale near midnight can fall on the wrong business day.

**Remediation:** add a store timezone and a business-day cutoff, and compute "today" per store. Store timestamps remain UTC; only the reporting boundary changes. Document the behavior.

### DATA-22 — Rounding and currency are fixed

**Evidence:** money is `BigDecimal` with HALF_UP at 2 decimals (cost at 4); tax split uses HALF_UP; currency is implicit INR; no rounding configuration.

**Impact:** jurisdictions/businesses with different rounding or currency rules cannot configure them; minor paise differences can appear versus external accounting.

**Remediation:** if needed, add a store-level rounding policy and currency code; otherwise document INR/HALF_UP explicitly in reports and invoices.

---

## 24.4 Missing business capabilities (P1–P3)

| ID | Severity | Missing capability | Current behavior / consequence | Recommended direction |
|---|---|---|---|---|
| FEAT-01 | P1 | POS held bills, split tender, change, cash drawer/session | A sale posts immediately; one `payment_method`; no held carts or shift close | Add held bills, tender lines, change calculation, cash session open/close and Z-report |
| FEAT-02 | P2 | Barcode scanning | Item code is manual; no barcode field or scanner input | Add barcode/EAN per item, scanner-friendly search, label printing |
| FEAT-03 | P2 | Offline POS | Requires the API; a network drop blocks billing | Evaluate an offline queue with conflict handling; do not fake stock locally |
| FEAT-04 | P2 | Direct receipt-printer integration | Bills are printed via the browser/PDF | Add ESC/POS or a print agent; keep the PDF fallback |
| FEAT-05 | P2 | Customer master, history, loyalty | `customers` exists but is unused; sales store name/mobile/state only | Implement lookup/create, history, opt-in loyalty, privacy controls |
| FEAT-06 | P2 | Purchase orders and approvals | Purchase starts as DRAFT/COMPLETED; no PO or approval chain | Add PO, approval, partial receipt; keep stock posting at receipt |
| FEAT-07 | P2 | Stock take / cycle count | Only manual adjustments | Add count documents, variance approval, adjustment posting |
| FEAT-08 | P1 | Batch/expiry tracking | Item-level stock only | See DATA-19 |
| FEAT-09 | P2 | Low-stock alerts/notifications | Low-stock page only; no push/e-mail | Add scheduled alerts per store/branch with thresholds |
| FEAT-10 | P3 | Multi-warehouse/bin locations | Branch-level inventory only | Add locations/bins if operations require them |
| FEAT-11 | P3 | Attachments (invoice scans, documents) | No file storage | Add object storage with virus scanning and access control |
| FEAT-12 | P1 | E-invoice/GST filing | No IRN/QR, HSN summary or filing export | See DATA-08/DATA-09; integrate a GSP if required |
| FEAT-13 | P3 | MFA/SSO/role hierarchy | Password-only; flat roles | Add MFA for platform/owner, SSO via OIDC, role ranks (SEC-02) |
| FEAT-14 | P3 | Dashboard date range | Today only | Add a date range and compare-to-previous period |
| FEAT-15 | P3 | Scheduled reports | On-demand only | Add schedules and secure delivery |
| FEAT-16 | P3 | Bulk import/export | Exports are client-side; no import | Add validated CSV/XLSX import with dry-run and audit |
| FEAT-17 | P2 | Audit UI/export | Direct SQL only | See SEC-13 |
| FEAT-18 | P3 | OpenAPI/Swagger | No generated API docs | Add springdoc in non-production profiles |
| FEAT-19 | P1 | Idempotency | Double submit duplicates | See DATA-11 |
| FEAT-20 | P3 | Multi-currency | INR assumed | Add currency only if the business requires it |

---

## 24.5 Operational and quality gaps

| ID | Severity | Gap | Evidence / impact | Remediation |
|---|---|---|---|---|
| OPS-01 | P1 | No backup automation in the repository | Backups are documented in section 21 but not scheduled; data loss risk | Implement OS/service-level `pg_dump` + retention + restore drills |
| OPS-02 | P2 | No frontend automated tests | `package.json` has no test runner; regressions rely on manual checks | Add Vitest + React Testing Library for critical forms, plus Playwright for POS/auth |
| OPS-03 | P2 | No CI pipeline | No `.github`/GitLab CI; tests/build are manual | Add CI for backend tests, frontend lint/build, and a disposable PostgreSQL smoke test |
| OPS-04 | P2 | No health/metrics endpoint | No Actuator dependency; monitoring cannot probe readiness | Add Actuator health/liveness/readiness and restrict exposure |
| OPS-05 | P2 | No migration rollback scripts | Flyway migrations are forward-only | Document restore-based rollback; add tested `V4` procedures for destructive changes |
| OPS-06 | P2 | Manual schema can drift from Flyway | `docs/sql/02` is a copy; no automated comparison | Add a CI check that builds both paths and diffs `information_schema` |
| OPS-07 | P3 | Blacklist cleanup is manual | `purgeExpired()` is never scheduled | Schedule cleanup or use TTL storage (SEC-08) |
| OPS-08 | P2 | No structured logging/correlation ids | Logs are plain; tracing a request across services is hard | Add request id/MDC, JSON logs, and avoid PII |
| OPS-09 | P2 | Secrets are environment/defaults only | No secret-manager integration; defaults are unsafe (SEC-03/04) | Integrate the deployment secret store and fail-fast on missing values |
| OPS-10 | P3 | No Docker/installer | Manual Java/Node/PostgreSQL setup | Provide a documented container or installer if it reduces deployment risk |
| OPS-11 | P2 | No audit retention/partitioning | `audit_logs` grows without a retention policy | Define retention, archive/partition, and restrict delete rights |
| OPS-12 | P3 | No request size limits beyond framework defaults | Large JSON could consume memory | Set explicit `server.max-http-request-header-size`/multipart limits and a body size limit at the proxy |
| OPS-13 | P3 | No API versioning | All endpoints are `/api`, no version | Introduce `/api/v1` only with a compatibility plan; do not break current clients casually |
| OPS-14 | P3 | No database connection retry/backoff | Startup fails if PostgreSQL is briefly unavailable | Add bounded retry for startup/readiness; do not retry non-idempotent writes blindly |
| OPS-15 | P2 | No PII redaction policy | Customer mobile/name and supplier data appear in logs/audit details | Define what may be logged; redact tokens/passwords/mobiles where not needed |
| OPS-16 | P3 | No rate limits at the proxy | App-level only (none); see SEC-09 | Add gateway limits per IP/user/route |
| OPS-17 | P3 | No OpenAPI contract tests | API drift risk | Generate OpenAPI and compare in CI |
| OPS-18 | P3 | No load/performance tests | Report/dashboard performance unproven at scale | Add k6/JMeter scenarios for dashboard/reports/POS |

---

## 24.6 What is already solid

These are implemented and verified; do not "fix" them into regressions:

- **Tenant isolation in queries and keys.** Store ids come from the JWT; repositories filter by store; composite `(store_id, id)` foreign keys make cross-tenant references difficult at the database level.
- **Platform/business boundary.** `TenantContext.storeId()` rejects platform users from store business data.
- **Stock safety.** `INSERT ... ON CONFLICT` + `SELECT ... FOR UPDATE`, `current_stock >= 0`, and a concurrency test proving six simultaneous sales cannot oversell one unit.
- **Transaction boundaries.** Purchase/sale/transfer posting and ledger writes are transactional; drafts do not touch stock.
- **Immutable history.** Completed documents are cancelled, not deleted; rates and GST are snapshotted on lines.
- **GST arithmetic.** Server-side exclusive/inclusive calculation, intra/inter-state split, tax snapshots, and report filtering by snapshot rate.
- **Rate contract.** Item master has one rate; purchases and normal sales accept manual rates; POS forces the master rate server-side.
- **Permission checks.** 28 codes, method-level `@PreAuthorize`, default role matrix, branch access checks.
- **Audit.** Sensitive operations write audit rows; login success is audited.
- **Validation and error contract.** Bean Validation plus business rules; consistent JSON errors.
- **Test coverage of invariants.** 16 integration tests against real PostgreSQL cover auth, tenancy, stock, GST, transfers, deactivation and concurrency.
- **Responsive UI.** The reports/layout regression was fixed and manually verified; no page-level horizontal scroll at the tested widths.

---

## 24.7 Prioritized remediation roadmap

### Phase 0 — before any shared/production deployment

1. **SEC-03 / SEC-04 / SEC-09:** remove default DB and JWT credentials; require secrets; add rate limiting and lockout.
2. **SEC-01 / SEC-02:** close `PLATFORM_MANAGE` and role-rank escalation; enforce the platform boundary in `StoreController`.
3. **SEC-05 / SEC-06:** remove the known platform password and default demo seeding; force a password change.
4. **SEC-07 / SEC-16 / SEC-17:** TLS, CSP/security headers, token storage decision, shorter access tokens/refresh rotation.
5. **OPS-01:** automated backups and a tested restore drill before real data is entered.

### Phase 1 — financial and workflow correctness

6. **DATA-01:** fix purchase-cancellation costing (or restrict cancellation).
7. **DATA-12:** canonical lock ordering and deadlock retry in purchase/sale.
8. **DATA-02:** transfer cost snapshot at dispatch.
9. **DATA-06:** opening-stock document/import.
10. **DATA-04 / DATA-05:** returns/credit notes and payments; define the financial model before building UI.
11. **DATA-11:** idempotency keys for create documents.
12. **SEC-08 / SEC-11 / SEC-12:** persistent revocation and token-version invalidation.

### Phase 2 — compliance and scale

13. **DATA-08 / DATA-09 / FEAT-12:** purchase GST/ITC, HSN, B2B GSTIN, e-invoice.
14. **DATA-19 / FEAT-08:** batch/expiry/serial tracking.
15. **DATA-21 / DATA-22:** store timezone/business day and rounding policy.
16. **OPS-02 / OPS-03 / OPS-04:** frontend tests, CI, Actuator.
17. **FEAT-01 / FEAT-05 / FEAT-06:** POS depth, customer master, purchase orders.
18. **SEC-13 / FEAT-17:** audit read/export and retention.

### Phase 3 — polish

19. Barcode, offline POS, printer integration, scheduled reports, bulk import, OpenAPI, load tests, multi-currency if required.

---

## 24.8 Acceptance tests for the critical fixes

These tests do not exist yet unless stated; add them when the corresponding fix is implemented.

| Issue | Test to add | Expected |
|---|---|---|
| SEC-01 | Store owner creates a role with `PLATFORM_MANAGE`; store token calls `/api/stores` | `400` on role create; `403` on store list |
| SEC-02 | `STORE_ADMIN` assigns `STORE_OWNER` to self | `403`/`400`; role unchanged |
| SEC-03 | Start `postgres` profile without `DB_USERNAME`/`DB_PASSWORD` | Startup fails with a clear message; never connects as `postgres` |
| SEC-04 | Start `postgres` profile without `JWT_SECRET` | Startup fails; no default secret accepted |
| SEC-05 | Fresh database, no seed password env | No known default platform login |
| SEC-06 | Production profile with default settings | No `ABC001` tenant, no demo users |
| SEC-07 | XSS payload in item/customer name | Rendered as text; CSP blocks inline script |
| SEC-08 | Logout on instance A, call instance B | `401` |
| SEC-09 | 10 rapid failed logins | `429`/lockout; audit records the failures |
| SEC-11 | Remove a branch from a user with an active token | Access denied on the next request (after fix) |
| SEC-12 | Change password, reuse old token | `401` |
| DATA-01 | Purchase 10@40 then 10@60, cancel the second | Stock 10, average 40, value 400 |
| DATA-02 | Dispatch transfer, change source average, receive | Destination uses dispatch-time cost |
| DATA-11 | Repeat `POST /api/sales` with the same idempotency key | One sale; same response |
| DATA-12 | Two purchases with lines A,B and B,A concurrently | No deadlock; both complete or one retries cleanly |
| DATA-14 | Two stores use `MILK-1L` | Both succeed; same store duplicate `409`/`400` |

---

## 24.9 Documentation caveats

- This review reflects the source at the date above. It does not certify the running database or a deployed environment.
- The SQL scripts were not executed. The validation script is the intended acceptance check; run it manually.
- Some items above are product decisions, not bugs: returns, payments, batch tracking, e-invoicing and multi-currency require business/legal input before implementation.
- The root `README.md` still references a second demo store `SUN001` / `sunrise.owner`; the current `DemoDataSeeder` creates only `ABC001`. Treat the seeder and this documentation as authoritative.
- The application has no hidden endpoints. Anything not listed in section 15 does not exist; proposed endpoints in this section are recommendations, not current routes.
- "Fix" recommendations are intentionally written without code changes. Implement them with a new migration and tests; do not edit applied migrations or seed production data by hand.

---

**End of section 24.** No application source, schema, migration, package or configuration was modified, and no SQL, test, migration or installation was executed while producing this document.
