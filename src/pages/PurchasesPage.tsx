import { ClipboardList, Eye, Pencil, Plus, Printer, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  Badge,
  Button,
  Card,
  ConfirmDialog,
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
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { printPurchase } from '../lib/documents'
import { formatDate, money, qty, today } from '../lib/format'
import { billPreview, isInterState } from '../lib/tax'
import { unitOptionsFor } from '../lib/units'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type { Item, PageResponse, Purchase, Supplier, Unit } from '../types'

interface LineForm {
  key: number
  itemId: number | ''
  quantity: string
  unit: Unit
  purchaseRate: string
  discount: string
}

interface PurchaseFormState {
  branchId: number | ''
  supplierId: number | ''
  invoiceNumber: string
  purchaseDate: string
  status: 'DRAFT' | 'COMPLETED'
  discount: string
  paidAmount: string
  notes: string
  items: LineForm[]
}

let lineKey = 0
const newLine = (): LineForm => ({
  key: ++lineKey,
  itemId: '',
  quantity: '1',
  unit: 'PIECE',
  purchaseRate: '',
  discount: '0',
})

const toNumber = (value: string | number) => {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

export function PurchasesPage() {
  const [params, setParams] = useSearchParams()
  const { branches, selectedBranchId, hasPermission, store } = useAuthStore()
  const canManage = hasPermission('PURCHASE_MANAGE')
  const branchId = selectedBranchId

  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<Purchase>>(
    () =>
      api
        .get('/purchases', {
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

  const { data: suppliers } = useApi<Supplier[]>(
    () => api.get('/suppliers/options').then((response) => response.data),
    [],
  )
  const { data: items } = useApi<Item[]>(() => api.get('/items/options').then((response) => response.data), [])

  const [formOpen, setFormOpen] = useState(params.get('new') === '1')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [form, setForm] = useState<PurchaseFormState>(() => ({
    branchId: selectedBranchId ?? branches[0]?.id ?? '',
    supplierId: '',
    invoiceNumber: '',
    purchaseDate: today(),
    status: 'COMPLETED',
    discount: '0',
    paidAmount: '',
    notes: '',
    items: [newLine()],
  }))
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const [detail, setDetail] = useState<Purchase | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [action, setAction] = useState<{ type: 'complete' | 'cancel'; purchase: Purchase } | null>(null)
  const [actionLoading, setActionLoading] = useState(false)

  const closeForm = () => {
    setFormOpen(false)
    setEditingId(null)
    if (params.get('new')) {
      const next = new URLSearchParams(params)
      next.delete('new')
      setParams(next, { replace: true })
    }
  }

  const openCreate = () => {
    setEditingId(null)
    setForm({
      branchId: selectedBranchId ?? branches[0]?.id ?? '',
      supplierId: '',
      invoiceNumber: '',
      purchaseDate: today(),
      status: 'COMPLETED',
      discount: '0',
      paidAmount: '',
      notes: '',
      items: [newLine()],
    })
    setFormErrors({})
    setFormError(null)
    setFormOpen(true)
  }

  const openEdit = (purchase: Purchase) => {
    setEditingId(purchase.id)
    setForm({
      branchId: purchase.branchId,
      supplierId: purchase.supplierId,
      invoiceNumber: purchase.invoiceNumber ?? '',
      purchaseDate: purchase.purchaseDate,
      status: 'DRAFT',
      discount: String(purchase.discount),
      paidAmount: String(purchase.paidAmount ?? 0),
      notes: purchase.notes ?? '',
      items: purchase.items.map((line) => ({
        key: ++lineKey,
        itemId: line.itemId,
        quantity: String(line.quantity),
        unit: line.unit,
        purchaseRate: String(line.purchaseRate),
        discount: String(line.discount),
      })),
    })
    setFormErrors({})
    setFormError(null)
    setDetail(null)
    setFormOpen(true)
  }

  const viewPurchase = async (id: number) => {
    setDetailLoading(true)
    setDetail({ id } as Purchase)
    try {
      const response = await api.get<Purchase>(`/purchases/${id}`)
      setDetail(response.data)
    } catch (err) {
      notifyError(errorMessage(err))
      setDetail(null)
    } finally {
      setDetailLoading(false)
    }
  }

  const updateLine = (key: number, patch: Partial<LineForm>) => {
    setForm((current) => ({
      ...current,
      items: current.items.map((line) => (line.key === key ? { ...line, ...patch } : line)),
    }))
  }

  const chooseItem = (key: number, itemIdRaw: string) => {
    const itemId = itemIdRaw ? Number(itemIdRaw) : ''
    const item = items?.find((entry) => entry.id === itemId)
    updateLine(key, {
      itemId,
      unit: item?.baseUnit ?? 'PIECE',
      purchaseRate: '',
    })
  }

  const removeLine = (key: number) => {
    setForm((current) => ({
      ...current,
      items: current.items.length === 1 ? current.items : current.items.filter((line) => line.key !== key),
    }))
  }

  const inclusive = Boolean(store?.taxInclusive)
  const branchState = branches.find((branch) => branch.id === form.branchId)?.state ?? null
  const supplierState = suppliers?.find((supplier) => supplier.id === form.supplierId)?.state ?? null
  const interState = isInterState(branchState, supplierState)
  const preview = billPreview(
    form.items.map((line) => ({
      gross: toNumber(line.quantity) * toNumber(line.purchaseRate),
      discount: toNumber(line.discount),
      gstPercentage: items?.find((entry) => entry.id === line.itemId)?.gstPercentage ?? 0,
    })),
    toNumber(form.discount),
    inclusive,
    interState,
  )
  const { subtotal, tax: totalTax, grand: grandTotal } = preview

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!form.branchId) nextErrors.branchId = 'Select a branch'
    if (!form.supplierId) nextErrors.supplierId = 'Select a supplier'
    if (!form.purchaseDate) nextErrors.purchaseDate = 'Purchase date is required'
    const validLines = form.items.filter((line) => line.itemId && toNumber(line.quantity) > 0)
    if (validLines.length === 0) nextErrors.items = 'Add at least one item with quantity'
    if (validLines.length !== form.items.length) nextErrors.items = 'Remove empty lines or enter quantity'
    if (Object.keys(nextErrors).length > 0) {
      setFormErrors(nextErrors)
      return
    }

    setSaving(true)
    setFormError(null)
    setFormErrors({})
    try {
      const payload = {
        branchId: form.branchId,
        supplierId: form.supplierId,
        invoiceNumber: form.invoiceNumber.trim() || undefined,
        purchaseDate: form.purchaseDate,
        discount: toNumber(form.discount),
        paidAmount: form.paidAmount === '' ? undefined : toNumber(form.paidAmount),
        notes: form.notes.trim() || undefined,
        status: editingId ? 'DRAFT' : form.status,
        items: form.items.map((line) => ({
          itemId: line.itemId,
          quantity: toNumber(line.quantity),
          unit: line.unit,
          purchaseRate: toNumber(line.purchaseRate),
          discount: toNumber(line.discount),
        })),
      }

      if (editingId) {
        await api.put(`/purchases/${editingId}`, payload)
        notify('Draft purchase updated')
      } else {
        await api.post('/purchases', payload)
        notify(form.status === 'COMPLETED' ? 'Purchase saved and stock updated' : 'Draft purchase saved')
      }
      closeForm()
      reload()
    } catch (err) {
      setFormError(errorMessage(err))
      setFormErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  const runAction = async () => {
    if (!action) return
    setActionLoading(true)
    try {
      const response = await api.post<Purchase>(
        `/purchases/${action.purchase.id}/${action.type === 'complete' ? 'complete' : 'cancel'}`,
      )
      notify(action.type === 'complete' ? 'Purchase completed and stock received' : 'Purchase cancelled and stock reversed')
      setAction(null)
      setDetail(response.data)
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setActionLoading(false)
    }
  }

  const columns: Column<Purchase>[] = [
    {
      key: 'number',
      header: 'Purchase',
      render: (row) => (
        <div>
          <p className="font-semibold text-slate-800">{row.purchaseNumber}</p>
          <p className="text-[11px] text-slate-400">{row.invoiceNumber ? `Invoice ${row.invoiceNumber}` : 'No invoice no.'}</p>
        </div>
      ),
    },
    { key: 'date', header: 'Date', render: (row) => <span className="text-slate-600">{formatDate(row.purchaseDate)}</span> },
    { key: 'supplier', header: 'Supplier', render: (row) => <span className="font-medium text-slate-700">{row.supplierName}</span> },
    { key: 'branch', header: 'Branch', render: (row) => <Badge tone="slate">{row.branchName}</Badge> },
    { key: 'amount', header: 'Amount', align: 'right', render: (row) => <span className="font-semibold text-slate-800">{money(row.grandTotal)}</span> },
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
            onClick={() => viewPurchase(row.id)}
            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="View"
          >
            <Eye className="h-3.5 w-3.5" />
          </button>
          {canManage && row.status === 'DRAFT' && (
            <button
              onClick={async () => {
                try {
                  const response = await api.get<Purchase>(`/purchases/${row.id}`)
                  openEdit(response.data)
                } catch (err) {
                  notifyError(errorMessage(err))
                }
              }}
              className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
              title="Edit draft"
            >
              <Pencil className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="Purchases"
        subtitle="Record stock received from suppliers. Completing a purchase updates inventory."
        actions={
          canManage ? (
            <Button icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
              New purchase
            </Button>
          ) : undefined
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 xl:flex-row xl:items-center">
          <SearchInput
            className="xl:w-72"
            placeholder="Search number, invoice or supplier…"
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
            <option value="DRAFT">Draft</option>
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
                  icon={<ClipboardList className="h-6 w-6" />}
                  title="No purchases yet"
                  message="Record your first supplier purchase to bring stock into a branch."
                  action={canManage ? <Button size="sm" onClick={openCreate}>New purchase</Button> : undefined}
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
                itemLabel="purchases"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={formOpen}
        onClose={closeForm}
        title={editingId ? 'Edit draft purchase' : 'New purchase'}
        description="Stock is updated immediately when the purchase is completed."
        size="xl"
        bodyClassName="bg-slate-50/40"
        footer={
          <>
            <Button variant="outline" onClick={closeForm} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              {editingId ? 'Save draft' : form.status === 'DRAFT' ? 'Save draft' : 'Save & receive stock'}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {formError && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
              {formError}
            </div>
          )}

          <div className="grid grid-cols-1 gap-3 rounded-xl border border-slate-200 bg-white p-4 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="Branch" required error={formErrors.branchId}>
              <Select
                value={form.branchId}
                onChange={(event) => setForm({ ...form, branchId: event.target.value ? Number(event.target.value) : '' })}
              >
                <option value="">Select branch</option>
                {branches.map((branch) => (
                  <option key={branch.id} value={branch.id}>
                    {branch.branchName}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Supplier" required error={formErrors.supplierId}>
              <Select
                value={form.supplierId}
                onChange={(event) => setForm({ ...form, supplierId: event.target.value ? Number(event.target.value) : '' })}
              >
                <option value="">Select supplier</option>
                {suppliers?.map((supplier) => (
                  <option key={supplier.id} value={supplier.id}>
                    {supplier.supplierName}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Purchase date" required error={formErrors.purchaseDate}>
              <Input
                type="date"
                value={form.purchaseDate}
                max={today()}
                onChange={(event) => setForm({ ...form, purchaseDate: event.target.value })}
              />
            </Field>
            <Field label="Supplier invoice no.">
              <Input
                value={form.invoiceNumber}
                onChange={(event) => setForm({ ...form, invoiceNumber: event.target.value })}
                placeholder="Optional"
              />
            </Field>
            {!editingId && (
              <Field label="Save as">
                <Select
                  value={form.status}
                  onChange={(event) => setForm({ ...form, status: event.target.value as 'DRAFT' | 'COMPLETED' })}
                >
                  <option value="COMPLETED">Completed · update stock</option>
                  <option value="DRAFT">Draft · no stock update</option>
                </Select>
              </Field>
            )}
            <Field label="Paid amount" hint="Blank = full payment on completed" className={editingId ? 'lg:col-span-1' : ''}>
              <Input
                type="number"
                min="0"
                step="0.01"
                value={form.paidAmount}
                onChange={(event) => setForm({ ...form, paidAmount: event.target.value })}
                placeholder={String(grandTotal.toFixed(2))}
              />
            </Field>
            <Field label="Notes" className="sm:col-span-2 lg:col-span-2">
              <Input
                value={form.notes}
                onChange={(event) => setForm({ ...form, notes: event.target.value })}
                placeholder="Optional remarks"
              />
            </Field>
          </div>

          <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
            <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
              <div>
                <h3 className="text-sm font-bold text-slate-800">Purchase items</h3>
                {formErrors.items && <p className="text-xs font-medium text-rose-600">{formErrors.items}</p>}
              </div>
              <Button
                variant="outline"
                size="sm"
                icon={<Plus className="h-3.5 w-3.5" />}
                onClick={() => setForm((current) => ({ ...current, items: [...current.items, newLine()] }))}
              >
                Add line
              </Button>
            </div>
            <div className="thin-scrollbar overflow-x-auto">
              <table className="w-full min-w-[780px]">
                <thead className="bg-slate-50">
                  <tr>
                    <th className="th">Item</th>
                    <th className="th w-16">Qty</th>
                    <th className="th w-28">Unit</th>
                    <th className="th w-28">Rate ₹</th>
                    <th className="th w-16">Disc ₹</th>
                    <th className="th w-24">GST</th>
                    <th className="th w-20 text-right">Total</th>
                    <th className="th w-10" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {form.items.map((line, index) => {
                    const item = items?.find((entry) => entry.id === line.itemId)
                    const linePreview = preview.lines[index]
                    return (
                      <tr key={line.key}>
                        <td className="px-2 py-2">
                          <Select value={line.itemId} onChange={(event) => chooseItem(line.key, event.target.value)}>
                            <option value="">Select item</option>
                            {items?.map((entry) => (
                              <option key={entry.id} value={entry.id}>
                                {entry.itemName} ({entry.itemCode})
                              </option>
                            ))}
                          </Select>
                        </td>
                        <td className="px-2 py-2">
                          <Input
                            type="number"
                            min="0.001"
                            step="0.001"
                            value={line.quantity}
                            onChange={(event) => updateLine(line.key, { quantity: event.target.value })}
                          />
                        </td>
                        <td className="px-2 py-2">
                          <Select
                            value={line.unit}
                            onChange={(event) => updateLine(line.key, { unit: event.target.value as Unit })}
                          >
                            {unitOptionsFor(item?.baseUnit).map((option) => (
                              <option key={option.value} value={option.value}>
                                {option.label}
                              </option>
                            ))}
                          </Select>
                        </td>
                        <td className="px-2 py-2">
                          <Input
                            type="number"
                            min="0"
                            step="0.01"
                            value={line.purchaseRate}
                            onChange={(event) => updateLine(line.key, { purchaseRate: event.target.value })}
                          />
                        </td>
                        <td className="px-2 py-2">
                          <Input
                            type="number"
                            min="0"
                            step="0.01"
                            value={line.discount}
                            onChange={(event) => updateLine(line.key, { discount: event.target.value })}
                          />
                        </td>
                        <td className="px-2 py-2">
                          <div className="rounded-lg border border-slate-200 bg-slate-50 px-2 py-1.5 text-right">
                            <p className="text-[10px] font-semibold text-slate-400">
                              {item
                                ? item.taxType === 'EXEMPT'
                                  ? 'Exempt'
                                  : `${Number(item.gstPercentage)}% GST`
                                : '—'}
                            </p>
                            <p className="text-xs font-semibold text-slate-700">
                              {money(linePreview?.taxAmount ?? 0)}
                            </p>
                          </div>
                        </td>
                        <td className="td text-right font-semibold">{money(linePreview?.payableAmount ?? 0)}</td>
                        <td className="px-2 py-2 text-center">
                          <button
                            onClick={() => removeLine(line.key)}
                            disabled={form.items.length === 1}
                            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-rose-50 hover:text-rose-600 disabled:opacity-30"
                            title="Remove line"
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </div>

          <div className="flex flex-col gap-4 sm:flex-row sm:justify-end">
            <div className="flex gap-3 sm:w-80">
              <Field label="Bill discount ₹" className="flex-1">
                <Input
                  type="number"
                  min="0"
                  step="0.01"
                  value={form.discount}
                  onChange={(event) => setForm({ ...form, discount: event.target.value })}
                />
              </Field>
              <div className="flex-1 rounded-lg border border-slate-200 bg-slate-50 px-3 py-1.5">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">GST mode</p>
                <p className="mt-0.5 text-xs font-semibold text-slate-700">
                  {inclusive ? 'Inclusive' : 'Exclusive'}
                  {interState ? ' · inter-state (IGST)' : ' · intra-state (CGST+SGST)'}
                </p>
              </div>
            </div>
            <div className="w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm sm:w-72">
              <div className="flex justify-between py-0.5 text-slate-500">
                <span>Subtotal</span>
                <span>{money(subtotal)}</span>
              </div>
              <div className="flex justify-between py-0.5 text-slate-500">
                <span>Discount</span>
                <span>− {money(preview.discount)}</span>
              </div>
              <div className="flex justify-between py-0.5 text-slate-500">
                <span>Taxable value</span>
                <span>{money(preview.taxable)}</span>
              </div>
              {interState ? (
                <div className="flex justify-between py-0.5 text-slate-500">
                  <span>IGST</span>
                  <span>{money(preview.igst)}</span>
                </div>
              ) : (
                <>
                  <div className="flex justify-between py-0.5 text-slate-500">
                    <span>CGST</span>
                    <span>{money(preview.cgst)}</span>
                  </div>
                  <div className="flex justify-between py-0.5 text-slate-500">
                    <span>SGST</span>
                    <span>{money(preview.sgst)}</span>
                  </div>
                </>
              )}
              <div className="flex justify-between py-0.5 text-slate-500">
                <span>Total tax</span>
                <span>{money(totalTax)}</span>
              </div>
              <div className="mt-1.5 flex justify-between border-t border-slate-200 pt-2 text-base font-bold text-slate-900">
                <span>Grand total</span>
                <span>{money(grandTotal)}</span>
              </div>
            </div>
          </div>
        </div>
      </Modal>

      <Modal
        open={Boolean(detail)}
        onClose={() => setDetail(null)}
        title={detail ? `Purchase ${detail.purchaseNumber}` : 'Purchase'}
        description={detail ? `${detail.supplierName} · ${detail.branchName} · ${formatDate(detail.purchaseDate)}` : undefined}
        size="lg"
        footer={
          detail && !detailLoading ? (
            <>
              <Button variant="outline" icon={<Printer className="h-4 w-4" />} onClick={() => printPurchase(detail, { name: store?.storeName ?? 'Store', gstNumber: store?.gstNumber })}>
                Print
              </Button>
              {canManage && detail.status === 'DRAFT' && (
                <>
                  <Button variant="outline" icon={<Pencil className="h-4 w-4" />} onClick={() => openEdit(detail)}>
                    Edit
                  </Button>
                  <Button
                    onClick={() => setAction({ type: 'complete', purchase: detail })}
                    icon={<ClipboardList className="h-4 w-4" />}
                  >
                    Complete & receive
                  </Button>
                </>
              )}
              {canManage && detail.status !== 'CANCELLED' && (
                <Button variant="danger" onClick={() => setAction({ type: 'cancel', purchase: detail })}>
                  Cancel purchase
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
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Invoice</p>
                <p className="mt-0.5 text-xs font-semibold text-slate-700">{detail.invoiceNumber || '—'}</p>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Paid</p>
                <p className="mt-0.5 text-xs font-semibold text-slate-700">{money(detail.paidAmount)}</p>
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
                      <td className="td text-right">{money(line.purchaseRate)}</td>
                      <td className="td text-right">{money(line.discount)}</td>
                      <td className="td text-right">
                        <p>{money(line.tax)}</p>
                        <p className="text-[10px] text-slate-400">
                          {line.taxName ?? '—'}
                          {line.taxName && line.taxId ? ` · ${Number(line.gstPercentage ?? 0)}%` : ''}
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

            {detail.notes && <p className="text-xs text-slate-500">Notes: {detail.notes}</p>}
          </div>
        )}
      </Modal>

      <ConfirmDialog
        open={Boolean(action)}
        onClose={() => setAction(null)}
        onConfirm={runAction}
        title={action?.type === 'complete' ? 'Complete purchase' : 'Cancel purchase'}
        message={
          action?.type === 'complete'
            ? `Receive stock for ${action.purchase.purchaseNumber}? Inventory will increase for every line.`
            : `Cancel ${action?.purchase.purchaseNumber ?? 'this purchase'}? Completed purchases will reverse their stock movement, and this cannot be undone.`
        }
        confirmLabel={action?.type === 'complete' ? 'Complete purchase' : 'Cancel purchase'}
        tone={action?.type === 'cancel' ? 'danger' : 'primary'}
        loading={actionLoading}
        cancelLabel="Keep as is"
      />
    </div>
  )
}
