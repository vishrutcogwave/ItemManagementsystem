import { IndianRupee, TrendingDown, TrendingUp } from 'lucide-react'
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
import type { ProfitReport } from '../../types'
import type { ReportFilters } from './types'

type ItemRow = ProfitReport['byItem'][number]
type BranchRow = ProfitReport['byBranch'][number]

function margin(revenue: number, profit: number): string {
  const base = Number(revenue)
  if (base <= 0) return '—'
  return `${((Number(profit) / base) * 100).toFixed(1)}%`
}

export function ProfitReportView({ filters }: { filters: ReportFilters }) {
  const { data, loading, error, reload } = useApi<ProfitReport>(
    () =>
      api
        .get('/reports/profit', {
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

  const itemColumns: Column<ItemRow>[] = [
    { key: 'item', header: 'Item', render: (row) => <span className="font-semibold text-slate-800">{row.itemName}</span> },
    { key: 'qty', header: 'Qty sold', align: 'right', render: (row) => qty(row.quantitySold) },
    { key: 'revenue', header: 'Revenue', align: 'right', render: (row) => money(row.revenue) },
    { key: 'cogs', header: 'COGS', align: 'right', render: (row) => <span className="text-slate-500">{money(row.cogs)}</span> },
    {
      key: 'profit',
      header: 'Gross profit',
      align: 'right',
      render: (row) => (
        <span className={`font-bold ${Number(row.profit) >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>{money(row.profit)}</span>
      ),
    },
    { key: 'margin', header: 'Margin', align: 'right', render: (row) => margin(row.revenue, row.profit) },
  ]

  const branchColumns: Column<BranchRow>[] = [
    { key: 'branch', header: 'Branch', render: (row) => <span className="font-semibold text-slate-800">{row.branchName}</span> },
    { key: 'revenue', header: 'Revenue', align: 'right', render: (row) => money(row.revenue) },
    { key: 'cogs', header: 'COGS', align: 'right', render: (row) => <span className="text-slate-500">{money(row.cogs)}</span> },
    {
      key: 'profit',
      header: 'Gross profit',
      align: 'right',
      render: (row) => (
        <span className={`font-bold ${Number(row.profit) >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>{money(row.profit)}</span>
      ),
    },
    { key: 'margin', header: 'Margin', align: 'right', render: (row) => margin(row.revenue, row.profit) },
  ]

  const itemExport = [
    { header: 'Item', value: (row: ItemRow) => row.itemName },
    { header: 'Quantity sold', value: (row: ItemRow) => Number(row.quantitySold) },
    { header: 'Revenue', value: (row: ItemRow) => Number(row.revenue) },
    { header: 'COGS', value: (row: ItemRow) => Number(row.cogs) },
    { header: 'Gross profit', value: (row: ItemRow) => Number(row.profit) },
  ]

  const branchExport = [
    { header: 'Branch', value: (row: BranchRow) => row.branchName },
    { header: 'Revenue', value: (row: BranchRow) => Number(row.revenue) },
    { header: 'COGS', value: (row: BranchRow) => Number(row.cogs) },
    { header: 'Gross profit', value: (row: BranchRow) => Number(row.profit) },
  ]

  if (error) return <Card><ErrorState message={error} onRetry={reload} /></Card>

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Sales revenue" value={money(data?.totalSales ?? 0)} icon={<IndianRupee className="h-5 w-5" />} tone="brand" />
        <StatCard label="Cost of goods sold" value={money(data?.costOfGoodsSold ?? 0)} hint="At sale-time weighted cost" icon={<TrendingDown className="h-5 w-5" />} tone="amber" />
        <StatCard
          label="Gross profit"
          value={money(data?.grossProfit ?? 0)}
          hint={data ? `Margin ${margin(data.totalSales, data.grossProfit)}` : undefined}
          icon={<TrendingUp className="h-5 w-5" />}
          tone={Number(data?.grossProfit ?? 0) >= 0 ? 'green' : 'rose'}
        />
      </div>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-bold text-slate-800">Profit by item</h2>
            <p className="text-xs text-slate-500">
              {formatDate(filters.from)} – {formatDate(filters.to)} · {filters.branchLabel}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <ReportExport
              title="Profit by item"
              filename="profit-by-item"
              subtitle={`${formatDate(filters.from)} – ${formatDate(filters.to)} · ${filters.branchLabel}`}
              columns={itemExport}
              rows={data?.byItem ?? []}
              summary={[`Revenue ${money(data?.totalSales ?? 0)}`, `Profit ${money(data?.grossProfit ?? 0)}`]}
            />
          </div>
        </div>
        {loading ? (
          <LoadingState />
        ) : (
          <DataTable
            columns={itemColumns}
            rows={data?.byItem ?? []}
            rowKey={(row) => row.itemId}
            empty={<EmptyState title="No sales in this range" message="Profit is calculated from completed sales in the selected period." />}
          />
        )}
      </Card>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <h2 className="text-sm font-bold text-slate-800">Profit by branch</h2>
          <div className="flex flex-wrap gap-2">
            <ReportExport
              title="Profit by branch"
              filename="profit-by-branch"
              subtitle={`${formatDate(filters.from)} – ${formatDate(filters.to)}`}
              columns={branchExport}
              rows={data?.byBranch ?? []}
              summary={[`Revenue ${money(data?.totalSales ?? 0)}`, `Profit ${money(data?.grossProfit ?? 0)}`]}
            />
          </div>
        </div>
        {!loading && (
          <DataTable
            columns={branchColumns}
            rows={data?.byBranch ?? []}
            rowKey={(row) => row.branchId}
            empty={<EmptyState title="No branch profit data" message="Completed sales are needed to calculate branch profit." />}
          />
        )}
      </Card>
    </div>
  )
}
