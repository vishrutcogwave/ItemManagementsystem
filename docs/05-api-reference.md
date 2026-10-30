# 15. API Reference

> **Documentation status.** Every endpoint below exists in the current controllers. Paths,
> methods, parameters, permissions and field names were read from
> `controller/*.java` and `dto/**/*.java`. No endpoint was invented; anything absent is
> listed explicitly in 15.18. Nothing was executed while writing this section.

---

## 15.1 Conventions

| Item | Value |
| --- | --- |
| Base path | `/api` (Vite proxies `/api` to `http://localhost:8080` in development) |
| Content type | `application/json` for requests and responses |
| Authentication | `Authorization: Bearer <jwt>` on every endpoint except the two public ones |
| Dates | `yyyy-MM-dd` (`LocalDate`) |
| Timestamps | ISO-8601 UTC (`Instant`), e.g. `2026-10-30T09:15:42.123Z` |
| Money | JSON number, 2 decimals in storage; 3 decimals for quantities |
| Nulls | Omitted from JSON (`spring.jackson.default-property-inclusion: non_null`) |
| Paging | `page` is 0-based; `size` is clamped per endpoint; responses use `PageResponse` |
| Status values | `ACTIVE` / `INACTIVE`; purchases `DRAFT`/`COMPLETED`/`CANCELLED`; sales `COMPLETED`/`CANCELLED`; transfers `DRAFT`/`PENDING`/`APPROVED`/`DISPATCHED`/`RECEIVED`/`REJECTED`/`CANCELLED` |
| Payment methods | `CASH`, `UPI`, `CARD`, `CREDIT` |
| Units | `LITER`, `ML`, `KG`, `GRAM`, `PIECE`, `PACKET`, `BOX`, `BOTTLE`, `DOZEN` |
| Success codes | `200 OK` for reads and most writes; `201 Created` only for `POST /api/auth/register-store`; deletes return `200` with an empty body |
| Auth failures | `401` missing/invalid/expired/revoked token; `403` authenticated but not permitted (or wrong branch/store) |

### Error envelope

```json
{
  "timestamp": "2026-10-30T09:15:42.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Please correct the highlighted fields",
  "fieldErrors": { "itemCode": "Item code is required" }
}
```

- `fieldErrors` appears only for Jakarta Bean Validation failures.
- A database constraint conflict returns `400` with
  `This change conflicts with existing data. Please check codes, names or connected records.`
- Unhandled errors return `500 Something went wrong. Please try again.` and are logged.

### Common authorization errors

| Message | Cause |
| --- | --- |
| `Authentication required` | no/invalid token on a protected path |
| `You do not have permission to perform this action` | missing permission |
| `You do not have access to this branch` | branch not assigned / not all-branches |
| `Platform administrators cannot access store business data` | platform token on a business endpoint |
| `Platform administrators cannot register a store through this endpoint` | not used; registration is public |

### PageResponse

```json
{ "content": [], "page": 0, "size": 10, "totalElements": 0, "totalPages": 0 }
```

---

## 15.2 Inventory of endpoints

| Module | Count | Section |
| --- | ---: | --- |
| Auth | 5 | 15.3 |
| Stores | 7 | 15.4 |
| Branches | 7 | 15.5 |
| Users | 7 | 15.6 |
| Roles and permissions | 4 | 15.7 |
| Categories | 6 | 15.8 |
| Tax master | 7 | 15.9 |
| Items and POS catalogue | 9 | 15.10 |
| Suppliers | 7 | 15.11 |
| Dashboard | 1 | 15.12 |
| Inventory | 5 | 15.13 |
| Purchases | 6 | 15.14 |
| Sales and POS checkout | 4 | 15.15 |
| Stock transfers | 7 | 15.16 |
| Reports | 8 | 15.17 |
| **Total** | **90** | |

All are **Implemented** unless a row says otherwise. Table names in each section are the
tables the operation reads or writes.

---

## 15.3 Auth

Public: `POST /api/auth/login`, `POST /api/auth/register-store`. The other three require a
valid token but no specific permission.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | public | authenticate a store or platform user |
| GET | `/api/auth/me` | bearer | current profile, no new token |
| POST | `/api/auth/refresh` | bearer | revoke current token and issue a new one |
| POST | `/api/auth/logout` | bearer | revoke current token |
| POST | `/api/auth/register-store` | public | provision a new store + owner |

### POST `/api/auth/login`

Request:

```json
{ "storeCode": "ACME01", "username": "asha", "password": "ChangeMe123!" }
```

Validation: all three required. `username` accepts a username or the account e-mail.
`storeCode` may be `PLATFORM`.

Response `200` — `AuthResponse` (see 15.19.1). Errors: `401` invalid credentials,
`403` inactive user/store, `403` no role.

Tables: `users`, `stores`, `roles`, `permissions`, `branches`, `audit_logs` (LOGIN).

