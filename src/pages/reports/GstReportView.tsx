import { Landmark, Percent, ReceiptText } from 'lucide-react'
import { useState } from 'react'
import { ReportExport } from '../../components/ReportExport'
import {
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  LoadingState,
  Select,
  StatCard,
  type Column,
} from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { api } from '../../lib/api'
import { formatDate, money } from '../../lib/format'
import type { GstReport, TaxMaster } from '../../types'
import type { ReportFilters } from './types'

type GstRow = GstReport['rows'][number]

export function GstReportView({ filters }: { filters: ReportFilters }) {
  const [taxRate, setTaxRate] = useState('')

  const { data: taxes } = useApi<TaxMaster[]>(
    () => api.get('/taxes/options').then((response) => response.data),
    [],
  )
  const rates = Array.from(new Set((taxes ?? []).map((tax) => Number(tax.gstPercentage)))).sort((a, b) => a - b)

  const { data, loading, error, reload } = useApi<GstReport>(
    () =>
      api
        .get('/reports/gst', {
          params: {
            from: filters.from || undefined,
            to: filters.to || undefined,
            branchId: filters.branchId ?? undefined,
            taxRate: taxRate === '' ? undefined : Number(taxRate),
          },
        })
        .then((response) => response.data),
    [filters.from, filters.to, filters.branchId, taxRate],
  )

  const columns: Column<GstRow>[] = [
    { key: 'branch', header: 'Branch', render: (row) => <span className="font-semibold text-slate-800">{row.branchName}</span> },
    { key: 'tax', header: 'Tax', render: (row) => <span className="text-xs font-medium text-slate-700">{row.taxName}</span> },
    { key: 'rate', header: 'GST %', align: 'right', render: (row) => `${Number(row.gstPercentage)}%` },
    { key: 'taxable', header: 'Taxable', align: 'right', render: (row) => money(row.taxableAmount) },
    { key: 'cgst', header: 'CGST', align: 'right', render: (row) => money(row.cgstAmount) },
    { key: 'sgst', header: 'SGST', align: 'right', render: (row) => money(row.sgstAmount) },
    { key: 'igst', header: 'IGST', align: 'right', render: (row) => money(row.igstAmount) },
    {
      key: 'taxAmount',
      header: 'Total tax',
      align: 'right',
      render: (row) => <span className="font-semibold text-slate-800">{money(row.taxAmount)}</span>,
    },
    { key: 'invoices', header: 'Invoices', align: 'right', render: (row) => row.invoiceCount },
  ]

  const exportColumns = [
    { header: 'Branch', value: (row: GstRow) => row.branchName },
    { header: 'Tax', value: (row: GstRow) => row.taxName },
    { header: 'GST %', value: (row: GstRow) => Number(row.gstPercentage) },
    { header: 'Taxable', value: (row: GstRow) => Number(row.taxableAmount) },
    { header: 'CGST', value: (row: GstRow) => Number(row.cgstAmount) },
    { header: 'SGST', value: (row: GstRow) => Number(row.sgstAmount) },
    { header: 'IGST', value: (row: GstRow) => Number(row.igstAmount) },
    { header: 'Total tax', value: (row: GstRow) => Number(row.taxAmount) },
    { header: 'Invoices', value: (row: GstRow) => row.invoiceCount },
  ]

  if (error) return <Card><ErrorState message={error} onRetry={reload} /></Card>

  const totals = data?.totals

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="Taxable value" value={money(totals?.taxableAmount ?? 0)} icon={<Landmark className="h-5 w-5" />} tone="brand" />
        <StatCard label="CGST" value={money(totals?.cgstAmount ?? 0)} icon={<Percent className="h-5 w-5" />} tone="blue" />
        <StatCard label="SGST" value={money(totals?.sgstAmount ?? 0)} icon={<Percent className="h-5 w-5" />} tone="purple" />
        <StatCard label="Total tax" value={money(totals?.taxAmount ?? 0)} hint={data ? `${totals?.invoiceCount ?? 0} invoices` : undefined} icon={<ReceiptText className="h-5 w-5" />} tone="amber" />
      </div>

      <Card>
        <div className="flex flex-col gap-2 border-b border-slate-100 px-4 py-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h2 className="text-sm font-bold text-slate-800">GST summary</h2>
            <p className="text-xs text-slate-500">
              {formatDate(filters.from)} – {formatDate(filters.to)} · {filters.branchLabel}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Select className="w-40" value={taxRate} onChange={(event) => setTaxRate(event.target.value)}>
              <option value="">All tax rates</option>
              {rates.map((rate) => (
                <option key={rate} value={rate}>
                  {rate}% GST
                </option>
              ))}
            </Select>
            <ReportExport
              title="GST summary"
              filename="gst-summary"
              subtitle={`${formatDate(filters.from)} – ${formatDate(filters.to)} · ${filters.branchLabel}`}
              columns={exportColumns}
              rows={data?.rows ?? []}
              summary={
                totals
                  ? [
                      `Taxable ${money(totals.taxableAmount)}`,
                      `CGST ${money(totals.cgstAmount)}`,
                      `SGST ${money(totals.sgstAmount)}`,
                      `IGST ${money(totals.igstAmount)}`,
                      `Total tax ${money(totals.taxAmount)}`,
                    ]
                  : undefined
              }
            />
          </div>
        </div>
        {loading ? (
          <LoadingState />
        ) : (
          <DataTable
            columns={columns}
            rows={data?.rows ?? []}
            rowKey={(row) => `${row.branchId}-${row.taxName}-${row.gstPercentage}`}
            empty={
              <EmptyState
                title="No GST collected in this range"
                message="Completed sales within the selected period will be summarised by tax slab here."
              />
            }
          />
        )}
      </Card>
    </div>
  )
}
