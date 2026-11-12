import { Banknote, Eye, Printer, ReceiptText, Wallet } from 'lucide-react'
import { useState } from 'react'
import {
  Badge,
  Button,
  Card,
  Checkbox,
  DataTable,
  EmptyState,
  ErrorState,
  Field,
  Input,
  LoadingState,
  Modal,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatusBadge,
  Tabs,
  Textarea,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { printSupplierPayment } from '../lib/documents'
import { formatDate, formatDateTime, money, today } from '../lib/format'
import { useAuthStore } from '../store/auth'
import { notify } from '../store/ui'
import type {
  PageResponse,
  Supplier,
  SupplierBill,
  SupplierOutstanding,
  SupplierPayment,
  SupplierPaymentMethod,
} from '../types'

const TABS = [
  { key: 'outstanding', label: 'Outstanding' },
  { key: 'history', label: 'Payment history' },
]

const METHODS: { value: SupplierPaymentMethod; label: string }[] = [
  { value: 'CASH', label: 'Cash' },
  { value: 'BANK', label: 'Bank transfer' },
  { value: 'UPI', label: 'UPI' },
  { value: 'CARD', label: 'Card' },
]

const methodTone = (method: SupplierPaymentMethod) =>
  method === 'CASH' ? 'green' : method === 'BANK' ? 'blue' : method === 'UPI' ? 'purple' : 'amber'

const methodLabel = (method: SupplierPaymentMethod) =>
  METHODS.find((entry) => entry.value === method)?.label ?? method

const newClientReference = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `pay-${Date.now()}-${Math.random().toString(36).slice(2)}`

const round2 = (value: number) => Math.round((value + Number.EPSILON) * 100) / 100

export function SupplierPaymentsPage() {
  const { hasPermission, selectedBranchId, store } = useAuthStore()
  const canManage = hasPermission('PURCHASE_MANAGE')
  const [tab, setTab] = useState('outstanding')

  const [outstandingSearch, setOutstandingSearch] = useState('')
  const [outstandingPage, setOutstandingPage] = useState(0)
  const [outstandingSize, setOutstandingSize] = useState(10)
  const outstandingQuery = useDebounce(outstandingSearch)

  const outstanding = useApi<PageResponse<SupplierOutstanding>>(
    () =>
      api
        .get('/supplier-payments/outstanding', {
          params: {
            branchId: selectedBranchId ?? undefined,
            search: outstandingQuery || undefined,
            page: outstandingPage,
            size: outstandingSize,
          },
        })
        .then((response) => response.data),
    [selectedBranchId, outstandingQuery, outstandingPage, outstandingSize],
  )

  const { data: suppliers } = useApi<Supplier[]>(
    () => api.get('/suppliers/options').then((response) => response.data),
    [],
  )

  const [supplierFilter, setSupplierFilter] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [historySearch, setHistorySearch] = useState('')
  const [historyPage, setHistoryPage] = useState(0)
  const [historySize, setHistorySize] = useState(10)
  const historyQuery = useDebounce(historySearch)

  const history = useApi<PageResponse<SupplierPayment>>(
    () =>
      api
        .get('/supplier-payments', {
          params: {
            supplierId: supplierFilter || undefined,
            from: from || undefined,
            to: to || undefined,
            search: historyQuery || undefined,
            page: historyPage,
            size: historySize,
          },
        })
        .then((response) => response.data),
    [supplierFilter, from, to, historyQuery, historyPage, historySize],
  )

  const [target, setTarget] = useState<SupplierOutstanding | null>(null)
  const [bills, setBills] = useState<SupplierBill[]>([])
  const [billsLoading, setBillsLoading] = useState(false)
  const [billsError, setBillsError] = useState<string | null>(null)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [amount, setAmount] = useState('')
  const [method, setMethod] = useState<SupplierPaymentMethod>('CASH')
  const [paymentDate, setPaymentDate] = useState(today())
  const [referenceNumber, setReferenceNumber] = useState('')
  const [remarks, setRemarks] = useState('')
  const [clientReference, setClientReference] = useState('')
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [detail, setDetail] = useState<SupplierPayment | null>(null)

  const openPayment = async (row: SupplierOutstanding) => {
    setTarget(row)
    setBills([])
    setSelected(new Set())
    setAmount('')
    setMethod('CASH')
    setPaymentDate(today())
    setReferenceNumber('')
    setRemarks('')
    setClientReference(newClientReference())
    setFormErrors({})
    setFormError(null)
    setBillsError(null)
    setBillsLoading(true)
    try {
      const response = await api.get<SupplierBill[]>(
        `/supplier-payments/suppliers/${row.supplierId}/bills`,
        { params: { branchId: selectedBranchId ?? undefined } },
      )
      setBills(response.data.filter((bill) => Number(bill.balanceAmount) > 0))
    } catch (err) {
      setBillsError(errorMessage(err))
    } finally {
      setBillsLoading(false)
    }
  }

  const closePayment = () => {
    if (saving) return
    setTarget(null)
    setBills([])
    setSelected(new Set())
  }

  const toggleBill = (purchaseId: number) => {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(purchaseId)) next.delete(purchaseId)
      else next.add(purchaseId)
      return next
    })
  }

  const toggleAll = () => {
    setSelected((current) =>
      current.size === bills.length ? new Set() : new Set(bills.map((bill) => bill.purchaseId)),
    )
  }

  const selectedBills = bills.filter((bill) => selected.has(bill.purchaseId))
  const selectedTotal = round2(
    selectedBills.reduce((sum, bill) => sum + Number(bill.balanceAmount), 0),
  )
  const amountValue = amount === '' ? selectedTotal : round2(Number(amount) || 0)
  const remaining = round2(selectedTotal - amountValue)

  const savePayment = async () => {
    const nextErrors: Record<string, string> = {}
    if (selected.size === 0) nextErrors.purchaseIds = 'Select at least one outstanding bill'
    if (!paymentDate) nextErrors.paymentDate = 'Payment date is required'
    if (paymentDate > today()) nextErrors.paymentDate = 'Payment date cannot be in the future'
    if (!Number.isFinite(amountValue) || amountValue <= 0) {
      nextErrors.amount = 'Payment amount must be greater than zero'
    } else if (amountValue > selectedTotal) {
      nextErrors.amount = 'Payment amount cannot exceed the selected outstanding balance'
    }
    if (Object.keys(nextErrors).length > 0) {
      setFormErrors(nextErrors)
      return
    }

    setSaving(true)
    setFormError(null)
    setFormErrors({})
    try {
      const response = await api.post<SupplierPayment>('/supplier-payments', {
        supplierId: target?.supplierId,
        paymentDate,
        paymentMethod: method,
        amount: amountValue,
        referenceNumber: referenceNumber.trim() || undefined,
        remarks: remarks.trim() || undefined,
        clientReference,
        purchaseIds: [...selected],
      })
      notify(`Payment ${response.data.paymentNumber} recorded`)
      setTarget(null)
      setBills([])
      setSelected(new Set())
      outstanding.reload()
      history.reload()
    } catch (err) {
      setFormError(errorMessage(err))
      setFormErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  const outstandingColumns: Column<SupplierOutstanding>[] = [
    {
      key: 'code',
      header: 'Supplier code',
      render: (row) => <span className="font-mono text-xs font-semibold text-slate-600">{row.supplierCode}</span>,
    },
    {
      key: 'supplier',
      header: 'Supplier',
      render: (row) => <span className="font-medium text-slate-800">{row.supplierName}</span>,
    },
    {
      key: 'purchases',
      header: 'Total purchase amount',
      align: 'right',
      render: (row) => money(row.totalPurchaseAmount),
    },
    { key: 'paid', header: 'Total amount paid', align: 'right', render: (row) => money(row.totalPaid) },
    {
      key: 'outstanding',
      header: 'Outstanding balance',
      align: 'right',
      render: (row) =>
        Number(row.outstandingAmount) > 0 ? (
          <span className="font-bold text-rose-600">{money(row.outstandingAmount)}</span>
        ) : (
          <span className="font-semibold text-emerald-600">{money(0)}</span>
        ),
    },
    {
      key: 'bills',
      header: 'Pending bills',
      align: 'center',
      render: (row) =>
        row.pendingBillCount > 0 ? (
          <Badge tone="amber">{row.pendingBillCount}</Badge>
        ) : (
          <Badge tone="green">Settled</Badge>
        ),
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (row) => (
        <Button
          size="sm"
          variant={canManage && Number(row.outstandingAmount) > 0 ? 'primary' : 'outline'}
          icon={<Wallet className="h-3.5 w-3.5" />}
          onClick={() => openPayment(row)}
        >
          {canManage && Number(row.outstandingAmount) > 0 ? 'Pay' : 'Bills'}
        </Button>
      ),
    },
  ]

  const historyColumns: Column<SupplierPayment>[] = [
    {
      key: 'number',
      header: 'Payment',
      render: (row) => <span className="font-semibold text-slate-800">{row.paymentNumber}</span>,
    },
    { key: 'date', header: 'Date', render: (row) => formatDate(row.paymentDate) },
    {
      key: 'supplier',
      header: 'Supplier',
      render: (row) => (
        <div>
          <p className="font-medium text-slate-700">{row.supplierName}</p>
          <p className="font-mono text-[10px] text-slate-400">{row.supplierCode}</p>
        </div>
      ),
    },
    {
      key: 'bills',
      header: 'Purchase bill',
      render: (row) => (
        <span className="text-xs text-slate-600">
          {row.allocations.map((allocation) => allocation.purchaseNumber).join(', ') || '—'}
        </span>
      ),
    },
    {
      key: 'method',
      header: 'Payment type',
      render: (row) => <Badge tone={methodTone(row.paymentMethod)}>{methodLabel(row.paymentMethod)}</Badge>,
    },
    {
      key: 'amount',
      header: 'Amount paid',
      align: 'right',
      render: (row) => <span className="font-semibold text-slate-800">{money(row.amount)}</span>,
    },
    { key: 'reference', header: 'Reference', render: (row) => row.referenceNumber || '—' },
    {
      key: 'remarks',
      header: 'Remarks',
      render: (row) => <span className="block max-w-40 truncate text-xs text-slate-500">{row.remarks || '—'}</span>,
    },
    {
      key: 'created',
      header: 'Created by',
      render: (row) => <span className="text-xs text-slate-600">{row.createdByName || '—'}</span>,
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (row) => (
        <div className="flex items-center justify-end gap-1">
          <button
            onClick={() => setDetail(row)}
            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="View payment"
          >
            <Eye className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={() =>
              printSupplierPayment(row, {
                name: store?.storeName ?? 'Store',
                gstNumber: store?.gstNumber,
                address: store?.address,
                mobile: store?.mobile,
              })
            }
            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="Print receipt"
          >
            <Printer className="h-3.5 w-3.5" />
          </button>
        </div>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="Supplier outstanding"
        subtitle="Track payable balances, record part payments and review every supplier payment."
      />

      <div className="mb-4">
        <Tabs tabs={TABS} value={tab} onChange={setTab} />
      </div>

      {tab === 'outstanding' && (
        <Card>
          <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center">
            <SearchInput
              className="sm:w-72"
              placeholder="Search supplier code or name…"
              value={outstandingSearch}
              onChange={(event) => {
                setOutstandingSearch(event.target.value)
                setOutstandingPage(0)
              }}
            />
            <p className="text-xs text-slate-500 sm:ml-auto">
              Outstanding is calculated from completed purchase bills minus all recorded payments.
            </p>
          </div>
          {outstanding.error ? (
            <ErrorState message={outstanding.error} onRetry={outstanding.reload} />
          ) : (
            <>
              <DataTable
                columns={outstandingColumns}
                rows={outstanding.data?.content ?? []}
                rowKey={(row) => row.supplierId}
                loading={outstanding.loading}
                empty={
                  <EmptyState
                    icon={<Banknote className="h-6 w-6" />}
                    title="No supplier balances"
                    message="Supplier balances appear after completed purchases are recorded."
                  />
                }
              />
              {outstanding.data && outstanding.data.totalElements > 0 && (
                <Pagination
                  page={outstandingPage}
                  size={outstandingSize}
                  totalPages={outstanding.data.totalPages}
                  totalElements={outstanding.data.totalElements}
                  onPageChange={setOutstandingPage}
                  onSizeChange={(value) => {
                    setOutstandingSize(value)
                    setOutstandingPage(0)
                  }}
                  itemLabel="suppliers"
                />
              )}
            </>
          )}
        </Card>
      )}

      {tab === 'history' && (
        <Card>
          <div className="flex flex-col gap-3 border-b border-slate-100 p-4 xl:flex-row xl:items-center">
            <SearchInput
              className="xl:w-64"
              placeholder="Search payment, reference or supplier…"
              value={historySearch}
              onChange={(event) => {
                setHistorySearch(event.target.value)
                setHistoryPage(0)
              }}
            />
            <Select
              className="xl:w-52"
              value={supplierFilter}
              onChange={(event) => {
                setSupplierFilter(event.target.value)
                setHistoryPage(0)
              }}
            >
              <option value="">All suppliers</option>
              {suppliers?.map((supplier) => (
                <option key={supplier.id} value={supplier.id}>
                  {supplier.supplierName}
                </option>
              ))}
            </Select>
            <div className="flex items-center gap-2">
              <Input
                type="date"
                className="w-38"
                value={from}
                max={to || today()}
                onChange={(event) => {
                  setFrom(event.target.value)
                  setHistoryPage(0)
                }}
              />
              <span className="text-xs text-slate-400">to</span>
              <Input
                type="date"
                className="w-38"
                value={to}
                min={from}
                max={today()}
                onChange={(event) => {
                  setTo(event.target.value)
                  setHistoryPage(0)
                }}
              />
              {(from || to) && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setFrom('')
                    setTo('')
                  }}
                >
                  Clear
                </Button>
              )}
            </div>
          </div>
          {history.error ? (
            <ErrorState message={history.error} onRetry={history.reload} />
          ) : (
            <>
              <DataTable
                columns={historyColumns}
                rows={history.data?.content ?? []}
                rowKey={(row) => row.id}
                loading={history.loading}
                empty={
                  <EmptyState
                    icon={<ReceiptText className="h-6 w-6" />}
                    title="No supplier payments yet"
                    message="Payments recorded against purchase bills will appear here."
                  />
                }
              />
              {history.data && history.data.totalElements > 0 && (
                <Pagination
                  page={historyPage}
                  size={historySize}
                  totalPages={history.data.totalPages}
                  totalElements={history.data.totalElements}
                  onPageChange={setHistoryPage}
                  onSizeChange={(value) => {
                    setHistorySize(value)
                    setHistoryPage(0)
                  }}
                  itemLabel="payments"
                />
              )}
            </>
          )}
        </Card>
      )}

      <Modal
        open={Boolean(target)}
        onClose={closePayment}
        title={target ? `Supplier bills · ${target.supplierName}` : 'Supplier bills'}
        description={
          target
            ? `${target.supplierCode} · Outstanding ${money(target.outstandingAmount)} · Filled oldest bill first`
            : undefined
        }
        size="xl"
        footer={
          <>
            <Button variant="outline" onClick={closePayment} disabled={saving}>
              Close
            </Button>
            {canManage && bills.length > 0 && (
              <Button onClick={savePayment} loading={saving} disabled={selected.size === 0}>
                Save payment
              </Button>
            )}
          </>
        }
      >
        <div className="space-y-4">
          {formError && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
              {formError}
            </div>
          )}

          {billsLoading ? (
            <LoadingState label="Loading outstanding bills…" />
          ) : billsError ? (
            <ErrorState message={billsError} />
          ) : bills.length === 0 ? (
            <EmptyState
              icon={<ReceiptText className="h-6 w-6" />}
              title="No outstanding bills"
              message="Every completed purchase bill for this supplier is fully paid."
            />
          ) : (
            <>
              <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
                <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
                  <div>
                    <h3 className="text-sm font-bold text-slate-800">Outstanding bills</h3>
                    <p className="text-xs text-slate-500">
                      Select the bills this payment should settle. Allocation is applied oldest first.
                    </p>
                  </div>
                  <Checkbox
                    label={selected.size === bills.length ? 'Clear all' : 'Select all'}
                    checked={selected.size === bills.length}
                    onChange={toggleAll}
                  />
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[720px]">
                    <thead className="bg-slate-50">
                      <tr>
                        <th className="th w-10" />
                        <th className="th">Bill</th>
                        <th className="th">Date</th>
                        <th className="th">Branch</th>
                        <th className="th text-right">Bill total</th>
                        <th className="th text-right">Paid</th>
                        <th className="th text-right">Outstanding</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {bills.map((bill) => (
                        <tr
                          key={bill.purchaseId}
                          className="cursor-pointer hover:bg-slate-50/70"
                          onClick={() => toggleBill(bill.purchaseId)}
                        >
                          <td className="px-3 py-2">
                            <input
                              type="checkbox"
                              className="h-4 w-4 rounded border-slate-300 accent-brand-600"
                              checked={selected.has(bill.purchaseId)}
                              onChange={() => toggleBill(bill.purchaseId)}
                              onClick={(event) => event.stopPropagation()}
                            />
                          </td>
                          <td className="td">
                            <p className="font-semibold text-slate-800">{bill.purchaseNumber}</p>
                            <p className="text-[11px] text-slate-400">
                              {bill.invoiceNumber ? `Invoice ${bill.invoiceNumber}` : 'No invoice no.'}
                            </p>
                          </td>
                          <td className="td">{formatDate(bill.purchaseDate)}</td>
                          <td className="td">{bill.branchName}</td>
                          <td className="td text-right">{money(bill.grandTotal)}</td>
                          <td className="td text-right">{money(bill.paidAmount)}</td>
                          <td className="td text-right font-semibold text-rose-600">
                            {money(bill.balanceAmount)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>

              {canManage ? (
                <div className="grid grid-cols-1 gap-3 rounded-xl border border-slate-200 bg-white p-4 sm:grid-cols-2 lg:grid-cols-3">
                  <Field
                    label="Payment amount ₹"
                    required
                    error={formErrors.amount}
                    hint={`Selected outstanding ${money(selectedTotal)}`}
                  >
                    <Input
                      type="number"
                      min="0.01"
                      step="0.01"
                      value={amount}
                      onChange={(event) => setAmount(event.target.value)}
                      placeholder={String(selectedTotal.toFixed(2))}
                    />
                  </Field>
                  <Field label="Payment type" required>
                    <Select
                      value={method}
                      onChange={(event) => setMethod(event.target.value as SupplierPaymentMethod)}
                    >
                      {METHODS.map((entry) => (
                        <option key={entry.value} value={entry.value}>
                          {entry.label}
                        </option>
                      ))}
                    </Select>
                  </Field>
                  <Field label="Payment date" required error={formErrors.paymentDate}>
                    <Input
                      type="date"
                      value={paymentDate}
                      max={today()}
                      onChange={(event) => setPaymentDate(event.target.value)}
                    />
                  </Field>
                  <Field label="Reference / transaction ID" hint="Cheque, UPI or bank reference">
                    <Input
                      value={referenceNumber}
                      onChange={(event) => setReferenceNumber(event.target.value)}
                      placeholder="Optional"
                    />
                  </Field>
                  <Field label="Remarks" className="sm:col-span-2">
                    <Textarea
                      value={remarks}
                      onChange={(event) => setRemarks(event.target.value)}
                      placeholder="Optional notes for this payment"
                      rows={2}
                    />
                  </Field>
                  {formErrors.purchaseIds && (
                    <p className="text-xs font-medium text-rose-600 sm:col-span-2 lg:col-span-3">
                      {formErrors.purchaseIds}
                    </p>
                  )}
                  <div className="flex flex-wrap items-center gap-4 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600 sm:col-span-2 lg:col-span-3">
                    <span>
                      Selected <span className="font-semibold text-slate-800">{selected.size}</span> bill(s)
                    </span>
                    <span>
                      Paying <span className="font-semibold text-slate-800">{money(amountValue)}</span>
                    </span>
                    <span>
                      Remaining after payment{' '}
                      <span className={`font-semibold ${remaining > 0 ? 'text-rose-600' : 'text-emerald-600'}`}>
                        {money(Math.max(0, remaining))}
                      </span>
                    </span>
                    <StatusBadge
                      status={
                        amountValue <= 0 ? 'UNPAID' : amountValue >= selectedTotal && selectedTotal > 0 ? 'PAID' : 'PARTIALLY_PAID'
                      }
                    />
                  </div>
                </div>
              ) : (
                <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
                  You can view outstanding bills but need purchase management permission to record a payment.
                </p>
              )}
            </>
          )}
        </div>
      </Modal>

      <Modal
        open={Boolean(detail)}
        onClose={() => setDetail(null)}
        title={detail ? `Payment ${detail.paymentNumber}` : 'Payment'}
        description={
          detail
            ? `${detail.supplierName} · ${detail.supplierCode} · ${formatDate(detail.paymentDate)}`
            : undefined
        }
        size="lg"
        footer={
          detail ? (
            <>
              <Button variant="outline" onClick={() => setDetail(null)}>
                Close
              </Button>
              <Button
                variant="outline"
                icon={<Printer className="h-4 w-4" />}
                onClick={() =>
                  printSupplierPayment(detail, {
                    name: store?.storeName ?? 'Store',
                    gstNumber: store?.gstNumber,
                    address: store?.address,
                    mobile: store?.mobile,
                  })
                }
              >
                Print receipt
              </Button>
            </>
          ) : undefined
        }
      >
        {detail && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Amount</p>
                <p className="mt-0.5 text-sm font-bold text-slate-800">{money(detail.amount)}</p>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Type</p>
                <div className="mt-1">
                  <Badge tone={methodTone(detail.paymentMethod)}>{methodLabel(detail.paymentMethod)}</Badge>
                </div>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Reference</p>
                <p className="mt-0.5 text-xs font-semibold text-slate-700">{detail.referenceNumber || '—'}</p>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Created by</p>
                <p className="mt-0.5 text-xs font-semibold text-slate-700">{detail.createdByName || '—'}</p>
              </div>
            </div>

            <div className="overflow-hidden rounded-lg border border-slate-200">
              <table className="w-full">
                <thead className="bg-slate-50">
                  <tr>
                    <th className="th">Purchase bill</th>
                    <th className="th">Bill date</th>
                    <th className="th text-right">Allocated</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {detail.allocations.map((allocation) => (
                    <tr key={`${allocation.purchaseId}-${allocation.amount}`}>
                      <td className="td font-semibold text-slate-800">{allocation.purchaseNumber}</td>
                      <td className="td">{formatDate(allocation.purchaseDate)}</td>
                      <td className="td text-right">{money(allocation.amount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {detail.remarks && <p className="text-xs text-slate-500">Remarks: {detail.remarks}</p>}
            <p className="text-[11px] text-slate-400">Recorded {formatDateTime(detail.createdAt)}</p>
          </div>
        )}
      </Modal>
    </div>
  )
}
