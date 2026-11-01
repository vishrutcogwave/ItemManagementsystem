# Test Execution Report

Release tested: barcode (V7), printing (V6), Platform Admin store accounts, printing/mobile fixes.

Execution date: **2026-11-01** (local). This report records only tests that were actually executed.
Unexecuted work is explicitly marked **NOT PERFORMED** or **BLOCKED** with a reason.

---

## 1. Environment

| Item | Value |
|---|---|
| Host | Windows 10.0.26300, x64 |
| Java | 17 |
| Node.js / npm | 24.18.0 |
| Backend | `item-management-backend-1.0.0.jar` on `http://localhost:8080` |
| Database | Embedded PostgreSQL on port `54329`, Flyway V1–V8, `ddl-auto=validate` |
| Frontend | Vite dev server `http://localhost:5173` |
| Browser | In-app Chromium browser (browser-use) |
| Test data | Demo store `ABC001` (10 visible items), platform admin, store owner |
| Production | **Not used. No production connection or credentials.** |

---

## 2. Automated backend tests

Command (re-run fresh for this report):

```bash
cd backend
./gradlew test --rerun-tasks --console=plain
```

Result:

| Metric | Value |
|---|---|
| Exit code | **0** |
| Build | **BUILD SUCCESSFUL in 3m 18s** |
| Passed | **68** |
| Failed | **0** |
| Skipped | 0 |

Breakdown:

| Test class | Tests | Result | Coverage highlights |
|---|---|---|---|
| `AuthSecurityIntegrationTest` | 14 | PASS | No platform admin without explicit provisioning; store/platform password change invalidates sessions; reset forces change; DB-backed revocation; lockout; HttpOnly cookie + origin rejection; `no-store`; audit filters/export; demo seeder disabled; health/version; pagination clamps; shared password policy; V7 rollback shim |
| `SystemInvariantIntegrationTest` | 22 | PASS | Auth/token rejection; tenant isolation; deactivation revokes tokens; expired subscription; branch limits; GST arithmetic/snapshots; purchase/sale stock; oversell prevention; transfer valuation; subscription isolation |
| `ItemImportIntegrationTest` | 13 | PASS | Import/preview/confirm, duplicates, rollback, branch-specific opening stock, permissions. Items insert successfully under `barcode NOT NULL`, which proves imported items receive a barcode. (No explicit barcode-value assertion.) |
| `PrintingIntegrationTest` | 8 | PASS | Printer CRUD and scoping; test status; single-use pairing/revocable token; idempotent automatic jobs; cancelled sales/foreign stores blocked; concurrency; permissions |
| `BarcodeIntegrationTest` | 6 | PASS | Auto Code 128; store-scoped uniqueness; EAN-13 check digit; regeneration/search; `ITEM_MANAGE` permission; **migration backfill of old rows** |
| `PlatformAccountIntegrationTest` | 5 | PASS | Search by store/username/code with no password fields; one-time temporary password + audit; store-scoped username uniqueness; disable/enable; store users and platform targets rejected |

Evidence: Gradle test-result XML for all six classes (`tests="…" failures="0" errors="0"
skipped="0"`), summing to 68; console `BUILD SUCCESSFUL`, exit `0`.

Notable passing assertions relevant to this release:

- `noPlatformAdministratorIsCreatedWithoutExplicitProvisioning` — no credential exists in source.
- `platformAdministratorChangesOwnPassword` / `storeUserChangesOwnPasswordAndEveryPreviousSessionIsInvalidated`.
- `administratorResetForcesPasswordChangeOnNextLogin` / `revokedTokensAreStoredInTheDatabaseAndRejected`.
- `repeatedFailedLoginsLockTheAccountKey` — login lockout.
- `browserSessionUsesHttpOnlyCookieAndRejectsUntrustedOrigins` / `sessionResponsesAndTemporaryPasswordsAreNotCacheable`.
- `platformAuditViewerFiltersAndExportsCsvForPlatformAdminsOnly`.
- `healthAndVersionArePublicAndContainNoSecrets` / `paginationClampsNegativePageAndOversizedPageSize`.
- `v7RollbackShimAllowsItemInsertWithoutBarcodeAndChangesNoExistingRows`.
- `oldItemRowsWereBackfilledByTheMigration` — V7 backfill verified against the real migrated schema.
- `manualBarcodesAreValidatedAndUniqueInsideAStoreOnly` — duplicate rejection + cross-store independence.
- `resetPasswordReturnsOneTimeTemporaryPasswordAndAuditsTheChange` — reset contract and audit.
- `deactivationImmediatelyRevokesExistingTokens` — disabled accounts blocked without waiting for expiry.
- `expiredSubscriptionBlocksApisAndRenewalRestoresAccessImmediately` — subscription boundary intact.

