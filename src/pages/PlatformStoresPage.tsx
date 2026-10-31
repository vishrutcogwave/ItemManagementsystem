import {
  Building2,
  CalendarDays,
  CheckCircle2,
  Eye,
  History,
  Mail,
  MapPin,
  Pencil,
  Phone,
  Plus,
  Power,
  Store as StoreIcon,
  Users,
} from 'lucide-react'
import { useMemo, useState } from 'react'
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
  StatCard,
  StatusBadge,
  Textarea,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { formatDate, formatDateTime } from '../lib/format'
import { notify, notifyError } from '../store/ui'
import type {
  PageResponse,
  PlatformContact,
  RegisterStoreRequest,
  Store,
  SubscriptionHistoryEntry,
  SubscriptionSummary,
} from '../types'

interface FormState {
  storeCode: string
  storeName: string
  ownerName: string
  email: string
  mobile: string
  address: string
  gstNumber: string
  branchName: string
  username: string
  password: string
}

const empty: FormState = {
  storeCode: '',
  storeName: '',
  ownerName: '',
  email: '',
  mobile: '',
  address: '',
  gstNumber: '',
  branchName: '',
  username: '',
  password: '',
}

interface SubscriptionFormState {
  startDate: string
  endDate: string
  maxBranches: string
  note: string
}

const emptySubscription: SubscriptionFormState = {
  startDate: '',
  endDate: '',
  maxBranches: '',
  note: '',
}

