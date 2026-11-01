# API Documentation

> Verified against the 24 controller classes in
> `backend/src/main/java/com/itemmanagement/controller/` on **2026-11-01**. There are
> **133 route mappings**. Every path is prefixed with `/api` at the reverse proxy; controllers
> declare `/api/...` explicitly.
>
> “Permission” is the `@PreAuthorize` authority required **in addition to** a valid JWT. Missing or
> invalid token → `401`; missing permission → `403`; wrong tenant/branch → `403`/`404`; validation →
> `400`; duplicate → `409`.

---

## 1. Conventions

### Authentication

Browser sessions use an **HttpOnly cookie**, issued by login, refresh and password change:

```http
Set-Cookie: ims_auth=<jwt>; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200[; Secure]
```

JavaScript cannot read the cookie; the SPA uses `withCredentials`. Non-browser clients may send
`Authorization: Bearer <jwt>` instead. Both `POST /api/auth/login` and the cookie response return
`token`, `expiresIn` (ms), `user`, `store`, `branches`, `roles`, `permissions`, `allBranches`,
`platformAdmin`; the body token exists solely for API clients and automated tests.

Tokens are HMAC-signed (JJWT 0.12.6) and carry `storeId`, roles, permissions, branch ids,
`allBranches`, `jti` and `ver` (`users.token_version`). The server reloads the user and store on every
request, so deactivation takes effect immediately. Revoked JTIs are persisted in
`revoked_tokens` and rejected across restarts; a `ver` mismatch invalidates every token issued before
a password change or reset. Session, reset and password responses send `Cache-Control: no-store`.

Unsafe cookie-authenticated requests (`POST/PUT/PATCH/DELETE`) must carry an `Origin` (or `Referer`)
whose origin is listed in `CORS_ORIGINS`; otherwise the API returns `403` with
`code: "CSRF_ORIGIN_REJECTED"`. Send `Authorization` explicitly if you call the API from a non-browser
client with an origin-less request.

A user with `must_change_password=true` receives `403` with
`code: "PASSWORD_CHANGE_REQUIRED"` on every endpoint except `/api/auth/**`; call
`POST /api/auth/change-password` first.

### Paging

List endpoints with `page`/`size` return:

```json
{
  "content": [],
  "page": 0,
  "size": 10,
  "totalElements": 0,
  "totalPages": 0
}
```

`page` is zero-based and clamped to `>= 0`; `size` is clamped globally to `1..100` by
`Pagination.of` (import history and print jobs cap at 50).

### Errors

```json
{
  "timestamp": "2026-11-01T15:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Branch is required",
  "path": "/api/sales"
}
```

Validation errors return the first field message. Subscription expiry returns
`403 {"message":"…","code":"SUBSCRIPTION_EXPIRED"}` for every non-exempt endpoint.

### Dates and money

Business dates are ISO `yyyy-MM-dd` (`LocalDate`). Timestamps are ISO-8601 instants. Money is JSON
number with 2 decimals; quantities may have 3 decimals.

