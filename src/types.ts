export type StoreStatus = 'ACTIVE' | 'INACTIVE'
export type TaxType = 'GST' | 'EXEMPT'
export type Unit = string
export type PaymentMethod = 'CASH' | 'UPI' | 'CARD' | 'CREDIT'
export type SupplierPaymentMethod = 'CASH' | 'BANK' | 'UPI' | 'CARD'
export type SettlementStatus = 'UNPAID' | 'PARTIALLY_PAID' | 'PAID'
export type BarcodeFormat = 'CODE128' | 'EAN13'
export type PurchaseStatus = 'DRAFT' | 'COMPLETED' | 'CANCELLED'
export type SaleStatus = 'COMPLETED' | 'CANCELLED'
export type TransferStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'DISPATCHED' | 'RECEIVED' | 'REJECTED' | 'CANCELLED'
export type TransactionType =
  | 'OPENING_STOCK'
  | 'PURCHASE_IN'
  | 'SALE_OUT'
  | 'TRANSFER_IN'
  | 'TRANSFER_OUT'
  | 'ADJUSTMENT_IN'
  | 'ADJUSTMENT_OUT'
  | 'PURCHASE_REVERSAL'
  | 'SALE_REVERSAL'

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface MessageResponse {
  message: string
}

export interface StatusRequest {
  status: StoreStatus
}

export interface AuthResponse {
  token: string | null
  tokenType: string | null
  expiresIn: number
  user: {
    id: number
    username: string
    fullName: string
    email: string
    mobile: string | null
  }
  store: {
    id: number
    storeCode: string
    storeName: string
    ownerName: string
    email: string
    mobile: string
    address: string
    gstNumber: string | null
    taxInclusive: boolean
    status: string
  } | null
  branches: { id: number; branchCode: string; branchName: string; state: string | null }[]
  roles: string[]
  permissions: string[]
  allBranches: boolean
  platformAdmin: boolean
  mustChangePassword: boolean
}

export interface LoginRequest {
  storeCode: string
  username: string
  password: string
}

export interface RegisterStoreRequest {
  storeCode: string
  storeName: string
  ownerName: string
  email: string
  mobile: string
  address: string
  gstNumber?: string
  state?: string
  username: string
  password: string
  branchName?: string
}

export interface Category {
  id: number
  categoryName: string
  description: string | null
  status: string
  itemCount: number
  createdAt: string
}

export interface CategoryRequest {
  categoryName: string
  description?: string
  status?: StoreStatus
}

export type ImportBatchStatus = 'PREVIEWED' | 'COMPLETED' | 'FAILED'
export type ImportValidationStatus = 'VALID' | 'INVALID'
export type ImportAction =
  | 'NEW_ITEM'
  | 'EXISTING_ITEM_OPENING_STOCK'
  | 'EXISTING_ITEM_NO_STOCK'
  | 'DUPLICATE_ROW'
  | 'VALIDATION_ERROR'
  | 'ALREADY_IMPORTED'

export interface ImportSummary {
  totalRows: number
  newCategories: number
  newUnits: number
  reusedUnits: number
  newItems: number
  existingItems: number
  stockUpdates: number
  duplicateRows: number
  invalidRows: number
  alreadyImportedRows: number
  importableRows: number
}

export interface ImportRow {
  id: number
  rowNumber: number
  itemCode: string | null
  itemName: string | null
  categoryName: string | null
  unit: string | null
  rate: number | null
  taxName: string | null
  openingStock: number | null
  minimumStock: number | null
  description: string | null
  itemId: number | null
  importAction: ImportAction
  validationStatus: ImportValidationStatus
  errorMessage: string | null
}

export interface ImportPreview {
  batchId: number
  importNumber: string
  fileName: string
  branchId: number
  branchName: string
  status: ImportBatchStatus
  createdAt: string
  completedAt: string | null
  failureMessage: string | null
  summary: ImportSummary
  rows: ImportRow[]
}

