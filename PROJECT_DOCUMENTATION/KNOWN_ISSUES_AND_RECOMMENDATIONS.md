# Known Issues and Recommendations — Post-Implementation Status

Release: security and reliability hardening (auth V8, barcode V7, printing V6, platform accounts).
Report date: 2026-11-01. Baseline commit: `01567eb` plus the uncommitted hardening work described here.

This document is the authoritative status of the issues previously listed in this file. Every status
below was re-checked against the current source code and the executed test suites — documentation
claims made before the hardening work are **not** treated as evidence.

## Status definitions

| Status | Meaning |
|---|---|
| **Fixed** | Implementation and automated tests exist and passed in this environment. |
| **Partially Fixed** | The code change is implemented and tested, but a production/server action or a hardware verification remains outside this environment. |
| **Not Fixed** | No implementation was made. The reason and the agreed plan are stated. |
| **Not Applicable** | Verified as out of scope or as correct existing behaviour; no change is required. |

## Verification executed for this report

| Check | Command | Result |
|---|---|---|
| Backend integration suite | `backend/gradlew test` | **68 tests, 0 failures, 0 errors, 0 skipped** (6 classes; see §6) |
| Frontend type check | `npx tsc -b` | Passed, no diagnostics |
| Frontend lint | `npm run lint` (oxlint) | **0 warnings, 0 errors** (78 files) |
| Frontend production build | `npm run build` | Passed |
| Dependency audit | `npm audit --omit=dev` | 0 vulnerabilities |
| Live UI flows | Chromium against the local jar + Vite build | Password change, forced change, audit viewer + CSV export, cookie-only session, barcode generate/scan/label, ZXing fallback, store registration, platform store accounts |
| Responsive layout | 1440 px and 375 px viewports (`/reports`, `/settings`, `/items`, `/pos`, `/platform/audit`) | No horizontal overflow |

Hardware and production-server checks that could **not** be executed are listed in §7.

---

## 1. Summary

| ID | Issue | Status |
|---|---|---|
| K-01 | Hard-coded platform administrator credentials | **Partially Fixed** — source fixed and tested; production credential rotation still required |
| K-02 | Platform administrator cannot change own password | **Fixed** |
| K-03 | No store-user self password change; misleading reset text | **Fixed** |
| K-04 | V7 rollback incompatibility | **Fixed** — compatibility scripts + automated test; not executed on production |
| K-05 | JWT revocation in memory only | **Fixed** |
| K-06 | No login rate limiting / lockout | **Fixed** |
| K-07 | JWT in `localStorage` | **Fixed** — HttpOnly cookie + origin check |
| K-08 | Temporary password returned in a response | **Fixed** — mitigated to the fullest extent possible |
| K-09 | No audit viewer / retention policy | **Partially Fixed** — viewer, filters and CSV export fixed; retention documented, deletion intentionally manual |
| K-10 | Demo store credentials | **Fixed** |
| K-11 | No health/readiness endpoint | **Fixed** |
| K-12 | Production deployment not verified | **Not Fixed** — requires operator access; checklist provided |
| K-13 | Camera scanning depends on `BarcodeDetector` | **Fixed** — ZXing fallback; physical camera not tested |
| K-14 | Internal EAN-13 uses non-GS1 prefix | **Fixed** — configurable prefix, source classification, UI badge |
| K-15 | Label printing is client-side and printer-calibrated | **Partially Fixed** — calibration + documentation added; printer hardware not verified |
| K-16 | No purchase returns / credit notes | **Not Fixed** — design plan only, as instructed |
| K-17 | Transfer valuation timing | **Not Applicable** — verified correct; documented |
| K-18 | No customer-facing storefront | **Not Applicable** — out of scope; documented |
| K-19 | Documentation drift | **Fixed** — documentation set refreshed |
| K-20 | No automated backup/monitoring | **Partially Fixed** — procedures documented; no server access for automation |
| K-21 | No audit/print-job retention | **Partially Fixed** — policy documented; no automatic deletion (by design) |
| K-22 | Static version string | **Fixed** |
| K-23 | Lint warnings | **Fixed** |
| K-24 | Inconsistent page-size bounds | **Fixed** |
| K-25 | Subscription allowlist review | **Fixed** |
| K-26 | No MFA | **Not Fixed** — evaluated; recommendation documented |
| K-27 | No consistent password-strength policy | **Fixed** |

---

## 2. Critical issues

### K-01 — Hard-coded platform administrator credentials

