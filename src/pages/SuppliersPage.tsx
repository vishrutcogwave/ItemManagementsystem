import { History, Mail, Pencil, Phone, Plus, Power, Truck } from 'lucide-react'
import { useEffect, useState } from 'react'
import {
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
  StatusBadge,
  Textarea,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { formatDate, money } from '../lib/format'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type { PageResponse, Supplier, SupplierPurchase } from '../types'

interface FormState {
  supplierName: string
  mobile: string
  address: string
  state: string
  gstNumber: string
  email: string
  status: 'ACTIVE' | 'INACTIVE'
}

const emptyForm: FormState = {
  supplierName: '',
  mobile: '',
  address: '',
  state: '',
  gstNumber: '',
  email: '',
  status: 'ACTIVE',
}

export function SuppliersPage() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const canManage = hasPermission('SUPPLIER_MANAGE')

  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<Supplier>>(
    () =>
      api
        .get('/suppliers', {
          params: { search: debouncedSearch || undefined, status: status || undefined, page, size },
        })
        .then((response) => response.data),
    [debouncedSearch, status, page, size],
  )

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Supplier | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm)
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const [historySupplier, setHistorySupplier] = useState<Supplier | null>(null)
  const [history, setHistory] = useState<SupplierPurchase[] | null>(null)

  useEffect(() => {
    if (!historySupplier) return
    let active = true
    setHistory(null)
    api
      .get<SupplierPurchase[]>(`/suppliers/${historySupplier.id}/purchases`)
      .then((response) => {
        if (active) setHistory(response.data)
      })
      .catch(() => {
        if (active) setHistory([])
      })
    return () => {
      active = false
    }
  }, [historySupplier])

  const openCreate = () => {
    setEditing(null)
    setForm(emptyForm)
    setFormErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const openEdit = (supplier: Supplier) => {
    setEditing(supplier)
    setForm({
      supplierName: supplier.supplierName,
      mobile: supplier.mobile,
      address: supplier.address,
      state: supplier.state ?? '',
      gstNumber: supplier.gstNumber ?? '',
      email: supplier.email ?? '',
      status: supplier.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    })
    setFormErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!form.supplierName.trim()) nextErrors.supplierName = 'Supplier name is required'
    if (!form.mobile.trim()) nextErrors.mobile = 'Mobile number is required'
    if (!form.address.trim()) nextErrors.address = 'Address is required'
    if (Object.keys(nextErrors).length > 0) {
      setFormErrors(nextErrors)
      return
    }

    setSaving(true)
    setFormError(null)
    setFormErrors({})
    try {
      const payload = {
        supplierName: form.supplierName.trim(),
        mobile: form.mobile.trim(),
        address: form.address.trim(),
        state: form.state.trim() || undefined,
        gstNumber: form.gstNumber.trim() || undefined,
        email: form.email.trim() || undefined,
        status: form.status,
      }
      if (editing) {
        await api.put(`/suppliers/${editing.id}`, payload)
        notify('Supplier updated')
      } else {
        await api.post('/suppliers', payload)
        notify('Supplier created')
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

  const toggleStatus = async (supplier: Supplier) => {
    try {
      await api.put(`/suppliers/${supplier.id}/status`, {
        status: supplier.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      })
      notify(supplier.status === 'ACTIVE' ? 'Supplier deactivated' : 'Supplier activated')
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    }
  }

  const columns: Column<Supplier>[] = [
    {
      key: 'supplier',
      header: 'Supplier',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-sky-50 text-sky-600">
            <Truck className="h-4 w-4" />
          </span>
          <div>
            <p className="font-semibold text-slate-800">{row.supplierName}</p>
            <p className="max-w-56 truncate text-[11px] text-slate-400">{row.address}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'contact',
      header: 'Contact',
      render: (row) => (
        <div className="space-y-0.5 text-xs text-slate-600">
          <p className="flex items-center gap-1.5">
            <Phone className="h-3 w-3 text-slate-400" /> {row.mobile}
          </p>
          {row.email && (
            <p className="flex items-center gap-1.5">
              <Mail className="h-3 w-3 text-slate-400" /> {row.email}
            </p>
          )}
        </div>
      ),
    },
    { key: 'gst', header: 'GSTIN', render: (row) => <span className="text-xs text-slate-500">{row.gstNumber || '—'}</span> },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    { key: 'created', header: 'Added', render: (row) => <span className="text-slate-500">{formatDate(row.createdAt)}</span> },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (row) => (
        <div className="flex items-center justify-end gap-1">
          <button
            onClick={() => setHistorySupplier(row)}
            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="Purchase history"
          >
            <History className="h-3.5 w-3.5" />
          </button>
          {canManage && (
            <>
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
            </>
          )}
        </div>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="Suppliers"
        subtitle="Vendors you buy stock from, with purchase history in one place."
        actions={
          canManage ? (
            <Button icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
              Add supplier
            </Button>
          ) : undefined
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center">
          <SearchInput
            className="sm:w-80"
            placeholder="Search supplier, mobile or GSTIN…"
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
                  icon={<Truck className="h-6 w-6" />}
                  title="No suppliers yet"
                  message="Add suppliers to record purchases and track what you pay them."
                  action={canManage ? <Button size="sm" onClick={openCreate}>Add supplier</Button> : undefined}
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
                itemLabel="suppliers"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit supplier' : 'Add supplier'}
        size="lg"
        footer={
          <>
            <Button variant="outline" onClick={() => setModalOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              {editing ? 'Save changes' : 'Create supplier'}
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
            <Field label="Supplier name" required error={formErrors.supplierName}>
              <Input
                value={form.supplierName}
                onChange={(event) => setForm({ ...form, supplierName: event.target.value })}
                placeholder="Business or vendor name"
                autoFocus
              />
            </Field>
            <Field label="Mobile number" required error={formErrors.mobile}>
              <Input
                value={form.mobile}
                onChange={(event) => setForm({ ...form, mobile: event.target.value })}
                placeholder="9876543210"
              />
            </Field>
            <Field label="Email" error={formErrors.email}>
              <Input
                type="email"
                value={form.email}
                onChange={(event) => setForm({ ...form, email: event.target.value })}
                placeholder="accounts@supplier.com"
              />
            </Field>
            <Field label="GSTIN" error={formErrors.gstNumber}>
              <Input
                value={form.gstNumber}
                onChange={(event) => setForm({ ...form, gstNumber: event.target.value.toUpperCase() })}
                placeholder="22AAAAA0000A1Z5"
                className="uppercase"
              />
            </Field>
            <Field label="State" error={formErrors.state} hint="Used for inter-state GST (IGST)">
              <Input
                value={form.state}
                onChange={(event) => setForm({ ...form, state: event.target.value })}
                placeholder="e.g. Karnataka"
              />
            </Field>
          </div>
          <Field label="Address" required error={formErrors.address}>
            <Textarea
              value={form.address}
              onChange={(event) => setForm({ ...form, address: event.target.value })}
              placeholder="Street, area, city, state, PIN"
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

      <Modal
        open={Boolean(historySupplier)}
        onClose={() => setHistorySupplier(null)}
        title={historySupplier ? `${historySupplier.supplierName} · purchase history` : 'Purchase history'}
        size="lg"
      >
        {!history ? (
          <LoadingState />
        ) : history.length === 0 ? (
          <EmptyState title="No purchases yet" message="Purchases from this supplier will appear here." />
        ) : (
          <div className="overflow-hidden rounded-lg border border-slate-200">
            <table className="w-full">
              <thead className="bg-slate-50">
                <tr>
                  <th className="th">Purchase</th>
                  <th className="th">Date</th>
                  <th className="th">Branch</th>
                  <th className="th text-right">Amount</th>
                  <th className="th">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {history.map((entry) => (
                  <tr key={entry.id}>
                    <td className="td font-semibold">{entry.purchaseNumber}</td>
                    <td className="td text-slate-500">{formatDate(entry.purchaseDate)}</td>
                    <td className="td">{entry.branchName}</td>
                    <td className="td text-right font-semibold">{money(entry.grandTotal)}</td>
                    <td className="td">
                      <StatusBadge status={entry.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Modal>
    </div>
  )
}