export interface ImportBatch {
  batchId: number
  importNumber: string
  fileName: string
  branchId: number
  branchName: string
  status: ImportBatchStatus
  totalRows: number
  successRows: number
  failedRows: number
  newCategories: number
  newItems: number
  existingItems: number
  stockUpdates: number
  duplicateRows: number
  invalidRows: number
  alreadyImportedRows: number
  failureMessage: string | null
  uploadedBy: string
  createdAt: string
  completedAt: string | null
}

export interface Item {
  id: number
  itemCode: string
  barcode: string
  barcodeFormat: BarcodeFormat
  barcodeSource: 'INTERNAL' | 'GS1'
  itemName: string
  description: string | null
  categoryId: number
  categoryName: string
  taxId: number
  taxName: string
  gstPercentage: number
  taxType: TaxType
  baseUnit: Unit
  rate: number
  minStockLevel: number
  status: string
  createdAt: string
}

export interface ItemRequest {
  itemCode: string
  barcode?: string
  barcodeFormat?: BarcodeFormat
  itemName: string
  description?: string
  categoryId: number | ''
  taxId: number | ''
  baseUnit: Unit
  rate: number | ''
  minStockLevel: number | ''
  status?: StoreStatus
}

export interface TaxMaster {
  id: number
  storeId: number
  taxName: string
  description: string | null
  gstPercentage: number
  cgstPercentage: number
  sgstPercentage: number
  igstPercentage: number
  taxType: TaxType
  status: string
  itemCount: number
  createdAt: string
  updatedAt: string
}

export interface TaxMasterRequest {
  taxName: string
  description?: string
  gstPercentage: number | ''
  taxType: TaxType
  status?: StoreStatus
}

export interface UnitMaster {
  id: number
  storeId: number
  unitCode: string
  unitName: string
  description: string | null
  status: string
  itemCount: number
  createdAt: string
  updatedAt: string
}

export interface UnitMasterRequest {
  unitCode: string
  unitName: string
  description?: string
  status?: StoreStatus
}

export interface Supplier {
  id: number
  supplierName: string
  mobile: string
  address: string
  state: string | null
  gstNumber: string | null
  email: string | null
  status: string
  createdAt: string
}

export interface SupplierRequest {
  supplierName: string
  mobile: string
  address: string
  state?: string
  gstNumber?: string
  email?: string
  status?: StoreStatus
}

export interface SupplierPurchase {
  id: number
  purchaseNumber: string
  purchaseDate: string
  branchName: string
  grandTotal: number
  status: string
  createdAt: string
}

export interface SupplierOutstanding {
  supplierId: number
  supplierCode: string
  supplierName: string
  totalPurchaseAmount: number
  totalPaid: number
  outstandingAmount: number
  billCount: number
  pendingBillCount: number
}

export interface SupplierBill {
  purchaseId: number
  purchaseNumber: string
  invoiceNumber: string | null
  purchaseDate: string
  branchName: string
  grandTotal: number
  paidAmount: number
  balanceAmount: number
  settlementStatus: SettlementStatus
}

export interface SupplierPaymentAllocation {
  purchaseId: number
  purchaseNumber: string
  purchaseDate: string | null
  amount: number
}

export interface SupplierPayment {
  id: number
  paymentNumber: string
  paymentDate: string
  supplierId: number
  supplierCode: string
  supplierName: string
  paymentMethod: SupplierPaymentMethod
  amount: number
  referenceNumber: string | null
  remarks: string | null
  createdBy: number | null
  createdByName: string | null
  createdAt: string
  allocations: SupplierPaymentAllocation[]
}

export interface SupplierPaymentRequest {
  supplierId: number
  paymentDate: string
  paymentMethod: SupplierPaymentMethod
  amount: number
  referenceNumber?: string
  remarks?: string
  clientReference: string
  purchaseIds: number[]
}

export interface Branch {
  id: number
  storeId: number
  branchCode: string
  branchName: string
  address: string
  contactNumber: string
  state: string | null
  status: string
  createdAt: string
}

export interface BranchRequest {
  branchCode: string
  branchName: string
  address: string
  contactNumber: string
  state?: string
  status?: StoreStatus
}