### GET `/api/auth/me`

No body. Returns `AuthResponse` with `token: null`, `expiresIn` still reported.
Errors: `401` if the user no longer exists.

### POST `/api/auth/refresh`

No body; uses the bearer token. Blacklists the current `jti`, re-reads roles, permissions
and branches, returns a fresh `AuthResponse`. Errors: `401` for an invalid/revoked token.

### POST `/api/auth/logout`

No body. Returns `{"message":"Signed out"}`. Blacklists the current `jti`.

### POST `/api/auth/register-store`

Request `RegisterStoreRequest`:

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

Validation as in 15.19.2. Response `201`:

```json
{ "message": "Store registered successfully. You can now sign in with store code ACME01." }
```

Errors: `400` validation; `409` duplicate store code or username;
`400 PLATFORM is a reserved store code`.

Tables written: `stores`, `branches`, `tax_master` (5 slabs), `roles` (4),
`role_permissions`, `users`, `user_roles`, `user_branches`, `audit_logs`.

---

## 15.4 Stores

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/stores` | `PLATFORM_MANAGE` | list all stores (platform only in intent) |
| POST | `/api/stores` | `PLATFORM_MANAGE` | create a store on behalf of an owner |
| GET | `/api/stores/current` | `STORE_VIEW` | current tenant store |
| PUT | `/api/stores/current` | `STORE_MANAGE` | edit current store |
| GET | `/api/stores/{id}` | `PLATFORM_MANAGE` | any store |
| PUT | `/api/stores/{id}` | `PLATFORM_MANAGE` | edit any store |
| PUT | `/api/stores/{id}/status` | `PLATFORM_MANAGE` | activate/deactivate any store |

`GET /api/stores` params: `search`, `status`, `page` (0), `size` (10, max 100).
Response `PageResponse<StoreResponse>` (15.19.6).

`POST /api/stores` body = `RegisterStoreRequest`; response `StoreResponse`.

`PUT /api/stores/current` and `PUT /api/stores/{id}` body `UpdateStoreRequest`:

```json
{
  "storeName": "Acme Retail",
  "ownerName": "Asha Rao",
  "email": "asha@acme.example",
  "mobile": "9876543210",
  "address": "12 Market Road, Bengaluru 560001",
  "gstNumber": "29ABCDE1234F1Z5",
  "taxInclusive": false
}
```

`PUT /api/stores/{id}/status` body `{"status":"INACTIVE"}`.

Tables: `stores`, `branches`/`users` (counts), `audit_logs`. Errors: `404 Store not found`,
`400` validation, `403` missing permission.

> **Security note.** The platform endpoints rely on `PLATFORM_MANAGE` only; see the role
> escalation defect in `docs/04-security.md` 14.4.4. They are not reachable by ordinary
> store roles in the seeded matrix, because no seeded store role has that permission.

---

## 15.5 Branches

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/branches` | `BRANCH_VIEW` | branches accessible to the user |
| GET | `/api/branches/my-branches` | authenticated (no method permission) | same accessible list for selectors |
| GET | `/api/branches/all` | `BRANCH_MANAGE` | all store branches, including inactive |
| GET | `/api/branches/{id}` | `BRANCH_VIEW` | one branch (must be accessible) |
| POST | `/api/branches` | `BRANCH_MANAGE` | create |
| PUT | `/api/branches/{id}` | `BRANCH_MANAGE` | update |
| PUT | `/api/branches/{id}/status` | `BRANCH_MANAGE` | activate/deactivate |

`POST`/`PUT` body `BranchResponse.Request`:

```json
{
  "branchCode": "MAIN",
  "branchName": "Main Branch",
  "address": "12 Market Road, Bengaluru 560001",
  "contactNumber": "9876543210",
  "state": "Karnataka",
  "status": "ACTIVE"
}
```

Validation: code `^[A-Za-z0-9_-]{2,30}$`; name/address/contact required; name ≤ 150;
contact ≤ 20; state ≤ 80. Code is upper-cased; duplicate in store → `409`.

Response `BranchResponse` (15.19.4). `GET /api/branches/{id}` errors: `404`, `403`.

Tables: `branches`, `audit_logs`. No delete endpoint.

---