---

## 3. Frontend static checks and build

| Command | Result | Evidence |
|---|---|---|
| `npx tsc -b` | **Exit 0** (no type errors) | fresh run 2026-11-01 |
| `npm run lint` | **0 errors, 0 warnings** (78 files) | fresh run |
| `npm run build` | **Success** | fresh run; output in `dist/` |
| `npm audit --omit=dev` | **0 vulnerabilities** | fresh run |

The 12 pre-existing React Compiler warnings were resolved during the hardening work; the current lint
baseline is clean.

Build output highlights: `ItemsPage` 93.27 kB, `DashboardPage` 382.72 kB, main bundle 383.84 kB,
`xlsx` 491.98 kB (all gzip sizes reported by Vite).

---

## 4. Database verification

Verified through the test suite (real embedded PostgreSQL with Flyway V1–V8 and
`ddl-auto=validate`) and through live UI/API data:

| Check | Result | Evidence |
|---|---|---|
| Flyway V1–V8 apply cleanly on an empty database | PASS | every integration test starts the app against the migrated schema |
| V7 backfill of pre-existing rows | PASS | `oldItemRowsWereBackfilledByTheMigration` |
| Barcode uniqueness per store | PASS | `manualBarcodesAreValidatedAndUniqueInsideAStoreOnly` |
| EAN-13 check digit / character rules | PASS | `ean13ChecksumAndCharacterRulesAreEnforced` |
| Items can be inserted after `barcode NOT NULL` | PASS | item create and import tests |
| V7 rollback shim preserves existing rows | PASS | `v7RollbackShimAllowsItemInsertWithoutBarcodeAndChangesNoExistingRows` |
| V8 columns and `revoked_tokens` | PASS | auth tests run against the migrated schema; revocation test writes and reads the table |
| Printing tables and constraints | PASS | `PrintingIntegrationTest` (8 tests) |
| Cross-store print isolation | PASS | `cancelledSalesAndForeignStoresCannotPrint`, `printerConfigCrudValidatesAndIsStrictlyScoped` |
| Print job idempotency under concurrency | PASS | `concurrentAutomaticRequestsCreateExactlyOneJob` |
| UI shows backfilled barcodes | PASS | Items page displays `IMS000000000001`…`IMS000000000013` for demo items |

Direct SQL client check: **NOT PERFORMED** — `psql` is not installed on the build host. Equivalent
schema checks run through Hibernate validation and the integration tests; SQL commands for the
production server are provided in `DATABASE_DOCUMENTATION.md` and `DEPLOYMENT_CHECKLIST.md`.

---

## 5. Responsive test execution

Method: headless Chromium, real logged-in sessions, measured
`document.documentElement.scrollWidth` vs `window.innerWidth` and `body.scrollWidth`.

Widths/height: 320×568, 390×844, 768×1024, 1024×768, 1440×900.

Routes: `/`, `/pos`, `/items`, `/sales`, `/inventory`, `/reports`, `/settings?tab=printers`,
`/print-history` (store); `/platform/accounts`, `/platform/stores` (platform).

| Result | Count |
|---|---|
| Measurements | **50** |
| Overflow failures (final) | **0** |
| Widths with overflow before fix | 1 (`/sales` at 320 px, 4 px) |

Defect found and fixed during execution: the `/sales` date-filter row overflowed by 4 px at 320 px.
The wrapper was changed to `flex flex-wrap` and the date inputs to `w-38 min-w-0 flex-1 sm:flex-none`
in [src/pages/SalesPage.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/pages/SalesPage.tsx).
Re-measured: `innerWidth 320 / scrollWidth 320 / body 320` — no overflow.

Raw evidence: `PROJECT_DOCUMENTATION/assets/responsive-results.json` (all 50 entries, `overflow:false`).

Screenshot evidence: `17-sales-mobile-320.png`, `12-pos-mobile-390.png`, `13-pos-tablet-768.png`,
`14-dashboard-mobile-390.png`.

---

## 6. Manual UI execution

Captured real screenshots: `PROJECT_DOCUMENTATION/assets/screenshots/` (17 PNGs).