export interface User {
  id: number
  username: string
  fullName: string
  email: string
  mobile: string | null
  status: string
  roles: { id: number; code: string; name: string }[]
  branches: { id: number; branchCode: string; branchName: string }[]
  createdAt: string
}

export interface UserCreateRequest {
  username: string
  email: string
  password: string
  fullName: string
  mobile?: string
  roleId: number | ''
  branchIds: number[]
}

export interface UserUpdateRequest {
  fullName: string
  email: string
  mobile?: string
  roleId: number | ''
  branchIds: number[]
  status?: StoreStatus
  password?: string
}

export interface Permission {
  id: number
  code: string
  name: string
  description: string | null
}

export interface Role {
  id: number
  code: string
  name: string
  description: string | null
  system: boolean
  permissions: Permission[]
  userCount: number
}

export interface RoleRequest {
  code: string
  name: string
  description?: string
  permissionCodes: string[]
}

export interface Store {
  id: number
  storeCode: string
  storeName: string
  ownerName: string
  email: string
  mobile: string
  address: string
  gstNumber: string | null
  taxInclusive: boolean
  status: string
  createdAt: string
  branchCount: number
  userCount: number
  subscriptionStartDate: string
  subscriptionEndDate: string
  maxBranches: number
  remainingBranches: number
  subscriptionExpired: boolean
}

export interface PlatformAccount {
  id: number
  storeId: number
  storeCode: string | null
  storeName: string | null
  username: string
  fullName: string
  email: string
  mobile: string | null
  status: StoreStatus
  roles: { id: number; code: string; name: string }[]
  owner: boolean
  createdAt: string
  updatedAt: string
}

export interface PlatformPasswordReset {
  userId: number
  username: string
  storeCode: string | null
  temporaryPassword: string
  generatedAt: string
}

export interface CredentialHistoryEntry {
  id: number
  action: string
  actorUsername: string | null
  details: string | null
  createdAt: string
}

export interface AuditLogEntry {
  id: number
  storeId: number | null
  storeCode: string | null
  storeName: string | null
  userId: number | null
  username: string | null
  action: string
  entityType: string | null
  entityId: number | null
  details: string | null
  createdAt: string
}

export interface SubscriptionSummary {
  storeId: number
  storeCode: string
  storeName: string
  startDate: string
  endDate: string
  daysRemaining: number
  maxBranches: number
  branchCount: number
  remainingBranches: number
  expired: boolean
  platformContactEmail: string
  platformContactPhone: string
}

export interface SubscriptionRequest {
  startDate: string
  endDate: string
  maxBranches: number | ''
  note?: string
}

export interface SubscriptionHistoryEntry {
  id: number
  changedAt: string
  changedBy: number | null
  changedByUsername: string | null
  previousStartDate: string | null
  newStartDate: string | null
  previousEndDate: string | null
  newEndDate: string | null
  previousMaxBranches: number | null
  newMaxBranches: number | null
  note: string | null
}

export interface PlatformContact {
  contactEmail: string
  contactPhone: string
  updatedAt: string | null
}

export interface UpdateStoreRequest {
  storeName: string
  ownerName: string
  email: string
  mobile: string
  address: string
  gstNumber?: string
  taxInclusive?: boolean
}

export interface InventoryRow {
  id: number
  branchId: number
  branchCode: string
  branchName: string
  itemId: number
  itemCode: string
  itemName: string
  categoryId: number
  categoryName: string
  unit: Unit
  currentStock: number
  minimumStock: number
  purchasePrice: number
  sellingPrice: number
  stockValue: number
  lowStock: boolean
}

export interface InventoryTransaction {
  id: number
  branchId: number
  branchName: string
  itemId: number
  itemName: string
  transactionType: TransactionType
  referenceType: string | null
  referenceNumber: string | null
  quantityIn: number
  quantityOut: number
  balanceAfter: number
  unit: Unit
  notes: string | null
  transactionDate: string
}