### Public endpoints

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/login` | none |
| POST | `/api/auth/register-store` | none |
| GET | `/api/health` | none — database probe, no secrets |
| GET | `/api/version` | none — build metadata, no secrets |
| POST | `/api/printing/devices/pair` | one-time pairing code |
| GET | `/api/printing/device/config` | `X-Device-Token` |
| GET | `/api/subscription/summary` | JWT, exempt from subscription blocking |
| GET | `/error` | none |

---

## 2. AuthController — `/api/auth`

| Method | Path | Auth | Request / notes |
|---|---|---|---|
| POST | `/login` | public | `{storeCode, username, password}`. `storeCode = "PLATFORM"` selects a platform user. Wrong store/user/password all return the same `401`. Inactive user/store → `403`. Locked key → `429 TOO_MANY_REQUESTS`. Sets the `ims_auth` cookie. |
| GET | `/me` | JWT/cookie | Rebuilds the profile from the database; no new token. `no-store`. |
| POST | `/refresh` | JWT/cookie | Revokes the current `jti` and issues a new token/cookie. |
| POST | `/logout` | JWT/cookie | Revokes the current `jti`, clears the cookie; returns a message. `no-store`. |
| POST | `/change-password` | JWT/cookie | `{"currentPassword":"…","newPassword":"…"}`. Verifies the current password, applies the shared policy, clears `mustChangePassword`, increments `token_version` (invalidating all other sessions), audits `PASSWORD_CHANGE` and returns a new session/cookie. Available to platform and store users; the authenticated user is always the target. |
| POST | `/register-store` | public | `RegisterStoreRequest` (Section 2.1). Creates store, first branch, 5 tax slabs, 4 roles, owner and membership in one transaction. |

### 2.1 `RegisterStoreRequest`

```json
{
  "storeCode": "ACME01",
  "storeName": "Acme Retail",
  "ownerName": "Asha Rao",
  "email": "asha@acme.example",
  "mobile": "9876543210",
  "address": "12 Market Road, Bengaluru 560001",
  "gstNumber": "29ABCDE1234F1Z5",
  "state": "Karnataka",
  "username": "asha",
  "password": "ChangeMe123!",
  "branchName": "Main Branch"
}
```

`storeCode`: 3–30 `[A-Za-z0-9_-]`, stored upper-case, `PLATFORM` reserved, globally unique.
`username`: 3–40 `[A-Za-z0-9._-]`. `password`: 8–72 characters with upper-case, lower-case, number
and special character, no spaces, and not a common password or the username/email. Duplicate store
code → `409`.

---

## 3. BranchController — `/api/branches`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `BRANCH_VIEW` | Branches the caller may access. |
| GET | `/my-branches` | JWT | The caller’s assigned branches (or all for owner/admin). |
| GET | `/all` | `BRANCH_MANAGE` | Every branch in the store. |
| GET | `/{id}` | `BRANCH_VIEW` | Tenant + branch access checked. |
| POST | `` | `BRANCH_MANAGE` | `{branchCode, branchName, address, contactNumber, state?}`. Code unique per store (`409`). Enforces `max_branches`. |
| PUT | `/{id}` | `BRANCH_MANAGE` | Same body as create. |
| PUT | `/{id}/status` | `BRANCH_MANAGE` | `{status: "ACTIVE"\|"INACTIVE"}`. No delete endpoint. |

---

## 4. CategoryController — `/api/categories`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `CATEGORY_VIEW` | `search`, `status`, `page`, `size`; includes `itemCount`. |
| GET | `/options` | `CATEGORY_VIEW` | All categories for selects. |
| POST | `` | `CATEGORY_MANAGE` | `{categoryName, description?, status?}`. Name unique per store. |
| PUT | `/{id}` | `CATEGORY_MANAGE` | Same body. |
| DELETE | `/{id}` | `CATEGORY_MANAGE` | Blocked with `400` when items reference the category. |
| PUT | `/{id}/status` | `CATEGORY_MANAGE` | `{status}`. |

---

## 5. DashboardController — `/api/dashboard`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `DASHBOARD_VIEW` | Optional `branchId`. Returns sales/purchase totals for the period, low-stock count and the first low-stock rows, per-branch summary. |

---

## 6. InventoryController — `/api/inventory`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `INVENTORY_VIEW` | `branchId?`, `categoryId?`, `search?`, `lowOnly=false`, `page`, `size` (default 20). |
| GET | `/low-stock` | `INVENTORY_VIEW` | `branchId?`, `limit` (default 50). Rule `current_stock <= minimum_stock`. |
| GET | `/transactions` | `INVENTORY_VIEW` | `branchId?`, `itemId?`, `search?`, `page`, `size`. |
| POST | `/adjustments` | `INVENTORY_ADJUST` | `{branchId, itemId, type:"IN"\|"OUT", quantity>0, unit?, reason}`. Immediate, audited, creates `ADJ-…`. |
| PUT | `/{branchId}/items/{itemId}/pricing` | `INVENTORY_ADJUST` | `{sellingPrice, minimumStock}` for that branch row. |

---

## 7. ItemController — `/api/items`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `ITEM_VIEW` | `search` (name, code or barcode), `categoryId`, `status`, `page`, `size`. |
| GET | `/options` | `ITEM_VIEW` | Item options, optional `categoryId`. |
| GET | `/pos` | `SALES_MANAGE` or `SALES_VIEW` | `branchId` required; optional `categoryId`, `search`. Returns master rate, available stock, tax details. |
| GET | `/barcode/{barcode}` | `ITEM_VIEW` / `SALES_MANAGE` / `PURCHASE_MANAGE` | Exact barcode lookup inside the tenant. `404 No item uses this barcode`. |
| GET | `/{id}` | `ITEM_VIEW` | Full item including barcode, `barcodeSource` (`INTERNAL`/`GS1`), format, tax. |
| POST | `` | `ITEM_MANAGE` | `ItemRequest` (Section 7.1). Blank barcode → generated (Code 128 `IMS…` by default). |
| PUT | `/{id}` | `ITEM_MANAGE` | Same body. Changing format without a value is rejected. |
| PUT | `/{id}/status` | `ITEM_MANAGE` | `{status}`. Inactive items cannot transact but remain in history. |
| POST | `/{id}/barcode` | `ITEM_MANAGE` | `{format:"CODE128"\|"EAN13"}`; generates a new unique value and audits the change. |
| GET | `/{id}/stock` | `ITEM_VIEW` | Per-branch stock rows. |
| GET | `/{id}/transactions` | `ITEM_VIEW` or `INVENTORY_VIEW` | Optional `branchId`. |

### 7.1 `ItemRequest`

```json
{
  "itemCode": "MILK-1L",
  "barcode": "",
  "barcodeFormat": "CODE128",
  "itemName": "Milk 1L",
  "description": "Toned milk",
  "categoryId": 3,
  "taxId": 2,
  "baseUnit": "LITER",
  "rate": 60.00,
  "minStockLevel": 10,
  "status": "ACTIVE"
}
```

`itemCode` 2–50 `[A-Za-z0-9._-]`, unique per store. `rate > 0`. `baseUnit` one of
`LITER, ML, KG, GRAM, PIECE, PACKET, BOX, BOTTLE, DOZEN`. `barcode` max 64; EAN-13 requires 13
digits and a valid check digit; Code 128 allows `[A-Za-z0-9._-]{1,64}`; duplicates in the store →
`409`.

Generated EAN-13 values use the configurable internal prefix (default `20`, GS1's internal range
02/20–29, `app.barcode.ean13-prefix`); they are classified as `INTERNAL`. A manually entered valid
EAN-13 outside that range is classified as `GS1` and should be a registered company prefix. Barcode
uniqueness is per store.

---

## 8. ItemImportController — `/api/item-imports`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/template` | `ITEM_VIEW` | Downloads the `.xlsx` template (exact columns). |
| POST | `/preview` | `ITEM_MANAGE` **and** `INVENTORY_ADJUST` | `multipart/form-data`: `file`, `branchId`. Parses and validates the whole workbook, stores batch/rows, returns preview. No data is written. |
| POST | `/{batchId}/confirm` | `ITEM_MANAGE` **and** `INVENTORY_ADJUST` | Creates categories/items and opening stock atomically; `409` if the same file was already completed. |
| GET | `` | `ITEM_VIEW` or `INVENTORY_VIEW` | `page`, `size` import history. |
| GET | `/{batchId}` | `ITEM_VIEW` or `INVENTORY_VIEW` | Batch detail + row results. |

