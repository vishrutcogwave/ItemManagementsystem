import { ArrowLeft, ArrowRight } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AuthShell } from '../components/layout/AuthShell'
import { Button, Field, Input, Textarea } from '../components/ui'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { passwordProblem, PASSWORD_HINT } from '../lib/password'
import { notify } from '../store/ui'
import type { RegisterStoreRequest } from '../types'

const initial: RegisterStoreRequest = {
  storeCode: '',
  storeName: '',
  ownerName: '',
  email: '',
  mobile: '',
  address: '',
  gstNumber: '',
  state: '',
  username: '',
  password: '',
  branchName: '',
}

export function RegisterPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState<RegisterStoreRequest>(initial)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})

  const update = (key: keyof RegisterStoreRequest, value: string) => {
    setForm((current) => ({ ...current, [key]: value }))
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setLoading(true)
    setError(null)
    setErrors({})
    const passwordError = passwordProblem(form.password, form.username, form.email)
    if (passwordError) {
      setErrors({ password: passwordError })
      setLoading(false)
      return
    }
    try {
      await api.post('/auth/register-store', {
        ...form,
        storeCode: form.storeCode.trim().toUpperCase(),
        gstNumber: form.gstNumber?.trim() || undefined,
        state: form.state?.trim() || undefined,
        branchName: form.branchName?.trim() || undefined,
      })
      notify('Store registered successfully. You can sign in now.')
      navigate(`/login?store=${encodeURIComponent(form.storeCode.trim().toUpperCase())}`)
    } catch (err) {
      setError(errorMessage(err))
      setErrors(fieldErrorsOf(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthShell
      title="Register your store"
      subtitle="Create a store workspace with an owner account and first branch."
    >
      <form onSubmit={submit} className="space-y-4">
        {error && (
          <div className="rounded-lg border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-xs font-medium text-rose-700">
            {error}
          </div>
        )}

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label="Store code" required error={errors.storeCode} hint="3–30 letters, numbers, - or _">
            <Input
              value={form.storeCode}
              onChange={(event) => update('storeCode', event.target.value.toUpperCase())}
              placeholder="ABC001"
              className="uppercase"
              invalid={Boolean(errors.storeCode)}
            />
          </Field>
          <Field label="Store name" required error={errors.storeName}>
            <Input
              value={form.storeName}
              onChange={(event) => update('storeName', event.target.value)}
              placeholder="Sunrise Supermarket"
              invalid={Boolean(errors.storeName)}
            />
          </Field>
          <Field label="Owner name" required error={errors.ownerName}>
            <Input
              value={form.ownerName}
              onChange={(event) => update('ownerName', event.target.value)}
              placeholder="Full name"
              invalid={Boolean(errors.ownerName)}
            />
          </Field>
          <Field label="First branch name" error={errors.branchName} hint="Defaults to Main Branch">
            <Input
              value={form.branchName}
              onChange={(event) => update('branchName', event.target.value)}
              placeholder="Main Branch"
              invalid={Boolean(errors.branchName)}
            />
          </Field>
          <Field label="Owner email" required error={errors.email}>
            <Input
              type="email"
              value={form.email}
              onChange={(event) => update('email', event.target.value)}
              placeholder="owner@business.com"
              invalid={Boolean(errors.email)}
            />
          </Field>
          <Field label="Mobile number" required error={errors.mobile}>
            <Input
              value={form.mobile}
              onChange={(event) => update('mobile', event.target.value)}
              placeholder="9876543210"
              invalid={Boolean(errors.mobile)}
            />
          </Field>
        </div>

        <Field label="Store address" required error={errors.address}>
          <Textarea
            value={form.address}
            onChange={(event) => update('address', event.target.value)}
            placeholder="Street, area, city, state, PIN"
            rows={2}
          />
        </Field>

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label="GSTIN" error={errors.gstNumber} hint="Optional">
            <Input
              value={form.gstNumber}
              onChange={(event) => update('gstNumber', event.target.value.toUpperCase())}
              placeholder="22AAAAA0000A1Z5"
              className="uppercase"
            />
          </Field>
          <Field label="State" error={errors.state} hint="Used for intra / inter-state GST">
            <Input
              value={form.state}
              onChange={(event) => update('state', event.target.value)}
              placeholder="e.g. Karnataka"
            />
          </Field>
        </div>

        <div className="rounded-xl border border-slate-200 bg-slate-50/60 p-3.5">
          <p className="mb-3 text-[11px] font-bold tracking-wider text-slate-500 uppercase">Owner login</p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="Username" required error={errors.username}>
              <Input
                value={form.username}
                onChange={(event) => update('username', event.target.value)}
                placeholder="owner"
                autoComplete="username"
                invalid={Boolean(errors.username)}
              />
            </Field>
            <Field label="Password" required error={errors.password} hint={PASSWORD_HINT}>
              <Input
                type="password"
                value={form.password}
                onChange={(event) => update('password', event.target.value)}
                placeholder="••••••••"
                autoComplete="new-password"
                invalid={Boolean(errors.password)}
              />
            </Field>
          </div>
        </div>

        <Button type="submit" size="lg" loading={loading} className="w-full justify-center">
          Create store <ArrowRight className="h-4 w-4" />
        </Button>

        <p className="text-center text-xs text-slate-500">
          Already registered?{' '}
          <Link to="/login" className="link inline-flex items-center gap-1">
            <ArrowLeft className="h-3 w-3" /> Back to sign in
          </Link>
        </p>
      </form>
    </AuthShell>
  )
}