export interface StockAdjustmentRequest {
  branchId: number | ''
  itemId: number | ''
  type: 'IN' | 'OUT'
  quantity: number | ''
  unit?: Unit
  reason: string
}

export interface InventoryPricingRequest {
  minimumStock: number | ''
  purchasePrice: number | ''
  sellingPrice: number | ''
}

export interface BranchStock {
  branchId: number
  branchCode: string
  branchName: string
  currentStock: number
  minimumStock: number
  purchasePrice: number
  sellingPrice: number
  unit: Unit
  lowStock: boolean
}

export interface PurchaseLine {
  id?: number
  itemId: number | ''
  itemCode?: string
  itemName?: string
  quantity: number | ''
  unit: Unit
  baseQuantity?: number
  purchaseRate: number | ''
  discount: number | ''
  taxId?: number
  taxName?: string
  gstPercentage?: number
  cgstPercentage?: number
  sgstPercentage?: number
  igstPercentage?: number
  taxableAmount?: number
  cgstAmount?: number
  sgstAmount?: number
  igstAmount?: number
  tax?: number
  totalAmount?: number
}

export interface Purchase {
  id: number
  purchaseNumber: string
  invoiceNumber: string | null
  purchaseDate: string
  branchId: number
  branchName: string
  supplierId: number
  supplierName: string
  subtotal: number
  discount: number
  taxableAmount: number
  cgstAmount: number
  sgstAmount: number
  igstAmount: number
  tax: number
  grandTotal: number
  paidAmount: number
  balanceAmount: number
  paymentStatus: SettlementStatus
  notes: string | null
  status: PurchaseStatus
  createdAt: string
  items: PurchaseLine[]
}

export interface PurchaseRequest {
  branchId: number | ''
  supplierId: number | ''
  invoiceNumber?: string
  purchaseDate: string
  discount: number | ''
  payableAmount?: number | ''
  paidAmount: number | ''
  notes?: string
  status: 'DRAFT' | 'COMPLETED'
  items: {
    itemId: number | ''
    quantity: number | ''
    unit: Unit
    purchaseRate: number | ''
    discount: number | ''
  }[]
}

export interface SaleLine {
  id?: number
  itemId: number | ''
  itemCode?: string
  itemName?: string
  quantity: number | ''
  unit: Unit
  baseQuantity?: number
  sellingRate: number | ''
  costRate?: number
  discount: number | ''
  taxId?: number
  taxName?: string
  gstPercentage?: number
  cgstPercentage?: number
  sgstPercentage?: number
  igstPercentage?: number
  taxableAmount?: number
  cgstAmount?: number
  sgstAmount?: number
  igstAmount?: number
  tax?: number
  totalAmount?: number
}

export interface Sale {
  id: number
  salesNumber: string
  salesDate: string
  branchId: number
  branchName: string
  customerName: string | null
  customerMobile: string | null
  customerState: string | null
  subtotal: number
  discount: number
  taxableAmount: number
  cgstAmount: number
  sgstAmount: number
  igstAmount: number
  tax: number
  grandTotal: number
  paidAmount: number
  balanceAmount: number
  paymentMethod: PaymentMethod
  status: SaleStatus
  createdAt: string
  items: SaleLine[]
}

export interface SaleRequest {
  branchId: number | ''
  customerName?: string
  customerMobile?: string
  customerState?: string
  salesDate: string
  discount: number | ''
  paidAmount: number | ''
  paymentMethod: PaymentMethod
  status: 'COMPLETED'
  pos?: boolean
  items: {
    itemId: number | ''
    quantity: number | ''
    unit: Unit
    sellingRate: number | ''
    discount: number | ''
  }[]
}

export interface TransferLine {
  id?: number
  itemId: number | ''
  itemCode?: string
  itemName?: string
  quantity: number | ''
  unit: Unit
  baseQuantity?: number
}

export interface Transfer {
  id: number
  transferNumber: string
  fromBranchId: number
  fromBranchName: string
  toBranchId: number
  toBranchName: string
  transferDate: string
  status: TransferStatus
  notes: string | null
  createdAt: string
  items: TransferLine[]
}