Template columns: `Item Code`, `Item Name`, `Category`, `Unit`, `Rate`, `Tax`, `Opening Stock`,
`Minimum Stock`, `Description`. Limit: 10 MB / 12 MB request (multipart). Barcodes are generated
automatically for created items.

---

## 9. PlatformAccountController — `/api/platform/accounts`

Class-level `@PreAuthorize("hasAuthority('PLATFORM_MANAGE')")`; the service additionally requires
`AuthUser.isPlatformAdmin()`. Only users with a non-null `store_id` are exposed.

| Method | Path | Request | Notes |
|---|---|---|---|
| GET | `` | `search?`, `status?`, `storeId?`, `page`, `size` | Search matches store name, store code, username, full name, email or mobile (case-insensitive). Password hashes are never returned. |
| GET | `/{id}` | — | One store account. Platform accounts → `404`. |
| PUT | `/{id}/username` | `{username}` | 3–80 `[A-Za-z0-9._-]`; unique per store (`409`). Audits `USERNAME_UPDATE` (old → new). |
| PUT | `/{id}/status` | `{status:"ACTIVE"\|"INACTIVE"}` | Audits `ACTIVATE`/`DEACTIVATE`. Disabled users are rejected by the JWT filter on their next request. |
| POST | `/{id}/reset-password` | `{confirm:true}` | Requires explicit confirmation. Generates a 14-character temporary password (upper/lower/digit/special, ambiguous characters excluded), stores only the BCrypt hash, sets `mustChangePassword=true`, increments `token_version`, audits `PASSWORD_RESET`, returns the plain temporary password **once** with `Cache-Control: no-store`. |
| GET | `/{id}/history` | — | Audit entries for `PASSWORD_RESET`, `PASSWORD_CHANGE`, `USERNAME_UPDATE`, `ACTIVATE`, `DEACTIVATE`, newest first. No passwords or hashes. |

