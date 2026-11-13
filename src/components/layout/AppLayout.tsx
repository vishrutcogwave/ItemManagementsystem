import { Building2, ChevronDown, KeyRound, LogOut, Menu, Plus, Store, X } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api, SUBSCRIPTION_EXPIRED_EVENT, type SubscriptionExpiredDetail } from '../../lib/api'
import { initials } from '../../lib/format'
import { useAuthStore } from '../../store/auth'
import type { SubscriptionSummary } from '../../types'
import { Button } from '../ui'
import { platformNav, pageTitle, storeNav } from './nav'
import { SubscriptionExpiredScreen } from './SubscriptionExpired'

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const { platformAdmin, store, user, logout, hasPermission, roles } = useAuthStore()
  const navigate = useNavigate()
  const groups = platformAdmin ? platformNav : storeNav

  const handleLogout = async () => {
    try {
      await api.post('/auth/logout')
    } catch {
      // Token may already be expired; local cleanup still applies.
    }
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="flex h-full flex-col bg-ink-900 text-slate-300">
      <div className="flex h-16 shrink-0 items-center gap-2.5 border-b border-white/5 px-5">
        <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-brand-600 text-white shadow-lg shadow-brand-600/30">
          <Store className="h-5 w-5" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-bold tracking-tight text-white">Item Management</p>
          <p className="truncate text-[10px] font-semibold tracking-[0.16em] text-slate-500 uppercase">
            {platformAdmin ? 'Platform Console' : 'Store Operations'}
          </p>
        </div>
      </div>

      {!platformAdmin && store && (
        <div className="mx-4 mt-4 shrink-0 rounded-xl border border-white/5 bg-white/[0.04] px-3 py-2.5">
          <p className="text-[10px] font-bold tracking-wider text-slate-500 uppercase">Current store</p>
          <p className="mt-0.5 truncate text-sm font-semibold text-white">{store.storeName}</p>
          <p className="truncate text-[11px] text-slate-400">
            {store.storeCode} · {roles.join(', ')}
          </p>
        </div>
      )}

      <nav
        aria-label="Primary"
        className="thin-scrollbar mt-4 min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 pb-4 [scrollbar-gutter:stable]"
      >
        {groups.map((group) => {
          const items = group.items.filter((item) => hasPermission(item.permission))
          if (items.length === 0) return null
          return (
            <div key={group.label} className="mb-5">
              <p className="mb-1.5 px-2.5 text-[10px] font-bold tracking-[0.16em] text-slate-500 uppercase">
                {group.label}
              </p>
              <div className="space-y-0.5">
                {items.map((item) => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.end}
                    onClick={onNavigate}
                    className={({ isActive }) =>
                      `group flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] font-medium transition ${
                        isActive
                          ? 'bg-brand-600 text-white shadow-md shadow-brand-900/30'
                          : 'text-slate-400 hover:bg-white/5 hover:text-white'
                      }`
                    }
                  >
                    <item.icon className="h-4.5 w-4.5 shrink-0" />
                    {item.label}
                  </NavLink>
                ))}
              </div>
            </div>
          )
        })}
      </nav>

      <div className="shrink-0 border-t border-white/5 p-3">
        <div className="flex items-center gap-2.5 rounded-lg px-2 py-1.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-brand-600/20 text-xs font-bold text-brand-300">
            {initials(user?.fullName)}
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-xs font-semibold text-white">{user?.fullName}</p>
            <p className="truncate text-[10px] text-slate-500">{user?.email}</p>
          </div>
          <button
            onClick={() => {
              onNavigate?.()
              navigate('/change-password')
            }}
            title="Change password"
            aria-label="Change password"
            className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition hover:bg-white/10 hover:text-white"
          >
            <KeyRound className="h-4 w-4" />
          </button>
          <button
            onClick={handleLogout}
            title="Sign out"
            aria-label="Sign out"
            className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition hover:bg-white/10 hover:text-white"
          >
            <LogOut className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  )
}

function BranchSelector() {
  const { branches, selectedBranchId, setBranch, allBranches, platformAdmin } = useAuthStore()
  if (platformAdmin || branches.length === 0) return null

  return (
    <div className="relative">
      <div className="pointer-events-none absolute top-1 left-3 flex items-center gap-1.5">
        <Building2 className="h-3 w-3 text-slate-400" />
        <span className="text-[9px] font-bold tracking-wider text-slate-400 uppercase">Branch</span>
      </div>
      <select
        value={selectedBranchId ?? ''}
        onChange={(event) => setBranch(event.target.value ? Number(event.target.value) : null)}
        className="h-10 w-full min-w-40 appearance-none rounded-lg border border-slate-200 bg-white pr-8 pl-3 pt-3 pb-0.5 text-xs font-semibold text-slate-800 outline-none transition hover:border-slate-300 focus:border-brand-500 focus:ring-2 focus:ring-brand-500/20 sm:min-w-48"
        aria-label="Select branch"
      >
        {allBranches && <option value="">All branches</option>}
        {branches.map((branch) => (
          <option key={branch.id} value={branch.id}>
            {branch.branchName} ({branch.branchCode})
          </option>
        ))}
      </select>
      <ChevronDown className="pointer-events-none absolute top-1/2 right-2.5 h-4 w-4 -translate-y-1/2 text-slate-400" />
    </div>
  )
}

