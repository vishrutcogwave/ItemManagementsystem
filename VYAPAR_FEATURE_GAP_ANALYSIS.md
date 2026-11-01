# Vyapar vs. Item Management System — Feature Gap Analysis

**Document type:** Product and technical gap analysis (research only)
**Prepared:** 2026-11-01
**Scope:** Existing Item Management System in this repository vs. the Vyapar application
**Code baseline:** branch `main`, commit `01567eb` (new code updated)
**Restriction compliance:** No source code, API, database schema, migration, UI or configuration was modified. This is the only file created for this task.

---

## How to read this document

Every capability below was checked against the actual repository (frontend routes, pages, controllers, services, entities, migrations) before assigning a status. Vyapar capabilities were taken from the four official sources listed in Section 3 and are labelled **Verified** (explicitly described on an official Vyapar page) or **Vendor-claimed / plan-dependent** (advertised but the exact plan, platform or country availability was not confirmed).

### Status definitions

| Status | Meaning |
|---|---|
| **Implemented** | The feature exists end-to-end and is wired into a real workflow. |
| **Partially Implemented** | A foundation or a subset exists, but an important part of the feature is absent. |
| **Missing** | No implementation was found in the repository. |
| **Needs Verification** | The repository does not prove whether the behaviour exists (for example, infrastructure-level controls), so it must be tested before judging. |
| **Not Applicable** | The capability has no relevance to this product. |

### Headline counts

| Status | Count | Share of 155 compared capabilities |
|---|---:|---:|
| Implemented | 45 | 29.0% |
| Partially Implemented | 25 | 16.1% |
| Missing | 83 | 53.5% |
| Needs Verification | 2 | 1.3% |
| Not Applicable | 0 | 0.0% |
| **Total compared** | **155** | **100%** |

**Why Not Applicable is zero:** Vyapar and this application target the same SMB trading/retail segment, so no Vyapar capability was judged inherently out of scope. Some features are lower value for this product (for example manufacturing/BOM), but they remain applicable and are therefore classified as Missing with a later phase.

---

## 1. Executive summary

The existing Item Management System is a **strong inventory-and-billing operations platform with an accounting gap**, while Vyapar is an **accounting-first business management product with a lighter multi-branch/operations model**. The two products overlap heavily in GST billing, item masters, purchases and stock, but they diverge sharply after the invoice is created: Vyapar continues into ledgers, receivables/payables, bank and cash, statutory returns and party communication; this application currently stops at the transaction report.

**What this application already does well (45 implemented capabilities)**

- End-to-end GST billing with per-line CGST/SGST/IGST, inclusive/exclusive tax, place-of-supply and immutable tax snapshots.
- Multi-branch inventory with branch-scoped stock, a five-stage transfer workflow (pending → approved → dispatched → received, plus reject/cancel), stock adjustments with reasons, weighted-average valuation and a full inventory transaction ledger.
- Hard negative-stock prevention with row-level locking (`findForUpdate`) so concurrent sales cannot oversell.
- Multi-tenant isolation by `store_id` and branch isolation by `branch_id`, enforced in services and by composite foreign keys.
- Granular role/permission model (30 permission codes: 26 base plus `TAX_*` and `PRINTER_*`), custom roles, branch assignment and action-level audit logs.
- Excel item import with automatic category creation, validation preview and opening-stock posting.
- Free silent thermal printing through a Windows bridge and an Android bridge, with pairing codes, hashed device tokens, duplicate-job suppression and print history.
- Operational reports (sales, purchases, stock movement, GST summary, profit, low stock, branch, store) with Excel/PDF/print export.
- Subscription tracking and server-enforced branch limits.

**What is missing that matters most**

1. **Customer master and receivables.** The `customers` table and entity exist, but there is no customer CRUD API/UI, no party ledger, no payment receipts and no receivable aging. Credit sales therefore create a `balance_amount` that cannot be collected, aged or reported in the app.
2. **Sales returns / credit notes and purchase returns / debit notes.** Once a sale or purchase is completed, the only correction is cancellation, which reverses the whole document. Vyapar supports partial returns.
3. **Financial accounting.** No chart of accounts, journal, cash book, bank book, expense, trial balance, P&L, balance sheet, cash flow or day book. The profit report is an operational revenue-minus-COGS calculation, not a double-entry P&L.
4. **GST filing outputs.** The GST report is a rate-wise summary. GSTR-1, GSTR-3B, GSTR-9, HSN summary, e-invoice and e-way bill are absent.
5. **Document workflow.** No quotation/estimate, proforma, sale order, purchase order, delivery challan or recurring invoice.
6. **Invoice-level essentials for Indian SMBs.** No HSN/SAC field, no barcode, no percentage discount, no round-off, no configurable invoice numbering, no WhatsApp/email sharing, no customer credit limit.
7. **Payment and collection tools.** No payment vouchers, reminders, UPI QR/payment links, advances or refunds.
8. **Mobile/offline depth.** The UI is responsive and there is an Android print bridge, but there is no full native app, no offline mode and no barcode-scanner workflow.

**Recommended direction**

Do not rebuild the operational core. Extend it in four phases:

- **Phase 1 (essential):** customer master, payment receipts and receivables, sales returns/credit notes, HSN/SAC, barcode, percentage discount, round-off, configurable numbering, invoice sharing, party statement, GSTIN validation, GSTR-1-ready data and HSN summary, forgot-password.
- **Phase 2 (accounting and inventory depth):** accounting core (accounts, journal, cash/bank, expenses, trial balance, P&L, balance sheet, cash flow, day book), supplier payments and payables, purchase returns, quotations/orders/challans, batch/expiry, GSTR-3B, RCM, e-way bill, payment reminders, dashboard receivables/payables.
- **Phase 3 (advanced):** e-invoice, GSTR-9, price lists, serial numbers, Tally, WhatsApp billing, payment links, native app, expiry/inventory aging, manufacturing/BOM deferral, advanced security (OTP/device lock), backup.
- **Phase 4 (optional/premium):** multi-currency, custom fields, item images, custom report builder, scheduled reports, 2FA, field-level permissions, edit approvals, online store, OCR, payment gateway, public API, full desktop app, online subscription billing.

**Critical caution:** Vyapar's accounting model cannot be bolted onto the current schema by adding a few columns. Phase 2 must introduce a small double-entry core (accounts, vouchers, voucher lines, party allocations) and then derive receivables, payables and financial statements from it. Until that exists, "profit" in this application should be described as **gross trading margin**, not accounting profit.

---

## 2. Overview of the existing application

### 2.1 Technology and architecture

| Layer | Technology | Notes |
|---|---|---|
| Frontend | React 19, TypeScript, Vite 8, Tailwind 4, React Router 7, Zustand, Axios | SPA served separately; API base `/api`; responsive layout. |
| Backend | Spring Boot 3.3.5, Java 17, Gradle 8.10.2 | 21 REST controllers; stateless JWT security. |
| Persistence | PostgreSQL (18.6 production target, 14.10 embedded dev), Hibernate `ddl-auto=validate` | Flyway migrations V1–V6; JPA entities validate against schema. |
| Multi-tenancy | Shared database, `store_id` on every business row; `branch_id` on operational rows | Composite FKs such as `(branch_id, store_id) → branches(id, store_id)` prevent cross-store references. |
| Auth | Spring Security + JWT, 12-hour expiry, refresh endpoint | Store, branch scope and permissions resolved from the token context. |
| Printing | V6 tables plus Windows (.NET) and Android (Kotlin) bridges | Bridges bind to `127.0.0.1`, use hashed device tokens and duplicate-job suppression. |

### 2.2 Functional modules present today

| Module | Frontend route | Backend surface | Core capability |
|---|---|---|---|
| Auth | `/login`, `/register` | `/api/auth/*` | Login, refresh, logout, me, store registration. |
| Dashboard | `/` | `/api/dashboard` | Counts, today's sales/purchases, low stock, branch summaries, recent documents, top items, stock value. |
| POS | `/pos` | `/api/items/pos`, `/api/sales` | Item search, cart, customer snapshot, discount, payment mode, auto-print. |
| Sales | `/sales/new`, `/sales` | `/api/sales` | Manual GST sale, list/detail, cancel with stock reversal. |
| Purchases | `/purchases` | `/api/purchases` | Draft/complete purchase with GST, edit draft, cancel with reversal. |
| Items | `/items` | `/api/items` | CRUD, status, POS feed, branch stock, per-item transactions. |
| Item import | `/items/imports` | `/api/item-imports` | Excel template, preview, confirm, history, detail. |
| Categories | `/categories` | `/api/categories` | CRUD, options, status. |
| Tax master | `/taxes` | `/api/taxes` | GST slabs with CGST/SGST/IGST split, status. |
| Suppliers | `/suppliers` | `/api/suppliers` | CRUD, status, purchase history. |
| Inventory | `/inventory`, `/low-stock` | `/api/inventory` | Branch rows, adjustments, transactions, branch pricing. |
| Transfers | `/transfers` | `/api/transfers` | Create, approve, dispatch, receive, reject, cancel. |
| Reports | `/reports` | `/api/reports` | Sales, purchases, stock, profit, GST, branch, store, low stock; export. |
| Print history | `/print-history` | `/api/printing/jobs` | Job list, status, reprint. |
| Settings | `/settings` | `/api/stores`, `/api/users`, `/api/roles`, `/api/branches`, `/api/printing` | Store, branches, users, roles, printers, paired bridges. |
| Platform | `/platform/stores` | `/api/platform`, `/api/stores` | Store onboarding and platform contact. |
| Subscription | Settings/store | `/api/subscription`, `/api/stores` | Expiry, branch allowance, history. |

### 2.3 Domain and database footprint

- **31 tables:** stores, branches, roles, permissions, role_permissions, users, user_roles, user_branches, categories, items, suppliers, customers, branch_inventory, inventory_transactions, stock_adjustments, purchases, purchase_details, sales, sales_details, stock_transfers, stock_transfer_details, audit_logs, tax_master, subscription_change_history, platform_settings, item_import_batches, item_import_rows, printer_configs, print_pairing_codes, print_devices, print_jobs.
- **`customers` exists in the schema but is not exposed:** `Customer.java` and `CustomerRepository.java` exist, yet no controller, service, page or route uses them. Sales store `customer_name`, `customer_mobile` and `customer_state` snapshots only.
- **No financial tables:** no accounts, ledgers, vouchers, journal lines, payments, expenses, bank accounts, loans, quotations, orders, challans, credit/debit notes, batches, serials, price lists, HSN master, or e-invoice/e-way-bill tables.

### 2.4 Role and permission model

Base permissions (V1): `DASHBOARD_VIEW`, `PLATFORM_MANAGE`, `STORE_VIEW/MANAGE`, `BRANCH_VIEW/MANAGE`, `USER_VIEW/MANAGE`, `ROLE_VIEW/MANAGE`, `CATEGORY_VIEW/MANAGE`, `ITEM_VIEW/MANAGE`, `SUPPLIER_VIEW/MANAGE`, `PURCHASE_VIEW/MANAGE`, `INVENTORY_VIEW/ADJUST`, `TRANSFER_VIEW/MANAGE/APPROVE`, `SALES_VIEW/MANAGE`, `REPORT_VIEW`.
V2 adds `TAX_VIEW/MANAGE`; V6 adds `PRINTER_VIEW/MANAGE`.
Default roles provisioned per store: `STORE_OWNER`, `STORE_ADMIN`, `BRANCH_MANAGER`, `BRANCH_STAFF`.

### 2.5 Workflows that define the product today

1. **Purchase:** create (draft or completed) → GST and discount allocation → stock-in and weighted-average cost → optional cancel with `PURCHASE_REVERSAL`.
2. **Sale:** POS or manual → unit conversion → GST → stock-out with availability check → optional cancel with `SALE_REVERSAL`.
3. **Transfer:** create (pending) → approve → dispatch (stock-out) → receive (stock-in at source average cost) → reject/cancel in allowed states.
4. **Adjustment:** in/out with mandatory reason → ledger entry → audit log.
5. **Import:** template → preview with row-level validation → confirm (creates categories, items, opening stock in one or more branches) → history.
6. **Printing:** sale/purchase/report document → print job → bridge claims job → local silent print → status `PRINTED`/`FAILED` → reprint as a new job.

These workflows are the product's differentiators and should be preserved (Section 9.3).

---

## 3. Vyapar feature catalogue

### 3.1 Sources used

| Source | URL | What it confirmed |
|---|---|---|
| Vyapar home | https://vyaparapp.in/ | GST billing, inventory, accounting, reports, multi-device, invoicing, payment reminders. |
| Vyapar features blog | https://vyaparapp.in/blog/vyapar-app-features/ | Detailed feature catalogue: billing documents, inventory, accounting, GST, reports, sharing, security. |
| Small business accounting page | https://vyaparapp.in/free/small-business-accounting-software | Accounting scope: ledgers, cash/bank, expenses, trial balance, P&L, balance sheet, day book. |
| Google Play listing | https://play.google.com/store/apps/details?id=in.android.vyapar | Android app existence and mobile capabilities. The page is JavaScript-rendered, so capability details were cross-checked on the official site. |

