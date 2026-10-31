Item Management Print Bridge (Windows)
======================================

This small tray application lets the store website print receipts silently on
this PC's Windows default printer. It only listens on 127.0.0.1:17777 and every
request must carry the bridge key shown by the tray app.

Install
-------
Run ItemManagementPrintBridgeSetup.exe. The installer copies the bridge to
%LOCALAPPDATA%\ItemManagementPrintBridge, adds it to Windows startup for the
current user, creates a Start Menu shortcut and starts it in the system tray.

Connect the website
-------------------
1. Open the store website on this PC.
2. Go to Settings > Printers.
3. In the Windows print bridge card, paste the bridge key from the tray app
   (tray icon > Show bridge key... > Copy) and click Save.
4. Click Check status. The default printer name should appear.
5. Click Test default printer to send a test slip.

The store website prints automatically after every successful sale. No browser
print dialog, printer selection or confirmation is shown.

Printer behaviour
-----------------
- The bridge always uses the printer currently set as the Windows default.
- Thermal style default printers (POS, 80C, XP, KOT, etc.) receive raw ESC/POS.
- Other default printers receive the receipt through the normal Windows driver.
- Print mode can be forced to "raw", "gdi" or "auto" in config.json.

Configuration
-------------
File: %LOCALAPPDATA%\ItemManagementPrintBridge\config.json

  port            Local port (default 17777). Restart the bridge after changing.
  key             Bridge key. Delete it and restart to generate a new key.
  printMode       auto (default), raw or gdi.
  allowedOrigins  Website addresses allowed to call the bridge. Add the address
                  used by your store (for example https://store.example.com),
                  save the file, then use tray > Reload settings.
  startWithWindows  true or false.

Logs: %LOCALAPPDATA%\ItemManagementPrintBridge\bridge.log

Troubleshooting
---------------
- "Cannot reach the Windows print bridge": open the tray app. If it is not
  running, start it from the Start Menu. Keep the port at 17777 unless you also
  change it in Settings > Printers.
- "The bridge key is invalid": copy the key again from the tray app.
- "This website is not allowed": add the website address to allowedOrigins.
- "No default printer": set a default printer in Windows Settings > Printers.
- Nothing prints: open the Windows print queue and check the printer is online.