## 15.6 Users

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/users` | `USER_VIEW` | paged tenant users |
| GET | `/api/users/{id}` | `USER_VIEW` | one user |
| POST | `/api/users` | `USER_MANAGE` | create user |
| PUT | `/api/users/{id}` | `USER_MANAGE` | update profile, role, branches, status, password |
| POST | `/api/users/{id}/branches` | `USER_MANAGE` | replace branch assignment |
| PUT | `/api/users/{id}/status` | `USER_MANAGE` | activate/deactivate |
| GET | `/api/users/roles/available` | `ROLE_VIEW` or `USER_MANAGE` | roles for the selector |

`GET /api/users` params: `search`, `status`, `page`, `size` (max 100).

`POST` body `UserResponse.CreateRequest`:

```json
{
  "username": "cashier1",
  "email": "cashier1@acme.example",
  "password": "ChangeMe123!",
  "fullName": "Ravi Kumar",
  "mobile": "9880011223",
  "roleId": 4,
  "branchIds": [1, 2]
}
```

Validation: username `^[A-Za-z0-9._-]{3,40}$`; valid e-mail; password 8–72; full name ≤ 150;
role required; mobile ≤ 20. Duplicate username in store → `409`. A branch-level role
(`BRANCH_MANAGER`, `BRANCH_STAFF`) requires at least one branch:
`400 Assign at least one branch to branch-level users`. Branch ids from another store →
`400 One or more branches do not belong to this store`.

`PUT /api/users/{id}` body `UserResponse.UpdateRequest`:

```json
{
  "fullName": "Ravi Kumar",
  "email": "ravi@acme.example",
  "mobile": "9880011223",
  "roleId": 4,
  "branchIds": [1],
  "status": "ACTIVE",
  "password": "NewPassword123"
}
```

`password` is optional; blank/omitted keeps the current hash; < 8 → `400`.
Self-deactivation → `400 You cannot deactivate your own account`.

`POST /api/users/{id}/branches` body `{"branchIds":[1,2]}`. Empty list allowed except that
this endpoint passes no role, so the branch-level minimum is not enforced here.

`PUT /api/users/{id}/status` body `{"status":"INACTIVE"}`.

Response `UserResponse` (15.19.7). Tables: `users`, `user_roles`, `user_branches`,
`roles`, `branches`, `audit_logs`.

> **Security note.** There is no rank check on `roleId`: an actor with `USER_MANAGE` can
> assign a higher role, including `STORE_OWNER`. See 14.4.4.

---

## 15.7 Roles and permissions

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/roles` | `ROLE_VIEW` | roles of the tenant with permissions |
| POST | `/api/roles` | `ROLE_MANAGE` | create a custom role |
| PUT | `/api/roles/{id}` | `ROLE_MANAGE` | edit name/description/permissions |
| GET | `/api/permissions` | `ROLE_MANAGE` | the global permission catalogue |

`POST`/`PUT` body `RoleResponse.Request`:

```json
{
  "code": "ACCOUNTANT",
  "name": "Accountant",
  "description": "Read-only finance",
  "permissionCodes": ["DASHBOARD_VIEW", "PURCHASE_VIEW", "SALES_VIEW", "REPORT_VIEW"]
}
```

Validation: code `^[A-Za-z0-9_]{2,50}$` (upper-cased); name required ≤ 100; description ≤ 255.
Duplicate code in store → `409`. Unknown permission codes are silently ignored (only
existing codes are attached). System roles can be edited through this endpoint; `code` is
not changed on update.

Response: `RoleResponse` (15.19.8); list endpoints return arrays.
Tables: `roles`, `role_permissions`, `permissions`, `users` (count), `audit_logs`.

> **Security note.** `PLATFORM_MANAGE` is accepted here and is returned by
> `GET /api/permissions`. This is the escalation described in 14.4.4. Do not expose this
> endpoint to untrusted administrators until it is fixed.

---

## 15.8 Categories

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/categories` | `CATEGORY_VIEW` | paged list |
| GET | `/api/categories/options` | `CATEGORY_VIEW` | active categories for selectors |
| POST | `/api/categories` | `CATEGORY_MANAGE` | create |
| PUT | `/api/categories/{id}` | `CATEGORY_MANAGE` | update |
| DELETE | `/api/categories/{id}` | `CATEGORY_MANAGE` | delete only when unused |
| PUT | `/api/categories/{id}/status` | `CATEGORY_MANAGE` | activate/deactivate |

`GET` params: `search`, `status`, `page`, `size`.

Body (`CategoryRequest`):

```json
{ "categoryName": "Dairy", "description": "Milk, curd and chilled dairy", "status": "ACTIVE" }
```

Validation: name required ≤ 120; description ≤ 255. Duplicate name → `409`.
Delete with items → `400 This category is used by N item(s). Move those items to another category before deleting.`

Response `CategoryResponse` (15.19.9); delete returns empty. Tables: `categories`, `items`
(count and delete guard), `audit_logs`.

---

## 15.9 Tax master

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/taxes` | `TAX_VIEW` | paged list |
| GET | `/api/taxes/options` | `TAX_VIEW` or `ITEM_VIEW` | all taxes for selectors (includes inactive) |
| GET | `/api/taxes/{id}` | `TAX_VIEW` | one tax |
| POST | `/api/taxes` | `TAX_MANAGE` | create |
| PUT | `/api/taxes/{id}` | `TAX_MANAGE` | update |
| PUT | `/api/taxes/{id}/status` | `TAX_MANAGE` | activate/deactivate |
| DELETE | `/api/taxes/{id}` | `TAX_MANAGE` | delete only when unreferenced |