Example reset response (show once, then discard):

```json
{
  "userId": 42,
  "username": "owner",
  "storeCode": "ABC001",
  "temporaryPassword": "Kq7#mRt2vX9!pL",
  "generatedAt": "2026-11-01T15:30:00Z"
}
```

---

## 10. PlatformController — `/api/platform`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/contact` | `PLATFORM_MANAGE` | Email/phone shown on the expired-subscription screen. |
| PUT | `/contact` | `PLATFORM_MANAGE` | `{contactEmail?, contactPhone?}`. |

---

## 11. PrintDeviceController — `/api/printing`

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/devices/pair` | one-time pairing code | `{code, deviceName, platform:"ANDROID"\|"WINDOWS"}`. Code is single-use, 10-minute expiry. Returns an opaque device token and branch info. |
| GET | `/device/config` | `X-Device-Token` | Returns the paired device’s store/branch printer list. Revoked token → `401`. |

---

## 12. PrintJobController — `/api/printing/jobs`

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `` | `SALES_MANAGE` | `{documentType:"SALE_RECEIPT"\|"PURCHASE"\|"TEST", documentId?, channel:"WINDOWS"\|"ANDROID", trigger:"AUTO"\|"MANUAL", printerConfigId?}`. Auto sale jobs use the unique key `AUTO:SALE_RECEIPT:…`; a repeat returns the existing job with `duplicate:true`. Cancelled/foreign sales are rejected. |
| PUT | `/{id}/status` | `SALES_MANAGE` | `{status:"PRINTED"\|"FAILED", errorMessage?}`; increments attempts. |
| GET | `` | `SALES_VIEW` | `page`, `size` (default 20), `status?`, `branchId?`. |
| GET | `/{id}` | `SALES_VIEW` | Job detail. |
| POST | `/{id}/reprint` | `SALES_MANAGE` | Creates a new `MANUAL:<uuid>` job; the original job is unchanged. |

---

## 13. PrinterController — `/api/printing/printers`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `PRINTER_VIEW` | `branchId` required. |
| POST | `` | `PRINTER_MANAGE` | `PrinterConfigRequest` (Section 13.1). Name unique per branch. |
| PUT | `/{id}` | `PRINTER_MANAGE` | Same body. |
| PUT | `/{id}/status` | `PRINTER_MANAGE` | `{status}`. |
| PUT | `/{id}/test-result` | `PRINTER_MANAGE` | `{success, message}` recorded from the bridge test. |
| DELETE | `/{id}` | `PRINTER_MANAGE` | Deletes configuration only; historical jobs retain the printer-name snapshot. |
| POST | `/pairing-code` | `PRINTER_MANAGE` | `branchId`; returns a one-time code + expiry. |
| GET | `/devices` | `PRINTER_MANAGE` | `branchId`; paired bridges. |
| DELETE | `/devices/{id}` | `PRINTER_MANAGE` | Revokes a device token. |

### 13.1 `PrinterConfigRequest`

```json
{
  "printerName": "Counter printer",
  "ipAddress": "192.168.1.50",
  "port": 9100,
  "paperWidth": "MM80",
  "purpose": "RECEIPT",
  "branchId": 1,
  "status": "ACTIVE"
}
```

`ipAddress` must be an IPv4 address or hostname; `port` 1–65535; `paperWidth` `MM58`/`MM80`;
`purpose` `RECEIPT`/`PURCHASE`/`REPORT`/`ALL`; `status` `ACTIVE`/`INACTIVE`.

---

## 14. PurchaseController — `/api/purchases`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `PURCHASE_VIEW` | `branchId?`, `status?`, `from?`, `to?`, `search?`, `page`, `size`. |
| GET | `/{id}` | `PURCHASE_VIEW` | Header + lines + GST snapshot. |
| POST | `` | `PURCHASE_MANAGE` | `PurchaseRequest` (Section 14.1). Default status `COMPLETED`; `DRAFT` writes no stock. |
| PUT | `/{id}` | `PURCHASE_MANAGE` | Draft only. Replaces lines atomically. |
| POST | `/{id}/complete` | `PURCHASE_MANAGE` | Posts stock + ledger (one transaction). |
| POST | `/{id}/cancel` | `PURCHASE_MANAGE` | Draft → cancelled (no stock). Completed → `PURCHASE_REVERSAL` stock-out; fails if stock is insufficient. |

### 14.1 `PurchaseRequest`

```json
{
  "branchId": 1,
  "supplierId": 4,
  "invoiceNumber": "INV-2026-001",
  "purchaseDate": "2026-11-01",
  "discount": 0,
  "paidAmount": 2520,
  "notes": "",
  "status": "COMPLETED",
  "items": [
    { "itemId": 10, "quantity": 50, "unit": "LITER", "purchaseRate": 48, "discount": 0 }
  ]
}
```

Tax is never accepted from the client; it is resolved from the item’s tax master and the
branch/supplier state. Header discount is allocated proportionally by gross; the last line takes the
remainder.

---

## 15. ReportController — `/api/reports`

All require `REPORT_VIEW`. All accept `from?`, `to?` (default: first day of current month → today;
`from > to` → `400`) and `branchId?`.

| Method | Path | Extra filters | Result |
|---|---|---|---|
| GET | `/purchases` | `categoryId?`, `itemId?`, `search?` | Line-level purchases for COMPLETED documents |
| GET | `/sales` | `categoryId?`, `itemId?`, `search?` | Line-level sales for COMPLETED documents |
| GET | `/gst` | `taxRate?` | Output-tax summary by branch + tax snapshot + rate |
| GET | `/stock` | `categoryId?`, `itemId?`, `search?` | Opening/current, purchased, sold, transfers, adjustments, stock value |
| GET | `/low-stock` | `categoryId?`, `search?` | Items at/below minimum |
| GET | `/branches` | — | Per-branch purchase/sales/stock summary |
| GET | `/store` | — | Store-level summary |
| GET | `/profit` | `categoryId?`, `itemId?`, `search?` | Revenue − COGS using the sale-time `cost_rate` snapshot |

---

## 16. RoleController — `/api`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/roles` | `ROLE_VIEW` | Roles in the store. |
| POST | `/roles` | `ROLE_MANAGE` | `{code, name, description?, permissionIds[]}`. |
| PUT | `/roles/{id}` | `ROLE_MANAGE` | Same body; system roles cannot be redefined by normal users. |
| GET | `/permissions` | `ROLE_MANAGE` | All 30 permission codes. |

