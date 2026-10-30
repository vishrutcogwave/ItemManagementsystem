import { Building2, IndianRupee, ShoppingBag } from 'lucide-react'
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
import type { BranchReport } from '../../types'
import type { ReportFilters } from './types'

type Row = BranchReport['rows'][number]

export function BranchReportView({ filters }: { filters: ReportFilters }) {
  const { data, loading, error, reload } = useApi<BranchReport>(
    () =>
      api
        .get('/reports/branches', {
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
    {
      key: 'branch',
      header: 'Branch',
      render: (row) => (
        <div className="flex items-center gap-2">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
            <Building2 className="h-4 w-4" />
          </span>
          <span className="font-semibold text-slate-800">{row.branchName}</span>
        </div>
      ),
    },
    { key: 'purchases', header: 'Purchases', align: 'right', render: (row) => money(row.totalPurchases) },
    { key: 'sales', header: 'Sales', align: 'right', render: (row) => <span className="font-semibold text-slate-800">{money(row.totalSales)}</span> },
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
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Total purchases" value={money(data?.totals.totalPurchases ?? 0)} icon={<IndianRupee className="h-5 w-5" />} tone="blue" />
        <StatCard label="Total sales" value={money(data?.totals.totalSales ?? 0)} icon={<IndianRupee className="h-5 w-5" />} tone="green" />
        <StatCard label="Items sold" value={qty(data?.totals.itemsSold ?? 0)} icon={<ShoppingBag className="h-5 w-5" />} tone="purple" />
      </div>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-bold text-slate-800">Branch comparison</h2>
            <p className="text-xs text-slate-500">
              {formatDate(filters.from)} – {formatDate(filters.to)}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <ReportExport
              title="Branch report"
              filename="branch-report"
              subtitle={`${formatDate(filters.from)} – ${formatDate(filters.to)}`}
              columns={exportColumns}
              rows={data?.rows ?? []}
              summary={[`Sales ${money(data?.totals.totalSales ?? 0)}`, `Stock ${money(data?.totals.stockValue ?? 0)}`]}
            />
          </div>
        </div>
        {loading ? (
          <LoadingState />
        ) : (
          <DataTable
            columns={columns}
            rows={data?.rows ?? []}
            rowKey={(row) => row.branchId}
            empty={<EmptyState title="No branch data" message="No accessible branches found for this selection." />}
          />
        )}
      </Card>
    </div>
  )
}
