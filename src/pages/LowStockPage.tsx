import { Download, FileText, Printer, TriangleAlert } from 'lucide-react'
import { useMemo, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  PageHeader,
  SearchInput,
  Select,
  StatCard,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api } from '../lib/api'
import { exportExcel, exportPdf, printHtml } from '../lib/export'
import { money, qty } from '../lib/format'
import { useAuthStore } from '../store/auth'
import type { Category, InventoryRow } from '../types'

export function LowStockPage() {
  const { selectedBranchId, allBranches, branches, hasPermission, store } = useAuthStore()
  const canAdjust = hasPermission('INVENTORY_ADJUST')

  const [search, setSearch] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<InventoryRow[]>(
    () =>
      api
        .get('/inventory/low-stock', { params: { branchId: selectedBranchId ?? undefined, limit: 500 } })
        .then((response) => response.data),
    [selectedBranchId],
  )

  const { data: categories } = useApi<Category[]>(
    () => api.get('/categories/options').then((response) => response.data),
    [],
  )

  const rows = useMemo(() => {
    const needle = debouncedSearch.trim().toLowerCase()
    return (data ?? []).filter((row) => {
      const categoryMatch = !categoryId || row.categoryId === Number(categoryId)
      const searchMatch =
        !needle ||
        row.itemName.toLowerCase().includes(needle) ||
        row.itemCode.toLowerCase().includes(needle) ||
        row.branchName.toLowerCase().includes(needle)
      return categoryMatch && searchMatch
    })
  }, [data, debouncedSearch, categoryId])

  const branchLabel = selectedBranchId
    ? branches.find((branch) => branch.id === selectedBranchId)?.branchName
    : allBranches
      ? 'All branches'
      : 'Assigned branches'

  const shortageValue = rows.reduce((sum, row) => sum + Number(row.stockValue), 0)
  const branchesAffected = new Set(rows.map((row) => row.branchId)).size

  const columns: Column<InventoryRow>[] = [
    {
      key: 'item',
      header: 'Item',
      render: (row) => (
        <div>
          <p className="font-semibold text-slate-800">{row.itemName}</p>
          <p className="text-[11px] text-slate-400">
            {row.itemCode} · {row.categoryName}
          </p>
        </div>
      ),
    },
    { key: 'branch', header: 'Branch', render: (row) => <Badge tone="slate">{row.branchName}</Badge> },
    {
      key: 'stock',
      header: 'On hand',
      align: 'right',
      render: (row) => (
        <span className="font-bold text-rose-600">
          {qty(row.currentStock)} {row.unit}
        </span>
      ),
    },
    { key: 'min', header: 'Minimum', align: 'right', render: (row) => <span className="text-slate-600">{qty(row.minimumStock)}</span> },
    {
      key: 'shortage',
      header: 'Short by',
      align: 'right',
      render: (row) => (
        <span className="font-semibold text-amber-600">
          {qty(Math.max(0, Number(row.minimumStock) - Number(row.currentStock)))} {row.unit}
        </span>
      ),
    },
    { key: 'selling', header: 'Selling ₹', align: 'right', render: (row) => money(row.sellingPrice) },
    { key: 'value', header: 'Stock value', align: 'right', render: (row) => money(row.stockValue) },
  ]

  const exportColumns = [
    { header: 'Item code', value: (row: InventoryRow) => row.itemCode },
    { header: 'Item', value: (row: InventoryRow) => row.itemName },
    { header: 'Category', value: (row: InventoryRow) => row.categoryName },
    { header: 'Branch', value: (row: InventoryRow) => row.branchName },
    { header: 'On hand', value: (row: InventoryRow) => Number(row.currentStock) },
    { header: 'Minimum', value: (row: InventoryRow) => Number(row.minimumStock) },
    {
      header: 'Short by',
      value: (row: InventoryRow) => Math.max(0, Number(row.minimumStock) - Number(row.currentStock)),
    },
    { header: 'Unit', value: (row: InventoryRow) => row.unit },
    { header: 'Selling price', value: (row: InventoryRow) => Number(row.sellingPrice) },
  ]

  const exportRows = () => exportExcel(`low-stock-${branchLabel}`, 'Low stock', exportColumns, rows)
  const exportPdfFile = () =>
    exportPdf({
      title: 'Low stock report',
      filename: `low-stock-${branchLabel}`,
      subtitle: `${store?.storeName ?? 'Store'} · ${branchLabel}`,
      columns: exportColumns,
      rows,
      summary: [`${rows.length} low-stock records`, `${branchesAffected} branches affected`],
    })

  const print = () => {
    const body = rows
      .map(
        (row) => `<tr>
          <td>${row.itemName}<div class="muted">${row.itemCode}</div></td>
          <td>${row.categoryName}</td>
          <td>${row.branchName}</td>
          <td class="right">${qty(row.currentStock)} ${row.unit}</td>
          <td class="right">${qty(row.minimumStock)}</td>
          <td class="right">${qty(Math.max(0, Number(row.minimumStock) - Number(row.currentStock)))}</td>
        </tr>`,
      )
      .join('')
    printHtml(
      `<div class="title-row"><div><p class="brand">${store?.storeName ?? 'Store'}</p><h1>Low stock report</h1><p class="muted">${branchLabel}</p></div><p class="muted">${new Date().toLocaleString('en-IN')}</p></div>
      <table><thead><tr><th>Item</th><th>Category</th><th>Branch</th><th class="right">On hand</th><th class="right">Minimum</th><th class="right">Short by</th></tr></thead><tbody>${body}</tbody></table>
      <p class="muted" style="margin-top:14px">${rows.length} low-stock records across ${branchesAffected} branches.</p>`,
      'Low stock report',
    )
  }

  return (
    <div>
      <PageHeader
        title="Low stock alerts"
        subtitle="Items at or below their reorder level, across the branches you can access."
        actions={
          <>
            <Button variant="outline" size="sm" icon={<Download className="h-4 w-4" />} onClick={exportRows} disabled={rows.length === 0}>
              Excel
            </Button>
            <Button variant="outline" size="sm" icon={<FileText className="h-4 w-4" />} onClick={exportPdfFile} disabled={rows.length === 0}>
              PDF
            </Button>
            <Button variant="outline" size="sm" icon={<Printer className="h-4 w-4" />} onClick={print} disabled={rows.length === 0}>
              Print
            </Button>
          </>
        }
      />

      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard
          label="Low stock records"
          value={rows.length}
          hint={branchLabel}
          icon={<TriangleAlert className="h-5 w-5" />}
          tone="rose"
        />
        <StatCard label="Branches affected" value={branchesAffected} icon={<TriangleAlert className="h-5 w-5" />} tone="amber" />
        <StatCard label="Value at risk" value={money(shortageValue)} hint="Current value of these items" icon={<TriangleAlert className="h-5 w-5" />} tone="purple" />
      </div>

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center">
          <SearchInput
            className="sm:w-80"
            placeholder="Search item, code or branch…"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <Select className="sm:w-48" value={categoryId} onChange={(event) => setCategoryId(event.target.value)}>
            <option value="">All categories</option>
            {categories?.map((category) => (
              <option key={category.id} value={category.id}>
                {category.categoryName}
              </option>
            ))}
          </Select>
          <span className="text-xs text-slate-500 sm:ml-auto">
            {canAdjust ? 'Use Inventory to receive or adjust stock.' : 'Contact a stock manager to replenish.'}
          </span>
        </div>

        {error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(row) => `${row.branchId}-${row.itemId}`}
            loading={loading}
            highlight={() => true}
            empty={
              <EmptyState
                icon={<TriangleAlert className="h-6 w-6" />}
                title="No low-stock items"
                message="Every item is above its minimum stock level for the selected branch."
              />
            }
          />
        )}
      </Card>
    </div>
  )
}
