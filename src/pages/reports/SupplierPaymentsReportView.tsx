import { Calculator, ReceiptText, Wallet } from 'lucide-react'
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
import { formatDate, money } from '../../lib/format'
import type { SupplierPaymentReport } from '../../types'
import type { ReportFilters } from './types'

type Row = SupplierPaymentReport['rows'][number]

const methodLabel: Record<string, string> = {
  CASH: 'Cash',
  BANK: 'Bank',
  UPI: 'UPI',
  CARD: 'Card',
}

export function SupplierPaymentsReportView({ filters }: { filters: ReportFilters }) {
  const { data, loading, error, reload } = useApi<SupplierPaymentReport>(
    () =>
      api
        .get('/reports/supplier-payments', {
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
    { key: 'number', header: 'Payment', render: (row) => <span className="font-semibold text-slate-800">{row.paymentNumber}</span> },
    { key: 'date', header: 'Date', render: (row) => formatDate(row.paymentDate) },
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
    { key: 'bills', header: 'Bills', render: (row) => <span className="text-xs text-slate-600">{row.bills}</span> },
    { key: 'method', header: 'Type', render: (row) => methodLabel[row.paymentMethod] ?? row.paymentMethod },
    { key: 'amount', header: 'Amount paid', align: 'right', render: (row) => <span className="font-semibold text-slate-800">{money(row.amount)}</span> },
    { key: 'reference', header: 'Reference', render: (row) => row.referenceNumber || <span className="text-slate-400">—</span> },
    { key: 'remarks', header: 'Remarks', render: (row) => row.remarks || <span className="text-slate-400">—</span> },
    { key: 'createdBy', header: 'Created by', render: (row) => row.createdByName ?? '—' },
  ]

  const exportColumns = [
    { header: 'Payment number', value: (row: Row) => row.paymentNumber },
    { header: 'Payment date', value: (row: Row) => row.paymentDate },
    { header: 'Supplier code', value: (row: Row) => row.supplierCode },
    { header: 'Supplier', value: (row: Row) => row.supplierName },
    { header: 'Bills', value: (row: Row) => row.bills },
    { header: 'Payment type', value: (row: Row) => methodLabel[row.paymentMethod] ?? row.paymentMethod },
    { header: 'Amount paid', value: (row: Row) => Number(row.amount) },
    { header: 'Reference', value: (row: Row) => row.referenceNumber ?? '' },
    { header: 'Remarks', value: (row: Row) => row.remarks ?? '' },
    { header: 'Created by', value: (row: Row) => row.createdByName ?? '' },
  ]

  if (error) return <Card><ErrorState message={error} onRetry={reload} /></Card>

  const totals = data?.totals

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Total paid" value={money(totals?.amount ?? 0)} icon={<Wallet className="h-5 w-5" />} tone="brand" />
        <StatCard label="Payments" value={totals?.payments ?? 0} icon={<ReceiptText className="h-5 w-5" />} tone="blue" />
        <StatCard
          label="Average payment"
          value={money((totals?.payments ?? 0) > 0 ? (totals?.amount ?? 0) / (totals?.payments ?? 1) : 0)}
          icon={<Calculator className="h-5 w-5" />}
          tone="purple"
        />
      </div>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-bold text-slate-800">Supplier payment history</h2>
            <p className="text-xs text-slate-500">
              {formatDate(filters.from)} – {formatDate(filters.to)} · {filters.branchLabel}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <ReportExport
              title="Supplier payment history"
              filename="supplier-payments"
              subtitle={`${formatDate(filters.from)} – ${formatDate(filters.to)} · ${filters.branchLabel}`}
              columns={exportColumns}
              rows={data?.rows ?? []}
              summary={[`Total ${money(totals?.amount ?? 0)}`, `${totals?.payments ?? 0} payments`]}
            />
          </div>
        </div>
        {loading ? (
          <LoadingState />
        ) : (
          <DataTable
            columns={columns}
            rows={data?.rows ?? []}
            rowKey={(row) => row.paymentNumber}
            empty={<EmptyState icon={<ReceiptText className="h-6 w-6" />} title="No supplier payments in this range" message="Record a payment from Supplier Outstanding to build history." />}
          />
        )}
      </Card>
    </div>
  )
}