`GET` params: `search`, `status`, `page`, `size`.

Body (`TaxMasterRequest`):

```json
{
  "taxName": "GST 5%",
  "description": "Standard 5% GST slab",
  "gstPercentage": 5.00,
  "taxType": "GST",
  "status": "ACTIVE"
}
```

Validation: name required ≤ 120; GST 0–100; type `GST`/`EXEMPT`. The server derives CGST,
SGST and IGST; client-supplied splits do not exist. Duplicate name → `409`.

Delete with references → `400 This tax is used by N item(s), M purchase line(s) and K sales line(s). Deactivate it instead of deleting.`

Response `TaxMasterResponse` (15.19.10). Tables: `tax_master`, `items`,
`purchase_details`, `sales_details` (counts/guards), `audit_logs`.

---

## 15.10 Items and POS catalogue

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/items` | `ITEM_VIEW` | paged list |
| GET | `/api/items/options` | `ITEM_VIEW` | active items for selectors (optional `categoryId`) |
| GET | `/api/items/pos` | `SALES_VIEW` or `SALES_MANAGE` | POS catalogue for one accessible branch |
| GET | `/api/items/{id}` | `ITEM_VIEW` | one item |
| POST | `/api/items` | `ITEM_MANAGE` | create |
| PUT | `/api/items/{id}` | `ITEM_MANAGE` | update |
| PUT | `/api/items/{id}/status` | `ITEM_MANAGE` | activate/deactivate |
| GET | `/api/items/{id}/stock` | `ITEM_VIEW` | per-branch stock (accessible branches) |
| GET | `/api/items/{id}/transactions` | `ITEM_VIEW` or `INVENTORY_VIEW` | latest 200 ledger rows (optional `branchId`) |

`GET /api/items` params: `search`, `categoryId`, `status`, `page`, `size`.

Body (`ItemResponse.Request`):

```json
{
  "itemCode": "MILK-1L",
  "itemName": "Milk 1L",
  "categoryId": 3,
  "taxId": 2,
  "baseUnit": "LITER",
  "rate": 60.00,
  "minStockLevel": 10,
  "status": "ACTIVE"
}
```

Validation: code `^[A-Za-z0-9._-]{2,50}$` (upper-cased, unique per store → `409`);
name required ≤ 150; category required and in tenant; tax required and in tenant (inactive
tax rejected unless unchanged); unit required; rate ≥ 0.01; minimum stock ≥ 0.

`GET /api/items/pos` params: `branchId` (required), `categoryId`, `search`.
Response: array of `PosItemResponse` (15.19.12). Errors: `403` branch, `400` inactive
branch, `404` branch.

`GET /api/items/{id}/stock` response: array of `BranchStockResponse` (15.19.13).
`GET /api/items/{id}/transactions` response: array of `InventoryTransactionResponse`
(15.19.13), limited to 200 and filtered to accessible branches.

Tables: `items`, `categories`, `tax_master`, `branch_inventory`, `inventory_transactions`,
`branches`, `audit_logs`. No item delete endpoint.

---

## 15.11 Suppliers

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/suppliers` | `SUPPLIER_VIEW` | paged list |
| GET | `/api/suppliers/options` | `SUPPLIER_VIEW` | active suppliers |
| GET | `/api/suppliers/{id}` | `SUPPLIER_VIEW` | one supplier |
| POST | `/api/suppliers` | `SUPPLIER_MANAGE` | create |
| PUT | `/api/suppliers/{id}` | `SUPPLIER_MANAGE` | update |
| PUT | `/api/suppliers/{id}/status` | `SUPPLIER_MANAGE` | activate/deactivate |
| GET | `/api/suppliers/{id}/purchases` | `SUPPLIER_VIEW` | latest 20 purchases (accessible branches) |

`GET` params: `search`, `status`, `page`, `size`.

Body (`SupplierResponse.Request`):

```json
{
  "supplierName": "Demo Dairy Supplier",
  "mobile": "9988776655",
  "address": "Industrial Area, Bengaluru",
  "state": "Karnataka",
  "gstNumber": "29AAACD1234E1Z2",
  "email": "supplier@example.com",
  "status": "ACTIVE"
}
```

Validation: name/mobile/address required; mobile ≤ 20; state ≤ 80; GSTIN ≤ 30; valid e-mail.
No uniqueness constraint on supplier name.

Response `SupplierResponse` (15.19.11); purchases response
`List<SupplierPurchaseResponse>`. Tables: `suppliers`, `purchases`, `branches`,
`audit_logs`. No delete endpoint.

---