**Status: Partially Fixed.** The source-level exposure is removed and covered by tests. The
production database may still contain the previously seeded account, so its password must be rotated
by the operator (see §8).

**Root cause.** `DataSeeder` created a platform administrator with a literal username and password on
every start when the account was missing. The seeder was not gated by `SEED_DEMO`, so any deployment
started with a known credential and any deletion recreated it.

**Implementation.**
- `DataSeeder` is deleted. It is replaced by `PlatformAdminProvisioner`
  (`backend/src/main/java/com/itemmanagement/config/PlatformAdminProvisioner.java`), which runs only
  when `PROVISION_PLATFORM_ADMIN=true` **and** `PLATFORM_ADMIN_USERNAME`, `PLATFORM_ADMIN_PASSWORD`
  and `PLATFORM_ADMIN_EMAIL` are supplied. There is no fallback username, password or email in source.
- Provisioning is refused when a platform administrator already exists, so a restart can never
  overwrite or recreate an account. The created account is always `mustChangePassword=true`.
- Credential values pass the shared `PasswordPolicy`; username and email are validated before insert.
- `DemoDataSeeder` is `@Profile("!postgres")` and `@Value("${app.seed-demo:false}")`; production uses
  the `postgres` profile and therefore never runs it.
- Test: `noPlatformAdministratorIsCreatedWithoutExplicitProvisioning`.

**Remaining operator action.** Change the password of any platform administrator that already exists
in production using the new self-service flow (K-02). Do not delete the account. Never place
`PLATFORM_ADMIN_PASSWORD` in source control or shell history; pass it through the service environment.

### K-02 — Platform administrators cannot reset or change their own password

**Status: Fixed.**

**Root cause.** The platform account API intentionally filters to store accounts (`storeId != null`)
and no self-service change endpoint existed, so platform administrators had no way to rotate a
password.

**Implementation.**
- `POST /api/auth/change-password` is available to every authenticated account, including platform
  administrators (`backend/src/main/java/com/itemmanagement/controller/AuthController.java:58`,
  `AuthService.changePassword`).
- It verifies the current password, applies `PasswordPolicy`, rejects reuse of the current password,
  stores a BCrypt hash, clears `must_change_password`, increments `users.token_version`, revokes the
  presented token, writes a `PASSWORD_CHANGE` audit entry and issues a fresh cookie.
- The frontend exposes **Settings → Profile → Change password** for all authenticated users and forces
  the screen after login when the server reports `mustChangePassword`.
- Tests: `platformAdministratorChangesOwnPassword`. Live UI: platform password change and forced
  first-login change both verified.

### K-03 — No store-user self-service password change; misleading reset message

**Status: Fixed.**

**Root cause.** Only platform-initiated resets existed. The reset audit text claimed the user must
change the password, but no change endpoint or UI existed, so a temporary password became permanent.

**Implementation.**
- The authenticated change-password endpoint above applies to store users. It cannot target another
  user — the user is always resolved from the authenticated principal, so cross-user/tenant targeting
  is impossible.
- Platform reset now sets `must_change_password=true` and increments `token_version`, invalidating all
  existing sessions. Login returns HTTP 403 with code `PASSWORD_CHANGE_REQUIRED` for every endpoint
  except `/api/auth/**`, so the UI routes to the forced change screen.
- The reset audit text now states the real behaviour.
- Frontend: forced change screen, Settings → Profile screen, shared password hint, client-side
  validation mirroring the server policy.
- Tests: `storeUserChangesOwnPasswordAndEveryPreviousSessionIsInvalidated`,
  `administratorResetForcesPasswordChangeOnNextLogin`,
  `registrationRejectsWeakPasswordsWithTheSharedPolicy`. Live UI: old password rejected after change;
  new password accepted; reset-then-forced-change verified.

### K-04 — V7 rollback incompatibility

**Status: Fixed (compatibility scripts supplied and tested locally; not applied to production).**

**Root cause.** `V7__item_barcode.sql` makes `items.barcode` `NOT NULL` with no default, so rolling
back only the application jar breaks item creation and Excel import while the V7 schema remains.

**Implementation.**
- `docs/sql/07_rollback_v7_compatibility.sql` restores backward compatibility **without touching
  existing rows**: it adds a default for new inserts so the old jar can create items, and relaxes the
  constraint only as required. No barcode value is removed or rewritten.
