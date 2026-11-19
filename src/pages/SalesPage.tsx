import { paymentBreakdown, salePayments } from '../lib/salePayments'
import { Ban, Eye, FilePenLine, FileText, Plus, Printer, ReceiptText, ShoppingCart } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Badge,
  Button,
  Card,
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
  Spinner,
  StatusBadge,
  Textarea,
  type Column,
} from '../components/ui'
import { InvoicePrintPreview } from '../components/billing/InvoicePrintPreview'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage } from '../lib/api'
import { formatDate, formatDateTime, money, qty, today } from '../lib/format'
import { printSaleReceiptForDevice } from '../services/printerService'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type { PageResponse, Sale, SaleCancellation } from '../types'

export function SalesPage() {
  const navigate = useNavigate()
  const { selectedBranchId, hasPermission, store } = useAuthStore()
  const canManage = hasPermission('SALES_MANAGE')
  const canModify = hasPermission('SALES_MODIFY')
  const canCancel = hasPermission('SALES_CANCEL')
  const branchId = selectedBranchId
  const storeInfo = {
    name: store?.storeName ?? 'Store',
    gstNumber: store?.gstNumber,
    address: store?.address,
    mobile: store?.mobile,
    email: store?.email,
  }

  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<Sale>>(
    () =>
      api
        .get('/sales', {
          params: {
            branchId: branchId ?? undefined,
            status: statusFilter || undefined,
            from: from || undefined,
            to: to || undefined,
            search: debouncedSearch || undefined,
            page,
            size,
          },
        })
        .then((response) => response.data),
    [branchId, statusFilter, from, to, debouncedSearch, page, size],
  )

  const [detail, setDetail] = useState<Sale | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [cancelSale, setCancelSale] = useState<Sale | null>(null)
  const [cancelReason, setCancelReason] = useState('')
  const [cancelError, setCancelError] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [printingId, setPrintingId] = useState<number | null>(null)
  const [printingDetail, setPrintingDetail] = useState(false)
  const [invoiceLoading, setInvoiceLoading] = useState<number | 'detail' | null>(null)
  const [previewSale, setPreviewSale] = useState<Sale | null>(null)

  const printSale = async (sale: Sale, rowId?: number) => {
    if (rowId !== undefined && printingId !== null) return
    if (rowId === undefined && printingDetail) return
    if (rowId !== undefined) setPrintingId(rowId)
    else setPrintingDetail(true)
    try {
      const outcome = await printSaleReceiptForDevice(sale, storeInfo)
      if (!outcome) return
      if (outcome.status === 'PRINTED') notify('Receipt sent to the printer')
      else if (outcome.status === 'DUPLICATE') notify('This receipt was already printed')
      else if (outcome.message) notifyError(outcome.message)
    } finally {
      if (rowId !== undefined) setPrintingId(null)
      else setPrintingDetail(false)
    }
  }

  const viewSale = async (id: number) => {
    setDetailLoading(true)
    setDetail({ id } as Sale)
    try {
      const response = await api.get<Sale>(`/sales/${id}`)
      setDetail(response.data)
    } catch (err) {
      notifyError(errorMessage(err))
      setDetail(null)
    } finally {
      setDetailLoading(false)
    }
  }

  const openInvoicePreview = async (rowId?: number) => {
    if (invoiceLoading !== null) return
    setInvoiceLoading(rowId ?? 'detail')
    try {
      const target = rowId !== undefined ? (await api.get<Sale>(`/sales/${rowId}`)).data : detail
      if (target) setPreviewSale(target)
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setInvoiceLoading(null)
    }
  }

  const openCancel = (sale: Sale) => {
    setCancelSale(sale)
    setCancelReason('')
    setCancelError(null)
  }

  const confirmCancel = async () => {
    if (!cancelSale) return
    const reason = cancelReason.trim()
    if (!reason) {
      setCancelError('Enter the reason for cancelling this bill')
      return
    }
    setCancelling(true)
    setCancelError(null)
    try {
      const response = await api.post<SaleCancellation>(`/sales/${cancelSale.id}/cancel`, { reason })
      notify(`Bill cancelled — reference ${response.data.cancellationNumber}`)
      const cancelledId = response.data.saleId
      setCancelSale(null)
      setCancelReason('')
      if (detail?.id === cancelledId) {
        setDetail(null)
        void viewSale(cancelledId)
      }
      reload()
    } catch (err) {
      setCancelError(errorMessage(err))
      notifyError(errorMessage(err))
    } finally {
      setCancelling(false)
    }
  }

  const columns: Column<Sale>[] = [
    {
      key: 'number',
      header: 'Invoice',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600">
            <ReceiptText className="h-4 w-4" />
          </span>
          <div>
            <p className="font-semibold text-slate-800">{row.salesNumber}</p>
            <p className="text-[11px] text-slate-400">{formatDate(row.salesDate)}</p>
            {row.modificationCount > 0 && (
              <p className="text-[10px] font-semibold text-amber-600">{row.modificationCount}× modified</p>
            )}
          </div>
        </div>
      ),
    },
    {
      key: 'customer',
      header: 'Customer',
      render: (row) => (
        <div>
          <p className="text-xs font-medium text-slate-700">{row.customerName || 'Walk-in'}</p>
          {row.customerMobile && <p className="text-[11px] text-slate-400">{row.customerMobile}</p>}
        </div>
      ),
    },
    { key: 'branch', header: 'Branch', render: (row) => <Badge tone="slate">{row.branchName}</Badge> },
    { key: 'total', header: 'Total', align: 'right', render: (row) => <span className="font-semibold text-slate-800">{money(row.grandTotal)}</span> },
    { key: 'payment', header: 'Payment', render: (row) => <Badge tone="blue">{paymentBreakdown(salePayments(row))}</Badge> },
    {
      key: 'balance',
      header: 'Balance',
      align: 'right',
      render: (row) =>
        Number(row.balanceAmount) > 0 ? (
          <span className="font-semibold text-rose-600">{money(row.balanceAmount)}</span>
        ) : (
          <span className="text-emerald-600">Paid</span>
        ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (row) => (
        <div className="flex items-center justify-end gap-1">
          <button
            onClick={() => viewSale(row.id)}
            className="flex h-8 w-8 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="View"
            aria-label={`View invoice ${row.salesNumber}`}
          >
            <Eye className="h-3.5 w-3.5" />
          </button>
          {canModify && row.status === 'COMPLETED' && (
            <button
              onClick={() => navigate(`/sales/modify/${row.id}`)}
              className="flex h-8 w-8 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
              title="Modify bill"
              aria-label={`Modify invoice ${row.salesNumber}`}
            >
              <FilePenLine className="h-3.5 w-3.5" />
            </button>
          )}
          <button
            onClick={() => void openInvoicePreview(row.id)}
            disabled={invoiceLoading !== null}
            className="flex h-8 w-8 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
            title="Preview & print tax invoice"
            aria-label={`Preview and print tax invoice for ${row.salesNumber}`}
          >
            {invoiceLoading === row.id ? <Spinner className="h-3.5 w-3.5" /> : <FileText className="h-3.5 w-3.5" />}
          </button>
          <button
            onClick={async () => {
              try {
                const response = await api.get<Sale>(`/sales/${row.id}`)
                await printSale(response.data, row.id)
              } catch (err) {
                notifyError(errorMessage(err))
              }
            }}
            disabled={printingId !== null}
            className="flex h-8 w-8 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
            title="Print receipt"
            aria-label={`Print receipt for invoice ${row.salesNumber}`}
          >
            {printingId === row.id ? <Spinner className="h-3.5 w-3.5" /> : <Printer className="h-3.5 w-3.5" />}
          </button>
        </div>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="Sales history"
        subtitle="Every completed bill with payment status, receipts and cancellations."
        actions={
          canManage ? (
            <Button icon={<Plus className="h-4 w-4" />} onClick={() => navigate('/sales/new')}>
              New sale
            </Button>
          ) : undefined
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 xl:flex-row xl:items-center">
          <SearchInput
            className="xl:w-72"
            placeholder="Search invoice, customer or mobile…"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value)
              setPage(0)
            }}
          />
          <Select
            className="xl:w-40"
            value={statusFilter}
            onChange={(event) => {
              setStatusFilter(event.target.value)
              setPage(0)
            }}
          >
            <option value="">All statuses</option>
            <option value="COMPLETED">Completed</option>
            <option value="CANCELLED">Cancelled</option>
          </Select>
          <div className="flex flex-wrap items-center gap-2">
            <Input
              type="date"
              className="w-38 min-w-0 flex-1 sm:flex-none"
              value={from}
              max={to || today()}
              onChange={(event) => {
                setFrom(event.target.value)
                setPage(0)
              }}
            />
            <span className="text-xs text-slate-400">to</span>
            <Input
              type="date"
              className="w-38 min-w-0 flex-1 sm:flex-none"
              value={to}
              min={from}
              max={today()}
              onChange={(event) => {
                setTo(event.target.value)
                setPage(0)
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

        {error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={data?.content ?? []}
              rowKey={(row) => row.id}
              loading={loading}
              empty={
                <EmptyState
                  icon={<ShoppingCart className="h-6 w-6" />}
                  title="No sales yet"
                  message="Bills completed from the POS screen will appear here."
                />
              }
            />
            {data && data.totalElements > 0 && (
              <Pagination
                page={page}
                size={size}
                totalPages={data.totalPages}
                totalElements={data.totalElements}
                onPageChange={setPage}
                onSizeChange={(value) => {
                  setSize(value)
                  setPage(0)
                }}
                itemLabel="sales"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={Boolean(detail)}
        onClose={() => setDetail(null)}
        title={detail ? `Invoice ${detail.salesNumber}` : 'Sale'}
        description={detail ? `${detail.branchName} · ${formatDateTime(detail.createdAt)}` : undefined}
        size="lg"
        footer={
          detail && !detailLoading ? (
            <div className="flex w-full flex-wrap items-center justify-end gap-2">
              <Button
                variant="outline"
                icon={invoiceLoading === 'detail' ? undefined : <FileText className="h-4 w-4" />}
                loading={invoiceLoading === 'detail'}
                onClick={() => void openInvoicePreview()}
              >
                Print invoice
              </Button>
              <Button
                variant="outline"
                icon={printingDetail ? undefined : <Printer className="h-4 w-4" />}
                loading={printingDetail}
                onClick={() => void printSale(detail)}
              >
                Print receipt
              </Button>
              {canModify && detail.status === 'COMPLETED' && (
                <Button
                  variant="ghost"
                  icon={<FilePenLine className="h-4 w-4" />}
                  onClick={() => navigate(`/sales/modify/${detail.id}`)}
                >
                  Modify bill
                </Button>
              )}
              {canCancel && detail.status === 'COMPLETED' && (
                <Button variant="danger" icon={<Ban className="h-4 w-4" />} onClick={() => openCancel(detail)}>
                  Cancel bill
                </Button>
              )}
            </div>
          ) : undefined
        }
      >
        {detailLoading || !detail ? (
          <LoadingState />
        ) : (
          <div className="space-y-4">
            {detail.status === 'CANCELLED' && (
              <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
                <p className="font-bold">
                  Bill cancelled{detail.cancelledAt ? ` on ${formatDateTime(detail.cancelledAt)}` : ''}
                </p>
                {detail.cancellationReason && <p className="mt-0.5">Reason: {detail.cancellationReason}</p>}
              </div>
            )}
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Status</p>
                <div className="mt-1">
                  <StatusBadge status={detail.status} />
                </div>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Customer</p>
                <p className="mt-0.5 truncate text-xs font-semibold text-slate-700">{detail.customerName || 'Walk-in'}</p>
                {(detail.customerMobile || detail.customerState) && (
                  <p className="truncate text-[10px] text-slate-400">
                    {[detail.customerMobile, detail.customerState].filter(Boolean).join(' · ')}
                  </p>
                )}
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Payment</p>
                <p className="mt-0.5 text-xs font-semibold text-slate-700">
                  {paymentBreakdown(salePayments(detail))}
                </p>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Balance</p>
                <p className={`mt-0.5 text-xs font-semibold ${Number(detail.balanceAmount) > 0 ? 'text-rose-600' : 'text-emerald-600'}`}>
                  {money(detail.balanceAmount)}
                </p>
              </div>
            </div>

            <div className="overflow-hidden rounded-lg border border-slate-200">
              <table className="w-full">
                <thead className="bg-slate-50">
                  <tr>
                    <th className="th">Item</th>
                    <th className="th text-right">Qty</th>
                    <th className="th text-right">Rate</th>
                    <th className="th text-right">Disc</th>
                    <th className="th text-right">Tax</th>
                    <th className="th text-right">Amount</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {detail.items?.map((line) => (
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
                      <td className="td text-right">
                        <p>{money(line.tax)}</p>
                        <p className="text-[10px] text-slate-400">
                          {line.taxName ?? '—'}
                          {line.taxName ? ` · ${Number(line.gstPercentage ?? 0)}%` : ''}
                        </p>
                      </td>
                      <td className="td text-right font-semibold">{money(line.totalAmount ?? 0)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="ml-auto w-full max-w-72 space-y-1 text-sm">
              <div className="flex justify-between text-slate-500">
                <span>Subtotal</span>
                <span>{money(detail.subtotal)}</span>
              </div>
              <div className="flex justify-between text-slate-500">
                <span>Discount</span>
                <span>− {money(detail.discount)}</span>
              </div>
              <div className="flex justify-between text-slate-500">
                <span>Taxable value</span>
                <span>{money(detail.taxableAmount)}</span>
              </div>
              {Number(detail.igstAmount) > 0 ? (
                <div className="flex justify-between text-slate-500">
                  <span>IGST</span>
                  <span>{money(detail.igstAmount)}</span>
                </div>
              ) : (
                <>
                  <div className="flex justify-between text-slate-500">
                    <span>CGST</span>
                    <span>{money(detail.cgstAmount)}</span>
                  </div>
                  <div className="flex justify-between text-slate-500">
                    <span>SGST</span>
                    <span>{money(detail.sgstAmount)}</span>
                  </div>
                </>
              )}
              <div className="flex justify-between text-slate-500">
                <span>Total tax</span>
                <span>{money(detail.tax)}</span>
              </div>
              <div className="flex justify-between border-t border-slate-200 pt-1.5 text-base font-bold text-slate-900">
                <span>Grand total</span>
                <span>{money(detail.grandTotal)}</span>
              </div>
            </div>
          </div>
        )}
      </Modal>

      <Modal
        open={Boolean(cancelSale)}
        onClose={() => {
          if (!cancelling) setCancelSale(null)
        }}
        title="Cancel bill"
        description={
          cancelSale
            ? `${cancelSale.salesNumber} · ${money(cancelSale.grandTotal)} · ${cancelSale.branchName}`
            : undefined
        }
        size="sm"
        footer={
          <>
            <Button variant="outline" onClick={() => setCancelSale(null)} disabled={cancelling}>
              Keep bill
            </Button>
            <Button variant="danger" onClick={() => void confirmCancel()} loading={cancelling}>
              Cancel bill
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <p className="text-sm leading-relaxed text-slate-600">
            Cancelling restores stock to the branch, marks the bill CANCELLED and keeps it in history with this
            reason. This cannot be undone.
          </p>
          <Field label="Cancellation reason" required error={cancelError ?? undefined}>
            <Textarea
              rows={3}
              maxLength={500}
              value={cancelReason}
              onChange={(event) => {
                setCancelReason(event.target.value)
                if (cancelError) setCancelError(null)
              }}
              placeholder="e.g. Customer returned the goods"
            />
          </Field>
          {Number(cancelSale?.paidAmount ?? 0) > 0 && (
            <p className="rounded-lg bg-amber-50 px-3 py-2 text-[11px] text-amber-800">
              {money(cancelSale?.paidAmount)} was collected on this bill — refund it to the customer separately.
            </p>
          )}
        </div>
      </Modal>

      <InvoicePrintPreview
        open={Boolean(previewSale)}
        sale={previewSale}
        store={storeInfo}
        cancellationReason={previewSale?.cancellationReason}
        onClose={() => setPreviewSale(null)}
      />
    </div>
  )
}