function Topbar({ onOpenMenu }: { onOpenMenu: () => void }) {
  const location = useLocation()
  const navigate = useNavigate()
  const { platformAdmin, hasPermission, user } = useAuthStore()
  const title = pageTitle(location.pathname)

  return (
    <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-slate-200/80 bg-white/90 px-4 backdrop-blur sm:px-6">
      <button
        onClick={onOpenMenu}
        className="flex h-9 w-9 items-center justify-center rounded-lg text-slate-600 transition hover:bg-slate-100 lg:hidden"
        aria-label="Open menu"
      >
        <Menu className="h-5 w-5" />
      </button>

      <div className="min-w-0 flex-1">
        <p className="truncate text-[11px] font-semibold tracking-wide text-slate-400 uppercase">
          {platformAdmin ? 'Platform Administration' : 'Item Management System'}
        </p>
        <h1 className="truncate text-sm font-bold text-slate-900 sm:text-base">{title}</h1>
      </div>

      <div className="hidden items-center gap-3 sm:flex">
        <BranchSelector />
        {hasPermission('SALES_MANAGE') && (
          <Button size="sm" icon={<Plus className="h-4 w-4" />} onClick={() => navigate('/pos')} className="h-10">
            New Sale
          </Button>
        )}
      </div>

      <div className="flex items-center gap-2 border-l border-slate-200 pl-2.5">
        <span className="hidden text-right sm:block">
          <span className="block max-w-32 truncate text-xs font-semibold text-slate-700">{user?.fullName}</span>
          <span className="block text-[10px] text-slate-400">Signed in</span>
        </span>
        <span className="flex h-9 w-9 items-center justify-center rounded-full bg-brand-50 text-xs font-bold text-brand-700 ring-1 ring-brand-600/15">
          {initials(user?.fullName)}
        </span>
      </div>
    </header>
  )
}

export function AppLayout() {
  const [menuOpen, setMenuOpen] = useState(false)
  const { platformAdmin, hasPermission, logout } = useAuthStore()
  const navigate = useNavigate()
  const [summary, setSummary] = useState<SubscriptionSummary | null>(null)
  const [expiredDetail, setExpiredDetail] = useState<SubscriptionExpiredDetail | null>(null)
  const [expired, setExpired] = useState(false)
  const [checking, setChecking] = useState(false)

  const checkSubscription = useCallback(async () => {
    if (platformAdmin) return
    setChecking(true)
    try {
      const { data } = await api.get<SubscriptionSummary>('/subscription/summary')
      setSummary(data)
      setExpired(data.expired)
      if (!data.expired) setExpiredDetail(null)
    } catch {
      // The server enforces expiry on every API; a failed read must not unlock access.
    } finally {
      setChecking(false)
    }
  }, [platformAdmin])

  useEffect(() => {
    if (platformAdmin) return
    const timer = window.setTimeout(() => void checkSubscription(), 0)
    return () => window.clearTimeout(timer)
  }, [platformAdmin, checkSubscription])

  useEffect(() => {
    if (platformAdmin) return
    const handler = (event: Event) => {
      setExpired(true)
      const detail = (event as CustomEvent<SubscriptionExpiredDetail>).detail
      if (detail) setExpiredDetail(detail)
      void checkSubscription()
    }
    window.addEventListener(SUBSCRIPTION_EXPIRED_EVENT, handler)
    return () => window.removeEventListener(SUBSCRIPTION_EXPIRED_EVENT, handler)
  }, [platformAdmin, checkSubscription])

  useEffect(() => {
    if (!expired || platformAdmin) return
    const poll = () => void checkSubscription()
    const timer = window.setInterval(poll, 30_000)
    window.addEventListener('focus', poll)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', poll)
    }
  }, [expired, platformAdmin, checkSubscription])

  const signOut = async () => {
    try {
      await api.post('/auth/logout')
    } catch {
      // Token may already be expired; local cleanup still applies.
    }
    logout()
    navigate('/login', { replace: true })
  }

  if (!platformAdmin && expired) {
    return (
      <SubscriptionExpiredScreen
        summary={summary}
        fallback={expiredDetail}
        checking={checking}
        onRetry={checkSubscription}
        onSignOut={signOut}
      />
    )
  }

  return (
    <div className="min-h-screen bg-[#f3f5f9]">
      <aside className="fixed inset-y-0 left-0 z-40 hidden w-62 lg:block">
        <SidebarContent />
      </aside>

      {menuOpen && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div className="absolute inset-0 bg-slate-950/50 backdrop-blur-[2px]" onClick={() => setMenuOpen(false)} />
          <div className="absolute inset-y-0 left-0 w-72 shadow-pop">
            <button
              onClick={() => setMenuOpen(false)}
              className="absolute top-4 right-3 z-10 flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 hover:bg-white/10 hover:text-white"
              aria-label="Close menu"
            >
              <X className="h-4 w-4" />
            </button>
            <SidebarContent onNavigate={() => setMenuOpen(false)} />
          </div>
        </div>
      )}

      <div className="lg:pl-62">
        <Topbar onOpenMenu={() => setMenuOpen(true)} />
        <main className="px-4 py-5 sm:px-6 sm:py-6">
          {!platformAdmin && !window.navigator.onLine ? (
            <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2.5 text-xs font-medium text-amber-800">
              You are offline. Inventory and billing actions require a connection to the store server.
            </div>
          ) : null}
          <Outlet context={{ hasPermission }} />
        </main>
      </div>
    </div>
  )
}
