# Silent Thermal Printing — Windows PC and Android

**Status:** implemented and tested, 2026-11-01
**Scope:** printing only. No existing module, workflow, API or database table was replaced.

Receipts print silently — no browser dialog, no “PRINT NOW”, no printer picker. A small local
companion app (the *print bridge*) does the printing:

| Device | Bridge | Target printer | Port |
|---|---|---|---|
| Windows PC | `ItemManagementPrintBridge.exe` (tray app) | this PC’s default installed printer | 17777 |
| Android phone/tablet | `ItemManagementPrintBridge.apk` (foreground service) | the store’s Wi-Fi/LAN printer IP + port configured on the website | 17778 |

The website never opens a print dialog on these platforms. If the bridge is missing or unreachable
the page shows a clear inline error and the job is recorded as `FAILED` in Print History.

## 1. Receipt flow

1. A sale (or a manual receipt print) is saved by the backend first.
2. The website asks the backend for a print job (`POST /api/printing/jobs`).
3. The backend validates store/branch ownership and creates the job.
   Automatic jobs use the key `AUTO:SALE_RECEIPT:<channel>:<branchId>:<saleId>`; the unique key
   makes a refresh or double click return the existing job with `duplicate: true` instead of
   printing twice.
4. The website sends the job to the bridge at `http://127.0.0.1:<port>` with the `X-Bridge-Key`
   header and the receipt as text lines (32 columns for 58 mm, 48 for 80 mm).
5. The bridge generates ESC/POS commands and prints:
   - Windows: raw ESC/POS to the default printer (GDI fallback for non-thermal drivers).
   - Android: TCP to the configured printer IP:port (default 9100).
6. The website reports `PRINTED` or `FAILED` back to the backend; Print History shows the result,
   attempts, printer and error.

Bridges also cache the last 30 minutes of job keys, so a retried HTTP call cannot print twice.

## 2. Database

Migration `backend/src/main/resources/db/migration/V6__printing.sql` adds four tables:
`printer_configs`, `print_pairing_codes`, `print_devices`, `print_jobs`.
All rows are scoped by `store_id` (and `branch_id` where relevant).

New permissions: `PRINTER_VIEW` and `PRINTER_MANAGE`, granted to `STORE_OWNER` (and to other roles
through the existing role editor). No existing table or permission was changed.

## 3. Backend API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET/POST/PUT/DELETE | `/api/printing/printers` | JWT + `PRINTER_VIEW`/`PRINTER_MANAGE` | Printer CRUD and test results |
| POST | `/api/printing/printers/pairing-code` | JWT + `PRINTER_MANAGE` | One-time code (10 min, single use, SHA-256 stored) |
| GET/DELETE | `/api/printing/devices` | JWT + `PRINTER_VIEW`/`PRINTER_MANAGE` | Paired bridges, revoke |
| POST | `/api/printing/devices/pair` | one-time code | Bridge pairing, returns opaque device token |
| GET | `/api/printing/device/config` | `X-Device-Token` | Bridge pulls its branch printer list |
| POST/GET | `/api/printing/jobs` | JWT + `SALES_VIEW`/`SALES_MANAGE` | Create/list jobs |
| POST | `/api/printing/jobs/{id}/reprint` | JWT | Manual reprint (new `MANUAL:<uuid>` job) |
| PUT | `/api/printing/jobs/{id}/status` | JWT | Bridge result (`PRINTED`/`FAILED`) |

## 4. Security

- Website ↔ backend stays on the existing JWT and role permissions; store/branch come from the
  token, never from request parameters.
- Website ↔ bridge requires a per-device bridge key (32 random bytes, compared by SHA-256 digest
  in constant time) and an origin allowlist.
- Bridges listen on `127.0.0.1` only; they are not reachable from the LAN.
- The Android bridge can additionally store one allowed website origin; requests from any other
  origin get `403`. Leave it blank to accept any origin that presents the correct key.
- Pairing codes are one-time, expire in 10 minutes, and are stored hashed. Device tokens are stored
  hashed and can be revoked from **Settings → Printers → Paired bridges**.
- Printer IP/host and port are validated by the backend. An Android bridge rejects a job whose
  printer does not match its synced branch configuration.
- Cross-store printer access, foreign-store sales and cancelled sales are rejected (covered by
  `PrintingIntegrationTest`).

## 5. Website configuration (store owner)

