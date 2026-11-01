# Complete Test Plan

Release under test: authentication hardening (V8), barcode (V7), printing (V6), Platform Admin store
accounts, and the documentation set.

> **Result reporting rule.** A test is recorded as **PASS** only if it was executed and the evidence
> exists. Otherwise it is **FAIL**, **BLOCKED** (hardware/environment unavailable), or **NOT
> PERFORMED**. This plan defines what to test; actual outcomes are in `TEST_EXECUTION_REPORT.md`.

---

## 1. Test levels and classification

| Level | Meaning | Examples |
|---|---|---|
| **A — Automated** | Runs in CI/locally, deterministic, no hardware | JUnit integration tests, `tsc`, lint, build |
| **M — Manual** | Human executes in a real browser/app and observes | Barcode label, POS scan, platform UI |
| **H — Hardware** | Requires a physical device | Thermal printer paper output, USB scanner, Android device |
| **N — Not performed** | Planned but not executed, with reason | Load test, production deployment |

Evidence rules:

- Automated: test report output, exit code, build log.
- Manual: screenshot or recorded steps with timestamp.
- Hardware: photo/serial log, plus device and driver model.
- Never convert a manual observation into an automated claim.

---

## 2. Environments and matrix

### 2.1 Environments

| Environment | Purpose | Backend | Database | Frontend |
|---|---|---|---|---|
| Local development | Functional, API, UI, responsive | Spring Boot jar on `:8080` | Embedded PostgreSQL `:54329` (Flyway V1–V8) | Vite `:5173` |
| Automated test | Backend integration | Spring Boot test context | Embedded PostgreSQL, Flyway V1–V8 | not used |
| Windows bridge test | Printing | Local backend | Local DB | Vite/Chrome |
| Android emulator | Printing bridge | Local backend | Local DB | Chrome on emulator |
| Production | Deployment verification | Live jar | Production PostgreSQL | Live domain |
| **Production test** | Not performed | — | — | — |

### 2.2 Browsers and viewports

| Class | Width | Browser |
|---|---|---|
| Small mobile | 320 px | Chromium |
| Mobile | 390 px | Chromium |
| Large mobile | 430 px | Chromium |
| Tablet | 768 px | Chromium |
| Laptop | 1024 px | Chromium |
| Desktop | 1440 px | Chromium |

Browsers required for sign-off: Chrome/Edge (Chromium), Firefox, Safari (iOS/macOS) where available.

### 2.3 Test data

- Store `ABC001`, branch(es), owner/manager/cashier roles, platform admin.
- At least 30 items with varied tax rates and prices.
- Categories, suppliers, one purchase, one sale, one transfer, one adjustment.
- Existing items from before V7 (to verify backfill).
- One Excel import file (valid), one malformed file, one duplicate-barcode file.
- One store with an expired subscription.
- One disabled user.

Secrets: use test credentials only; never production passwords.

---

## 3. Requirement traceability

| Requirement | Test IDs |
|---|---|
| Auto-generate unique barcode on item creation | BC-01, BC-02, API-01 |
| Backfill existing items | DB-01, DB-02 |
| Code 128 and EAN-13 support | BC-03, BC-04, API-02 |
| Barcode on item master/details | BC-05, UI-01 |
| Label printing/download, multiple labels | BC-06, BC-07, PRT-11 |
| Label content: name, code, barcode, selling price | BC-06 |
| USB scanner on desktop | BC-08, PRT-12 |
| Mobile camera scanning | BC-09, BC-10 |
| Auto-identify scanned item in workflows | BC-11, BC-12, BC-13 |
| Prevent duplicate barcodes | BC-14, DB-03 |
| Tenant/store/branch/permission isolation | SEC-01…SEC-06 |
| Responsive desktop/tablet/mobile | RESP-01…RESP-06 |
| Platform: list/search stores and users | PA-01, PA-02 |
| Identify a store’s login account | PA-03 |
| Reset forgotten password | PA-04 |
| Update username | PA-05 |
| Enable/disable account + status | PA-06 |
| Credential change audit history | PA-07 |
| No plaintext password exposure | SEC-07…SEC-10 |
| Tenant credential isolation | SEC-11 |
| Confirmation before reset | PA-08 |
| Mobile print buttons not overlapping | PRT-01, RESP-07 |
| Printer config/pairing/status/test | PRT-02…PRT-07 |
| Print job lifecycle and duplicate suppression | PRT-08, PRT-09 |
| Non-browser fallback and error handling | PRT-10 |
| Preserve Windows/Android printing | PRT-12, PRT-13 |
| Documentation and deployment instructions | DOC-01…DOC-06 |

