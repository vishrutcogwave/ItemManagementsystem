import { ArrowDownLeft, ArrowUpRight, BookOpen, Users } from 'lucide-react'
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
import type { SupplierLedgerReport } from '../../types'
import type { ReportFilters } from './types'

type Row = SupplierLedgerReport['rows'][number]

export function SupplierLedgerReportView({ filters }: { filters: ReportFilters }) {
  const supplierId = filters.supplierId
  const { data, loading, error, reload } = useApi<SupplierLedgerReport | null>(
    () =>
      supplierId
        ? api
            .get('/reports/supplier-ledger', {
                params: {
                  supplierId,
                  from: filters.from || undefined,
                  to: filters.to || undefined,
                  branchId: filters.branchId ?? undefined,
                },
              })
            .then((response) => response.data)
        : Promise.resolve(null),
    [supplierId, filters.from, filters.to, filters.branchId],
  )

  const columns: Column<Row>[] = [
    { key: 'date', header: 'Date', render: (row) => formatDate(row.date) },
    {
      key: 'type',
      header: 'Type',
      render: (row) => (
        <span className={row.type === 'PAYMENT' ? 'text-emerald-600' : 'text-slate-700'}>{row.type === 'PAYMENT' ? 'Payment' : 'Purchase'}</span>
      ),
    },
    { key: 'reference', header: 'Reference', render: (row) => <span className="font-medium text-slate-700">{row.reference}</span> },
    { key: 'description', header: 'Description', render: (row) => <span className="text-xs text-slate-600">{row.description}</span> },
    { key: 'debit', header: 'Debit', align: 'right', render: (row) => (Number(row.debit) > 0 ? money(row.debit) : <span className="text-slate-300">—</span>) },
    { key: 'credit', header: 'Credit', align: 'right', render: (row) => (Number(row.credit) > 0 ? <span className="text-emerald-600">{money(row.credit)}</span> : <span className="text-slate-300">—</span>) },
    { key: 'balance', header: 'Balance', align: 'right', render: (row) => <span className="font-semibold text-slate-800">{money(row.balance)}</span> },
  ]

  const exportColumns = [
    { header: 'Date', value: (row: Row) => row.date },
    { header: 'Type', value: (row: Row) => row.type },
    { header: 'Reference', value: (row: Row) => row.reference },
    { header: 'Description', value: (row: Row) => row.description },
    { header: 'Debit', value: (row: Row) => Number(row.debit) },
    { header: 'Credit', value: (row: Row) => Number(row.credit) },
    { header: 'Balance', value: (row: Row) => Number(row.balance) },
  ]

  if (!supplierId) {
    return (
      <Card>
        <EmptyState
          icon={<Users className="h-6 w-6" />}
          title="Select a supplier"
          message="Choose a supplier in the filter above to view their ledger."
        />
      </Card>
    )
  }

  if (error) return <Card><ErrorState message={error} onRetry={reload} /></Card>

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-4">
        <StatCard label="Opening balance" value={money(data?.openingBalance ?? 0)} icon={<BookOpen className="h-5 w-5" />} tone="blue" />
        <StatCard label="Debit (purchases)" value={money(data?.totalDebit ?? 0)} icon={<ArrowUpRight className="h-5 w-5" />} tone="amber" />
        <StatCard label="Credit (payments)" value={money(data?.totalCredit ?? 0)} icon={<ArrowDownLeft className="h-5 w-5" />} tone="green" />
        <StatCard label="Closing balance" value={money(data?.closingBalance ?? 0)} icon={<BookOpen className="h-5 w-5" />} tone="rose" />
      </div>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-bold text-slate-800">
              Supplier ledger{data ? ` · ${data.supplierName}` : ''}
            </h2>
            <p className="text-xs text-slate-500">
              {formatDate(filters.from)} – {formatDate(filters.to)} · {filters.branchLabel}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <ReportExport
              title="Supplier ledger"
              filename="supplier-ledger"
              subtitle={`${formatDate(filters.from)} – ${formatDate(filters.to)} · ${filters.branchLabel}`}
              columns={exportColumns}
              rows={data?.rows ?? []}
              summary={[
                `Opening ${money(data?.openingBalance ?? 0)}`,
                `Closing ${money(data?.closingBalance ?? 0)}`,
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
            rowKey={(row) => `${row.type}-${row.reference}-${row.date}`}
            empty={<EmptyState icon={<BookOpen className="h-6 w-6" />} title="No ledger entries" message="Purchases and payments for this supplier will appear here." />}
          />
        )}
      </Card>
    </div>
  )
}
