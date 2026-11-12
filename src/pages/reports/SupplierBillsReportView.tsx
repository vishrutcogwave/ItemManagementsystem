import { FileClock, IndianRupee, Receipt } from 'lucide-react'
import { ReportExport } from '../../components/ReportExport'
import {
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  LoadingState,
  StatCard,
  StatusBadge,
  type Column,
} from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { api } from '../../lib/api'
import { formatDate, money } from '../../lib/format'
import type { SupplierBillReport } from '../../types'
import type { ReportFilters } from './types'

type Row = SupplierBillReport['rows'][number]

export function SupplierBillsReportView({ filters }: { filters: ReportFilters }) {
  const { data, loading, error, reload } = useApi<SupplierBillReport>(
    () =>
      api
        .get('/reports/supplier-bills', {
          params: {
            from: filters.from || undefined,
            to: filters.to || undefined,
            branchId: filters.branchId ?? undefined,
            supplierId: filters.supplierId || undefined,
            search: filters.search || undefined,
          },
        })
        .then((response) => response.data),
    [filters.from, filters.to, filters.branchId, filters.supplierId, filters.search],
  )

  const columns: Column<Row>[] = [
    {
      key: 'bill',
      header: 'Purchase bill',
      render: (row) => (
        <div>
          <p className="font-semibold text-slate-800">{row.purchaseNumber}</p>
          <p className="text-[11px] text-slate-400">{row.invoiceNumber ? `Invoice ${row.invoiceNumber}` : 'No invoice no.'}</p>
        </div>
      ),
    },
    { key: 'date', header: 'Date', render: (row) => formatDate(row.purchaseDate) },
    {
      key: 'supplier',
      header: 'Supplier',
      render: (row) => (
        <div>
          <p className="font-medium text-slate-700">{row.supplierName}</p>
          <p className="text-[11px] text-slate-400">{row.supplierCode}</p>
        </div>
      ),
    },
    { key: 'branch', header: 'Branch', render: (row) => row.branchName },
    { key: 'total', header: 'Purchase amount', align: 'right', render: (row) => money(row.grandTotal) },
    { key: 'paid', header: 'Paid', align: 'right', render: (row) => <span className="text-emerald-600">{money(row.paidAmount)}</span> },
    {
      key: 'balance',
      header: 'Outstanding',
      align: 'right',
      render: (row) => (
        <span className={Number(row.balanceAmount) > 0 ? 'font-semibold text-rose-600' : 'text-slate-500'}>
          {money(row.balanceAmount)}
        </span>
      ),
    },
    { key: 'status', header: 'Settlement', render: (row) => <StatusBadge status={row.settlementStatus} /> },
  ]

  const exportColumns = [
    { header: 'Purchase bill', value: (row: Row) => row.purchaseNumber },
    { header: 'Invoice', value: (row: Row) => row.invoiceNumber ?? '' },
    { header: 'Date', value: (row: Row) => row.purchaseDate },
    { header: 'Supplier code', value: (row: Row) => row.supplierCode },
    { header: 'Supplier', value: (row: Row) => row.supplierName },
    { header: 'Branch', value: (row: Row) => row.branchName },
    { header: 'Purchase amount', value: (row: Row) => Number(row.grandTotal) },
    { header: 'Paid', value: (row: Row) => Number(row.paidAmount) },
    { header: 'Outstanding', value: (row: Row) => Number(row.balanceAmount) },
    { header: 'Settlement', value: (row: Row) => row.settlementStatus },
  ]

  if (error) return <Card><ErrorState message={error} onRetry={reload} /></Card>

  const totals = data?.totals

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Purchase amount" value={money(totals?.grandTotal ?? 0)} icon={<IndianRupee className="h-5 w-5" />} tone="blue" />
        <StatCard label="Paid" value={money(totals?.paidAmount ?? 0)} icon={<Receipt className="h-5 w-5" />} tone="green" />
        <StatCard
          label="Outstanding"
          value={money(totals?.balanceAmount ?? 0)}
          hint={`${totals?.bills ?? 0} bills`}
          icon={<FileClock className="h-5 w-5" />}
          tone="rose"
        />
      </div>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-bold text-slate-800">Bill-wise outstanding</h2>
            <p className="text-xs text-slate-500">
              {formatDate(filters.from)} – {formatDate(filters.to)} · {filters.branchLabel}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <ReportExport
              title="Bill-wise outstanding report"
              filename="supplier-bills"
              subtitle={`${formatDate(filters.from)} – ${formatDate(filters.to)} · ${filters.branchLabel}`}
              columns={exportColumns}
              rows={data?.rows ?? []}
              summary={[
                `Purchase ${money(totals?.grandTotal ?? 0)}`,
                `Paid ${money(totals?.paidAmount ?? 0)}`,
                `Outstanding ${money(totals?.balanceAmount ?? 0)}`,
              ]}
            />
          </div>
        </div>
        {loading ? (
          <LoadingState />
        ) : (
          <DataTable
            columns={columns}
            rows={data?.rows ?? []}
            rowKey={(row) => row.purchaseNumber}
            highlight={(row) => Number(row.balanceAmount) > 0}
            empty={<EmptyState icon={<FileClock className="h-6 w-6" />} title="No purchase bills in this range" message="Adjust the dates or filters to see supplier bills." />}
          />
        )}
      </Card>
    </div>
  )
}
