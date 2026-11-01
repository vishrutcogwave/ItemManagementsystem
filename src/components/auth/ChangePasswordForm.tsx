import { Eye, EyeOff, KeyRound } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Button, Field, Input } from '../ui'
import { api, errorMessage, fieldErrorsOf } from '../../lib/api'
import { passwordProblem } from '../../lib/password'
import { useAuthStore } from '../../store/auth'
import type { AuthResponse } from '../../types'

interface ChangePasswordFormProps {
  onSuccess?: () => void
}

export function ChangePasswordForm({ onSuccess }: ChangePasswordFormProps) {
  const user = useAuthStore((state) => state.user)
  const setSession = useAuthStore((state) => state.setSession)

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showPasswords, setShowPasswords] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    const nextErrors: Record<string, string> = {}
    if (!currentPassword) nextErrors.currentPassword = 'Enter your current password'
    const problem = passwordProblem(newPassword, user?.username, user?.email)
    if (problem) nextErrors.newPassword = problem
    else if (newPassword === currentPassword) {
      nextErrors.newPassword = 'Choose a password different from the current one'
    }
    if (confirmPassword !== newPassword) nextErrors.confirmPassword = 'Passwords do not match'
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors)
      return
    }

    setSaving(true)
    setErrors({})
    try {
      const { data } = await api.post<AuthResponse>('/auth/change-password', {
        currentPassword,
        newPassword,
      })
      setSession(data)
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
      onSuccess?.()
    } catch (err) {
      setError(errorMessage(err))
      setErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      {error && (
        <div className="rounded-lg border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-xs font-medium text-rose-700">
          {error}
        </div>
      )}

      <Field label="Current password" required error={errors.currentPassword}>
        <div className="relative">
          <KeyRound className="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input
            type={showPasswords ? 'text' : 'password'}
            value={currentPassword}
            onChange={(event) => setCurrentPassword(event.target.value)}
            autoComplete="current-password"
            className="pl-9"
            invalid={Boolean(errors.currentPassword)}
          />
        </div>
      </Field>

      <Field
        label="New password"
        required
        error={errors.newPassword}
        hint="8-72 characters with upper-case, lower-case, number and special character"
      >
        <Input
          type={showPasswords ? 'text' : 'password'}
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
          autoComplete="new-password"
          invalid={Boolean(errors.newPassword)}
        />
      </Field>

      <Field label="Confirm new password" required error={errors.confirmPassword}>
        <Input
          type={showPasswords ? 'text' : 'password'}
          value={confirmPassword}
          onChange={(event) => setConfirmPassword(event.target.value)}
          autoComplete="new-password"
          invalid={Boolean(errors.confirmPassword)}
        />
      </Field>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <button
          type="button"
          onClick={() => setShowPasswords((value) => !value)}
          className="inline-flex items-center gap-1.5 text-xs font-semibold text-slate-500 hover:text-slate-700"
        >
          {showPasswords ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
          {showPasswords ? 'Hide passwords' : 'Show passwords'}
        </button>
        <Button type="submit" loading={saving}>
          Update password
        </Button>
      </div>
    </form>
  )
}