### 3.2 Verified Vyapar capability groups

| Group | Capabilities described by Vyapar |
|---|---|
| Billing and invoicing | GST invoices, estimates/quotations, proforma invoices, sale orders, purchase orders, delivery challans, credit notes, debit notes, recurring invoices, custom invoice formats, round-off, multiple payment modes. |
| Inventory | Item master with HSN and barcode, batch and expiry tracking, serial numbers, stock alerts, godowns/warehouses, stock transfers, price lists, manufacturing/BOM, physical stock count, inventory reports. |
| Accounting | Ledgers and chart of accounts, journal entries, contra entries, cash book, bank book, bank reconciliation, cheque management, expenses, other income, loans, fixed assets, trial balance, profit and loss, balance sheet, cash flow, day book, party statements, receivables/payables aging, bill-wise profit. |
| GST and compliance | GST rate setup, GSTR-1, GSTR-3B, GSTR-9, HSN summary, e-invoice, e-way bill, reverse charge, TDS/TCS, audit trail. |
| Payments and receivables | Payment-in and payment-out, partial payments, payment reminders via WhatsApp/SMS, UPI QR and payment links, customer advances, refunds. |
| Reports | 50+ reports across sales, purchases, inventory, accounting, taxation, party and profitability, with export. |
| Users and security | Multi-user access, role-based permissions, OTP/passcode/device lock, auto backup, multi-device sync, offline operation. |
| Mobile and integrations | Android and iOS apps, desktop app, barcode scanning, WhatsApp billing and sharing, OCR bill capture, Tally transfer, online store, multi-currency. |

### 3.3 Vendor-claimed or plan-dependent capabilities

These are advertised by Vyapar but their exact availability depends on plan, platform (mobile vs. desktop) or country. They are treated as **Vyapar features** in the comparison, but any implementation decision should re-verify the current plan matrix.

- Online store / e-commerce storefront.
- Multi-currency.
- Manufacturing/BOM.
- OCR bill capture.
- Tally data transfer.
- e-invoice and e-way bill generation (also dependent on GSTN/IRP onboarding and turnover thresholds).
- WhatsApp/SMS reminder delivery (dependent on messaging partner and plan).
- Payment links/UPI QR (dependent on payment partner).
- Auto backup and offline mode (mobile app behaviour).

### 3.4 Limitations of this research

- Vyapar feature names and packaging change frequently; this catalogue reflects the official pages as read during this analysis, not an in-product audit.
- Vyapar's multi-branch/godown depth, API access and on-premise options were not verified in detail.
- No Vyapar trial account was used. Statements such as "Vyapar supports X" mean "Vyapar advertises X", not "X was exercised in a live account".
- Conversely, every "Implemented", "Partially Implemented" or "Missing" statement about this repository is based on source inspection, not on marketing material.

---

## 4. Detailed module-by-module comparison

### 4.1 Comparison matrix

Statuses and priorities use the definitions in "How to read this document". Priority `—` means the capability is already implemented or is only a verification item.

