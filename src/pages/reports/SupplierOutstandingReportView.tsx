import { AlertCircle, IndianRupee, Users, Wallet } from 'lucide-react'
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
import { money } from '../../lib/format'
import type { SupplierOutstandingReport } from '../../types'
import type { ReportFilters } from './types'

type Row = SupplierOutstandingReport['rows'][number]

export function SupplierOutstandingReportView({ filters }: { filters: ReportFilters }) {
  const { data, loading, error, reload } = useApi<SupplierOutstandingReport>(
    () =>
      api
        .get('/reports/supplier-outstanding', {
          params: {
            branchId: filters.branchId ?? undefined,
            supplierId: filters.supplierId || undefined,
            search: filters.search || undefined,
          },
        })
        .then((response) => response.data),
    [filters.branchId, filters.supplierId, filters.search],
  )

  const columns: Column<Row>[] = [
    {
      key: 'supplier',
      header: 'Supplier',
      render: (row) => (
        <div>
          <p className="font-semibold text-slate-800">{row.supplierName}</p>
          <p className="text-[11px] text-slate-400">{row.supplierCode}</p>
        </div>
      ),
    },
    { key: 'purchase', header: 'Total purchase', align: 'right', render: (row) => money(row.totalPurchaseAmount) },
    { key: 'paid', header: 'Total paid', align: 'right', render: (row) => <span className="text-emerald-600">{money(row.totalPaid)}</span> },
    {
      key: 'outstanding',
      header: 'Outstanding',
      align: 'right',
      render: (row) => (
        <span className={Number(row.outstandingAmount) > 0 ? 'font-bold text-rose-600' : 'font-semibold text-emerald-600'}>
          {money(row.outstandingAmount)}
        </span>
      ),
    },
    { key: 'bills', header: 'Bills', align: 'right', render: (row) => row.billCount },
    {
      key: 'pending',
      header: 'Pending bills',
      align: 'right',
      render: (row) => <Badge tone={row.pendingBillCount > 0 ? 'amber' : 'green'}>{row.pendingBillCount}</Badge>,
    },
  ]

  const exportColumns = [
    { header: 'Supplier code', value: (row: Row) => row.supplierCode },
    { header: 'Supplier', value: (row: Row) => row.supplierName },
    { header: 'Total purchase amount', value: (row: Row) => Number(row.totalPurchaseAmount) },
    { header: 'Total amount paid', value: (row: Row) => Number(row.totalPaid) },
    { header: 'Outstanding balance', value: (row: Row) => Number(row.outstandingAmount) },
    { header: 'Bills', value: (row: Row) => row.billCount },
    { header: 'Pending bills', value: (row: Row) => row.pendingBillCount },
  ]

  if (error) return <Card><ErrorState message={error} onRetry={reload} /></Card>

  const totals = data?.totals

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Total purchase" value={money(totals?.totalPurchaseAmount ?? 0)} icon={<IndianRupee className="h-5 w-5" />} tone="blue" />
        <StatCard label="Total paid" value={money(totals?.totalPaid ?? 0)} icon={<Wallet className="h-5 w-5" />} tone="brand" />
        <StatCard
          label="Outstanding"
          value={money(totals?.outstandingAmount ?? 0)}
          hint={`${totals?.supplierCount ?? 0} suppliers`}
          icon={<AlertCircle className="h-5 w-5" />}
          tone="rose"
        />
      </div>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-bold text-slate-800">Supplier outstanding</h2>
            <p className="text-xs text-slate-500">Completed purchase bills vs recorded payments · {filters.branchLabel}</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <ReportExport
              title="Supplier outstanding report"
              filename="supplier-outstanding"
              subtitle={filters.branchLabel}
              columns={exportColumns}
              rows={data?.rows ?? []}
              summary={[
                `Purchase ${money(totals?.totalPurchaseAmount ?? 0)}`,
                `Paid ${money(totals?.totalPaid ?? 0)}`,
                `Outstanding ${money(totals?.outstandingAmount ?? 0)}`,
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
            rowKey={(row) => row.supplierId}
            highlight={(row) => Number(row.outstandingAmount) > 0}
            empty={<EmptyState icon={<Users className="h-6 w-6" />} title="No supplier balances" message="Supplier purchases and payments will appear here." />}
          />
        )}
      </Card>
    </div>
  )
}
