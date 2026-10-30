import { TriangleAlert } from 'lucide-react'
import { ReportExport } from '../../components/ReportExport'
import {
  Badge,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  LoadingState,
  type Column,
} from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { api } from '../../lib/api'
import { money, qty } from '../../lib/format'
import type { InventoryRow, LowStockReport } from '../../types'
import type { ReportFilters } from './types'

type Row = InventoryRow

export function LowStockReportView({ filters }: { filters: ReportFilters }) {
  const { data, loading, error, reload } = useApi<LowStockReport>(
    () =>
      api
        .get('/reports/low-stock', {
          params: {
            branchId: filters.branchId ?? undefined,
            categoryId: filters.categoryId || undefined,
            search: filters.search || undefined,
          },
        })
        .then((response) => response.data),
    [filters.branchId, filters.categoryId, filters.search],
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
    { key: 'min', header: 'Minimum', align: 'right', render: (row) => qty(row.minimumStock) },
    {
      key: 'short',
      header: 'Short by',
      align: 'right',
      render: (row) => (
        <span className="font-semibold text-amber-600">
          {qty(Math.max(0, Number(row.minimumStock) - Number(row.currentStock)))}
        </span>
      ),
    },
    { key: 'value', header: 'Stock value', align: 'right', render: (row) => money(row.stockValue) },
  ]

  const exportColumns = [
    { header: 'Item code', value: (row: Row) => row.itemCode },
    { header: 'Item', value: (row: Row) => row.itemName },
    { header: 'Category', value: (row: Row) => row.categoryName },
    { header: 'Branch', value: (row: Row) => row.branchName },
    { header: 'On hand', value: (row: Row) => Number(row.currentStock) },
    { header: 'Minimum', value: (row: Row) => Number(row.minimumStock) },
    {
      header: 'Short by',
      value: (row: Row) => Math.max(0, Number(row.minimumStock) - Number(row.currentStock)),
    },
    { header: 'Unit', value: (row: Row) => row.unit },
  ]

  if (error) return <Card><ErrorState message={error} onRetry={reload} /></Card>

  return (
    <Card>
      <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 className="text-sm font-bold text-slate-800">Low stock report</h2>
          <p className="text-xs text-slate-500">
            {data?.total ?? 0} items at or below reorder level · {filters.branchLabel}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <ReportExport
            title="Low stock report"
            filename="low-stock-report"
            subtitle={filters.branchLabel}
            columns={exportColumns}
            rows={data?.rows ?? []}
            summary={[`${data?.total ?? 0} low-stock records`]}
          />
        </div>
      </div>
      {loading ? (
        <LoadingState />
      ) : (
        <DataTable
          columns={columns}
          rows={data?.rows ?? []}
          rowKey={(row) => `${row.branchId}-${row.itemId}`}
          highlight={() => true}
          empty={<EmptyState icon={<TriangleAlert className="h-6 w-6" />} title="No low-stock items" message="Every item is above its minimum level for the selected filters." />}
        />
      )}
    </Card>
  )
}
