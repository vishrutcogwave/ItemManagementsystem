import { Eye, Plus, Printer, ReceiptText, ShoppingCart } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Badge,
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  EmptyState,
  ErrorState,
  Input,
  LoadingState,
  Modal,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatusBadge,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage } from '../lib/api'
import { printSaleReceipt } from '../lib/documents'
import { formatDate, formatDateTime, money, qty, today } from '../lib/format'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type { PageResponse, Sale } from '../types'

export function SalesPage() {
  const navigate = useNavigate()
  const { selectedBranchId, hasPermission, store } = useAuthStore()
  const canManage = hasPermission('SALES_MANAGE')
  const branchId = selectedBranchId

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
  const [cancelling, setCancelling] = useState(false)

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

  const confirmCancel = async () => {
    if (!cancelSale) return
    setCancelling(true)
    try {
      const response = await api.post<Sale>(`/sales/${cancelSale.id}/cancel`)
      notify('Sale cancelled and stock restored')
      setCancelSale(null)
      setDetail(response.data)
      reload()
    } catch (err) {
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
    { key: 'payment', header: 'Payment', render: (row) => <Badge tone="blue">{row.paymentMethod}</Badge> },
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
            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="View"
          >
            <Eye className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={async () => {
              try {
                const response = await api.get<Sale>(`/sales/${row.id}`)
                printSaleReceipt(response.data, { name: store?.storeName ?? 'Store', gstNumber: store?.gstNumber })
              } catch (err) {
                notifyError(errorMessage(err))
              }
            }}
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
          <div className="flex items-center gap-2">
            <Input
              type="date"
              className="w-38"
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
              className="w-38"
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
            <>
              <Button
                variant="outline"
                icon={<Printer className="h-4 w-4" />}
                onClick={() =>
                  printSaleReceipt(detail, { name: store?.storeName ?? 'Store', gstNumber: store?.gstNumber })
                }
              >
                Print receipt
              </Button>
              {canManage && detail.status === 'COMPLETED' && (
                <Button variant="danger" onClick={() => setCancelSale(detail)}>
                  Cancel sale
                </Button>
              )}
            </>
          ) : undefined
        }
      >
        {detailLoading || !detail ? (
          <LoadingState />
        ) : (
          <div className="space-y-4">
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
                  {detail.paymentMethod} · {money(detail.paidAmount)}
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

      <ConfirmDialog
        open={Boolean(cancelSale)}
        onClose={() => setCancelSale(null)}
        onConfirm={confirmCancel}
        title="Cancel sale"
        message={`Cancel ${cancelSale?.salesNumber ?? 'this sale'}? Stock will be restored to the branch and the invoice will be marked cancelled.`}
        confirmLabel="Cancel sale"
        tone="danger"
        loading={cancelling}
        cancelLabel="Keep sale"
      />
    </div>
  )
}
