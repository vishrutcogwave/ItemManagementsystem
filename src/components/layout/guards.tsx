import { ArrowLeft, LockKeyhole } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '../../store/auth'
import { Button, LoadingState } from '../ui'

function SessionLoading() {
  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <LoadingState />
    </div>
  )
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const user = useAuthStore((state) => state.user)
  const sessionReady = useAuthStore((state) => state.sessionReady)
  const mustChangePassword = useAuthStore((state) => state.mustChangePassword)
  const location = useLocation()

  if (!sessionReady) return <SessionLoading />
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />
  }
  return <>{children}</>
}

export function RequirePermission({
  permission,
  anyOf,
  children,
}: {
  permission?: string
  anyOf?: string[]
  children: ReactNode
}) {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const hasAnyPermission = useAuthStore((state) => state.hasAnyPermission)

  const allowed = permission ? hasPermission(permission) : anyOf ? hasAnyPermission(...anyOf) : true
  if (!allowed) return <AccessDenied />
  return <>{children}</>
}

export function PublicOnly({ children }: { children: ReactNode }) {
  const user = useAuthStore((state) => state.user)
  const sessionReady = useAuthStore((state) => state.sessionReady)
  const platformAdmin = useAuthStore((state) => state.platformAdmin)
  const mustChangePassword = useAuthStore((state) => state.mustChangePassword)
  if (!sessionReady) return <SessionLoading />
  if (user) {
    return (
      <Navigate
        to={mustChangePassword ? '/change-password' : platformAdmin ? '/platform/stores' : '/'}
        replace
      />
    )
  }
  return <>{children}</>
}

export function AccessDenied() {
  const platformAdmin = useAuthStore((state) => state.platformAdmin)
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center px-6 text-center">
      <span className="flex h-16 w-16 items-center justify-center rounded-2xl bg-rose-50 text-rose-500">
        <LockKeyhole className="h-7 w-7" />
      </span>
      <h2 className="mt-4 text-lg font-bold text-slate-900">Access restricted</h2>
      <p className="mt-1 max-w-sm text-sm text-slate-500">
        Your role does not include permission for this page. Ask your store administrator if you need access.
      </p>
      <Link to={platformAdmin ? '/platform/stores' : '/'} className="mt-5">
        <Button icon={<ArrowLeft className="h-4 w-4" />}>{platformAdmin ? 'Back to stores' : 'Back to dashboard'}</Button>
      </Link>
    </div>
  )
}