| Module | Vyapar Feature | Existing Implementation | Status | Missing Functionality | Priority |
|---|---|---|---|---|---|
| Company & Branch | Multiple firms/companies in one app with a company switcher | `stores` table, platform admin store management, store-bound JWT context; no in-app firm switch | Partially Implemented | Store membership per user, company switcher, copying masters between firms | Phase 3 |
| Company & Branch | Multiple branches / godowns | Branch CRUD, branch-scoped inventory, branch users | Implemented | — | — |
| Company & Branch | Branch-level stock and inter-branch movement | Per-branch inventory plus transfer state machine | Implemented | — | — |
| Company & Branch | Company profile, GSTIN, tax-inclusive pricing | Store profile fields and `taxInclusive` flag | Implemented | — | — |
| Company & Branch | Subscription/expiry and branch allowance | Subscription dates, change history, server-enforced branch limits | Implemented | — | — |
| Company & Branch | Platform/admin console | Platform store onboarding, status, subscription views | Implemented | — | — |
| Items & Catalog | Item master with code, category, tax, unit, rate, minimum stock | Full item CRUD and branch pricing | Implemented | — | — |
| Items & Catalog | Item categories | Category CRUD, options, status | Implemented | — | — |
| Items & Catalog | Multiple units and unit conversion | Nine base units and conversion to the item base unit | Partially Implemented | Custom units, compound/secondary units (e.g. box = 12 pcs) | Phase 2 |
| Items & Catalog | HSN/SAC codes on items and invoices | No HSN/SAC field anywhere | Missing | HSN/SAC master or field, invoice snapshot, HSN summary | Phase 1 |
| Items & Catalog | Barcode generation and scanning | Item code search only; no barcode field or scanner flow | Missing | Barcode field, label generation, scanner input in POS/purchase | Phase 1 |
| Items & Catalog | Item images | No image field or upload | Missing | Image storage, thumbnails, POS display | Phase 4 |
| Items & Catalog | Batch/lot tracking with manufacturing and expiry dates | No batch entity, no expiry fields | Missing | Batch master, batch-level stock, FEFO issue, expiry alerts | Phase 2 |
| Items & Catalog | Serial number tracking | No serial entity or flow | Missing | Serial registry, capture on purchase/sale, warranty history | Phase 3 |
| Items & Catalog | Price lists and party-specific pricing | Item rate and per-branch purchase/selling price only | Partially Implemented | Price list entity, customer assignment, rate resolution | Phase 3 |
| Items & Catalog | Opening stock | Excel import posts opening stock; adjustments can add stock | Implemented | — | — |
| Items & Catalog | Bulk item import with automatic category creation and opening stock | V5 import batches, preview, confirm, history | Implemented | — | — |
| Items & Catalog | Item-wise stock across branches | Item stock endpoint and inventory rows per branch | Implemented | — | — |
| Items & Catalog | Custom item fields | Fixed item schema only | Missing | Configurable fields and validation | Phase 4 |
| Sales & Billing | GST sales invoice with CGST/SGST/IGST | Full sale calculation with tax snapshots | Implemented | — | — |
| Sales & Billing | POS quick billing | POS page with search, cart, payment mode, auto-print | Implemented | — | — |
| Sales & Billing | Manual sales entry | Dedicated sales entry page and API | Implemented | — | — |
| Sales & Billing | Automatic invoice numbering | Database sequences (`SAL-1001`, `PUR-1001`, etc.) | Implemented | — | — |
| Sales & Billing | Configurable invoice numbering/prefix/series | Fixed prefixes and sequences | Missing | Prefix, suffix, financial-year reset, per-branch series | Phase 1 |
| Sales & Billing | Discount by percentage or amount | Backend applies monetary discount amounts | Partially Implemented | Percentage entry, per-line/bill percentage distribution | Phase 1 |
| Sales & Billing | Bill round-off | Line and total amounts are rounded to paise; no round-off adjustment line | Missing | Round-off setting and posted adjustment | Phase 1 |
| Sales & Billing | Split/multiple payment modes on one invoice | One payment method per sale | Missing | Payment allocation lines and validation | Phase 2 |
| Sales & Billing | Partial payment and balance tracking | `paid_amount` and `balance_amount` stored; credit mode supported | Partially Implemented | Receipt application, payment history, status lifecycle | Phase 1 |
| Sales & Billing | Payment receipt/collection against invoices | No receipt document or API | Missing | Receipt voucher, allocation to invoices, customer ledger | Phase 1 |
| Sales & Billing | Customer master with GSTIN, address, opening balance, credit limit | `customers` table and entity/repo only; sales store name/mobile/state snapshots | Partially Implemented | Customer CRUD API/UI, extra fields, opening balance, credit limit | Phase 1 |
| Sales & Billing | Customer credit limit and outstanding block | No credit check | Missing | Limit, exposure calculation, override permission | Phase 2 |
| Sales & Billing | Credit sale tracking and outstanding list | Balance stored; no receivable view | Partially Implemented | Outstanding list, aging, party statement | Phase 1 |
| Sales & Billing | Quotation/estimate | No document type | Missing | Quotation entity, convert-to-invoice, status | Phase 2 |
| Sales & Billing | Proforma invoice | No document type | Missing | Proforma entity and conversion | Phase 2 |
| Sales & Billing | Sale order | No document type | Missing | Order entity, fulfilment tracking | Phase 2 |
| Sales & Billing | Delivery challan | No document type | Missing | Challan entity, dispatch/receive, invoice conversion | Phase 2 |
| Sales & Billing | Sales return / credit note | Only whole-sale cancellation with stock reversal | Missing | Partial return, credit note, stock and GST reversal | Phase 1 |
| Sales & Billing | Recurring invoices | None | Missing | Schedule, template, generation job | Phase 3 |
| Sales & Billing | Hold/park bill | None | Missing | Parked cart persistence and recall | Phase 3 |
| Sales & Billing | Invoice branding, logo, signature, terms, templates | Fixed HTML/thermal layout with store name and GSTIN | Partially Implemented | Logo upload, terms, signature, A4/A5 templates | Phase 2 |
| Sales & Billing | Share invoice via WhatsApp/email/SMS | Browser print only | Missing | Share links, PDF generation, messaging integration | Phase 1 |
| Sales & Billing | Sale cancellation with stock reversal | Cancel endpoint reverses stock and audits | Implemented | — | — |
| Sales & Billing | Salesman/agent attribution | `created_by` only | Partially Implemented | Salesman master, invoice attribution, commission | Phase 3 |
| Purchases & Suppliers | Supplier master | CRUD, status, GSTIN, email, purchase history | Implemented | — | — |
| Purchases & Suppliers | Purchase invoice with GST | Full purchase calculation and snapshots | Implemented | — | — |
| Purchases & Suppliers | Draft-to-complete purchase | Draft create/edit/complete | Implemented | — | — |
| Purchases & Suppliers | Purchase order | None | Missing | PO entity, approval, goods receipt linkage | Phase 2 |
| Purchases & Suppliers | Purchase return / debit note | Only whole-purchase cancellation | Missing | Partial return, debit note, stock/GST reversal | Phase 2 |
| Purchases & Suppliers | Supplier payments, advances and adjustments | `paid_amount` on purchase; supplier purchase history | Partially Implemented | Payment-out voucher, advance, allocation, supplier ledger | Phase 2 |
| Purchases & Suppliers | Landed cost / expense allocation | Purchase rate only | Missing | Additional cost heads and allocation to item cost | Phase 3 |
| Purchases & Suppliers | Purchase bill attachment | None | Missing | Attachment storage and preview | Phase 3 |
| Purchases & Suppliers | Supplier ledger/statement | Purchase history only, no ledger or balance | Missing | Running balance, statement export | Phase 2 |
| Purchases & Suppliers | Purchase price history | Average cost and last purchase price stored | Partially Implemented | Dedicated per-supplier price history report | Phase 3 |
| Inventory | Branch stock ledger | Inventory transactions with running balance | Implemented | — | — |
| Inventory | Stock adjustment with reason | Adjustment in/out, reason, audit | Implemented | — | — |
| Inventory | Stock transfer approval workflow | Pending/approved/dispatched/received/rejected/cancelled | Implemented | — | — |
| Inventory | Low stock alerts and notifications | Low-stock list, report, dashboard count | Partially Implemented | Push/email alerts, reorder quantity, subscription to alerts | Phase 2 |
| Inventory | Reorder/replenishment suggestions | None | Missing | Suggested order quantity and supplier/PO generation | Phase 2 |
| Inventory | Weighted-average stock valuation | `avg_cost` updated on stock-in | Implemented | — | — |
| Inventory | Negative stock prevention | Availability check plus row lock | Implemented | — | — |
| Inventory | Physical stock count/audit | Manual adjustments only | Partially Implemented | Count sheet, variance calculation, approval, posting | Phase 2 |
| Inventory | Expiry alerts | No batch/expiry data | Missing | Expiry bucket report and alerts (depends on batch) | Phase 2 |
| Inventory | Inventory aging | None | Missing | Age buckets by received date and movement | Phase 3 |
| Inventory | Warehouse/bin sub-locations | Branch is the only location level | Missing | Warehouse and bin levels under a branch | Phase 3 |
| Inventory | Manufacturing/BOM | None | Missing | BOM, production order, component issue, finished goods receipt | Phase 4 |
| Inventory | Stock valuation report | Stock report includes current value | Implemented | — | — |
| Accounting & Finance | Chart of accounts/ledgers | None | Missing | Account groups, ledgers, opening balances | Phase 2 |
| Accounting & Finance | Journal vouchers | None | Missing | Double-entry voucher and lines | Phase 2 |
| Accounting & Finance | Contra entries (cash/bank transfers) | None | Missing | Contra voucher | Phase 2 |
| Accounting & Finance | Cash book | None | Missing | Cash ledger and daily book | Phase 2 |
| Accounting & Finance | Bank book and reconciliation | None | Missing | Bank accounts, statement import, reconciliation | Phase 3 |
| Accounting & Finance | Expense tracking | Purchase and adjustment costs only | Missing | Expense heads, vouchers, payment mode | Phase 2 |
| Accounting & Finance | Other income | None | Missing | Income heads and vouchers | Phase 2 |
| Accounting & Finance | Loans and liabilities | None | Missing | Loan accounts, EMI schedules, interest posting | Phase 3 |
| Accounting & Finance | Cheque management | None | Missing | Cheque register, status, post-dated handling | Phase 3 |
| Accounting & Finance | Fixed assets | None | Missing | Asset register, depreciation | Phase 4 |
| Accounting & Finance | Trial balance | None | Missing | Ledger balances and drill-down | Phase 2 |
| Accounting & Finance | Profit and loss statement | Operational profit report by item/branch | Missing | Accounting P&L with expense/income heads | Phase 2 |
| Accounting & Finance | Balance sheet | None | Missing | Assets/liabilities/equity statement | Phase 2 |
| Accounting & Finance | Cash flow statement | None | Missing | Operating/investing/financing cash flow | Phase 2 |
| Accounting & Finance | Day book | None | Missing | Chronological all-voucher book | Phase 2 |
| Accounting & Finance | Party financial ledger | Supplier purchase history only; no customer ledger | Missing | Running-balance party ledger | Phase 2 |
| Accounting & Finance | Receivables aging | None | Missing | 0-30/31-60/61-90/90+ buckets | Phase 2 |
| Accounting & Finance | Payables aging | None | Missing | Supplier aging buckets | Phase 2 |
| Accounting & Finance | Bill-wise profit | Profit report by item and branch only | Partially Implemented | Per-invoice revenue, cost and margin | Phase 3 |
| Accounting & Finance | Multi-currency | Single currency (INR implied) | Missing | Currency master, exchange rates, revaluation | Phase 4 |
| Accounting & Finance | TDS/TCS | None | Missing | Sections, thresholds, deduction and reports | Phase 3 |
| Accounting & Finance | Field-level audit/edit history | Action-level `audit_logs` | Partially Implemented | Field-level before/after values | Phase 4 |
| GST & Compliance | Tax master with CGST/SGST/IGST split | V2 tax master with snapshots | Implemented | — | — |
| GST & Compliance | Inclusive/exclusive tax and inter-state supply | GstCalculator and store flag | Implemented | — | — |
| GST & Compliance | GST summary report | Aggregated by branch, tax name and rate | Implemented | — | — |
| GST & Compliance | GSTIN validation | GSTIN stored as free text | Partially Implemented | Format and checksum validation, auto-fill | Phase 1 |
| GST & Compliance | GSTR-1 | None | Missing | Outward supply data and export | Phase 1 |
| GST & Compliance | GSTR-3B | None | Missing | Summary return data | Phase 2 |
| GST & Compliance | GSTR-9 | None | Missing | Annual return data | Phase 3 |
| GST & Compliance | HSN summary | No HSN data | Missing | HSN-wise quantity, taxable value and tax | Phase 1 |
| GST & Compliance | e-Invoice | None | Missing | IRP integration, IRN/QR, cancellation | Phase 3 |
| GST & Compliance | e-Way bill | None | Missing | EWB generation, vehicle/transporter data | Phase 2 |
| GST & Compliance | Reverse charge mechanism | None | Missing | RCM flag, self-invoice, tax payable tracking | Phase 2 |
| Payments & Receivables | Payment-in/payment-out vouchers | None | Missing | Payment vouchers and allocations | Phase 2 |
| Payments & Receivables | Payment reminders | None | Missing | Reminder templates, schedules, delivery log | Phase 2 |
| Payments & Receivables | UPI QR/payment link | None | Missing | QR generation and reconciliation | Phase 3 |
| Payments & Receivables | Customer advances | None | Missing | Advance receipt and adjustment | Phase 2 |
| Payments & Receivables | Refunds | None | Missing | Refund voucher and payment mode | Phase 2 |
| Payments & Receivables | Configurable payment mode master | Fixed enum (CASH/UPI/CARD/CREDIT) | Partially Implemented | Custom modes and mapping to accounts | Phase 2 |
| Reports & Analytics | Sales report | Item-level sales report with filters and export | Implemented | — | — |
| Reports & Analytics | Purchase report | Item-level purchase report with filters and export | Implemented | — | — |
| Reports & Analytics | Profit report | Revenue minus COGS by item and branch | Partially Implemented | Invoice-wise profit, expense-inclusive profit | Phase 2 |
| Reports & Analytics | GST report | Rate-wise GST summary | Implemented | — | — |
| Reports & Analytics | Stock movement report | Opening, in/out movements and closing | Implemented | — | — |
| Reports & Analytics | Low stock report | Low-stock list with filters and export | Implemented | — | — |
| Reports & Analytics | Branch performance report | Sales, purchases, stock, low stock by branch | Implemented | — | — |
| Reports & Analytics | Store summary | Consolidated store totals | Implemented | — | — |
| Reports & Analytics | Dashboard KPIs | Today's sales/purchases, counts, top items, stock | Partially Implemented | Receivables, payables, cash/bank, collections | Phase 2 |
| Reports & Analytics | Excel/PDF/print export | ReportExport with xlsx, jsPDF and print | Implemented | — | — |
| Reports & Analytics | Party statement | None | Missing | Customer/supplier statement with running balance | Phase 1 |
| Reports & Analytics | Sales/purchase by party | Supplier purchase history only | Partially Implemented | Customer-wise sales, party-wise purchase report | Phase 1 |
| Reports & Analytics | Category-wise summary | Category filter on rows; no aggregate | Partially Implemented | Category totals and margins | Phase 2 |
| Reports & Analytics | Expense report | None | Missing | Expense by head, period, branch | Phase 2 |
| Reports & Analytics | Cash/bank flow report | None | Missing | Inflow/outflow by account | Phase 2 |
| Reports & Analytics | Custom report builder | None | Missing | Field/grouping/filter builder | Phase 4 |
| Reports & Analytics | Scheduled reports | None | Missing | Schedule and delivery | Phase 4 |
| Users & Security | Multi-user accounts | User CRUD with branch assignment | Implemented | — | — |
| Users & Security | Custom roles and permissions | Role/permission editor, 30 permission codes | Implemented | — | — |
| Users & Security | Branch-scoped access | Service-level branch checks | Implemented | — | — |
| Users & Security | Audit logs | Action-level audit log written by services (no viewer UI) | Implemented | — | — |
| Users & Security | Password hashing and JWT | BCrypt, JWT, refresh/logout | Implemented | — | — |
| Users & Security | Session refresh/logout | Refresh and logout endpoints | Implemented | — | — |
| Users & Security | Forgot password/email reset | None | Missing | Reset token, email delivery, expiry | Phase 1 |
| Users & Security | OTP/passcode/device lock | JWT login only | Missing | OTP login, app passcode, device binding | Phase 3 |
| Users & Security | Two-factor authentication | None | Missing | TOTP/email OTP and recovery codes | Phase 4 |
| Users & Security | Login lockout/rate limiting | Not found in application code; may be at the proxy | Needs Verification | Brute-force protection, account/IP throttling | Phase 1 |
| Users & Security | In-app backup/restore | Operational DB backups only | Missing | Backup scheduling, download, restore workflow | Phase 3 |
| Users & Security | Full data export | Report exports only | Missing | Store-level data export package | Phase 2 |
| Users & Security | Field-level permissions | Permission-level only | Missing | Field/action-level restrictions | Phase 4 |
| Users & Security | Edit approval workflow | Direct edits with audit | Missing | Maker-checker approval for sensitive edits | Phase 4 |
| Mobile & Integrations | Responsive web application | Tailwind responsive layout across pages | Implemented | — | — |
| Mobile & Integrations | Native Android application | Android print bridge APK only | Partially Implemented | Full native app with offline, camera, sharing | Phase 3 |
| Mobile & Integrations | Windows desktop application | Windows print bridge only | Partially Implemented | Full desktop client | Phase 4 |
| Mobile & Integrations | Offline mode | Server-dependent SPA | Missing | Local cache, queue, conflict resolution | Phase 4 |
| Mobile & Integrations | Real-time multi-device sync | Server-authoritative REST; no push channel found | Needs Verification | Confirm refresh/polling behaviour and multi-tab consistency | Phase 2 |
| Mobile & Integrations | Silent thermal printing (Windows/Android) | V6 printing stack, bridges, job dedupe | Implemented | — | — |
| Mobile & Integrations | Online store/e-commerce | None | Missing | Storefront, catalog sync, order intake | Phase 4 |
| Mobile & Integrations | WhatsApp billing/share | None | Missing | Share templates and messaging API | Phase 3 |
| Mobile & Integrations | OCR bill capture | None | Missing | Image capture, OCR, draft purchase | Phase 4 |
| Mobile & Integrations | Tally integration | None | Missing | Export/sync to Tally | Phase 3 |
| Mobile & Integrations | Hardware barcode scanner | None | Missing | Scanner input handling, label formats | Phase 3 |
| Mobile & Integrations | Bulk data import/export | Item import plus report exports | Partially Implemented | Customer/supplier import, transaction import, full export | Phase 2 |
| Mobile & Integrations | Email/SMS notifications | None | Missing | Transactional email/SMS service | Phase 3 |
| Mobile & Integrations | Payment gateway integration | None | Missing | Gateway, callback, reconciliation | Phase 4 |
| Mobile & Integrations | Public API/webhooks | None | Missing | API keys, scopes, webhook events | Phase 4 |
| Subscription & Platform | Subscription tracking | Dates, history, expiry screen | Implemented | — | — |
| Subscription & Platform | Branch limit enforcement | Server-side limit enforcement | Implemented | — | — |
| Subscription & Platform | Online subscription billing | Manual/platform-managed dates only | Missing | Plans, invoices, online payment | Phase 4 |
| Subscription & Platform | Plan/feature gating | Only branch-count gating | Partially Implemented | Feature flags per plan | Phase 2 |

### 4.2 Module observations

- **Sales & Billing (24 capabilities, 5 implemented)** is the widest gap. The existing invoice is technically solid but stops at creation: no customer identity, no returns, no collection, no document chain.
- **Accounting & Finance (22 capabilities, 0 implemented)** is the largest absolute gap. This is a product-scope decision, not a bug: the current system was designed as an operational inventory system.
- **GST & Compliance (11 capabilities, 3 implemented)** has correct tax maths but no filing outputs.
- **Inventory (13 capabilities, 6 implemented)** is the strongest module. Missing items are mostly depth (batch, expiry, bins, replenishment), not core correctness.
- **Users & Security (14 capabilities, 6 implemented)** is stronger than Vyapar on branch isolation and custom permissions, weaker on account recovery and device security.
- **Mobile & Integrations (15 capabilities)** shows the product is a responsive web app with excellent printing, not a mobile-first platform.
- **Company & Branch (6 capabilities, 5 implemented)** is close to complete; only multi-firm switching is absent.

## 5. Implemented features

All 45 capabilities below were confirmed in source. "Evidence" points to the concrete implementation surface; route paths are relative to `/api`.