export interface TransferRequest {
  fromBranchId: number | ''
  toBranchId: number | ''
  transferDate: string
  notes?: string
  items: {
    itemId: number | ''
    quantity: number | ''
    unit: Unit
  }[]
}

export interface PosItem {
  id: number
  itemCode: string
  barcode: string
  barcodeFormat: BarcodeFormat
  itemName: string
  categoryId: number
  categoryName: string
  unit: Unit
  rate: number
  availableStock: number
  lowStock: boolean
  taxId: number
  taxName: string
  gstPercentage: number
  taxType: TaxType
  taxInclusive: boolean
}

export interface Dashboard {
  summary: {
    totalBranches: number
    totalCategories: number
    totalItems: number
    totalSuppliers: number
    todayPurchaseAmount: number
    todaySalesAmount: number
    totalLowStockItems: number
    totalStockValue: number
  }
  branchSummary: {
    branchId: number
    branchName: string
    salesToday: number
    purchasesToday: number
    stockValue: number
    lowStockItems: number
  }[]
  recentPurchases: RecentTransaction[]
  recentSales: RecentTransaction[]
  lowStock: InventoryRow[]
  topSellingItems: {
    itemId: number
    itemName: string
    unit: Unit
    quantitySold: number
    revenue: number
  }[]
  stockSummary: {
    totalQuantity: number
    totalValue: number
    itemsInStock: number
    itemsOutOfStock: number
  }
}

export interface RecentTransaction {
  id: number
  number: string
  partyName: string | null
  branchName: string
  date: string
  amount: number
  status: string
}

export interface PurchaseReport {
  rows: {
    purchaseNumber: string
    purchaseDate: string
    supplierName: string
    branchName: string
    itemCode: string
    itemName: string
    quantity: number
    unit: Unit
    purchaseRate: number
    totalAmount: number
  }[]
  totalBaseQuantity: number
  totalAmount: number
  entries: number
}

export interface SalesReport {
  rows: {
    salesNumber: string
    salesDate: string
    branchName: string
    customerName: string | null
    itemCode: string
    itemName: string
    quantity: number
    unit: Unit
    sellingRate: number
    totalAmount: number
  }[]
  totalBaseQuantity: number
  totalAmount: number
  entries: number
}

export interface StockReport {
  rows: {
    itemCode: string
    itemName: string
    categoryName: string
    branchName: string
    openingStock: number
    purchased: number
    sold: number
    transferIn: number
    transferOut: number
    adjustmentIn: number
    adjustmentOut: number
    currentStock: number
    unit: Unit
  }[]
  totalCurrentStock: number
  totalStockValue: number
}

export interface LowStockReport {
  rows: InventoryRow[]
  total: number
}

export interface BranchReport {
  rows: {
    branchId: number
    branchName: string
    totalPurchases: number
    totalSales: number
    itemsSold: number
    stockValue: number
    lowStockItems: number
  }[]
  totals: {
    totalPurchases: number
    totalSales: number
    itemsSold: number
    stockValue: number
    lowStockItems: number
  }
}

export interface StoreReport {
  totalPurchaseAmount: number
  totalSalesAmount: number
  totalItemsSold: number
  totalInventoryQuantity: number
  totalInventoryValue: number
  totalLowStockItems: number
  branches: BranchReport['rows']
}

export interface ProfitReport {
  totalSales: number
  costOfGoodsSold: number
  grossProfit: number
  byItem: {
    itemId: number
    itemName: string
    quantitySold: number
    revenue: number
    cogs: number
    profit: number
  }[]
  byBranch: {
    branchId: number
    branchName: string
    revenue: number
    cogs: number
    profit: number
  }[]
}

export interface GstReport {
  rows: {
    branchId: number
    branchName: string
    taxName: string
    gstPercentage: number
    cgstPercentage: number
    sgstPercentage: number
    igstPercentage: number
    taxableAmount: number
    cgstAmount: number
    sgstAmount: number
    igstAmount: number
    taxAmount: number
    invoiceCount: number
  }[]
  totals: {
    taxableAmount: number
    cgstAmount: number
    sgstAmount: number
    igstAmount: number
    taxAmount: number
    invoiceCount: number
  }
}