---

## 4. Barcode tests (BC)

| ID | Type | Scenario | Steps | Expected |
|---|---|---|---|---|
| BC-01 | A/M | Create item without barcode | `POST /api/items` minimal body; open item | Barcode generated, `CODE128`, displayed |
| BC-02 | A | Create two items rapidly | Two creates in same store | Two distinct barcodes |
| BC-03 | A | Generate Code 128 | Create/regenerate with `CODE128` | Matches `IMS` + 10 digits; scannable |
| BC-04 | A | Generate EAN-13 | Regenerate with `EAN13` | 13 digits, valid check digit, scannable |
| BC-05 | M | Item list and detail display | Open Items page and an item | Barcode visible; search by barcode returns the item |
| BC-06 | M | Label content | Open label dialog | Name, item code, barcode symbol, selling price present in 50×30 mm layout |
| BC-07 | M | Multiple labels and download | Set quantity > 1; download PNG; print | Correct count; PNG 600×360 with all fields; print preview correct |
| BC-08 | H | USB scanner | Focus scan field; scan label | Item identified; no double submit |
| BC-09 | M/H | Camera scan dialog | Open on Android Chrome | Camera permission prompt; live preview |
| BC-10 | M | Unsupported camera browser | Open on a browser without `BarcodeDetector` | Clear fallback message; manual entry still works |
| BC-11 | M | POS scan | Scan into POS | Correct item and price added once |
| BC-12 | M | Sales entry scan | Scan into sales entry | Correct item added |
| BC-13 | M | Purchase scan | Scan into purchase | Correct item added |
| BC-14 | A | Duplicate barcode | Create/update with an existing barcode in the same store | Rejected with validation error; nothing saved |
| BC-15 | A | Cross-store same barcode | Same barcode value in two stores | Allowed; each store resolves its own item |
| BC-16 | M | Invalid barcode input | Scan unknown code | Clear “not found” message; no crash |

---

## 5. Platform account tests (PA)

| ID | Type | Scenario | Expected |
|---|---|---|---|
| PA-01 | A/M | List accounts | Paginated; store code/name and username shown; no password field |
| PA-02 | A | Search by store name, code, username | Matching results only |
| PA-03 | M | Identify a store’s login account | Account list scoped to the selected store |
| PA-04 | A | Reset password with `confirm=true` | 14-character temporary password returned once; hash updated |
| PA-05 | A | Update username | New username persisted; duplicate rejected; invalid characters rejected |
| PA-06 | A | Enable/disable account | Status changes; disabled user cannot log in |
| PA-07 | A | Credential history | Entries for reset/rename/status; no secrets |
| PA-08 | A | Reset without confirmation | 400; no password change |
| PA-09 | M | Platform UI | Account actions require explicit confirmation; loading/error states visible |
| PA-10 | A | Platform admin only | Store user receives 403 on `/api/platform/accounts` |
| PA-11 | A | No cross-tenant access | Platform admin cannot operate on a store user from another store via tampered IDs (target is platform/eligible scope as designed) |

---

## 6. Printing tests (PRT)

