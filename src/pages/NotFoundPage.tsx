import { Compass } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Button } from '../components/ui'
import { useAuthStore } from '../store/auth'

export function NotFoundPage() {
  const platformAdmin = useAuthStore((state) => state.platformAdmin)
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-[#f3f5f9] px-6 text-center">
      <span className="flex h-16 w-16 items-center justify-center rounded-2xl bg-brand-50 text-brand-600">
        <Compass className="h-8 w-8" />
      </span>
      <p className="mt-5 text-sm font-bold tracking-widest text-brand-600 uppercase">Error 404</p>
      <h1 className="mt-1 text-2xl font-bold text-slate-900">Page not found</h1>
      <p className="mt-2 max-w-sm text-sm text-slate-500">
        The page you are looking for has moved or does not exist.
      </p>
      <Link to={platformAdmin ? '/platform/stores' : '/'} className="mt-6">
        <Button>Go to {platformAdmin ? 'stores' : 'dashboard'}</Button>
      </Link>
    </div>
  )
}