## 15.12 Dashboard

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/dashboard` | `DASHBOARD_VIEW` | today's KPIs, branch summaries, recent documents, low stock, top items |

Param: `branchId` optional (must be accessible). Response `DashboardResponse` (15.19.14).
Tables: `branches`, `branch_inventory`, `items`, `categories`, `suppliers`, `purchases`,
`sales`, `sales_details`, `inventory_transactions` (indirectly via low stock).

---

## 15.13 Inventory

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/inventory` | `INVENTORY_VIEW` | paged branch/item stock rows |
| GET | `/api/inventory/low-stock` | `INVENTORY_VIEW` | low-stock rows |
| GET | `/api/inventory/transactions` | `INVENTORY_VIEW` | paged ledger |
| POST | `/api/inventory/adjustments` | `INVENTORY_ADJUST` | stock in/out adjustment |
| PUT | `/api/inventory/{branchId}/items/{itemId}/pricing` | `INVENTORY_ADJUST` | branch minimum/purchase/selling prices |

`GET /api/inventory` params: `branchId`, `categoryId`, `search`, `lowOnly` (false),
`page` (0), `size` (20, max 200). Response `PageResponse<InventoryRowResponse>`.

`GET /api/inventory/low-stock` params: `branchId`, `limit` (50, clamped 1–500).
Response `List<InventoryRowResponse>`.

`GET /api/inventory/transactions` params: `branchId`, `itemId`, `search`, `page`, `size`
(max 100). Response `PageResponse<InventoryTransactionResponse>`.

`POST /api/inventory/adjustments` body (`StockAdjustmentRequest`):

```json
{
  "branchId": 1,
  "itemId": 5,
  "type": "OUT",
  "quantity": 2,
  "unit": "LITER",
  "reason": "Spillage during handling"
}
```

Validation: branch/item/type/quantity/reason required; type `IN|OUT`; quantity ≥ 0.001;
reason ≤ 255. Errors: `403` branch, `400` inactive branch/item, insufficient stock,
`404` item. Response `InventoryRowResponse`.

`PUT .../pricing` body (`InventoryPricingRequest`):

```json
{ "minimumStock": 10, "purchasePrice": 48.00, "sellingPrice": 60.00 }
```

All three required and ≥ 0. Response `InventoryRowResponse`. Note: this endpoint updates
the informational branch prices; it does **not** change `items.rate` or `avg_cost`.

Tables: `branch_inventory`, `inventory_transactions`, `stock_adjustments`, `items`,
`branches`, `categories`, `audit_logs`.

---

## 15.14 Purchases

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/purchases` | `PURCHASE_VIEW` | paged list |
| GET | `/api/purchases/{id}` | `PURCHASE_VIEW` | one purchase with lines |
| POST | `/api/purchases` | `PURCHASE_MANAGE` | create draft or completed purchase |
| PUT | `/api/purchases/{id}` | `PURCHASE_MANAGE` | edit a draft only |
| POST | `/api/purchases/{id}/complete` | `PURCHASE_MANAGE` | post stock and mark completed |
| POST | `/api/purchases/{id}/cancel` | `PURCHASE_MANAGE` | cancel (reverse stock if completed) |

`GET` params: `branchId`, `status`, `from`, `to`, `search`, `page`, `size`.
`search` matches purchase number and invoice number.

`POST`/`PUT` body (`PurchaseRequest`):

```json
{
  "branchId": 1,
  "supplierId": 2,
  "invoiceNumber": "INV-2026-001",
  "purchaseDate": "2026-10-30",
  "discount": 0,
  "paidAmount": 2520.00,
  "notes": "Weekly stock",
  "status": "COMPLETED",
  "items": [
    {
      "itemId": 5,
      "quantity": 50,
      "unit": "LITER",
      "purchaseRate": 48.00,
      "discount": 0
    }
  ]
}
```

Validation: branch, supplier, date, at least one line; quantity ≥ 0.001; rate ≥ 0; discounts
and paid ≥ 0; invoice ≤ 80; notes ≤ 500. `status` omitted → `COMPLETED`; `CANCELLED` at
create → `400`. Server-side checks: duplicate item, inactive item/branch/supplier, line
discount ≤ gross, header discount ≤ subtotal, paid ≤ grand, tax configured for every item.

Response `PurchaseResponse` (15.19.15). Errors: `400` any rule, `403` branch,
`404 Purchase not found`, `409` duplicate number (practically impossible).

Tables: `purchases`, `purchase_details`, `branch_inventory`, `inventory_transactions`,
`items`, `suppliers`, `branches`, `tax_master`, `audit_logs`.

`POST /{id}/complete` errors: `400 Only draft purchases can be completed`,
`400 Add at least one item before completing this purchase`, `403` branch, insufficient
stock never applies (stock-in). `POST /{id}/cancel` errors: already cancelled, completed
with insufficient stock to reverse, `400` for draft reversal not needed.

---

## 15.15 Sales and POS checkout

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/sales` | `SALES_VIEW` | paged list |
| GET | `/api/sales/{id}` | `SALES_VIEW` | one sale with lines |
| POST | `/api/sales` | `SALES_MANAGE` | create a completed sale (normal or POS) |
| POST | `/api/sales/{id}/cancel` | `SALES_MANAGE` | cancel and restore stock |