---

## 17. SaleController — `/api/sales`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `SALES_VIEW` | `branchId?`, `status?`, `from?`, `to?`, `search?`, `page`, `size`. |
| GET | `/{id}` | `SALES_VIEW` | Header + lines + GST snapshot. |
| POST | `` | `SALES_MANAGE` | `SaleRequest` (Section 17.1). Stock checked/decremented in the same transaction. |
| POST | `/{id}/cancel` | `SALES_MANAGE` | `SALE_REVERSAL` stock-in; keeps the document with status `CANCELLED`. |

### 17.1 `SaleRequest`

```json
{
  "branchId": 1,
  "salesDate": "2026-11-01",
  "customerName": "Walk-in",
  "customerMobile": "",
  "customerState": "Karnataka",
  "discount": 0,
  "paymentMethod": "CASH",
  "paidAmount": 735,
  "pos": false,
  "items": [
    { "itemId": 10, "quantity": 10, "unit": "LITER", "sellingRate": 70, "discount": 0 }
  ]
}
```

`paymentMethod`: `CASH`/`UPI`/`CARD`/`CREDIT`. When `pos: true`, the server **ignores
`sellingRate`** and uses `items.rate`. Customer state drives intra-state (CGST+SGST) vs inter-state
(IGST); missing state means intra-state. `paidAmount` may not exceed the grand total.

---

