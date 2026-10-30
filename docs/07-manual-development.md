# 22–23. Manual Development Guide and Testing

This part describes how to re-implement, extend or review each module manually. The application is already implemented; nothing here is a request to change it. Every class, route and table named below exists in the repository unless it is explicitly marked as a recommendation or a gap.

> **Schema changes are migration-first.** Never edit `V1__init.sql`, `V2__tax_master.sql` or `V3__item_rate.sql` on a database that has already run them. Add a new `V4__*.sql`, update the entity, keep `ddl-auto=validate`, and update `docs/sql/02`–`05` if the manual path must stay in sync. Do not execute a migration as part of documentation work.

---

## 22. Manual Development Guide

### 22.1 Layering and rules

Every backend module follows the same path:

```text
entity (JPA) → repository (Spring Data) → DTO (Java record)
             → service (transaction + rules) → controller (@RestController + @PreAuthorize)
             → frontend page (React) → src/lib/api.ts call
```

Non-negotiable rules:

1. **Tenant identity comes from the token, never from the request.** Use `TenantContext.storeId()` for store business data; use `TenantContext.requireBranch(branchId)` before touching a branch-scoped resource. Never accept `storeId` in a request body.
2. **Every repository query is store-scoped.** Prefer `findByIdAndStoreId`, `findByStoreId...` or JPQL with `storeId = :storeId`. A lookup by bare `id` is a cross-tenant defect.
3. **Authorization is server-side.** `@PreAuthorize("hasAuthority('...')")` on the controller; the React guard is only a UX convenience. Branch access is a second check, not a replacement for the permission check.
4. **Money and quantity are `BigDecimal`.** Money is stored at 2 decimals, quantities at 3, average cost at 4. Do not use `double` for money.
5. **Writes are transactional.** Purchase/sale/transfer posting, stock movement and document status change happen in one transaction. Do not update inventory when validation fails.
6. **History is immutable.** Completed purchases, sales and received transfers are cancelled/reversed, never deleted. Line rates and GST snapshots are copied at posting time.
7. **Audit sensitive actions** through `AuditService.log(action, entityType, entityId, details)` after the business write. Audit is best-effort and must not mask the business result.
8. **Errors use the shared contract.** Throw `ApiException.badRequest/notFound/forbidden/conflict`; `GlobalExceptionHandler` produces the JSON envelope. Validation messages are user-facing.
9. **Add tests for invariants.** Any change to stock, rates, GST, tenancy or permissions needs an integration test in `SystemInvariantIntegrationTest` or a new test class using the same embedded PostgreSQL setup.

### 22.2 Shared building blocks

| Component | Location | Responsibility |
|---|---|---|
| `AuthUser` | `security/AuthUser.java` | Authenticated principal: user id, store id, username, roles, permissions, branch ids, `allBranches`, `canAccessBranch` |
| `TenantContext` | `security/TenantContext.java` | `user()`, `storeId()` (rejects platform users), `requireBranch()`, `canAccessAllBranches()` |
| `JwtService` | `security/JwtService.java` | Issue/parse tokens; claims include `storeId`, roles, permissions, branch ids |
| `JwtAuthFilter` | `security/JwtAuthFilter.java` | Validate bearer token, reload user/store active state, reject store mismatch |
| `TokenBlacklistService` | `security/TokenBlacklistService.java` | In-memory `jti` blacklist for logout/refresh |
| `ApiException` / `GlobalExceptionHandler` | `exception/` | Consistent status codes and field errors |
| `PageResponse<T>` | `util/PageResponse.java` | Paged JSON contract |
| `GstCalculator` | `util/GstCalculator.java` | Exclusive/inclusive GST, intra/inter-state split |
| `UnitConverter` | `util/UnitConverter.java` | Convert L/ML, KG/GRAM, PIECE/DOZEN to the item base unit; rejects cross-family conversions |
| `NumberGenerator` | `service/NumberGenerator.java` | `PUR`/`SAL`/`TRF`/`ADJ` numbers from PostgreSQL sequences starting at 1001 |
| `AuditService` | `service/AuditService.java` | Append-only audit rows |

### 22.3 Recommended implementation order

Build in dependency order; each step should compile, migrate and be testable before the next.

1. Database migrations, entities, enums and repositories.
2. Security: `AuthUser`, JWT, filter, `TenantContext`, `SecurityConfig`.
3. Store provisioning and auth (public registration, login, refresh, logout).
4. Platform stores, branches, users, roles/permissions.
5. Categories, tax master, items, suppliers.
6. Inventory core: `branch_inventory`, ledger, locking, stock in/out.
7. Purchases.
8. Sales and POS checkout.
9. Transfers.
10. Dashboard and reports.
11. Frontend shell, auth store, API client, layout, navigation, guards.
12. Frontend modules in the same order; test each against the live API.
13. Exports, responsive pass, security regression suite.

The frontend can be built in parallel only after the API contract for a module is stable. Do not build static screens with local mock data: the SRS requires every operation to call the Spring Boot API.

---

### 22.4 Authentication and store registration