`GET` params: `branchId`, `status`, `from`, `to`, `search`, `page`, `size`.
`search` matches sales number, customer name and mobile.

`POST` body (`SaleRequest`):

```json
{
  "branchId": 1,
  "salesDate": "2026-10-30",
  "customerName": "Walk-in Customer",
  "customerMobile": null,
  "customerState": "Karnataka",
  "discount": 0,
  "paymentMethod": "CASH",
  "paidAmount": 735.00,
  "pos": false,
  "items": [
    {
      "itemId": 5,
      "quantity": 10,
      "unit": "LITER",
      "sellingRate": 70.00,
      "discount": 0
    }
  ]
}
```

Validation: branch and at least one line required; quantity ≥ 0.001; `sellingRate`
**required even for POS** (the server ignores it when `pos` is true); discounts and paid
≥ 0; name ≤ 150; mobile ≤ 20; state ≤ 80; payment method enum or omitted (default CASH).

Server rules:

- duplicate item → `400`;
- inactive/missing item → `400`; inactive/missing branch → `400`/`403`;
- no tax on item → `400 Tax is not configured for <item>`;
- line discount > gross or header discount > subtotal → `400`;
- paid > grand → `400 Paid amount cannot exceed the grand total`;
- insufficient stock → `400 Insufficient stock for <item>. Available: X, required: Y`;
- `pos: true` → the server posts `items.rate` regardless of `sellingRate`.

Response `SaleResponse` (15.19.16). Tables: `sales`, `sales_details`,
`branch_inventory`, `inventory_transactions`, `items`, `branches`, `tax_master`,
`audit_logs`.

`POST /{id}/cancel` errors: `400 This sale is already cancelled`, `403` branch,
`404 Sale not found`. Response is the cancelled `SaleResponse`.

---