1. Sign in as a user with `PRINTER_MANAGE`.
2. Open **Settings → Printers**.
3. **Android/LAN printer:** under *Store printers* choose the branch, click **Add printer**, enter
   name, printer IP or hostname (e.g. `192.168.1.50`), port `9100`, paper width (58/80 mm), purpose
   (Sale receipts / Purchase invoices / Reports / All documents) and status. Save. Use **Test
   connection** on an Android device to send a test slip.
4. **Pair an Android bridge:** click **Generate code**, open the bridge app on the tablet/phone,
   enter the website address and the code, tap **Pair device**. The app then syncs this branch’s
   printers automatically every 5 minutes.
5. **Windows bridge:** install it (section 6), then paste its bridge key into the *Print bridge on
   this device* card and click **Save bridge**. Port stays `17777`. **Check status** shows the
   default printer; **Test default printer** prints a test slip.
6. Printers and paired bridges are listed per branch and store; other stores cannot see them.

## 6. Windows bridge

Artifacts: `bridges/windows/dist/ItemManagementPrintBridgeSetup.exe` (installer) and the published
executable/source under `bridges/windows/ItemManagementPrintBridge/`.

Install:

1. Run `ItemManagementPrintBridgeSetup.exe` (per-user, no admin prompt). It installs to
   `%LOCALAPPDATA%\ItemManagementPrintBridge`, registers **Start with Windows**, creates a Start
   Menu shortcut and starts the tray app.
2. Right-click the tray icon to see status, default printer, last result, copy the bridge key, edit
   allowed websites, open the log folder or exit.
3. Paste the key into **Settings → Printers** on the website.

Configuration is stored in `%LOCALAPPDATA%\ItemManagementPrintBridge\config.json`; the log is
`bridge.log` in the same folder. Default allowed websites are
`http://localhost:5173`, `http://127.0.0.1:5173`, `http://localhost:4173`, `http://127.0.0.1:4173`.
Add your production website origin through the tray menu (**Edit allowed websites**) and restart the
bridge. The bridge always prints to whatever printer is set as the Windows default — the website
does not ask for, or send, a printer name or IP.

Rebuild: `powershell -ExecutionPolicy Bypass -File bridges/windows/build-installer.ps1`
(self-contained `win-x64`, single file).

## 7. Android bridge

Artifact: `bridges/android/dist/ItemManagementPrintBridge.apk` (signed release build; package
`com.itemmanagement.printbridge`, version 1.0.0, minSdk 24, targetSdk 34).

Install and pair:

1. Install the APK on the phone/tablet (allow installation from unknown sources if prompted).
2. Open **Item Management Print Bridge**; it starts the foreground service and shows the bridge key
   and `127.0.0.1:17778`.
3. On the website generate a pairing code, then in the app enter the website base URL
   (for example `https://store.example.com`), the code and a device name; tap **Pair device**.
4. After pairing the app lists the store, branch and receipt printers. **Refresh printer config**
   syncs immediately; otherwise it syncs every 5 minutes.
5. In **Settings → Printers** on the Android browser, paste the bridge key and click **Save bridge**
   (port `17778`). Optionally set **Allowed website** in the app.
6. Use **Allow background service** to exempt the app from battery optimisation, and keep
   **Start bridge when Android starts** enabled so billing works after a reboot.

The app has no store UI: it only prints. The printer must be reachable from the tablet’s Wi-Fi
(stable IP recommended) and support ESC/POS on TCP 9100 (most Wi-Fi/LAN receipt printers do).

Rebuild: open `bridges/android` in Android Studio or run
`powershell -ExecutionPolicy Bypass -File bridges/android/build-apk.ps1`. Release signing uses
`bridges/android/keystore.properties`. See `bridges/android/README.md` for details and the
keystore certificate fingerprint.

## 8. Printer setup

1. Give the printer a fixed LAN IP (DHCP reservation or static IP).
2. Confirm it accepts ESC/POS raw jobs on port 9100 (the usual “POS”/“RAW” port).
3. Add it in **Settings → Printers** and run **Test connection** from an Android device on the same
   Wi-Fi.
4. For Windows, install the printer’s Windows driver and set it as the default printer; the bridge
   uses it directly and needs no IP.

## 9. Receipt content