| ID | Type | Scenario | Expected |
|---|---|---|---|
| PRT-01 | M | Mobile layout | Print buttons do not overlap at 320/390/430 px |
| PRT-02 | M | Printer configuration | Add/edit/delete printer per branch; validation messages clear |
| PRT-03 | A | Pairing code | One-time code, 10-minute expiry, hash stored |
| PRT-04 | A | Device pairing | Device receives token; token never exposed to the website |
| PRT-05 | M | Connection status | Online/offline shown correctly; stale status expires |
| PRT-06 | M | Test print | Test slip prints via Windows and Android bridges |
| PRT-07 | M | Paper width/purpose | 58 mm and 80 mm render without clipping |
| PRT-08 | A | Job lifecycle | PENDING → PRINTED; failure → FAILED with message |
| PRT-09 | A | Duplicate suppression | Same auto job key cannot create two jobs |
| PRT-10 | M | No bridge | Clear error; job FAILED; no browser print dialog |
| PRT-11 | M | Label printing | Labels print/download from the item dialog |
| PRT-12 | H | Windows bridge | Default printer, silent print, no dialog |
| PRT-13 | H | Android bridge | LAN ESC/POS print, pairing, reprint |
| PRT-14 | M | Print history | Channel, trigger, status, timestamps visible; manual reprint works |

---

## 7. API tests (API)

| ID | Type | Scenario | Expected |
|---|---|---|---|
| API-01 | A | `POST /api/items` without barcode | 201/200; response contains barcode |
| API-02 | A | `POST /api/items/{id}/barcode` | Returns regenerated barcode and format |
| API-03 | A | `GET /api/items/barcode/{barcode}` | 200 for own store; 404 for unknown; no cross-store leak |
| API-04 | A | Validation errors | Consistent error body; no stack trace or secret |
| API-05 | A | Paging bounds | Page/size clamped or validated; no negative offsets |
| API-06 | A | Auth required | 401 without token on protected endpoints |
| API-07 | A | Permission denied | 403 with a clear code |
| API-08 | A | Subscription expired | 403 `SUBSCRIPTION_EXPIRED` except allowed endpoints |
| API-09 | A | Multipart limit | >10 MB rejected cleanly |
| API-10 | A | Platform reset response | Temporary password only in reset response; absent elsewhere |

---

## 8. Database tests (DB)

| ID | Type | Scenario | Expected |
|---|---|---|---|
| DB-01 | A | V7 backfill | `COUNT(*) WHERE barcode IS NULL` = 0 |
| DB-02 | A | Backfill format | Pattern `IMS` + 12 digits; length 15 |
| DB-03 | A | Unique index | `uq_items_store_barcode` exists; duplicate insert fails |
| DB-04 | A | Format check | Invalid `barcode_format` rejected |
| DB-05 | A | V6 tables | Four tables with FKs, checks, indexes |
| DB-06 | A | Printing uniqueness | Duplicate `(store_id, job_key)` fails |
| DB-07 | A | Cross-store integrity | Zero rows for mismatched branch/store |
| DB-08 | A | Flyway history | V1–V8 successful; no failed rows |
| DB-09 | A | Permission grants | `PRINTER_VIEW` for all store roles; `PRINTER_MANAGE` for owner/admin |
| DB-10 | A | Migration idempotence | Restart does not re-apply migrations |
| DB-11 | A | Backup/restore | Dump restores into scratch DB with matching counts |
| DB-12 | A | Multi-tenancy | All tenant tables filter by `store_id`; no orphan cross-tenant FKs |
| DB-13 | A | V8 columns | `users.token_version` default 0, `users.must_change_password` default false on existing rows |
| DB-14 | A | V8 revocation table | `revoked_tokens` exists with unique `jti` and expiry index; no existing row altered |
| DB-15 | A | V8 rollback scripts | `07_rollback_v7_compatibility.sql` / `08_remove_v7_rollback_compatibility.sql` are additive and preserve barcode data |

---

## 9. Security tests (SEC)

