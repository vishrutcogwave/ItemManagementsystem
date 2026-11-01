import { ArrowLeft, ShieldCheck } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { AuthShell } from '../components/layout/AuthShell'
import { ChangePasswordForm } from '../components/auth/ChangePasswordForm'
import { useAuthStore } from '../store/auth'
import { notify } from '../store/ui'

export function ChangePasswordPage() {
  const navigate = useNavigate()
  const mustChangePassword = useAuthStore((state) => state.mustChangePassword)
  const platformAdmin = useAuthStore((state) => state.platformAdmin)

  const finish = () => {
    notify('Password updated. Other sessions have been signed out.')
    navigate(platformAdmin ? '/platform/stores' : '/', { replace: true })
  }

  return (
    <AuthShell
      title={mustChangePassword ? 'Set a new password' : 'Change your password'}
      subtitle={
        mustChangePassword
          ? 'An administrator reset your password. Choose a new password to continue.'
          : 'Confirm your current password, then choose a new one.'
      }
    >
      {mustChangePassword && (
        <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-3.5 py-2.5 text-xs font-medium text-amber-800">
          For security, the temporary password must be replaced before you can use the workspace.
        </div>
      )}

      <ChangePasswordForm onSuccess={finish} />

      <div className="mt-6 flex flex-col gap-3 text-xs text-slate-500 sm:flex-row sm:items-center sm:justify-between">
        <span className="flex items-center gap-1.5">
          <ShieldCheck className="h-3.5 w-3.5" />
          All other active sessions are signed out.
        </span>
        {!mustChangePassword && (
          <Link to="/" className="inline-flex items-center gap-1.5 font-semibold text-brand-600 hover:underline">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to workspace
          </Link>
        )}
      </div>
    </AuthShell>
  )
}