export function PlatformStoresPage() {
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<Store>>(
    () =>
      api
        .get('/stores', {
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

  const { data: overview, reload: reloadOverview } = useApi<PageResponse<Store>>(
    () => api.get('/stores', { params: { size: 100 } }).then((response) => response.data),
    [],
  )

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Store | null>(null)
  const [form, setForm] = useState<FormState>(empty)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const [viewing, setViewing] = useState<Store | null>(null)
  const [statusTarget, setStatusTarget] = useState<Store | null>(null)
  const [statusSaving, setStatusSaving] = useState(false)

  const [subTarget, setSubTarget] = useState<Store | null>(null)
  const [subSummary, setSubSummary] = useState<SubscriptionSummary | null>(null)
  const [subHistory, setSubHistory] = useState<SubscriptionHistoryEntry[]>([])
  const [subForm, setSubForm] = useState<SubscriptionFormState>(emptySubscription)
  const [subErrors, setSubErrors] = useState<Record<string, string>>({})
  const [subError, setSubError] = useState<string | null>(null)
  const [subLoading, setSubLoading] = useState(false)
  const [subSaving, setSubSaving] = useState(false)

  const [contactOpen, setContactOpen] = useState(false)
  const [contactForm, setContactForm] = useState({ contactEmail: '', contactPhone: '' })
  const [contactErrors, setContactErrors] = useState<Record<string, string>>({})
  const [contactError, setContactError] = useState<string | null>(null)
  const [contactLoading, setContactLoading] = useState(false)
  const [contactSaving, setContactSaving] = useState(false)

  const refresh = () => {
    reload()
    reloadOverview()
  }

  const stats = useMemo(() => {
    const stores = overview?.content ?? []
    return {
      total: overview?.totalElements ?? 0,
      active: stores.filter((store) => store.status === 'ACTIVE').length,
      branches: stores.reduce((sum, store) => sum + store.branchCount, 0),
      users: stores.reduce((sum, store) => sum + store.userCount, 0),
    }
  }, [overview])

  const openCreate = () => {
    setEditing(null)
    setForm(empty)
    setErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const openEdit = (store: Store) => {
    setViewing(null)
    setEditing(store)
    setForm({
      ...empty,
      storeCode: store.storeCode,
      storeName: store.storeName,
      ownerName: store.ownerName,
      email: store.email,
      mobile: store.mobile,
      address: store.address,
      gstNumber: store.gstNumber ?? '',
    })
    setErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!editing && !/^[A-Za-z0-9_-]{3,30}$/.test(form.storeCode.trim())) {
      nextErrors.storeCode = 'Use 3-30 letters, numbers, hyphen or underscore'
    }
    if (!form.storeName.trim()) nextErrors.storeName = 'Store name is required'
    if (!form.ownerName.trim()) nextErrors.ownerName = 'Owner name is required'
    if (!form.email.trim()) nextErrors.email = 'Email is required'
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) nextErrors.email = 'Enter a valid email'
    if (!form.mobile.trim()) nextErrors.mobile = 'Mobile number is required'
    if (!form.address.trim()) nextErrors.address = 'Address is required'
    if (!editing) {
      if (!/^[A-Za-z0-9._-]{3,40}$/.test(form.username.trim())) {
        nextErrors.username = 'Use 3-40 letters, numbers, dot, underscore or hyphen'
      }
      if (form.password.length < 8) nextErrors.password = 'Password must be at least 8 characters'
    }
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors)
      return
    }

    setSaving(true)
    setFormError(null)
    setErrors({})
    try {
      const profile = {
        storeName: form.storeName.trim(),
        ownerName: form.ownerName.trim(),
        email: form.email.trim(),
        mobile: form.mobile.trim(),
        address: form.address.trim(),
        gstNumber: form.gstNumber.trim() || undefined,
      }
      if (editing) {
        await api.put(`/stores/${editing.id}`, profile)
        notify('Store updated')
      } else {
        const payload: RegisterStoreRequest = {
          ...profile,
          storeCode: form.storeCode.trim().toUpperCase(),
          username: form.username.trim(),
          password: form.password,
          branchName: form.branchName.trim() || undefined,
        }
        await api.post('/stores', payload)
        notify('Store onboarded with an owner account')
      }
      setModalOpen(false)
      refresh()
    } catch (err) {
      setFormError(errorMessage(err))
      setErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  const confirmStatus = async () => {
    if (!statusTarget) return
    setStatusSaving(true)
    try {
      const next = statusTarget.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
      await api.put(`/stores/${statusTarget.id}/status`, { status: next })
      notify(next === 'ACTIVE' ? 'Store activated' : 'Store deactivated')
      setStatusTarget(null)
      setViewing(null)
      refresh()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setStatusSaving(false)
    }
  }

  const loadSubscription = async (store: Store) => {
    const [summary, history] = await Promise.all([
      api.get<SubscriptionSummary>(`/stores/${store.id}/subscription`),
      api.get<SubscriptionHistoryEntry[]>(`/stores/${store.id}/subscription/history`),
    ])
    setSubSummary(summary.data)
    setSubHistory(history.data)
    setSubForm({
      startDate: summary.data.startDate,
      endDate: summary.data.endDate,
      maxBranches: String(summary.data.maxBranches),
      note: '',
    })
  }

  const openSubscription = async (store: Store) => {
    setViewing(null)
    setSubTarget(store)
    setSubSummary(null)
    setSubHistory([])
    setSubForm({
      startDate: store.subscriptionStartDate,
      endDate: store.subscriptionEndDate,
      maxBranches: String(store.maxBranches),
      note: '',
    })
    setSubErrors({})
    setSubError(null)
    setSubLoading(true)
    try {
      await loadSubscription(store)
    } catch (err) {
      setSubError(errorMessage(err))
    } finally {
      setSubLoading(false)
    }
  }

  const saveSubscription = async () => {
    if (!subTarget) return
    const nextErrors: Record<string, string> = {}
    if (!subForm.startDate) nextErrors.startDate = 'Start date is required'
    if (!subForm.endDate) nextErrors.endDate = 'End date is required'
    if (subForm.startDate && subForm.endDate && subForm.endDate < subForm.startDate) {
      nextErrors.endDate = 'End date cannot be before the start date'
    }
    const limit = Number(subForm.maxBranches)
    if (!subForm.maxBranches.trim() || !Number.isInteger(limit) || limit < 1) {
      nextErrors.maxBranches = 'Branch limit must be a whole number of at least 1'
    }
    if (Object.keys(nextErrors).length > 0) {
      setSubErrors(nextErrors)
      return
    }

    setSubSaving(true)
    setSubError(null)
    setSubErrors({})
    try {
      await api.put(`/stores/${subTarget.id}/subscription`, {
        startDate: subForm.startDate,
        endDate: subForm.endDate,
        maxBranches: limit,
        note: subForm.note.trim() || undefined,
      })
      notify('Subscription updated')
      await loadSubscription(subTarget)
      refresh()
    } catch (err) {
      setSubError(errorMessage(err))
      setSubErrors(fieldErrorsOf(err))
    } finally {
      setSubSaving(false)
    }
  }

  const openContact = async () => {
    setContactOpen(true)
    setContactErrors({})
    setContactError(null)
    setContactLoading(true)
    try {
      const { data } = await api.get<PlatformContact>('/platform/contact')
      setContactForm({ contactEmail: data.contactEmail, contactPhone: data.contactPhone })
    } catch (err) {
      setContactError(errorMessage(err))
    } finally {
      setContactLoading(false)
    }
  }

  const saveContact = async () => {
    const nextErrors: Record<string, string> = {}
    if (!contactForm.contactEmail.trim()) nextErrors.contactEmail = 'Contact email is required'
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contactForm.contactEmail.trim())) {
      nextErrors.contactEmail = 'Enter a valid email'
    }
    if (!contactForm.contactPhone.trim()) nextErrors.contactPhone = 'Contact phone is required'
    if (Object.keys(nextErrors).length > 0) {
      setContactErrors(nextErrors)
      return
    }
    setContactSaving(true)
    setContactError(null)
    setContactErrors({})
    try {
      await api.put('/platform/contact', {
        contactEmail: contactForm.contactEmail.trim(),
        contactPhone: contactForm.contactPhone.trim(),
      })
      notify('Platform contact updated')
      setContactOpen(false)
    } catch (err) {
      setContactError(errorMessage(err))
      setContactErrors(fieldErrorsOf(err))
    } finally {
      setContactSaving(false)
    }
  }

  const columns: Column<Store>[] = [
    {
      key: 'store',
      header: 'Store',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
            <StoreIcon className="h-4.5 w-4.5" />
          </span>
          <div>
            <p className="font-semibold text-slate-800">{row.storeName}</p>
            <p className="text-[11px] tracking-wide text-slate-400">{row.storeCode}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'owner',
      header: 'Owner',
      render: (row) => (
        <div className="text-xs">
          <p className="font-medium text-slate-700">{row.ownerName}</p>
          <p className="text-slate-400">{row.email}</p>
        </div>
      ),
    },
    {
      key: 'contact',
      header: 'Mobile',
      render: (row) => <span className="text-xs text-slate-600">{row.mobile}</span>,
    },
    {
      key: 'footprint',
      header: 'Footprint',
      render: (row) => (
        <div className="flex items-center gap-1.5">
          <Badge tone="blue">
            <Building2 className="h-3 w-3" /> {row.branchCount}
          </Badge>
          <Badge tone="purple">
            <Users className="h-3 w-3" /> {row.userCount}
          </Badge>
        </div>
      ),
    },
    {
      key: 'subscription',
      header: 'Subscription',
      render: (row) => (
        <div className="text-xs">
          <p className={row.subscriptionExpired ? 'font-semibold text-rose-600' : 'font-medium text-slate-700'}>
            {formatDate(row.subscriptionEndDate)}
          </p>
          <p className="mt-0.5 flex items-center gap-1 text-[11px] text-slate-400">
            {row.subscriptionExpired && <Badge tone="red">Expired</Badge>}
            {row.branchCount} / {row.maxBranches} branches · {row.remainingBranches} left
          </p>
        </div>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    { key: 'created', header: 'Onboarded', render: (row) => <span className="text-slate-500">{formatDate(row.createdAt)}</span> },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (row) => (
        <div className="flex items-center justify-end gap-1">
          <button
            onClick={() => openSubscription(row)}
            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="Manage subscription"
          >
            <CalendarDays className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={() => setViewing(row)}
            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="View store"
          >
            <Eye className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={() => openEdit(row)}
            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="Edit store"
          >
            <Pencil className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={() => setStatusTarget(row)}
            className={`flex h-7 w-7 items-center justify-center rounded-md transition hover:bg-slate-100 ${
              row.status === 'ACTIVE' ? 'text-amber-500' : 'text-emerald-500'
            }`}
            title={row.status === 'ACTIVE' ? 'Deactivate store' : 'Activate store'}
          >
            <Power className="h-3.5 w-3.5" />
          </button>
        </div>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        eyebrow="Platform console"
        title="Registered stores"
        subtitle="Onboard new businesses and monitor store footprints across the platform."
        actions={
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" icon={<Phone className="h-4 w-4" />} onClick={openContact}>
              Platform contact
            </Button>
            <Button icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
              Onboard store
            </Button>
          </div>
        }
      />

      <div className="mb-4 grid grid-cols-2 gap-3 xl:grid-cols-4">
        <StatCard label="Stores" value={stats.total} hint="Registered on platform" icon={<StoreIcon className="h-5 w-5" />} />
        <StatCard
          label="Active stores"
          value={stats.active}
          hint={`${stats.total - stats.active} inactive`}
          icon={<CheckCircle2 className="h-5 w-5" />}
          tone="green"
        />
        <StatCard label="Branches" value={stats.branches} hint="Across all stores" icon={<Building2 className="h-5 w-5" />} tone="blue" />
        <StatCard label="Users" value={stats.users} hint="Store team members" icon={<Users className="h-5 w-5" />} tone="purple" />
      </div>

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
            <SearchInput
              className="sm:w-72"
              placeholder="Search store, owner or code…"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value)
                setPage(0)
              }}
            />
            <Select
              className="sm:w-40"
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
          <Badge tone="slate">{data?.totalElements ?? 0} stores</Badge>
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
                  icon={<StoreIcon className="h-6 w-6" />}
                  title="No stores registered"
                  message="Onboard a store to give a business its own isolated workspace, branches and users."
                  action={
                    <Button size="sm" icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
                      Onboard store
                    </Button>
                  }
                />
              }
            />
            {data && data.totalElements > 0 && (
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                totalElements={data.totalElements}
                size={data.size}
                onPageChange={setPage}
                onSizeChange={(next) => {
                  setSize(next)
                  setPage(0)
                }}
                itemLabel="stores"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit store' : 'Onboard a new store'}
        description={
          editing
            ? 'Update the business profile used on receipts and reports.'
            : 'Creates an isolated store workspace with a Main Branch and owner login.'
        }
        size="lg"
        footer={
          <>
            <Button variant="outline" onClick={() => setModalOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              {editing ? 'Save changes' : 'Onboard store'}
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
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="Store code" required={!editing} error={errors.storeCode} hint={editing ? 'Code cannot be changed' : 'Used at sign-in'}>
              <Input
                value={form.storeCode}
                onChange={(event) => setForm({ ...form, storeCode: event.target.value.toUpperCase() })}
                disabled={Boolean(editing)}
                className="uppercase"
                placeholder="ABC001"
              />
            </Field>
            <Field label="Store name" required error={errors.storeName}>
              <Input value={form.storeName} onChange={(event) => setForm({ ...form, storeName: event.target.value })} placeholder="Sunrise Supermarket" />
            </Field>
            <Field label="Owner name" required error={errors.ownerName}>
              <Input value={form.ownerName} onChange={(event) => setForm({ ...form, ownerName: event.target.value })} placeholder="Full name" />
            </Field>
            <Field label="Owner email" required error={errors.email}>
              <Input type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} placeholder="owner@business.com" />
            </Field>
            <Field label="Mobile number" required error={errors.mobile}>
              <Input value={form.mobile} onChange={(event) => setForm({ ...form, mobile: event.target.value })} placeholder="9876543210" />
            </Field>
            <Field label="GSTIN" error={errors.gstNumber} hint="Optional">
              <Input
                value={form.gstNumber}
                onChange={(event) => setForm({ ...form, gstNumber: event.target.value.toUpperCase() })}
                className="uppercase"
                placeholder="22AAAAA0000A1Z5"
              />
            </Field>
          </div>
          <Field label="Store address" required error={errors.address}>
            <Textarea value={form.address} onChange={(event) => setForm({ ...form, address: event.target.value })} rows={2} placeholder="Street, area, city, state, PIN" />
          </Field>

          {!editing && (
            <div className="rounded-xl border border-slate-200 bg-slate-50/60 p-3.5">
              <p className="mb-3 text-[11px] font-bold tracking-wider text-slate-500 uppercase">Owner login & first branch</p>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Field label="First branch name" error={errors.branchName} hint="Defaults to Main Branch">
                  <Input value={form.branchName} onChange={(event) => setForm({ ...form, branchName: event.target.value })} placeholder="Main Branch" />
                </Field>
                <Field label="Owner username" required error={errors.username}>
                  <Input
                    value={form.username}
                    onChange={(event) => setForm({ ...form, username: event.target.value })}
                    placeholder="owner"
                    autoComplete="off"
                  />
                </Field>
                <Field label="Owner password" required error={errors.password} hint="At least 8 characters">
                  <Input
                    type="password"
                    value={form.password}
                    onChange={(event) => setForm({ ...form, password: event.target.value })}
                    placeholder="••••••••"
                    autoComplete="new-password"
                  />
                </Field>
              </div>
            </div>
          )}
        </div>
      </Modal>

      <Modal
        open={Boolean(viewing)}
        onClose={() => setViewing(null)}
        title={viewing?.storeName ?? 'Store details'}
        description={viewing ? `${viewing.storeCode} · Onboarded ${formatDate(viewing.createdAt)}` : undefined}
        size="md"
        footer={
          viewing ? (
            <>
              <Button variant="ghost" onClick={() => setStatusTarget(viewing)}>
                {viewing.status === 'ACTIVE' ? 'Deactivate store' : 'Activate store'}
              </Button>
              <Button variant="outline" onClick={() => setViewing(null)}>
                Close
              </Button>
              <Button icon={<Pencil className="h-3.5 w-3.5" />} onClick={() => openEdit(viewing)}>
                Edit store
              </Button>
            </>
          ) : undefined
        }
      >
        {viewing && (
          <div className="space-y-4">
            <div className="flex items-center gap-3">
              <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-brand-50 text-brand-600">
                <StoreIcon className="h-6 w-6" />
              </span>
              <div>
                <p className="flex items-center gap-2 text-sm font-bold text-slate-800">
                  {viewing.storeName} <StatusBadge status={viewing.status} />
                  {viewing.subscriptionExpired && <Badge tone="red">Subscription expired</Badge>}
                </p>
                <p className="text-xs text-slate-500">Owner: {viewing.ownerName}</p>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-2">
              <div className="rounded-lg bg-slate-50 p-3">
                <p className="flex items-center gap-1.5 text-[11px] font-semibold text-slate-500">
                  <Building2 className="h-3.5 w-3.5" /> Branches
                </p>
                <p className="mt-1 text-xl font-bold text-slate-900">{viewing.branchCount}</p>
              </div>
              <div className="rounded-lg bg-slate-50 p-3">
                <p className="flex items-center gap-1.5 text-[11px] font-semibold text-slate-500">
                  <Users className="h-3.5 w-3.5" /> Users
                </p>
                <p className="mt-1 text-xl font-bold text-slate-900">{viewing.userCount}</p>
              </div>
              <div className="rounded-lg bg-slate-50 p-3">
                <p className="flex items-center gap-1.5 text-[11px] font-semibold text-slate-500">
                  <CalendarDays className="h-3.5 w-3.5" /> Subscription ends
                </p>
                <p className={`mt-1 text-sm font-bold ${viewing.subscriptionExpired ? 'text-rose-600' : 'text-slate-900'}`}>
                  {formatDate(viewing.subscriptionEndDate)}
                </p>
              </div>
              <div className="rounded-lg bg-slate-50 p-3">
                <p className="flex items-center gap-1.5 text-[11px] font-semibold text-slate-500">
                  <Building2 className="h-3.5 w-3.5" /> Branch limit
                </p>
                <p className="mt-1 text-sm font-bold text-slate-900">
                  {viewing.branchCount} / {viewing.maxBranches}
                </p>
              </div>
            </div>

            <div className="space-y-2 rounded-lg border border-slate-200 p-3 text-xs text-slate-600">
              <p className="flex items-start gap-2">
                <Mail className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-400" /> {viewing.email}
              </p>
              <p className="flex items-start gap-2">
                <Phone className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-400" /> {viewing.mobile}
              </p>
              <p className="flex items-start gap-2">
                <MapPin className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-400" /> {viewing.address}
              </p>
              <p className="flex items-start gap-2">
                <CalendarDays className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-400" /> {formatDate(viewing.createdAt)}
              </p>
              {viewing.gstNumber && (
                <p className="flex items-start gap-2">
                  <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-400" /> GSTIN {viewing.gstNumber}
                </p>
              )}
            </div>
          </div>
        )}
      </Modal>

      <Modal
        open={Boolean(subTarget)}
        onClose={() => setSubTarget(null)}
        title={subTarget ? `Subscription · ${subTarget.storeName}` : 'Subscription'}
        description={
          subTarget
            ? `${subTarget.storeCode} · Set the subscription period and the maximum number of branches.`
            : undefined
        }
        size="lg"
        footer={
          <>
            <Button variant="outline" onClick={() => setSubTarget(null)} disabled={subSaving}>
              Close
            </Button>
            <Button onClick={saveSubscription} loading={subSaving}>
              Save subscription
            </Button>
          </>
        }
      >
        {subLoading && !subSummary ? (
          <LoadingState />
        ) : (
          <div className="space-y-4">
            {subError && (
              <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
                {subError}
              </div>
            )}

            {subSummary && (
              <>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  <div className="rounded-lg bg-slate-50 p-3">
                    <p className="text-[11px] font-semibold text-slate-500">Ends</p>
                    <p className={`mt-1 text-sm font-bold ${subSummary.expired ? 'text-rose-600' : 'text-slate-900'}`}>
                      {formatDate(subSummary.endDate)}
                    </p>
                  </div>
                  <div className="rounded-lg bg-slate-50 p-3">
                    <p className="text-[11px] font-semibold text-slate-500">Branches used</p>
                    <p className="mt-1 text-sm font-bold text-slate-900">{subSummary.branchCount}</p>
                  </div>
                  <div className="rounded-lg bg-slate-50 p-3">
                    <p className="text-[11px] font-semibold text-slate-500">Limit</p>
                    <p className="mt-1 text-sm font-bold text-slate-900">{subSummary.maxBranches}</p>
                  </div>
                  <div className="rounded-lg bg-slate-50 p-3">
                    <p className="text-[11px] font-semibold text-slate-500">Remaining</p>
                    <p className={`mt-1 text-sm font-bold ${subSummary.remainingBranches === 0 ? 'text-rose-600' : 'text-emerald-600'}`}>
                      {subSummary.remainingBranches}
                    </p>
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  <Badge tone={subSummary.expired ? 'red' : 'green'}>
                    {subSummary.expired ? 'Expired' : 'Active'}
                  </Badge>
                  <span className="text-slate-500">
                    {subSummary.expired
                      ? 'Store users are blocked until the end date is extended.'
                      : `${subSummary.daysRemaining} days remaining in the current period.`}
                  </span>
                </div>
                {subSummary.expired && (
                  <p className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-semibold text-rose-700">
                    Your subscription has expired. Please contact the Platform Administrator to renew your
                    subscription.
                  </p>
                )}
              </>
            )}

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label="Subscription start date" required error={subErrors.startDate}>
                <Input
                  type="date"
                  value={subForm.startDate}
                  onChange={(event) => setSubForm({ ...subForm, startDate: event.target.value })}
                />
              </Field>
              <Field label="Subscription end date" required error={subErrors.endDate} hint="Extend at any time">
                <Input
                  type="date"
                  value={subForm.endDate}
                  onChange={(event) => setSubForm({ ...subForm, endDate: event.target.value })}
                />
              </Field>
              <Field
                label="Maximum branches"
                required
                error={subErrors.maxBranches}
                hint="Total branches allowed, including the Main branch"
              >
                <Input
                  type="number"
                  min={1}
                  value={subForm.maxBranches}
                  onChange={(event) => setSubForm({ ...subForm, maxBranches: event.target.value })}
                />
              </Field>
              <Field label="Change note" error={subErrors.note} hint="Saved in the change history">
                <Input
                  value={subForm.note}
                  onChange={(event) => setSubForm({ ...subForm, note: event.target.value })}
                  placeholder="Reason for this change"
                />
              </Field>
            </div>

            {subSummary && Number(subForm.maxBranches) < subSummary.branchCount && (
              <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs font-medium text-amber-800">
                This limit is below the {subSummary.branchCount} existing branches. No branches will be deleted;
                new branches will be blocked until the limit is raised.
              </p>
            )}

            <div className="rounded-xl border border-slate-200">
              <div className="flex items-center justify-between border-b border-slate-100 px-3.5 py-2.5">
                <p className="flex items-center gap-1.5 text-[11px] font-bold tracking-wider text-slate-500 uppercase">
                  <History className="h-3.5 w-3.5" /> Change history
                </p>
                <span className="text-[11px] text-slate-400">{subHistory.length} entries</span>
              </div>
              <div className="max-h-56 space-y-2 overflow-y-auto p-3">
                {subHistory.length === 0 ? (
                  <p className="px-1 py-2 text-xs text-slate-500">No subscription changes recorded yet.</p>
                ) : (
                  subHistory.map((entry) => (
                    <div key={entry.id} className="rounded-lg bg-slate-50 px-3 py-2 text-[11px]">
                      <div className="flex flex-wrap items-center justify-between gap-1">
                        <span className="font-semibold text-slate-700">
                          {entry.changedByUsername || 'Platform Administrator'}
                        </span>
                        <span className="text-slate-400">{formatDateTime(entry.changedAt)}</span>
                      </div>
                      <p className="mt-1 text-slate-600">
                        Period: {formatDate(entry.previousStartDate)} → {formatDate(entry.newStartDate)} ·{' '}
                        {formatDate(entry.previousEndDate)} → {formatDate(entry.newEndDate)}
                      </p>
                      <p className="text-slate-600">
                        Branch limit: {entry.previousMaxBranches ?? '—'} → {entry.newMaxBranches ?? '—'}
                      </p>
                      {entry.note && <p className="mt-0.5 text-slate-500 italic">“{entry.note}”</p>}
                    </div>
                  ))
                )}
              </div>
            </div>
          </div>
        )}
      </Modal>

      <Modal
        open={contactOpen}
        onClose={() => setContactOpen(false)}
        title="Platform contact"
        description="These details are shown to store users on the expired-subscription screen."
        size="md"
        footer={
          <>
            <Button variant="outline" onClick={() => setContactOpen(false)} disabled={contactSaving}>
              Cancel
            </Button>
            <Button onClick={saveContact} loading={contactSaving}>
              Save contact
            </Button>
          </>
        }
      >
        {contactLoading ? (
          <LoadingState />
        ) : (
          <div className="space-y-4">
            {contactError && (
              <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
                {contactError}
              </div>
            )}
            <Field label="Contact email" required error={contactErrors.contactEmail}>
              <Input
                type="email"
                value={contactForm.contactEmail}
                onChange={(event) => setContactForm({ ...contactForm, contactEmail: event.target.value })}
                placeholder="admin@company.com"
              />
            </Field>
            <Field label="Contact phone" required error={contactErrors.contactPhone}>
              <Input
                value={contactForm.contactPhone}
                onChange={(event) => setContactForm({ ...contactForm, contactPhone: event.target.value })}
                placeholder="9876543210"
              />
            </Field>
            <p className="rounded-lg bg-slate-50 px-3 py-2 text-[11px] text-slate-500">
              If a value is left empty, the primary platform administrator account email and mobile number are used
              automatically.
            </p>
          </div>
        )}
      </Modal>

      <ConfirmDialog
        open={Boolean(statusTarget)}
        onClose={() => setStatusTarget(null)}
        onConfirm={confirmStatus}
        title={statusTarget?.status === 'ACTIVE' ? 'Deactivate store?' : 'Activate store?'}
        message={
          statusTarget?.status === 'ACTIVE'
            ? `${statusTarget?.storeName} will be signed out and its users blocked from accessing the workspace until reactivated.`
            : `${statusTarget?.storeName} will regain access to its workspace immediately.`
        }
        confirmLabel={statusTarget?.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
        tone={statusTarget?.status === 'ACTIVE' ? 'danger' : 'primary'}
        loading={statusSaving}
      />
    </div>
  )
}
