import { Info, ShieldCheck } from 'lucide-react'
import { ChangePasswordForm } from '../../components/auth/ChangePasswordForm'
import { Badge, Card } from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { api } from '../../lib/api'
import { useAuthStore } from '../../store/auth'
import { notify } from '../../store/ui'

interface BuildInfo {
  name: string
  version: string
  artifact: string
  buildTime: string
  gitCommit: string
  javaVersion: string
}

export function SecuritySettings() {
  const user = useAuthStore((state) => state.user)
  const roles = useAuthStore((state) => state.roles)
  const { data: build } = useApi<BuildInfo>(
    () => api.get<BuildInfo>('/version').then((response) => response.data),
    [],
  )

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <Card className="lg:col-span-2">
        <div className="mb-4 flex items-start gap-3">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-brand-50 text-brand-600">
            <ShieldCheck className="h-5 w-5" />
          </span>
          <div>
            <h3 className="text-sm font-bold text-slate-900">Change your password</h3>
            <p className="mt-0.5 text-xs text-slate-500">
              Your current password is required. Updating it signs out your other active sessions.
            </p>
          </div>
        </div>
        <ChangePasswordForm onSuccess={() => notify('Password updated successfully')} />
      </Card>

      <Card>
        <h3 className="text-sm font-bold text-slate-900">Account</h3>
        <dl className="mt-3 space-y-2.5 text-xs">
          <div>
            <dt className="text-slate-400">Username</dt>
            <dd className="font-semibold text-slate-700">{user?.username}</dd>
          </div>
          <div>
            <dt className="text-slate-400">Email</dt>
            <dd className="font-semibold break-all text-slate-700">{user?.email}</dd>
          </div>
          <div>
            <dt className="text-slate-400">Roles</dt>
            <dd className="mt-1 flex flex-wrap gap-1">
              {roles.map((role) => (
                <Badge key={role} tone="slate">
                  {role.replaceAll('_', ' ')}
                </Badge>
              ))}
            </dd>
          </div>
        </dl>
        <p className="mt-4 rounded-lg bg-slate-50 px-3 py-2 text-[11px] leading-relaxed text-slate-500">
          Forgotten your password? Ask a store administrator or the platform team to reset it. A reset
          requires you to choose a new password at your next sign-in.
        </p>
      </Card>

      <Card className="lg:col-span-3">
        <div className="flex flex-wrap items-start gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
            <Info className="h-4 w-4" />
          </span>
          <div className="min-w-0 flex-1">
            <h3 className="text-sm font-bold text-slate-900">Server version</h3>
            <div className="mt-2 grid grid-cols-2 gap-x-6 gap-y-1.5 text-xs sm:grid-cols-4">
              <div>
                <p className="text-slate-400">Version</p>
                <p className="font-semibold text-slate-700">{build?.version ?? '—'}</p>
              </div>
              <div>
                <p className="text-slate-400">Build</p>
                <p className="font-mono text-[11px] font-semibold text-slate-700">{build?.gitCommit ?? '—'}</p>
              </div>
              <div>
                <p className="text-slate-400">Built</p>
                <p className="font-semibold text-slate-700">{build?.buildTime ?? '—'}</p>
              </div>
              <div>
                <p className="text-slate-400">Java</p>
                <p className="font-semibold text-slate-700">{build?.javaVersion ?? '—'}</p>
              </div>
            </div>
            <p className="mt-2 text-[11px] text-slate-400">
              Quote the version and build commit when reporting a problem so support can match the exact release.
            </p>
          </div>
        </div>
      </Card>
    </div>
  )
}
