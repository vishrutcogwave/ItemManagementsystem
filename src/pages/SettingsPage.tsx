import { Building2, Printer, ShieldCheck, Store, Users } from 'lucide-react'
import { useEffect, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Badge, PageHeader, Tabs } from '../components/ui'
import { useAuthStore } from '../store/auth'
import { BranchesSettings } from './settings/BranchesSettings'
import { PrintersSettings } from './settings/PrintersSettings'
import { RolesSettings } from './settings/RolesSettings'
import { SecuritySettings } from './settings/SecuritySettings'
import { StoreSettings } from './settings/StoreSettings'
import { UsersSettings } from './settings/UsersSettings'

export function SettingsPage() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const store = useAuthStore((state) => state.store)
  const [searchParams, setSearchParams] = useSearchParams()

  const tabs = useMemo(() => {
    const list: { key: string; label: string }[] = []
    if (hasPermission('STORE_VIEW')) list.push({ key: 'store', label: 'Store profile' })
    if (hasPermission('BRANCH_VIEW')) list.push({ key: 'branches', label: 'Branches' })
    if (hasPermission('USER_VIEW')) list.push({ key: 'users', label: 'Users' })
    if (hasPermission('ROLE_VIEW')) list.push({ key: 'roles', label: 'Roles & permissions' })
    if (hasPermission('PRINTER_VIEW')) list.push({ key: 'printers', label: 'Printers' })
    list.push({ key: 'security', label: 'My security' })
    return list
  }, [hasPermission])

  const requested = searchParams.get('tab')
  const active = tabs.some((tab) => tab.key === requested) ? requested! : (tabs[0]?.key ?? '')

  useEffect(() => {
    if (tabs.length > 0 && requested !== active) {
      setSearchParams({ tab: active }, { replace: true })
    }
  }, [active, requested, setSearchParams, tabs.length])

  return (
    <div>
      <PageHeader
        eyebrow="Administration"
        title="Store settings"
        subtitle={
          store
            ? `${store.storeName} · ${store.storeCode} — manage your store profile, branches, team access and roles.`
            : 'Manage your store profile, branches, team access and roles.'
        }
        actions={<Badge tone="brand">Store owner controls</Badge>}
      />

      <div className="mb-4 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <Tabs tabs={tabs} value={active} onChange={(key) => setSearchParams({ tab: key })} />
        <div className="hidden items-center gap-3 text-[11px] text-slate-400 sm:flex">
          <span className="flex items-center gap-1">
            <Store className="h-3.5 w-3.5" /> Profile
          </span>
          <span className="flex items-center gap-1">
            <Building2 className="h-3.5 w-3.5" /> Branches
          </span>
          <span className="flex items-center gap-1">
            <Users className="h-3.5 w-3.5" /> Team
          </span>
          <span className="flex items-center gap-1">
            <ShieldCheck className="h-3.5 w-3.5" /> Roles
          </span>
          <span className="flex items-center gap-1">
            <Printer className="h-3.5 w-3.5" /> Printers
          </span>
        </div>
      </div>

      {active === 'store' && <StoreSettings />}
      {active === 'branches' && <BranchesSettings />}
      {active === 'users' && <UsersSettings />}
      {active === 'roles' && <RolesSettings />}
      {active === 'printers' && <PrintersSettings />}
      {active === 'security' && <SecuritySettings />}
    </div>
  )
}
