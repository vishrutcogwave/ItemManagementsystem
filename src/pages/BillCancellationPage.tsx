import {
  ArrowLeft,
  Ban,
  CheckCircle2,
  History,
  Printer,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { BillLookup } from '../components/billing/BillLookup'
import { InvoicePrintPreview } from '../components/billing/InvoicePrintPreview'
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  Field,
  LoadingState,
  Modal,
  PageHeader,
  StatusBadge,
  Textarea,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { ApiError, api, errorMessage } from '../lib/api'
import { formatDate, formatDateTime, money, qty } from '../lib/format'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type { PageResponse, Sale, SaleCancellation } from '../types'

interface StoreDetails {
  name: string
  gstNumber?: string | null
  address?: string | null
  mobile?: string | null
  email?: string | null
}

export function BillCancellationPage() {
  const navigate = useNavigate()
  const params = useParams()
  const saleId = params.id && Number(params.id) > 0 ? Number(params.id) : null
  const { store, selectedBranchId } = useAuthStore()

  const [reloadToken, setReloadToken] = useState(0)
  const [result, setResult] = useState<SaleCancellation | null>(null)
  const [previewOpen, setPreviewOpen] = useState(false)

  const saleQuery = useApi<Sale | null>(
    () =>
      saleId
        ? api.get<Sale>(`/sales/${saleId}`).then((response) => response.data)
        : Promise.resolve(null),
    [saleId, reloadToken],
  )
  const sale = saleQuery.data && saleQuery.data.id === saleId ? saleQuery.data : null

  const cancellationQuery = useApi<SaleCancellation | null>(
    () =>
      sale && sale.status === 'CANCELLED'
        ? api
            .get<SaleCancellation>(`/sales/${sale.id}/cancellation`)
            .then((response) => response.data)
            .catch(() => null)
        : Promise.resolve(null),
    [sale?.id ?? 0, sale?.status ?? ''],
  )
  const cancellation =
    cancellationQuery.data && sale && cancellationQuery.data.saleId === sale.id
      ? cancellationQuery.data
      : null

  const { data: recent, loading: recentLoading, reload: reloadRecent } = useApi<PageResponse<Sale>>(
    () =>
      api
        .get('/sales', {
          params: { branchId: selectedBranchId ?? undefined, status: 'CANCELLED', page: 0, size: 6 },
        })
        .then((response) => response.data),
    [selectedBranchId, reloadToken],
  )

  const storeInfo: StoreDetails = {
    name: store?.storeName ?? 'Store',
    gstNumber: store?.gstNumber,
    address: store?.address,
    mobile: store?.mobile,
    email: store?.email,
  }

  const header = (
    <PageHeader
      eyebrow="Sell"
      title="Cancel bill"
      subtitle="Cancel a completed bill with a recorded reason. Stock returns to the branch and the bill stays visible as CANCELLED."
      actions={
        saleId ? (
          <Button
            variant="outline"
            icon={<ArrowLeft className="h-4 w-4" />}
            onClick={() => navigate('/sales/cancel')}
          >
            Find another bill
          </Button>
        ) : undefined
      }
    />
  )

  if (!saleId) {
    return (
      <div>
        {header}
        <div className="space-y-3">
          <BillLookup onSelect={(bill) => navigate(`/sales/cancel/${bill.id}`)} />
          <p className="px-1 text-xs text-slate-500">
            Search by bill number, date, customer name or mobile. Only completed bills can be cancelled; already
            cancelled bills are listed read-only below.
          </p>
        </div>
      </div>
    )
  }

  if (!sale) {
    return (
      <div>
        {header}
        <Card>
          {saleQuery.error ? (
            <ErrorState message={saleQuery.error} onRetry={saleQuery.reload} />
          ) : (
            <LoadingState label="Loading bill…" />
          )}
        </Card>
      </div>
    )
  }

  const banner = result && result.saleId === sale.id ? result : null
  const cancelled = sale.status === 'CANCELLED'
  const items = sale.items ?? []

  const printInvoice = () => setPreviewOpen(true)

  const handleCancelled = (record: SaleCancellation) => {
    setResult(record)
    setReloadToken((value) => value + 1)
    reloadRecent()
  }

  return (
    <div>
      {header}

      {banner && (
        <div className="mb-4 rounded-xl border border-emerald-200 bg-emerald-50 p-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="flex min-w-0 items-start gap-3">
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-700">
                <CheckCircle2 className="h-5 w-5" />
              </span>
              <div className="min-w-0">
                <p className="text-sm font-bold text-emerald-900">Bill cancelled successfully</p>
                <p className="mt-0.5 text-xs text-emerald-800">
                  Reference <strong>{banner.cancellationNumber}</strong> · {formatDateTime(banner.cancelledAt)}
                </p>
                <p className="mt-1 text-xs text-emerald-800">
                  {banner.salesNumber} · {banner.restoredItems} item{banner.restoredItems === 1 ? '' : 's'} returned
                  to stock · {money(banner.saleTotal)}
                </p>
                {banner.paidAmount > 0 && (
                  <p className="mt-1 text-xs text-amber-800">
                    {money(banner.paidAmount)} was already collected — refund it to the customer as per store policy.
                  </p>
                )}
              </div>
            </div>
            <Button
              variant="outline"
              size="sm"
              icon={<Printer className="h-4 w-4" />}
              onClick={printInvoice}
            >
              Print cancelled invoice
            </Button>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
        <div className="space-y-4">
          <Card className="p-4">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-base font-bold text-slate-900">{sale.salesNumber}</h2>
                  <StatusBadge status={sale.status} />
                  {sale.modificationCount > 0 && (
                    <Badge tone="amber">{sale.modificationCount}× modified</Badge>
                  )}
                </div>
                <p className="mt-1 text-xs text-slate-500">
                  {formatDate(sale.salesDate)} · {sale.branchName} · {sale.customerName || 'Walk-in'}
                  {sale.customerMobile ? ` · ${sale.customerMobile}` : ''}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" size="sm" icon={<Printer className="h-4 w-4" />} onClick={printInvoice}>
                  Print invoice
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  icon={<RefreshCw className="h-4 w-4" />}
                  onClick={() => setReloadToken((value) => value + 1)}
                >
                  Reload
                </Button>
              </div>
            </div>

            <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Bill total</p>
                <p className="mt-0.5 text-sm font-bold text-slate-800">{money(sale.grandTotal)}</p>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Paid</p>
                <p className="mt-0.5 text-sm font-bold text-slate-800">{money(sale.paidAmount)}</p>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Balance</p>
                <p
                  className={`mt-0.5 text-sm font-bold ${
                    Number(sale.balanceAmount) > 0 ? 'text-rose-600' : 'text-emerald-600'
                  }`}
                >
                  {money(sale.balanceAmount)}
                </p>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Payment</p>
                <p className="mt-0.5 text-sm font-bold text-slate-800">{sale.paymentMethod}</p>
              </div>
            </div>
          </Card>

          <Card className="overflow-hidden">
            <div className="border-b border-slate-100 px-4 py-3">
              <h3 className="text-sm font-bold text-slate-800">Bill items</h3>
              <p className="text-[11px] text-slate-500">
                {cancelled
                  ? 'These quantities were returned to branch stock when the bill was cancelled.'
                  : 'Cancelling returns every line below to branch stock.'}
              </p>
            </div>
            <div className="thin-scrollbar overflow-x-auto">
              <table className="w-full min-w-[640px]">
                <thead className="bg-slate-50">
                  <tr>
                    <th className="th">Item</th>
                    <th className="th text-right">Qty</th>
                    <th className="th text-right">Rate</th>
                    <th className="th text-right">Discount</th>
                    <th className="th text-right">Tax</th>
                    <th className="th text-right">Amount</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {items.map((line) => (
                    <tr key={line.id ?? `${line.itemId}-${line.quantity}`}>
                      <td className="td">
                        <p className="font-semibold text-slate-800">{line.itemName}</p>
                        <p className="text-[11px] text-slate-400">{line.itemCode}</p>
                      </td>
                      <td className="td text-right">
                        {qty(line.quantity)} {line.unit}
                      </td>
                      <td className="td text-right">{money(line.sellingRate)}</td>
                      <td className="td text-right">{money(line.discount)}</td>
                      <td className="td text-right">{money(line.tax)}</td>
                      <td className="td text-right font-semibold">{money(line.totalAmount ?? 0)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <Card className="overflow-hidden">
            <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
              <div className="flex items-center gap-2">
                <History className="h-4 w-4 text-slate-400" />
                <h3 className="text-sm font-bold text-slate-800">Cancellation history</h3>
              </div>
              <Badge tone="slate">Immutable</Badge>
            </div>
            {recentLoading && !recent ? (
              <div className="flex min-h-24 items-center justify-center">
                <LoadingState label="Loading cancellations…" />
              </div>
            ) : !recent || recent.content.length === 0 ? (
              <EmptyState
                icon={<Ban className="h-6 w-6" />}
                title="No cancelled bills"
                message="Cancelled bills are kept here with their reason and reference number."
              />
            ) : (
              <ul className="divide-y divide-slate-100">
                {recent.content.map((entry) => (
                  <li key={entry.id}>
                    <button
                      onClick={() => navigate(`/sales/cancel/${entry.id}`)}
                      className={`flex w-full flex-col gap-1 px-4 py-3 text-left transition hover:bg-slate-50 ${
                        entry.id === sale.id ? 'bg-rose-50/60' : ''
                      }`}
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-xs font-bold text-slate-800">{entry.salesNumber}</span>
                          <StatusBadge status={entry.status} />
                          {entry.cancelledAt && (
                            <span className="text-[11px] text-slate-400">
                              {formatDateTime(entry.cancelledAt)}
                            </span>
                          )}
                        </div>
                        <span className="text-xs font-semibold text-slate-700">{money(entry.grandTotal)}</span>
                      </div>
                      <p className="text-[11px] text-slate-500">
                        {formatDate(entry.salesDate)} · {entry.branchName} · {entry.customerName || 'Walk-in'}
                      </p>
                      {entry.cancellationReason && (
                        <p className="truncate text-[11px] text-slate-500 italic">
                          “{entry.cancellationReason}”
                        </p>
                      )}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>

        {cancelled ? (
          <Card className="h-fit space-y-3 p-4 xl:sticky xl:top-20">
            <div className="flex items-center gap-2">
              <span className="flex h-9 w-9 items-center justify-center rounded-full bg-rose-50 text-rose-600">
                <Ban className="h-4 w-4" />
              </span>
              <div>
                <h3 className="text-sm font-bold text-slate-900">Cancellation record</h3>
                <p className="text-[11px] text-slate-500">Stored permanently; cannot be edited.</p>
              </div>
            </div>

            {cancellation ? (
              <>
                <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2">
                  <p className="text-[10px] font-bold tracking-wider text-rose-700 uppercase">Reference</p>
                  <p className="mt-0.5 text-sm font-bold text-rose-900">{cancellation.cancellationNumber}</p>
                  <p className="text-[11px] text-rose-700">
                    {formatDateTime(cancellation.cancelledAt)}
                    {cancellation.cancelledByName ? ` · ${cancellation.cancelledByName}` : ''}
                  </p>
                </div>
                <div>
                  <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Reason</p>
                  <p className="mt-0.5 text-xs text-slate-700">{cancellation.reason}</p>
                </div>
                <div className="space-y-1 border-t border-slate-200 pt-3 text-xs">
                  <div className="flex justify-between text-slate-500">
                    <span>Bill total</span>
                    <span>{money(cancellation.saleTotal)}</span>
                  </div>
                  <div className="flex justify-between text-slate-500">
                    <span>Paid at cancellation</span>
                    <span>{money(cancellation.paidAmount)}</span>
                  </div>
                  <div className="flex justify-between text-slate-500">
                    <span>Items returned to stock</span>
                    <span>{cancellation.restoredItems}</span>
                  </div>
                </div>
                {sale.cancellationReason && sale.cancellationReason !== cancellation.reason && (
                  <p className="text-[11px] text-slate-500 italic">Bill note: “{sale.cancellationReason}”</p>
                )}
                <Button
                  variant="outline"
                  className="w-full"
                  icon={<Printer className="h-4 w-4" />}
                  onClick={printInvoice}
                >
                  Print cancelled invoice
                </Button>
              </>
            ) : (
              <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600">
                {cancellationQuery.loading
                  ? 'Loading the cancellation record…'
                  : 'This bill is marked CANCELLED but no cancellation record was found for it. The invoice still prints with the CANCELLED watermark.'}
              </p>
            )}
          </Card>
        ) : (
          <CancelPanel key={sale.id} sale={sale} onCancelled={handleCancelled} onStale={saleQuery.reload} />
        )}
      </div>

      <InvoicePrintPreview
        open={previewOpen}
        sale={sale}
        store={storeInfo}
        cancellationNumber={cancellation?.cancellationNumber}
        cancellationReason={cancellation?.reason ?? sale.cancellationReason}
        onClose={() => setPreviewOpen(false)}
      />
    </div>
  )
}

function CancelPanel({
  sale,
  onCancelled,
  onStale,
}: {
  sale: Sale
  onCancelled: (record: SaleCancellation) => void
  onStale: () => void
}) {
  const [reason, setReason] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [saving, setSaving] = useState(false)

  const items = sale.items ?? []
  const paid = Number(sale.paidAmount)

  const openConfirm = () => {
    if (!reason.trim()) {
      setFormError('Enter the cancellation reason before continuing')
      return
    }
    setFormError(null)
    setConfirmOpen(true)
  }

  const confirmCancellation = async () => {
    const trimmed = reason.trim()
    if (!trimmed) {
      setConfirmOpen(false)
      setFormError('A cancellation reason is required')
      return
    }
    setSaving(true)
    try {
      const { data } = await api.post<SaleCancellation>(`/sales/${sale.id}/cancel`, { reason: trimmed })
      setConfirmOpen(false)
      setReason('')
      notify(`Bill cancelled — reference ${data.cancellationNumber}`)
      onCancelled(data)
    } catch (err) {
      setConfirmOpen(false)
      const message = errorMessage(err)
      setFormError(message)
      notifyError(message)
      if (err instanceof ApiError && err.status === 409) {
        // Another user cancelled this bill first: reload the authoritative state.
        onStale()
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <Card className="h-fit space-y-3 p-4 xl:sticky xl:top-20">
        <div className="flex items-center gap-2">
          <span className="flex h-9 w-9 items-center justify-center rounded-full bg-rose-50 text-rose-600">
            <ShieldAlert className="h-4 w-4" />
          </span>
          <div>
            <h3 className="text-sm font-bold text-slate-900">Cancel this bill</h3>
            <p className="text-[11px] text-slate-500">A reason is mandatory and recorded in the audit trail.</p>
          </div>
        </div>

        <Field label="Cancellation reason" required error={formError ?? undefined}>
          <Textarea
            rows={4}
            maxLength={500}
            value={reason}
            onChange={(event) => {
              setReason(event.target.value)
              if (formError) setFormError(null)
            }}
            placeholder="e.g. Customer returned the goods / duplicate billing"
          />
        </Field>

        <ul className="space-y-1.5 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[11px] text-amber-900">
          <li className="flex gap-1.5">
            <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            {items.length} line{items.length === 1 ? '' : 's'} (
            {qty(items.reduce((sum, line) => sum + Number(line.quantity), 0))} units) will be returned to{' '}
            {sale.branchName} stock.
          </li>
          <li className="flex gap-1.5">
            <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            The bill stays visible as CANCELLED in sales, reports and tax records.
          </li>
          <li className="flex gap-1.5">
            <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            Cancelling cannot be undone. Issue a new bill if the sale is repeated.
          </li>
        </ul>

        {paid > 0 && (
          <p className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-[11px] text-rose-700">
            {money(paid)} has already been collected on this bill. Refund it to the customer separately — the system
            does not move cash.
          </p>
        )}

        <Button variant="danger" className="w-full" icon={<Ban className="h-4 w-4" />} onClick={openConfirm}>
          Cancel this bill
        </Button>
      </Card>

      <Modal
        open={confirmOpen}
        onClose={() => {
          if (!saving) setConfirmOpen(false)
        }}
        title="Confirm bill cancellation"
        description={`${sale.salesNumber} · ${formatDate(sale.salesDate)} · ${sale.branchName}`}
        size="md"
        footer={
          <>
            <Button variant="outline" onClick={() => setConfirmOpen(false)} disabled={saving}>
              Keep the bill
            </Button>
            <Button variant="danger" onClick={() => void confirmCancellation()} loading={saving}>
              Confirm cancellation
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <div className="flex gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-rose-50 text-rose-600">
              <TriangleAlert className="h-5 w-5" />
            </span>
            <p className="pt-1 text-sm leading-relaxed text-slate-600">
              This marks the bill as CANCELLED, returns its stock and records the reason permanently. It cannot be
              undone.
            </p>
          </div>
          <div className="grid grid-cols-2 gap-2 text-xs">
            <div className="rounded-lg bg-slate-50 px-3 py-2">
              <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Bill total</p>
              <p className="mt-0.5 font-bold text-slate-800">{money(sale.grandTotal)}</p>
            </div>
            <div className="rounded-lg bg-slate-50 px-3 py-2">
              <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Paid</p>
              <p className="mt-0.5 font-bold text-slate-800">{money(sale.paidAmount)}</p>
            </div>
            <div className="rounded-lg bg-slate-50 px-3 py-2">
              <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Items</p>
              <p className="mt-0.5 font-bold text-slate-800">{items.length}</p>
            </div>
            <div className="rounded-lg bg-slate-50 px-3 py-2">
              <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Stock restored</p>
              <p className="mt-0.5 font-bold text-slate-800">All lines</p>
            </div>
          </div>
          <div className="rounded-lg border border-slate-200 px-3 py-2 text-xs">
            <span className="font-semibold text-slate-600">Reason: </span>
            <span className="text-slate-700">{reason.trim()}</span>
          </div>
          {paid > 0 && (
            <p className="rounded-lg bg-amber-50 px-3 py-2 text-[11px] text-amber-800">
              Remember to refund the {money(paid)} already collected from the customer.
            </p>
          )}
        </div>
      </Modal>
    </>
  )
}