## 18. StoreController — `/api/stores`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `PLATFORM_MANAGE` | `search?`, `status?`, `page`, `size`. |
| POST | `` | `PLATFORM_MANAGE` | Same body as `register-store`; creates a tenant. |
| GET | `/current` | `STORE_VIEW` | Current store profile. |
| PUT | `/current` | `STORE_MANAGE` | Store profile fields (name, owner, contacts, address, GST, tax mode). |
| GET | `/{id}` | `PLATFORM_MANAGE` | Any store. |
| PUT | `/{id}` | `PLATFORM_MANAGE` | Update any store. |
| PUT | `/{id}/status` | `PLATFORM_MANAGE` | Suspend/reactivate a tenant. |
| GET | `/{id}/subscription` | `PLATFORM_MANAGE` | Current window, branch limit, usage. |
| PUT | `/{id}/subscription` | `PLATFORM_MANAGE` | `{startDate, endDate, maxBranches, note?}`; writes history. |
| GET | `/{id}/subscription/history` | `PLATFORM_MANAGE` | Change history. |

---

## 19. SubscriptionController — `/api/subscription`

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/summary` | JWT | Current store subscription, branch usage/limit and platform contact. Exempt from the subscription block so the UI can render the expired screen. |

---

## 20. SupplierController — `/api/suppliers`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `SUPPLIER_VIEW` | `search?`, `status?`, `page`, `size`. |
| GET | `/options` | `SUPPLIER_VIEW` | Select options. |
| GET | `/{id}` | `SUPPLIER_VIEW` | Detail. |
| POST | `` | `SUPPLIER_MANAGE` | `{supplierName, mobile, address, state?, gstNumber?, email?, status?}`. |
| PUT | `/{id}` | `SUPPLIER_MANAGE` | Same body. |
| PUT | `/{id}/status` | `SUPPLIER_MANAGE` | `{status}`. No delete. |
| GET | `/{id}/purchases` | `SUPPLIER_VIEW` | Latest 20 purchases in accessible branches. |

---

## 21. TaxMasterController — `/api/taxes`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `TAX_VIEW` | `search?`, `status?`, `page`, `size`. |
| GET | `/options` | `TAX_VIEW` or `ITEM_VIEW` | Tax options for item forms. |
| GET | `/{id}` | `TAX_VIEW` | Detail. |
| POST | `` | `TAX_MANAGE` | `{taxName, description?, gstPercentage, taxType:"GST"\|"EXEMPT", status?}`. CGST = GST/2, SGST = remainder, IGST = GST. |
| PUT | `/{id}` | `TAX_MANAGE` | Same body; history keeps snapshots. |
| PUT | `/{id}/status` | `TAX_MANAGE` | `{status}`. |
| DELETE | `/{id}` | `TAX_MANAGE` | Blocked if items/purchase/sales lines reference it. |

---

## 22. TransferController — `/api/transfers`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `TRANSFER_VIEW` | `branchId?`, `status?`, `from?`, `to?`, `search?`, `page`, `size`. |
| GET | `/{id}` | `TRANSFER_VIEW` | Header + lines. |
| POST | `` | `TRANSFER_MANAGE` | `{fromBranchId, toBranchId, transferDate?, notes?, items:[{itemId, quantity, unit?}]}`. Status `PENDING`; no stock movement. |
| POST | `/{id}/approve` | `TRANSFER_APPROVE` | `PENDING → APPROVED`. |
| POST | `/{id}/dispatch` | `TRANSFER_MANAGE` | `APPROVED → DISPATCHED`; stock-out at source (transactional). |
| POST | `/{id}/receive` | `TRANSFER_MANAGE` | `DISPATCHED → RECEIVED`; stock-in at destination at source avg cost. |
| POST | `/{id}/reject` | `TRANSFER_APPROVE` | `PENDING`/`APPROVED → REJECTED`. |
| POST | `/{id}/cancel` | `TRANSFER_MANAGE` | Only before dispatch. |

---

## 23. UserController — `/api/users`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `` | `USER_VIEW` | `search?`, `status?`, `page`, `size`; store users only. |
| GET | `/{id}` | `USER_VIEW` | Detail with roles/branches. |
| POST | `` | `USER_MANAGE` | `{username, email, fullName, mobile?, password, roleId, branchIds[], status?}`. Password 8–72, hashed. |
| PUT | `/{id}` | `USER_MANAGE` | Update profile/role; blank password keeps the current hash. |
| POST | `/{id}/branches` | `USER_MANAGE` | `{branchIds:[]}`. |
| PUT | `/{id}/status` | `USER_MANAGE` | `{status}`; deactivation applies on the next request. |
| GET | `/roles/available` | `ROLE_VIEW` or `USER_MANAGE` | Roles assignable to users. |

Password hashes are never returned by any user endpoint.

---

## 24. PlatformAuditController — `/api/platform/audit-logs`

Class-level `@PreAuthorize("hasAuthority('PLATFORM_MANAGE')")`. Read-only; audit rows are never edited
or deleted by the API.

| Method | Path | Notes |
|---|---|---|
| GET | `` | `storeId?`, `action?`, `entityType?`, `username?`, `search?`, `from?`, `to?` (ISO-8601 instants), `page` (0), `size` (25, max 100). Newest first. |
| GET | `/options` | Distinct actions for the filter dropdown. |
| GET | `/entity-types` | Distinct entity types for the filter dropdown. |
| GET | `/export` | Same filters; returns `text/csv; charset=UTF-8` with `Content-Disposition: attachment`. No secrets are exported. |

Example:

```bash
curl -b cookies.txt "https://host/api/platform/audit-logs?action=PASSWORD_RESET&from=2026-10-01T00:00:00Z&page=0&size=25"
curl -b cookies.txt -o audit.csv "https://host/api/platform/audit-logs/export?username=owner"
```

---

## 25. SystemStatusController — `/api`

Public, unauthenticated operational endpoints; no credentials, host names or configuration.

| Method | Path | Notes |
|---|---|---|
| GET | `/health` | Runs `select 1`; `200 {"status":"UP","database":"UP","timestamp":"…"}` or `503` with `DOWN`. `no-store`. |
| GET | `/version` | `{name, version, artifact, buildTime, gitCommit, javaVersion}`. `no-store`. Intended for deployment identification. |

Neither endpoint is blocked by subscription expiry.

---

## 26. Permission → endpoint quick map

| Permission | Endpoint groups |
|---|---|
| `PLATFORM_MANAGE` | `/api/platform/**`, `/api/stores` (list/create/any store), subscription admin |
| `STORE_VIEW` / `STORE_MANAGE` | `/api/stores/current` |
| `BRANCH_VIEW` / `BRANCH_MANAGE` | `/api/branches` |
| `USER_VIEW` / `USER_MANAGE` | `/api/users` |
| `ROLE_VIEW` / `ROLE_MANAGE` | `/api/roles`, `/api/permissions` |
| `CATEGORY_VIEW` / `CATEGORY_MANAGE` | `/api/categories` |
| `ITEM_VIEW` / `ITEM_MANAGE` | `/api/items`, item import template/history; import confirm also needs `INVENTORY_ADJUST` |
| `TAX_VIEW` / `TAX_MANAGE` | `/api/taxes` |
| `SUPPLIER_VIEW` / `SUPPLIER_MANAGE` | `/api/suppliers` |
| `PURCHASE_VIEW` / `PURCHASE_MANAGE` | `/api/purchases` |
| `INVENTORY_VIEW` / `INVENTORY_ADJUST` | `/api/inventory`, `/api/reports/stock` (report uses `REPORT_VIEW`) |
| `TRANSFER_VIEW` / `TRANSFER_MANAGE` / `TRANSFER_APPROVE` | `/api/transfers` |
| `SALES_VIEW` / `SALES_MANAGE` | `/api/sales`, `/api/printing/jobs`, `/api/items/pos` |
| `REPORT_VIEW` | `/api/reports/*` |
| `PRINTER_VIEW` / `PRINTER_MANAGE` | `/api/printing/printers` |
| `DASHBOARD_VIEW` | `/api/dashboard` |

---

## 27. Endpoints deliberately absent

- No customer CRUD API (internal store administration only).
- No purchase-return/partial-return API (design plan in
  `KNOWN_ISSUES_AND_RECOMMENDATIONS.md`, K-16).
- No split-payment or offline-sync API.
- No label-PDF server endpoint: labels are rendered client-side (JsBarcode + browser print/PNG).
- No MFA/TOTP endpoints yet (K-26).
- No audit or print-job deletion endpoint: retention is a manual, approved operation (K-21).