| ID | Module | Capability | Evidence in repository | Why it is a strength |
|---|---|---|---|---|
| CS-02 | Company & Branch | Multiple branches | `BranchController`, `BranchService`, `branches` table, branch-scoped rows | Real multi-branch operation, not just a label. |
| CS-03 | Company & Branch | Branch-level stock and movement | `branch_inventory`, `TransferService`, transfer ledger entries | Stock is isolated per branch and moves through an auditable workflow. |
| CS-04 | Company & Branch | Store profile and tax-inclusive pricing | `StoreController`, `StoreService`, `Store.taxInclusive`, `GstCalculator` | Store-level tax policy is applied consistently to all documents. |
| CS-05 | Company & Branch | Subscription and expiry tracking | V4 schema, `SubscriptionService`, subscription settings UI | Commercial lifecycle is controlled server-side. |
| CS-06 | Company & Branch | Platform admin console | `PlatformController`, `PlatformStoresPage`, `PLATFORM_MANAGE` | Store onboarding and monitoring are built in. |
| IT-01 | Items & Catalog | Item master | `ItemService`, `ItemsPage`, `/items` | Code, category, tax, unit, rate and minimum stock are validated. |
| IT-02 | Items & Catalog | Categories | `CategoryService`, `CategoriesPage`, `/categories` | Categories are store-scoped with status and usage rules. |
| IT-10 | Items & Catalog | Opening stock | `ItemImportService.applyOpeningStock`, `OPENING_STOCK` ledger type | Opening balances are posted through the inventory ledger, not edited directly. |
| IT-11 | Items & Catalog | Excel item import | V5 tables, `ItemImportExcelService`, `ItemImportService`, `/item-imports`, `ItemImportsPage` | Preview-first import with automatic category creation and branch opening stock. |
| IT-12 | Items & Catalog | Item stock across branches | `ItemService.stock`, `BranchStockResponse`, `/items/{id}/stock` | One item can be inspected across every accessible branch. |
| SL-01 | Sales & Billing | GST sales invoice | `SaleService`, `GstCalculator`, `sales_details` snapshots | Tax is calculated once and snapshotted for historical accuracy. |
| SL-02 | Sales & Billing | POS quick billing | `PosPage`, `/items/pos`, `/sales`, printer service | Fast counter billing with automatic receipt printing. |
| SL-03 | Sales & Billing | Manual sales entry | `SalesEntryPage`, `/sales` | Full invoice entry for non-POS orders. |
| SL-04 | Sales & Billing | Automatic numbering | `NumberGenerator`, database sequences | Gap-free issuance under concurrency. |
| SL-23 | Sales & Billing | Sale cancellation with stock reversal | `SaleService.cancel`, `SALE_REVERSAL` | Corrections restore stock and preserve an audit trail. |
| PU-01 | Purchases & Suppliers | Supplier master | `SupplierService`, `SuppliersPage`, `/suppliers` | GSTIN, state, contact and purchase history in one place. |
| PU-02 | Purchases & Suppliers | Purchase invoice with GST | `PurchaseService`, `purchase_details` snapshots | Supplier invoices follow the same tax rules as sales. |
| PU-03 | Purchases & Suppliers | Draft-to-complete purchase | `PurchaseService.create/updateDraft/complete` | Stock is posted only when the purchase is completed. |
| IV-01 | Inventory | Branch stock ledger | `InventoryService.transactions`, `inventory_transactions` | Every movement is recorded with a running balance. |
| IV-02 | Inventory | Stock adjustment with reason | `InventoryService.adjust`, `StockAdjustment`, `/inventory/adjustments` | Controlled corrections instead of direct stock edits. |
| IV-03 | Inventory | Stock transfer workflow | `TransferService`, `stock_transfers`, `/transfers` | Five-stage state machine with reject/cancel guards. |
| IV-06 | Inventory | Weighted-average valuation | `InventoryService.applyStockIn` | Cost updates on every receipt; used for sale COGS. |
| IV-07 | Inventory | Negative stock prevention | `applyStockOut` check plus `findForUpdate` row lock | Concurrent sales cannot oversell. |
| IV-13 | Inventory | Stock valuation report | `ReportService.stockReport` closing stock times average cost | Inventory value is visible per branch and item. |
| GS-01 | GST & Compliance | Tax master with CGST/SGST/IGST split | V2 migration, `TaxMasterService`, `TaxMasterPage` | Rates are centrally managed and snapshotted on documents. |
| GS-02 | GST & Compliance | Inclusive/exclusive tax and inter-state supply | `GstCalculator`, store tax-inclusive flag, state comparison | Correct CGST/SGST versus IGST selection. |
| GS-03 | GST & Compliance | GST summary report | `ReportService.gstReport`, `GstReportView` | Rate-wise taxable value and tax totals by branch. |
| RP-01 | Reports & Analytics | Sales report | `ReportService.salesReport`, `SalesReportView` | Item-level detail, filters and recomputed totals. |
| RP-02 | Reports & Analytics | Purchase report | `ReportService.purchaseReport`, `PurchaseReportView` | Supplier, branch, item and tax detail. |
| RP-04 | Reports & Analytics | GST report | `ReportService.gstReport`, `GstReportView` | Branch/tax/rate aggregation with tax splits. |
| RP-05 | Reports & Analytics | Stock movement report | `ReportService.stockReport`, `StockReportView` | Opening, inward, outward and closing per item/branch. |
| RP-06 | Reports & Analytics | Low stock report | `ReportService.lowStockReport`, `LowStockReportView` | Actionable list with filters. |
| RP-07 | Reports & Analytics | Branch performance report | `ReportService.branchReport`, `BranchReportView` | Comparative branch totals. |
| RP-08 | Reports & Analytics | Store summary | `ReportService.storeReport`, `StoreReportView` | Consolidated operational totals. |
| RP-10 | Reports & Analytics | Excel/PDF/print export | `ReportExport.tsx`, `lib/export.ts` | Every report can leave the app in a usable format. |
| SC-01 | Users & Security | Multi-user accounts | `UserService`, `UsersSettings`, `/users` | Store users with branch assignment. |
| SC-02 | Users & Security | Custom roles and permissions | `RoleController`, `RolesSettings`, `role_permissions` | Fine-grained access, editable per store. |
| SC-03 | Users & Security | Branch-scoped access | `TenantContext.requireBranch`, `AuthUser.canAccessBranch` | Branch users cannot see other branches. |
| SC-04 | Users & Security | Audit logs | `AuditService`, `audit_logs` | Create/update/cancel/approve actions are attributable. |
| SC-05 | Users & Security | Password hashing and JWT | `SecurityConfig`, BCrypt, JWT filter | Standard stateless authentication. |
| SC-06 | Users & Security | Session refresh/logout | `AuthService.refresh/logout` | Tokens can be renewed and invalidated. |
| IN-01 | Mobile & Integrations | Responsive web application | Tailwind layouts, responsive navigation | Usable on desktop, tablet and phone browsers. |
| IN-06 | Mobile & Integrations | Silent thermal printing | V6 schema, printer services, Windows and Android bridges | A genuine differentiator: local silent printing without paid middleware. |
| SB-01 | Subscription & Platform | Subscription tracking | V4 schema, `SubscriptionService`, history table | Renewals and expiry are recorded. |
| SB-02 | Subscription & Platform | Branch limit enforcement | `SubscriptionService`, `BranchService` | Limits are enforced server-side, not just hidden in the UI. |

### 5.1 Implemented capabilities that are better than Vyapar's equivalent

These are discussed in detail in Section 9.1. In short: store-level tenant isolation, branch-level permissions, the transfer state machine, hard negative-stock prevention, tax snapshots, preview-first Excel import, and free silent printing are all areas where this application is equal to or stronger than the Vyapar feature set reviewed.

---

## 6. Partially implemented features (25)

Each row documents what Vyapar does, the benefit for this application, the precise missing part, the existing implementation surface, whether schema work is likely, dependencies, complexity and the phase with its rationale. "DB change" means a schema change would probably be required; it does not authorise one.

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| CS-01 | Multiple firms/companies | Keeps several businesses in one account and switches between them | Lets one owner run multiple stores without separate logins | User-to-many-stores membership, company switcher, master-data copy | `/api/stores`, auth store context, no switcher UI | Yes | Auth/TenantContext, per-store roles | High | Phase 3 — only matters once multi-business owners are targeted |
| IT-03 | Units and conversion | Custom units and compound units such as box = 12 pcs | Accurate purchase/sale in packs while stocking in base units | Custom unit master, secondary unit, compound conversion | `Unit` enum, `UnitConverter`, item base unit | Yes | Stock maths, imports, reports | Medium | Phase 2 — needed for multi-pack purchasing, not blocking GST billing |
| IT-09 | Price lists / party pricing | Wholesale, retail and party-specific rate lists | Segment pricing without editing item rates | Price-list entity, customer mapping, rate resolution in POS | Item rate, branch purchase/selling price | Yes | Customer master (SL-11) | Medium | Phase 3 — requires customer master and a pricing policy first |
| SL-06 | Percentage discount | Discount by percent or amount at line and bill level | Faster, less error-prone discount entry | Percentage input and derivation to amount | `SaleService`/`PurchaseService` treat discount as money; POS discount state is an amount | No | None | Low | Phase 1 — used on most retail bills and cheap to add |
| SL-09 | Partial payment and balance | Part payment with a tracked balance and later receipts | Makes credit sales collectable | Receipt application, payment history, status lifecycle | `paid_amount`, `balance_amount`, CREDIT mode | Yes | Customer master, payment receipts | Medium | Phase 1 — credit sales today create an uncollectable balance |
| SL-11 | Customer master | Full customer records with GSTIN, address, opening balance and credit limit | Repeat customers, correct place of supply, statements and credit control | CRUD API/UI, extra fields, opening balance, credit limit | `customers` table, `Customer` entity/repository; sale stores name/mobile/state snapshots | Yes | None | Medium | Phase 1 — prerequisite for receipts, aging, credit limits and statements |
| SL-13 | Credit sale tracking | Outstanding and partly paid invoices by customer | Visibility of money owed | Outstanding list, aging, party statement | `balance_amount`, CREDIT payment method | Maybe | SL-11, SL-10 | Medium | Phase 1 — direct revenue collection; depends on customer master |
| SL-21 | Invoice branding | Logo, signature, terms and multiple invoice templates | Professional, compliant-looking invoices | Logo upload, terms, signature, A4/A5 templates | `lib/documents.ts` fixed HTML/thermal layout; print jobs | Yes | Store profile, printing | Medium | Phase 2 — matters after compliance basics are in place |
| SL-24 | Salesman attribution | Salesman/agent on invoices and performance tracking (vendor-claimed) | Sales performance and commission | Salesman master, invoice attribution, commission | `created_by` on sales | Yes | User master | Low | Phase 3 — commission tracking is an advanced sales-management need |
| PU-06 | Supplier payments and advances | Payment-out vouchers, advances and adjustments against bills | Accurate payables and supplier balances | Payment-out entity, allocation, supplier ledger | Purchase `paid_amount`/`balance_amount`, `SupplierService.purchaseHistory` | Yes | Supplier ledger, accounting core | High | Phase 2 — payables cannot be managed without payment-out |
| PU-10 | Purchase price history | Last purchase rate and price trend per supplier/item | Better negotiation and costing | Dedicated price-history query/report | `avg_cost`, `purchase_price`, purchase details | No | None | Low | Phase 3 — useful for negotiation, not blocking operations |
| IV-04 | Low stock alerts | Reorder alerts and notifications | Prevents stockouts without checking reports | Push/email alerts, reorder quantity, alert subscription | Low-stock list, dashboard count, low-stock report | Maybe | Notification infrastructure | Low | Phase 2 — stockouts cost sales, but current list already gives visibility |
| IV-08 | Physical stock count | Count sheets with variance and posting | Improves stock accuracy | Count session, expected vs counted, variance approval, posting | Adjustments with reason and ledger | Yes | Inventory ledger | Medium | Phase 2 — accuracy improvement; adjustments remain a fallback |
| AC-19 | Bill-wise profit | Profit per invoice | Knows which bills actually made money | Per-document revenue, cost and margin | `ReportService` profit by item/branch; `cost_rate` stored | No | Accounting/expense data for full profit | Low | Phase 3 — invoice margin becomes more valuable once accounting exists |
| AC-22 | Field-level edit history | Detailed change history for compliance | Accountability for sensitive edits | Before/after values per field | `audit_logs` action-level entries | Yes | Audit service | Medium | Phase 4 — compliance-grade field history is a premium control |
| GS-04 | GSTIN validation | Validates GSTIN format/checksum and helps auto-fill | Fewer invalid invoices and filing errors | Format and checksum validation | Free-text `gstNumber` on store and supplier | No | None | Low | Phase 1 — an invalid GSTIN breaks returns and invoice validity |
| PR-06 | Payment mode master | Configurable payment modes mapped to accounts | Flexibility for new payment channels | Mode master, account mapping | Fixed enum CASH/UPI/CARD/CREDIT | Yes | Accounting core | Low | Phase 2 — custom modes are an accounting integration concern |
| RP-03 | Invoice-wise profit | Profit per bill | Identifies unprofitable transactions | Per-invoice margin report | Profit by item and branch | No | None | Low | Phase 2 — schedule with the accounting/profit work |
| RP-09 | Dashboard receivables/payables/cash | Cash, bank, receivable and payable KPIs | Immediate financial pulse | Receivable, payable, cash and bank cards | Dashboard counts, today's sales/purchases, top items, stock value | No | SL-10, PU-06, AC-04 | Medium | Phase 2 — requires Phase 1/2 data to exist |
| RP-12 | Sales/purchase by party | Party-wise sales and purchase reports | Collections and supplier negotiation | Customer-wise sales report, party-wise purchase view | Supplier purchase history; reports filter by item/category | No | SL-11 | Low | Phase 1 — party-wise reports are needed for collections immediately |
| RP-13 | Category-wise summary | Aggregated category sales/purchases and margin | Merchandising decisions | Aggregate totals by category | Category filter on report rows | No | None | Low | Phase 2 — insight is an optimization, not compliance |
| IN-02 | Native Android app | Full mobile app with offline, camera and sharing | Field sales, mobile billing, bill capture | Native app, offline store, camera, share intents | Responsive web plus Android print bridge APK | No | Public API, offline architecture | High | Phase 3 — large investment after core parity |
| IN-03 | Windows desktop app | Desktop application with local data | Offline desktop operation | Desktop client | Windows print bridge only | No | Offline architecture | High | Phase 4 — redundant with the web app for most users |
| IN-12 | Bulk data import/export | Import/export masters and transactions | Faster onboarding and data ownership | Customer/supplier import, transaction import, full export | Item import, report exports | No | None | Medium | Phase 2 — data portability is important for adoption and migration |
| SB-04 | Plan/feature gating | Plans unlock feature sets | Tiered commercial packaging | Feature flags per plan and enforcement | Subscription dates and branch limit only | Yes | Subscription model | Medium | Phase 2 — needed before selling tiered plans |