## 15.16 Stock transfers

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/api/transfers` | `TRANSFER_VIEW` | paged list |
| GET | `/api/transfers/{id}` | `TRANSFER_VIEW` | one transfer (source **or** destination access) |
| POST | `/api/transfers` | `TRANSFER_MANAGE` | create pending transfer |
| POST | `/api/transfers/{id}/approve` | `TRANSFER_APPROVE` | PENDING → APPROVED |
| POST | `/api/transfers/{id}/dispatch` | `TRANSFER_MANAGE` | APPROVED → DISPATCHED, stock out at source |
| POST | `/api/transfers/{id}/receive` | `TRANSFER_MANAGE` | DISPATCHED → RECEIVED, stock in at destination |
| POST | `/api/transfers/{id}/reject` | `TRANSFER_APPROVE` | PENDING/APPROVED → REJECTED |
| POST | `/api/transfers/{id}/cancel` | `TRANSFER_MANAGE` | PENDING/APPROVED → CANCELLED |

`GET` params: `branchId`, `status`, `from`, `to`, `search`, `page`, `size`.
`search` matches transfer number and notes.

`POST` body (`TransferRequest`):

```json
{
  "fromBranchId": 1,
  "toBranchId": 2,
  "transferDate": "2026-10-30",
  "notes": "Rebalance stock",
  "items": [
    { "itemId": 5, "quantity": 20, "unit": "LITER" }
  ]
}
```

Validation: both branches required and different; at least one line; quantity ≥ 0.001;
notes ≤ 500. Errors: `400` same branch, inactive branch/item, duplicate item,
`403 You do not have access to this branch` on source (create/dispatch/cancel),
`404 Stock transfer not found`.

State errors: `400 Only pending transfers can be approved`,
`400 Only approved transfers can be dispatched`,
`400 Only dispatched transfers can be received`,
`400 Only pending or approved transfers can be rejected`,
`400 A received transfer cannot be cancelled`,
`400 A dispatched transfer must be received or resolved by the destination branch`,
`400 This transfer is already cancelled`.

Response `TransferResponse` (15.19.17). Tables: `stock_transfers`,
`stock_transfer_details`, `branch_inventory`, `inventory_transactions`, `branches`,
`items`, `audit_logs`.

---

## 15.17 Reports

All require `REPORT_VIEW`. Dates default to first of current month and today; `from > to`
→ `400 From date cannot be after to date`. Only `COMPLETED` purchases/sales are included.
Scope is always the JWT tenant and accessible branches.

| Method | Path | Params | Response |
| --- | --- | --- | --- |
| GET | `/api/reports/purchases` | `from`, `to`, `branchId`, `categoryId`, `itemId`, `search` | `PurchaseReportResponse` |
| GET | `/api/reports/sales` | same | `SalesReportResponse` |
| GET | `/api/reports/gst` | `from`, `to`, `branchId`, `taxRate` | `GstReportResponse` |
| GET | `/api/reports/stock` | `from`, `to`, `branchId`, `categoryId`, `itemId`, `search` | `StockReportResponse` |
| GET | `/api/reports/low-stock` | `branchId`, `categoryId`, `search` | `LowStockReportResponse` |
| GET | `/api/reports/branches` | `from`, `to`, `branchId` | `BranchReportResponse` |
| GET | `/api/reports/store` | `from`, `to`, `branchId` | `StoreReportResponse` |
| GET | `/api/reports/profit` | `from`, `to`, `branchId`, `categoryId`, `itemId`, `search` | `ProfitReportResponse` |

`taxRate` for GST is an exact match on the line's snapshot percentage, e.g. `5.00`.

Schema field lists: 15.19.18. Tables: see section 9.15 in `docs/03-business-flows.md`.

---

## 15.18 What does not exist

No endpoint exists for the following. Do not assume a hidden or alternate path.

| Missing capability | Consequence |
| --- | --- |
| Payments / settlement / receipts | only `paid_amount` and `balance_amount` on the document |
| Purchase or sales returns / refunds / credit notes | cancel the whole document instead |
| Opening stock entry | only demo/SQL seeds; no API |
| Item, branch, supplier, user, role delete | deactivate instead (category/tax delete exist with guards) |
| Audit log read/export | query PostgreSQL directly |
| Self-service password change / reset | admin edits the user |
| Store feature flags, tax-inclusive toggle after creation | `PUT /api/stores/current` accepts `taxInclusive` (so it **is** changeable by an owner/admin, despite the UI not exposing it) |
| POS session, cash drawer, held bills, split tender, change | not modeled |
| Purchase order / approval workflow beyond draft | not modeled |
| Stock transfer in-transit resolution | dispatched must be received while stock remains; no loss flow |
| Export endpoints | exports are generated client-side (XLSX/PDF) |
| Health/metrics | Actuator not included |
| Idempotency keys | double submit creates duplicates |
| Rate limiting / account lockout | none |

---

## 15.19 Schema catalogue

Field order matches the Java record; JSON uses these exact names. `null` fields are omitted.

### 15.19.1 AuthResponse

```text
token, tokenType, expiresIn,
user{id, username, fullName, email, mobile},
store{id, storeCode, storeName, ownerName, email, mobile, address, gstNumber, taxInclusive, status} | null,
branches[{id, branchCode, branchName, state}],
roles[], permissions[], allBranches, platformAdmin
```

### 15.19.2 RegisterStoreRequest

All fields listed in 15.3; required: storeCode, storeName, ownerName, email, mobile,
address, username, password. Optional: gstNumber, state, branchName.

### 15.19.3 LoginRequest

`storeCode`, `username`, `password` — all required.

### 15.19.4 BranchResponse

```text
id, storeId, branchCode, branchName, address, contactNumber, state, status, createdAt
```

### 15.19.5 Category

`CategoryRequest`: `categoryName`, `description`, `status`.
`CategoryResponse`: `id, categoryName, description, status, itemCount, createdAt`.

### 15.19.6 StoreResponse

```text
id, storeCode, storeName, ownerName, email, mobile, address, gstNumber,
taxInclusive, status, createdAt, branchCount, userCount
```

### 15.19.7 UserResponse

```text
id, username, fullName, email, mobile, status,
roles[{id, code, name}], branches[{id, branchCode, branchName}], createdAt
```

### 15.19.8 RoleResponse

```text
id, code, name, description, system,
permissions[{id, code, name, description}], userCount
```

### 15.19.9 ItemResponse

```text
id, itemCode, itemName, categoryId, categoryName,
taxId, taxName, gstPercentage, taxType,
baseUnit, rate, minStockLevel, status, createdAt
```

### 15.19.10 TaxMasterResponse

```text
id, storeId, taxName, description,
gstPercentage, cgstPercentage, sgstPercentage, igstPercentage,
taxType, status, itemCount, createdAt, updatedAt
```

### 15.19.11 Supplier

`SupplierResponse`: `id, supplierName, mobile, address, state, gstNumber, email, status,
createdAt`.
`SupplierPurchaseResponse`: `id, purchaseNumber, purchaseDate, branchName, grandTotal,
status, createdAt`.

### 15.19.12 PosItemResponse

```text
id, itemCode, itemName, categoryId, categoryName, unit,
rate, availableStock, lowStock,
taxId, taxName, gstPercentage, taxType, taxInclusive
```

### 15.19.13 Inventory

`InventoryRowResponse`:

```text
id, branchId, branchCode, branchName,
itemId, itemCode, itemName, categoryId, categoryName, unit,
currentStock, minimumStock, purchasePrice, sellingPrice, stockValue, lowStock
```

`InventoryTransactionResponse`:

```text
id, branchId, branchName, itemId, itemName,
transactionType, referenceType, referenceNumber,
quantityIn, quantityOut, balanceAfter, unit, notes, transactionDate
```

`BranchStockResponse`:

```text
branchId, branchCode, branchName, currentStock, minimumStock,
purchasePrice, sellingPrice, unit, lowStock
```

### 15.19.14 DashboardResponse

```text
summary{totalBranches, totalCategories, totalItems, totalSuppliers,
        todayPurchaseAmount, todaySalesAmount, totalLowStockItems, totalStockValue},
