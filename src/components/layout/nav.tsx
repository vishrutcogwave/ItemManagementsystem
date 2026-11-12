import {
  ArrowLeftRight,
  BarChart3,
  Boxes,
  ClipboardList,
  FileSpreadsheet,
  LayoutDashboard,
  Package,
  Percent,
  Printer,
  ReceiptText,
  Ruler,
  ScrollText,
  Settings,
  ShoppingCart,
  SquarePen,
  Store,
  Tags,
  Truck,
  TriangleAlert,
  UserCog,
  Wallet,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  permission: string
  end?: boolean
}

export interface NavGroup {
  label: string
  items: NavItem[]
}

export const storeNav: NavGroup[] = [
  {
    label: 'Overview',
    items: [{ to: '/', label: 'Dashboard', icon: LayoutDashboard, permission: 'DASHBOARD_VIEW', end: true }],
  },
  {
    label: 'Sell',
    items: [
      { to: '/pos', label: 'POS Billing', icon: ShoppingCart, permission: 'SALES_MANAGE' },
      { to: '/sales/new', label: 'Sales Entry', icon: SquarePen, permission: 'SALES_MANAGE' },
      { to: '/sales', label: 'Sales History', icon: ReceiptText, permission: 'SALES_VIEW' },
      { to: '/print-history', label: 'Print History', icon: Printer, permission: 'SALES_VIEW' },
    ],
  },
  {
    label: 'Catalog',
    items: [
      { to: '/items', label: 'Items', icon: Package, permission: 'ITEM_VIEW' },
      { to: '/items/imports', label: 'Import History', icon: FileSpreadsheet, permission: 'ITEM_VIEW' },
      { to: '/categories', label: 'Categories', icon: Tags, permission: 'CATEGORY_VIEW' },
      { to: '/taxes', label: 'Tax Master', icon: Percent, permission: 'TAX_VIEW' },
      { to: '/units', label: 'Unit Master', icon: Ruler, permission: 'UNIT_VIEW' },
      { to: '/suppliers', label: 'Suppliers', icon: Truck, permission: 'SUPPLIER_VIEW' },
    ],
  },
  {
    label: 'Stock',
    items: [
      { to: '/purchases', label: 'Purchases', icon: ClipboardList, permission: 'PURCHASE_VIEW' },
      { to: '/supplier-payments', label: 'Supplier Outstanding', icon: Wallet, permission: 'PURCHASE_VIEW' },
      { to: '/inventory', label: 'Inventory', icon: Boxes, permission: 'INVENTORY_VIEW' },
      { to: '/transfers', label: 'Stock Transfers', icon: ArrowLeftRight, permission: 'TRANSFER_VIEW' },
      { to: '/low-stock', label: 'Low Stock', icon: TriangleAlert, permission: 'INVENTORY_VIEW' },
    ],
  },
  {
    label: 'Insights',
    items: [{ to: '/reports', label: 'Reports', icon: BarChart3, permission: 'REPORT_VIEW' }],
  },
  {
    label: 'Administration',
    items: [{ to: '/settings', label: 'Store Settings', icon: Settings, permission: 'STORE_VIEW' }],
  },
]

export const platformNav: NavGroup[] = [
  {
    label: 'Platform',
    items: [
      { to: '/platform/stores', label: 'Stores', icon: Store, permission: 'PLATFORM_MANAGE' },
      { to: '/platform/accounts', label: 'Store Accounts', icon: UserCog, permission: 'PLATFORM_MANAGE' },
      { to: '/platform/audit', label: 'Audit Log', icon: ScrollText, permission: 'PLATFORM_MANAGE' },
    ],
  },
]

export function pageTitle(pathname: string): string {
  const all = [...storeNav.flatMap((group) => group.items), ...platformNav.flatMap((group) => group.items)]
  if (pathname === '/') return 'Dashboard'
  const match = [...all]
    .sort((a, b) => b.to.length - a.to.length)
    .find((item) => item.to !== '/' && (pathname === item.to || pathname.startsWith(`${item.to}/`)))
  return match?.label ?? 'Item Management'
}
