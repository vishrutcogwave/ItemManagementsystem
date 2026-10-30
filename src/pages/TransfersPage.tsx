import { ArrowLeftRight, ArrowRight, CheckCircle2, Eye, Plus, Send, Trash2, XCircle } from 'lucide-react'
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
import { formatDate, qty, today, titleCase } from '../lib/format'
import { unitOptionsFor } from '../lib/units'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type { InventoryRow, Item, PageResponse, Transfer, Unit } from '../types'

interface LineForm {
  key: number
  itemId: number | ''
  quantity: string
  unit: Unit
}

interface TransferForm {
  fromBranchId: number | ''
  toBranchId: number | ''
  transferDate: string
  notes: string
  items: LineForm[]
}

let lineKey = 0
const newLine = (): LineForm => ({ key: ++lineKey, itemId: '', quantity: '1', unit: 'PIECE' })

type ActionType = 'approve' | 'reject' | 'dispatch' | 'receive' | 'cancel'

const actionMeta: Record<ActionType, { label: string; success: string; title: string; message: (transfer: Transfer) => string }> = {
  approve: {
    label: 'Approve',
    success: 'Transfer approved',
    title: 'Approve transfer',
    message: (transfer) => `Approve ${transfer.transferNumber} for dispatch? Stock is not moved until dispatch.`,
  },
  reject: {
    label: 'Reject',
    success: 'Transfer rejected',
    title: 'Reject transfer',
    message: (transfer) => `Reject ${transfer.transferNumber}? It will not be dispatched.`,
  },
  dispatch: {
    label: 'Dispatch',
    success: 'Transfer dispatched and source stock deducted',
    title: 'Dispatch transfer',
    message: (transfer) =>
      `Dispatch ${transfer.transferNumber}? Stock will be deducted from ${transfer.fromBranchName} immediately.`,
  },
  receive: {
    label: 'Receive',
    success: 'Transfer received and destination stock updated',
    title: 'Receive transfer',
    message: (transfer) =>
      `Confirm receipt at ${transfer.toBranchName}? Stock will be added to the destination branch.`,
  },
  cancel: {
    label: 'Cancel',
    success: 'Transfer cancelled',
    title: 'Cancel transfer',
    message: (transfer) => `Cancel ${transfer.transferNumber}? This cannot be undone.`,
  },
}