branchSummary[{branchId, branchName, salesToday, purchasesToday, stockValue, lowStockItems}],
recentPurchases[{id, number, partyName, branchName, date, amount, status}],
recentSales[...same shape...],
lowStock[InventoryRowResponse],
topSellingItems[{itemId, itemName, unit, quantitySold, revenue}],
stockSummary{totalQuantity, totalValue, itemsInStock, itemsOutOfStock}
```

### 15.19.15 PurchaseResponse

```text
id, purchaseNumber, invoiceNumber, purchaseDate, branchId, branchName,
supplierId, supplierName, subtotal, discount, taxableAmount,
cgstAmount, sgstAmount, igstAmount, tax, grandTotal,
paidAmount, balanceAmount, notes, status, createdAt,
items[{id, itemId, itemCode, itemName, quantity, unit, baseQuantity,
       purchaseRate, discount, taxId, taxName,
       gstPercentage, cgstPercentage, sgstPercentage, igstPercentage,
       taxableAmount, cgstAmount, sgstAmount, igstAmount, tax, totalAmount}]
```

### 15.19.16 SaleResponse

```text
id, salesNumber, salesDate, branchId, branchName,
customerName, customerMobile, customerState,
subtotal, discount, taxableAmount, cgstAmount, sgstAmount, igstAmount, tax, grandTotal,
paidAmount, balanceAmount, paymentMethod, status, createdAt,
items[{id, itemId, itemCode, itemName, quantity, unit, baseQuantity,
       sellingRate, costRate, discount, taxId, taxName,
       gstPercentage, cgstPercentage, sgstPercentage, igstPercentage,
       taxableAmount, cgstAmount, sgstAmount, igstAmount, tax, totalAmount}]
```

### 15.19.17 TransferResponse

```text
id, transferNumber, fromBranchId, fromBranchName, toBranchId, toBranchName,
transferDate, status, notes, createdAt,
items[{id, itemId, itemCode, itemName, quantity, unit, baseQuantity}]
```

### 15.19.18 Reports

`PurchaseReportResponse`: `rows[], totalBaseQuantity, totalAmount, entries`
`Row`: `purchaseNumber, purchaseDate, supplierName, branchName, itemCode, itemName,
quantity, unit, purchaseRate, totalAmount`

`SalesReportResponse`: `rows[], totalBaseQuantity, totalAmount, entries`
`Row`: `salesNumber, salesDate, branchName, customerName, itemCode, itemName,
quantity, unit, sellingRate, totalAmount`

`GstReportResponse`: `rows[], totals`
`Row`: `branchId, branchName, taxName, gstPercentage, cgstPercentage, sgstPercentage,
igstPercentage, taxableAmount, cgstAmount, sgstAmount, igstAmount, taxAmount, invoiceCount`
`Totals`: `taxableAmount, cgstAmount, sgstAmount, igstAmount, taxAmount, invoiceCount`

`StockReportResponse`: `rows[], totalCurrentStock, totalStockValue`
`Row`: `itemCode, itemName, categoryName, branchName, openingStock, purchased, sold,
transferIn, transferOut, adjustmentIn, adjustmentOut, currentStock, unit`

`LowStockReportResponse`: `rows[InventoryRowResponse], total`

`ProfitReportResponse`: `totalSales, costOfGoodsSold, grossProfit, byItem[], byBranch[]`
`ItemProfit`: `itemId, itemName, quantitySold, revenue, cogs, profit`
`BranchProfit`: `branchId, branchName, revenue, cogs, profit`

`BranchReportResponse`: `rows[], totals`
`Row`: `branchId, branchName, totalPurchases, totalSales, itemsSold, stockValue,
lowStockItems`
`Totals`: `totalPurchases, totalSales, itemsSold, stockValue, lowStockItems`

`StoreReportResponse`: `totalPurchaseAmount, totalSalesAmount, totalItemsSold,
totalInventoryQuantity, totalInventoryValue, totalLowStockItems, branches[]`

### 15.19.19 Status and message

`StatusRequest`: `{"status":"ACTIVE"|"INACTIVE"}`.
`MessageResponse`: `{"message":"..."}`.

---

## Cross-references

- Behaviour and arithmetic behind each write: `docs/03-business-flows.md` sections 9–13.
- Permissions, token and escalation findings: `docs/04-security.md` section 14.
- Tables and columns returned here: `docs/02-database.md` section 6.
- Missing features and fixes: `docs/08-known-issues.md` section 24.