- `docs/sql/08_remove_v7_rollback_compatibility.sql` removes the shim after the V7 application is
  restored, re-tightening the constraint.
- Procedure and decision table: `PROJECT_DOCUMENTATION/ROLLBACK_GUIDE.md` §1.1.
- Test: `v7RollbackShimAllowsItemInsertWithoutBarcodeAndChangesNoExistingRows` asserts that the shim
  permits an insert without a barcode and that existing barcode values are unchanged.
- These scripts have **not** been executed against production; they are run only if a jar rollback is
  actually approved.

---

## 3. High issues

### K-05 — JWT revocation is in-memory only

**Status: Fixed.**

**Root cause.** `TokenBlacklistService` held revoked token IDs in a `ConcurrentHashMap`, so a restart
(or a second instance) forgot every logout.

**Implementation.**
- `V8__auth_security.sql` adds the `revoked_tokens(jti PRIMARY KEY, expires_at, revoked_at)` table
  with `ix_revoked_tokens_expires`.
- `TokenBlacklistService` writes revocations to PostgreSQL, treats an unreadable store as
  fail-closed, and purges only expired rows through a scheduled job
  (`app.auth.blacklist-cleanup-ms`, default hourly).
- `users.token_version` is embedded in each JWT as `ver` and compared on every request
  (`JwtAuthFilter`), so a password change, reset or forced logout invalidates all previously issued
  tokens even if a JTI was not individually revoked.
- Tests: `revokedTokensAreStoredInTheDatabaseAndRejected`,
  `deactivationImmediatelyRevokesExistingTokens`,
  `storeUserChangesOwnPasswordAndEveryPreviousSessionIsInvalidated`.

### K-06 — No login rate limiting or account lockout

**Status: Fixed.**

**Root cause.** No throttling existed in the auth filter chain or service.

**Implementation.** `LoginAttemptService` tracks failures per client address and login key.
Defaults: 5 attempts per 900 s window → 900 s lockout; configurable via `LOGIN_MAX_ATTEMPTS`,
`LOGIN_WINDOW_SECONDS`, `LOGIN_LOCKOUT_SECONDS`. Lockout returns HTTP 429 with
`TOO_MANY_REQUESTS`; a successful login clears the counter. Test:
`repeatedFailedLoginsLockTheAccountKey`.

**Residual limitation.** Counters are per instance. A future multi-instance deployment should move
them to a shared store (Redis/PostgreSQL); a single-instance deployment is fully covered.

### K-07 — JWT stored in `localStorage`

**Status: Fixed.**

**Root cause.** The SPA persisted the bearer token with Zustand `persist` and attached it with
`Authorization` on every request, so any XSS could read a 12-hour token.

**Implementation.**
- Login, refresh and password change set an `HttpOnly` cookie (`ims_auth`) with `SameSite=Strict`
  and configurable `Secure` (`AUTH_COOKIE_SECURE`); the frontend Axios client uses
  `withCredentials: true` and never stores a token. The persisted store migrates v1 state by deleting
  the old `token` key.
- Safety for cookie authentication: unsafe methods (`POST/PUT/PATCH/DELETE`) require an `Origin` or
  `Referer` matching `CORS_ORIGINS`; otherwise the request is rejected with
  `CSRF_ORIGIN_REJECTED`. This is an origin allowlist, not a weakening of tenant isolation.
- The token remains in the JSON body for non-browser API clients and automated tests only.
- Tests: `browserSessionUsesHttpOnlyCookieAndRejectsUntrustedOrigins`. Live UI: `document.cookie` is
  empty and API calls succeed without an `Authorization` header.

**Residual limitation.** No per-request CSRF nonce; the origin allowlist is the accepted control for
this deployment. Keep `CORS_ORIGINS` exact and HTTPS-only in production.

### K-08 — Temporary password returned in an API response

**Status: Fixed** (the design still must return it once; all avoidable exposure is removed).

**Implementation.**
- `POST /api/platform/accounts/{id}/reset-password` requires `confirm=true`, returns the temporary
  password exactly once, sets `Cache-Control: no-store`, stores only a BCrypt hash, invalidates other
  sessions, forces a change at next login and writes an audit entry without the secret.
- Reset/list/detail responses never contain stored passwords or hashes; a test asserts no password
  fields appear in account responses (`assertNoPasswordFields`).
- Frontend shows the password in a one-time dialog with copy-to-clipboard; it is not stored in
  persisted state and not included in list data.
