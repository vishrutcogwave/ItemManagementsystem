import { ArrowRight, Eye, EyeOff, LockKeyhole, Store, UserRound } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { AuthShell } from '../components/layout/AuthShell'
import { Button, Field, Input } from '../components/ui'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { useAuthStore } from '../store/auth'
import { notify } from '../store/ui'
import type { AuthResponse } from '../types'

export function LoginPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const setSession = useAuthStore((state) => state.setSession)

  const [storeCode, setStoreCode] = useState(params.get('store') ?? '')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setLoading(true)
    setError(null)
    setErrors({})
    try {
      const { data } = await api.post<AuthResponse>('/auth/login', {
        storeCode: storeCode.trim(),
        username: username.trim(),
        password,
      })
      setSession(data)
      notify(`Welcome back, ${data.user.fullName.split(' ')[0]}`)
      navigate(data.platformAdmin ? '/platform/stores' : '/', { replace: true })
    } catch (err) {
      setError(errorMessage(err))
      setErrors(fieldErrorsOf(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthShell
      title="Sign in to your workspace"
      subtitle="Enter your store code and credentials to continue."
    >
      <form onSubmit={submit} className="space-y-4">
        {error && (
          <div className="rounded-lg border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-xs font-medium text-rose-700">
            {error}
          </div>
        )}

        <Field label="Store code" required error={errors.storeCode}>
          <div className="relative">
            <Store className="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <Input
              value={storeCode}
              onChange={(event) => setStoreCode(event.target.value.toUpperCase())}
              placeholder="e.g. ABC001"
              autoComplete="organization"
              className="pl-9 tracking-wide uppercase"
              invalid={Boolean(errors.storeCode)}
              autoFocus
            />
          </div>
        </Field>

        <Field label="Username or email" required error={errors.username}>
          <div className="relative">
            <UserRound className="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <Input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              placeholder="Your username"
              autoComplete="username"
              className="pl-9"
              invalid={Boolean(errors.username)}
            />
          </div>
        </Field>

        <Field label="Password" required error={errors.password}>
          <div className="relative">
            <LockKeyhole className="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <Input
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="Your password"
              autoComplete="current-password"
              className="pr-10 pl-9"
              invalid={Boolean(errors.password)}
            />
            <button
              type="button"
              onClick={() => setShowPassword((value) => !value)}
              className="absolute top-1/2 right-3 -translate-y-1/2 text-slate-400 hover:text-slate-600"
              aria-label={showPassword ? 'Hide password' : 'Show password'}
            >
              {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </Field>

        <Button type="submit" size="lg" loading={loading} className="w-full justify-center">
          Sign in <ArrowRight className="h-4 w-4" />
        </Button>

        <p className="text-center text-xs text-slate-500">
          New store?{' '}
          <Link to="/register" className="link">
            Register your business
          </Link>
        </p>
      </form>

      <div className="mt-8 rounded-xl border border-slate-200 bg-slate-50/60 px-4 py-3 text-[11px] leading-relaxed text-slate-500">
        <span className="font-semibold text-slate-600">Need help signing in?</span> Use the store code shared by your
        administrator. If your store is inactive or you are locked out, contact the platform team.
      </div>
    </AuthShell>
  )
}
