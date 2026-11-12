import { Pencil, Plus, Power, Ruler, Trash2 } from 'lucide-react'
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
import type { PageResponse, UnitMaster, UnitMasterRequest } from '../types'

interface FormState {
  unitCode: string
  unitName: string
  description: string
  status: 'ACTIVE' | 'INACTIVE'
}

const emptyForm: FormState = {
  unitCode: '',
  unitName: '',
  description: '',
  status: 'ACTIVE',
}

export function UnitMasterPage() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const canManage = hasPermission('UNIT_MANAGE')

  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<UnitMaster>>(
    () =>
      api
        .get('/units', {
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
  const [editing, setEditing] = useState<UnitMaster | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm)
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState<UnitMaster | null>(null)
  const [deleteLoading, setDeleteLoading] = useState(false)

  const openCreate = () => {
    setEditing(null)
    setForm(emptyForm)
    setFormErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const openEdit = (unit: UnitMaster) => {
    setEditing(unit)
    setForm({
      unitCode: unit.unitCode,
      unitName: unit.unitName,
      description: unit.description ?? '',
      status: unit.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    })
    setFormErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!form.unitCode.trim()) nextErrors.unitCode = 'Unit code is required'
    else if (!/^[A-Za-z0-9._-]{1,20}$/.test(form.unitCode.trim())) {
      nextErrors.unitCode = 'Use 1-20 letters, numbers, dot, underscore or hyphen'
    }
    if (!form.unitName.trim()) nextErrors.unitName = 'Unit name is required'
    if (Object.keys(nextErrors).length > 0) {
      setFormErrors(nextErrors)
      return
    }
    setSaving(true)
    setFormError(null)
    setFormErrors({})
    try {
      const payload: UnitMasterRequest = {
        unitCode: form.unitCode.trim(),
        unitName: form.unitName.trim(),
        description: form.description.trim() || undefined,
        status: form.status,
      }
      if (editing) {
        await api.put(`/units/${editing.id}`, payload)
        notify('Unit updated. Existing items keep their unit.')
      } else {
        await api.post('/units', payload)
        notify('Unit created')
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

  const toggleStatus = async (unit: UnitMaster) => {
    try {
      await api.put(`/units/${unit.id}/status`, {
        status: unit.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      })
      notify(unit.status === 'ACTIVE' ? 'Unit deactivated' : 'Unit activated')
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    }
  }

  const confirmDelete = async () => {
    if (!deleting) return
    setDeleteLoading(true)
    try {
      await api.delete(`/units/${deleting.id}`)
      notify('Unit deleted')
      setDeleting(null)
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setDeleteLoading(false)
    }
  }

  const columns: Column<UnitMaster>[] = [
    {
      key: 'unit',
      header: 'Unit',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
            <Ruler className="h-4 w-4" />
          </span>
          <div>
            <p className="font-semibold text-slate-800">
              {row.unitCode}
              <span className="ml-2 text-xs font-medium text-slate-500">{row.unitName}</span>
            </p>
            <p className="max-w-56 truncate text-[11px] text-slate-400">{row.description || 'No description'}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'items',
      header: 'Items',
      align: 'right',
      render: (row) => <Badge tone={row.itemCount > 0 ? 'green' : 'slate'}>{row.itemCount}</Badge>,
    },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    { key: 'created', header: 'Created', render: (row) => <span className="text-slate-500">{formatDate(row.createdAt)}</span> },
    ...(canManage
      ? [
          {
            key: 'actions',
            header: '',
            align: 'right' as const,
            render: (row: UnitMaster) => (
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
                  title={row.itemCount > 0 ? 'Reassign items before deleting this unit' : 'Delete'}
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
        title="Unit Master"
        subtitle="Store-specific unit codes used by items, purchases, sales and stock transfers."
        actions={
          canManage ? (
            <Button icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
              Add unit
            </Button>
          ) : undefined
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center">
          <SearchInput
            className="sm:w-80"
            placeholder="Search unit code or name…"
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
                  icon={<Ruler className="h-6 w-6" />}
                  title="No units configured"
                  message="Add unit codes such as PCS, KG or BOX. Units are used by items and every stock transaction."
                  action={canManage ? <Button size="sm" onClick={openCreate}>Add unit</Button> : undefined}
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
                itemLabel="units"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit unit' : 'Add unit'}
        description="Changing a unit code is blocked while items or transactions use it."
        footer={
          <>
            <Button variant="outline" onClick={() => setModalOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              {editing ? 'Save changes' : 'Create unit'}
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
            <Field label="Unit code" required error={formErrors.unitCode} hint="Stored on every item and transaction">
              <Input
                value={form.unitCode}
                onChange={(event) => setForm({ ...form, unitCode: event.target.value.toUpperCase() })}
                placeholder="e.g. PCS"
                maxLength={20}
                autoFocus
              />
            </Field>
            <Field label="Unit name" required error={formErrors.unitName}>
              <Input
                value={form.unitName}
                onChange={(event) => setForm({ ...form, unitName: event.target.value })}
                placeholder="e.g. Pieces"
                maxLength={80}
              />
            </Field>
          </div>
          <Field label="Description" error={formErrors.description}>
            <Textarea
              value={form.description}
              onChange={(event) => setForm({ ...form, description: event.target.value })}
              placeholder="Optional notes about this unit"
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
        title="Delete unit"
        message={`Delete "${deleting?.unitCode ?? ''}"? Units used by items or transactions cannot be deleted.`}
        confirmLabel="Delete unit"
        tone="danger"
        loading={deleteLoading}
      />
    </div>
  )
}