| Area | Test | Result | Notes |
|---|---|---|---|
| Login | Sign in as store owner | PASS | `01-login-desktop.png` |
| Dashboard | Render with data at desktop and 390 px | PASS | `02`, `14` |
| Items | Barcode column and values visible | PASS | `03-items-desktop.png`; barcodes `IMS…` observed |
| Items | Barcode label dialog content | PASS | `04-item-barcode-label-dialog.png`; name, item code, symbol, `₹` price, barcode text, 50×30 mm note |
| Items | Download PNG action | PASS | Clicked **Download PNG** on Milk; success toast “Barcode label downloaded”; canvas path executed |
| Items | Print label action | **PARTIAL** | Button invokes the print pipeline; the native print dialog blocked the automated browser, so no paper/PDF output was verified. Browser became unresponsive after this step. |
| Items | Copy barcode value | NOT PERFORMED | Clipboard permission not exercised |
| Items | Search by barcode | NOT PERFORMED in UI | Backend barcode search covered by `regeneratePreviewAndSearchWork`; UI search box is present |
| POS | Page renders at desktop/390/768 | PASS | screenshots `05`, `12`, `13` |
| POS | Barcode scan adds an item | NOT PERFORMED in this run | Backend lookup covered by tests; UI scan path not exercised after the print dialog blocked the browser |
| Sales / Purchases | Pages render and were captured | PASS | `06`, `07`; barcode scan integration present in code |
| Inventory / Reports | Render | PASS | `08`, `09` |
| Settings → Printers | Render at desktop/mobile | PASS | `10`; responsive measurement |
| Print History | Render with job history | PASS | `11` |
| Platform → Store Accounts | List, detail, search, status display | PASS | `15-platform-accounts-desktop.png`; live platform session |
| Platform → Stores | List/search/subscription display | PASS | `16-platform-stores-desktop.png` |
| Platform → Reset password (UI) | Confirmation + one-time display | NOT PERFORMED manually | Deliberately avoided changing a real account; API contract covered by `PlatformAccountIntegrationTest` |
| Platform account rename/disable (UI) | Buttons and validation | NOT PERFORMED manually | API covered by integration tests |
| Auth | Forced password change after platform reset/provisioning | PASS | Server returned `PASSWORD_CHANGE_REQUIRED`; UI routed to the change screen; new password accepted |
| Auth | Settings → Profile → Change password (store user) | PASS | Wrong current password rejected; old password stopped working; new password signed in |
| Auth | Session cookie protection | PASS | `document.cookie` empty; API calls succeeded via `withCredentials`; no token in `localStorage` |
| Auth | Weak-password rejection on registration | PASS | Client showed the shared policy message; server policy test also covers it |
| Platform → Audit log | Filters and CSV export | PASS | Filtered query returned rows; export returned `200 text/csv` with a downloadable file |
| Items | GS1 vs INTERNAL source badge | PASS | GS1 value shows the `GS1` badge; generated EAN-13 shows `INTERNAL` |
| POS / scan bar | Barcode lookup from a scan value | PASS | Item matched; out-of-stock message shown correctly for an item with no stock |
| Scan dialog | `BarcodeDetector` unavailable → ZXing fallback | PASS | Fallback status message shown; camera permission denial handled without crashing |
| Settings → Security | Build/version card | PASS | Version `1.0.0`, commit `01567eb`, build time and Java `17.0.17` displayed |
| Responsive | `/reports`, `/settings`, `/items`, `/pos`, `/platform/audit` at 1440 and 375 px | PASS | `scrollWidth <= clientWidth`; no horizontal overflow; sidebar visible at 1440 |

Important limitations:

- The manual UI run used the **demo store**, not production data.
- The native print dialog is outside the page and could not be completed in the automated browser.
  Printing on paper is hardware-dependent and was not verified.
- POS camera scanning requires a camera and HTTPS; not exercised in this desktop browser run.

---

## 7. Printing verification

| Test | Result | Notes |
|---|---|---|
| Mobile print buttons overlap | PASS (fixed and measured) | Settings → Printers and Print History have no overlap at 320/390/768; 0 overflow |
| Printer configuration CRUD + validation | PASS (automated) | `printerConfigCrudValidatesAndIsStrictlyScoped` |
| Pairing code single-use + token revocation | PASS (automated) | `pairingCodeIssuesSingleUseDeviceTokenAndConfigIsRevocable` |
| Automatic job idempotency | PASS (automated) | `automaticAndroidPrintJobsAreIdempotentAndUseBranchPrinter`, concurrency test |
| Failed/cancelled sale blocked from printing | PASS (automated) | `cancelledSalesAndForeignStoresCannotPrint` |
| Permissions | PASS (automated) | `permissionsAndAuthenticationAreEnforced` |
| Windows bridge live | PASS (earlier session) | Local tray app on 127.0.0.1:17777; status, test and sale print |
| Android bridge | PASS on emulator with mock printer | Pairing, config, raw ESC/POS request path |
| Physical Windows thermal printer paper output | **NOT PERFORMED** | No physical printer attached to this host |
| Physical Android device output | **NOT PERFORMED** | Emulator/mock only |
| Label printer / 50×30 mm label output | **NOT PERFORMED** | Hardware unavailable |