Sale receipts keep the existing content: store name, branch, GSTIN, invoice number and date,
customer, item lines, subtotal/discount/taxable value, CGST+SGST or IGST, total, payment method and
balance. Text is ASCII-safe (`₹` is printed as `Rs`) and wrapped to the configured paper width.
The same receipt line builder is used for both platforms.

## 10. Print History and reprint

**Print History** (`/print-history`) lists every job with date, document, branch, printer, bridge,
status, attempts and error. Windows rows show *Windows default printer*; Android rows show the
configured LAN printer. **Reprint** creates a new manual job, so retrying a failed job is explicit
and recorded.

## 11. Tests performed

| Area | Result |
|---|---|
| Backend test suite (`./gradlew test`) | 42 tests, 0 failures — includes 8 printing integration tests (CRUD + scoping, test results, single-use pairing/revocation, payload validation, AUTO idempotency, cancelled/foreign-store rejection, concurrency, permissions) |
| Frontend `npm run build` | passed |
| Frontend `npm run lint` | 0 errors, 10 pre-existing warnings |
| `npm audit` | 0 vulnerabilities |
| Windows bridge live | auth 401 without/with wrong key; origin 403; preflight 204 with CORS/PNA headers; status, test slip, receipt, duplicate suppression, invalid payload rejection; installer, autostart registry and default printer `POS-80C` verified |
| Windows website flow | bridge key saved in website, status shows `Bridge 1.0.0 · Default printer: POS-80C`, test print printed through spooler, sale `SAL-1001` auto-printed (job 1 `PRINTED`), manual reprint (job 2), bridge-off failure (job 3 `FAILED` with clear error, no dialog), retry after restart (job 4 `PRINTED`) |
| Android emulator (API 33) | APK installed and launched; pairing code consumed; config synced (store, branch, `Android Test Printer — 10.0.2.2:9100 · 80 mm · RECEIPT`); status/test/print/duplicate/invalid-payload/unconfigured-printer/wrong-key/origin checks passed; START/STOP service lifecycle passed; 80 mm and 58 mm (32-column) ESC/POS captured by a mock TCP printer |
| Android website flow (Chrome on emulator) | login, sale `SAL-1002` auto-printed via the Android bridge (job 5 `ANDROID/AUTO/PRINTED`, 658-byte ESC/POS received by the mock printer), manual **Print receipt** (job 6 `ANDROID/MANUAL/PRINTED`) |
| Responsive | `/settings?tab=printers` and `/print-history` measured at 320×568, 360×800, 390×844, 430×932, 600×960, 768×1024, 1024×768, 1366×768, 1440×900, 1920×1080 — no body horizontal overflow at any size; mobile menu, Add/Edit printer modal (320 px wide, buttons inside), validation, filters and the horizontally scrollable job table all usable |
| Physical paper | **Not visually verified.** Windows jobs were confirmed accepted and spooled to `POS-80C` and the Android path was confirmed by captured ESC/POS bytes on a mock TCP printer. No physical printer paper output was inspected. |

## 12. Troubleshooting

| Symptom | Fix |
|---|---|
| *Enter the bridge key…* | Paste the key shown by the bridge app into **Settings → Printers**. |
| *The print bridge key is invalid* | Copy the key again from the bridge app/tray menu; the key is per device. |
| *Cannot reach the Windows print bridge…* | Start **Item Management Print Bridge** from the Start Menu; check port 17777. |
| *Cannot reach the Android print bridge…* | Open the bridge app; check port 17778 and that the service is running. |
| *No receipt printer is configured…* | Add an active *Sale receipts* or *All documents* printer for the branch, then refresh the Android app. |
| Printer not printing though status is fine | Confirm the printer is on the same network, IP is fixed, and the test slip prints. |
| Windows prints garbage | The driver is not raw-compatible; set `printMode` to `gdi` in `config.json` (or install the vendor driver). |
| Android jobs fail after reboot | Enable *Start bridge when Android starts* and exempt the app from battery optimisation. |

## 13. Known limitations

- Physical printer paper output was not visually inspected; only spooler/bridge acceptance and
  captured ESC/POS bytes were verified.
- Android was tested on an emulator with a mock TCP printer, not on a physical tablet and LAN
  printer.
- Bluetooth/USB printers are not supported; only Windows default printers and network printers on
  TCP (ESC/POS) are.
- The Android bridge allowlist stores one website origin; blank means “any origin with the key”.
- On narrow screens the settings tab strip scrolls horizontally (shared `Tabs` component, not
  specific to printing).