- Tests: `resetPasswordReturnsOneTimeTemporaryPasswordAndAuditsTheChange`,
  `sessionResponsesAndTemporaryPasswordsAreNotCacheable`.

**Residual limitation.** A password returned to a browser can appear in screenshots or developer
tools. Operators must not paste it into tickets and must force the first-login change (already
enforced).

### K-09 — No audit-log viewer or retention policy

**Status: Partially Fixed.**

**Implementation.**
- New platform-only endpoints (`PLATFORM_MANAGE`): `GET /api/platform/audit-logs` (filters: store, action,
  entity type, username, free-text search, `from`/`to`, page/size), `GET /api/platform/audit-logs/options`,
  `GET /api/platform/audit-logs/entity-types`, `GET /api/platform/audit-logs/export` (CSV).
- Frontend **Platform → Audit log** screen with filters and CSV export. Live UI: filtered query and
  downloadable `text/csv` response verified.
- Access is platform-only; store users cannot read the global audit. Credential history remains
  available per account.
- Tests: `platformAuditViewerFiltersAndExportsCsvForPlatformAdminsOnly`.

**Not done (by design).** No automatic deletion. The retention policy is documented in §5 (K-21):
audit 24 months, print jobs 12 months, archive before deletion, deletion only with written approval.

### K-10 — Hard-coded demo store credentials

**Status: Fixed.**

**Implementation.** `DemoDataSeeder` only runs outside the `postgres` profile and only when
`SEED_DEMO=true`; production must keep `SEED_DEMO=false`. Test:
`demoStoreIsNotSeededInProductionMode` asserts the production profile creates no demo account.

**Operator action.** Keep `SEED_DEMO=false` in production and verify no demo store exists there.

### K-11 — No health/readiness endpoint

**Status: Fixed.**

**Implementation.** `GET /api/health` is public, executes `select 1` against PostgreSQL and returns
`{status, database, timestamp}` with `Cache-Control: no-store`; HTTP 503 when the database is down.
It contains no version, host, credentials or configuration. `GET /api/version` returns build metadata
without secrets. Both are exempt from the subscription filter. Test:
`healthAndVersionArePublicAndContainNoSecrets`.

### K-12 — Production deployment not executed or verified

**Status: Not Fixed — requires production access and operator approval.**

**Assessment.** The hardening is validated only in the local environment (embedded PostgreSQL, local
jar, local Vite build). The live server's jar, schema version, environment variables, reverse-proxy
configuration, TLS and browser matrix are unknown from this environment. No production command was
run.

**Required before any deployment claim.**
1. Complete `SERVER_VERSION_COMPARISON.md` §12 read-only checks.
2. Complete `DEPLOYMENT_CHECKLIST.md` §7–§10 (backup, migration, health, smoke tests).
3. Rotate the platform administrator password (K-01) using K-02 immediately after deployment.
4. Keep `SEED_DEMO=false`, set a strong `JWT_SECRET`, `AUTH_COOKIE_SECURE=true` behind HTTPS, and an
   exact `CORS_ORIGINS` list.
5. Follow `ROLLBACK_GUIDE.md` if V8 must be reverted (V8 is additive; the application can be rolled
   back safely, but see the guide for the token-version caveat).

---

## 4. Medium issues

### K-13 — Barcode camera support depends on `BarcodeDetector`

**Status: Fixed.** `BarcodeScanDialog` uses the native `BarcodeDetector` when available and lazily
loads `@zxing/browser` otherwise, with a clear unsupported-browser message when the camera is denied
or unavailable. USB/keyboard-wedge and manual entry remain available. Verified live in Chromium with
the camera-denied path and fallback messaging; a physical camera scan was not performed (see §7).

### K-14 — EAN-13 values use a non-GS1 prefix

