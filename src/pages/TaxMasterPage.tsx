import { Pencil, Percent, Plus, Power, Trash2 } from 'lucide-react'
import { useState } from 'react'
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
  Modal,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatusBadge,
  Textarea,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { formatDate } from '../lib/format'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type { PageResponse, TaxMaster, TaxMasterRequest, TaxType } from '../types'

interface FormState {
  taxName: string
  description: string
  taxType: TaxType
  gstPercentage: string
  status: 'ACTIVE' | 'INACTIVE'
}

const emptyForm: FormState = {
  taxName: '',
  description: '',
  taxType: 'GST',
  gstPercentage: '',
  status: 'ACTIVE',
}

function rate(value: number | string | null | undefined): string {
  return `${Number(value ?? 0).toFixed(2)}%`
}

export function TaxMasterPage() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const canManage = hasPermission('TAX_MANAGE')

  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<TaxMaster>>(
    () =>
      api
        .get('/taxes', {
          params: {
            search: debouncedSearch || undefined,
            status: status || undefined,
            page,
            size,
          },
        })
        .then((response) => response.data),
    [debouncedSearch, status, page, size],
  )

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<TaxMaster | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm)
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState<TaxMaster | null>(null)
  const [deleteLoading, setDeleteLoading] = useState(false)

  const openCreate = () => {
    setEditing(null)
    setForm(emptyForm)
    setFormErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const openEdit = (tax: TaxMaster) => {
    setEditing(tax)
    setForm({
      taxName: tax.taxName,
      description: tax.description ?? '',
      taxType: tax.taxType === 'EXEMPT' ? 'EXEMPT' : 'GST',
      gstPercentage: tax.taxType === 'EXEMPT' ? '0' : String(tax.gstPercentage),
      status: tax.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    })
    setFormErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const gst = form.taxType === 'EXEMPT' ? 0 : Number(form.gstPercentage || 0)
  const cgst = Math.round((gst / 2) * 100) / 100
  const sgst = Math.round((gst - gst / 2) * 100) / 100

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!form.taxName.trim()) nextErrors.taxName = 'Tax name is required'
    if (form.taxType === 'GST') {
      const value = Number(form.gstPercentage)
      if (form.gstPercentage === '' || Number.isNaN(value)) nextErrors.gstPercentage = 'GST percentage is required'
      else if (value <= 0 || value > 100) nextErrors.gstPercentage = 'Enter a rate between 0.01 and 100'
    }
    if (Object.keys(nextErrors).length > 0) {
      setFormErrors(nextErrors)
      return
    }
    setSaving(true)
    setFormError(null)
    setFormErrors({})
    try {
      const payload: TaxMasterRequest = {
        taxName: form.taxName.trim(),
        description: form.description.trim() || undefined,
        taxType: form.taxType,
        gstPercentage: form.taxType === 'EXEMPT' ? 0 : Number(form.gstPercentage),
        status: form.status,
      }
      if (editing) {
        await api.put(`/taxes/${editing.id}`, payload)
        notify('Tax updated. Existing invoices keep their original rates.')
      } else {
        await api.post('/taxes', payload)
        notify('Tax created')
      }
      setModalOpen(false)
      reload()
    } catch (err) {
      setFormError(errorMessage(err))
      setFormErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  const toggleStatus = async (tax: TaxMaster) => {
    try {
      await api.put(`/taxes/${tax.id}/status`, {
        status: tax.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      })
      notify(tax.status === 'ACTIVE' ? 'Tax deactivated' : 'Tax activated')
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    }
  }

  const confirmDelete = async () => {
    if (!deleting) return
    setDeleteLoading(true)
    try {
      await api.delete(`/taxes/${deleting.id}`)
      notify('Tax deleted')
      setDeleting(null)
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setDeleteLoading(false)
    }
  }

  const columns: Column<TaxMaster>[] = [
    {
      key: 'name',
      header: 'Tax',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
            <Percent className="h-4 w-4" />
          </span>
          <div>
            <p className="font-semibold text-slate-800">{row.taxName}</p>
            <p className="max-w-56 truncate text-[11px] text-slate-400">{row.description || 'No description'}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      render: (row) =>
        row.taxType === 'EXEMPT' ? <Badge tone="slate">Exempt</Badge> : <Badge tone="green">GST</Badge>,
    },
    { key: 'gst', header: 'GST', align: 'right', render: (row) => <span className="font-semibold text-slate-800">{rate(row.gstPercentage)}</span> },
    { key: 'cgst', header: 'CGST', align: 'right', render: (row) => <span className="text-slate-600">{rate(row.cgstPercentage)}</span> },
    { key: 'sgst', header: 'SGST', align: 'right', render: (row) => <span className="text-slate-600">{rate(row.sgstPercentage)}</span> },
    { key: 'igst', header: 'IGST', align: 'right', render: (row) => <span className="text-slate-600">{rate(row.igstPercentage)}</span> },
    { key: 'items', header: 'Items', align: 'right', render: (row) => <span className="font-semibold">{row.itemCount}</span> },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    { key: 'created', header: 'Created', render: (row) => <span className="text-slate-500">{formatDate(row.createdAt)}</span> },
    ...(canManage
      ? [
          {
            key: 'actions',
            header: '',
            align: 'right' as const,
            render: (row: TaxMaster) => (
              <div className="flex items-center justify-end gap-1">
                <button
                  onClick={() => openEdit(row)}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
                  title="Edit"
                >
                  <Pencil className="h-3.5 w-3.5" />
                </button>
                <button
                  onClick={() => toggleStatus(row)}
                  className={`flex h-7 w-7 items-center justify-center rounded-md transition hover:bg-slate-100 ${
                    row.status === 'ACTIVE' ? 'text-amber-500 hover:text-amber-600' : 'text-emerald-500'
                  }`}
                  title={row.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                >
                  <Power className="h-3.5 w-3.5" />
                </button>
                <button
                  onClick={() => setDeleting(row)}
                  disabled={row.itemCount > 0}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-30"
                  title={row.itemCount > 0 ? 'Reassign items before deleting this tax' : 'Delete'}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            ),
          },
        ]
      : []),
  ]

  return (
    <div>
      <PageHeader
        title="Tax Master"
        subtitle="Store-specific GST slabs that drive automatic tax on purchases and sales."
        actions={
          canManage ? (
            <Button icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
              Add tax
            </Button>
          ) : undefined
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center">
          <SearchInput
            className="sm:w-80"
            placeholder="Search tax name or description…"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value)
              setPage(0)
            }}
          />
          <Select
            className="sm:w-44"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value)
              setPage(0)
            }}
          >
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </Select>
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
                  icon={<Percent className="h-6 w-6" />}
                  title="No taxes configured"
                  message="Add GST slabs such as 5%, 12% or 18%, plus an exempt slab for tax-free items."
                  action={canManage ? <Button size="sm" onClick={openCreate}>Add tax</Button> : undefined}
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
                itemLabel="taxes"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit tax' : 'Add tax'}
        description="Editing a slab never changes the tax stored on past purchases or sales."
        footer={
          <>
            <Button variant="outline" onClick={() => setModalOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              {editing ? 'Save changes' : 'Create tax'}
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
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Tax name" required error={formErrors.taxName}>
              <Input
                value={form.taxName}
                onChange={(event) => setForm({ ...form, taxName: event.target.value })}
                placeholder="e.g. GST 18%"
                autoFocus
              />
            </Field>
            <Field label="Tax type" required>
              <Select
                value={form.taxType}
                onChange={(event) => setForm({ ...form, taxType: event.target.value as TaxType })}
              >
                <option value="GST">GST</option>
                <option value="EXEMPT">Exempt / Nil rated</option>
              </Select>
            </Field>
          </div>
          <Field
            label="GST percentage"
            required={form.taxType === 'GST'}
            error={formErrors.gstPercentage}
            hint="CGST and SGST are half each for intra-state; IGST equals the full rate for inter-state."
          >
            <Input
              type="number"
              min="0"
              max="100"
              step="0.01"
              value={form.taxType === 'EXEMPT' ? '0' : form.gstPercentage}
              onChange={(event) => setForm({ ...form, gstPercentage: event.target.value })}
              placeholder="18"
              disabled={form.taxType === 'EXEMPT'}
            />
          </Field>
          <div className="grid grid-cols-3 gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-center text-xs">
            <div>
              <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">CGST</p>
              <p className="mt-0.5 font-semibold text-slate-700">{rate(form.taxType === 'EXEMPT' ? 0 : cgst)}</p>
            </div>
            <div>
              <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">SGST</p>
              <p className="mt-0.5 font-semibold text-slate-700">{rate(form.taxType === 'EXEMPT' ? 0 : sgst)}</p>
            </div>
            <div>
              <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">IGST</p>
              <p className="mt-0.5 font-semibold text-slate-700">{rate(gst)}</p>
            </div>
          </div>
          <Field label="Description" error={formErrors.description}>
            <Textarea
              value={form.description}
              onChange={(event) => setForm({ ...form, description: event.target.value })}
              placeholder="Optional notes about this tax slab"
              rows={2}
            />
          </Field>
          <Field label="Status">
            <Select
              value={form.status}
              onChange={(event) => setForm({ ...form, status: event.target.value as FormState['status'] })}
            >
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
            </Select>
          </Field>
        </div>
      </Modal>

      <ConfirmDialog
        open={Boolean(deleting)}
        onClose={() => setDeleting(null)}
        onConfirm={confirmDelete}
        title="Delete tax"
        message={`Delete "${deleting?.taxName ?? ''}"? Taxes used by items or transactions cannot be deleted.`}
        confirmLabel="Delete tax"
        tone="danger"
        loading={deleteLoading}
      />
    </div>
  )
}