### 6.1 Cross-cutting observations on partial features

- **Customer identity is the single most reused dependency.** Seven partial/missing capabilities (SL-09, SL-11, SL-12, SL-13, AC-16, AC-17, RP-11) become simpler once SL-11 exists.
- **Payment receipts are the second.** Without SL-10 and PU-06, `paid_amount` and `balance_amount` are static fields that no workflow updates.
- **Partial features should be completed, not replaced.** For example, SL-06 should extend the existing discount amount path rather than introduce a parallel discount engine.
- **Do not duplicate units or pricing.** IT-03 and IT-09 must extend `UnitConverter` and branch pricing, not create a second rate mechanism.

## 7. Missing features (83)

Grouped by module. Each row follows the same evidence standard as Section 6. Proposed schema is described only to size the change; no schema was modified.

### 7.1 Items & Catalog (6 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| IT-04 | HSN/SAC codes | Stores HSN/SAC per item and prints it on GST invoices and summaries | Statutory B2B invoices and correct GST classification | HSN/SAC field, document snapshot, validation, summary | `ItemService`, `ItemsPage`, sale/purchase detail snapshots | Yes — item column plus detail snapshots | Report service | Low | Phase 1 — mandatory on GST invoices and GSTR-1 |
| IT-05 | Barcode generation and scanning | Generates barcodes and scans them into billing | Faster, more accurate item selection at the counter | Barcode field, label generation, scanner input handling | POS search by name/code only | Yes — barcode column and uniqueness rule | POS, printing | Medium | Phase 1 — speeds POS and reduces item-selection errors |
| IT-06 | Item images | Stores product photos shown during billing | Faster recognition for large catalogs | Image upload, storage, thumbnails | Item master has description only | Yes — image reference | File storage | Low | Phase 4 — cosmetic/productivity enhancement |
| IT-07 | Batch and expiry tracking | Tracks batch number, manufacturing date, expiry date and issues by expiry | Prevents expired stock sales and write-offs | Batch entity, batch stock, FEFO issue, expiry alerts | No batch concept anywhere | Yes — batch and batch-stock tables | Inventory ledger, purchase/sale lines | High | Phase 2 — required for pharma/FMCG expiry stock |
| IT-08 | Serial number tracking | Captures serial numbers on purchase and sale with warranty history | Traceability for electronics and high-value goods | Serial registry and capture flows | None | Yes — serial tables | Purchase/sale lines | High | Phase 3 — niche for electronics/warranty businesses |
| IT-13 | Custom item fields | Adds configurable fields to items | Accommodates industry-specific attributes | Field definitions and validation | Fixed item schema | Yes — field-definition and value tables | Item master | Medium | Phase 4 — flexibility after core parity |

### 7.2 Sales & Billing (13 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| SL-05 | Configurable invoice numbering | Prefix, suffix, series and financial-year reset | Meets statutory and audit expectations | Numbering settings and per-branch series | Fixed `NumberGenerator` prefixes (`SAL`, `PUR`) | Yes — numbering settings table | None | Low | Phase 1 — statutory series and audit expectations |
| SL-07 | Bill round-off | Rounds the invoice total and posts the difference | Matches common Indian invoice practice | Round-off setting and posted adjustment | `GstCalculator` rounds each line to paise; no round-off line | Yes — round-off column on documents | None | Low | Phase 1 — Indian invoice convention; low effort |
| SL-08 | Split/multiple payment modes | Splits one bill across cash, card, UPI and credit | Matches how customers actually pay | Payment allocation lines and validation | Single `payment_method` per sale | Yes — sale payment lines | Receipts | Medium | Phase 2 — common but not universal; touches the payment model |
| SL-10 | Payment receipts/collections | Records receipts and allocates them to invoices | Closes the credit-sale loop | Receipt document, allocation, status updates | `balance_amount` only | Yes — receipt and allocation tables | SL-11, SL-13 | Medium | Phase 1 — closes the credit-sale loop |
| SL-12 | Customer credit limit | Blocks or warns when a customer exceeds their limit | Limits bad debt | Limit field, exposure calculation, override permission | None | Yes — customer limit | SL-11, SL-13 | Medium | Phase 2 — risk control after receivables exist |
| SL-14 | Quotation/estimate | Creates estimates and converts them into invoices | Formal pre-sales documents | Quotation entity and conversion | None | Yes — quotation tables | Customer master | Medium | Phase 2 — pre-sales need; convert to invoice later |
| SL-15 | Proforma invoice | Issues a proforma before supply | Advance payment and approval workflows | Proforma entity and conversion | None | Yes — document tables | Customer master | Low | Phase 2 — low effort once a document framework exists |
| SL-16 | Sale order | Captures orders and tracks fulfilment | B2B order-to-invoice flow | Order entity and status tracking | None | Yes — order tables | Customer master | Medium | Phase 2 — order-to-invoice flow for B2B |
| SL-17 | Delivery challan | Moves goods before invoicing | Correct dispatch documentation | Challan entity and invoice conversion | Transfer only covers branch movement | Yes — challan tables | Customer master | Medium | Phase 2 — goods movement without an invoice |
| SL-18 | Sales return/credit note | Returns part of an invoice and issues a credit note | Handles real retail returns without cancelling the sale | Return entity, credit note, partial stock/GST reversal | Whole-sale cancellation only | Yes — return and credit-note tables | GST, inventory | High | Phase 1 — retail reality; cancellation alone is insufficient |
| SL-19 | Recurring invoices | Automatically issues invoices on a schedule | Subscription/service billing | Schedule, template, generation job | None | Yes — schedule tables | Customer master | Medium | Phase 3 — subscription/service businesses only |
| SL-20 | Hold/park bill | Parks a cart and recalls it later | Handles interrupted counter sales | Parked cart persistence and recall | None | Maybe — parked carts table or client storage | POS | Low | Phase 3 — POS convenience, not compliance |
| SL-22 | Invoice sharing | Sends invoices via WhatsApp, email or SMS | Immediate delivery and fewer printouts | PDF share, deep links, messaging integration | Browser print only | Maybe — share log | Printing, messaging | Medium | Phase 1 — customers expect WhatsApp/email invoices |

### 7.3 Purchases & Suppliers (5 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| PU-04 | Purchase order | Raises a PO and links goods receipt to it | Procurement control and expected-stock visibility | PO entity, approval, receipt linkage | Purchases only | Yes — PO tables | Supplier master | Medium | Phase 2 — procurement control for larger catalogs |
| PU-05 | Purchase return/debit note | Returns goods to a supplier with a debit note | Corrects supplier invoices without cancelling them | Return entity, debit note, stock/GST reversal | Whole-purchase cancellation only | Yes — return/debit-note tables | GST, inventory | High | Phase 2 — supplier corrections are frequent |
| PU-07 | Landed cost allocation | Adds freight and other costs to item cost | Accurate margin on freight-heavy goods | Cost heads and allocation across lines | Purchase rate only | Yes — purchase expense tables | Inventory valuation | High | Phase 3 — accurate margin for freight-heavy goods |
| PU-08 | Purchase bill attachment | Attaches the supplier bill image/PDF | Evidence retention and audit | Upload, storage, preview | None | Yes — attachment reference | File storage | Low | Phase 3 — evidence retention, not core flow |
| PU-09 | Supplier ledger/statement | Running supplier balance and statement | Payables control and reconciliation | Ledger entries and statement export | Purchase history only | Yes — ledger entries | PU-06 | Medium | Phase 2 — payables require it |

### 7.4 Inventory (5 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| IV-05 | Reorder/replenishment suggestions | Suggests what to reorder and how much | Turns low-stock data into action | Reorder quantity, supplier suggestion, PO generation | Low-stock list only | Maybe — reorder fields | Low stock, PU-04 | Medium | Phase 2 — converts low-stock data into action |
| IV-09 | Expiry alerts | Alerts for near-expiry and expired batches | Protects margin and compliance | Expiry buckets and alerts | No batch data | Yes — depends on batch | IT-07 | Medium | Phase 2 — depends on batch tracking; protects write-offs |
| IV-10 | Inventory aging | Shows how long stock has been held | Identifies slow-moving capital | Age buckets by receipt and movement | Movement report only | No — derive from ledger | Inventory ledger | Medium | Phase 3 — slow-moving stock analysis |
| IV-11 | Warehouse/bin sub-locations | Stores stock in warehouses and bins inside a location | Precise picking and counts | Location hierarchy and bin stock | Branch is the only level | Yes — location tables | Inventory ledger | High | Phase 3 — needed only for large warehouses |
| IV-12 | Manufacturing/BOM | Defines bills of materials and production orders | Light manufacturing | BOM, production issue/receipt | None | Yes — BOM and production tables | Inventory | High | Phase 4 — different business model |

### 7.5 Accounting & Finance (20 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| AC-01 | Chart of accounts/ledgers | Maintains account groups and ledgers | Foundation for every financial report | Account groups, ledgers, opening balances | None | Yes — accounts and ledgers | None | High | Phase 2 — foundation for all accounting |
| AC-02 | Journal vouchers | Double-entry journal entries | Correct books for adjustments | Voucher and lines with debit/credit validation | None | Yes — voucher tables | AC-01 | High | Phase 2 — core double entry |
| AC-03 | Contra entries | Cash-to-bank and bank-to-bank transfers | Correct cash/bank movement | Contra voucher | None | Yes — voucher tables | AC-01, AC-04 | Medium | Phase 2 — cash/bank movement |
| AC-04 | Cash book | Daily cash receipts and payments | Daily cash control | Cash ledger and book view | None | Yes — voucher/ledger | AC-01 | Medium | Phase 2 — daily cash control |
| AC-05 | Bank book and reconciliation | Bank ledger plus statement matching | Accurate bank balances | Bank accounts, import, matching | None | Yes — bank tables | AC-04 | High | Phase 3 — higher effort, after the bank book exists |
| AC-06 | Expense tracking | Records expenses by head and payment mode | True profitability | Expense heads and vouchers | Purchase and adjustment costs only | Yes — expense tables | AC-01 | Medium | Phase 2 — needed for a true P&L |
| AC-07 | Other income | Records non-sales income | Completes the P&L | Income heads and vouchers | None | Yes — voucher tables | AC-01 | Low | Phase 2 — completes P&L |
| AC-08 | Loans and liabilities | Tracks loans and interest | Financing visibility | Loan accounts and schedules | None | Yes — loan tables | AC-01 | Medium | Phase 3 — financing, less frequent |
| AC-09 | Cheque management | Cheque register and status | Payment instrument control | Cheque entity and lifecycle | None | Yes — cheque table | AC-05 | Medium | Phase 3 — payment instrument depth |
| AC-10 | Fixed assets | Asset register and depreciation | Balance-sheet accuracy | Asset and depreciation tables | None | Yes — asset tables | AC-01 | High | Phase 4 — depreciation is a premium capability |
| AC-11 | Trial balance | Ledger balances for verification | Books can be checked | Aggregation query and view | None | No — derived | AC-01, AC-02 | Medium | Phase 2 — derived from ledgers |
| AC-12 | Profit and loss | Income and expense statement | Real profit, not just gross margin | P&L report from ledgers | Gross margin report only | No — derived | AC-01, AC-06 | Medium | Phase 2 — primary accounting output |
| AC-13 | Balance sheet | Assets, liabilities and equity | Financial position | Balance-sheet report | None | No — derived | AC-01, AC-10 | Medium | Phase 2 — primary accounting output |
| AC-14 | Cash flow statement | Operating/investing/financing flows | Cash discipline | Cash-flow classification and report | None | Maybe — flow tags | AC-02, AC-05 | Medium | Phase 2 — derived from vouchers |
| AC-15 | Day book | Chronological all-voucher book | Daily review and audit | Day book view | None | No — derived | AC-02 | Low | Phase 2 — low effort, high daily value |
| AC-16 | Party financial ledger | Running balance per customer/supplier | Statements and collections | Ledger postings per party | Supplier purchase history only | Yes — party ledger links | AC-01, SL-11, PU-06 | Medium | Phase 2 — collections and payments |
| AC-17 | Receivables aging | Outstanding by age bucket | Collections prioritisation | Aging query and report | None | No — derived | SL-10, SL-13 | Medium | Phase 2 — collections management |
| AC-18 | Payables aging | Supplier dues by age bucket | Payment prioritisation | Aging query and report | None | No — derived | PU-06, PU-09 | Medium | Phase 2 — supplier management |
| AC-20 | Multi-currency | Foreign currency invoices and revaluation | Export/import businesses | Currency master, rates, revaluation | INR only | Yes — currency and rate tables | Accounting core | High | Phase 4 — export businesses only |
| AC-21 | TDS/TCS | Deducts and reports TDS/TCS | Statutory compliance for applicable vendors | Sections, thresholds, deduction, reports | None | Yes — TDS/TCS tables | Accounting core | High | Phase 3 — statutory for some vendors |

