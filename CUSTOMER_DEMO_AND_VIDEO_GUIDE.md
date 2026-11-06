# Customer Demo & Video Recording Guide

**Item Management System — production release v1.0.0**
Prepared 2 November 2026 for demonstrations of the deployed system at `https://hiremathwebstudio.in`

> **Accuracy note.** Every screen name, button, route, role and workflow in this guide was checked against the v1.0.0 source code and the deployed release. Features that do not exist are listed as **not available** in Section 9 and are never presented as working. Hardware-dependent steps are marked **[requires hardware verification]**. No customer data, real passwords, tokens, server addresses or configuration values appear in this document.

---

## Table of contents

1. [How to use this guide](#1-how-to-use-this-guide)
2. [What is the Item Management System?](#2-what-is-the-item-management-system)
3. [Before you record: demo setup](#3-before-you-record-demo-setup)
4. [Complete customer demonstration, module by module](#4-complete-customer-demonstration-module-by-module)
   - [A. Store registration and onboarding](#a-store-registration-and-onboarding)
   - [B. Login, dashboard and navigation](#b-login-dashboard-and-navigation)
   - [C. Store and branch management](#c-store-and-branch-management)
   - [D. User management and permissions](#d-user-management-and-permissions)
   - [E. Master data management](#e-master-data-management)
   - [F. Barcode management](#f-barcode-management)
   - [G. Excel item import](#g-excel-item-import)
   - [H. Supplier and purchase management](#h-supplier-and-purchase-management)
   - [I. Inventory management](#i-inventory-management)
   - [J. Stock transfer between branches](#j-stock-transfer-between-branches)
   - [K. POS and quick billing](#k-pos-and-quick-billing)
   - [L. Sales management](#l-sales-management)
   - [M. GST and taxation](#m-gst-and-taxation)
   - [N. Reports and business analysis](#n-reports-and-business-analysis)
   - [O. Printing and printer configuration](#o-printing-and-printer-configuration)
   - [P. Platform Admin](#p-platform-admin)
   - [Q. Audit, security and subscriptions](#q-audit-security-and-subscriptions)
5. [Five complete real-world business workflows](#5-five-complete-real-world-business-workflows)
6. [Full video script (18 chapters)](#6-full-video-script-18-chapters)
7. [Short 5–10 minute customer introduction](#7-short-510-minute-customer-introduction)
8. [Sales presentation kit](#8-sales-presentation-kit)
9. [Feature accuracy: implemented, partial, unavailable](#9-feature-accuracy-implemented-partial-unavailable)
10. [Security and privacy during recording](#10-security-and-privacy-during-recording)
11. [Screen and navigation reference](#11-screen-and-navigation-reference)
12. [Final pre-publish checklist](#12-final-pre-publish-checklist)

---

## 1. How to use this guide

This is one document with two jobs:

1. **A preparation manual** (Sections 2, 3, 5, 8, 9) — read this before you record.
2. **A word-for-word recording script** (Sections 4 and 6) — keep this on a second screen while recording.

**Conventions used in this guide**

| Convention | Meaning |
|---|---|
| **Bold label** | The exact text you will see on screen |
| `[Button]` | A button or menu item to click |
| **Say:** “…” | Word-for-word narration you can speak |
| **[requires hardware verification]** | The software path exists, but physical output (printer, scanner, camera, Android device) was not tested on the customer’s hardware |
| **Not available** | The feature does not exist in v1.0.0; do not promise it |

**Suggested recording order:** follow Section 6 chapter by chapter. Section 4 explains each module in more depth and gives shorter narration you can use in a live sales meeting when you are not following the full script.

**Time needed:** about 45 minutes for the full walkthrough, or 8–9 minutes for the short version.

---

## 2. What is the Item Management System?

### 2.1 In one sentence

The Item Management System is a **multi-branch inventory and billing platform** for retail and trading businesses. It keeps one item catalogue, tracks stock branch by branch, records purchases and sales with GST, prints invoices and thermal receipts, and gives the owner clear reports — all from one web application.

### 2.2 Who can use it

It fits businesses that buy stock and sell it across one or more locations:

- Grocery stores, supermarkets and provision stores
- Dairy and FMCG distributors
- Wholesale and trading businesses
- Retail chains with a main store and branch outlets
- Pharmacies and stationery stores that use item codes and barcodes
- Any small or medium business that needs purchase, stock and billing control without full accounting software

It is designed for the Indian market: amounts are in ₹, GST slabs are built in, and invoices show CGST/SGST or IGST.

### 2.3 Business problems it solves

| Everyday problem | How the system helps |
|---|---|
| Stock is counted in registers and does not match the shelf | Every purchase, sale, transfer and adjustment updates stock immediately |
| The counter cannot find an item quickly | Search by name, item code or barcode; scan barcodes at billing |
| The owner does not know what is happening at the branch | Dashboard and branch reports show sales, purchases, stock value and low stock |
| Purchase costs are guessed | Purchases record rates, discounts and GST, and the system keeps a weighted-average cost |
| The same item is priced differently at each outlet | One item master rate for POS, with branch-level cost and selling-price records |
| GST is calculated by hand and mistakes happen | GST is calculated per line and saved with the invoice, so old bills stay correct |
| Staff see data they should not see | Four built-in roles, custom roles, branch assignment and store-level data isolation |
| No record of who changed what | Platform audit log records security-sensitive actions |
| Bills are written by hand or printed slowly | POS billing, invoice print, and optional silent thermal printing through a local bridge |

### 2.4 What makes it different

- **Multi-store, multi-branch from day one:** one deployment serves many independent stores; each store’s data is isolated.
- **Barcode-first operations:** automatic barcode generation, label printing, USB-scanner input and camera scanning in POS, purchases and manual sales.
- **Preview-first Excel import:** upload a file, see every row validated, and import only when everything is correct.
- **Stock that cannot go negative:** sales and dispatches are checked against available stock.
- **GST documents with snapshots:** tax rates are copied onto each invoice line, so changing a tax rate later does not change history.
- **Silent thermal printing:** an optional local Windows or Android bridge prints receipts without the browser print dialog. **[requires hardware verification]**
- **Platform console:** onboard stores, manage subscriptions and branch limits, reset passwords, and review the audit log.

### 2.5 How multi-store and multi-branch work

- A **store** is one business (one tenant). It has its own catalogue, suppliers, users, roles, taxes, branches and reports.
- A **branch** is a physical location inside a store. Stock, purchases, sales, transfers and adjustments are recorded branch by branch.
- A **store owner** sees all branches. A **branch manager** or **branch staff** user sees only the branches assigned to them.
- A **Platform Admin** is outside any store. This account onboards stores and manages subscriptions, but cannot read store business data through the store screens.

### 2.6 What the system is not

Say this clearly when a customer asks. The system is **not**:

- Full accounting software (no ledgers, trial balance, P&L or balance sheet)
- GST filing software (no GSTR-1/GSTR-3B submission, e-invoice or e-way bill)
- A returns/credit-note system (a sale or purchase can be cancelled as a whole, but partial returns are not supported)
- A customer relationship or receivables system (customer name and mobile are free text; there is no customer ledger or payment collection)
- An offline or mobile app (it needs a connection; the Android app is only a print bridge)

Being honest here builds trust and prevents disappointment after the sale.

---

## 3. Before you record: demo setup

### 3.1 Use a separate demo store — never real customer data

The production system already contains real stores. **Do not record inside a real customer’s store.** Use one of these:

| Option | When to use | Notes |
|---|---|---|
| **A dedicated demo store on the production site (recommended)** | You want the recording to show the real deployed product | Register a new store through **Register your business**. It is a separate tenant; no customer data is touched. Because stores cannot be deleted from the UI, treat this as a permanent sales-demo store and reuse it. |
| A local or staging copy | You want to practise without adding demo data to production | Set `SEED_DEMO=true` only in a non-production environment. Never enable demo seeding on production. This guide's screen names still apply. |
| Existing empty test store | You already have an empty test tenant | A store named “temp store” looks unprofessional on video; rename it through **Store Settings → Store profile → Store name** if you use it. |

**Demo store used in this script:** **Sri Ram Traders**, store code **SRIRAM01**, owner **Ravi Kumar**, Karnataka. All names, emails, phone numbers and GSTINs below are fictional. Replace them with your own fictional demo data if you prefer.

### 3.2 Demo accounts

Create these through the UI. Use one demo password everywhere, for example `Demo@2026Shop` — **demo only, never a real customer password**. The password must contain upper case, lower case, a number and a special character, and must not contain spaces.

| Role | Username | Purpose in the demo | Branch access |
|---|---|---|---|
| Store Owner | `owner` | Full store demo, settings, reports, approvals | All branches |
| Store Admin | `admin` | Show a delegated administrator; can manage users but not store/branch/roles | All branches |
| Branch Manager | `manager` | Show branch-level operations; creates purchases, adjusts stock, dispatches transfers | MAIN + BLR |
| Branch Staff | `staff` | Show cashier access: POS, sales history | MAIN |

**Platform Admin:** use your existing platform account only for Chapter 16. Never type its password on camera.

> **Note:** Chapter 16 resets the `staff` account password to demonstrate recovery. That invalidates `staff` sessions and forces a password change at its next sign-in. Do not reset `owner` during recording — you would be signed out of the store you are demonstrating. After recording, set a fresh demo password for `staff` through Store Settings → Users if you need the account again.

### 3.3 Baseline demo data (create once, before recording)

Prepare this data a few hours before recording, **dated today**, so the dashboard and reports already look alive. The live demo then adds new transactions on top.

**Categories:** Dairy, Grocery, Snacks, Beverages. (Home Care is created later by the Excel import, to demonstrate automatic category creation.)

**Suppliers**

| Supplier | Mobile | GSTIN (fictional) | State | Why |
|---|---|---|---|---|
| Nandini Distributors | 98765 00011 | 29AAAAA0000A1Z5 | Karnataka | Intra-state purchase → CGST + SGST |
| Chennai Snacks Depot | 98765 00012 | 33BBBBB0000B1Z5 | Tamil Nadu | Inter-state purchase → IGST |

**Items** (single master rate; GST from Tax Master; minimum stock for low-stock demo)

| Code | Item name | Category | Unit | Rate ₹ | GST | Min stock |
|---|---|---|---|---|---|---|
| MILK1L | Nandini Toned Milk 1L | Dairy | LITER | 60 | GST 5% | 10 |
| CURD500 | Nandini Curd 500g | Dairy | PACKET | 40 | GST 5% | 8 |
| RICE5K | Sona Masoori Rice 5kg | Grocery | PACKET | 450 | GST 5% | 5 |
| OIL1L | Sunflower Oil 1L | Grocery | LITER | 150 | GST 5% | 6 |
| CHIPS50 | Masala Potato Chips 50g | Snacks | PACKET | 20 | GST 18% | 20 |
| DRINK750 | Soft Drink 750ml | Beverages | BOTTLE | 40 | GST 28% | 12 |
| TEA250 | Premium Tea Powder 250g | Beverages | PACKET | 120 | GST 5% | 30 |

**Baseline transactions (all dated today, BEFORE recording)**

1. **Purchase PUR-1001** — branch MAIN, supplier Nandini Distributors, supplier invoice `ND-1042`, status **Completed**:
   - Milk 100 × ₹48
   - Curd 50 × ₹32
   - Rice 20 × ₹380
   - Oil 24 × ₹120
   - No bill discount, GST **Exclusive**. Expected: subtotal ₹16,880, CGST ₹422, SGST ₹422, total ₹17,724.
2. **Purchase PUR-1002** — branch MAIN, supplier Chennai Snacks Depot, supplier invoice `CS-778`, status **Completed**, GST **Exclusive**:
   - Chips 60 × ₹14 (18%)
   - Soft drink 48 × ₹30 (28%)
   - Tea 20 × ₹95 (5%)
   - Expected: subtotal ₹4,180, IGST ₹649.40, total ₹4,829.40.
3. **POS sale S1** — branch MAIN, Cash:
   - Milk 2, Curd 1, Chips 2, Soft drink 1
   - Expected: subtotal ₹240, CGST ₹13.20, SGST ₹13.20, total ₹266.40.
4. **Manual sale S2** — branch MAIN, customer **Ramesh**, mobile `98450 00001`, customer state **Tamil Nadu**, payment **UPI**:
   - Rice 1 × ₹450 with line discount ₹20 (net ₹430)
   - Oil 2 × ₹150
   - Expected: taxable ₹730, IGST ₹36.50, total ₹766.50. (Inter-state sale demonstrates IGST.)
5. **Manual sale S3** — branch MAIN, customer **Anita**, mobile `98450 00002`, customer state **Karnataka**, payment **Credit**:
   - Chips 3 × ₹20
   - Expected: taxable ₹60, CGST ₹5.40, SGST ₹5.40, total ₹70.80, balance ₹70.80.
6. **Adjustments** — branch MAIN:
   - Stock out **2 Milk**, reason `Damaged in transit`
   - Stock in **5 Tea**, reason `Physical count correction`

**Stock after baseline (this is your checkpoint before recording):**

| Item | MAIN | BLR |
|---|---:|---:|
| Milk | 96 | 0 |
| Curd | 49 | 0 |
| Rice | 19 | 0 |
| Oil | 22 | 0 |
| Chips | 55 | 0 |
| Soft drink | 47 | 0 |
| Tea | 25 | 0 |

Tea at 25 with a minimum of 30 is intentionally **low stock**.

### 3.4 Step-by-step baseline preparation

Do this once. Every step uses only the normal UI.

1. Open `https://hiremathwebstudio.in/register`.
2. Fill **Store code** `SRIRAM01`, **Store name** `Sri Ram Traders`, **Owner name** `Ravi Kumar`, **First branch name** `Main Branch`, **Owner email** `demo.owner@sriram.example`, **Mobile number** `9876500001`, **Store address** `12 MG Road, Bengaluru, Karnataka 560001`, **GSTIN** `29ABCDE1234F1Z5`, **State** `Karnataka`, **Username** `owner`, **Password** `Demo@2026Shop`. Click **[Create store]**.
3. Sign in with store code `SRIRAM01`, username `owner`.
4. **Store Settings → Store profile**: confirm details; leave **Tax inclusive pricing** off (Exclusive). Click **[Save changes]** if you edit anything.
5. **Store Settings → Branches**: edit **MAIN** and confirm state `Karnataka`, address and contact number. (The branch state drives CGST/SGST vs IGST.)
6. **Store Settings → Users**: create `admin` (Store Admin, all branches), `manager` (Branch Manager, MAIN), `staff` (Branch Staff, MAIN).
7. **Categories**: create Dairy, Grocery, Snacks, Beverages.
8. **Suppliers**: create Nandini Distributors (Karnataka) and Chennai Snacks Depot (Tamil Nadu).
9. **Items**: create the seven items in the table above. For each, choose the tax name exactly as shown (`GST 5%`, `GST 18%`, `GST 28%`). Leave **Barcode** blank so the system generates one. Set the minimum stock levels.
10. **Purchases**: create PUR-1001 and PUR-1002 with today’s date and status **Completed · update stock**.
11. **POS Billing**: select MAIN, add Milk 2, Curd 1, Chips 2, Soft drink 1, payment **Cash**, amount received ₹300, complete the sale. Do not print if no printer is connected.
12. **Sales Entry**: create S2 and S3 exactly as listed, with today’s date.
13. **Inventory → Adjust stock**: stock out 2 Milk and stock in 5 Tea, with the reasons above.
14. **Dashboard**: confirm today’s sales and purchases are non-zero, and **Low stock alerts** shows Tea.

> **Safety:** these transactions are in the new demo store only. If you use a shared demo store, understand that these sales will affect its reports until you reset the data. There is no “reset store” button.

### 3.5 What you will add live during recording

| Chapter | Live action | Effect |
|---|---|---|
| 5 | Add branch **BLR — Bengaluru City** | Branch count 1 → 2; the branch selector and branch filters now offer two locations |
| 6 | Edit `manager`, assign BLR; create a custom role | Demonstrates branch assignment and permissions |
| 7 | Import 6 items into BLR | New category Home Care, 6 items, opening stock in BLR |
| 8 | Open a barcode label for Milk, download PNG, print 2 labels | Demonstrates labels |
| 9 | Create purchase **PUR-1003** from Nandini: Milk 24 × ₹48, Curd 12 × ₹32, Completed | MAIN stock becomes Milk 120, Curd 61; total ₹1,612.80 |
| 10 | Adjust MAIN: stock out 1 Milk (`Expired`), stock in 6 Curd (`Count correction`) | MAIN Milk 119, Curd 67 |
| 11 | Transfer **TRF-1001** MAIN → BLR: Milk 10, Curd 5; approve, dispatch, receive | MAIN Milk 109, Curd 62; BLR Milk 10, Curd 5 |
| 12 | POS sale: Milk 2, Curd 1, Chips 2, Soft drink 1, Cash ₹300 | MAIN Milk 107, Curd 61, Chips 53, Soft drink 46; total ₹266.40 |
| 13 | Create a small test sale for 1 Tea, then cancel it | Tea 25 → 24 → 25; demonstrates stock reversal |
| 14 | Reports for today | Sales, purchases, GST, profit, stock movement, low stock |
| 15 | Show printer settings and print history (optional safe test) | No real job unless approved |
| 16 | Platform console: view store, subscription, audit; reset demo owner password | Demonstration only |

### 3.6 Excel import file (prepare before recording)

Create `demo-items.xlsx` from the template downloaded at **Items → [Excel template]**. Keep the column names unchanged. Fill these six rows:

| Item Code | Item Name | Category Name | Unit | Rate | Tax Name | Opening Stock Quantity | Minimum Stock Quantity | Description |
|---|---|---|---|---|---:|---|---:|---:|---|
| GHEE500 | Ghee 500ml | Dairy | BOTTLE | 320 | GST 12% | 12 | 5 | Pure cow ghee |
| PANEER200 | Paneer 200g | Dairy | PACKET | 90 | GST 5% | 15 | 6 | Fresh paneer |
| SUGAR1K | Sugar 1kg | Grocery | KG | 45 | GST 5% | 30 | 10 | Refined sugar |
| SALT1K | Iodised Salt 1kg | Grocery | KG | 25 | GST 5% | 40 | 10 | Iodised salt |
| BISCUIT100 | Biscuit 100g | Snacks | PACKET | 30 | GST 18% | 50 | 24 | Tea-time biscuit |
| DISH500 | Dish Wash 500ml | Home Care | BOTTLE | 110 | GST 18% | 10 | 6 | Lemon dish wash |

Expected preview after uploading into **BLR**: **6 total rows, 1 new category (Home Care), 6 new items, 6 stock updates, 0 duplicates, 0 invalid, 6 importable**. The import creates items at store level and posts opening stock only to BLR.

> On the current production host, the web server limits uploads to about 1 MB, so keep the demo file well under 1 MB (six rows is only a few KB). Large real-world files need the server upload limit raised first.

### 3.7 Barcode and printing preparation

- **Barcode labels** print from the browser (50 × 30 mm, 1–100 copies, PNG download). You do not need the print bridge for labels.
- **Receipt printing** needs one of: a Windows print bridge installed on the billing PC, or an Android print bridge paired with a LAN thermal printer (port 9100). If neither is available, use **Print receipt** in the browser and choose **Save as PDF** to demonstrate the layout, and say clearly that silent printing requires on-site setup.
- **Do not enqueue a real print job during the recording** unless you (the owner) approve a safe test in the demo store. A failed or duplicate job in a real store is a visible side effect.
- Physical printer, USB scanner, phone camera and Android-device behaviour are **[requires hardware verification]**.

### 3.8 Recording environment checklist

- Browser: Chrome or Edge, one clean window, 1920 × 1080 (or 1440 × 900), zoom 100%, bookmarks bar hidden.
- Screen recording: 1080p, 30 fps, system audio off, microphone tested.
- Notifications off; sign out of personal email and chat apps.
- One tab for the app, one tab with this script (on a second monitor, not recorded).
- Stable internet; the app needs a live connection.
- Close DevTools. Never open the browser console or network panel on camera.
- Have the demo data sheet and stock checkpoints printed or on the second screen.

### 3.9 Pre-recording checklist

- [ ] Demo store registered, owner can sign in
- [ ] Store profile, MAIN branch state and tax mode confirmed
- [ ] Four demo users created; manager and staff assigned to MAIN
- [ ] Four categories, two suppliers, seven items created
- [ ] Purchases PUR-1001 and PUR-1002 completed
- [ ] Sales S1 (POS), S2 (IGST/UPI), S3 (credit) created
- [ ] Adjustments (Milk out 2, Tea in 5) posted
- [ ] Dashboard shows today’s sales, purchases, stock value and Tea low stock
- [ ] `demo-items.xlsx` ready and validated once in a scratch/demo environment
- [ ] Barcode label for MILK1L previews correctly
- [ ] Platform Admin account available (not typed on camera)
- [ ] Printer decision made: bridge, PDF fallback, or skip
- [ ] Screen resolution, mic and notifications checked

---

## 4. Complete customer demonstration, module by module

Each module below follows the same nine questions: what it is, why a business needs it, what the customer can do, how to navigate, the steps to demonstrate, what happens after saving, how it connects to other modules, a practical example, and the words to say.

---

### A. Store registration and onboarding

**What it is.** A self-service way for a new business to create its own workspace: store details, the first branch, the owner login, default GST slabs and four built-in roles, all in one step.

**Why a business needs it.** A new customer can start without a sales call or manual database setup. The store code becomes the business’s sign-in identity, and the first branch is ready immediately.

**What the customer can do.**
- Register a store with a unique store code.
- Set the owner’s name, email, mobile, address, GSTIN and state.
- Name the first branch (defaults to **Main Branch**).
- Choose the owner username and password.
- Sign in with the store code immediately after registration.

**How to navigate.** Open `/register` (link **Register your business** on the login page), or go directly to `https://hiremathwebstudio.in/register`.

**Step-by-step demonstration.**
1. On the login page, click **[Register your business]**.
2. Point out the heading **Register your store** and the subtitle **Create a store workspace with an owner account and first branch.**
3. Fill **Store code** `SRIRAM01` and explain the hint: 3–30 letters, numbers, `-` or `_`.
4. Fill **Store name** `Sri Ram Traders`, **Owner name** `Ravi Kumar`, **First branch name** `Main Branch`.
5. Fill **Owner email**, **Mobile number**, **Store address**, optional **GSTIN**, and **State** `Karnataka`.
6. Scroll to **Owner login**: **Username** `owner`, **Password** `Demo@2026Shop`. Point out the password hint.
7. Click **[Create store]**.
8. Show the success message **Store registered successfully. You can sign in now.** and that the login page opens with the store code pre-filled.

**What happens after saving.** The system creates the store in **Active** status, a **Main Branch**, five GST slabs (0% exempt, 5%, 12%, 18%, 28%), the four built-in roles (Store Owner, Store Admin, Branch Manager, Branch Staff), and the owner account with the Store Owner role. A one-year subscription is recorded with a default limit of five branches. Registration does **not** sign you in automatically, and it does **not** show a temporary password — the owner chose the password.

**How it connects.** The store code is used on every login. The first branch is where the first purchase, sale and stock adjustment will be recorded. The GST slabs appear immediately in **Tax Master**, and the roles appear in **Settings → Roles & permissions**.

**Practical example.** A grocery owner in Bengaluru registers at 9 a.m., names the first branch **Main Branch**, signs in, adds two suppliers and starts billing the same morning.

**Say:**
> “This is how a new business starts. I enter a unique store code — this becomes our sign-in identity — then the business name, owner details and the first branch. I choose the owner login and create the store. The system prepares the GST slabs, the staff roles and the main branch automatically. There is no software to install to start billing; it runs in the browser — the optional print helper is only for silent receipt printing.”

---

### B. Login, dashboard and navigation

**What it is.** A secure sign-in using store code, username (or email) and password, followed by a dashboard that summarises today’s business and a sidebar that organises every module.

**Why a business needs it.** The owner needs one screen that answers: How much did we sell today? What did we buy? What is the stock worth? What is running low? Which branch is performing? Staff need fast access to billing and stock, and the menu should show only what their role allows.

**What the customer can do.**
- Sign in with **Store code**, **Username or email**, **Password**; show/hide the password.
- See today’s sales, today’s purchases, inventory value and low-stock alerts.
- See branch performance, top-selling items, branch stock overview, recent purchases and recent sales.
- Use quick actions: **Record purchase**, **Adjust stock**, **New transfer**, **Open reports**.
- Switch the branch context from the **Branch** selector (including **All branches** where permitted).
- Change the password or sign out from the sidebar footer.

**How to navigate.** `/login` → after sign-in, `/` (Dashboard). The sidebar groups are **Overview**, **Sell**, **Catalog**, **Stock**, **Insights** and **Administration**.

**Step-by-step demonstration.**
1. Open `/login`.
2. Enter **Store code** `SRIRAM01`, **Username** `owner`, **Password** `Demo@2026Shop`; click **[Sign in]**.
3. On the dashboard, read the greeting and the store/branch subtitle.
4. Point to the four stat cards: **Today’s sales**, **Today’s purchases**, **Inventory value**, **Low stock alerts**.
5. Scroll through **Branch performance today**, **Top selling items**, **Branch stock overview**, **Low stock alerts**, **Recent purchases** and **Recent sales**.
6. Hover over the sidebar groups and name each menu item.
7. Use the **Branch** selector to show **All branches** and a single branch.
8. Point to **New sale** in the top bar, and to the key icon (**Change password**) and **Sign out** icon in the sidebar footer.

**What happens after saving.** Nothing is saved on this screen; it is a live summary. Sign-in issues a secure session cookie, and the dashboard immediately reflects the branch selected. If the store subscription has expired, the system shows a subscription-expired screen and blocks business screens until the platform team renews it.

**How it connects.** Every number links to a module: sales to POS/Sales History, purchases to Purchases, inventory value to Inventory, low stock to Low Stock and Reports. The branch selector controls the context for POS, purchases, inventory and reports.

**Practical example.** At 9 p.m. the owner opens the dashboard, sees today’s sales and the low-stock alert for tea, and sends the purchase list to the branch manager.

**Say:**
> “After sign-in, the dashboard gives me the day at a glance: sales, purchases, stock value and low-stock alerts. I can see how each branch is performing, what is selling best, and the latest purchases and bills. The menu is organised the way a shop works: sell, catalogue, stock, insights and administration. And I can switch between branches or view all branches.”

---

### C. Store and branch management

**What it is.** The store profile holds the business identity, GSTIN and tax mode. Branches represent physical locations, each with its own address, contact number and state.

**Why a business needs it.** GST depends on the place of supply: the branch state versus the supplier or customer state decides CGST+SGST or IGST. Branch-level records also let the owner compare outlets and move stock between them.

**What the customer can do.**
- View and edit store name, owner name, email, mobile, GSTIN and address.
- See the store code (read-only) and the store status.
- Switch **Tax inclusive pricing** on or off.
- View subscription dates, maximum branches, existing branches and remaining branches.
- Add and edit branches, set branch code, name, address, contact and state, and activate a branch.
- Work within the branch limit.

**How to navigate.** **Store Settings** (`/settings`) → **Store profile** or **Branches**. The tab is visible only to users with the matching permission; the Store Owner sees all tabs.

**Step-by-step demonstration.**
1. Open **Store Settings → Store profile**. Point out the business details and the read-only **Store code**.
2. Point to **Tax inclusive pricing** and explain: off means GST is added on top of the rate; on means the rate already includes GST.
3. Scroll to **Subscription & branch limit** and read the dates, maximum branches, existing and remaining.
4. Open **Store Settings → Branches**. Show **Main Branch**, state **Karnataka**, and the counters.
5. Click **[Add branch]**; enter branch code `BLR`, name `Bengaluru City`, address `45 Brigade Road, Bengaluru`, contact `9876500002`, state `Karnataka`; save.
6. Explain that the demo plan allows five branches; when the limit is reached, the **Add branch** button is disabled and the alert **You have reached your maximum branch limit. Please contact the Platform Administrator.** appears.
7. Click **[Edit]** on BLR to show that the branch code cannot be changed after creation.

**What happens after saving.** Store changes update the profile immediately. A new branch appears in the branch selector and in branch reports, with zero stock. Branch creation is protected by the subscription branch limit on the server; exceeding it returns a conflict instead of silently creating an extra branch. Changing a branch state affects future GST calculations (intra-state vs inter-state), not old invoices.

**How it connects.** Branch state feeds GST on purchases and sales. Branches are used by inventory, transfers, POS, purchases, reports and user branch assignment. Subscription dates and branch limit come from the Platform Admin.

**Practical example.** A store with a main outlet and a new city outlet adds **BLR**, assigns a manager to both branches, and transfers slow-moving stock from MAIN to BLR.

**Say:**
> “The store profile holds the business identity and GST number. Branches are the physical locations. Each branch has its own state, and that state decides whether a bill uses CGST plus SGST or IGST. Here I can see the subscription dates and the branch limit. I add the Bengaluru branch, and it immediately becomes available for stock, billing and reports — within the plan’s branch limit.”

---

### D. User management and permissions

**What it is.** A role-based access system with four built-in roles and the ability to create custom roles from thirty permission codes. Users are assigned a role and, for branch roles, one or more branches.

**Why a business needs it.** Not every team member should see costs, reports or settings. A cashier needs billing; a branch manager needs stock and purchases; the owner needs everything. Branch assignment also prevents staff at one outlet from seeing another outlet’s data.

**What the customer can do.**
- Add a team member with username, full name, email, mobile, password, role and branches.
- Edit a user, change role or branch access, reset the password, deactivate or reactivate the account.
- Create custom roles and choose permissions by group.
- See how many permissions a role grants and how many users have it.

**Built-in roles (verified):**

| Role | Access summary |
|---|---|
| **Store Owner** | Everything in the store: all operations, settings, branches, users, roles, printers and reports. No platform console. |
| **Store Admin** | Day-to-day operations plus users and printers, but cannot manage the store profile, branches or roles. |
| **Branch Manager** | Purchases, inventory adjustments, transfers (create/dispatch/receive, not approve), POS and sales, reports, read-only masters. Only assigned branches. |
| **Branch Staff** | POS, sales and sales history, plus read-only catalogue and stock. Only assigned branches. |

**How to navigate.** **Store Settings → Users** (`/settings?tab=users`) and **Store Settings → Roles & permissions** (`/settings?tab=roles`).

**Step-by-step demonstration.**
1. Open **Users**. Show the team list, role and branch badge.
2. Click **[Add user]**; note that the **Username** field becomes disabled when you edit an existing user.
3. Enter a sample user, choose **Branch Manager**, tick **Main Branch** (and BLR if it exists), and save.
4. Open **Roles & permissions**; show the four system roles and their permission counts.
5. Click **[Add role]**, create `Cashier` with Dashboard, Sales view, Sales manage and Item view only. Save.
6. Show the new role card and that it is marked **Custom**.
7. Edit the `staff` user to assign the Cashier role; explain that permission changes apply when the user signs in again or refreshes the session.
8. Show **Deactivate** on a user and explain that the account can no longer sign in, but its audit history is kept.

**What happens after saving.** New users can sign in immediately (unless deactivated). Branch-bound roles must have at least one branch. A user with **All branches** badge has store-wide access within their permissions. Deactivation blocks login and invalidates the current session on the next request; it does not delete the user or their history.

**How it connects.** Role permissions control every screen, button and API. Branch assignment controls which stock, sales, purchases and reports a user can see. The audit log records user and role changes.

**Practical example.** The owner creates a **Cashier** role that can bill and view items but cannot see purchase costs or reports, then assigns it to two counter staff at MAIN.

**Say:**
> “Access is controlled by roles, not by sharing one password. The owner sees everything. The branch manager handles purchases, stock and transfers for assigned branches. The cashier only gets billing and item lookup. I can also create a custom role and tick exactly the permissions it needs. Branch assignment makes sure a staff member at one outlet cannot see another outlet’s stock or sales.”

---

### E. Master data management

**What it is.** The shared foundation every transaction uses: categories, items, units, tax slabs and suppliers. One catalogue serves all branches; stock is tracked separately per branch, with branch-level cost and price records.

**Why a business needs it.** If item names, units and tax rates are entered consistently once, billing becomes fast and reports become trustworthy. Correct master data also prevents GST mistakes and duplicate item codes.

**What the customer can do.**
- Create categories and deactivate or delete unused ones.
- Create items with item code, name, category, unit, rate, tax slab, minimum stock and status.
- Leave the barcode blank for automatic generation, or enter/scan an existing supplier barcode.
- Search items by name, code or barcode.
- Create tax slabs with one GST percentage; the system derives CGST/SGST/IGST.
- Create suppliers with contact, GSTIN, state and address.
- View an item’s stock by branch and its recent movements.

**How to navigate.**
- **Categories** `/categories`
- **Items** `/items`
- **Tax Master** `/taxes`
- **Suppliers** `/suppliers`

**Step-by-step demonstration.**
1. Open **Categories**; show Dairy, Grocery, Snacks and Beverages; add `Home Care` manually only if you are not demonstrating the import (the import will create it otherwise).
2. Open **Tax Master**; show the five slabs. Click **[Add tax]** and point out the single **GST percentage** field; the **CGST**, **SGST** and **IGST** boxes are derived and read-only. Explain the hint: half each for intra-state, full rate for inter-state. Cancel without saving.
3. Open **Items**; show the columns and explain that a **GS1** badge marks a retail GTIN entered from the manufacturer, while system-generated in-store barcodes do not carry it.
4. Click **[Add item]**; walk through **Item code**, **Item name**, **Barcode** (`Auto-generated when blank`), **Barcode format**, **Description**, **Category**, **Tax slab**, **Base unit**, **Rate ₹**, **Minimum stock level** and **Status**. Save a sample item only if you want an extra row.
5. Use the search box to find `MILK` and show that name, code and barcode all work.
6. Open an item’s **[View stock]** modal and show branch-wise on-hand, minimum, purchase price, selling price and recent movements.
7. Open **Suppliers**; show the supplier list and the **[Purchase history]** action. Add a supplier only if needed for the purchase demo.

**What happens after saving.** New master data is available immediately in purchases, POS, sales, imports and reports. Items are shared at store level; stock is created only when a purchase, opening-stock import or adjustment posts it. An item cannot be deleted — it can be deactivated. A category or tax slab used by items or transactions cannot be deleted; the system asks you to reassign first. Tax changes do not alter historical invoices because each line stores a snapshot.

**How it connects.** Items connect to tax slabs, categories, inventory, barcodes, imports, purchases, POS, sales and reports. Suppliers connect to purchases and GST (supplier state). Categories and items are filters in inventory and reports.

**Practical example.** A dairy owner creates the **Dairy** category, a **GST 5%** slab, and the item **Nandini Toned Milk 1L** at ₹60 with a minimum of 10. Every purchase, sale and report now uses the same item and tax.

**Say:**
> “Everything starts with clean master data. Categories group the catalogue. Tax Master holds the GST slabs — I enter one GST rate, and the system works out CGST and SGST for intra-state bills and IGST for inter-state bills. Items carry the item code, unit, selling rate, tax and minimum stock. Suppliers carry their state, which decides IGST on purchases. This is the single source of truth for billing, stock and reports.”

---

### F. Barcode management

**What it is.** Automatic barcode generation and label printing for every item, plus barcode search and scanner input in POS, purchases and manual sales.

**Why a business needs it.** Barcodes remove typing errors at the counter, speed up billing and make stock identification unambiguous. A shop can label its own loose items even when the supplier does not provide a barcode.

**What the customer can do.**
- Let the system generate a **Code 128** barcode when the item is created with a blank barcode.
- Choose **EAN-13** where a 13-digit retail barcode is preferred.
- See the barcode in the item list and item details.
- Search by barcode.
- Preview a **50 × 30 mm** label with item name, barcode, code and price.
- Print 1–100 labels, or download the barcode as a PNG.
- Regenerate a barcode (with a warning that old printed labels will stop scanning).
- Scan with a USB/keyboard-wedge scanner or the device camera.
- Scan into POS, Sales Entry and Purchase entry.

**Barcode facts (verified).**
- **Code 128** is generated as `IMS` plus 10 random digits.
- **EAN-13** uses the internal prefix `20`, plus a valid check digit.
- Barcodes are unique **within a store**, not across stores.
- Labels are rendered in the browser at 50 × 30 mm; up to 100 copies per print.
- Camera scanning needs camera permission and a supported browser; it uses the browser’s barcode detector with a ZXing fallback.

**How to navigate.** **Items** `/items` → the barcode icon on a row, or **Items → [Add item] → Barcode**. Scanning works from the scan bar on **POS Billing**, **Sales Entry** and **New purchase**, and from the **Camera** button.

**Step-by-step demonstration.**
1. Open **Items** and point to the barcode shown under the item name.
2. On the Milk row, click the **Barcode label** action.
3. Show the label preview: item name, `MILK1L · CODE128`, barcode, price.
4. Set **Labels to print** to `2`; point out the 50 × 30 mm hint.
5. Click **[Download PNG]** and show the downloaded file.
6. Click **[Print 2 labels]** and choose **Save as PDF** if no label printer is available (or print on paper if one is connected).
7. Open the **Barcode format** select and explain Code 128 vs EAN-13. Do **not** regenerate an existing barcode on camera unless you are in the demo store and accept that old labels stop scanning.
8. On **POS Billing**, click the scan field and type or scan the Milk barcode; show the item added to the bill.

**What happens after saving.** A generated barcode is stored on the item immediately and is unique inside the store. Labels are a browser output; no server print job is created. Scanning an unknown barcode in POS shows that no item was found; scanning in a purchase or sales line adds the matching item line.

**How it connects.** Barcodes belong to items and appear in POS, sales, purchases, imports (generated for imported items with blank codes) and label printing. The barcode is also returned by item search and the item lookup API.

**Practical example.** A provision store buys loose rice and packs it in 5 kg bags. It creates the item, prints two labels, sticks one on the shelf and one on the bag, and the cashier scans it at billing.

**Say:**
> “Every item gets its own barcode inside the store. If I leave the barcode blank, the system generates one — Code 128 by default, or EAN-13 if I prefer a retail format. I can preview the 50 by 30 millimetre label, print one or many copies, or download the barcode as an image. At billing, the cashier can scan with a USB scanner or use the camera. The barcode is unique to my store, so two stores can use the same supplier barcode without clashing.” **[requires hardware verification for scanner and camera]**

---

### G. Excel item import

**What it is.** A bulk way to create items from an Excel file, with a validation preview before anything is written, automatic category creation and branch-specific opening stock.

**Why a business needs it.** A new store may have hundreds or thousands of items. Typing each one is slow and error-prone. Import lets the owner prepare the catalogue in Excel once and load it safely.

**What the customer can do.**
- Download a template with the correct columns and an Instructions sheet.
- Choose the branch that should receive opening stock.
- Upload `.xlsx` or `.xls` (up to 10 MB, maximum 2,000 rows).
- Preview every row: new items, existing items, stock updates, duplicates, invalid rows and already-imported rows.
- Fix errors and upload again; nothing is written until confirmation.
- Confirm the import atomically.
- See import history with status and counts.

**Template columns (exact):** Item Code, Item Name, Category Name, Unit, Rate, Tax Name, Opening Stock Quantity, Minimum Stock Quantity, Description.

**Rules (verified).**
- Required: item name, category name, unit, rate and tax name.
- Unit must be one of LITER, ML, KG, GRAM, PIECE, PACKET, BOX, BOTTLE, DOZEN.
- Tax name must match an existing slab exactly (for a new store: `GST 0% (Exempt)`, `GST 5%`, `GST 12%`, `GST 18%`, `GST 28%`).
- Item code is optional; blank codes are generated. A supplied code must be 2–50 characters using letters, numbers, dot, underscore or hyphen.
- Duplicate rows or invalid rows block the whole import.
- Categories are created automatically for new items.
- Opening stock is posted **once per item and branch**, only to the selected branch.
- Re-uploading the exact same file for the same store and branch is blocked as already imported.
- If anything fails during confirmation, the whole import rolls back and is marked Failed.

**How to navigate.** **Items** `/items` → **[Upload Excel]** or **Catalog → Import History** `/items/imports`.

**Step-by-step demonstration.**
1. On **Items**, click **[Excel template]**; open the downloaded file and show the **Items** and **Instructions** sheets.
2. Show your prepared `demo-items.xlsx` with six rows.
3. Click **[Upload Excel]**.
4. Select **Import into branch** `BLR — Bengaluru City`.
5. Choose the file and click **[Validate & preview]**.
6. Read the summary: **6 total rows, 1 new category, 6 new items, 6 stock updates, 0 duplicate rows, 0 invalid rows, 6 importable rows**.
7. Scroll the row preview and point out the **Import Action** and **Validation** columns.
8. Click **[Confirm import]**; show the success toast and that rows become **Completed** in **Import history**.
9. Open **Inventory**, select BLR, and show the six items with opening stock.
10. Open **Import history** and show the batch counts and status.

**What happens after saving.** All valid rows are created in one transaction: categories, items, generated barcodes and opening-stock movements for the selected branch. The import batch stores the original file hash and row plan. Failed or blocked imports write nothing.

**How it connects.** Imported items appear immediately in Items, Inventory, POS, purchases and reports. The opening stock becomes the branch’s first stock movement. Import history links back to the preview. Import needs both item-management and inventory-adjust permissions.

**Practical example.** A new supermarket receives a supplier price list in Excel, maps it to the template, imports 800 items into the main branch, and starts billing the same day.

**Say:**
> “For a new store with hundreds of items, I do not want to type each one. I download the template, fill my item list, choose the branch, and upload. Before anything is saved, the system shows me every row: how many new items, how many stock updates, and any invalid or duplicate rows. If one row is wrong, nothing is imported — I fix the Excel and try again. When everything is clean, I confirm, and the categories, items and opening stock are created together.”

---

### H. Supplier and purchase management

**What it is.** A complete purchase workflow: supplier master, purchase entry with item lines, discounts and GST, draft or completed status, stock receipt, purchase document and cancellation with stock reversal.

**Why a business needs it.** Purchases are where stock and cost enter the business. Recording them properly gives accurate stock, weighted-average cost, GST input details and a supplier history.

**What the customer can do.**
- Create and deactivate suppliers, and view a supplier’s purchase history.
- Create a purchase for a branch with supplier, date, supplier invoice number, notes and lines.
- Add lines by item or barcode, with quantity, rate, line discount and GST.
- Apply a bill-level discount; see the store-level GST **Inclusive** or **Exclusive** mode applied to the lines.
- See subtotal, discount, taxable value, CGST/SGST or IGST, total tax and grand total.
- Save as **Completed · update stock** or **Draft · no stock update**.
- Complete a draft later.
- View and print the purchase document.
- Cancel a purchase; completed purchases reverse their stock movement.

**Purchase statuses (verified).** **DRAFT** → no stock effect; **COMPLETED** → stock received, weighted-average cost updated; **CANCELLED** → no further use, and for a previously completed purchase the stock movement is reversed. Completed purchases cannot be edited.

**How to navigate.** **Purchases** `/purchases` → **[New purchase]**. Supplier history: **Suppliers → [Purchase history]**.

**Step-by-step demonstration.**
1. Open **Suppliers** and point out Nandini Distributors (Karnataka) and Chennai Snacks Depot (Tamil Nadu).
2. Open **Purchases**; show PUR-1001 (intra-state, CGST/SGST) and PUR-1002 (inter-state, IGST).
3. Click **[New purchase]**.
4. Select **Branch** `MAIN`, **Supplier** `Nandini Distributors`, keep today’s date, enter **Supplier invoice no.** `ND-1043`.
5. Add a line: Milk, quantity `24`, rate `48`. Add a second line: Curd, quantity `12`, rate `32`.
6. Point to the read-only GST percentage and amount on each line.
7. Show the summary: **Subtotal ₹1,536.00**, **CGST ₹38.40**, **SGST ₹38.40**, **Grand total ₹1,612.80** (Exclusive mode).
8. In **Save as**, choose **Completed · update stock**; click **[Save & receive stock]**.
9. Open the saved purchase and click **[Print]** to show the purchase document.
10. Return to **Inventory** and show Milk 120 and Curd 61 at MAIN.

**What happens after saving.** A completed purchase immediately increases branch stock, writes `PURCHASE_IN` movements, updates the branch purchase price and weighted-average cost, and creates an audit entry. The purchase number is generated by the system (`PUR-…`); the supplier invoice number is whatever you typed. A draft changes nothing in stock. Cancelling a completed purchase reverses the stock movements and fails if that stock has already been sold or transferred.

**How it connects.** Purchases feed inventory, branch cost, profit reports, purchase reports and GST input details. Suppliers feed purchase selection and GST (their state vs the branch state). Stock received here is what POS and sales can sell.

**Practical example.** A dairy receives 100 litres of milk from a Karnataka supplier. The purchase records the cost and 5% GST, stock rises, and the cost per litre becomes available for profit reporting.

**Say:**
> “Purchases are how stock and cost enter the system. I choose the branch and supplier, enter the supplier’s invoice number, and add item lines with quantity and purchase rate. The GST is calculated on each line — CGST and SGST because the supplier is in Karnataka. When I complete the purchase, stock is received immediately and the branch cost is updated. If I only save a draft, nothing changes in stock. A completed purchase can be cancelled, and the stock movement is reversed.”

---

### I. Inventory management

**What it is.** Live branch-wise stock with a full movement ledger, manual adjustments, branch pricing fields, low-stock detection and stock valuation.

**Why a business needs it.** The owner must know what is on hand at each location, why it changed, and what it is worth. Adjustments cover damage, expiry and physical-count corrections without fake sales.

**What the customer can do.**
- View stock levels by branch, with on-hand, minimum, cost, selling price, stock value and status.
- Filter by branch, category, search or **Low stock only**.
- Adjust stock **in** or **out** with a mandatory reason.
- View the full movement history: opening, purchase, sale, transfer in/out, adjustment in/out, reversals.
- Edit branch pricing: minimum stock, purchase price and selling price.
- See the low-stock list and value at risk.

**How inventory is valued (verified).** Stock value uses the weighted-average cost held for the item at that branch. A purchase updates the average; a sale snapshots the cost on the sale line for profit reporting. Stock cannot go negative: a sale or dispatch that exceeds available quantity is rejected.

**How to navigate.** **Inventory** `/inventory`; **Low Stock** `/low-stock`; item-level stock from **Items → [View stock]**.

**Step-by-step demonstration.**
1. Open **Inventory**; show **Stock levels** for **All branches**.
2. Select **MAIN** and point out Milk 120, Curd 61, Tea 25 and the **Low** badge on Tea.
3. Click **[Adjust stock]**; choose branch MAIN, item Milk, type **Stock out**, quantity `1`, reason `Expired — discarded`; click **[Reduce stock]**.
4. Show Milk 119.
5. Open the **Movements** tab; point out the latest `ADJUSTMENT_OUT` row, the reference and the running balance.
6. Use the search box to find `CURD`; click **[Adjust stock]**, type **Stock in**, quantity `6`, reason `Physical count correction`; click **[Add stock]**. Show Curd 67; Tea remains at 25 and deliberately stays in **Low Stock** for the next step.
7. Click **Edit branch pricing** on Milk; show **Minimum stock level**, **Purchase price ₹**, **Selling price ₹** and the weighted-average note. Close without saving.
8. Open **Low Stock**; show the filtered list, **Value at risk**, and the **Excel**, **PDF** and **Print** buttons.

**What happens after saving.** An adjustment writes an immediate stock movement with a unique `ADJ-…` reference and an audit entry. Low stock is recalculated on the next load. Pricing edits update the branch record and low-stock threshold, but stock value still uses the weighted-average cost; **POS always uses the item master rate**, not the branch selling price, and manual sales can use a negotiated rate.

**How it connects.** Inventory is the result of purchases, sales, transfers, adjustments and opening stock. It drives POS availability, low-stock alerts, stock movement reports and stock value on the dashboard.

**Practical example.** One milk packet expires in the fridge. The manager records a stock-out of 1 with the reason “Expired”, so the system stock matches the shelf without creating a fake sale.

**Say:**
> “Inventory shows me the truth at each branch: what is on hand, what it cost, what it is worth and what is running low. Every change has a reason and a reference — purchase, sale, transfer, adjustment or reversal. If a packet expires, I adjust stock out with a reason; I do not create a fake sale. Stock can never go negative, because the system checks availability before it sells or dispatches.” **[note: POS uses the item master rate, not the branch selling price]**

---

### J. Stock transfer between branches

**What it is.** A controlled movement of stock from one branch to another, with approval, dispatch and receipt steps, and a permanent movement history at both ends.

**Why a business needs it.** Branches should not borrow stock informally. A transfer records who asked, who approved, what left the source and what arrived at the destination, keeping both branch stocks correct.

**What the customer can do.**
- Create a transfer from a source branch to a different destination branch.
- Add item lines with quantity and see available stock.
- Submit for approval (status **Pending**).
- Approve or reject a pending transfer.
- Dispatch an approved transfer; source stock is deducted.
- Receive a dispatched transfer; destination stock is added.
- Cancel a transfer before dispatch.

**Status flow (verified).** **Pending → Approved → Dispatched → Received**, with **Rejected** and **Cancelled** as exit paths. Approval requires the transfer-approve permission; dispatch and receipt require transfer management permission. Stock is checked and deducted only at dispatch. There is no stock reservation while a transfer is pending.

**How to navigate.** **Stock Transfers** `/transfers` → **[New transfer]**.

**Step-by-step demonstration.**
1. Open **Stock Transfers**; show the transfer list and the status filters.
2. Click **[New transfer]**; choose **From branch** `MAIN`, **To branch** `BLR`; point to the workflow hint at the bottom of the dialog.
3. Add a line: Milk, quantity `10`; add Curd, quantity `5`.
4. Save; show the toast **Transfer created and waiting for approval** and the status **PENDING**.
5. As the owner, click **[Approve]**; read the confirmation: stock is not moved until dispatch.
6. Click **[Dispatch]**; read the warning that stock will be deducted from MAIN immediately.
7. Sign in as the manager (assigned to BLR) or stay as owner, open the transfer and click **[Receive]**.
8. Open **Inventory → BLR** and show Milk 10 and Curd 5; open MAIN and show Milk 109 and Curd 62.
9. Open the **Movements** tab at each branch and show `TRANSFER_OUT` and `TRANSFER_IN`.

**What happens after saving.** Each transition writes an audit entry. Dispatch deducts source stock at the source weighted-average cost; receipt adds the same cost at the destination. A dispatch cannot be undone; it must be received. Rejection is allowed before dispatch. A same-branch transfer is blocked.

**How it connects.** Transfers connect inventory, branch stock reports, movement reports, audit and user branch permissions. They do not create a sale or purchase and do not affect GST.

**Practical example.** The main store has excess milk approaching its shelf life; it transfers 10 litres to the city branch. The branch receives 10, and both stock records match the physical movement.

**Say:**
> “Transfers are controlled, not informal. I choose the source and destination, add the items and quantities, and submit. The transfer waits for approval. After approval, dispatch deducts the stock from the source, and receiving adds it to the destination at the same cost. Both branches keep a permanent movement record, so there is no doubt about what left and what arrived. Dispatch cannot be undone — the stock must be received.”

---

### K. POS and quick billing

**What it is.** A fast counter screen: pick a branch, find items by search or barcode, build a cart, apply a bill discount, take payment and complete the sale. POS always uses the item master rate.

**Why a business needs it.** At the counter, speed and accuracy matter. POS reduces typing, prevents overselling, calculates GST automatically and prints or saves the receipt.

**What the customer can do.**
- Select the billing branch.
- Search by item name, code or barcode, or scan.
- Filter by category; tap item cards to add to the bill.
- Change quantity with − and +, remove a line, clear the bill.
- See available stock and a **Low** or **Out** badge.
- Apply a bill-level **Discount ₹**.
- Enter customer name, mobile and customer state (place of supply).
- Choose **Cash**, **UPI**, **Card** or **Credit**.
- Enter **Amount received** and see **Balance** or **Change**.
- Complete the sale and print the receipt.

**Important POS rules (verified).**
- The selling rate is fixed to the current item master rate; it is **not editable** in POS.
- Per-line discounts are not available in POS; only the bill discount.
- Quantity is capped by available stock; the system refuses an oversell.
- A **Credit** sale records the unpaid balance but does not collect it later.
- Stock is deducted immediately when the sale is saved.
- If a print bridge is configured, the receipt is sent automatically; otherwise use **Print receipt**.

**How to navigate.** **POS Billing** `/pos`.

**Step-by-step demonstration.**
1. Open **POS Billing**; select branch `MAIN`.
2. Search `MILK`; click the Milk card twice.
3. Add Curd once, Chips twice and Soft drink once.
4. Point out the available stock and the line totals.
5. Click **+** on Milk to show quantity control, then **−** to return to 2.
6. Enter **Discount ₹** `0` (or a small value such as ₹10 and show the total change, then set it back to 0 for clean GST numbers).
7. Enter **Customer name** `Walk-in`, **Mobile** blank, **Customer state** `Karnataka`.
8. Choose **Cash**, enter **Amount received** `300`.
9. Point to **Subtotal ₹240.00**, **CGST ₹13.20**, **SGST ₹13.20**, **Total ₹266.40**, and **Change ₹33.60**.
10. Click **[Complete sale · ₹266.40]**.
11. Show the success panel: **Sale completed**, amount collected, lines, method and branch; then **Print receipt** (or **New sale**).
12. Open **Inventory** and show Milk 107, Curd 61, Chips 53 and Soft drink 46 at MAIN.

**What happens after saving.** The system re-prices the sale on the server, validates stock, calculates GST from the snapshotted tax and branch/customer state, writes `SALE_OUT` movements, generates the next `SAL-…` number, and optionally queues a receipt. If the stock changed since the page loaded, the server rejects the sale rather than overselling.

**How it connects.** POS consumes items, taxes and branch stock; it creates sales, invoices, stock movements, profit data, GST data and print jobs. It is the main source of the sales report and dashboard figures.

**Practical example.** A customer buys two milk packets, one curd, two chips and a drink. The cashier scans or taps the items, takes ₹300 cash, gives ₹33.60 change, and the stock updates before the customer leaves.

**Say:**
> “This is the counter screen. I choose the branch, scan or tap items, and the cart builds itself. The rate comes from the item master, so the cashier cannot accidentally use an old price. The system checks available stock — it will not allow an oversell. GST is calculated line by line, I choose cash, UPI, card or credit, enter the amount received, and complete the sale. Stock is deducted immediately, and the receipt can print automatically if a print bridge is set up.” **[requires hardware verification for receipt printing]**

---

### L. Sales management

**What it is.** Manual sales entry for negotiated or wholesale billing, plus a complete sales history with invoice details, receipt printing and cancellation.

**Why a business needs it.** Some customers negotiate a rate or buy in bulk. Manual sales allow a negotiated selling rate and line discount while still producing a proper GST invoice and updating stock.

**What the customer can do.**
- Create a sale with branch, date, payment method, customer name, mobile and customer state.
- Add lines with item, quantity, **negotiated selling rate** and line discount.
- Apply a bill-level discount and enter the paid amount.
- See GST and totals before completing.
- Search and filter sales history.
- View an invoice, print the receipt, and cancel a completed sale.
- See stock reversed automatically after cancellation.

**Difference from POS (verified).**

| | POS Billing | Sales Entry |
|---|---|---|
| Price | Fixed item master rate | Negotiated selling rate per line |
| Line discount | No | Yes |
| Customer state | Yes | Yes |
| Best for | Retail counter | Wholesale, negotiated or credit sales |
| Stock deduction | Immediate | Immediate |
| Cancellation | Yes, from Sales History | Yes, from Sales History |

**Statuses (verified).** Sales have **COMPLETED** or **CANCELLED** only. There is no draft sale and no editing after completion. Cancelling restores stock and keeps the invoice visible as cancelled.

**How to navigate.** **Sales Entry** `/sales/new`; **Sales History** `/sales`.

**Step-by-step demonstration.**
1. Open **Sales Entry**; select branch `MAIN`.
2. Choose **Payment method** `UPI`; enter **Customer name** `Ramesh`, **Mobile** `98450 00001`, **Customer state** `Tamil Nadu`.
3. Add Rice, quantity `1`, selling rate `450`, discount `20`.
4. Add Oil, quantity `2`, selling rate `150`.
5. Point to **IGST ₹36.50** and **Grand total ₹766.50** (inter-state because the customer state is Tamil Nadu).
6. Click **[Complete sale]** and show the success panel with **Print receipt** and **New sale**.
7. Open **Sales History**; find the new invoice and click **[View]**.
8. Show the invoice cards: **Status**, **Customer**, **Payment**, **Balance**, and the line/tax table.
9. Click **[Print receipt]** to show the receipt layout (PDF is acceptable if no printer).
10. To demonstrate cancellation, create a small sale for 1 Tea, open it, click **[Cancel sale]**, confirm, and show that the invoice becomes **CANCELLED** and Tea stock returns to 25.

**What happens after saving.** The sale is completed immediately: stock is deducted, a `SAL-…` number is generated, the server calculates GST and stores tax snapshots, and the receipt data is available. Cancellation reverses the stock movement and marks the invoice cancelled; it does not delete the record.

**How it connects.** Sales feed inventory, sales history, receipts, print jobs, dashboard, sales report, GST report, profit report, stock movement and branch reports. Credit sales show a balance but have no later collection screen.

**Practical example.** A restaurant owner buys one 5 kg rice bag and two litres of oil at a negotiated rate for UPI payment. The sale is invoiced with IGST because the customer is in another state, and stock drops immediately.

**Say:**
> “POS is for the counter; Sales Entry is for negotiated or wholesale billing. Here I can enter a different selling rate and a line discount for each line, and still get a proper GST invoice. I choose the customer’s state, so the bill uses CGST and SGST or IGST correctly. After the sale, I can print the receipt. If a bill is wrong, I cancel it — stock is restored and the invoice stays in history as cancelled, so the record is never lost.”

---

### M. GST and taxation

**What it is.** Per-line GST calculation for purchases and sales, with intra-state CGST+SGST or inter-state IGST, inclusive or exclusive pricing, tax snapshots and a GST summary report.

**Why a business needs it.** GST errors are expensive and time-consuming. The system calculates tax consistently, shows it on the invoice, and keeps historical rates intact when slabs change.

**What the customer can do.**
- Create GST slabs from 0% to 100%, or exempt slabs.
- Let the system derive CGST/SGST (half each) and IGST (full rate).
- Choose **Tax inclusive pricing** at store level, or keep GST exclusive.
- Set the supplier state (purchases) and customer state (sales) to drive IGST.
- See CGST/SGST or IGST on every purchase, sale and receipt.
- Run the **GST** report by period and branch, filtered by tax rate.
- Rely on tax snapshots so old invoices never change.

**How the calculation works (verified).**
- **Exclusive:** taxable value = line amount after discount; tax = taxable × rate ÷ 100.
- **Inclusive:** taxable value = line amount ÷ (1 + rate ÷ 100); tax = line amount − taxable.
- **Intra-state:** CGST = half the tax, SGST = the other half.
- **Inter-state:** IGST = full tax.
- Inter-state is decided by the branch state versus the supplier state (purchase) or customer state (sale). If the state is missing, the system treats it as intra-state.
- Each line rounds to two decimals; totals are the sum of the line values.
- Tax name, percentages and amounts are stored on the line as a snapshot.

**What is not available.** GST return filing, GSTR-1 or GSTR-3B submission, e-invoicing, e-way bills, HSN/SAC codes and a purchase-side GST report are **not implemented**. The GST tab is an internal summary of output tax on sales, not a filing return.

**How to navigate.** **Tax Master** `/taxes`; **Reports → GST** `/reports`; tax breakdown also appears on purchase/sale screens and receipts.

**Step-by-step demonstration.**
1. Open **Tax Master**; show the five slabs and the derived CGST/SGST/IGST columns.
2. Open **Store Settings → Store profile**; point to **Tax inclusive pricing** and explain the difference.
3. Open the saved Purchase PUR-1001 (Karnataka supplier) and point to **CGST** and **SGST**.
4. Open Purchase PUR-1002 (Tamil Nadu supplier) and point to **IGST**.
5. Open Sales History → the Ramesh invoice (Tamil Nadu customer) and point to **IGST**; then the Anita invoice (Karnataka) and point to **CGST + SGST**.
6. Open **Reports → GST**; choose today’s date range and **All branches**; show taxable value, CGST, SGST, total tax and the tax-rate filter.
7. Explain that changing a tax slab tomorrow will not change these invoices.

**What happens after saving.** GST is calculated by the server and saved with the document. The GST report aggregates completed sales by branch, tax name and rate. Tax masters referenced by items or documents cannot be deleted, which protects historical data.

**How it connects.** Tax slabs connect to items, purchases, sales, receipts, the GST report and profit reporting (revenue excludes tax). Supplier and customer states connect master data to the tax split.

**Practical example.** A Bengaluru store buys from a Tamil Nadu supplier and pays IGST; it sells to a local customer with CGST+SGST and to an out-of-state customer with IGST. All three documents keep their original tax breakdown.

**Say:**
> “GST is calculated on every line and saved with the document. For a local supplier or customer, the tax splits into CGST and SGST. For an out-of-state supplier or customer, it becomes IGST. I can set prices as GST-exclusive or GST-inclusive at the store level. The GST report gives me a rate-wise summary for a period and branch. To be clear: this is GST billing and a summary report — it is not GSTR filing or e-invoicing.”

---

### N. Reports and business analysis

**What it is.** Eight report families for sales, purchases, stock movement, profit, GST, branch performance, store summary and low stock, with date, branch, category and item filters and Excel/PDF/print export.

**Why a business needs it.** The owner needs to see what sold, what was bought, what stock moved, what margin was earned and which branch is performing — without exporting raw data and building spreadsheets by hand.

**What the customer can do.**
- Choose a report tab and a date range (default: month start to today).
- Filter by branch, category, item or search.
- Read summary cards and detailed tables.
- Export the current report to **Excel** or **PDF**, or **Print** it.
- Compare branches and review the store summary.
- Review low-stock items and value at risk.

**The eight reports (verified).**

| Tab | What it shows |
|---|---|
| **Sales** | Sales value, entries, quantity sold; invoice-level table |
| **Purchases** | Purchase value, entries, quantity purchased; purchase-level table |
| **Stock movement** | Opening, purchased, sold, transfer in/out, adjustment in/out, closing quantity, inventory value |
| **Profit** | Revenue, COGS, gross profit, margin — by item and by branch |
| **GST** | Taxable value, CGST, SGST, total tax by branch/tax rate; sales only |
| **Branch performance** | Sales and purchase comparison across branches |
| **Store summary** | Store-level totals for the period |
| **Low stock** | Items at or below minimum, with shortfall and value |

**Important accuracy notes.**
- **Profit** is gross trading margin (revenue excluding tax minus cost of goods sold), not accounting net profit.
- **GST** is output tax on sales only; there is no purchase GST report.
- Reports use snapshotted tax and cost data, so later master-data changes do not rewrite history.
- Excel and PDF are generated in the browser; there is no scheduled or emailed report, and no server-side report export except the platform audit CSV.

**How to navigate.** **Reports** `/reports`; **Low Stock** `/low-stock` has its own Excel/PDF/Print buttons.

**Step-by-step demonstration.**
1. Open **Reports**; show the eight tabs.
2. On **Sales**, set the date range to today and **All branches**; show the summary cards and invoice table; click **[Excel]** and open the downloaded file.
3. Switch to **Purchases**; show the two completed baseline purchases plus the live purchase.
4. Open **Stock movement**; select Milk and point to opening, purchased, sold, transfer out, adjustment out and closing.
5. Open **Profit**; explain revenue, COGS and gross margin, and that this is not net accounting profit.
6. Open **GST**; show the rate-wise table and the tax-rate filter.
7. Open **Branch performance**; compare MAIN and BLR.
8. Open **Store summary**; show the period totals.
9. Open **Low stock**; show Tea and the export buttons.
10. Click **[PDF]** on one report and show the generated PDF; then **[Print]** to show the print layout.

**What happens after saving.** Reports are read-only and refresh when filters change. Export files contain the rows currently shown; if a report has no rows, the export buttons are disabled. The low-stock report uses the same `on hand <= minimum` rule as the dashboard.

**How it connects.** Reports read the same transactions created by purchases, sales, transfers, adjustments and imports. They are the end of the data flow: master data → stock → transactions → reports.

**Practical example.** At month end, the owner exports the sales and purchase reports to Excel for the accountant, checks the GST summary, reviews the profit by item, and orders more of the low-stock tea.

**Say:**
> “Reports turn daily work into decisions. I can see sales and purchases for any period, how stock moved, gross profit by item and branch, GST by rate, branch performance and the store summary. Every report can be exported to Excel or PDF, or printed. Two honest points: profit here means gross trading margin, not accounting profit; and the GST report is a summary of sales tax, not a filing return.”

---

### O. Printing and printer configuration

**What it is.** Optional silent thermal printing through a local bridge on a Windows PC or an Android device, plus printer configuration, device pairing, print history, reprint and browser-based barcode labels.

**Why a business needs it.** A busy counter cannot wait for a browser print dialog on every bill. Silent printing sends the receipt directly to the thermal printer. Print history shows what was sent, to which printer, and whether it succeeded.

**What the customer can do.**
- Configure a print bridge on the billing device (Windows or Android).
- Add store printers with name, IP/host, port, paper width (58 mm or 80 mm) and purpose.
- Generate a one-time pairing code for an Android bridge.
- Test a printer connection (Android bridge) or the default printer (Windows bridge).
- See print history with document, branch, printer, bridge, status, attempts and error.
- Reprint a receipt.
- Print barcode labels from the browser.

**What is supported (verified).**
- Document types in print history: **Sale receipts** only for bridge jobs; purchase and report printing use the browser.
- Statuses: **PENDING → PRINTED** or **FAILED**; there is no automatic retry.
- Reprint creates a new manual job.
- Windows bridge runs locally on port 17777; Android bridge on 17778.
- The backend never talks to the printer directly; the local bridge pulls the job configuration and prints.
- Barcode labels are browser-rendered and are not a bridge job.

**How to navigate.** **Store Settings → Printers** (`/settings?tab=printers`); **Print History** `/print-history`.

**Step-by-step demonstration.**
1. Open **Store Settings → Printers**; show **Print bridge on this device**.
2. Explain the two options: install the Windows bridge on the billing PC, or pair an Android device with a LAN printer.
3. Show **Store printers**; add or view a printer with IP `192.168.1.50`, port `9100`, paper **80 mm**, purpose **Sale receipts**.
4. Show **[Generate code]** for Android pairing and explain that the code is valid for 10 minutes and can be used once.
5. Open **Print History**; show the filters and columns.
6. Open a printed sale and click **[Reprint]**; explain that this creates a new manual job.
7. If a bridge and printer are available **and the owner approves a safe test**, print one receipt from the demo sale. Otherwise, use **Print receipt → Save as PDF** and say that silent printing requires on-site hardware setup.
8. Show **Items → Barcode label → [Print labels]** to demonstrate browser label printing.

**What happens after saving.** Printer settings are store-scoped. A paired device receives a token once; revoking it blocks future jobs. A completed sale with a configured bridge triggers an automatic receipt job; duplicates are prevented by an idempotency key. A failed job records the error for review.

**How it connects.** Printing connects POS/sales to the local bridge, and print history connects to sales. Barcode labels connect to items. Print failures do not block a sale; the sale is already completed.

**Practical example.** A bakery's billing PC runs the Windows bridge and a 58 mm thermal printer. The cashier completes a sale and the receipt prints without any dialog; if the printer is offline, the sale still succeeds and the failure appears in Print History for reprint.

**Say:**
> “Printing is optional and local. On a Windows billing PC, a small bridge sends receipts straight to the thermal printer, with no browser dialog. On Android, I pair the device with the printer using a one-time code. Print History shows every job and whether it printed or failed, and I can reprint. Barcode labels print from the browser. I will not claim that a printer works until it has been tested on your hardware.” **[requires hardware verification]**

---

### P. Platform Admin

**What it is.** A separate console for the software provider, outside any store. It onboards stores, manages subscriptions and branch limits, manages store login accounts, and reviews the audit log.

**Why a business needs it.** The provider must be able to create new tenants, suspend or reactivate a store, renew subscriptions, help a locked-out owner and investigate security events — without entering a store’s business data.

**What the customer can do (Platform Admin only).**
- See all registered stores with owner, mobile, branches, users, subscription and status.
- Search and filter stores.
- Onboard a store with a store code, business details, owner login and first branch.
- View a store’s footprint.
- Activate or deactivate a store; deactivation blocks its users.
- Manage subscription end date and maximum branches, with change history.
- Search store login accounts; reset a password; change a username; disable or enable an account.
- View credential history for an account.
- Review and export the audit log.

**What is not available.** There is no store deletion, no platform-side account creation or deletion, and no way to view existing passwords or hashes. A password reset generates a one-time temporary password shown once.

**Difference from Store Admin.**

| | Platform Admin | Store Admin |
|---|---|---|
| Scope | All stores, software provider level | One store |
| Business data | No access through store screens | Full operational access within assigned permissions |
| Main jobs | Onboarding, subscriptions, account recovery, audit | Daily operations, users, printers |
| Password reset | Can reset any store account from the platform console | Can reset a password for a user in their own store (User edit, change forced at next sign-in) |

**How to navigate.** Sign in with the platform account; the app opens **Platform → Stores** (`/platform/stores`). Other pages: `/platform/accounts`, `/platform/audit`.

**Step-by-step demonstration.**
1. Sign out of the store account, then sign in with the platform account (never show the password).
2. On **Registered stores**, point to the counters **Stores**, **Active stores**, **Branches**, **Users**.
3. Search for `SRIRAM01`; open **[View store]** and show branches, users, subscription and branch limit.
4. Open **[Manage subscription]**; show the dates, maximum branches, remaining branches and **Change history**. Do not change real dates unless you are in the demo store.
5. Open **Store Accounts**; search `owner`; show **Credential history**, **Reset password**, **Change username** and **Disable account**.
6. Reset the **demo `staff` account** password only (not the owner you are signed in as); show the **Temporary password generated** dialog, explain that it is shown once, and **blur or pause the recording before copying it**. Do not reset a real customer.
7. Open **Audit Log**; filter by store `SRIRAM01` and show login, password and account events. Click **[Export CSV]** to show the export.
8. Open **Platform contact** and explain that these details appear to stores when a subscription expires.
9. Sign out of the platform account and sign back in as the store owner before continuing.

**What happens after saving.** Store deactivation blocks logins and invalidates active sessions. A subscription change takes effect immediately and is recorded in history. A password reset sets a temporary password, forces a password change on first use, and invalidates old sessions. A username change does not invalidate the current session. Audit rows are append-only.

**How it connects.** Platform Admin creates the store that the owner later uses; subscription dates and branch limits flow into **Store Settings**; password resets flow into the forced password-change screen; every action flows into the audit log.

**Practical example.** A new customer calls; the provider onboards the store, gives the owner the store code and login, and later extends the subscription and branch limit when the business opens a second outlet.

**Say:**
> “The Platform Console is for the software provider, not for the shop owner. Here I can see every registered store, onboard a new one, manage its subscription and branch limit, and activate or deactivate it. I can help an owner who is locked out by resetting the password — the temporary password is shown once and must be changed at first login. And every sensitive action is recorded in the audit log. I never see a customer’s existing password, and I never enter their business data.”

---

### Q. Audit, security and subscriptions

**What it is.** The controls that protect the business: secure sessions, password changes and resets, role permissions, store and branch isolation, lockout, audit history and subscription enforcement.

**Why a business needs it.** Stock and sales data are sensitive. The owner needs confidence that staff see only their branch, that a stolen session can be invalidated, that a disabled employee cannot sign in, and that there is a record of security events.

**What the customer can do.**
- Sign in with a secure HttpOnly session cookie; the token is not readable by page scripts.
- Change their own password; all other sessions are signed out.
- Have an administrator reset a password; the next login forces a change.
- Use roles and branch assignment to limit access.
- Benefit from a 5-attempt / 15-minute login lockout.
- Have the platform team review audit events and export them to CSV.
- See subscription dates and branch limits; renewal restores access immediately.

**How it works (verified).**
- The session is a 12-hour signed token delivered in a secure cookie that page scripts cannot read; the browser does not send it with cross-site requests.
- Every request re-checks the account status, store status and token version; branch access is enforced whenever branch-scoped data is read or written.
- A password change or administrator reset invalidates all older sessions.
- Logout revokes the token.
- Cookie-based write requests require a trusted Origin, which blocks cross-site request forgery.
- Store data is scoped by store ID; branch users are limited to assigned branches.
- Audit events include login, store registration, password change/reset, user and role changes, purchases, sales, cancellations, transfers, adjustments, imports and platform actions. Logout and failed logins are not recorded.
- Store users do **not** have an audit viewer; the platform team does.
- There is **no MFA** and **no self-service “forgot password”**; recovery is through a store administrator or the platform team.

**How to navigate.** **Store Settings → My security** (`/settings?tab=security`); **Change password** `/change-password`; Platform **Audit Log** `/platform/audit`.

**Step-by-step demonstration.**
1. Open **Store Settings → My security**; show **Change your password**, the account card and the **Server version** card.
2. Click **[Change password]** (or the key icon in the sidebar); show **Current password**, **New password**, **Confirm new password** and the policy hint. Do not actually change the demo password unless you have planned the new one.
3. Explain that after a password change, other sessions are signed out.
4. Open **Users**; show a deactivated user and explain that login is blocked.
5. As the platform admin, open **Audit Log**; filter by action **LOGIN** and show the entries.
6. Open **Store Settings → Store profile**; show the subscription card and explain what happens on expiry: business screens are blocked with a subscription-expired message until renewal.
7. Explain isolation in plain words: a store cannot see another store’s items, sales or purchases; a branch user cannot see unassigned branches.

**What happens after saving.** Password changes invalidate old sessions. Account deactivation blocks login. Subscription expiry blocks business APIs but still allows sign-in, health and subscription summary so the owner can contact the provider. Branch-limit enforcement is server-side, not just a disabled button.

**How it connects.** Security protects every module. Permissions control navigation and APIs. Audit connects all sensitive actions. Subscription connects the platform console to the store’s branch creation and access.

**Practical example.** A cashier leaves the business. The owner deactivates the account; the cashier’s session stops working, and the audit log still shows the account’s past activity. The owner does not need to change everyone else’s password.

**Say:**
> “Security is built into the workflow. The session is stored in a secure cookie that page scripts cannot read. Every request checks the account, the store and the branch. A password change signs out other sessions, and a deactivated employee cannot sign in. The platform team can review an append-only audit log and export it. On subscriptions: each store has dates and a branch limit, and the server enforces both. There is no MFA yet, and password recovery is handled by an administrator, not a self-service email link.”

---

## 5. Five complete real-world business workflows

These examples show how the modules work together. Use them in a live sales meeting when the customer asks, “But how would I actually use it?”

### Example 1 — Starting a new business

**Goal:** a new store is ready to bill within one working session.

| Step | Module | What happens | Data created |
|---|---|---|---|
| 1 | Registration | The owner registers the store and first branch | Store, Main Branch, owner account, five GST slabs, four roles, one-year subscription |
| 2 | Store Settings → Store profile | Business details, GSTIN and tax mode are confirmed | Store profile |
| 3 | Store Settings → Branches | Extra branches are added within the plan limit | Branch records |
| 4 | Store Settings → Users | A manager and cashier are created and assigned to branches | Users with roles and branch access |
| 5 | Categories / Tax Master | The catalogue structure and GST slabs are confirmed | Categories; default slabs already exist |
| 6 | Items | Items are created or imported | Item master with rates, units, taxes and minimum stock |
| 7 | Suppliers | Suppliers are created with their states | Supplier master |
| 8 | Excel import or Purchase | Opening stock is imported, or the first purchase brings new stock in | Opening-stock or purchase movements in the branch |

**End state:** the owner can open POS, scan or search an item, take payment and print a bill. The dashboard shows stock value and low-stock alerts. Reports are ready for the first day of trading.

**Say:**
> “A new business can be ready in one session: register, confirm the branch and GST details, add the team, load the catalogue and bring in opening stock. From that moment, billing, stock and reports all work from the same data.”

---

### Example 2 — Purchasing new stock

**Goal:** buy 24 milk and 12 curd from a local supplier and see stock and cost update.

1. **Suppliers:** Nandini Distributors already exists with state Karnataka.
2. **Purchases → New purchase:** choose branch MAIN and supplier Nandini, enter supplier invoice `ND-1043`.
3. **Lines:** Milk 24 × ₹48, Curd 12 × ₹32. The system calculates 5% GST.
4. **Totals:** subtotal ₹1,536, CGST ₹38.40, SGST ₹38.40, grand total ₹1,612.80.
5. **Complete:** choose **Completed · update stock** and save.
6. **Inventory:** MAIN Milk rises from 96 to 120; Curd rises from 49 to 61.
7. **Cost:** the branch purchase price and weighted-average cost update for both items.
8. **Reports:** the purchase appears in the purchase report and the stock movement report.
9. **GST:** the GST report shows the sales-side output tax; the purchase document keeps its own tax breakdown for reference.

**Data flow:** supplier → purchase lines → tax calculation → stock movement `PURCHASE_IN` → branch stock and average cost → dashboard, inventory, purchase report, profit COGS.

**Say:**
> “A purchase is not just a record. Completing it brings stock into the branch, updates the cost, and makes the stock available for billing. The GST is saved on the purchase, and the reports pick it up immediately.”

---

### Example 3 — Selling an item

**Goal:** a customer buys two milk, one curd, two chips and one drink for cash.

1. **POS Billing:** select branch MAIN.
2. **Scan or search:** scan the Milk barcode or type `MILK`; tap the item twice.
3. **Cart:** add Curd once, Chips twice and Soft drink once. Quantity controls allow changes; the rate is fixed to the item master.
4. **Stock check:** each line shows available stock; the system refuses quantities above availability.
5. **Customer and payment:** customer state Karnataka; payment Cash; amount received ₹300.
6. **GST:** subtotal ₹240, CGST ₹13.20, SGST ₹13.20, total ₹266.40; change ₹33.60.
7. **Complete:** stock is deducted immediately.
8. **Receipt:** print through the bridge if configured, or use **Print receipt** and save as PDF.
9. **History:** the bill appears in **Sales History** with payment and balance.
10. **Inventory and reports:** Milk 107, Curd 61, Chips 53, Soft drink 46; the sale appears in sales, GST, profit and stock movement reports.

**Data flow:** item master rate and tax → server re-price → stock validation → `SALE_OUT` → invoice number → receipt → sales/GST/profit reports → dashboard.

**Say:**
> “The cashier scans, the bill builds, GST is calculated, payment is taken, and stock drops before the customer leaves. The server re-checks the price and stock, so an old screen cannot create an oversell or a wrong rate.”

---

### Example 4 — Managing multiple branches

**Goal:** move slow-moving milk and curd from the main store to the city branch.

1. **Branches:** BLR exists within the plan limit.
2. **Stock transfer:** create MAIN → BLR for Milk 10 and Curd 5.
3. **Approval:** the owner approves; no stock moves yet.
4. **Dispatch:** the system checks MAIN stock and deducts Milk 10 and Curd 5.
5. **Receive:** the destination confirms receipt; BLR stock rises by 10 and 5 at the source cost.
6. **History:** both branches show `TRANSFER_OUT` and `TRANSFER_IN` movements.
7. **Reports:** branch performance and stock reports show the new distribution.

**Data flow:** transfer request → approval → dispatch stock-out at source → receive stock-in at destination → branch inventories, movement ledger, branch reports.

**Say:**
> “Stock movement between branches is documented at every step. Approval, dispatch and receipt are separate actions, so the owner knows exactly when stock left and when it arrived. Neither branch can silently change the other’s stock.”

---

### Example 5 — Managing low stock

**Goal:** tea is below its minimum; buy more and clear the alert.

1. **Dashboard / Low Stock:** Tea shows 25 on hand against a minimum of 30, so it is low.
2. **Decision:** the owner checks the low-stock report and the value at risk.
3. **Purchase:** create a purchase for Tea 40 × ₹95 from the supplier; complete it.
4. **Stock:** MAIN Tea rises from 25 to 65.
5. **Alert:** the item is no longer low because 65 is above 30.
6. **Reports:** the stock movement report shows the purchase-in; the low-stock report no longer lists Tea.

**Data flow:** low-stock rule (`on hand <= minimum`) → dashboard/report → purchase → stock-in → rule recalculated → alert clears.

**Say:**
> “Low stock is not a guess. Every item has a minimum, and the system flags it when the branch stock is at or below that level. I can go straight from the alert to a purchase, and the alert clears when the stock is healthy again.”

---

## 6. Full video script (18 chapters)

**Total duration: about 44–45 minutes.** Record one chapter at a time; pause between chapters so you can cut cleanly. Keep the narration natural — do not read like a manual.

**Before you start:** sign out, close extra tabs, hide the bookmarks bar, and open `https://hiremathwebstudio.in/login`. Do not show password fields while typing; use the show/hide control only when necessary, and never show the platform password.

### Chapter 1 — Introduction (1:00)

| Field | Detail |
|---|---|
| Section | Welcome and what you will see |
| Duration | 1 minute |
| Screen | Login page, not signed in |
| Action | Stand on the login page; point to the product name and the two links |
| Narration | “Hello, and welcome. In the next few minutes I will show you the Item Management System — a multi-branch inventory and billing platform for retail and trading businesses. You will see how a store is created, how items and barcodes are set up, how purchases bring stock in, how POS billing works, how GST is calculated, and how the owner sees the whole business in reports. Everything you see is the live application.” |
| Expected result | The login screen is visible with **Store code**, **Username or email**, **Password**, **Sign in** and **Register your business** |
| Transition | “Let us start with the problems this solves.” |

### Chapter 2 — Business problems and solution (1:30)

| Field | Detail |
|---|---|
| Section | Why a business needs this |
| Duration | 1 minute 30 seconds |
| Screen | Login page |
| Action | Speak to camera; no clicking |
| Narration | “Most growing shops face the same problems. Stock is kept in registers, so the shelf and the book do not match. The counter cannot find an item quickly. The owner does not know what is happening at the branch. Purchase costs are guessed. GST is calculated by hand. And there is no record of who changed what. This system solves those problems with one catalogue, branch-wise stock, barcode billing, proper purchase and sales documents, GST calculation, role-based access and reports. It is not accounting software — it is an operations platform for inventory and billing.” |
| Expected result | The customer understands the scope and the honest boundary |
| Transition | “Let me show you how a new store starts.” |

### Chapter 3 — Store registration and login (3:00)

| Field | Detail |
|---|---|
| Section | Onboarding and first sign-in |
| Duration | 3 minutes |
| Screen | `/register`, then `/login`, then dashboard |
| Action | Click **Register your business**; fill the store form; create the store; sign in |
| Narration | “This is the registration screen. I enter a unique store code — this becomes our sign-in identity — then the store name, owner name and first branch. I add the owner email, mobile, address, GSTIN and state. The state matters because it decides intra-state or inter-state GST. At the bottom I choose the owner username and password. When I create the store, the system prepares the main branch, the GST slabs and the four staff roles automatically. It does not sign me in automatically; it takes me to the login page with the store code filled in. I enter my username and password, and I am in.” |
| Expected result | Toast **Store registered successfully. You can sign in now.**; login page with store code pre-filled; dashboard after sign-in |
| Transition | “This is the dashboard.” |

### Chapter 4 — Dashboard overview (2:00)

| Field | Detail |
|---|---|
| Section | The owner’s daily view |
| Duration | 2 minutes |
| Screen | Dashboard `/` |
| Action | Point to the four stat cards, branch performance, top items, stock overview, low stock and recent activity; use the branch selector |
| Narration | “The dashboard answers the owner’s first questions of the day. Today’s sales, today’s purchases, inventory value and low-stock alerts. Below that, branch performance compares sales and purchases, top-selling items shows what is moving, and the branch stock overview shows the value and low-stock count at each location. Recent purchases and recent sales give the latest activity. I can switch between all branches or one branch. The menu is grouped the way a shop works: sell, catalogue, stock, insights and administration.” |
| Expected result | Live figures from the baseline demo data; Tea appears in low stock |
| Transition | “Next, the store and branch setup.” |

### Chapter 5 — Store and branch setup (2:30)

| Field | Detail |
|---|---|
| Section | Business profile, GST mode and branches |
| Duration | 2 minutes 30 seconds |
| Screen | Store Settings → Store profile, then Branches |
| Action | Show profile fields, tax mode, subscription card; add branch BLR |
| Narration | “The store profile holds the business identity and GST number. The store code is fixed after registration. Tax inclusive pricing decides whether GST is added on top of the rate or already included. Here I can also see the subscription dates, maximum branches, existing branches and remaining branches. In the Branches tab, Main Branch is already created. I add a second branch, Bengaluru City, with its own address, contact and state. The branch state is used for GST on future bills. The branch code cannot be changed after creation, and the system will not allow more branches than the plan allows.” |
| Expected result | BLR appears in the branch list and the branch selector; branch counters update |
| Transition | “Now the team and their permissions.” |

### Chapter 6 — Users and permissions (2:30)

| Field | Detail |
|---|---|
| Section | Roles, branch access and custom permissions |
| Duration | 2 minutes 30 seconds |
| Screen | Store Settings → Users, then Roles & permissions |
| Action | Show the team; edit the manager to add BLR; show the four roles; create a Cashier role |
| Narration | “Access is controlled by roles, not by sharing one password. The owner sees everything. The store admin handles daily operations and users, but not the store profile, branches or roles. The branch manager handles purchases, stock, transfers and sales for assigned branches. The branch staff user gets POS and sales. I assign the manager to both branches. I can also create a custom role — here is a Cashier role with only dashboard, item lookup and billing permissions. When I change permissions, the user gets them on the next sign-in or session refresh. A deactivated user cannot sign in, but their history remains.” |
| Expected result | Manager shows MAIN and BLR; Cashier role card shows the selected permission count |
| Transition | “Now the catalogue: categories, taxes and items.” |

### Chapter 7 — Master data, items and Excel import (3:30)

| Field | Detail |
|---|---|
| Section | Categories, GST slabs, items and bulk import |
| Duration | 3 minutes 30 seconds |
| Screen | Categories, Tax Master, Items, Import preview |
| Action | Show categories and tax slabs; open an item; open stock; upload `demo-items.xlsx` and confirm |
| Narration | “Everything starts with clean master data. Categories group the catalogue. Tax Master holds the GST slabs — I enter one GST rate, and the system derives CGST, SGST and IGST. Items carry the item code, unit, selling rate, tax and minimum stock. This is the single source of truth for billing and reports. For a large catalogue, I use the Excel template instead of typing every item. I choose the branch, upload the file, and the system shows a preview before writing anything: how many new items, how many stock updates, and any invalid or duplicate rows. One bad row blocks the whole import. When everything is clean, I confirm, and the categories, items and opening stock are created together.” |
| Expected result | Preview shows 6 rows, 1 new category, 6 new items, 6 stock updates, 0 invalid; confirm succeeds; BLR inventory shows the six items |
| Transition | “Every item can have a barcode.” |

### Chapter 8 — Barcode generation and scanning (2:30)

| Field | Detail |
|---|---|
| Section | Labels, PNG download and scanner input |
| Duration | 2 minutes 30 seconds |
| Screen | Items → Barcode label dialog; POS scan field |
| Action | Open the Milk label, set 2 copies, download PNG, print or save as PDF; explain Code 128 and EAN-13; scan or type the barcode |
| Narration | “Every item gets its own barcode inside the store. If I leave the barcode blank, the system generates one — Code 128 by default, or EAN-13 if I prefer a retail format. I can preview the 50 by 30 millimetre label, print one or many copies, or download the barcode as an image. At billing, the cashier can scan with a USB scanner or use the camera. The barcode is unique to my store, so two stores can use the same supplier barcode without clashing. If I regenerate a barcode, old printed labels stop working, so the system warns me first.” |
| Expected result | Label preview with name, code, barcode and price; PNG downloaded; barcode accepted in the scan field |
| Transition | “Now let us buy stock from a supplier.” |

### Chapter 9 — Purchase and supplier management (4:00)

| Field | Detail |
|---|---|
| Section | Supplier, purchase entry, GST and stock receipt |
| Duration | 4 minutes |
| Screen | Suppliers, Purchases, New purchase, Inventory |
| Action | Show suppliers; create purchase PUR-1003; scan Milk; enter rates; complete; print; show stock |
| Narration | “Purchases are how stock and cost enter the business. I choose the branch and supplier, enter the supplier’s invoice number, and add item lines. I can scan a barcode to add the item. The GST is calculated on each line — CGST and SGST because the supplier is in Karnataka. The summary shows subtotal, tax and grand total. I can save a draft, which does not touch stock, or complete the purchase, which receives the stock immediately and updates the branch cost. Here I complete it. The purchase document can be printed, and the inventory immediately shows the new quantity. A completed purchase cannot be edited; if it is wrong, it can be cancelled and the stock movement is reversed.” |
| Expected result | Purchase completes; MAIN Milk 120, Curd 61; purchase document shows ₹1,612.80 |
| Transition | “Let us look at stock in detail.” |

### Chapter 10 — Inventory management (2:30)

| Field | Detail |
|---|---|
| Section | Branch stock, adjustments, movements and low stock |
| Duration | 2 minutes 30 seconds |
| Screen | Inventory, Low Stock |
| Action | Show stock levels; adjust Milk out 1 and Curd in 6; show movements; open pricing; open low stock |
| Narration | “Inventory shows the truth at each branch: on hand, minimum, cost, selling price, stock value and status. Every change has a reason and a reference. One packet has expired, so I adjust stock out with a reason; I do not create a fake sale. If the physical count finds extra curd, I adjust stock in. The Movements tab shows the full history. I can also set the branch minimum and prices. Stock can never go negative. Low Stock lists every item at or below its minimum, with the value at risk.” |
| Expected result | Milk 119, Curd 67 after adjustments; movement rows visible; Tea remains low at 25 |
| Transition | “Now move stock between branches.” |

### Chapter 11 — Stock transfers (2:30)

| Field | Detail |
|---|---|
| Section | Approval, dispatch and receipt |
| Duration | 2 minutes 30 seconds |
| Screen | Stock Transfers |
| Action | Create MAIN → BLR transfer for Milk 10 and Curd 5; approve; dispatch; receive; verify both branches |
| Narration | “Transfers are controlled, not informal. I choose the source and destination, add the items and quantities, and submit. The transfer waits for approval. After approval, dispatch deducts the stock from the source, and receiving adds it to the destination at the same cost. Both branches keep a permanent movement record. Dispatch cannot be undone — the stock must be received. This is how a main store can support a branch without losing track of stock.” |
| Expected result | Status moves Pending → Approved → Dispatched → Received; MAIN Milk 109, Curd 62; BLR Milk 10, Curd 5 |
| Transition | “Now the counter: POS billing.” |

### Chapter 12 — POS and billing (4:00)

| Field | Detail |
|---|---|
| Section | Scan, cart, GST, payment and receipt |
| Duration | 4 minutes |
| Screen | POS Billing |
| Action | Select MAIN; scan Milk; add items; adjust quantity; customer state; cash ₹300; complete; print or PDF; show inventory |
| Narration | “This is the counter screen. I choose the branch and scan the Milk barcode. The item appears in the bill. I add curd, chips and a drink. I can change quantity with plus and minus. The rate is fixed to the item master, so the cashier cannot use an old price, and the system checks available stock before it sells. I enter the customer state for GST, choose Cash, and enter the amount received. The system shows the tax split and the change. When I complete the sale, stock is deducted immediately and the receipt is ready to print. If a print bridge is configured, it prints automatically; otherwise I can save the receipt as a PDF.” |
| Expected result | Sale completed for ₹266.40 with ₹33.60 change; MAIN Milk 107, Curd 61, Chips 53, Soft drink 46 |
| Transition | “POS is for the counter; manual sales are for negotiated billing.” |

### Chapter 13 — Sales management (2:30)

| Field | Detail |
|---|---|
| Section | Manual invoice, history and cancellation |
| Duration | 2 minutes 30 seconds |
| Screen | Sales Entry, Sales History |
| Action | Show the Ramesh IGST invoice; show the Anita credit invoice; create a 1 Tea test sale; cancel it |
| Narration | “Sales Entry is for negotiated or wholesale billing. Here I can enter a different selling rate and a line discount for each line, and still get a proper GST invoice. The customer’s state decides CGST and SGST or IGST. After the sale, I can print the receipt. Sales History keeps every bill, including credit sales with a balance. If a bill is wrong, I cancel it — stock is restored and the invoice stays in history as cancelled, so the record is never lost. There are no partial returns in this release; a sale is cancelled as a whole.” |
| Expected result | Ramesh invoice shows IGST; Anita shows balance; test Tea sale becomes Cancelled and Tea returns to 25 |
| Transition | “Now GST and reports.” |

### Chapter 14 — GST and reports (3:30)

| Field | Detail |
|---|---|
| Section | Tax calculation, GST summary and business reports |
| Duration | 3 minutes 30 seconds |
| Screen | Tax Master, Purchases, Sales History, Reports |
| Action | Show CGST/SGST vs IGST documents; open GST report; show sales, purchases, stock, profit, branch, store and low stock; export Excel and PDF |
| Narration | “GST is calculated on every line and saved with the document. A local supplier or customer gives CGST and SGST. An out-of-state supplier or customer gives IGST. I can set prices as GST-exclusive or GST-inclusive at the store level. The GST report gives a rate-wise summary for a period and branch. The other reports show sales, purchases, stock movement, gross profit, branch performance, the store summary and low stock. Every report can be exported to Excel or PDF, or printed. Two honest points: profit here is gross trading margin, not accounting profit; and the GST report is a summary of sales tax, not a filing return.” |
| Expected result | GST report shows CGST/SGST and IGST rows; Excel and PDF download; reports match the demo transactions |
| Transition | “Now printing.” |

### Chapter 15 — Printer setup and printing (2:00)

| Field | Detail |
|---|---|
| Section | Bridge, printer configuration, history and reprint |
| Duration | 2 minutes |
| Screen | Store Settings → Printers; Print History |
| Action | Show bridge options; show/add a printer; show print history; reprint or save PDF; state hardware caveat |
| Narration | “Printing is optional and local. On a Windows billing PC, a small bridge sends receipts straight to the thermal printer, with no browser dialog. On Android, I pair the device with the printer using a one-time code. Print History shows every job and whether it printed or failed, and I can reprint. Barcode labels print from the browser. I will not claim that a printer works until it has been tested on your hardware.” |
| Expected result | Printer settings and history visible; PDF receipt if no hardware; no fake printed job |
| Transition | “Finally, the platform console that manages stores.” |

### Chapter 16 — Platform Admin (2:30)

> **Account switch (pause recording while you do this):** sign out of the demo store account, then sign in with the platform account. Never type the platform password on camera. At the end of this chapter, sign out and sign back in as the store owner before Chapter 17.

| Field | Detail |
|---|---|
| Section | Stores, subscriptions, account recovery and audit |
| Duration | 2 minutes 30 seconds |
| Screen | Platform → Stores, Store Accounts, Audit Log |
| Action | Search SRIRAM01; view store; show subscription; show account actions; reset the demo `staff` password with the password blurred; show audit export |
| Narration | “The Platform Console is for the software provider, not for the shop owner. Here I can see every registered store, onboard a new one, manage its subscription and branch limit, and activate or deactivate it. I can help a team member who is locked out by resetting the password — for example this demo cashier account. The temporary password is shown once and must be changed at first login. I never see a customer’s existing password. Every sensitive action is recorded in the audit log, and the log can be exported. The platform admin does not enter the store’s business data.” |
| Expected result | Store details, subscription history, credential history and audit rows visible; temporary password blurred |
| Transition | “Let us finish with security and the business benefits.” |

### Chapter 17 — Security and business benefits (1:30)

| Field | Detail |
|---|---|
| Section | Controls and value |
| Duration | 1 minute 30 seconds |
| Screen | Store Settings → My security; Users; Dashboard |
| Action | Show password change and account status; summarise benefits |
| Narration | “Security is built into the workflow. The session is stored in a secure cookie that page scripts cannot read. Every request checks the account, the store and the branch. A password change signs out other sessions, and a deactivated employee cannot sign in. The platform team can review an append-only audit log. On benefits: better stock control, faster billing, barcode operations, multi-branch visibility, purchase tracking, GST calculation, reports, role-based access and optional silent printing. This is one system for the counter, the stock room and the owner’s desk.” |
| Expected result | Security tab and user status visible; benefits summarised |
| Transition | “Thank you — here is how to get started.” |

### Chapter 18 — Conclusion and call to action (1:00)

| Field | Detail |
|---|---|
| Section | Close |
| Duration | 1 minute |
| Screen | Dashboard |
| Action | Return to the dashboard; speak to camera |
| Narration | “That is the Item Management System: a multi-branch inventory and billing platform with GST documents and optional silent printing. If you would like, we can set up your store, import your item list and run a live billing session with your own products. We will also test the printer and scanner on your hardware before you rely on them. Thank you for watching.” |
| Expected result | Dashboard visible; clear next step |
| Transition | End recording |

---

## 7. Short 5–10 minute customer introduction

Use this when the customer has limited time. It covers the same product without the full setup detail.

**Total: about 8 minutes 45 seconds.**

| Segment | Duration | Screen | Narration |
|---|---:|---|---|
| 1. Introduction | 0:30 | Login page | “This is the Item Management System — a multi-branch inventory and billing platform for retail and trading businesses. In the next few minutes I will show you onboarding, items, barcode billing, stock, GST and reports.” |
| 2. Dashboard | 1:00 | Dashboard | “The dashboard shows today’s sales, purchases, stock value and low-stock alerts, plus branch performance and the latest activity. The owner gets the day at a glance.” |
| 3. Items and barcode | 1:15 | Items, label dialog | “Items carry the code, unit, rate, GST and minimum stock. Each item gets a unique barcode, and I can print 50 by 30 millimetre labels or download the barcode as an image.” |
| 4. Purchase | 1:00 | New purchase | “A completed purchase brings stock in, updates cost and records GST. A draft changes nothing. Cancelling a completed purchase reverses the stock.” |
| 5. POS billing | 1:30 | POS | “At the counter I scan or tap items, the rate comes from the item master, stock is checked, GST is calculated, payment is taken, and the receipt is ready. Stock drops immediately.” |
| 6. Sales and inventory | 1:00 | Sales History, Inventory | “Sales History keeps every bill, and cancelled bills stay visible with stock restored. Inventory shows branch-wise stock with a full movement history and low-stock alerts.” |
| 7. GST and reports | 1:00 | Reports → GST | “GST splits into CGST and SGST or IGST. Reports cover sales, purchases, stock, gross profit, branches, the store and low stock, and export to Excel or PDF.” |
| 8. Printing and security | 1:00 | Printers, Print History, My security | “Receipts can print silently through a local bridge. The provider also has a separate platform console for stores and subscriptions with an audit log. Roles and branch access protect the data.” |
| 9. Close | 0:30 | Dashboard | “If this fits your business, we can set up your store and run a live session with your own items, printer and scanner.” |

**Tip:** if the customer is a single-store retailer, skip the branch-transfer segment and spend the extra minute on POS and barcode scanning. If the customer is a distributor, spend it on purchases, cost and branch reports.

---

## 8. Sales presentation kit

### 8.1 Opening statement (30 seconds)

> “Thank you for your time. I want to show you a system that solves the everyday problems of a growing shop: stock that does not match the shelf, slow billing, no visibility across branches, and GST calculated by hand. The Item Management System brings your items, purchases, stock, billing, GST and reports into one place. It is built for Indian retail and trading businesses, and it runs in the browser — there is nothing to install to start billing. A small local helper is needed only if you want silent receipt printing.”

### 8.2 Sixty-second product introduction

> “The Item Management System is a multi-branch inventory and billing platform. You create your store, add your items and suppliers, and bring in opening stock manually or through Excel. Purchases record cost and GST and bring stock into the branch. At the counter, POS billing uses the item master rate, validates stock, calculates GST and deducts stock immediately. Branches can transfer stock with approval, dispatch and receipt. Every item can have a barcode and a printed label. The owner gets a dashboard and eight report families, including sales, purchases, stock movement, gross profit, GST, branch performance and low stock. Access is controlled by roles and branch assignment, and the provider has a separate console for onboarding, subscriptions and audit. Optional silent printing sends receipts directly to a thermal printer through a local bridge.”

### 8.3 Key benefits and proof points

| Benefit | What it means for the customer | Where to show it |
|---|---|---|
| Better inventory control | Stock updates on every purchase, sale, transfer and adjustment; no negative stock | Inventory, POS, Movements |
| Faster billing | Search or scan, tap to add, automatic GST and totals | POS |
| Barcode operations | Unique per-store barcodes, labels, PNG download, scanner and camera input | Items, label dialog, POS |
| Multi-branch visibility | Branch-wise stock, branch performance and controlled transfers | Branches, dashboard, reports |
| Purchase tracking | Supplier history, purchase documents, weighted-average cost | Purchases, Suppliers |
| Stock movement trail | Every in/out has a type, reference and balance | Inventory → Movements |
| GST calculation | CGST/SGST or IGST per line, inclusive or exclusive, saved with the document | Purchases, sales, GST report |
| Business reports | Sales, purchases, stock, gross profit, GST, branches, store, low stock; Excel and PDF | Reports |
| User permissions | Four built-in roles, custom roles, branch-level access | Users, Roles |
| Printing integration | Optional silent thermal printing through Windows or Android bridge | Printers, Print History |
| Centralised management | One catalogue and one set of reports for all branches | Dashboard, Items, Reports |
| Excel import | Validate before writing; automatic categories; branch opening stock | Items → Upload Excel |

### 8.4 Common customer questions — honest answers

| Customer question | Answer |
|---|---|
| “Is it cloud-based or installed on my computer?” | It is a web application. You use it in a browser; there is nothing to install on shop computers. It needs an internet connection. The optional print bridge is a small local program on the billing device. |
| “Can it work without internet?” | No. Billing and stock need a connection to the server. There is no offline mode or queued billing in this release. |
| “Can I manage more than one branch?” | Yes. Stock, purchases, sales, transfers and reports are branch-wise. New stores start with a branch limit of five; the provider can change the limit through the platform console. |
| “Does it file GST returns?” | No. It calculates GST on bills and provides a GST summary report. It does not file GSTR-1 or GSTR-3B, and it does not do e-invoicing or e-way bills. |
| “Can I make sales returns or credit notes?” | Not as partial returns. You can cancel a completed sale as a whole, which restores stock and keeps the invoice marked cancelled. Purchase returns are also not supported as partial documents. |
| “Is it accounting software?” | No. It is an operations platform for inventory and billing. It does not have ledgers, trial balance, P&L or balance sheet. |
| “Can I manage customers and credit?” | You can record a customer name, mobile and state on a bill, and a credit sale shows a balance. There is no customer master, credit limit, ledger, aging or payment-collection screen. |
| “Can I track supplier payments?” | A purchase records paid and balance amounts. There is no supplier ledger or payment schedule. |
| “Does it support barcode scanners?” | It accepts USB/keyboard-wedge scanner input and provides camera scanning in supported browsers. Test your exact scanner and phone on site before relying on it. |
| “Can it print to my thermal printer?” | Yes, through the Windows or Android print bridge for sale receipts, with 58 mm or 80 mm paper. Test your printer and cable on site. Purchase documents and reports print from the browser. |
| “Can I import my existing item list?” | Yes, through the Excel template with a preview-first import, automatic category creation and branch opening stock. Limits: 2,000 rows and 10 MB; keep the file under the current production upload limit until it is raised. |
| “Can I control what staff see?” | Yes. Four built-in roles plus custom roles, and branch assignment for branch managers and staff. Users only see the branches assigned to them. |
| “Is my data separate from other stores?” | Yes. Each store is a separate tenant; store data is scoped by store ID, and branch users are limited to assigned branches. |
| “Do you support MFA or single sign-on?” | Not yet. The system supports strong passwords, login lockout, session invalidation and an audit log. |
| “What if I forget my password?” | There is no self-service email reset. A store administrator or the platform team resets it, and the next login forces a password change. |
| “Can I customise it?” | You can create custom roles and choose permissions, but there is no custom-code or workflow customisation in this release. |
| “Does it work in multiple currencies?” | No. Amounts are in Indian rupees, with Indian GST slabs. |
| “Can I share invoices on WhatsApp?” | No. You can print or save the receipt as a PDF from the browser. |
| “Is there a mobile app?” | The application works in a mobile browser. The Android app in this release is a print bridge, not a full mobile POS. |
| “How long does onboarding take?” | A basic store can be ready in one session: register, add branches and users, import or create items, add suppliers and bring in opening stock. |
| “What happens when the subscription expires?” | Business screens are blocked with a subscription-expired message until the provider renews it. Sign-in and subscription summary remain available so the owner can contact the provider. |
| “Can I delete a store, item or supplier?” | Items, suppliers, branches and users can be deactivated; categories and tax slabs can be deleted only when unused. Stores cannot be deleted from the UI. |

### 8.5 Closing statement and next steps

> “That is the Item Management System. It gives you one catalogue, branch-wise stock, fast barcode billing, purchase and sales documents with GST, controlled transfers, reports and role-based access. The next step is simple: we set up a demo store with your own item list, run a live billing session, and test your printer and scanner on your hardware. If everything fits, we onboard your real store and train your team.”

**Next-step options:**

1. A 30-minute working session with the customer’s own item list.
2. A printer and scanner compatibility check on the billing PC.
3. A branch/stock structure review (how many branches, who approves transfers).
4. A user and permission plan (owner, manager, cashier).
5. A data import dry run with a small sample file.

### 8.6 Claims to avoid

| Do not say | Say instead |
|---|---|
| “Complete accounting software” | “Inventory and billing platform; accounting is not included” |
| “GST filing ready” | “GST billing with CGST/SGST/IGST and a GST summary report” |
| “Net profit” | “Gross trading margin (revenue minus cost of goods sold)” |
| “Customer management” | “Customer name, mobile and state on the bill” |
| “Returns and credit notes” | “Whole-bill cancellation with stock reversal” |
| “Works offline” | “Needs an internet connection” |
| “Unlimited branches” | “Subject to the subscription branch limit” |
| “Printer is verified” | “Requires on-site hardware verification” |
| “Replaces Tally” | “Complements billing and stock operations; it is not a Tally replacement” |
| “Vyapar clone or parity” | “A focused inventory and billing platform with GST billing and branch-wise stock control” |

---

## 9. Feature accuracy: implemented, partial, unavailable

This section is for the presenter. Read it before every customer meeting so you do not promise something the product cannot do.

### 9.1 Fully implemented in v1.0.0

- Store self-registration, store code, owner account, first branch, default GST slabs, four built-in roles and a one-year subscription with a branch limit.
- Secure login with store code, username or email and password; HttpOnly cookie session; logout; password change; forced password change after an administrator reset; session invalidation.
- Dashboard with today’s sales, purchases, inventory value, low-stock alerts, branch performance, top-selling items, branch stock overview, recent purchases and recent sales.
- Store profile, tax-inclusive/exclusive mode, branch management, branch limit, subscription summary.
- Users, roles, branch assignment, user activation/deactivation; custom roles with thirty permission codes.
- Categories, items, units, item codes, single item rate, tax slab, minimum stock, status; suppliers with GSTIN and state.
- Barcode generation (Code 128 and EAN-13), per-store uniqueness, barcode search, label preview, 1–100 label copies, PNG download, keyboard-wedge and camera scanning in POS, sales and purchases.
- Excel item import: template, branch selection, preview, validation, duplicate detection, atomic confirmation, automatic category creation, opening stock, import history.
- Purchases: draft, completed, cancelled; supplier invoice number; line and bill discounts; GST inclusive/exclusive; CGST/SGST/IGST; stock receipt; weighted-average cost; purchase document print; cancellation with stock reversal.
- Inventory: branch stock, adjustments in/out with reason, movement ledger, branch pricing fields, weighted-average valuation, low-stock detection, item stock history.
- Transfers: pending, approved, dispatched, received, rejected, cancelled; approval and dispatch/receive permissions; source and destination stock movements.
- POS: branch selection, search, barcode scan, cart, quantity controls, stock validation, bill discount, customer name/mobile/state, cash/UPI/card/credit, amount received, change, completion, receipt, immediate stock deduction.
- Manual sales: negotiated rate, line discount, bill discount, customer details, payment, invoice, receipt, history, cancellation with stock reversal.
- GST: slabs, CGST/SGST/IGST, intra/inter-state, inclusive/exclusive, per-line rounding and snapshots, GST summary report.
- Reports: sales, purchases, stock movement, profit (gross margin), GST, branch performance, store summary, low stock; filters; Excel, PDF and print from the browser.
- Printing: Windows and Android bridge configuration, printer records, one-time pairing code, print history, job status, reprint; browser receipt and barcode-label printing.
- Platform Admin: store list/search, onboarding, status, subscription dates and branch limit with change history, store account list, username change, password reset with one-time temporary password, enable/disable, credential history, audit log with filters and CSV export, platform contact.
- Security: login lockout after repeated failures, token-version invalidation, revoked-token persistence, CSRF origin guard on cookie writes, store and branch isolation, no-store headers on auth responses.
- Subscription enforcement: expiry blocks business APIs; branch creation is limited server-side.

### 9.2 Partially implemented

| Area | What works | What is limited |
|---|---|---|
| Credit sales | A **Credit** payment records the unpaid balance | No payment collection, customer ledger, aging or statement |
| Customer information | Name, mobile and state are stored on the sale and shown on the receipt | No customer master, credit limit or purchase history by customer |
| Audit | Platform audit log with filters and CSV | Store users have no audit viewer; logout and failed logins are not audited |
| Printing | Bridge configuration, pairing, job history, reprint, auto-print | Only sale receipts go through the bridge; no automatic retry; purchase/report printing is browser-based; hardware unverified |
| Camera scanning | Browser barcode detection with a ZXing fallback | Device and browser support varies; iOS behaviour unverified; hardware unverified |
| Barcode labels | Browser preview, PNG, 1–100 copies | Not a server PDF and not a bridge print job; physical label output unverified |
| Excel import | Preview, validation, atomic import, opening stock | 2,000 rows and 10 MB app limit; the current production web server limits uploads to about 1 MB until raised |
| Subscriptions | Dates, branch limit, expiry enforcement, platform change history | No self-serve renewal or payment collection; provider manages it |
| GST reporting | Sales-side summary by branch, tax and rate | No purchase-side GST report; not a filing return |
| Profit reporting | Revenue, COGS and gross margin by item and branch | Not net accounting profit |
| Branch pricing | Branch purchase price, selling price and minimum are stored | POS uses the item master rate, not the branch selling price |
| Transfers | Full approval, dispatch and receipt flow | No stock reservation while pending; dispatch cannot be undone |
| Store deletion | Stores can be deactivated | No delete endpoint or UI |
| Platform permissions | Custom roles expose the Platform permission group | Store roles must never be granted Platform permissions; keep the console for provider accounts only |

### 9.3 Not available in v1.0.0

**Accounting and finance:** ledgers, chart of accounts, journal entries, cash book, bank reconciliation, expenses, trial balance, P&L, balance sheet, day book, party statements, receivables/payables, payment collection, supplier payments.

**GST compliance:** GSTR-1, GSTR-3B, GSTR-9, e-invoicing, IRN, e-way bills, HSN/SAC codes, purchase GST report.

**Returns:** sales returns, purchase returns, credit notes, debit notes, partial returns, exchanges.

**Billing:** split or multiple payment modes on one bill, partial payments recorded against an invoice after billing, hold/park a bill, table or delivery management, tips, service charge.

**Customer and supplier CRM:** customer master, credit limits, customer ledger, aging, statements, loyalty, offers, price lists, schemes.

**Sharing and integrations:** WhatsApp, email or SMS invoice sharing, payment links, Tally integration, public API, webhooks, OCR, e-commerce.

**Platform:** offline billing, native mobile POS app (Android is a print bridge), multi-currency, MFA/SSO, self-service password reset, scheduled or emailed reports, store deletion, item/supplier/branch/user deletion, automatic print retry, server-side PDF/Excel report generation.

### 9.4 Requires hardware or configuration

| Capability | Requirement | Status |
|---|---|---|
| Silent receipt printing | Windows print bridge on the billing PC, or Android bridge paired to a LAN printer on port 9100 | Software path exists; **on-site hardware verification required** |
| USB barcode scanning | Keyboard-wedge scanner | Software path exists; **test the exact scanner** |
| Camera scanning | Camera permission, supported browser, good lighting | Software path exists; **test the device** |
| Barcode label printing | Label printer or PDF; 50 × 30 mm stock | Browser output exists; **test label alignment and scale** |
| Large Excel imports | Server upload limit raised above the app’s 10 MB limit | Current production host limits uploads to about 1 MB; keep demo files small |
| HTTPS and secure cookies | TLS certificate and the secure-cookie setting enabled | Configured on production; verify on any new host |
| Subscription and branch limit changes | Platform Admin access | Available; provider-managed |

### 9.5 Operational notes for the presenter

- **Upload limit:** the application accepts 10 MB, but the current production web server limits uploads to about 1 MB, so larger files can fail before the app sees them. Ask the platform team to raise it before a large real import.
- **Platform permissions:** never grant the Platform permission group to a store role during a demo. Keep the platform console for the provider account.
- **Malformed API calls:** the API returns a generic 500 for some malformed requests (for example, a missing required query parameter). Normal UI usage is unaffected; do not demonstrate raw API calls.
- **No MFA:** do not imply MFA or SSO.
- **No self-service password reset:** do not show a “forgot password” email flow; it does not exist.
- **No store-user audit:** do not tell a store owner they can open the audit log; only the platform team can.
- **No store deletion:** a demo store created on production will remain. Treat it as a permanent demo tenant.
- **No physical hardware proof:** never say a printer, scanner, camera or Android device has been tested unless it was tested on that customer’s hardware.

### 9.6 If a customer asks for something unavailable

Use a calm, honest response and redirect to what is available:

> “That is not part of the current release. What we do provide is [the closest implemented capability]. If it is important for your business, I can record it as a requirement for the product team, and we can discuss a timeline separately. I would rather be clear now than promise something that is not in the product.”

Examples:

- **Returns:** “Partial returns are not in this release. You can cancel a whole bill, and stock is restored automatically.”
- **GST filing:** “We calculate GST on every bill and give you a GST summary. Filing is done in your GST portal or by your accountant.”
- **Accounting:** “This is an inventory and billing platform. It does not replace your accountant’s ledger or balance sheet.”
- **Customer ledger:** “We capture the customer’s name, mobile and state on the bill, and a credit sale shows the balance. We do not yet provide a customer ledger or collection screen.”
- **Offline billing:** “The system needs a connection. There is no offline queue in this release.”

---

## 10. Security and privacy during recording

Follow these rules without exception.

### 10.1 Data

- Use only the fictional demo store and fictional people. Do not open a real customer’s store, items, sales or reports.
- Use fictional emails on `example` domains, fictional mobile numbers and clearly fictional GSTINs.
- Do not show real customer names, phone numbers, addresses, GSTINs, prices or invoices.
- Do not create unnecessary transactions in a live customer store. The demo transactions belong in the demo store.

### 10.2 Passwords and sessions

- Type only the demo password. Use the show/hide control sparingly; never leave a password visible in a still frame.
- Never type or display the Platform Admin password. Sign in before recording, or cut the sign-in from the final video.
- When demonstrating a password reset, **pause the recording or blur the screen** before the temporary password appears. Do not copy it on camera. Clear the clipboard afterwards.
- Do not save passwords in the document, a text file, a screenshot or the video description.
- Sign out of the demo store and the platform console at the end of the recording; close the browser.

### 10.3 Screen hygiene

- Close DevTools, the network panel, the console, server terminals and database tools.
- Do not show environment files, connection strings, logs, server IPs, SSH details, certificates or deployment scripts.
- Keep the browser zoom and window size fixed so text is readable and layout does not jump.
- Hide notifications, chat apps and personal email.
- Use a clean browser profile with no personal autofill.

### 10.4 Printing

- Do not enqueue real print jobs in a customer’s store.
- If you demonstrate printing, use the demo store and an approved safe test, or use **Save as PDF**.
- A failed print job is visible in Print History; avoid creating noise in a live environment.

### 10.5 After recording

- Sign out; close all tabs.
- Delete the local recording if it contains a temporary password or any unintended data.
- Review the video once at 2× speed for accidental sensitive frames before sharing.
- If a real customer store was opened by mistake, stop, delete the recording, and report it to the platform owner.

---

## 11. Screen and navigation reference

### 11.1 Routes

| Screen | Route | Menu group |
|---|---|---|
| Login | `/login` | Public |
| Register | `/register` | Public |
| Change password | `/change-password` | Account (outside the sidebar layout) |
| Dashboard | `/` | Overview |
| POS Billing | `/pos` | Sell |
| Sales Entry | `/sales/new` | Sell |
| Sales History | `/sales` | Sell |
| Print History | `/print-history` | Sell |
| Items | `/items` | Catalog |
| Import History | `/items/imports` | Catalog |
| Categories | `/categories` | Catalog |
| Tax Master | `/taxes` | Catalog |
| Suppliers | `/suppliers` | Catalog |
| Purchases | `/purchases` | Stock |
| Inventory | `/inventory` | Stock |
| Stock Transfers | `/transfers` | Stock |
| Low Stock | `/low-stock` | Stock |
| Reports | `/reports` | Insights |
| Store Settings | `/settings` | Administration |
| Store profile tab | `/settings?tab=store` | Administration |
| Branches tab | `/settings?tab=branches` | Administration |
| Users tab | `/settings?tab=users` | Administration |
| Roles & permissions tab | `/settings?tab=roles` | Administration |
| Printers tab | `/settings?tab=printers` | Administration |
| My security tab | `/settings?tab=security` | Administration |
| Platform — Stores | `/platform/stores` | Platform |
| Platform — Store Accounts | `/platform/accounts` | Platform |
| Platform — Audit Log | `/platform/audit` | Platform |
| Any unknown route | 404 page | — |

### 11.2 Status glossary

| Area | Statuses and meaning |
|---|---|
| Purchase | **Draft** — saved, no stock effect; **Completed** — stock received and cost updated; **Cancelled** — no further use, stock reversed if it had been completed |
| Sale | **Completed** — stock deducted and invoice issued; **Cancelled** — stock restored, invoice retained |
| Transfer | **Pending** — waiting for approval; **Approved** — ready to dispatch; **Dispatched** — source stock deducted; **Received** — destination stock added; **Rejected** — not dispatched; **Cancelled** — stopped before dispatch |
| Import | **Previewed** — validated, nothing written; **Completed** — rows imported; **Failed** — rolled back |
| Print job | **Pending** — queued; **Printed** — bridge confirmed; **Failed** — error recorded |
| Store / user / branch | **Active** — usable; **Inactive** — blocked from sign-in or business access |
| Subscription | Days remaining or **Expired**; expired stores are blocked from business screens |
| Item stock | **Healthy** — above minimum; **Low** — at or below minimum |

### 11.3 Terms used in the UI

- **Store** — the business tenant, identified by a store code.
- **Branch** — a location inside the store; stock and transactions are branch-wise.
- **Item rate** — the single selling rate in the item master, used by POS.
- **Tax slab** — a GST percentage with derived CGST/SGST/IGST values.
- **Minimum stock** — the reorder level; low stock means on hand is at or below it.
- **Opening stock** — the first stock for an item at a branch, usually from import or a purchase.
- **Movement** — one stock ledger entry (purchase in, sale out, transfer, adjustment or reversal).
- **Bridge** — the local Windows or Android program that sends receipts to a thermal printer.
- **Pairing code** — a one-time code used to connect an Android bridge to a store.
- **Audit log** — append-only record of security-sensitive actions, visible to the platform team.

---

## 12. Final pre-publish checklist

Before you upload or share the video, confirm every item.

### Content accuracy

- [ ] Every menu name and route matches Section 11 and the live app.
- [ ] No feature from Section 9.3 is presented as available.
- [ ] “Profit” is described as gross margin, not net profit.
- [ ] “GST” is described as billing and summary, not filing.
- [ ] Returns are described as whole-bill cancellation, not partial returns.
- [ ] Credit sales are described as a recorded balance, not a collection workflow.
- [ ] Printer, scanner, camera and Android claims include the hardware caveat.
- [ ] No claim of offline mode, MFA, customer ledger, WhatsApp sharing or Tally integration.
- [ ] The narration matches the actions shown on screen.

### Demo data

- [ ] The recording uses the demo store only.
- [ ] Baseline stock checkpoints match Section 3.3 before recording.
- [ ] The live purchase, adjustments, transfer, POS sale and test cancellation follow Section 3.5.
- [ ] The Excel import file validates with 6 rows, 0 invalid and 0 duplicates.
- [ ] Reports show the expected transactions and the Tea low-stock alert.

### Privacy and security

- [ ] No real customer name, mobile, email, GSTIN or address appears.
- [ ] No real password, token, cookie, server address or configuration appears.
- [ ] The temporary password from the platform reset is blurred or cut.
- [ ] DevTools, terminals and database tools are not visible.
- [ ] The demo store and platform console are signed out at the end.
- [ ] The video was reviewed at 2× speed for accidental sensitive frames.

### Production care

- [ ] No print job was enqueued in a real store.
- [ ] No real store settings, subscription or user account were changed.
- [ ] The platform admin password is rotated if it was ever exposed outside the secure login.
- [ ] The demo store is understood to be permanent unless the provider removes it outside the UI.
- [ ] Large Excel imports wait until the production upload limit is raised above 1 MB.

### Video quality

- [ ] Resolution is 1080p or higher; text is readable at 100% zoom.
- [ ] Audio is clear; no fan noise or notification sounds.
- [ ] Chapter timestamps are added to the description.
- [ ] A short 60-second teaser is cut from the short version if needed.
- [ ] Captions or a transcript are added for customers who watch without sound.

---

*Document verified against Item Management System v1.0.0 (commit `afc28b5e`) on 2 November 2026. Screen labels, routes, roles, statuses, GST behaviour, print limits and platform-admin actions were checked in the v1.0.0 source and the deployed application. Hardware-dependent output and any feature marked partial or unavailable must be described exactly as stated here.*
