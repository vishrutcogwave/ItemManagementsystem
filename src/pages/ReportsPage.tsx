import { Building2, CalendarRange, RotateCcw } from 'lucide-react'
import { useState } from 'react'
import { Button, Card, Input, PageHeader, SearchInput, Select, Tabs } from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api } from '../lib/api'
import { monthStart, today } from '../lib/format'
import { useAuthStore } from '../store/auth'
import type { Category, Item, Supplier } from '../types'
import { BranchReportView } from './reports/BranchReportView'
import { GstReportView } from './reports/GstReportView'
import { LowStockReportView } from './reports/LowStockReportView'
import { ProfitReportView } from './reports/ProfitReportView'
import { PurchaseReportView } from './reports/PurchaseReportView'
import { SalesReportView } from './reports/SalesReportView'
import { StockReportView } from './reports/StockReportView'
import { StoreReportView } from './reports/StoreReportView'
import { SupplierBillsReportView } from './reports/SupplierBillsReportView'
import { SupplierLedgerReportView } from './reports/SupplierLedgerReportView'
import { SupplierOutstandingReportView } from './reports/SupplierOutstandingReportView'
import { SupplierPaymentsReportView } from './reports/SupplierPaymentsReportView'
import type { ReportFilters } from './reports/types'

const TABS = [
  { key: 'sales', label: 'Sales' },
  { key: 'purchases', label: 'Purchases' },
  { key: 'stock', label: 'Stock movement' },
  { key: 'profit', label: 'Profit' },
  { key: 'gst', label: 'GST' },
  { key: 'branches', label: 'Branch performance' },
  { key: 'store', label: 'Store summary' },
  { key: 'low-stock', label: 'Low stock' },
  { key: 'supplier-outstanding', label: 'Supplier outstanding' },
  { key: 'supplier-bills', label: 'Bill-wise outstanding' },
  { key: 'supplier-payments', label: 'Supplier payments' },
  { key: 'supplier-ledger', label: 'Supplier ledger' },
]

const ITEM_FILTER_TABS = ['sales', 'purchases', 'stock', 'profit', 'gst']
const SUPPLIER_FILTER_TABS = ['supplier-outstanding', 'supplier-bills', 'supplier-payments', 'supplier-ledger']
const NO_DATE_TABS = ['low-stock', 'supplier-outstanding']

export function ReportsPage() {
  const { selectedBranchId, allBranches, branches } = useAuthStore()
  const [tab, setTab] = useState('sales')
  const [from, setFrom] = useState(monthStart())
  const [to, setTo] = useState(today())
  const [categoryId, setCategoryId] = useState('')
  const [itemId, setItemId] = useState('')
  const [supplierId, setSupplierId] = useState('')
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebounce(search)

  const { data: categories } = useApi<Category[]>(
    () => api.get('/categories/options').then((response) => response.data),
    [],
  )
  const { data: items } = useApi<Item[]>(() => api.get('/items/options').then((response) => response.data), [])
  const { data: suppliers } = useApi<Supplier[]>(
    () => api.get('/suppliers/options').then((response) => response.data),
    [],
  )

  const showItems = ITEM_FILTER_TABS.includes(tab)
  const showSuppliers = SUPPLIER_FILTER_TABS.includes(tab)
  const showDates = !NO_DATE_TABS.includes(tab)

  const branchLabel = selectedBranchId
    ? (branches.find((branch) => branch.id === selectedBranchId)?.branchName ?? 'Selected branch')
    : allBranches
      ? 'All branches'
      : 'Assigned branches'

  const filters: ReportFilters = {
    from,
    to,
    branchId: selectedBranchId,
    branchLabel,
    categoryId,
    itemId,
    supplierId,
    search: debouncedSearch,
  }

  const reset = () => {
    setFrom(monthStart())
    setTo(today())
    setCategoryId('')
    setItemId('')
    setSupplierId('')
    setSearch('')
  }

  return (
    <div>
      <PageHeader
        title="Reports & analytics"
        subtitle="Operational and financial reports across your accessible branches."
      />

      <Card className="mb-4 p-4">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2 sm:gap-3">
            {showDates && (
              <div className="flex w-full min-w-0 items-center gap-2 sm:w-auto sm:flex-none">
                <CalendarRange className="h-4 w-4 shrink-0 text-slate-400" />
                <Input
                  type="date"
                  className="date-compact flex-1 sm:w-38 sm:flex-none"
                  value={from}
                  max={to}
                  onChange={(event) => setFrom(event.target.value)}
                />
                <span className="text-xs text-slate-400">to</span>
                <Input
                  type="date"
                  className="date-compact flex-1 sm:w-38 sm:flex-none"
                  value={to}
                  min={from}
                  max={today()}
                  onChange={(event) => setTo(event.target.value)}
                />
              </div>
            )}
            <span className="inline-flex max-w-full min-w-0 items-center gap-1.5 truncate rounded-full bg-slate-100 px-3 py-1 text-[11px] font-semibold text-slate-600">
              <Building2 className="h-3.5 w-3.5 shrink-0" /> {branchLabel}
            </span>
            <Button
              variant="ghost"
              size="sm"
              className="ml-auto"
              icon={<RotateCcw className="h-3.5 w-3.5" />}
              onClick={reset}
            >
              Reset
            </Button>
          </div>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            {showItems && (
              <>
                <Select className="sm:w-44" value={categoryId} onChange={(event) => setCategoryId(event.target.value)}>
                  <option value="">All categories</option>
                  {categories?.map((category) => (
                    <option key={category.id} value={category.id}>
                      {category.categoryName}
                    </option>
                  ))}
                </Select>
                <Select className="sm:w-48" value={itemId} onChange={(event) => setItemId(event.target.value)}>
                  <option value="">All items</option>
                  {items?.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.itemName}
                    </option>
                  ))}
                </Select>
              </>
            )}
            {showSuppliers && (
              <Select className="sm:w-56" value={supplierId} onChange={(event) => setSupplierId(event.target.value)}>
                <option value="">All suppliers</option>
                {suppliers?.map((supplier) => (
                  <option key={supplier.id} value={supplier.id}>
                    {supplier.supplierName}
                  </option>
                ))}
              </Select>
            )}
            <SearchInput
              className="sm:min-w-40 sm:flex-1"
              placeholder={showSuppliers ? 'Search supplier, bill or reference…' : 'Search item or code…'}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </div>
          <Tabs tabs={TABS} value={tab} onChange={setTab} />
        </div>
      </Card>

      {tab === 'sales' && <SalesReportView filters={filters} />}
      {tab === 'purchases' && <PurchaseReportView filters={filters} />}
      {tab === 'stock' && <StockReportView filters={filters} />}
      {tab === 'profit' && <ProfitReportView filters={filters} />}
      {tab === 'gst' && <GstReportView filters={filters} />}
      {tab === 'branches' && <BranchReportView filters={filters} />}
      {tab === 'store' && <StoreReportView filters={filters} />}
      {tab === 'low-stock' && <LowStockReportView filters={filters} />}
      {tab === 'supplier-outstanding' && <SupplierOutstandingReportView filters={filters} />}
      {tab === 'supplier-bills' && <SupplierBillsReportView filters={filters} />}
      {tab === 'supplier-payments' && <SupplierPaymentsReportView filters={filters} />}
      {tab === 'supplier-ledger' && <SupplierLedgerReportView filters={filters} />}
    </div>
  )
}
