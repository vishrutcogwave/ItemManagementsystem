import { ReceiptText, Wallet } from 'lucide-react'
import { useState } from 'react'
import { Card, DataTable, ErrorState, Field, LoadingState, Select, StatCard } from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { api } from '../../lib/api'
import { money } from '../../lib/format'
import { collectionMethods, paymentLabels } from '../../lib/salePayments'
import { useAuthStore } from '../../store/auth'
import type { PaymentMethod } from '../../types'
import type { ReportFilters } from './types'

interface MethodTotal {
  method: PaymentMethod
  transactions: number
  collected: number
}

interface PaymentSummary {
  billAmount: number
  collected: number
  netReceived: number
  bills: number
  paid: number
  partiallyPaid: number
  unpaid: number
  methods: MethodTotal[]
  cashiers: { id: number; name: string }[]
}

export function PaymentSummaryReportView({ filters }: { filters: ReportFilters }) {
  const { store, branches } = useAuthStore()
  const [branchId, setBranchId] = useState(String(filters.branchId ?? ''))
  const [cashierId, setCashierId] = useState('')
  const [method, setMethod] = useState('')
  const { data, loading, error, reload } = useApi<PaymentSummary>(
    () => api.get<PaymentSummary>('/reports/payment-summary', {
      params: {
        from: filters.from || undefined,
        to: filters.to || undefined,
        storeId: store?.id,
        branchId: branchId || undefined,
        cashierId: cashierId || undefined,
        method: method || undefined,
      },
    }).then((response) => response.data),
    [filters.from, filters.to, store?.id, branchId, cashierId, method],
  )

  const methods: PaymentMethod[] = [...collectionMethods]
  if (method === 'CREDIT' || data?.methods.some((row) => row.method === 'CREDIT')) methods.push('CREDIT')
  const rows = methods.filter((entry) => !method || entry === method).map((entry) =>
    data?.methods.find((row) => row.method === entry) ?? { method: entry, transactions: 0, collected: 0 },
  )

  return (
    <div className="space-y-4">
      <Card className="grid gap-3 p-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field label="Store">
          <Select aria-label="Store" value={store?.id ?? ''} disabled>
            <option value={store?.id ?? ''}>{store?.storeName ?? 'Current store'}</option>
          </Select>
        </Field>
        <Field label="Branch">
          <Select aria-label="Branch" value={branchId} onChange={(event) => { setBranchId(event.target.value); setCashierId('') }}>
            <option value="">All accessible branches</option>
            {branches.map((branch) => <option key={branch.id} value={branch.id}>{branch.branchName}</option>)}
          </Select>
        </Field>
        <Field label="Payment method">
          <Select aria-label="Payment method" value={method} onChange={(event) => setMethod(event.target.value)}>
            <option value="">All payment methods</option>
            {([...collectionMethods, 'CREDIT'] as const).map((entry) =>
              <option key={entry} value={entry}>{paymentLabels[entry]}</option>,
            )}
          </Select>
        </Field>
        <Field label="Cashier">
          <Select aria-label="Cashier" value={cashierId} onChange={(event) => setCashierId(event.target.value)}>
            <option value="">All cashiers</option>
            {data?.cashiers.map((cashier) => <option key={cashier.id} value={cashier.id}>{cashier.name}</option>)}
            {cashierId && !data?.cashiers.some((cashier) => String(cashier.id) === cashierId) && (
              <option value={cashierId}>Selected cashier</option>
            )}
          </Select>
        </Field>
      </Card>
      {loading ? <LoadingState /> : error ? <ErrorState message={error} onRetry={reload} /> : data && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="Total bill amount" value={money(data.billAmount)} icon={<ReceiptText className="h-5 w-5" />} />
            <StatCard label="Total amount collected" value={money(data.collected)} icon={<Wallet className="h-5 w-5" />} />
            <StatCard label="Net amount received" value={money(data.netReceived)} icon={<Wallet className="h-5 w-5" />} />
            {[
              ['Number of bills', data.bills],
              ['Number of paid bills', data.paid],
              ['Number of partially paid bills', data.partiallyPaid],
              ['Number of unpaid bills', data.unpaid],
            ].map(([label, value]) => (
              <StatCard key={label} label={String(label)} value={value} icon={<ReceiptText className="h-5 w-5" />} />
            ))}
          </div>
          <Card className="p-4">
            <h2 className="mb-1 font-semibold">Payment method breakdown</h2>
            <p className="mb-3 text-xs text-slate-500">
              Current recorded payments on completed bills, grouped by bill date. A method filter limits the collected amounts to that method.
            </p>
            <DataTable<MethodTotal>
              columns={[
                { key: 'method', header: 'Method', render: (row) => paymentLabels[row.method] },
                { key: 'transactions', header: 'Transactions', align: 'right', render: (row) => row.transactions },
                { key: 'collected', header: 'Amount collected', align: 'right', render: (row) => money(row.collected) },
              ]}
              rows={rows}
              rowKey={(row) => row.method}
            />
          </Card>
        </>
      )}
    </div>
  )
}