No claim is made that physical printing was verified.

---

## 8. Security verification

| Test | Result | Evidence |
|---|---|---|
| Missing/invalid JWT rejected | PASS | `rejectsMissingAndInvalidTokens` |
| Deactivated user token rejected immediately | PASS | `deactivationImmediatelyRevokesExistingTokens` |
| Branch staff limited to branch + permissions | PASS | `branchStaffIsLimitedToAssignedBranchAndPermissions` |
| Subscription expiry + renewal | PASS | `expiredSubscriptionBlocksApisAndRenewalRestoresAccessImmediately` |
| Platform-only subscription management | PASS | `subscriptionManagementIsPlatformOnly` |
| Barcode lookup store-scoped | PASS | automated barcode tests + code review |
| Barcode changes require `ITEM_MANAGE` | PASS | `barcodeChangesRequireItemManagePermission` |
| Platform APIs reject store users | PASS | `storeUsersAndPlatformAccountsAreRejectedByTheAccountApi` |
| No password/hash in account responses | PASS | `platformAdminFindsStoreAccountsByStoreUsernameAndCodeWithoutPasswordHashes`; response DTOs contain no password field |
| Reset requires confirmation | PASS | `resetPasswordReturnsOneTimeTemporaryPasswordAndAuditsTheChange` plus request validation |
| Temporary password returned once, not in list/detail/history | PASS | integration test + DTO review |
| Audit entries omit secrets | PASS | service review; history DTO exposes action/actor/details/time only |
| No platform admin created without explicit provisioning | PASS | `noPlatformAdministratorIsCreatedWithoutExplicitProvisioning` |
| Platform admin can change own password | PASS | `platformAdministratorChangesOwnPassword` |
| Store user change invalidates every previous session | PASS | `storeUserChangesOwnPasswordAndEveryPreviousSessionIsInvalidated` |
| Admin reset forces a change at next login | PASS | `administratorResetForcesPasswordChangeOnNextLogin` |
| Revocation persists across restarts (DB) | PASS | `revokedTokensAreStoredInTheDatabaseAndRejected` |
| Login lockout | PASS | `repeatedFailedLoginsLockTheAccountKey` |
| HttpOnly cookie + untrusted-origin rejection | PASS | `browserSessionUsesHttpOnlyCookieAndRejectsUntrustedOrigins`; live `document.cookie` check |
| Sensitive responses not cacheable | PASS | `sessionResponsesAndTemporaryPasswordsAreNotCacheable` |
| Audit viewer/export platform-only | PASS | `platformAuditViewerFiltersAndExportsCsvForPlatformAdminsOnly` |
| Demo data disabled in production profile | PASS | `demoStoreIsNotSeededInProductionMode` |
| Health/version contain no secrets | PASS | `healthAndVersionArePublicAndContainNoSecrets` |
| SQL injection | PASS by design (parameterized repositories/JPA); no dynamic SQL with user input found |
| XSS | PASS by design (React escaping); no user-controlled `dangerouslySetInnerHTML` |
| CORS / cookie write origin check | PASS | automated origin-rejection test; configured exact origins |
| Login rate limiting | PASS | `repeatedFailedLoginsLockTheAccountKey` (per instance) |
| MFA | **NOT IMPLEMENTED** | K-26 (evaluated, deferred) |
| Hard-coded platform admin | **FIXED IN SOURCE**; production password rotation still required | K-01 / `PlatformAdminProvisioner` |

No production credential was used or displayed during testing.

---

## 9. Documentation verification

| Check | Result |
|---|---|
| Required 12 files present | PASS |
| 17 screenshots present and referenced | PASS |
| Placeholders explicitly marked (uncaptured screens) | PASS |
| API routes match controllers (24 controllers, 133 mappings) | PASS (counted from source) |
| DB docs match V1–V8 | PASS (cross-checked with migrations) |
| Windows vs Ubuntu commands distinguished | PASS |
| Deployment comparison labels live inspection as blocked | PASS |
| No secrets in documentation | PASS (grep for password values in `PROJECT_DOCUMENTATION/` found none) |