| ID | Type | Scenario | Expected |
|---|---|---|---|
| SEC-01 | A | Horizontal access | Store A cannot read/update Store B’s items, sales, printers, users |
| SEC-02 | A | Barcode lookup isolation | Barcode resolves only within the caller’s store |
| SEC-03 | A | Branch isolation | Branch-scoped operations reject another branch’s IDs |
| SEC-04 | A | Permission enforcement | Missing authority → 403 |
| SEC-05 | A | Platform role boundary | Only `PLATFORM_MANAGE` accesses platform APIs |
| SEC-06 | A | Subscription boundary | Expired store blocked except allowed endpoints |
| SEC-07 | A | Password exposure | No API response contains a password/hash except the one-time reset value |
| SEC-08 | A | Log exposure | Logs do not contain passwords, JWTs, DB credentials, bridge keys |
| SEC-09 | A | Hashing | Passwords stored as BCrypt; reset stores a hash |
| SEC-10 | A | Confirmation | Reset without `confirm=true` rejected |
| SEC-11 | A | Credential tenant isolation | Platform operations constrained to eligible accounts; no cross-tenant credential access |
| SEC-12 | A | JWT tampering | Modified/expired token → 401 |
| SEC-13 | A | Public endpoints | Only login, register-store, bridge pair/config, `/api/health`, `/api/version`, `/error` are public |
| SEC-14 | M | Frontend state | After logout, no token or credential remains in visible state/storage beyond design |
| SEC-15 | A | SQL injection | Parameterized queries; malicious search strings harmless |
| SEC-16 | A | XSS | User-controlled names are escaped in React output; no `dangerouslySetInnerHTML` on user data |
| SEC-17 | A | CORS | Only configured origins allowed for credentialed browser calls |
| SEC-18 | A | Cookie auth | Login sets `ims_auth` HttpOnly, `SameSite=Strict`, `Secure` when configured; body carries no token |
| SEC-19 | A | CSRF origin guard | Unsafe method with foreign `Origin`/`Referer` → 403 `CSRF_ORIGIN_REJECTED` |
| SEC-20 | A | Login lockout | Repeated failures → 429 with `Retry-After`; successful login resets the counter |
| SEC-21 | A | Revocation persistence | Logout/change/reset invalidates token; remains invalid after application restart |
| SEC-22 | A | Forced password change | Admin-reset user gets 403 `PASSWORD_CHANGE_REQUIRED` on other APIs until changed |
| SEC-23 | A | Self password change | Wrong current password rejected; reuse rejected; other sessions invalidated; new session returned |
| SEC-24 | A | Temp-password exposure | Reset response is `no-store`; password appears once and is absent from lists, history, audit and logs |
| SEC-25 | A | Provisioning gate | Platform-admin seeder refuses when a platform admin exists or when the env flag is off |
| SEC-26 | A | Health/version disclosure | Public status responses contain no credentials, hosts, or stack traces |

---

## 10. Responsive tests (RESP)

Measure `document.documentElement.scrollWidth` vs `window.innerWidth` at each width. Failure = overflow.

| ID | Route | 320 | 390 | 430 | 768 | 1024 | 1440 |
|---|---|---|---|---|---|---|---|
| RESP-01 | `/` dashboard | □ | □ | □ | □ | □ | □ |
| RESP-02 | `/items` | □ | □ | □ | □ | □ | □ |
| RESP-03 | `/pos` | □ | □ | □ | □ | □ | □ |
| RESP-04 | `/sales` + entry | □ | □ | □ | □ | □ | □ |
| RESP-05 | `/purchases` | □ | □ | □ | □ | □ | □ |
| RESP-06 | `/inventory` | □ | □ | □ | □ | □ | □ |
| RESP-07 | `/settings/printers` | □ | □ | □ | □ | □ | □ |
| RESP-08 | `/reports` | □ | □ | □ | □ | □ | □ |
| RESP-09 | `/platform/accounts` | — | — | — | □ | □ | □ |
| RESP-10 | `/platform/stores` | — | — | — | □ | □ | □ |
| RESP-11 | `/change-password` | □ | □ | □ | □ | □ | □ |
| RESP-12 | `/settings?tab=security` | □ | □ | □ | □ | □ | □ |
| RESP-13 | `/platform/audit` | — | — | — | □ | □ | □ |