### 7.6 GST & Compliance (7 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| GS-05 | GSTR-1 | Prepares outward-supply return data | Monthly filing without manual rework | B2B/B2C sections, HSN, export | GST summary only | Maybe — return snapshot | IT-04, GS-04 | High | Phase 1 — monthly filing obligation |
| GS-06 | GSTR-3B | Prepares the summary return | Monthly tax liability filing | Liability and ITC summary | None | Maybe — return snapshot | GS-05, PU-02 | High | Phase 2 — monthly summary filing |
| GS-07 | GSTR-9 | Prepares the annual return | Year-end compliance | Annual aggregation | None | No — derived | GS-05, GS-06 | High | Phase 3 — annual filing |
| GS-08 | HSN summary | HSN-wise quantity, value and tax | Required part of GST reporting | HSN aggregation | No HSN data | No — derived | IT-04 | Medium | Phase 1 — part of GSTR-1 |
| GS-09 | e-Invoice | Generates IRN and QR through the IRP | Compliance above the turnover threshold | IRP integration, IRN/QR, cancellation | None | Yes — e-invoice fields | GS-04, GS-05 | High | Phase 3 — turnover-threshold dependent |
| GS-10 | e-Way bill | Generates e-way bills for goods movement | Transport compliance | EWB integration, transporter and vehicle data | None | Yes — EWB fields | GSTIN validation | High | Phase 2 — goods movement compliance |
| GS-11 | Reverse charge mechanism | Handles RCM purchases and self-invoices | Correct tax liability | RCM flag, self-invoice, payable tracking | None | Yes — RCM fields/tables | Accounting core | Medium | Phase 2 — affects tax liability |

### 7.7 Payments & Receivables (5 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| PR-01 | Payment-in/payment-out vouchers | Records standalone receipts and payments | Accounting-grade cash control | Payment vouchers and allocation | None | Yes — voucher tables | AC-01 | Medium | Phase 2 — accounting foundation |
| PR-02 | Payment reminders | Sends scheduled reminders to customers | Faster collections | Templates, schedules, delivery log | None | Yes — reminder tables | SL-10, messaging | Medium | Phase 2 — collections efficiency |
| PR-03 | UPI QR/payment link | Collects payments through a QR/link | Faster settlement | QR generation and reconciliation | None | Yes — payment reference | Payment gateway partner | Medium | Phase 3 — payment convenience, partner-dependent |
| PR-04 | Customer advances | Records advances and adjusts them later | Common B2B practice | Advance receipt and adjustment | None | Yes — advance tables | SL-11, AC-16 | Medium | Phase 2 — common in B2B |
| PR-05 | Refunds | Pays money back against returns | Completes the returns loop | Refund voucher and mode | None | Yes — refund tables | SL-18 | Medium | Phase 2 — returns require refunds |

### 7.8 Reports & Analytics (5 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| RP-11 | Party statement | Customer/supplier statement with running balance | Immediate collections and reconciliation | Statement query, export, share | None | No — derived | SL-10, PU-06 | Medium | Phase 1 — immediate collections need |
| RP-14 | Expense report | Expenses by head, branch and period | Cost control | Aggregation and view | None | No — derived | AC-06 | Low | Phase 2 — depends on expenses |
| RP-15 | Cash/bank flow report | Inflow/outflow by account | Liquidity visibility | Account-wise flow report | None | No — derived | AC-04, AC-05 | Medium | Phase 2 — depends on cash/bank books |
| RP-16 | Custom report builder | User-defined fields, filters and grouping | Power-user flexibility | Report definition engine | None | Yes — saved definitions | Reporting framework | High | Phase 4 — power-user feature |
| RP-17 | Scheduled reports | Emails reports on a schedule | Hands-off monitoring | Scheduler and delivery | None | Yes — schedule tables | Email service | Medium | Phase 4 — automation after report depth |

### 7.9 Users & Security (7 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| SC-07 | Forgot password | Resets a password by email/OTP | Basic account recovery | Reset token, email delivery, expiry | None | Yes — reset token table | Email service | Low | Phase 1 — basic account recovery |
| SC-08 | OTP/passcode/device lock | Mobile OTP login and app passcode | Stronger mobile security | OTP and device binding | JWT login only | Yes — OTP/device tables | SMS/email | Medium | Phase 3 — mobile security depth |
| SC-09 | Two-factor authentication | Adds a second factor | Account protection | TOTP/email OTP and recovery codes | None | Yes — 2FA secrets | SC-07 | Medium | Phase 4 — premium security |
| SC-11 | In-app backup/restore | Backs up and restores company data | Operational resilience | Scheduled backup, download, restore | Database backups at ops level only | Yes — backup metadata | Storage | High | Phase 3 — operational resilience |
| SC-12 | Full data export | Exports all business data | Data ownership and migration | Store-level export package | Report exports only | No | None | Medium | Phase 2 — data ownership and portability |
| SC-13 | Field-level permissions | Restricts sensitive fields by role | Enterprise governance | Field/action policy | Permission-level only | Yes — policy table | Role model | High | Phase 4 — enterprise governance |
| SC-14 | Edit approval workflow | Requires approval for sensitive edits | Maker-checker control | Approval states and notifications | Direct edits with audit | Yes — approval tables | Workflow engine | High | Phase 4 — enterprise governance |

### 7.10 Mobile & Integrations (9 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| IN-04 | Offline mode | Works without connectivity and syncs later | Field sales and unreliable networks | Local cache, queue, conflict resolution | Server-dependent SPA | Yes — sync metadata | IN-02, sync design | High | Phase 4 — major architecture change |
| IN-07 | Online store/e-commerce | Publishes a storefront and receives orders | New sales channel | Storefront, catalog sync, order intake | None | Yes — storefront tables | Public API | High | Phase 4 — separate product surface |
| IN-08 | WhatsApp billing/share | Sends bills and reminders on WhatsApp | High-reach customer communication | Templates, API integration, delivery log | None | Yes — message log | Messaging provider | Medium | Phase 3 — high reach, integration effort |
| IN-09 | OCR bill capture | Scans a supplier bill into a purchase draft | Faster purchase entry | Image capture, OCR, field mapping | None | Yes — OCR result tables | Mobile app, AI/OCR service | High | Phase 4 — experimental productivity |
| IN-10 | Tally integration | Transfers vouchers to Tally | Accountant ecosystem | Export format and mapping | None | Maybe — mapping table | Accounting core | High | Phase 3 — accountant ecosystem |
| IN-11 | Hardware barcode scanner | Uses USB/Bluetooth scanners | Fast, accurate counter operation | Scanner input handling and formats | None | No | IT-05 | Medium | Phase 3 — hardware dependency |
| IN-13 | Email/SMS notifications | Sends transactional notifications | Customer engagement | Email/SMS service and templates | None | Yes — notification log | Messaging provider | Medium | Phase 3 — notification infrastructure |
| IN-14 | Payment gateway | Collects online payments | Faster settlement and reconciliation | Gateway, callback, reconciliation | None | Yes — gateway references | PR-03 | High | Phase 4 — online collections |
| IN-15 | Public API/webhooks | Lets third parties integrate | Ecosystem and automation | API keys, scopes, webhook events | Internal JWT API only | Yes — API client tables | API versioning | High | Phase 4 — ecosystem play |

### 7.11 Subscription & Platform (1 missing)

| ID | Feature | What Vyapar does | Benefit to this app | What is missing | Existing frontend / backend | DB change | Dependencies | Complexity | Phase and rationale |
|---|---|---|---|---|---|---|---|---|---|
| SB-03 | Online subscription billing | Sells and renews plans online | SaaS monetization | Plans, invoices, payment collection | Manual/platform-managed dates | Yes — plan and invoice tables | Payment gateway | High | Phase 4 — SaaS monetization |

### 7.12 Dependency map for the missing set

The critical path through the missing features is:

```text
SL-11 Customer master
  -> SL-10 Payment receipts -> SL-13 Credit tracking -> AC-16 Party ledger
  -> RP-11 Party statement -> AC-17 Receivables aging -> RP-09 Dashboard KPIs
  -> SL-12 Credit limit
  -> SL-18 Sales returns -> PR-05 Refunds

AC-01 Chart of accounts
  -> AC-02 Journal -> AC-04 Cash book -> AC-05 Bank book
  -> AC-06 Expenses -> AC-12 P&L -> AC-13 Balance sheet
  -> AC-11 Trial balance / AC-14 Cash flow / AC-15 Day book

IT-04 HSN -> GS-08 HSN summary -> GS-05 GSTR-1 -> GS-06 GSTR-3B -> GS-07 GSTR-9
IT-07 Batch -> IV-09 Expiry alerts
PU-06 Supplier payments -> PU-09 Supplier ledger -> AC-18 Payables aging
```

## 8. Features requiring verification

Only two compared capabilities could not be settled from source inspection. Both are infrastructure-level behaviours that can exist outside the repository.

| ID | Feature | What is known | What is unknown | How to verify | Impact if absent |
|---|---|---|---|---|---|
| SC-10 | Login lockout / rate limiting | No rate limiter, failed-attempt counter or lockout logic exists in the Spring application. Login is a standard JWT endpoint. | Whether the production reverse proxy or hosting layer throttles `/api/auth/login`. | Send repeated failed logins from one IP and one username against a staging deployment; inspect the proxy configuration (nginx, Caddy, cloud WAF). | Brute-force and credential-stuffing exposure, especially because usernames are store-scoped but predictable. |
| IN-05 | Real-time multi-device sync | The frontend uses REST calls through Axios; no WebSocket, SSE or polling framework was found. The server is authoritative, so a manual refresh shows current data. | Whether pages refetch on focus/navigation often enough to be considered "live", and whether concurrent edits are detected. | Open the same store in two browsers, complete a sale in one, and observe the dashboard/inventory in the other without refreshing; test two simultaneous sales of the last unit. | Stale dashboards and confusing concurrency behaviour. The inventory result itself is protected by row locks, so the risk is presentation, not overselling. |

### 8.1 Assumptions explicitly excluded from the counts

These are not counted as application features because they cannot be attributed to this repository's code:

- Vyapar plan availability, pricing and country-specific feature sets.
- Vyapar's exact multi-branch and godown permission granularity.
- Vyapar API availability and on-premise deployment options.
- GST portal, IRP and e-way-bill sandbox access for this application.
- Production proxy, WAF, TLS and database-at-rest encryption settings, which are deployment concerns outside the application source.

---

## 9. Existing application advantages

### 9.1 Capabilities that are stronger than the Vyapar feature set reviewed

| Area | This application | Why it is stronger |
|---|---|---|
| Tenant isolation | `store_id` on every row, composite foreign keys, tenant-scoped repositories | Cross-store references are blocked by the schema, not only by service code. |
| Branch access control | `AuthUser.canAccessBranch` plus `TenantContext.requireBranch` in every branch-aware service | A branch user cannot read or mutate another branch even by guessing IDs. |
| Transfer workflow | Explicit pending/approved/dispatched/received/rejected/cancelled states with transition guards | Stock movement is auditable and cannot skip steps. |
| Negative stock | Availability check plus `SELECT ... FOR UPDATE` on the inventory row | Concurrent billing cannot oversell the last unit. |
| Tax correctness | `GstCalculator` as a single source of truth, tax snapshots on every line, inclusive/exclusive and inter-state handling | Historical documents remain correct after tax rates change. |
| Excel import | Preview-first import, row-level validation, automatic category creation, branch opening stock | Safe bulk onboarding, which is more controlled than a blind spreadsheet import. |
| Printing | Free silent ESC/POS and GDI printing through local bridges, pairing codes, hashed tokens, duplicate-job suppression, print history | No paid middleware and no browser print dialog. |
| Commercial controls | Subscription dates and server-enforced branch limits | The platform can operate a tiered commercial model. |
| API shape | Consistent REST controllers, DTOs, validation and error handling | Easier to add accounting and mobile clients without rework. |
| Responsiveness | Responsive layouts across operational pages | Usable on tablets and phones without a separate build. |

