import { Building2, CalendarDays, Mail, MapPin, Phone, Save, Store as StoreIcon, Users } from 'lucide-react'
import { useEffect, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  ErrorState,
  Field,
  Input,
  LoadingState,
  StatusBadge,
  Textarea,
  Toggle,
} from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { api, errorMessage, fieldErrorsOf } from '../../lib/api'
import { formatDate } from '../../lib/format'
import { useAuthStore } from '../../store/auth'
import { notify } from '../../store/ui'
import type { Store, SubscriptionSummary } from '../../types'

interface FormState {
  storeName: string
  ownerName: string
  email: string
  mobile: string
  address: string
  gstNumber: string
  taxInclusive: boolean
}

const empty: FormState = {
  storeName: '',
  ownerName: '',
  email: '',
  mobile: '',
  address: '',
  gstNumber: '',
  taxInclusive: false,
}

export function StoreSettings() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const canManage = hasPermission('STORE_MANAGE')

  const { data, loading, error, reload } = useApi<Store>(
    () => api.get('/stores/current').then((response) => response.data),
    [],
  )

  const { data: subscription } = useApi<SubscriptionSummary>(
    () => api.get('/subscription/summary').then((response) => response.data),
    [],
  )

  const [form, setForm] = useState<FormState>(empty)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!data) return
    setForm({
      storeName: data.storeName,
      ownerName: data.ownerName,
      email: data.email,
      mobile: data.mobile,
      address: data.address,
      gstNumber: data.gstNumber ?? '',
      taxInclusive: data.taxInclusive,
    })
  }, [data])

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!form.storeName.trim()) nextErrors.storeName = 'Store name is required'
    if (!form.ownerName.trim()) nextErrors.ownerName = 'Owner name is required'
    if (!form.email.trim()) nextErrors.email = 'Email is required'
    if (!form.mobile.trim()) nextErrors.mobile = 'Mobile number is required'
    if (!form.address.trim()) nextErrors.address = 'Address is required'
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors)
      return
    }
    setSaving(true)
    setFormError(null)
    setErrors({})
    try {
      await api.put('/stores/current', {
        storeName: form.storeName,
        ownerName: form.ownerName,
        email: form.email,
        mobile: form.mobile,
        address: form.address,
        gstNumber: form.gstNumber.trim() || undefined,
        taxInclusive: form.taxInclusive,
      })
      notify('Store profile updated')
      const session = useAuthStore.getState().store
      if (session) useAuthStore.setState({ store: { ...session, taxInclusive: form.taxInclusive } })
      reload()
    } catch (err) {
      setFormError(errorMessage(err))
      setErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <LoadingState />
  if (error) return <ErrorState message={error} onRetry={reload} />
  if (!data) return null

  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
      <Card className="xl:col-span-2">
        <div className="border-b border-slate-100 px-5 py-4">
          <h2 className="text-sm font-bold text-slate-800">Store profile</h2>
          <p className="mt-0.5 text-xs text-slate-500">This information appears on receipts and reports.</p>
        </div>
        <div className="space-y-4 p-5">
          {formError && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
              {formError}
            </div>
          )}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Store name" required error={errors.storeName}>
              <Input value={form.storeName} onChange={(event) => setForm({ ...form, storeName: event.target.value })} disabled={!canManage} />
            </Field>
            <Field label="Owner name" required error={errors.ownerName}>
              <Input value={form.ownerName} onChange={(event) => setForm({ ...form, ownerName: event.target.value })} disabled={!canManage} />
            </Field>
            <Field label="Email" required error={errors.email}>
              <Input type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} disabled={!canManage} />
            </Field>
            <Field label="Mobile" required error={errors.mobile}>
              <Input value={form.mobile} onChange={(event) => setForm({ ...form, mobile: event.target.value })} disabled={!canManage} />
            </Field>
            <Field label="GSTIN" error={errors.gstNumber}>
              <Input
                value={form.gstNumber}
                onChange={(event) => setForm({ ...form, gstNumber: event.target.value.toUpperCase() })}
                className="uppercase"
                disabled={!canManage}
              />
            </Field>
            <Field label="Store code">
              <Input value={data.storeCode} disabled />
            </Field>
          </div>
          <Field label="Address" required error={errors.address}>
            <Textarea value={form.address} onChange={(event) => setForm({ ...form, address: event.target.value })} rows={2} disabled={!canManage} />
          </Field>

          <div className="rounded-lg border border-slate-200 bg-slate-50 px-4 py-3">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <p className="text-xs font-bold text-slate-800">Tax inclusive pricing</p>
                <p className="mt-0.5 text-[11px] text-slate-500">
                  {form.taxInclusive
                    ? 'Sale and purchase rates already include GST. Backend derives taxable value and tax from the total.'
                    : 'GST is added on top of sale and purchase rates. Example: ₹100 + 5% = ₹105.'}
                </p>
              </div>
              <Toggle
                checked={form.taxInclusive}
                onChange={(checked) => setForm({ ...form, taxInclusive: checked })}
                label={form.taxInclusive ? 'Inclusive' : 'Exclusive'}
                disabled={!canManage}
              />
            </div>
          </div>
          {canManage && (
            <div className="flex justify-end">
              <Button icon={<Save className="h-4 w-4" />} onClick={save} loading={saving}>
                Save changes
              </Button>
            </div>
          )}
        </div>
      </Card>

      <Card className="h-fit">
        <div className="flex items-center gap-3 border-b border-slate-100 px-5 py-4">
          <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-brand-50 text-brand-600">
            <StoreIcon className="h-5 w-5" />
          </span>
          <div>
            <h2 className="text-sm font-bold text-slate-800">{data.storeName}</h2>
            <p className="text-[11px] text-slate-500">{data.storeCode}</p>
          </div>
        </div>
        <div className="space-y-3 p-5 text-xs">
          <div className="flex items-center justify-between">
            <span className="text-slate-500">Status</span>
            <StatusBadge status={data.status} />
          </div>
          <div className="flex items-center justify-between">
            <span className="flex items-center gap-1.5 text-slate-500">
              <Building2 className="h-3.5 w-3.5" /> Branches
            </span>
            <Badge tone="slate">{data.branchCount}</Badge>
          </div>
          <div className="flex items-center justify-between">
            <span className="flex items-center gap-1.5 text-slate-500">
              <Users className="h-3.5 w-3.5" /> Users
            </span>
            <Badge tone="slate">{data.userCount}</Badge>
          </div>
          <div className="flex items-center justify-between">
            <span className="flex items-center gap-1.5 text-slate-500">
              <CalendarDays className="h-3.5 w-3.5" /> Created
            </span>
            <span className="font-medium text-slate-700">{formatDate(data.createdAt)}</span>
          </div>
          <div className="space-y-1.5 border-t border-slate-100 pt-3 text-slate-600">
            <p className="flex items-start gap-2">
              <Mail className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-400" /> {data.email}
            </p>
            <p className="flex items-start gap-2">
              <Phone className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-400" /> {data.mobile}
            </p>
            <p className="flex items-start gap-2">
              <MapPin className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-400" /> {data.address}
            </p>
          </div>
        </div>
      </Card>

      {subscription && (
        <Card className="xl:col-span-3">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-5 py-4">
            <div>
              <h2 className="text-sm font-bold text-slate-800">Subscription & branch limit</h2>
              <p className="mt-0.5 text-xs text-slate-500">
                Managed by the Platform Administrator. Changes apply immediately.
              </p>
            </div>
            <Badge tone={subscription.expired ? 'red' : subscription.daysRemaining <= 30 ? 'amber' : 'green'}>
              {subscription.expired ? 'Expired' : `${subscription.daysRemaining} days remaining`}
            </Badge>
          </div>

          <div className="grid grid-cols-2 gap-3 p-5 lg:grid-cols-5">
            <div className="rounded-lg bg-slate-50 p-3">
              <p className="text-[11px] font-semibold text-slate-500">Subscription starts</p>
              <p className="mt-1 text-sm font-bold text-slate-900">{formatDate(subscription.startDate)}</p>
            </div>
            <div className="rounded-lg bg-slate-50 p-3">
              <p className="text-[11px] font-semibold text-slate-500">Subscription ends</p>
              <p className={`mt-1 text-sm font-bold ${subscription.expired ? 'text-rose-600' : 'text-slate-900'}`}>
                {formatDate(subscription.endDate)}
              </p>
            </div>
            <div className="rounded-lg bg-slate-50 p-3">
              <p className="text-[11px] font-semibold text-slate-500">Maximum branches</p>
              <p className="mt-1 text-sm font-bold text-slate-900">{subscription.maxBranches}</p>
            </div>
            <div className="rounded-lg bg-slate-50 p-3">
              <p className="text-[11px] font-semibold text-slate-500">Existing branches</p>
              <p className="mt-1 text-sm font-bold text-slate-900">{subscription.branchCount}</p>
            </div>
            <div className="rounded-lg bg-slate-50 p-3">
              <p className="text-[11px] font-semibold text-slate-500">Remaining branches</p>
              <p className={`mt-1 text-sm font-bold ${subscription.remainingBranches === 0 ? 'text-rose-600' : 'text-emerald-600'}`}>
                {subscription.remainingBranches}
              </p>
            </div>
          </div>

          <div className="border-t border-slate-100 px-5 py-4 text-xs">
            <p className="mb-1.5 text-[11px] font-bold tracking-wider text-slate-500 uppercase">
              Platform administrator contact
            </p>
            <div className="flex flex-wrap gap-x-6 gap-y-1.5 text-slate-600">
              <span className="flex items-center gap-1.5">
                <Mail className="h-3.5 w-3.5 text-slate-400" />
                {subscription.platformContactEmail || 'Not configured'}
              </span>
              <span className="flex items-center gap-1.5">
                <Phone className="h-3.5 w-3.5 text-slate-400" />
                {subscription.platformContactPhone || 'Not configured'}
              </span>
            </div>
            {subscription.expired && (
              <p className="mt-3 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 font-semibold text-rose-700">
                Your subscription has expired. Please contact the Platform Administrator to renew your
                subscription.
              </p>
            )}
          </div>
        </Card>
      )}
    </div>
  )
}