Also verify at 320/390:

- Print buttons do not overlap.
- Tables scroll horizontally inside their container, not the page.
- Dialogs fit the viewport and scroll internally.
- Tap targets ≥ 40 px; labels and inputs remain readable.

---

## 11. Regression tests (REG)

| ID | Area | Expected |
|---|---|---|
| REG-01 | Login/logout | Works for store and platform users |
| REG-02 | Store registration | New store, branch, owner created |
| REG-03 | Branch limits | Subscription branch limit enforced |
| REG-04 | Item CRUD | Create/edit/status/delete rules unchanged |
| REG-05 | Excel import | Preview/confirm/history; duplicate handling; stock opening |
| REG-06 | Purchase | Stock and moving cost update; GST snapshot |
| REG-07 | Normal sale | Stock decrement; GST; invoice number |
| REG-08 | POS sale | Cart, discount, payment, receipt |
| REG-09 | Sale cancellation | Stock and ledger restore per rules |
| REG-10 | Transfer | Stock moves between branches |
| REG-11 | Adjustment | Stock and audit entry |
| REG-12 | Low stock | Threshold and list |
| REG-13 | Reports | Sales, purchase, GST, stock |
| REG-14 | Subscription expiry/renewal | Access blocked/restored |
| REG-15 | Platform stores | Search, detail, subscription update |
| REG-16 | Audit log | Entries for security-relevant actions |
| REG-17 | Printing | Existing printer/pairing/job flows |
| REG-18 | Bridges | Windows/Android unaffected |

---

## 12. Documentation and deployment validation (DOC)

| ID | Check | Expected |
|---|---|---|
| DOC-01 | Required file set | Exactly the 12 named docs plus the implementation summary present |
| DOC-02 | Screenshots | Real screenshots referenced exist; placeholders explicitly marked |
| DOC-03 | API doc vs code | Routes/permissions match controllers |
| DOC-04 | DB doc vs migrations | Tables/columns/indexes match V1–V8 |
| DOC-05 | Deployment commands | Windows vs Ubuntu distinguished; no secrets printed |
| DOC-06 | Server comparison | Live inspection gap labelled; declared vs verified separated |
| DOC-07 | Honesty | No unexecuted test reported as passed |
| DOC-08 | Links | Cross-references resolve; no broken relative paths |

---

## 13. Exit criteria

A release is test-complete when:

- [ ] All automated backend tests pass (expected: 68/68).
- [ ] `tsc -b` passes; lint has 0 errors; production build succeeds.
- [ ] All automated barcode/platform/printing/DB/API/security tests pass.
- [ ] All responsive measurements pass or defects are documented.
- [ ] Manual barcode, label, scanning and platform UI paths are verified.
- [ ] Printing is verified with at least one real bridge/device, or the gap is explicitly recorded as
      hardware-blocked.
- [ ] Regression spot checks pass.
- [ ] Documentation checks pass.
- [ ] Every not-performed test has a reason and an owner.

A production deployment additionally requires the `DEPLOYMENT_CHECKLIST.md` gate and owner approval.

---

## 14. Out of scope for this test cycle

- Load, stress and soak testing at production scale (no production access, no realistic data volume).
- Penetration testing by an external party.
- Physical paper/label verification on the customer’s specific printer (hardware unavailable).
- Verification on physical Android hardware (emulator/mock printer only unless a device is supplied).
- Live server comparison and production deployment (approval/access not provided).
- Cross-browser sign-off beyond Chromium (Firefox/Safari available only for limited manual checks).