export function TransfersPage() {
  const [params, setParams] = useSearchParams()
  const { branches, selectedBranchId, hasPermission } = useAuthStore()
  const canManage = hasPermission('TRANSFER_MANAGE')
  const canApprove = hasPermission('TRANSFER_APPROVE')
  const branchId = selectedBranchId

  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<Transfer>>(
    () =>
      api
        .get('/transfers', {
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

  const { data: items } = useApi<Item[]>(() => api.get('/items/options').then((response) => response.data), [])

  const [formOpen, setFormOpen] = useState(params.get('new') === '1')
  const firstBranch = selectedBranchId ?? branches[0]?.id ?? ''
  const [form, setForm] = useState<TransferForm>({
    fromBranchId: firstBranch,
    toBranchId: branches.find((branch) => branch.id !== firstBranch)?.id ?? '',
    transferDate: today(),
    notes: '',
    items: [newLine()],
  })
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const { data: sourceInventory } = useApi<PageResponse<InventoryRow>>(
    () =>
      api
        .get('/inventory', {
          params: { branchId: form.fromBranchId || undefined, size: 100, page: 0 },
        })
        .then((response) => response.data),
    [form.fromBranchId],
  )

  const [detail, setDetail] = useState<Transfer | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [action, setAction] = useState<{ type: ActionType; transfer: Transfer } | null>(null)
  const [actionLoading, setActionLoading] = useState(false)

  const available = (itemId: number | '') => {
    if (!itemId) return null
    const row = sourceInventory?.content.find((entry) => entry.itemId === itemId)
    return row ? row.currentStock : 0
  }

  const closeForm = () => {
    setFormOpen(false)
    if (params.get('new')) {
      const next = new URLSearchParams(params)
      next.delete('new')
      setParams(next, { replace: true })
    }
  }

  const openCreate = () => {
    const source = selectedBranchId ?? branches[0]?.id ?? ''
    setForm({
      fromBranchId: source,
      toBranchId: branches.find((branch) => branch.id !== source)?.id ?? '',
      transferDate: today(),
      notes: '',
      items: [newLine()],
    })
    setFormErrors({})
    setFormError(null)
    setFormOpen(true)
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
    updateLine(key, { itemId, unit: item?.baseUnit ?? 'PIECE' })
  }

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!form.fromBranchId) nextErrors.fromBranchId = 'Select the source branch'
    if (!form.toBranchId) nextErrors.toBranchId = 'Select the destination branch'
    if (form.fromBranchId && form.fromBranchId === form.toBranchId) nextErrors.toBranchId = 'Destination must differ from source'
    if (!form.transferDate) nextErrors.transferDate = 'Transfer date is required'
    const validLines = form.items.filter((line) => line.itemId && Number(line.quantity) > 0)
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
      await api.post('/transfers', {
        fromBranchId: form.fromBranchId,
        toBranchId: form.toBranchId,
        transferDate: form.transferDate,
        notes: form.notes.trim() || undefined,
        items: form.items.map((line) => ({
          itemId: line.itemId,
          quantity: Number(line.quantity),
          unit: line.unit,
        })),
      })
      notify('Transfer created and waiting for approval')
      closeForm()
      setStatusFilter('PENDING')
      reload()
    } catch (err) {
      setFormError(errorMessage(err))
      setFormErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  const viewTransfer = async (id: number) => {
    setDetailLoading(true)
    setDetail({ id } as Transfer)
    try {
      const response = await api.get<Transfer>(`/transfers/${id}`)
      setDetail(response.data)
    } catch (err) {
      notifyError(errorMessage(err))
      setDetail(null)
    } finally {
      setDetailLoading(false)
    }
  }

  const runAction = async () => {
    if (!action) return
    setActionLoading(true)
    try {
      const response = await api.post<Transfer>(`/transfers/${action.transfer.id}/${action.type}`)
      notify(actionMeta[action.type].success)
      setAction(null)
      setDetail(response.data)
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setActionLoading(false)
    }
  }

  const availableActions = (transfer: Transfer): ActionType[] => {
    const actions: ActionType[] = []
    if (transfer.status === 'PENDING' && canApprove) actions.push('approve', 'reject')
    if (transfer.status === 'APPROVED' && canApprove) actions.push('reject')
    if (transfer.status === 'APPROVED' && canManage) actions.push('dispatch')
    if (transfer.status === 'DISPATCHED' && canManage) actions.push('receive')
    if ((transfer.status === 'PENDING' || transfer.status === 'APPROVED') && canManage) actions.push('cancel')
    return actions
  }

  const columns: Column<Transfer>[] = [
    {
      key: 'number',
      header: 'Transfer',
      render: (row) => (
        <div>
          <p className="font-semibold text-slate-800">{row.transferNumber}</p>
          <p className="text-[11px] text-slate-400">{formatDate(row.transferDate)}</p>
        </div>
      ),
    },
    {
      key: 'route',
      header: 'Route',
      render: (row) => (
        <div className="flex items-center gap-1.5 text-xs">
          <Badge tone="slate">{row.fromBranchName}</Badge>
          <ArrowRight className="h-3.5 w-3.5 text-slate-400" />
          <Badge tone="brand">{row.toBranchName}</Badge>
        </div>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    { key: 'created', header: 'Created', render: (row) => <span className="text-slate-500">{formatDate(row.createdAt)}</span> },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (row) => (
        <div className="flex items-center justify-end gap-1">
          <button
            onClick={() => viewTransfer(row.id)}
            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="View"
          >
            <Eye className="h-3.5 w-3.5" />
          </button>
          {availableActions(row).includes('dispatch') && (
            <button
              onClick={() => setAction({ type: 'dispatch', transfer: row })}
              className="flex h-7 w-7 items-center justify-center rounded-md text-sky-500 transition hover:bg-sky-50"
              title="Dispatch"
            >
              <Send className="h-3.5 w-3.5" />
            </button>
          )}
          {availableActions(row).includes('receive') && (
            <button
              onClick={() => setAction({ type: 'receive', transfer: row })}
              className="flex h-7 w-7 items-center justify-center rounded-md text-emerald-500 transition hover:bg-emerald-50"
              title="Receive"
            >
              <CheckCircle2 className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="Stock transfers"
        subtitle="Move stock between branches with an approval and dispatch trail."
        actions={
          canManage ? (
            <Button icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
              New transfer
            </Button>
          ) : undefined
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 xl:flex-row xl:items-center">
          <SearchInput
            className="xl:w-72"
            placeholder="Search transfer number…"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value)
              setPage(0)
            }}
          />
          <Select
            className="xl:w-44"
            value={statusFilter}
            onChange={(event) => {
              setStatusFilter(event.target.value)
              setPage(0)
            }}
          >
            <option value="">All statuses</option>
            {['PENDING', 'APPROVED', 'DISPATCHED', 'RECEIVED', 'REJECTED', 'CANCELLED'].map((value) => (
              <option key={value} value={value}>
                {titleCase(value)}
              </option>
            ))}
          </Select>
          <div className="flex items-center gap-2">
            <Input
              type="date"
              className="w-38"
              value={from}
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
                  icon={<ArrowLeftRight className="h-6 w-6" />}
                  title="No transfers yet"
                  message="Create a transfer to request stock from another branch."
                  action={canManage ? <Button size="sm" onClick={openCreate}>New transfer</Button> : undefined}
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
                itemLabel="transfers"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={formOpen}
        onClose={closeForm}
        title="New stock transfer"
        description="Approval and dispatch are separate steps so stock can be verified before it moves."
        size="lg"
        footer={
          <>
            <Button variant="outline" onClick={closeForm} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              Create transfer
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
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <Field label="From branch" required error={formErrors.fromBranchId}>
              <Select
                value={form.fromBranchId}
                onChange={(event) => {
                  const value = event.target.value ? Number(event.target.value) : ''
                  setForm({
                    ...form,
                    fromBranchId: value,
                    toBranchId: form.toBranchId === value ? '' : form.toBranchId,
                  })
                }}
              >
                <option value="">Select source</option>
                {branches.map((branch) => (
                  <option key={branch.id} value={branch.id}>
                    {branch.branchName}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="To branch" required error={formErrors.toBranchId}>
              <Select
                value={form.toBranchId}
                onChange={(event) => setForm({ ...form, toBranchId: event.target.value ? Number(event.target.value) : '' })}
              >
                <option value="">Select destination</option>
                {branches
                  .filter((branch) => branch.id !== form.fromBranchId)
                  .map((branch) => (
                    <option key={branch.id} value={branch.id}>
                      {branch.branchName}
                    </option>
                  ))}
              </Select>
            </Field>
            <Field label="Transfer date" required error={formErrors.transferDate}>
              <Input
                type="date"
                value={form.transferDate}
                max={today()}
                onChange={(event) => setForm({ ...form, transferDate: event.target.value })}
              />
            </Field>
          </div>

          <div className="overflow-hidden rounded-xl border border-slate-200">
            <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
              <div>
                <h3 className="text-sm font-bold text-slate-800">Items to transfer</h3>
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
            <div className="divide-y divide-slate-100">
              {form.items.map((line) => {
                const item = items?.find((entry) => entry.id === line.itemId)
                const stock = available(line.itemId)
                return (
                  <div key={line.key} className="grid grid-cols-12 items-end gap-2 px-3 py-2.5">
                    <div className="col-span-12 sm:col-span-6">
                      <Field label="Item">
                        <Select value={line.itemId} onChange={(event) => chooseItem(line.key, event.target.value)}>
                          <option value="">Select item</option>
                          {items?.map((entry) => (
                            <option key={entry.id} value={entry.id}>
                              {entry.itemName} ({entry.itemCode})
                            </option>
                          ))}
                        </Select>
                      </Field>
                    </div>
                    <div className="col-span-5 sm:col-span-2">
                      <Field label="Qty" hint={stock !== null ? `Available ${qty(stock)}` : undefined}>
                        <Input
                          type="number"
                          min="0.001"
                          step="0.001"
                          value={line.quantity}
                          onChange={(event) => updateLine(line.key, { quantity: event.target.value })}
                        />
                      </Field>
                    </div>
                    <div className="col-span-4 sm:col-span-2">
                      <Field label="Unit">
                        <Select value={line.unit} onChange={(event) => updateLine(line.key, { unit: event.target.value as Unit })}>
                          {unitOptionsFor(item?.baseUnit).map((option) => (
                            <option key={option.value} value={option.value}>
                              {option.label}
                            </option>
                          ))}
                        </Select>
                      </Field>
                    </div>
                    <div className="col-span-3 flex justify-end pb-1 sm:col-span-2">
                      <button
                        onClick={() =>
                          setForm((current) => ({
                            ...current,
                            items: current.items.length === 1 ? current.items : current.items.filter((entry) => entry.key !== line.key),
                          }))
                        }
                        disabled={form.items.length === 1}
                        className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-rose-50 hover:text-rose-600 disabled:opacity-30"
                        title="Remove line"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          </div>

          <Field label="Notes">
            <Input
              value={form.notes}
              onChange={(event) => setForm({ ...form, notes: event.target.value })}
              placeholder="Optional instructions for the receiving branch"
            />
          </Field>
        </div>
      </Modal>

      <Modal
        open={Boolean(detail)}
        onClose={() => setDetail(null)}
        title={detail ? `Transfer ${detail.transferNumber}` : 'Transfer'}
        description={detail ? `${detail.fromBranchName} → ${detail.toBranchName} · ${formatDate(detail.transferDate)}` : undefined}
        size="lg"
        footer={
          detail && !detailLoading ? (
            <>
              {availableActions(detail).filter((type) => type !== 'cancel').map((type) => (
                <Button
                  key={type}
                  variant={type === 'reject' ? 'danger' : type === 'approve' ? 'success' : 'primary'}
                  icon={
                    type === 'approve' ? (
                      <CheckCircle2 className="h-4 w-4" />
                    ) : type === 'reject' ? (
                      <XCircle className="h-4 w-4" />
                    ) : type === 'dispatch' ? (
                      <Send className="h-4 w-4" />
                    ) : (
                      <CheckCircle2 className="h-4 w-4" />
                    )
                  }
                  onClick={() => setAction({ type, transfer: detail })}
                >
                  {actionMeta[type].label}
                </Button>
              ))}
              {availableActions(detail).includes('cancel') && (
                <Button variant="outline" onClick={() => setAction({ type: 'cancel', transfer: detail })}>
                  Cancel transfer
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
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">From</p>
                <p className="mt-0.5 text-xs font-semibold text-slate-700">{detail.fromBranchName}</p>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">To</p>
                <p className="mt-0.5 text-xs font-semibold text-slate-700">{detail.toBranchName}</p>
              </div>
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">Created</p>
                <p className="mt-0.5 text-xs font-semibold text-slate-700">{formatDate(detail.createdAt)}</p>
              </div>
            </div>

            <div className="overflow-hidden rounded-lg border border-slate-200">
              <table className="w-full">
                <thead className="bg-slate-50">
                  <tr>
                    <th className="th">Item</th>
                    <th className="th text-right">Quantity</th>
                    <th className="th text-right">Base quantity</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {detail.items?.map((line) => (
                    <tr key={line.id ?? `${line.itemId}-${line.quantity}`}>
                      <td className="td">
                        <p className="font-semibold text-slate-800">{line.itemName}</p>
                        <p className="text-[11px] text-slate-400">{line.itemCode}</p>
                      </td>
                      <td className="td text-right font-semibold">
                        {qty(line.quantity)} {line.unit}
                      </td>
                      <td className="td text-right text-slate-500">{qty(line.baseQuantity)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {detail.notes && <p className="text-xs text-slate-500">Notes: {detail.notes}</p>}

            <div className="rounded-lg border border-brand-100 bg-brand-50/60 px-3.5 py-2.5 text-[11px] leading-relaxed text-brand-800">
              Workflow: Pending → Approved → Dispatched (source stock deducted) → Received (destination stock added).
              Dispatch cannot be undone; use receive to complete the movement.
            </div>
          </div>
        )}
      </Modal>

      <ConfirmDialog
        open={Boolean(action)}
        onClose={() => setAction(null)}
        onConfirm={runAction}
        title={action ? actionMeta[action.type].title : 'Transfer action'}
        message={action ? actionMeta[action.type].message(action.transfer) : ''}
        confirmLabel={action ? actionMeta[action.type].label : 'Confirm'}
        tone={action && (action.type === 'reject' || action.type === 'cancel') ? 'danger' : 'primary'}
        loading={actionLoading}
      />
    </div>
  )
}