**Status: Fixed.** `BarcodeService` uses a configurable prefix
(`app.barcode.ean13-prefix`, default `20`, GS1's internal/restricted range 02/20–29). Every barcode is
classified as `INTERNAL` or `GS1`; item responses expose `barcodeSource` and the UI shows a `GS1`
badge. Documentation explains that internal codes are for in-store labels and that uniqueness is per
store by design. For retail/global codes, configure a registered GS1 company prefix.

### K-15 — Label printing is client-side and printer-calibrated

**Status: Partially Fixed.** Labels are 600×360 px for 50×30 mm, with a calibration test page and
documented DPI/paper settings. Automation could not complete a physical print (the native print
dialog blocks browser automation), so printer output on the customer's hardware remains unverified
(§7).

### K-16 — No purchase returns / credit notes

**Status: Not Fixed.** No partial workflow was implemented, because an incorrect reversal would
corrupt stock, cost or GST history.

**Design plan (implement as one migration + one workflow):**
1. `purchase_returns` (id, store_id, branch_id, supplier_id, return_number per store, original
   purchase_id, date, reason, status DRAFT/COMPLETED/CANCELLED, totals, created_by) and
   `purchase_return_items` (return_id, item_id, quantity, unit_cost snapshot, tax snapshot copied
   from the original purchase line, line totals).
2. Completion must, in one transaction: decrement branch stock only for quantity actually on hand,
   reverse inventory value at the **original purchase line cost** (not current average), reduce the
   supplier payable, and reverse GST using the original tax snapshot — never recompute from current
   tax masters.
3. Numbering, audit entries (`PURCHASE_RETURN`, `PURCHASE_RETURN_CANCEL`), `PURCHASE_MANAGE`
   permission, store/branch isolation and a reversal link on the purchase.
4. Tests: cannot return more than purchased or more than on hand; cost/GST totals equal the reversed
   purchase lines; cancelling after stock is consumed is blocked; cross-store access rejected.
5. Migration is additive (V9); rollback = stop using the feature, keep data, no destructive SQL.

Do not fake returns with negative sales.

### K-17 — Transfer valuation timing

**Status: Not Applicable — verified as documented behaviour.** Transfers move stock at the source
valuation; revaluation after transfer is not pushed to the destination. The automated test
`transferValuationFollowsSourceAverageCostWhenSourceStockIsEmptied` confirms the implemented rule.
No code change was made; the rule is documented in the customer manual.

### K-18 — No customer-facing storefront

**Status: Not Applicable.** The product is an internal store/POS/administration system; there is no
customer catalogue or ordering surface, and none should be advertised. No change made.

### K-19 — Documentation drift in older files

**Status: Fixed.** `PROJECT_DOCUMENTATION/` is the current source of truth. This report, the API,
database, deployment, rollback and test documents were updated, and dated pointers were added to the
older `docs/` pages and the root runbook/changelog.

### K-20 — No automated backup/monitoring in the repository

**Status: Partially Fixed.** `ADMIN_DEPLOYMENT_GUIDE.md` documents the nightly `pg_dump` + restore
verification, retention of backups, and external uptime monitoring using `/api/health`. Nothing was
installed on the server: this environment has no server access and no approval to modify scheduling,
systemd, Nginx or firewall settings.

### K-21 — Audit and print-job tables have no retention

**Status: Partially Fixed.** Policy documented; no automatic deletion exists or was added.

| Data | Retention | Deletion rule |
|---|---|---|
| `audit_logs` | 24 months | Archive to cold storage; delete only with written approval and an audit entry |
| `print_jobs` | 12 months | Archive; delete only with written approval |
| `revoked_tokens` | Until `expires_at` | Automatic purge of expired rows only (no business data) |
| Business records (items, stock, purchases, sales) | Permanent | Never auto-deleted |

Deleting historical production data without explicit approval is prohibited.

---

## 5. Low issues

### K-22 — Static version string

**Status: Fixed.** `GET /api/version` returns name, version, artifact, build time, Git commit
(`app.build.git-commit` / Gradle `buildInfo`) and Java version, with `no-store`. The Settings →
Security screen shows the same build information. Test:
`healthAndVersionArePublicAndContainNoSecrets`. The endpoint is intentionally public so a load
balancer or operator can identify a deployment; it exposes no secret, host or credential.

### K-23 — Lint warnings

**Status: Fixed.** `npm run lint` reports 0 warnings and 0 errors across 78 files.

### K-24 — Default page-size bounds

**Status: Fixed.** `Pagination.of(page, size)` clamps page to `>= 0` and size to `1..100`
(imports and print jobs use a maximum of 50). List endpoints use the shared helper; test:
`paginationClampsNegativePageAndOversizedPageSize`.

### K-25 — Subscription expiry allowlist

**Status: Fixed.** The exemption list was reviewed and now covers `OPTIONS`, `/api/auth/**`,
`/api/health`, `/api/version`, `/api/subscription/summary` and `/error`; platform accounts
(`storeId == null`) are never blocked. Tests: `expiredSubscriptionBlocksApisAndRenewalRestoresAccessImmediately`
and `healthAndVersionArePublicAndContainNoSecrets`.

### K-26 — No MFA

**Status: Not Fixed — evaluated; implementation deferred.**

**Recommendation.** Add TOTP (RFC 6238) for `PLATFORM_MANAGE` accounts first: enrolment with a QR
code, recovery codes stored as hashes, a second login step after the password check, and audit entries
for enrolment/disable. WebAuthn can follow. This is a product/scope decision; no partial MFA was
added because a half-implemented second factor is worse than none.

### K-27 — No password-strength policy

**Status: Fixed.** `PasswordPolicy` enforces 8–72 characters, upper/lower/digit/special, no spaces,
no common passwords, and no username/email match. It is used by registration, platform-admin
provisioning, administrator reset (generated passwords) and self-service change. The frontend mirrors
the rules with a shared hint (`src/lib/password.ts`). Tests:
`registrationRejectsWeakPasswordsWithTheSharedPolicy`; live UI verified for registration and account
change.

---

## 6. Test inventory (executed 2026-11-01)

| Class | Tests |
|---|---|
| `AuthSecurityIntegrationTest` | 14 |
| `SystemInvariantIntegrationTest` | 22 |
| `ItemImportIntegrationTest` | 13 |
| `PrintingIntegrationTest` | 8 |
| `BarcodeIntegrationTest` | 6 |
| `PlatformAccountIntegrationTest` | 5 |
| **Total** | **68 passed, 0 failed, 0 skipped** |

Frontend: `tsc -b` clean; oxlint 0/0; production build succeeded; `npm audit` 0 vulnerabilities.

## 7. Verification gaps (not defects)

| Gap | Reason | Owner action |
|---|---|---|
| Physical label/thermal output | Native print dialog blocks automation; no printer attached | On-site print test with the customer's hardware |
| Physical Android device and bridge | Emulator/local bridge only | Install the signed APK, pair, print |
| USB/keyboard-wedge scanner | No scanner hardware | On-site scan test |
| Camera barcode scan | No camera in the verification environment | Test on a phone/tablet camera |
| Windows silent print on the production PC | Local bridge only | Follow `ADMIN_DEPLOYMENT_GUIDE.md` §6.9 |
| Live server comparison and migration | No access/approval | `SERVER_VERSION_COMPARISON.md` §12 |
| Production deployment | Not approved | `DEPLOYMENT_CHECKLIST.md` |
| Load/stress testing | No production-scale environment | Staging load test |
| Firefox/Safari matrix | Chromium used for verification | Targeted browser pass before sign-off |

## 8. Required operator actions for this release

1. **Rotate the existing platform administrator password** after deployment using the key icon at the
   bottom of the sidebar (**Change password**) or **Settings → Security** (K-01/K-02). Do not delete the
   account.
2. Keep production environment values: `SEED_DEMO=false`, `PROVISION_PLATFORM_ADMIN=false` (after the
   initial provisioning), a strong `JWT_SECRET`, exact `CORS_ORIGINS`, `AUTH_COOKIE_SECURE=true` and
   HTTPS.
3. Apply V8 through Flyway during the deployment window; V8 is additive (`must_change_password`,
   `token_version`, `revoked_tokens`) and does not modify business data.
4. Verify `/api/health` returns `UP` and `/api/version` shows the expected Git commit.
5. Keep the V7 compatibility scripts ready but unapplied unless a jar rollback is approved.
6. Set up backups, restore verification, uptime monitoring and the documented retention review
   (K-20/K-21); no server settings were changed by this work.

## 9. Positive controls already in place

- Multi-tenant filtering by `store_id`/`branch_id`, with composite foreign keys preventing cross-store
  rows; platform administrators cannot read store business data.
- `@PreAuthorize` on controller methods; platform APIs restricted to `PLATFORM_MANAGE`.
- Platform account operations refuse store-less users and re-verify the actor on every call.
- BCrypt password hashes; no endpoint returns a stored password or hash.
- Reset requires `confirm=true`; credential audit entries omit secrets.
- User/store status and `token_version` are re-checked on every request, so disabling an account or
  store blocks existing tokens immediately.
- Login lockout, persistent revocation, origin-checked cookie sessions, `no-store` on sensitive
  responses.
- Print device tokens and pairing codes are stored as SHA-256 hashes.
- `spring.jpa.hibernate.ddl-auto=validate` prevents silent schema drift; Flyway migrations are
  versioned and additive.
- Health/version endpoints contain no secrets; demo data is profile- and flag-gated.