export interface SupplierOutstandingReport {
  rows: {
    supplierId: number
    supplierCode: string
    supplierName: string
    totalPurchaseAmount: number
    totalPaid: number
    outstandingAmount: number
    billCount: number
    pendingBillCount: number
  }[]
  totals: {
    totalPurchaseAmount: number
    totalPaid: number
    outstandingAmount: number
    supplierCount: number
  }
}

export interface SupplierBillReport {
  rows: {
    supplierId: number
    supplierCode: string
    supplierName: string
    purchaseNumber: string
    invoiceNumber: string | null
    purchaseDate: string
    branchName: string
    grandTotal: number
    paidAmount: number
    balanceAmount: number
    settlementStatus: SettlementStatus
  }[]
  totals: {
    grandTotal: number
    paidAmount: number
    balanceAmount: number
    bills: number
  }
}

export interface SupplierPaymentReport {
  rows: {
    paymentNumber: string
    paymentDate: string
    supplierCode: string
    supplierName: string
    bills: string
    paymentMethod: SupplierPaymentMethod
    amount: number
    referenceNumber: string | null
    remarks: string | null
    createdByName: string | null
  }[]
  totals: {
    amount: number
    payments: number
  }
}

export interface SupplierLedgerReport {
  supplierId: number
  supplierCode: string
  supplierName: string
  openingBalance: number
  rows: {
    date: string
    type: 'PURCHASE' | 'PAYMENT'
    reference: string
    description: string
    debit: number
    credit: number
    balance: number
  }[]
  totalDebit: number
  totalCredit: number
  closingBalance: number
}

export interface ApiErrorPayload {
  status: number
  error: string
  message: string
  code?: string
  contactEmail?: string
  contactPhone?: string
  fieldErrors?: Record<string, string>
}

export type PaperWidth = 'MM58' | 'MM80'
export type PrinterPurpose = 'RECEIPT' | 'PURCHASE' | 'REPORT' | 'ALL'
export type PrintPlatform = 'WINDOWS' | 'ANDROID'
export type PrintJobStatus = 'PENDING' | 'PRINTED' | 'FAILED'
export type PrintJobChannel = PrintPlatform
export type PrintJobTrigger = 'AUTO' | 'MANUAL'

export interface PrinterConfig {
  id: number
  storeId: number
  branchId: number
  printerName: string
  ipAddress: string
  port: number
  paperWidth: PaperWidth
  purpose: PrinterPurpose
  status: StoreStatus
  lastTestAt: string | null
  lastTestMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface PrinterConfigRequest {
  printerName: string
  ipAddress: string
  port: number
  paperWidth: PaperWidth
  purpose: PrinterPurpose
  branchId: number
  status: StoreStatus
}

export interface PrintPairingCode {
  code: string
  branchId: number
  expiresAt: string
}

export interface PrintDevice {
  id: number
  deviceName: string
  platform: PrintPlatform
  status: 'ACTIVE' | 'REVOKED'
  pairedBy: string | null
  createdAt: string
  lastSeenAt: string | null
}

export interface PrintJob {
  id: number
  jobKey: string
  documentType: 'SALE_RECEIPT' | 'PURCHASE' | 'TEST'
  documentId: number | null
  documentNumber: string | null
  branchId: number
  branchName: string | null
  printerConfigId: number | null
  printerName: string | null
  printerIpAddress: string | null
  printerPort: number | null
  paperWidth: PaperWidth | null
  printerPurpose: string | null
  channel: PrintJobChannel
  trigger: PrintJobTrigger
  status: PrintJobStatus
  attempts: number
  errorMessage: string | null
  createdBy: string | null
  createdAt: string
  printedAt: string | null
  duplicate: boolean
}

export interface PrintJobRequest {
  documentType: 'SALE_RECEIPT'
  documentId: number
  channel: PrintJobChannel
  trigger: PrintJobTrigger
  printerConfigId?: number | null
}

