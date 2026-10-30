import { Boxes, IndianRupee, ShoppingBag, TriangleAlert } from 'lucide-react'
import { ReportExport } from '../../components/ReportExport'
import {
  Badge,
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
import type { StoreReport } from '../../types'
import type { ReportFilters } from './types'

type Row = StoreReport['branches'][number]

export function StoreReportView({ filters }: { filters: ReportFilters }) {
  const { data, loading, error, reload } = useApi<StoreReport>(
    () =>
      api
        .get('/reports/store', {
          params: {
            from: filters.from || undefined,
            to: filters.to || undefined,
            branchId: filters.branchId ?? undefined,
          },
        })
        .then((response) => response.data),
    [filters.from, filters.to, filters.branchId],
  )

  const columns: Column<Row>[] = [
    { key: 'branch', header: 'Branch', render: (row) => <span className="font-semibold text-slate-800">{row.branchName}</span> },
    { key: 'purchases', header: 'Purchases', align: 'right', render: (row) => money(row.totalPurchases) },
    { key: 'sales', header: 'Sales', align: 'right', render: (row) => money(row.totalSales) },
    { key: 'items', header: 'Items sold', align: 'right', render: (row) => qty(row.itemsSold) },
    { key: 'stock', header: 'Stock value', align: 'right', render: (row) => money(row.stockValue) },
    {
      key: 'low',
      header: 'Low stock',
      align: 'right',
      render: (row) => (row.lowStockItems > 0 ? <Badge tone="red">{row.lowStockItems}</Badge> : <span className="text-emerald-600">Clear</span>),
    },
  ]

  const exportColumns = [
    { header: 'Branch', value: (row: Row) => row.branchName },
    { header: 'Purchases', value: (row: Row) => Number(row.totalPurchases) },
    { header: 'Sales', value: (row: Row) => Number(row.totalSales) },
    { header: 'Items sold', value: (row: Row) => Number(row.itemsSold) },
    { header: 'Stock value', value: (row: Row) => Number(row.stockValue) },
    { header: 'Low stock items', value: (row: Row) => row.lowStockItems },
  ]

  if (error) return <Card><ErrorState message={error} onRetry={reload} /></Card>

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Purchase total" value={money(data?.totalPurchaseAmount ?? 0)} icon={<IndianRupee className="h-5 w-5" />} tone="blue" />
        <StatCard label="Sales total" value={money(data?.totalSalesAmount ?? 0)} icon={<IndianRupee className="h-5 w-5" />} tone="green" />
        <StatCard label="Items sold" value={qty(data?.totalItemsSold ?? 0)} icon={<ShoppingBag className="h-5 w-5" />} tone="purple" />
        <StatCard label="Inventory value" value={money(data?.totalInventoryValue ?? 0)} hint={`${qty(data?.totalInventoryQuantity ?? 0)} units on hand`} icon={<Boxes className="h-5 w-5" />} tone="brand" />
      </div>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-bold text-slate-800">Store summary</h2>
            <p className="text-xs text-slate-500">
              {formatDate(filters.from)} – {formatDate(filters.to)} · {data?.totalLowStockItems ?? 0} low-stock records
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <ReportExport
              title="Store report"
              filename="store-report"
              subtitle={`${formatDate(filters.from)} – ${formatDate(filters.to)}`}
              columns={exportColumns}
              rows={data?.branches ?? []}
              summary={[`Sales ${money(data?.totalSalesAmount ?? 0)}`, `Inventory ${money(data?.totalInventoryValue ?? 0)}`]}
            />
          </div>
        </div>
        {loading ? (
          <LoadingState />
        ) : (
          <DataTable
            columns={columns}
            rows={data?.branches ?? []}
            rowKey={(row) => row.branchId}
            empty={<EmptyState icon={<TriangleAlert className="h-6 w-6" />} title="No store data" message="No accessible branches found for this selection." />}
          />
        )}
      </Card>
    </div>
  )
}
