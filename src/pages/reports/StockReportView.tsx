import { Boxes, IndianRupee, PackageSearch } from 'lucide-react'
import { ReportExport } from '../../components/ReportExport'
import {
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  LoadingState,
  StatCard,
  type Column,
} from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { api } from '../../lib/api'
import { formatDate, money, qty } from '../../lib/format'
import type { StockReport } from '../../types'
import type { ReportFilters } from './types'

type Row = StockReport['rows'][number]

export function StockReportView({ filters }: { filters: ReportFilters }) {
  const { data, loading, error, reload } = useApi<StockReport>(
    () =>
      api
        .get('/reports/stock', {
          params: {
            from: filters.from || undefined,
            to: filters.to || undefined,
            branchId: filters.branchId ?? undefined,
            categoryId: filters.categoryId || undefined,
            itemId: filters.itemId || undefined,
            search: filters.search || undefined,
          },
        })
        .then((response) => response.data),
    [filters.from, filters.to, filters.branchId, filters.categoryId, filters.itemId, filters.search],
  )

  const columns: Column<Row>[] = [
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
    { key: 'branch', header: 'Branch', render: (row) => row.branchName },
    { key: 'opening', header: 'Opening', align: 'right', render: (row) => qty(row.openingStock) },
    { key: 'purchased', header: 'Purchased', align: 'right', render: (row) => <span className="text-emerald-600">+{qty(row.purchased)}</span> },
    { key: 'sold', header: 'Sold', align: 'right', render: (row) => <span className="text-rose-600">−{qty(row.sold)}</span> },
    { key: 'transferIn', header: 'Trf in', align: 'right', render: (row) => (Number(row.transferIn) ? `+${qty(row.transferIn)}` : '—') },
    { key: 'transferOut', header: 'Trf out', align: 'right', render: (row) => (Number(row.transferOut) ? `−${qty(row.transferOut)}` : '—') },
    { key: 'adjIn', header: 'Adj in', align: 'right', render: (row) => (Number(row.adjustmentIn) ? `+${qty(row.adjustmentIn)}` : '—') },
    { key: 'adjOut', header: 'Adj out', align: 'right', render: (row) => (Number(row.adjustmentOut) ? `−${qty(row.adjustmentOut)}` : '—') },
    {
      key: 'current',
      header: 'Closing',
      align: 'right',
      render: (row) => (
        <span className="font-bold text-slate-900">
          {qty(row.currentStock)} {row.unit}
        </span>
      ),
    },
  ]

  const exportColumns = [
    { header: 'Item code', value: (row: Row) => row.itemCode },
    { header: 'Item', value: (row: Row) => row.itemName },
    { header: 'Category', value: (row: Row) => row.categoryName },
    { header: 'Branch', value: (row: Row) => row.branchName },
    { header: 'Opening', value: (row: Row) => Number(row.openingStock) },
    { header: 'Purchased', value: (row: Row) => Number(row.purchased) },
    { header: 'Sold', value: (row: Row) => Number(row.sold) },
    { header: 'Transfer in', value: (row: Row) => Number(row.transferIn) },
    { header: 'Transfer out', value: (row: Row) => Number(row.transferOut) },
    { header: 'Adjustment in', value: (row: Row) => Number(row.adjustmentIn) },
    { header: 'Adjustment out', value: (row: Row) => Number(row.adjustmentOut) },
    { header: 'Closing', value: (row: Row) => Number(row.currentStock) },
    { header: 'Unit', value: (row: Row) => row.unit },
  ]

  if (error) return <Card><ErrorState message={error} onRetry={reload} /></Card>

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Closing quantity" value={qty(data?.totalCurrentStock ?? 0)} hint="Base units across rows" icon={<Boxes className="h-5 w-5" />} tone="brand" />
        <StatCard label="Inventory value" value={money(data?.totalStockValue ?? 0)} hint="At weighted average cost" icon={<IndianRupee className="h-5 w-5" />} tone="green" />
        <StatCard label="Item × branch rows" value={data?.rows.length ?? 0} icon={<PackageSearch className="h-5 w-5" />} tone="purple" />
      </div>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-bold text-slate-800">Stock movement report</h2>
            <p className="text-xs text-slate-500">
              Opening to closing for {formatDate(filters.from)} – {formatDate(filters.to)} · {filters.branchLabel}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <ReportExport
              title="Stock report"
              filename="stock-report"
              subtitle={`${formatDate(filters.from)} – ${formatDate(filters.to)} · ${filters.branchLabel}`}
              columns={exportColumns}
              rows={data?.rows ?? []}
              summary={[`Closing ${qty(data?.totalCurrentStock ?? 0)}`, `Value ${money(data?.totalStockValue ?? 0)}`]}
            />
          </div>
        </div>
        {loading ? (
          <LoadingState />
        ) : (
          <DataTable
            columns={columns}
            rows={data?.rows ?? []}
            rowKey={(row) => `${row.branchName}-${row.itemCode}`}
            empty={<EmptyState icon={<Boxes className="h-6 w-6" />} title="No stock data" message="No stock movements found for the selected period and filters." />}
          />
        )}
      </Card>
    </div>
  )
}
