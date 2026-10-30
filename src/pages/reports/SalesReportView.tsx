import { IndianRupee, Package, ReceiptText } from 'lucide-react'
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
import type { SalesReport } from '../../types'
import type { ReportFilters } from './types'

type Row = SalesReport['rows'][number]

export function SalesReportView({ filters }: { filters: ReportFilters }) {
  const { data, loading, error, reload } = useApi<SalesReport>(
    () =>
      api
        .get('/reports/sales', {
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
    { key: 'number', header: 'Invoice', render: (row) => <span className="font-semibold text-slate-800">{row.salesNumber}</span> },
    { key: 'date', header: 'Date', render: (row) => formatDate(row.salesDate) },
    { key: 'customer', header: 'Customer', render: (row) => row.customerName || 'Walk-in' },
    { key: 'branch', header: 'Branch', render: (row) => row.branchName },
    {
      key: 'item',
      header: 'Item',
      render: (row) => (
        <div>
          <p className="font-medium text-slate-700">{row.itemName}</p>
          <p className="text-[11px] text-slate-400">{row.itemCode}</p>
        </div>
      ),
    },
    { key: 'qty', header: 'Qty', align: 'right', render: (row) => `${qty(row.quantity)} ${row.unit}` },
    { key: 'rate', header: 'Rate', align: 'right', render: (row) => money(row.sellingRate) },
    { key: 'amount', header: 'Amount', align: 'right', render: (row) => <span className="font-semibold">{money(row.totalAmount)}</span> },
  ]

  const exportColumns = [
    { header: 'Invoice', value: (row: Row) => row.salesNumber },
    { header: 'Date', value: (row: Row) => row.salesDate },
    { header: 'Customer', value: (row: Row) => row.customerName || 'Walk-in' },
    { header: 'Branch', value: (row: Row) => row.branchName },
    { header: 'Item code', value: (row: Row) => row.itemCode },
    { header: 'Item', value: (row: Row) => row.itemName },
    { header: 'Quantity', value: (row: Row) => Number(row.quantity) },
    { header: 'Unit', value: (row: Row) => row.unit },
    { header: 'Rate', value: (row: Row) => Number(row.sellingRate) },
    { header: 'Amount', value: (row: Row) => Number(row.totalAmount) },
  ]

  if (error) return <Card><ErrorState message={error} onRetry={reload} /></Card>

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Sales value" value={money(data?.totalAmount ?? 0)} icon={<IndianRupee className="h-5 w-5" />} tone="green" />
        <StatCard label="Entries" value={data?.entries ?? 0} hint="Invoice lines in range" icon={<ReceiptText className="h-5 w-5" />} tone="brand" />
        <StatCard label="Quantity sold" value={qty(data?.totalBaseQuantity ?? 0)} hint="Total base units" icon={<Package className="h-5 w-5" />} tone="purple" />
      </div>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-bold text-slate-800">Sales report</h2>
            <p className="text-xs text-slate-500">
              {formatDate(filters.from)} – {formatDate(filters.to)} · {filters.branchLabel}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <ReportExport
              title="Sales report"
              filename="sales-report"
              subtitle={`${formatDate(filters.from)} – ${formatDate(filters.to)} · ${filters.branchLabel}`}
              columns={exportColumns}
              rows={data?.rows ?? []}
              summary={[`Total ${money(data?.totalAmount ?? 0)}`, `${data?.entries ?? 0} entries`]}
            />
          </div>
        </div>
        {loading ? (
          <LoadingState />
        ) : (
          <DataTable
            columns={columns}
            rows={data?.rows ?? []}
            rowKey={(row) => `${row.salesNumber}-${row.itemCode}-${row.quantity}`}
            empty={<EmptyState icon={<ReceiptText className="h-6 w-6" />} title="No sales in this range" message="Adjust the dates or filters to see sales entries." />}
          />
        )}
      </Card>
    </div>
  )
}