### 9.2 Differences that are deliberate, not deficiencies

- **Single currency (INR)** matches the current market focus; multi-currency is a later expansion.
- **Fixed unit set with base-unit conversion** keeps stock maths simple; custom units are an extension, not a correction.
- **Whole-document cancellation** is a conservative correction path; partial returns should be added rather than replacing it.
- **Operational profit** (revenue minus COGS) is a legitimate gross-margin metric. It should be renamed in the UI or supplemented by accounting profit, not silently treated as net profit.
- **No customer CRUD yet** is consistent with a walk-in retail design; it becomes a gap only when credit sales and repeat customers are targeted.

### 9.3 Workflows that should not change

These invariants protect data integrity and should be treated as non-negotiable during any feature work:

1. **Inventory ledger is the only stock truth.** New features must post through `InventoryService` (`applyStockIn`, `applyStockOut`, `lockOrCreate`), never update `current_stock` directly.
2. **No negative stock.** Any new document type (returns, challans, production) must reuse the availability check and row lock.
3. **Tenant and branch keys stay composite.** New tables must carry `store_id` and, where branch-specific, `branch_id`, with composite FKs.
4. **Tax snapshots stay immutable.** Returns and credit notes must snapshot the original tax data; never recalculate historical tax from the current tax master.
5. **Server computes money.** Client-supplied totals must never be trusted; the existing compute-then-persist pattern must be preserved.
6. **Transfer state machine stays explicit.** New statuses require a documented transition matrix and audit entries.
7. **Print dedupe stays.** New document types must use unique job keys; otherwise retries will duplicate receipts.
8. **Permission checks stay in services.** Hiding a button is not authorization.
9. **Cancellation remains reversible and audited.** No hard deletes of financial documents.

### 9.4 Duplicate and overlapping functionality to avoid

| Overlap | Risk | Recommended consolidation |
|---|---|---|
| Sales cancellation vs. sales return (SL-18) | Two ways to reverse a sale with different accounting | Keep cancellation for mistakes; implement returns as a separate partial document that references the original invoice. |
| Customer payment receipt (SL-10) vs. payment-in voucher (PR-01) | Two receipt ledgers | Build one payment engine with two entry points; receipts are customer-allocated payments. |
| Supplier payment (PU-06) vs. payment-out voucher (PR-01) | Two payable ledgers | Same engine, supplier-allocated direction. |
| Party ledger (AC-16) vs. party statement (RP-11) | Duplicate balances | Statement is a read-only view over the ledger. |
| Credit tracking (SL-13) vs. receivables aging (AC-17) | Two outstanding calculations | One aging query with different presentations. |
| HSN field (IT-04) vs. HSN summary (GS-08) | Two sources of HSN truth | Summary derives from the item snapshot. |
| Batch tracking (IT-07) vs. expiry alerts (IV-09) | Two stock models | Expiry alert is a report over batch stock. |
| Price list (IT-09) vs. branch pricing | Two rate resolvers | Extend branch pricing into a resolution chain: party list → branch price → item rate. |
| Units (IT-03) vs. base unit | Two conversion systems | Extend `UnitConverter`; never add a parallel conversion. |
| Low-stock alerts (IV-04), payment reminders (PR-02), email/SMS (IN-13) | Three notification stacks | One notification service with event types. |
| Invoice sharing (SL-22), WhatsApp billing (IN-08), email/SMS (IN-13) | Three messaging integrations | One messaging abstraction and delivery log. |
| Online store (IN-07) vs. subscription billing (SB-03) | Two e-commerce surfaces | Separate concerns: storefront for merchants, billing for the platform. |
| Custom fields (IT-13) vs. item description | Redundant free-text storage | Use custom fields only for structured, filterable attributes. |

---

## 10. Business-logic, validation, reporting, mobile and security gaps

### 10.1 Business-logic gaps found in the current implementation

These are not "missing Vyapar features"; they are correctness or consistency issues that will surface as the product grows.

| # | Finding | Evidence | Consequence | Recommended handling |
|---|---|---|---|---|
| 1 | Credit sales are not collectable in-app | `SaleService` stores `paid_amount` and `balance_amount`; no receipt workflow exists | Money owed is recorded but cannot be collected, aged or reconciled | Complete SL-11, SL-10 and SL-13 before promoting credit sales |
| 2 | Branch selling price is not used by POS | `ItemService.posItems` returns `item.getRate()`; POS uses that value | A branch-level selling price set in inventory pricing is ignored at the counter | Resolve price as branch selling price, then item rate; make the rule explicit |
| 3 | Transfer valuation can drift | `TransferService.receive` reads the source `avg_cost` at receive time, not dispatch time | If the source receives stock between dispatch and receipt, the destination cost differs from the dispatched value | Snapshot the transfer cost at dispatch |
| 4 | No in-transit stock bucket | Dispatch reduces source stock; receipt adds destination stock; dispatched quantity is in neither location | Store-wide stock appears lower while goods are in transit | Add an in-transit state or report |
| 5 | Whole-document cancellation can fail after downstream activity | Cancelling a completed purchase reverses stock; if the stock was sold, `applyStockOut` blocks it | Users cannot correct a purchase without first unwinding the sales | Add purchase returns (PU-05) as the normal correction path |
| 6 | Document dates are not bounded | `SaleRequest.salesDate` and `PurchaseRequest.purchaseDate` have no past/future constraint | Future or far-backdated documents can distort period reports | Add configurable date-window validation |
| 7 | "Profit" means gross margin | `ReportService.profitReport` uses `cost_rate` from sale lines and excludes expenses | Users may read it as net profit | Rename to "Gross margin" or add the accounting P&L |
| 8 | Store-level tax policy only | `taxInclusive` is a store flag; branches do not carry separate GST registration | Cannot model branches with different tax treatment | Consider branch-level tax settings if multi-state operation grows |
| 9 | Purchase completion assumes full payment | `PurchaseService.paid` defaults paid to grand total for completed purchases | Supplier credit is not represented unless the user edits the paid amount | Make supplier credit explicit when PU-06 lands |
| 10 | Orphaned customer schema | `customers` table, entity and repository exist but no workflow uses them | Dead schema invites inconsistent future implementations | Either implement SL-11 or formally deprecate it; do not build a second customer table |
| 11 | Audit trail is action-level, not field-level | `AuditService.log` records an action and message | Cannot prove which field changed | Extend for sensitive fields in Phase 4 |
| 12 | Low-stock definition includes zero-threshold items | `row()` flags `stock <= minimum`, and minimum defaults to 5 but can be 0 | Items with a zero minimum are flagged at zero stock | Document the rule or treat `minimum = 0` as "no alert" |

### 10.2 Missing validations and edge cases

| Area | Missing validation | Risk |
|---|---|---|
| GSTIN | Format and checksum validation | Invalid tax identifiers on invoices and returns |
| HSN/SAC | Code format and length | Filing rejection |
| Customer | Duplicate detection by mobile/GSTIN, credit limit | Duplicate parties and uncontrolled credit |
| Discounts | Percentage bounds and per-line percentage | Entry errors |
| Dates | Future/backdated document windows | Period misstatement |
| Returns | Return quantity cannot exceed sold quantity; return value cannot exceed invoice value | Over-crediting |
| Payments | Allocation cannot exceed invoice balance; receipt cannot exceed outstanding | Over-collection |
| Transfers | In-transit loss/damage resolution | Stuck dispatched transfers |
| Batch | Cannot sell expired or unapproved batch | Compliance breach |
| Multi-currency | Rate and rounding policy | Valuation errors |
| Import | Row limit, duplicate barcode, duplicate GSTIN | Partial or corrupted onboarding |
| Security | Login throttling and password reset | Account takeover |

### 10.3 Reporting gaps

The current report set is operationally strong but financially incomplete:

- **Missing entirely:** day book, trial balance, P&L, balance sheet, cash flow, cash book, bank book, party statement, receivable aging, payable aging, expense report, GSTR-1/3B/9, HSN summary, e-invoice register, e-way-bill register, invoice-wise profit, inventory aging, custom and scheduled reports.
- **Present but narrow:** GST report is a rate-wise summary, not a return; profit is gross margin by item/branch; sales and purchases are transaction lists, not party or category summaries; dashboard has no receivables, payables, cash or bank.
- **Export is good:** Excel, PDF and print are implemented consistently through `ReportExport`.
- **Filtering is good:** date range, branch, category, item and text search are available on the main reports.

### 10.4 Mobile and tablet usability gaps

| Area | Current state | Gap |
|---|---|---|
| Layout | Responsive Tailwind layouts; verified at multiple widths in earlier delivery work | Dense report and settings tables still require horizontal scrolling on small phones |
| Billing | POS web page works on a tablet browser | No native gestures, no offline, no camera scanner |
| Printing | Android bridge prints to a LAN printer on the same device | Requires installing the bridge APK; no cloud print |
| Barcode | No barcode field or scanner input | Manual search only |
| Sharing | Browser print only | No share sheet, WhatsApp or email |
| Notifications | None | No low-stock or payment alerts on mobile |
| Offline | None | No billing without connectivity |
| Installability | No web manifest or service worker | Cannot be installed as a PWA |

### 10.5 Security and permission gaps

| Area | Current state | Gap |
|---|---|---|
| Authentication | JWT with 12-hour expiry, refresh, logout with token blacklist | No forgot password, OTP, 2FA or device lock |
| Brute force | No application-level throttle found | Needs proxy verification or implementation (SC-10) |
| Authorization | Permission codes plus branch checks in services | No field-level permissions or edit approvals |
| Audit | Action-level logs, no viewer UI | No field-level history and no in-app audit screen |
| Data protection | Hashed device tokens, hashed pairing codes, CORS allowlist | No in-app backup, no full export, no documented retention policy |
| Secrets | Environment-based datasource and JWT configuration | Production secret handling must be verified operationally, not from source |
| Printing | Bridges bind to `127.0.0.1`, device tokens hashed, origin allowlist | A compromised local machine can still reach the bridge; document hardening |

---

## 11. Technical impact assessment

### 11.1 Change surface by phase

The schema and API names below are **proposals for sizing**, not existing artifacts. No migration or code was created.

| Phase | Modules touched | Likely schema work (proposed) | Likely API/UI work (proposed) | Complexity | Estimate |
|---|---|---|---|---|---|
| Phase 1 | Customers, sales, returns, GST, auth, reports | Extend `customers`; add `sales_returns`, `sales_return_items`, `credit_notes`, `payments`, `payment_allocations`, HSN/barcode columns, `round_off`, `document_number_settings`, `password_reset_tokens` | Customer CRUD, receipt entry, return/credit-note flow, HSN and barcode fields, percentage discount, numbering settings, invoice share, party statement, GSTR-1/HSN export, forgot password | High | 10–14 person-weeks |
| Phase 2 | Accounting, purchases, documents, inventory, GST, notifications | Add `account_groups`, `accounts`, `vouchers`, `voucher_lines`, `party_allocations`, `expenses`, `purchase_orders`, `purchase_returns`, `quotations`, `sale_orders`, `delivery_challans`, `batches`, `batch_stock`, `payment_reminders`, `feature_flags` | Accounting screens, supplier payments, purchase returns, document chain, batch/expiry, GSTR-3B, RCM, e-way bill, reminders, dashboard KPIs, reorder suggestions, stock count, data export | Very high | 20–28 person-weeks |
| Phase 3 | Compliance, integrations, mobile, security | Add `price_lists`, `serials`, `bank_accounts`, `bank_reconciliations`, `cheques`, `loans`, `tds_entries`, `backup_jobs`, `otp_challenges`, `message_log`, `tally_exports` | e-invoice, GSTR-9, price lists, serial tracking, Tally, WhatsApp, payment links, native app, aging, bins, TDS, bank reconciliation, backup, OTP | High | 18–26 person-weeks |
| Phase 4 | Premium and ecosystem | Add `currencies`, `exchange_rates`, `fixed_assets`, `custom_fields`, `report_definitions`, `report_schedules`, `2fa_secrets`, `field_policies`, `approval_requests`, `storefront_*`, `ocr_jobs`, `api_clients`, `webhooks`, `subscription_plans`, `subscription_invoices` | Multi-currency, BOM, fixed assets, images, custom fields, report builder, scheduled reports, 2FA, field permissions, approvals, online store, OCR, gateway, public API, desktop/offline, subscription billing | Very high | 20–30 person-weeks |

**Indicative total for full Vyapar-level breadth: 68–98 person-weeks.** This is a planning estimate, not a commitment; it excludes regulatory onboarding, partner integration approvals, design work and production hardening.

### 11.2 Database impact

