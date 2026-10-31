# Item Management Print Bridge (Android)

A small companion app that prints store receipts silently on Wi-Fi/LAN thermal
printers (58 mm and 80 mm, ESC/POS). It has no billing screens and never shows a
print dialog. The website sends the receipt to the bridge after the sale is
saved; the bridge writes it straight to the printer configured for the branch.

## What it does

- Runs a foreground service listening only on `127.0.0.1:17778`.
- Requires the bridge key shown in the app on every request.
- Pairs once with the store using a one-time code from Settings → Printers.
- Pulls the authorised store/branch printer list and refreshes it every 5 minutes.
- Prints with raw ESC/POS over TCP, supports 58 mm and 80 mm paper widths,
  alignment, bold, double size and paper cut.
- Suppresses duplicate jobs for 30 minutes so a retried request cannot print twice.
- Starts at boot when enabled and keeps running when the screen is off.

## Install

1. Copy `dist/ItemManagementPrintBridge.apk` to the phone or tablet and install it
   (allow installs from this source when Android asks).
2. Open **Print Bridge**. The service starts immediately and a notification shows
   it is running.
3. In the store website (same Wi-Fi), open **Settings → Printers**:
   - Generate a one-time pairing code.
   - Copy the bridge key shown in the app and paste it into the website bridge card.
   - Keep the bridge port at `17778` (change it in both places if needed).
4. In the app enter:
   - the website address, for example `https://store.example.com` or
     `http://192.168.1.10:8080` (development server),
   - the pairing code,
   - a device name (defaults to the phone model).
   Tap **Pair device**. The store, branch and printers are shown.
5. Add/verify the branch printer in **Settings → Printers** (IP, port 9100,
   paper width, purpose Receipt). Tap **Refresh printer config** in the app.
6. Use **Test default printer** in the website or **Refresh** then a sale to verify
   printing.

Every sale saved from POS or Sales Entry prints automatically. Receipt buttons on
Sales History reprint silently. There is no Print Now dialog.

## Background reliability

- Tap **Allow background service** and allow the app to ignore battery
  optimisation, especially on Xiaomi/Oppo/Vivo/Samsung phones.
- Keep the notification visible: Android may stop a foreground service if the
  user swipes the app away on some manufacturers.
- The app starts at boot when **Start bridge when Android starts** is on and the
  device is paired.

## Security

- The HTTP bridge binds to loopback only; it is not reachable from the LAN.
- Every request needs the 43-character bridge key; the key is compared using a
  constant-time digest.
- Optionally set **Allowed website** to your store origin. With it set, requests
  from any other website origin are rejected.
- The device is paired with a single-use, hashed, 10-minute code; the resulting
  device token is stored on the phone and can be revoked from Settings → Printers.
- The app will not print when the device has been revoked or before pairing.
- Cleartext HTTP is enabled because LAN thermal printers and local development
  servers do not use TLS. Use HTTPS for the store website in production.

## Build

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35.

```powershell
cd bridges/android
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleRelease
```

or:

```powershell
.\build-apk.ps1
```

The signed APK is written to `dist/ItemManagementPrintBridge.apk`.

Signing uses `keystore/printbridge-release.jks` with credentials in
`keystore.properties`. Replace both before distributing publicly, keep the
keystore backed up, and never share the password outside the store owner's team.

## Troubleshooting

| Message | Fix |
| --- | --- |
| Cannot reach the store server | Check the website address, Wi-Fi and that the backend is running. |
| Pairing code is invalid or has expired | Generate a new code in Settings → Printers; codes expire after 10 minutes and work once. |
| This print bridge was revoked | Generate a new code and pair again. |
| The printer … refused the connection | Check the printer IP/port and that the phone is on the same network. |
| No receipt printer configured | Add an active receipt printer for the branch, then refresh the app. |
| Bridge stopped after a while | Allow battery optimisation exemption and auto-start for the app. |
