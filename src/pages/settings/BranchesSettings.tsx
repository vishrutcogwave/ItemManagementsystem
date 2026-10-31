import { Building2, Pencil, Plus, Power } from 'lucide-react'
import { useMemo, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  Field,
  Input,
  Modal,
  SearchInput,
  StatusBadge,
  Textarea,
  type Column,
} from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { api, errorMessage, fieldErrorsOf } from '../../lib/api'
import { formatDate } from '../../lib/format'
import { useAuthStore } from '../../store/auth'
import { notify, notifyError } from '../../store/ui'
import type { Branch, SubscriptionSummary } from '../../types'

const MAX_BRANCH_MESSAGE =
  'You have reached your maximum branch limit. Please contact the Platform Administrator.'

interface FormState {
  branchCode: string
  branchName: string
  address: string
  contactNumber: string
  state: string
  status: 'ACTIVE' | 'INACTIVE'
}

const empty: FormState = {
  branchCode: '',
  branchName: '',
  address: '',
  contactNumber: '',
  state: '',
  status: 'ACTIVE',
}

export function BranchesSettings() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const canManage = hasPermission('BRANCH_MANAGE')

  const { data, loading, error, reload } = useApi<Branch[]>(
    () =>
      api
        .get(canManage ? '/branches/all' : '/branches')
        .then((response) => response.data),
    [canManage],
  )

  const { data: subscription, reload: reloadSubscription } = useApi<SubscriptionSummary>(
    () => api.get('/subscription/summary').then((response) => response.data),
    [],
  )

  const atLimit = Boolean(subscription && subscription.remainingBranches <= 0)
  const refreshAll = () => {
    reload()
    reloadSubscription()
  }

  const [search, setSearch] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Branch | null>(null)
  const [form, setForm] = useState<FormState>(empty)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return (data ?? []).filter(
      (branch) =>
        !needle ||
        branch.branchName.toLowerCase().includes(needle) ||
        branch.branchCode.toLowerCase().includes(needle) ||
        branch.address.toLowerCase().includes(needle),
    )
  }, [data, search])

  const openCreate = () => {
    setEditing(null)
    setForm(empty)
    setErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const openEdit = (branch: Branch) => {
    setEditing(branch)
    setForm({
      branchCode: branch.branchCode,
      branchName: branch.branchName,
      address: branch.address,
      contactNumber: branch.contactNumber,
      state: branch.state ?? '',
      status: branch.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    })
    setErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!form.branchCode.trim()) nextErrors.branchCode = 'Branch code is required'
    if (!form.branchName.trim()) nextErrors.branchName = 'Branch name is required'
    if (!form.address.trim()) nextErrors.address = 'Address is required'
    if (!form.contactNumber.trim()) nextErrors.contactNumber = 'Contact number is required'
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors)
      return
    }
    setSaving(true)
    setFormError(null)
    setErrors({})
    try {
      const payload = {
        branchCode: form.branchCode.trim().toUpperCase(),
        branchName: form.branchName.trim(),
        address: form.address.trim(),
        contactNumber: form.contactNumber.trim(),
        state: form.state.trim() || undefined,
        status: form.status,
      }
      if (editing) {
        await api.put(`/branches/${editing.id}`, payload)
        notify('Branch updated')
      } else {
        await api.post('/branches', payload)
        notify('Branch created')
      }
      setModalOpen(false)
      refreshAll()
    } catch (err) {
      setFormError(errorMessage(err))
      setErrors(fieldErrorsOf(err))
      reloadSubscription()
    } finally {
      setSaving(false)
    }
  }

  const toggleStatus = async (branch: Branch) => {
    try {
      await api.put(`/branches/${branch.id}/status`, {
        status: branch.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      })
      notify(branch.status === 'ACTIVE' ? 'Branch deactivated' : 'Branch activated')
      refreshAll()
    } catch (err) {
      notifyError(errorMessage(err))
    }
  }

  const columns: Column<Branch>[] = [
    {
      key: 'branch',
      header: 'Branch',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
            <Building2 className="h-4 w-4" />
          </span>
          <div>
            <p className="font-semibold text-slate-800">{row.branchName}</p>
            <p className="text-[11px] text-slate-400">{row.branchCode}</p>
          </div>
        </div>
      ),
    },
    { key: 'contact', header: 'Contact', render: (row) => <span className="text-xs text-slate-600">{row.contactNumber}</span> },
    { key: 'address', header: 'Address', render: (row) => <span className="max-w-72 truncate text-xs text-slate-500">{row.address}</span> },
    { key: 'state', header: 'State', render: (row) => <span className="text-xs text-slate-600">{row.state || '—'}</span> },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    { key: 'created', header: 'Created', render: (row) => <span className="text-slate-500">{formatDate(row.createdAt)}</span> },
    ...(canManage
      ? [
          {
            key: 'actions',
            header: '',
            align: 'right' as const,
            render: (row: Branch) => (
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
                    row.status === 'ACTIVE' ? 'text-amber-500' : 'text-emerald-500'
                  }`}
                  title={row.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                >
                  <Power className="h-3.5 w-3.5" />
                </button>
              </div>
            ),
          },
        ]
      : []),
  ]

  return (
    <div>
      {subscription && (
        <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <div className="rounded-xl border border-slate-200 bg-white px-4 py-3">
            <p className="text-[11px] font-semibold text-slate-500">Maximum branches</p>
            <p className="mt-1 text-xl font-bold text-slate-900">{subscription.maxBranches}</p>
          </div>
          <div className="rounded-xl border border-slate-200 bg-white px-4 py-3">
            <p className="text-[11px] font-semibold text-slate-500">Existing branches</p>
            <p className="mt-1 text-xl font-bold text-slate-900">{subscription.branchCount}</p>
          </div>
          <div className="rounded-xl border border-slate-200 bg-white px-4 py-3">
            <p className="text-[11px] font-semibold text-slate-500">Remaining branches</p>
            <p className={`mt-1 text-xl font-bold ${atLimit ? 'text-rose-600' : 'text-emerald-600'}`}>
              {subscription.remainingBranches}
            </p>
          </div>
          <div className="rounded-xl border border-slate-200 bg-white px-4 py-3">
            <p className="text-[11px] font-semibold text-slate-500">Subscription ends</p>
            <p className={`mt-1 text-sm font-bold ${subscription.expired ? 'text-rose-600' : 'text-slate-900'}`}>
              {formatDate(subscription.endDate)}
            </p>
          </div>
        </div>
      )}

      {atLimit && (
        <div
          role="alert"
          className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-xs font-semibold text-rose-700"
        >
          {MAX_BRANCH_MESSAGE}
        </div>
      )}

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-3">
            <SearchInput className="sm:w-72" placeholder="Search branches…" value={search} onChange={(event) => setSearch(event.target.value)} />
            <Badge tone="slate">{rows.length} branches</Badge>
            {subscription && (
              <Badge tone={atLimit ? 'red' : 'blue'}>
                {subscription.branchCount} / {subscription.maxBranches} allowed
              </Badge>
            )}
          </div>
          {canManage && (
            <Button
              size="sm"
              icon={<Plus className="h-4 w-4" />}
              onClick={openCreate}
              disabled={atLimit}
              title={atLimit ? MAX_BRANCH_MESSAGE : undefined}
            >
              Add branch
            </Button>
          )}
        </div>
        {error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(row) => row.id}
            loading={loading}
            empty={<EmptyState icon={<Building2 className="h-6 w-6" />} title="No branches" message="Create a branch to start selling and stocking inventory." />}
          />
        )}
      </Card>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit branch' : 'Add branch'}
        footer={
          <>
            <Button variant="outline" onClick={() => setModalOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              {editing ? 'Save changes' : 'Create branch'}
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
          <div className="grid grid-cols-2 gap-3">
            <Field label="Branch code" required error={errors.branchCode}>
              <Input
                value={form.branchCode}
                onChange={(event) => setForm({ ...form, branchCode: event.target.value.toUpperCase() })}
                className="uppercase"
                disabled={Boolean(editing)}
                placeholder="BLR"
              />
            </Field>
            <Field label="Branch name" required error={errors.branchName}>
              <Input value={form.branchName} onChange={(event) => setForm({ ...form, branchName: event.target.value })} placeholder="Bangalore" />
            </Field>
          </div>
          <Field label="Address" required error={errors.address}>
            <Textarea value={form.address} onChange={(event) => setForm({ ...form, address: event.target.value })} rows={2} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Contact number" required error={errors.contactNumber}>
              <Input value={form.contactNumber} onChange={(event) => setForm({ ...form, contactNumber: event.target.value })} />
            </Field>
            <Field label="State" error={errors.state} hint="Place of supply for GST">
              <Input
                value={form.state}
                onChange={(event) => setForm({ ...form, state: event.target.value })}
                placeholder="e.g. Karnataka"
              />
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Status">
              <select
                className="field-input"
                value={form.status}
                onChange={(event) => setForm({ ...form, status: event.target.value as FormState['status'] })}
              >
                <option value="ACTIVE">Active</option>
                <option value="INACTIVE">Inactive</option>
              </select>
            </Field>
          </div>
        </div>
      </Modal>
    </div>
  )
}