- **Additive migrations only.** Existing V1–V6 migrations must not be edited; all work should land as V7 and later.
- **No destructive changes.** New columns on `items`, `customers`, `sales` and `purchases` must be nullable or defaulted to preserve existing rows.
- **Double-entry core is the pivotal change.** Once accounts and vouchers exist, receivables, payables and financial statements should be derived from them rather than from duplicated columns.
- **Batch tracking changes inventory identity.** `branch_inventory` is currently keyed by branch and item. Batch-level stock needs a new key that includes batch, with a migration path for existing stock.
- **Returns and payments need allocation tables.** A return must reference the original invoice line; a payment must be allocatable across invoices.
- **Indexes will be needed** on party, document date, status, batch, and voucher date as data volume grows.
- **Opening balances** must be designed before go-live of accounting; migrating an existing operational history into a new ledger is a one-time, high-risk task.

### 11.3 Integration and regulatory risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| GSTN/IRP onboarding | e-invoice and e-way bill require credentials, sandbox testing and production registration | Treat as an external dependency with a separate rollout gate |
| Messaging providers | WhatsApp/SMS templates need approval and per-message cost | Abstract the provider and start with email/print sharing |
| Payment partner | UPI QR and payment links depend on onboarding and settlement rules | Pilot with one provider; reconcile through the payment engine |
| Accounting correctness | A double-entry system that is wrong is worse than none | Parallel-run against an accountant's books; lock the chart of accounts |
| Offline conflicts | Two devices billing the same stock offline | Server-authoritative reconciliation with explicit conflict handling |
| Performance | Ledger and aging queries grow with transaction volume | Index early; test with production-scale data |
| Backward compatibility | Existing APIs are used by the current UI and printing bridges | Version new endpoints; do not change existing contracts |

### 11.4 Delivery risks and mitigations

| Risk | Mitigation |
|---|---|
| Scope explosion | Freeze each phase's feature list; defer "while we are here" items |
| Accounting design churn | Model accounts and vouchers with an accountant before coding |
| Inventory regressions | Add invariant tests for stock, transfers and cancellation before touching inventory |
| Tenant leaks | Every new table and query must include `store_id`; add cross-tenant tests |
| Migration failure | Rehearse on a copy; keep the verified production backup untouched |
| UI inconsistency | Extend existing page patterns (`PageHeader`, `Card`, `Tabs`, `ReportExport`) rather than new design systems |
| Hidden duplication | Enforce the consolidation choices in Section 9.4 in design review |

---

## 12. Final recommendations

### 12.1 Product positioning

Do not position this application as a Vyapar clone. Its defensible position is:

> **A multi-branch inventory and billing platform with GST-grade documents and silent printing, adding accounting depth where it matters.**

That means investing first in the operational advantages (branch control, stock integrity, printing) and then in the financial loop (customers, receipts, returns, ledgers), rather than copying Vyapar's entire surface area.

### 12.2 Recommended sequence

**Phase 1 — Close the billing and collection loop (highest value, lowest architectural risk)**

1. Customer master (SL-11) with GSTIN, address, opening balance and credit limit fields.
2. Payment receipts and allocation (SL-10), credit tracking (SL-13), party statement (RP-11), party-wise reports (RP-12).
3. Sales returns and credit notes (SL-18), with stock and GST reversal.
4. HSN/SAC (IT-04) and HSN summary (GS-08); GSTR-1-ready export (GS-05).
5. GSTIN validation (GS-04), percentage discount (SL-06), round-off (SL-07), configurable numbering (SL-05).
6. Invoice sharing via WhatsApp/email (SL-22).
7. Forgot password (SC-07) and rate-limit verification/implementation (SC-10).

**Definition of done for Phase 1:** a credit sale can be invoiced, shared, partly collected, aged, returned in part, and reported by party and GST period without leaving the application.

**Phase 2 — Build the accounting and inventory spine**

1. Chart of accounts, journal, contra, cash book, expenses, other income, day book, trial balance, P&L, balance sheet, cash flow.
2. Supplier payments (PU-06), supplier ledger (PU-09), payables aging (AC-18), purchase returns (PU-05).
3. Document chain: quotation (SL-14), proforma (SL-15), sale order (SL-16), delivery challan (SL-17), purchase order (PU-04).
4. Batch and expiry (IT-07, IV-09), stock count (IV-08), reorder suggestions (IV-05).
5. GSTR-3B (GS-06), RCM (GS-11), e-way bill (GS-10).
6. Payment reminders (PR-02), advances (PR-04), refunds (PR-05), payment mode master (PR-06).
7. Dashboard financial KPIs (RP-09), invoice-wise profit (RP-03), category summary (RP-13), expense and cash/bank reports (RP-14, RP-15).
8. Full data export (SC-12), bulk import (IN-12), plan/feature gating (SB-04).

**Definition of done for Phase 2:** an accountant can produce a trial balance, P&L and balance sheet from the app, and a store manager can see receivables and payables by age.

**Phase 3 — Compliance and ecosystem depth**

e-invoice, GSTR-9, price lists, serials, Tally, WhatsApp billing, payment links, native Android app, inventory aging, warehouse bins, TDS/TCS, bank reconciliation, loans, cheques, in-app backup, OTP/device lock.

**Phase 4 — Premium and optional**

Multi-currency, manufacturing/BOM, fixed assets, item images, custom fields, custom report builder, scheduled reports, 2FA, field-level permissions, edit approvals, online store, OCR, payment gateway, public API, full desktop/offline, online subscription billing.

### 12.3 What not to build now

- **Manufacturing/BOM, fixed assets, multi-currency, OCR, online store, public API, full desktop app and custom report builder.** Each is a large, separate product surface with limited overlap with the current customer base.
- **A second customer, unit, payment or inventory model.** Extend the existing entities and services.
- **A reporting data warehouse.** The current PostgreSQL queries are adequate at this scale; optimize after real volume.
- **A native app before the API surface and offline design are settled.**

### 12.4 Guardrails for every future change

1. Additive Flyway migrations only (V7+); never edit V1–V6.
2. Every new table carries `store_id`; branch data carries `branch_id` with composite FKs.
3. All stock changes go through `InventoryService`; all money is computed on the server.
4. Tax snapshots are written at document time and never recalculated.
5. New documents reuse the existing numbering, audit, permission and printing patterns.
6. Each phase ships with integration tests for tenant isolation, stock invariants and document totals.
7. No production deployment or production database change without explicit approval, and the verified production backup must remain untouched.

### 12.5 Verification plan for the two open items

- **SC-10:** run a scripted failed-login test against staging (20 attempts, one username; 100 attempts, one IP) and inspect the reverse proxy configuration. Record the result; implement application-level throttling if the proxy is absent.
- **IN-05:** run a two-browser concurrency test for dashboard refresh and last-unit sale. Confirm that the UI refreshes on navigation/focus and that the second sale is rejected with an insufficient-stock error. If staleness is unacceptable, add polling or SSE in Phase 2.

### 12.6 Success criteria for the gap-closing programme

| Metric | Target after Phase 1 | Target after Phase 2 |
|---|---|---|
| Invoices with a linked customer | ≥ 90% of non-walk-in sales | ≥ 95% |
| Credit sales with a recorded receipt or clear outstanding | 100% | 100% |
| Returns processed without cancelling the original invoice | 100% of partial returns | 100% |
| GST returns prepared without manual re-keying | GSTR-1 and HSN ready | GSTR-1, 3B and RCM ready |
| Financial statements produced in-app | No | Trial balance, P&L, balance sheet |
| Receivables/payables visible by age | Customer outstanding list | Full aging |
| Stock accuracy | Adjustments and counts recorded | Count sessions with variance approval |

---

## Appendix A — Counts by module

| Module | Implemented | Partially Implemented | Missing | Needs Verification | Not Applicable | Total |
|---|---:|---:|---:|---:|---:|---:|
| Company & Branch | 5 | 1 | 0 | 0 | 0 | 6 |
| Items & Catalog | 5 | 2 | 6 | 0 | 0 | 13 |
| Sales & Billing | 5 | 6 | 13 | 0 | 0 | 24 |
| Purchases & Suppliers | 3 | 2 | 5 | 0 | 0 | 10 |
| Inventory | 6 | 2 | 5 | 0 | 0 | 13 |
| Accounting & Finance | 0 | 2 | 20 | 0 | 0 | 22 |
| GST & Compliance | 3 | 1 | 7 | 0 | 0 | 11 |
| Payments & Receivables | 0 | 1 | 5 | 0 | 0 | 6 |
| Reports & Analytics | 8 | 4 | 5 | 0 | 0 | 17 |
| Users & Security | 6 | 0 | 7 | 1 | 0 | 14 |
| Mobile & Integrations | 2 | 3 | 9 | 1 | 0 | 15 |
| Subscription & Platform | 2 | 1 | 1 | 0 | 0 | 4 |
| **Total** | **45** | **25** | **83** | **2** | **0** | **155** |

## Appendix B — Evidence index

### Backend

- [SaleService.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/SaleService.java) — sales creation, discounts, GST, stock-out, cancellation.
- [PurchaseService.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/PurchaseService.java) — purchase draft/complete/cancel, stock-in, average cost.
- [InventoryService.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/InventoryService.java) — locking, stock in/out, adjustments, ledger.
- [TransferService.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/TransferService.java) — transfer state machine and valuation.
- [ItemService.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/ItemService.java) — item CRUD, POS feed, branch stock.
- [SupplierService.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/SupplierService.java) — supplier CRUD and purchase history.
- [UserService.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/UserService.java) — users, roles, permissions, branch assignment.
- [ReportService.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/ReportService.java) — all reports and profit calculation.
- [DashboardService.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/DashboardService.java) — dashboard aggregates.
- [ItemImportService.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/ItemImportService.java) — import preview/confirm and opening stock.
- [NumberGenerator.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/service/NumberGenerator.java) — fixed numbering prefixes.
- [GstCalculator.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/util/GstCalculator.java) — tax maths and rounding.
- [SecurityConfig.java](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/java/com/itemmanagement/config/SecurityConfig.java) — auth, CORS and stateless security.
- [application.yml](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/resources/application.yml) — runtime configuration.

### Database

- [V1__init.sql](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/resources/db/migration/V1__init.sql) — core schema, permissions, tables.
- [V2__tax_master.sql](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/resources/db/migration/V2__tax_master.sql) — tax master and snapshots.
- [V3__item_rate.sql](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/resources/db/migration/V3__item_rate.sql) — single item rate.
- [V4__subscriptions.sql](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/resources/db/migration/V4__subscriptions.sql) — subscription fields and history.
- [V5__item_import.sql](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/resources/db/migration/V5__item_import.sql) — import batches and rows.
- [V6__printing.sql](file:///D:/vishrut/Clone/ItemManagementSystem/backend/src/main/resources/db/migration/V6__printing.sql) — printers, pairing, devices, jobs.

### Frontend

- [App.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/App.tsx) — route inventory.
- [nav.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/components/layout/nav.tsx) — module navigation.
- [ReportsPage.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/pages/ReportsPage.tsx) — eight report tabs and export.
- [DashboardPage.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/pages/DashboardPage.tsx) — dashboard content.
- [PosPage.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/pages/PosPage.tsx) — POS capabilities and payment modes.
- [SalesEntryPage.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/pages/SalesEntryPage.tsx) — manual sales entry.
- [PurchasesPage.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/pages/PurchasesPage.tsx) — purchase workflow UI.
- [InventoryPage.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/pages/InventoryPage.tsx) — inventory and adjustments.
- [TransfersPage.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/pages/TransfersPage.tsx) — transfer workflow UI.
- [ItemImportsPage.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/pages/ItemImportsPage.tsx) — import UI.
- [SettingsPage.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/pages/SettingsPage.tsx) — settings shell.
- [documents.ts](file:///D:/vishrut/Clone/ItemManagementSystem/src/lib/documents.ts) — invoice/receipt HTML layouts.
- [export.ts](file:///D:/vishrut/Clone/ItemManagementSystem/src/lib/export.ts) — Excel/PDF/print export.
- [ReportExport.tsx](file:///D:/vishrut/Clone/ItemManagementSystem/src/components/ReportExport.tsx) — export controls.
- [index.html](file:///D:/vishrut/Clone/ItemManagementSystem/index.html) — no manifest or service worker.

### Verification commands used

```bash
# Route and page inventory
grep -n "path=" src/App.tsx
grep -n "group" src/components/layout/nav.tsx

# Controller and API inventory
ls backend/src/main/java/com/itemmanagement/controller
grep -rn "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" backend/src/main/java/com/itemmanagement/controller

# Schema inventory
grep -n "^CREATE TABLE" backend/src/main/resources/db/migration/*.sql

# Confirm absent capabilities
grep -rni "whatsapp\|forgot\|ratelimit\|lockout" src backend/src/main/java
grep -rni "websocket\|EventSource" src backend/src/main/java
grep -rni "customer" src/App.tsx src/components/layout/nav.tsx backend/src/main/java/com/itemmanagement/controller
```

---

**End of document.**