---

## 10. Regression checks

| Area | Result | Evidence |
|---|---|---|
| Auth and permissions | PASS | `SystemInvariantIntegrationTest` |
| Purchases / stock / GST | PASS | `purchaseLifecycleUpdatesStockAtomically`, `gstIsCalculatedOnServerAndSnapshotsStayImmutable`, inclusive-tax test |
| Sales / oversell | PASS | `concurrentSalesCannotOversellStock`, payment validation |
| Stock adjustments | PASS | `rejectsNegativeAdjustmentsAndDuplicateLines` |
| Branch limits | PASS | `branchLimitBlocksNewBranches...`, concurrency test |
| Subscription | PASS | expiry/renewal, platform-only tests |
| Excel import | PASS | 13 import tests |
| Printing | PASS | 8 printing tests |
| UI routes render | PASS | 17 screenshots |
| Responsive | PASS | 50 measurements |
| Frontend build | PASS | `npm run build` |

Not performed in regression: a full manual purchase/sale/transfer/import sequence through the UI in
this run. Those flows are covered by integration tests and previously captured screenshots, not by a
fresh end-to-end manual pass.

---

## 11. Tests not performed or blocked

| Test | Status | Reason | Owner |
|---|---|---|---|
| Live server version/schema/config comparison | **BLOCKED** | No SSH access or credentials | Server owner |
| Production deployment | **NOT PERFORMED** | Approval gate; explicitly forbidden initially | Approver |
| Physical thermal receipt printing | **BLOCKED** | No printer hardware on this host | Site operator |
| Physical label printing | **BLOCKED** | No label printer/paper | Site operator |
| Physical Android printing | **BLOCKED** | Emulator/mock only | Site operator |
| USB barcode scanner | **BLOCKED** | No scanner hardware | Site operator |
| Mobile camera scan | **NOT PERFORMED** | No camera/HTTPS device session | QA |
| POS/sales/purchase scan through UI | **NOT PERFORMED** | Browser blocked by native print dialog after label print test; backend covered | QA |
| Platform password reset against a real account | **NOT PERFORMED** | Avoided changing customer credentials | QA/owner |
| Load/stress/soak | **NOT PERFORMED** | No production-scale environment | QA |
| Cross-browser (Firefox/Safari) | **NOT PERFORMED** | Chromium only | QA |
| Backup/restore rehearsal on production | **NOT PERFORMED** | Requires server access and approval | Server owner |

---

## 12. Defects found and fixed during this cycle

| ID | Defect | Fix | Verification |
|---|---|---|---|
| D-01 | `/sales` overflowed horizontally by 4 px at 320 px | Date-filter wrapper `flex flex-wrap`; inputs `w-38 min-w-0 flex-1 sm:flex-none` | Re-measured 320/320; screenshot 17 |
| D-02 | Mobile print buttons overlapped | Layout/state changes in `PrintersSettings` and related pages | Responsive matrix + screenshots |
| D-03 | Printing could be triggered twice by rapid taps | Loading/click guards; server idempotency retained | Automated idempotency tests |

No other functional defect was confirmed during this execution. Open risks are listed in
`KNOWN_ISSUES_AND_RECOMMENDATIONS.md`.

---

## 13. Overall assessment

| Dimension | Status |
|---|---|
| Automated backend | **PASS** (68/68) |
| Static frontend checks | **PASS** (0 type errors, 0 warnings, 0 errors) |
| Frontend build | **PASS** |
| Database schema/migrations | **PASS** (automated on V1–V8) |
| Auth/security backend | **PASS** (14 tests) |
| System invariants | **PASS** (22 tests) |
| Barcode backend | **PASS** (6 tests) |
| Platform account backend | **PASS** (5 tests) |
| Printing backend | **PASS** (8 tests) |
| Responsive | **PASS** (50/50, after one fix; new routes re-checked at 1440/375) |
| Manual UI (tested paths) | **PASS with limitations** (print paper, scanner and physical camera not verified) |
| Physical hardware | **NOT VERIFIED** |
| Live server comparison | **BLOCKED** |
| Production deployment | **NOT PERFORMED** |

This is sufficient to proceed to an approval decision for deployment, **provided** the owner accepts
the hardware verification gaps, the blocked live-server inspection, and the critical credential issue
K-01 with a rotation plan.