| Aspect | Actual implementation |
|---|---|
| Entities / tables | `User`/`users`, `Store`/`stores`, `Role`/`roles`, `Permission`/`permissions`, `Branch`/`branches`, join tables `user_roles`, `user_branches`, `role_permissions` |
| Repositories | `UserRepository`, `StoreRepository`, `RoleRepository`, `PermissionRepository`, `BranchRepository` |
| DTOs | `LoginRequest`, `RegisterStoreRequest`, `AuthResponse`, `MessageResponse` |
| Services | `AuthService`, `StoreProvisioningService`, `JwtService`, `TokenBlacklistService` |
| Controller | `AuthController` |
| Endpoints | `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `POST /api/auth/register-store` (see 15.3) |
| Frontend | `src/pages/LoginPage.tsx`, `src/pages/RegisterPage.tsx`, `src/store/auth.ts`, `src/lib/api.ts` |
| Permissions | Public for login/register; the other three require a valid token but no specific permission |

Implementation steps:

1. Resolve the user by `(storeCode, username-or-email)`; `PLATFORM` resolves a platform user (`store_id IS NULL`). Use the same `401 Invalid store code, username or password` for every failure so account existence is not leaked.
2. Reject inactive user or inactive store with `403`. Reject a user with no role.
3. Verify the password with `BCryptPasswordEncoder` (strength 10). Never compare hashes manually.
4. Build the JWT through `JwtService`. Current claims: `jti`, `sub`, `storeId`, `username`, `fullName`, `email`, `mobile`, `storeCode`, `storeName`, `roles`, `permissions`, `branchIds`, `allBranches`, `iat`, `exp`. Expiry is `app.jwt.expiration-ms` (default 43,200,000 ms = 12 h).
5. Derive `allBranches` for `STORE_OWNER` and `STORE_ADMIN`; branch roles receive explicit `user_branches` ids.
6. Audit `LOGIN`; do not log passwords or tokens.
7. `register-store` must provision atomically: store → MAIN branch → 5 GST slabs → 4 store roles + permission grants → owner user → `user_roles`/`user_branches` → audit. `StoreProvisioningService.createStore` is the single transaction boundary. `PLATFORM` is a reserved store code.
8. `refresh` blacklists the current `jti` and re-reads roles, permissions and branches, so a refresh can pick up changes; `logout` blacklists the `jti`. The blacklist is in memory and is lost on restart.

Testing: missing/invalid tokens (`401`), wrong password (`401`), inactive user/store (`403`), platform login, refresh rotates the token, logout invalidates the old token, registration creates exactly one MAIN branch and the standard roles/tax slabs, duplicate store code/username (`409`).

> **Known limitation:** `JwtAuthFilter` reloads the user and store active state but does **not** re-read roles, permissions or branch assignments. Changes to those apply on the next login/refresh; deactivation applies on the next request. See section 24.

---

### 22.5 Stores (platform and current tenant)

| Aspect | Actual implementation |
|---|---|
| Entity / table | `Store`/`stores` |
| Repository | `StoreRepository` |
| DTOs | `StoreResponse`, `UpdateStoreRequest`, `RegisterStoreRequest`, `StatusRequest` |
| Service | `StoreService` |
| Controller | `StoreController` |
| Endpoints | `GET/POST /api/stores`, `GET/PUT /api/stores/current`, `GET/PUT /api/stores/{id}`, `PUT /api/stores/{id}/status` |
| Frontend | `src/pages/PlatformStoresPage.tsx`, `src/pages/settings/StoreSettings.tsx` |
| Permissions | Platform endpoints `PLATFORM_MANAGE`; current store `STORE_VIEW` / `STORE_MANAGE` |

Rules:

- Platform endpoints are intended for platform users, but they currently check only `PLATFORM_MANAGE`. That permission can be granted to a store role through `POST /api/roles`; this is the escalation defect in 14.4.4. Fix by also requiring `storeId == null` or by forbidding `PLATFORM_MANAGE` in store roles.
- `GET /api/stores/current` and `PUT /api/stores/current` must use `TenantContext.storeId()`. Platform users cannot access them.
- `UpdateStoreRequest` includes `taxInclusive`. Changing it affects only future calculations; historical line snapshots do not change.
- Store status `INACTIVE` blocks login; it does not delete data.
- No delete endpoint; deactivate instead.

Testing: platform can list/update stores; a store owner cannot reach platform endpoints in the seeded matrix; `current` never returns another tenant; invalid status/payload returns `400`; audit rows are written.

---

### 22.6 Branches

| Aspect | Actual implementation |
|---|---|
| Entity / table | `Branch`/`branches`; `user_branches` |
| Repository | `BranchRepository` |
| DTO | `BranchResponse` (including nested `Request`) |
| Service | `BranchService` |
| Controller | `BranchController` |
| Endpoints | `GET /api/branches`, `GET /api/branches/my-branches`, `GET /api/branches/all`, `GET/POST /api/branches`, `PUT /api/branches/{id}`, `PUT /api/branches/{id}/status` |
| Frontend | `src/pages/settings/BranchesSettings.tsx`; branch selectors across POS, purchases, sales, inventory, transfers, reports |
| Permissions | `BRANCH_VIEW` / `BRANCH_MANAGE` |

Rules:

- `GET /api/branches` and `/my-branches` return only accessible branches. `GET /api/branches/all` requires `BRANCH_MANAGE` and returns active and inactive branches.
- `GET /api/branches/{id}` checks accessibility, not just existence; return `404` or `403` consistently to avoid leaking ids.
- `branchCode` is upper-cased, 2–30 characters, unique per store. There is no delete; deactivate.
- `branches.state` is used as the place of supply for GST: compare it with `suppliers.state` (purchase) or `sales.customer_state` (sale). Missing state falls back to intra-state.
- A new store gets a `MAIN` branch during provisioning. Do not create a second MAIN by hand; the code only guarantees uniqueness, not the semantic role.
- Branch assignment changes affect transaction access only after the token is refreshed/logged in again (stale claims), except that deactivation of the user/store is immediate.

Testing: owner sees all branches; branch staff sees only assigned; cross-store branch id returns `403/404`; inactive branch cannot be used in a purchase/sale/transfer; duplicate code `409`; `/all` requires `BRANCH_MANAGE`.

---

### 22.7 Users

| Aspect | Actual implementation |
|---|---|
| Entities / tables | `User`/`users`, `user_roles`, `user_branches` |
| Repository | `UserRepository`, `RoleRepository`, `BranchRepository` |
| DTOs | `UserResponse` with nested `CreateRequest`/`UpdateRequest`; `StatusRequest` |
| Service | `UserService` |
| Controller | `UserController` |
| Endpoints | `GET /api/users`, `GET /api/users/{id}`, `POST /api/users`, `PUT /api/users/{id}`, `POST /api/users/{id}/branches`, `PUT /api/users/{id}/status`, `GET /api/users/roles/available` |
| Frontend | `src/pages/settings/UsersSettings.tsx` |
| Permissions | `USER_VIEW` / `USER_MANAGE`; role selector also `ROLE_VIEW` |

Rules:

- Usernames are unique per store (case-insensitive); e-mail is validated. Password length is 8–72 (BCrypt limit); hash with BCrypt, never return it.
- The service currently attaches **one role per user**. The join table could represent more, but do not build UI/API assumptions around multiple roles without changing the service and tests deliberately.
- Branch-level roles (`BRANCH_MANAGER`, `BRANCH_STAFF`) require at least one branch on create/update. The separate branch-assignment endpoint does not receive a role and therefore does not enforce that minimum; fix if you rely on it.
- Branch ids must belong to the tenant. Reject with `400 One or more branches do not belong to this store`.
- Prevent self-deactivation (`400 You cannot deactivate your own account`).
- `PUT` with a blank/omitted password keeps the current hash; a supplied password is re-hashed.
- There is no self-service password change/reset and no rank check on `roleId`; an actor with `USER_MANAGE` can assign `STORE_OWNER`. See 14.4.4.
- Password change does not invalidate existing JWTs; only deactivation does.

Testing: create with branch role and no branch (`400`), branch from another store (`400`), duplicate username (`409`), update without password keeps login working, self-deactivation (`400`), staff cannot list users without `USER_VIEW`, role escalation is a known failing case to fix.

---

### 22.8 Roles and permissions

| Aspect | Actual implementation |
|---|---|
| Entities / tables | `Role`/`roles`, `Permission`/`permissions`, `role_permissions` |
| Repositories | `RoleRepository`, `PermissionRepository` |
| DTO | `RoleResponse` with nested `Request` and `PermissionResponse` |
| Service methods | `UserService.roles()`, `createRole()`, `updateRole()`, `permissions()` (the role controller delegates here; there is no separate `RoleService`) |
| Controller | `RoleController` |
| Endpoints | `GET /api/roles`, `POST /api/roles`, `PUT /api/roles/{id}`, `GET /api/permissions` |
| Frontend | `src/pages/settings/RolesSettings.tsx` |
| Permissions | `ROLE_VIEW` for listing; `ROLE_MANAGE` for create/update and the permission catalogue |

Rules:

- Store roles have `store_id`; `PLATFORM_ADMIN` has `store_id IS NULL`. A store role lookup must include the tenant.
- The catalogue currently has 28 codes (26 from V1 plus `TAX_VIEW`/`TAX_MANAGE` from V2). Unknown codes in a request are silently ignored; validate and reject them if you want stricter behavior.
- `roles.code` is upper-cased and unique per store via the functional unique index `uq_roles_store_code`.
- System roles can be edited (name/description/permissions) through the endpoint; `code` is not changed on update. This means an owner can accidentally remove their own `ROLE_MANAGE`; add a guard if required.
- `PLATFORM_MANAGE` is accepted and listed by the catalogue. This is the second half of the cross-tenant escalation: a store role with `ROLE_MANAGE` can grant it, then call `/api/stores`. Fix before exposing role management to untrusted users.
- There is no rank model. `STORE_ADMIN` with `USER_MANAGE` can assign `STORE_OWNER` to itself.

Testing: create custom role, duplicate code (`409`), unknown permission ignored (current) or rejected (recommended), store role cannot see another store's roles, system role update, permission catalogue requires `ROLE_MANAGE`, escalation regression after fix.

---

### 22.9 Categories

| Aspect | Actual implementation |
|---|---|
| Entity / table | `Category`/`categories` |
| Repository | `CategoryRepository` |
| DTOs | `CategoryRequest`, `CategoryResponse` |
| Service | `CategoryService` |
| Controller | `CategoryController` |
| Endpoints | `GET /api/categories`, `GET /api/categories/options`, `POST /api/categories`, `PUT /api/categories/{id}`, `DELETE /api/categories/{id}`, `PUT /api/categories/{id}/status` |
| Frontend | `src/pages/CategoriesPage.tsx`; category selectors in items, inventory, reports |
| Permissions | `CATEGORY_VIEW` / `CATEGORY_MANAGE` |

Rules:

- Names are unique per store (case-insensitive) and required; description ≤ 255.
- `options` returns active categories only.
- Delete is allowed only when no item references the category; otherwise `400` with the count. Prefer deactivation for history.
- Category status does not cascade to items; an inactive category's items remain. Decide and document the intended behavior before changing it.

Testing: duplicate name (`409`), delete used category (`400`), delete unused category (`200`), tenant isolation, options exclude inactive, audit.

---

### 22.10 Tax master and GST

| Aspect | Actual implementation |
|---|---|
| Entity / table | `TaxMaster`/`tax_master`; `items.tax_id`; tax snapshot columns on `purchase_details` and `sales_details` |
| Repository | `TaxMasterRepository` |
| DTOs | `TaxMasterRequest`, `TaxMasterResponse` |
| Service | `TaxMasterService` |
| Controller | `TaxMasterController` |
| Endpoints | `GET /api/taxes`, `GET /api/taxes/options`, `GET /api/taxes/{id}`, `POST /api/taxes`, `PUT /api/taxes/{id}`, `PUT /api/taxes/{id}/status`, `DELETE /api/taxes/{id}` |
| Frontend | `src/pages/TaxMasterPage.tsx`; item form and POS use tax options; `src/lib/tax.ts` |
| Permissions | `TAX_VIEW` for reads; `TAX_MANAGE` for writes; item selectors may use `ITEM_VIEW` on `/options` |

Rules:

- The request supplies only `gstPercentage` and `taxType`; the server derives `cgstPercentage = gst/2`, `sgstPercentage = gst - cgst`, `igstPercentage = gst`. Do not accept client splits.
- `GST` must be 0–100; `EXEMPT` must be zero-rated. `tax_name` is unique per store.
- Items require a tax; missing tax blocks purchase/sale (`400 Tax is not configured for <item>`).
- GST calculation (`GstCalculator`):
  - exclusive: `net = gross − discount`, `tax = net × gst/100`, `total = net + tax`;
  - inclusive: `taxable = net / (1 + gst/100)` with an 8-decimal divisor, `tax = net − taxable`, `total = net`;
  - intra-state: `CGST = tax/2` HALF_UP, `SGST = tax − CGST`; inter-state: `IGST = tax`;
  - missing/mismatched state → intra-state.
- Posting copies tax id/name, percentages and amounts to every line. Later tax-master edits must not change history or reports.
- Delete is blocked when items, purchase lines or sales lines reference the slab; deactivate instead.
- `taxInclusive` is a store setting. Changing it affects future calculations only.

Testing: derived splits, EXEMPT validation, duplicate name, delete guard, intra-state 5% (2.50/2.50), inter-state (IGST 5.00), inclusive 18% on 100 (84.75 + 15.25, CGST 7.63/SGST 7.62), snapshot immutability after a tax edit, GST report filters by snapshot rate.

---

### 22.11 Items and POS catalogue

| Aspect | Actual implementation |
|---|---|
| Entity / table | `Item`/`items`; `branch_inventory`; `inventory_transactions` |
| Repository | `ItemRepository`, `BranchInventoryRepository`, `InventoryTransactionRepository` |
| DTOs | `ItemResponse` (+ nested `Request`), `PosItemResponse`, `BranchStockResponse`, `InventoryTransactionResponse` |
| Service | `ItemService` |
| Controller | `ItemController` |
| Endpoints | `GET /api/items`, `GET /api/items/options`, `GET /api/items/pos`, `GET /api/items/{id}`, `POST /api/items`, `PUT /api/items/{id}`, `PUT /api/items/{id}/status`, `GET /api/items/{id}/stock`, `GET /api/items/{id}/transactions` |
| Frontend | `src/pages/ItemsPage.tsx`; `src/pages/PosPage.tsx` consumes `/api/items/pos`; item selectors in purchases/sales |
| Permissions | `ITEM_VIEW` / `ITEM_MANAGE`; POS catalogue `SALES_VIEW` or `SALES_MANAGE` |

Rules:

- One master rate: `items.rate` (NUMERIC(12,2); the database CHECK is `rate >= 0`, while the API requires `>= 0.01`). The legacy `default_selling_price` / `default_purchase_price` columns were removed by V3; do not reintroduce them.
- Item code is upper-cased, 2–50 characters, unique per store. Name ≤ 150. Category and tax must belong to the tenant. Inactive tax is rejected unless it is unchanged on update.
- `baseUnit` is one of the nine `Unit` values. Stock is always stored in the base unit; transaction lines keep both the entered `quantity`/`unit` and the converted `base_quantity`.
- `minStockLevel` is the item master minimum; `branch_inventory.minimum_stock` is the branch override used by low-stock queries.
- No delete endpoint. Deactivate; history must remain readable.
- `/api/items/pos?branchId=...` requires an accessible, active branch. It returns `id`, `itemCode`, `itemName`, `categoryId`, `categoryName`, `unit`, `rate`, `availableStock`, `lowStock`, `taxId`, `taxName`, `gstPercentage`, `taxType`, `taxInclusive`. The POS page must use `rate`; it must not invent a price.
- `/stock` returns accessible branch rows; `/transactions` returns the latest 200 ledger rows, filtered to accessible branches.

Testing: duplicate code, cross-store category/tax (`400`), inactive tax, unit conversion (1 L → 1000 ML), POS branch access (`403`), stock list filtering, no delete, update rate does not rewrite historical lines.

---

### 22.12 Suppliers

| Aspect | Actual implementation |
|---|---|
| Entity / table | `Supplier`/`suppliers` |
| Repository | `SupplierRepository`, `PurchaseRepository` |
| DTOs | `SupplierResponse` (+ `Request`), `SupplierPurchaseResponse` |
| Service | `SupplierService` |
| Controller | `SupplierController` |
| Endpoints | `GET /api/suppliers`, `GET /api/suppliers/options`, `GET /api/suppliers/{id}`, `POST /api/suppliers`, `PUT /api/suppliers/{id}`, `PUT /api/suppliers/{id}/status`, `GET /api/suppliers/{id}/purchases` |
| Frontend | `src/pages/SuppliersPage.tsx`; supplier selector in purchases |
| Permissions | `SUPPLIER_VIEW` / `SUPPLIER_MANAGE` |

Rules:

- Name, mobile and address are required; e-mail is validated; state is optional but drives inter-state GST (compare with the branch state).
- There is no uniqueness constraint on supplier name; duplicates are allowed. Add a uniqueness rule only with a migration and a data cleanup plan.
- Purchase history returns the latest 20 purchases for accessible branches; it is not paginated.
- No delete; deactivate.

Testing: CRUD, state used for IGST, purchase history scoping, inactive supplier rejected on purchase, tenant isolation, audit.

---

### 22.13 Inventory core

| Aspect | Actual implementation |
|---|---|
| Entities / tables | `BranchInventory`/`branch_inventory`, `InventoryTransaction`/`inventory_transactions`, `StockAdjustment`/`stock_adjustments` |
| Repositories | `BranchInventoryRepository`, `InventoryTransactionRepository`, `StockAdjustmentRepository` |
| DTOs | `InventoryRowResponse`, `InventoryTransactionResponse`, `StockAdjustmentRequest`, `InventoryPricingRequest`, `BranchStockResponse` |
| Service | `InventoryService` |
| Controller | `InventoryController` |
| Endpoints | `GET /api/inventory`, `GET /api/inventory/low-stock`, `GET /api/inventory/transactions`, `POST /api/inventory/adjustments`, `PUT /api/inventory/{branchId}/items/{itemId}/pricing` |
| Frontend | `src/pages/InventoryPage.tsx`, `src/pages/LowStockPage.tsx`; item detail stock/transactions |
| Permissions | `INVENTORY_VIEW`; adjustment/pricing `INVENTORY_ADJUST` |

This is the most safety-critical module. Implement it before purchases/sales/transfers.

1. **Row creation and locking.** `lockOrCreate(storeId, branchId, item)` uses a native `INSERT ... ON CONFLICT DO NOTHING`, then `SELECT ... FOR UPDATE`. Never read-modify-write stock without the lock. The database also enforces `current_stock >= 0` with a check constraint.
2. **Stock in** (`applyStockIn`): increase `current_stock`; update weighted average cost:
   `newAvg = (oldStock × oldAvg + qty × unitCost) / newStock` (scale 4, HALF_UP). Set `purchase_price` only for `PURCHASE_IN`; other inbound movements leave it alone.
3. **Stock out** (`applyStockOut`): reject when `current_stock < quantity`; decrease; do not change average cost on a sale, transfer-out or adjustment-out.
4. **Ledger.** Every movement writes one `inventory_transactions` row with `transaction_type`, `reference_type`, `reference_id`, `reference_number`, `quantity_in`/`quantity_out`, `balance_after`, unit and notes. The nine types are `OPENING_STOCK`, `PURCHASE_IN`, `SALE_OUT`, `TRANSFER_IN`, `TRANSFER_OUT`, `ADJUSTMENT_IN`, `ADJUSTMENT_OUT`, `PURCHASE_REVERSAL`, `SALE_REVERSAL`.
5. **Adjustment.** `POST /api/inventory/adjustments` writes `stock_adjustments` plus the ledger and updates stock. Types are `IN`/`OUT`; quantity ≥ 0.001; reason required; branch must be accessible and active.
6. **Pricing.** `PUT .../pricing` updates informational `minimum_stock`, `purchase_price`, `selling_price`; it must not change `items.rate` or `avg_cost`. Low stock uses `current_stock <= minimum_stock`.
7. **Opening stock.** There is no API. The demo seeder and script 06 insert `OPENING_STOCK`; in production, use a controlled adjustment or a one-off migration approved by the business. Do not fake it by editing `branch_inventory` without a ledger row.
8. **Invariant.** For every `(store, branch, item)`, the ledger net must equal `branch_inventory.current_stock` and the last `balance_after`. Script 05 checks this.

Testing: adjustment in/out, negative adjustment rejected, insufficient out, duplicate lines rejected at document level, zero-quantity movement rejected, pricing does not alter stock/cost, low-stock boundary (`<=`), ledger balance, tenant/branch filtering, concurrent sales (6 attempts, stock 1 → exactly one success).

---

### 22.14 Purchases

| Aspect | Actual implementation |
|---|---|
| Entities / tables | `Purchase`/`purchases`, `PurchaseDetail`/`purchase_details`; inventory and ledger |
| Repositories | `PurchaseRepository`, `PurchaseDetailRepository`, `BranchInventoryRepository`, `InventoryTransactionRepository` |
| DTOs | `PurchaseRequest`, `PurchaseResponse` |
| Service | `PurchaseService`, `NumberGenerator`, `GstCalculator`, `AuditService` |
| Controller | `PurchaseController` |
| Endpoints | `GET /api/purchases`, `GET /api/purchases/{id}`, `POST /api/purchases`, `PUT /api/purchases/{id}`, `POST /api/purchases/{id}/complete`, `POST /api/purchases/{id}/cancel` |
| Frontend | `src/pages/PurchasesPage.tsx`; `src/lib/documents.ts` for print/export |
| Permissions | `PURCHASE_VIEW` / `PURCHASE_MANAGE` |

Workflow:

1. **Create.** Validate branch (accessible, active), supplier (tenant, active), date, at least one line, unique items, quantity ≥ 0.001, manual `purchaseRate` ≥ 0, discounts and paid within limits. `status` defaults to `COMPLETED`; `CANCELLED` at create is rejected; `DRAFT` is allowed.
2. **Draft.** A draft writes only `purchases` and `purchase_details`. It must not touch `branch_inventory` or the ledger.
3. **Complete.** Lock each item's inventory row, post `PURCHASE_IN`, update weighted average cost and stock, write ledger rows, set `COMPLETED`. Numbering uses `purchase_number_seq` with prefix `PUR`.
4. **Cost per base unit.** `unitCost = taxable_amount / base_quantity`; if zero, fall back to `total_amount / base_quantity`; if still zero, use the purchase rate. GST is excluded from inventory cost. `purchase_price` is set to this cost for `PURCHASE_IN`.
5. **Arithmetic.** Line gross = `quantity × purchaseRate`; line discount cannot exceed gross; taxable = gross − line discount; tax via `GstCalculator`; header discount is allocated across lines (last line takes the remainder); `grandTotal = taxable + tax`; `paid + balance = grandTotal`.
6. **Cancel.** `DRAFT` → `CANCELLED` with no stock movement. `COMPLETED` → reverse each line at the **current** average cost (`PURCHASE_REVERSAL`), subtract stock, then set `CANCELLED`. If stock has been sold/transferred and cannot cover the reversal, the cancel fails with `400`; this is deliberate, not a reason to delete history.
7. **Audit** create/complete/cancel.

Testing: draft leaves stock at zero; complete increases stock and weighted cost; purchase cancel before sale restores stock; cancel after stock is sold fails; duplicate item (`400`); paid > grand (`400`); discount > subtotal (`400`); GST header equals line sums; number starts at 1001; cross-store supplier/item rejected.

> **Known limitation:** reversing a completed purchase at the current average cost can leave the average cost inconsistent with the remaining stock when other receipts occurred in between. There is no costing layer. Document and test the intended policy before relying on cancel for accounting.

---

### 22.15 Sales and POS checkout

| Aspect | Actual implementation |
|---|---|
| Entities / tables | `Sale`/`sales`, `SalesDetail`/`sales_details`; inventory and ledger; `customers` is currently unused by the sale flow |
| Repositories | `SaleRepository`, `SalesDetailRepository`, `BranchInventoryRepository`, `InventoryTransactionRepository`, `CustomerRepository` |
| DTOs | `SaleRequest`, `SaleResponse` |
| Service | `SaleService`, `NumberGenerator`, `GstCalculator`, `AuditService` |
| Controller | `SaleController` |
| Endpoints | `GET /api/sales`, `GET /api/sales/{id}`, `POST /api/sales`, `POST /api/sales/{id}/cancel` |
| Frontend | `src/pages/PosPage.tsx`, `src/pages/SalesEntryPage.tsx`, `src/pages/SalesPage.tsx`; `src/lib/documents.ts` for the bill |
| Permissions | `SALES_VIEW` / `SALES_MANAGE` |

Rules:

1. Validate branch access/active state, at least one line, unique items, quantity ≥ 0.001, manual `sellingRate` (required by the DTO even for POS), discounts, payment method and paid amount.
2. `pos: false` uses the supplied `sellingRate`. `pos: true` ignores it and posts `item.getRate()` from the item master. This is the POS contract; do not let the client override it.
3. Lock each inventory row. Reject when `current_stock < baseQuantity` with the available/required quantities. Post `SALE_OUT` and write the ledger. Never allow negative stock.
4. Snapshot `cost_rate = branch_inventory.avg_cost` on each sales line. This is the COGS basis for profit reports.
5. Apply GST via `GstCalculator`; store header totals and per-line snapshots. `totalAmount` on the line is the customer-facing amount; `taxableAmount` excludes tax.
6. Numbering: `sales_number_seq`, prefix `SAL`. Payment methods: `CASH`, `UPI`, `CARD`, `CREDIT`; paid cannot exceed grand total; balance is allowed.
7. Cancel: set `CANCELLED`, post `SALE_REVERSAL` stock in, **do not** change average cost. Payload history stays. There are no partial returns, refunds or credit notes.
8. Audit create/cancel.

Testing: normal sale uses manual rate; POS uses master rate even if the request sends another; oversell rejected; concurrent sales cannot oversell; cost snapshot is stable after later purchases; cancel restores stock; cancel twice (`400`); paid > grand (`400`); branch access; GST intra/inter/inclusive.

---

### 22.16 Stock transfers

| Aspect | Actual implementation |
|---|---|
| Entities / tables | `StockTransfer`/`stock_transfers`, `StockTransferDetail`/`stock_transfer_details`; inventory and ledger |
| Repositories | `StockTransferRepository`, `StockTransferDetailRepository`, `BranchInventoryRepository`, `InventoryTransactionRepository` |
| DTOs | `TransferRequest`, `TransferResponse` |
| Service | `TransferService`, `NumberGenerator` |
| Controller | `TransferController` |
| Endpoints | `GET /api/transfers`, `GET /api/transfers/{id}`, `POST /api/transfers`, `POST /api/transfers/{id}/approve`, `POST /api/transfers/{id}/dispatch`, `POST /api/transfers/{id}/receive`, `POST /api/transfers/{id}/reject`, `POST /api/transfers/{id}/cancel` |
| Frontend | `src/pages/TransfersPage.tsx` |
| Permissions | `TRANSFER_VIEW`; `TRANSFER_MANAGE` create/dispatch/receive/cancel; `TRANSFER_APPROVE` approve/reject |

State machine:

```text
PENDING --approve--> APPROVED --dispatch--> DISPATCHED --receive--> RECEIVED
PENDING --reject--> REJECTED
APPROVED --reject--> REJECTED
PENDING/APPROVED --cancel--> CANCELLED
```

Rules:

- Source and destination must differ, belong to the tenant, and be active. The creator must have access to the source; viewing is allowed with source **or** destination access.
- Create does not move stock. Dispatch posts `TRANSFER_OUT` at the source and checks availability. Receive posts `TRANSFER_IN` at the destination.
- Numbering: `transfer_number_seq`, prefix `TRF`.
- Receive uses the **source average cost at receive time** for the destination's weighted average cost. There is no cost snapshot on the transfer line, so a cost change between dispatch and receive changes the received cost. This is a known limitation.
- Reject from PENDING/APPROVED; cancel only PENDING/APPROVED. A received transfer cannot be cancelled; a dispatched transfer must be received (there is no in-transit loss flow). The unused `DRAFT` enum value is not reachable through the API.
- No partial receipt and no line-level adjustments.
- Audit state changes.

Testing: create does not move stock; dispatch decreases source; receive increases destination; insufficient source stock at dispatch (`400`); invalid transitions (`400`); staff without approve permission (`403`); destination-only user can view but not dispatch; cancel/reject rules; tenant isolation; ledger pair for RECEIVED.

---

### 22.17 Dashboard

| Aspect | Actual implementation |
|---|---|
| Service | `DashboardService` |
| Controller | `DashboardController` |
| DTO | `DashboardResponse` |
| Endpoint | `GET /api/dashboard?branchId=` |
| Frontend | `src/pages/DashboardPage.tsx` |
| Permission | `DASHBOARD_VIEW` |
| Tables | `branches`, `branch_inventory`, `items`, `categories`, `suppliers`, `purchases`, `sales`, `sales_details` |

Rules:

- Scope by tenant and accessible branches; an inaccessible `branchId` → `403`.
- "Today" uses the server's date; the application runs UTC. Be explicit if the business day differs from UTC.
- Summary: branch/category/item/supplier counts, today's purchase and sales amounts, low-stock count, stock value. Branch summary, recent purchases/sales, low-stock rows, top-selling items, stock summary.
- Only `COMPLETED` documents contribute to amounts; `DRAFT`/`CANCELLED` do not.
- No caching. If performance becomes an issue, add a cache with tenant/branch keys and explicit invalidation; do not share cache entries across tenants.

Testing: owner sees all accessible branches; staff sees assigned only; platform (`storeId == null`) gets `403`; cancelled documents excluded; low-stock count matches inventory; inaccessible branch `403`.

---

### 22.18 Reports

| Aspect | Actual implementation |
|---|---|
| Service | `ReportService` |
| Controller | `ReportController` |
| DTOs | `PurchaseReportResponse`, `SalesReportResponse`, `GstReportResponse`, `StockReportResponse`, `LowStockReportResponse`, `BranchReportResponse`, `StoreReportResponse`, `ProfitReportResponse` |
| Endpoints | `GET /api/reports/purchases`, `/sales`, `/gst`, `/stock`, `/low-stock`, `/branches`, `/store`, `/profit` |
| Frontend | `src/pages/ReportsPage.tsx`, `src/pages/reports/*`, `src/components/ReportExport.tsx`, `src/lib/export.ts` |
| Permission | `REPORT_VIEW` |
| Tables | `purchases`, `purchase_details`, `sales`, `sales_details`, `branch_inventory`, `inventory_transactions`, `items`, `categories`, `branches`, `suppliers`, `tax_master` |

Rules:

- Default range: first day of the current month through today. `from > to` → `400`.
- Scope: tenant + accessible branches; `branchId` must be accessible.
- Only `COMPLETED` purchases/sales. `CANCELLED` documents are excluded; reversal ledger rows remain in stock reports.
- GST report reads the **snapshot** columns on sales lines, not the current tax master. `taxRate` is an exact match on `gst_percentage`.
- Stock report derives opening stock as `current_stock − net movements after the opening point`; it is a derived figure because there is no opening-stock document. Explain this to users.
- Profit: revenue = `totalAmount − tax`; COGS = `baseQuantity × costRate`; gross profit = revenue − COGS. This is a snapshot-based estimate, not an accounting valuation (no FIFO/weighted-average layering per sale).
- Low stock uses `current_stock <= minimum_stock`; `limit` is clamped.
- Reports return rows plus totals. Do not recompute from master data; use posted snapshots.
- Exports are client-side XLSX/PDF (`xlsx`, `jspdf`, `jspdf-autotable`); there is no server export endpoint.

Testing: default dates, invalid range, branch scope, cancelled documents excluded, GST snapshot after tax edit, stock opening math, low-stock boundary, profit arithmetic, report permission, tenant isolation.

---

### 22.19 Frontend application shell

| Concern | Actual implementation |
|---|---|
| Routing | `src/App.tsx` (React Router 7, lazy pages) |
| Guards | `src/components/layout/guards.tsx` — `RequireAuth`, `RequirePermission`, `AccessDenied`, `PublicOnly` |
| Layout/navigation | `src/components/layout/AppLayout.tsx`, `nav.tsx` |
| Auth state | `src/store/auth.ts` (Zustand + `persist`, key `ims-auth`) |
| API client | `src/lib/api.ts` (Axios, baseURL `/api`, bearer interceptor, 401 event) |
| UI kit | `src/components/ui/*` |
| Dev proxy | `vite.config.ts` proxies `/api` to `http://localhost:8080` |
| Build | `npm run build` (TypeScript project build + Vite) |
| Lint | `npm run lint` (oxlint) |

Rules:

- The token is read from `localStorage` (`ims-auth`) on every request. On `401` (except login/register) the client clears the session and emits `ims:unauthorized`; `SessionWatcher` redirects to `/login` with a message.
- `RequirePermission` hides/disallows routes, but it is not security. The API must enforce the same permission.
- Navigation items are filtered by permission. A missing permission must not leave a dead link.
- Platform users are redirected to `/platform/stores`; store users to the dashboard. `HomeRoute` handles this.
- Add a route in `App.tsx`, a nav entry in `nav.tsx`, and a permission in the guard. Keep the permission code identical to the backend authority string.
- Handle loading, empty, error and forbidden states in every page; don't render stale rows after a failed refresh.
- Responsive requirements: test at 320/375/768/1024/1440 px; no horizontal page scroll; tables scroll inside their container; modals fit; POS cart usable on a tablet; report filters stack.

### 22.20 Frontend module map

| Route | Page | Main API | Permission |
|---|---|---|---|
| `/login` | `LoginPage` | `POST /api/auth/login` | public |
| `/register` | `RegisterPage` | `POST /api/auth/register-store` | public |
| `/` | `DashboardPage` | `GET /api/dashboard` | `DASHBOARD_VIEW` |
| `/pos` | `PosPage` | `GET /api/items/pos`, `POST /api/sales` | `SALES_MANAGE` |
| `/sales/new` | `SalesEntryPage` | `GET /api/items/options`, `POST /api/sales` | `SALES_MANAGE` |
| `/sales` | `SalesPage` | `GET /api/sales`, `GET /api/sales/{id}`, cancel | `SALES_VIEW` |
| `/items` | `ItemsPage` | `/api/items`, `/api/categories/options`, `/api/taxes/options` | `ITEM_VIEW` |
| `/categories` | `CategoriesPage` | `/api/categories` | `CATEGORY_VIEW` |
| `/taxes` | `TaxMasterPage` | `/api/taxes` | `TAX_VIEW` |
| `/suppliers` | `SuppliersPage` | `/api/suppliers` | `SUPPLIER_VIEW` |
| `/purchases` | `PurchasesPage` | `/api/purchases`, complete/cancel | `PURCHASE_VIEW` |
| `/inventory` | `InventoryPage` | `/api/inventory`, adjustments, pricing | `INVENTORY_VIEW` |
| `/transfers` | `TransfersPage` | `/api/transfers`, state actions | `TRANSFER_VIEW` |
| `/low-stock` | `LowStockPage` | `/api/inventory/low-stock` | `INVENTORY_VIEW` |
| `/reports` | `ReportsPage` + `reports/*` | `/api/reports/*` | `REPORT_VIEW` |
| `/settings?tab=store` | `StoreSettings` | `/api/stores/current` | `STORE_VIEW` |
| `/settings?tab=branches` | `BranchesSettings` | `/api/branches` | `BRANCH_VIEW` |
| `/settings?tab=users` | `UsersSettings` | `/api/users` | `USER_VIEW` |
| `/settings?tab=roles` | `RolesSettings` | `/api/roles`, `/api/permissions` | `ROLE_VIEW` |
| `/platform/stores` | `PlatformStoresPage` | `/api/stores` | `PLATFORM_MANAGE` |

### 22.21 Security checklist for every module

- [ ] Controller method has `@PreAuthorize` with the exact permission.
- [ ] Service derives `storeId` from `TenantContext`, not from the request.
- [ ] Every entity lookup uses `findByIdAndStoreId` or a store-filtered query.
- [ ] Branch-scoped operations call `requireBranch` / verify branch access.
- [ ] Branch and supplier/item ids are tenant-validated before use.
- [ ] Request DTOs have Bean Validation; service adds business validation.
- [ ] Money/quantity scale and rounding are explicit.
- [ ] Transaction boundary covers all writes, including stock and ledger.
- [ ] No delete for history; use status/cancel/reversal.
- [ ] Audit action is written for sensitive changes.
- [ ] Error messages do not leak other tenants' data.
- [ ] Tests cover cross-tenant, cross-branch, insufficient stock and invalid state.

### 22.22 Extending the schema safely

1. Add `V4__short_description.sql` under `backend/src/main/resources/db/migration`; never modify an applied migration.
2. Use additive changes first (`ADD COLUMN ... NULL`, backfill, then `SET NOT NULL`), and add constraints after the backfill.
3. Update the entity and any DTO/service mapping. Keep `ddl-auto=validate`; do not switch to `update`.
4. Update `docs/sql/02_create_tables.sql`, `03_create_indexes.sql`, `04_insert_initial_data.sql` and `05_database_validation.sql` if the manual path must remain reproducible.
5. Add an integration test that starts from a clean embedded database and exercises the new column/constraint.
6. Test the upgrade on a copy of a populated database, not only on a fresh schema.
7. Document rollback. A destructive down-migration is not provided; restore from backup if a release must be reverted.

### 22.23 Definition of done per module

- Flyway migration applies cleanly on an empty database and on a copy of the current database.
- Entity/repository/DTO/service/controller compile; `ddl-auto=validate` passes.
- Permission and branch checks are enforced server-side and covered by tests.
- Happy path, validation errors, cross-tenant, cross-branch, and stock/GST edge cases pass.
- Audit rows are written for create/update/status/cancel where applicable.
- Frontend page calls the real API; loading/empty/error states work; responsive at the target widths.
- Documentation (API reference, table docs, business flow) is updated in the same change.

---

## 23. Testing

### 23.1 Actual test coverage

The repository has one backend integration test class:

```text
backend/src/test/java/com/itemmanagement/SystemInvariantIntegrationTest.java
```

It starts the full Spring Boot application on a random HTTP port with a **real embedded PostgreSQL 14.10**:

| Test property | Value |
|---|---|
| `app.embedded-postgres.port` | `54330` |
| `app.embedded-postgres.data-dir` | `build/pgdata-test` |
| `app.embedded-postgres.clean` | `true` |
| `app.seed-demo` | `false` |
| Logging | `com.itemmanagement=WARN` |

There is **no automated frontend test suite** in `package.json` (no Vitest/Jest/Playwright). Frontend verification is manual and must be done in a browser against the running API. Do not claim UI correctness from a successful TypeScript build.

### 23.2 Run the backend tests

From `backend/`:

```powershell
# Windows
.\gradlew.bat test

# Git Bash / Linux / macOS
./gradlew test
```

Run one class or one method:

```powershell
.\gradlew.bat test --tests "com.itemmanagement.SystemInvariantIntegrationTest"
.\gradlew.bat test --tests "com.itemmanagement.SystemInvariantIntegrationTest.concurrentSalesCannotOversellStock"
```

Notes:

- The test server and data directory are separate from development (`54329` / `backend/.pgdata`). Do not point tests at `.pgdata` or at a production database; `clean=true` wipes the test cluster.
- Each run recreates the test schema and seeds only the platform user (`platform` / `Admin@123`); store fixtures are created by the tests through the public registration API.
- The first run downloads dependencies and PostgreSQL binaries; later runs are faster.
- A failed test leaves `backend/build/pgdata-test`; the next run cleans it. Inspect it only after stopping the test JVM.
- The test uses `TestRestTemplate` against HTTP endpoints; it is not a mock-based unit test.

Expected: 16 tests, all passing.

### 23.3 The 16 integration tests

| # | Test | What it proves |
|---:|---|---|
| 1 | `rejectsMissingAndInvalidTokens` | Protected endpoints return `401` for absent/garbage tokens |
| 2 | `rejectsWrongPassword` | Login returns `401` and a message for a wrong password |
| 3 | `storeCannotReadOrUseAnotherStoresData` | Cross-tenant reads/writes are blocked for items, suppliers, branches, purchases, sales and reports |
| 4 | `platformAdminCannotReadStoreDataButCanManageStores` | Platform token is rejected on business endpoints but can list/create stores |
| 5 | `purchaseLifecycleUpdatesStockAtomically` | Draft leaves stock unchanged; completion posts stock; totals and status are correct |
| 6 | `purchaseCannotBeCancelledOnceStockIsSold` | Reversal fails when the stock is no longer available; history is not deleted |
| 7 | `saleDecrementsStockAndBlocksOverselling` | Sale posts stock out; oversell is rejected |
| 8 | `concurrentSalesCannotOversellStock` | Six simultaneous sales against stock 1: exactly one succeeds, five are rejected, final stock 0 |
| 9 | `rejectsNegativeAdjustmentsAndDuplicateLines` | Negative/zero adjustments and duplicate document lines are rejected |
| 10 | `rejectsPaymentAboveGrandTotal` | Paid amount cannot exceed the document total |
| 11 | `transferMovesStockOnlyAfterDispatchAndReceive` | Create/approve do not move stock; dispatch/receive do; state rules hold |
| 12 | `branchStaffIsLimitedToAssignedBranchAndPermissions` | Branch staff cannot access unassigned branches or admin operations |
| 13 | `deactivationImmediatelyRevokesExistingTokens` | Deactivating a user/store invalidates an already-issued token on the next request |
| 14 | `taxMasterIsStoreSpecificAndProtectedWhenUsed` | Tax slabs are tenant-scoped; used slabs cannot be deleted |
| 15 | `gstIsCalculatedOnServerAndSnapshotsStayImmutable` | Intra-state 5% split, inter-state IGST, snapshots unchanged after a tax edit, GST report uses snapshots |
| 16 | `inclusiveTaxSettingBackCalculatesTaxableAmount` | Inclusive 18% on 100 → taxable 84.75, tax 15.25, CGST 7.63, SGST 7.62 |

### 23.4 Manual API smoke test

Start the backend and frontend, then use a REST client. The demo seed (only when `SEED_DEMO=true`) creates store `ABC001`, user `owner` / `Owner@123`, branches `MAIN`, `BLR`, `MYS`, and items. These are development credentials; never use them in production.

PowerShell example:

```powershell
$base = "http://localhost:8080/api"
$login = Invoke-RestMethod -Method Post -Uri "$base/auth/login" -ContentType "application/json" -Body (@{
  storeCode = "ABC001"; username = "owner"; password = "Owner@123"
} | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.token)" }

Invoke-RestMethod -Method Get -Uri "$base/auth/me" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/branches" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/items?size=5" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/dashboard" -Headers $headers
```

Bash with `curl` and `jq`:

```bash
BASE=http://localhost:8080/api
TOKEN=$(curl -s -X POST "$BASE/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"storeCode":"ABC001","username":"owner","password":"Owner@123"}' | jq -r .token)

curl -s "$BASE/auth/me" -H "Authorization: Bearer $TOKEN" | jq
curl -s "$BASE/branches" -H "Authorization: Bearer $TOKEN" | jq
curl -s "$BASE/items?size=5" -H "Authorization: Bearer $TOKEN" | jq
```

A full write-path smoke test on a fresh store:

1. `POST /api/auth/register-store` → sign in with the new store code.
2. `GET /api/branches` → take the MAIN branch id.
3. `GET /api/taxes/options` → take the 5% tax id.
4. `POST /api/categories` → take the id.
5. `POST /api/items` with category, tax, base unit, rate, minimum stock.
6. `POST /api/suppliers`.
7. `POST /api/purchases` with `status: "COMPLETED"` → stock increases; check `GET /api/items/{id}/stock`.
8. `POST /api/sales` with `pos: false` and a manual `sellingRate` → stock decreases.
9. `POST /api/sales` with `pos: true` → verify the line rate equals `items.rate`.
10. `POST /api/transfers`, approve, dispatch, receive → verify both branch stocks.
11. `POST /api/inventory/adjustments` → verify stock and ledger.
12. `GET /api/reports/store` and `/api/reports/gst` → totals match the documents.
13. Cancel the sale/purchase only in a disposable test store; verify reversal rows and that history remains.

Use the exact bodies in `docs/05-api-reference.md` sections 15.3–15.17; do not guess fields. Verify that the response omits null fields (`default-property-inclusion: non_null`).

### 23.5 Frontend build and manual test matrix

```bash
npm install
npm run lint
npm run build
npm run dev      # http://localhost:5173, proxies /api to :8080
```

`npm run build` runs `tsc -b` before Vite. A type error fails the build. `npm run lint` uses oxlint. There is no `npm test`.

Manual browser matrix (use a real browser; do not rely on the build):

| Area | Checks |
|---|---|
| Auth | login success/failure, inactive user/store message, register store, refresh keeps session, logout clears `ims-auth`, expired token redirects with a message |
| Dashboard | summary cards, branch filter, recent documents, low stock, empty state |
| POS | branch selector, category/search, add/remove lines, stock limit, GST totals, payment method, paid/balance, submit, bill print/PDF |
| Sales entry | manual rate, discounts, customer state (IGST), paid validation, stock error |
| Sales history | filters, date range, detail, cancel, cancelled badge |
| Items | create/edit, category/tax selectors, rate/min stock, status, stock and ledger views, no delete |
| Categories / tax / suppliers | create/edit/status, delete guards, duplicate messages, options |
| Purchases | draft edit, complete, cancel, rate/discount/GST totals, stock after completion |
| Inventory | filters, low-only, adjustment in/out, pricing, ledger, low-stock page |
| Transfers | create, approve, dispatch, receive, reject, cancel, destination-only view |
| Reports | all 8 tabs, date defaults/validation, branch filter, totals, XLSX/PDF export |
| Settings | store profile, branches, users (role/branches/password), roles/permissions |
| Platform | store list/create/edit/status as platform user; no store business data |
| Responsive | 320/375/768/1024/1440 px; no page-level horizontal scroll; tables scroll; modals and POS cart usable |
| Errors | backend down message, `403` page, `404`, validation field errors, empty lists |

### 23.6 Security regression cases

Run these against a test database after any security change:

| Case | Expected |
|---|---|
| No token / malformed token | `401` on every protected endpoint |
| Expired/revoked (`logout`, `refresh`) token | `401` |
| Store A token with Store B item/branch/purchase/sale id | `404`/`403`, no data |
| Platform token on `/api/items`, `/api/dashboard` | `403` |
| Branch staff on an unassigned branch | `403` |
| Branch staff with only `SALES_MANAGE` on `/api/users`, `/api/roles` | `403` |
| Deactivated user/store with an existing token | `403` on the next request |
| Password change with an existing token | Token remains valid (known gap) |
| Removed branch assignment with an existing token | Access remains until refresh/login (known gap) |
| Store role granted `PLATFORM_MANAGE` | Currently can call `/api/stores` (known defect; must fail after fix) |
| `STORE_ADMIN` assigns `STORE_OWNER` to self | Currently allowed (known defect; must fail after fix) |
| SQL injection strings in search/filter params | Treated as data; no query error/data leak |
| Oversized page/size | Clamped by the endpoint; no unbounded result |
| Cross-tenant `branchIds` in user create | `400 One or more branches do not belong to this store` |

### 23.7 Inventory and concurrency tests

- The existing concurrency test uses six parallel HTTP requests for one unit of stock. Keep the pattern for any change to `lockOrCreate`, `applyStockOut` or sale posting.
- Add a two-instance test if you move to a standalone multi-node deployment; the database row lock is the guarantee, not the JVM.
- Test a purchase and a sale racing on the same item/branch. One should serialize behind the other; neither may produce negative stock.
- Test transfer dispatch racing with a sale at the source; insufficient stock must reject one.
- After every test, assert the ledger invariant: `sum(quantity_in − quantity_out) = current_stock` and the last `balance_after` matches.
- Run script 05 on the test database if you need a second, independent check.

### 23.8 GST test matrix

| Scenario | Expected |
|---|---|
| Exclusive 5%, intra-state, 100 taxable | CGST 2.50, SGST 2.50, tax 5.00, total 105.00 |
| Exclusive 5%, inter-state | IGST 5.00, CGST/SGST 0, total 105.00 |
| Inclusive 18%, customer pays 100 | taxable 84.75, tax 15.25, CGST 7.63, SGST 7.62 |
| Inclusive 5%, customer pays 105 | taxable 100.00, tax 5.00 |
| Line discount | Discount reduces the taxable base before GST |
| Header discount | Allocated across lines; last line takes the rounding remainder; header tax = sum of lines |
| Missing customer/supplier state | Treated as intra-state |
| Tax master edited after posting | Document and report snapshots unchanged |
| GST report filter `taxRate=18` | Only lines snapshotted at 18% |
| EXEMPT slab | All percentages zero; no tax |

### 23.9 Release regression checklist

- [ ] `.\gradlew.bat test` — 16/16 pass.
- [ ] `npm run lint` — no errors.
- [ ] `npm run build` — TypeScript and Vite succeed.
- [ ] Backend starts against a clean standalone database; Flyway applies V1–V3; `validate` passes.
- [ ] Script 05 has no integrity rows.
- [ ] Login as platform and as a store owner; branch staff is limited.
- [ ] Purchase → sale → POS → transfer → adjustment smoke test passes.
- [ ] Dashboard and all eight reports load within the agreed time.
- [ ] Responsive pass at the target widths.
- [ ] No secrets, dumps or `.pgdata` in the commit.
- [ ] Known issues reviewed; no new high-severity item left undocumented.

### 23.10 CI recommendation

A minimal pipeline:

1. Backend: JDK 17, `./gradlew test` (embedded PostgreSQL downloads on first run; cache the Gradle and Zonky caches).
2. Frontend: Node 24, `npm ci`, `npm run lint`, `npm run build`.
3. Optional: start the packaged backend against a disposable PostgreSQL service and run a smoke script; do not use the embedded test cluster for production-like checks.
4. Publish test reports and fail on any test failure; never mark the build green on skipped tests.

---

**End of sections 22–23.** This guide did not modify source, schema, migrations, packages or configuration, and no test, migration or install was executed while writing it.
