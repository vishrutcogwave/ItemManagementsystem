import { KeyRound, Pencil, Plus, ShieldCheck, Sparkles, Users } from 'lucide-react'
import { useMemo, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  Checkbox,
  EmptyState,
  ErrorState,
  Field,
  Input,
  LoadingState,
  Modal,
  SearchInput,
  Textarea,
} from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { api, errorMessage, fieldErrorsOf } from '../../lib/api'
import { titleCase } from '../../lib/format'
import { useAuthStore } from '../../store/auth'
import { notify } from '../../store/ui'
import type { Permission, Role } from '../../types'

interface FormState {
  code: string
  name: string
  description: string
  permissionCodes: string[]
}

const empty: FormState = { code: '', name: '', description: '', permissionCodes: [] }

const GROUP_ORDER = [
  'DASHBOARD',
  'ITEM',
  'CATEGORY',
  'SUPPLIER',
  'PURCHASE',
  'INVENTORY',
  'TRANSFER',
  'SALES',
  'REPORT',
  'USER',
  'ROLE',
  'BRANCH',
  'STORE',
  'PLATFORM',
]

const GROUP_LABELS: Record<string, string> = {
  DASHBOARD: 'Dashboard',
  ITEM: 'Items',
  CATEGORY: 'Categories',
  SUPPLIER: 'Suppliers',
  PURCHASE: 'Purchases',
  INVENTORY: 'Inventory',
  TRANSFER: 'Stock transfers',
  SALES: 'Sales & billing',
  REPORT: 'Reports',
  USER: 'Users',
  ROLE: 'Roles & permissions',
  BRANCH: 'Branches',
  STORE: 'Store',
  PLATFORM: 'Platform',
}

function groupPermissions(permissions: Permission[]) {
  const groups = new Map<string, Permission[]>()
  for (const permission of permissions) {
    const group = permission.code.split('_')[0] ?? 'OTHER'
    const list = groups.get(group) ?? []
    list.push(permission)
    groups.set(group, list)
  }
  const ordered = [...groups.keys()].sort((a, b) => {
    const ai = GROUP_ORDER.indexOf(a)
    const bi = GROUP_ORDER.indexOf(b)
    return (ai === -1 ? 999 : ai) - (bi === -1 ? 999 : bi)
  })
  return ordered.map((key) => ({
    key,
    label: GROUP_LABELS[key] ?? titleCase(key),
    items: groups.get(key) ?? [],
  }))
}

export function RolesSettings() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const canManage = hasPermission('ROLE_MANAGE')

  const { data, loading, error, reload } = useApi<Role[]>(
    () => api.get('/roles').then((response) => response.data),
    [],
  )

  const { data: permissions, loading: permissionsLoading } = useApi<Permission[]>(
    () =>
      canManage
        ? api.get('/permissions').then((response) => response.data)
        : Promise.resolve([]),
    [canManage],
  )

  const [search, setSearch] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Role | null>(null)
  const [form, setForm] = useState<FormState>(empty)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const permissionGroups = useMemo(() => groupPermissions(permissions ?? []), [permissions])

  const roles = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return (data ?? []).filter(
      (role) =>
        !needle ||
        role.name.toLowerCase().includes(needle) ||
        role.code.toLowerCase().includes(needle) ||
        (role.description ?? '').toLowerCase().includes(needle),
    )
  }, [data, search])

  const openCreate = () => {
    setEditing(null)
    setForm(empty)
    setErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const openEdit = (role: Role) => {
    setEditing(role)
    setForm({
      code: role.code,
      name: role.name,
      description: role.description ?? '',
      permissionCodes: role.permissions.map((permission) => permission.code),
    })
    setErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const togglePermission = (code: string) => {
    setForm((current) => ({
      ...current,
      permissionCodes: current.permissionCodes.includes(code)
        ? current.permissionCodes.filter((item) => item !== code)
        : [...current.permissionCodes, code],
    }))
  }

  const toggleGroup = (items: Permission[], checked: boolean) => {
    const codes = items.map((item) => item.code)
    setForm((current) => ({
      ...current,
      permissionCodes: checked
        ? [...new Set([...current.permissionCodes, ...codes])]
        : current.permissionCodes.filter((code) => !codes.includes(code)),
    }))
  }

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!/^[A-Za-z0-9_]{2,50}$/.test(form.code.trim())) {
      nextErrors.code = 'Use 2-50 letters, numbers or underscore'
    }
    if (!form.name.trim()) nextErrors.name = 'Role name is required'
    if (form.permissionCodes.length === 0) nextErrors.permissions = 'Select at least one permission'
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors)
      return
    }
    setSaving(true)
    setFormError(null)
    setErrors({})
    try {
      const payload = {
        code: form.code.trim().toUpperCase(),
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        permissionCodes: form.permissionCodes,
      }
      if (editing) {
        await api.put(`/roles/${editing.id}`, payload)
        notify('Role updated')
      } else {
        await api.post('/roles', payload)
        notify('Role created')
      }
      setModalOpen(false)
      reload()
    } catch (err) {
      setFormError(errorMessage(err))
      setErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  const totalUsers = (data ?? []).reduce((sum, role) => sum + role.userCount, 0)
  const customRoles = (data ?? []).filter((role) => !role.system).length

  return (
    <div>
      <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-wrap items-center gap-2">
          <SearchInput className="sm:w-64" placeholder="Search roles…" value={search} onChange={(event) => setSearch(event.target.value)} />
          <Badge tone="slate">{data?.length ?? 0} roles</Badge>
          <Badge tone="purple">{customRoles} custom</Badge>
          <Badge tone="blue">{totalUsers} assigned users</Badge>
        </div>
        {canManage && (
          <Button size="sm" icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
            Add role
          </Button>
        )}
      </div>

      {error ? (
        <Card>
          <ErrorState message={error} onRetry={reload} />
        </Card>
      ) : loading ? (
        <Card>
          <LoadingState label="Loading roles…" />
        </Card>
      ) : roles.length === 0 ? (
        <Card>
          <EmptyState
            icon={<ShieldCheck className="h-6 w-6" />}
            title="No roles found"
            message="Create a role to control which modules and actions your team can use."
            action={
              canManage ? (
                <Button size="sm" icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
                  Add role
                </Button>
              ) : undefined
            }
          />
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          {roles.map((role) => (
            <Card key={role.id} className="flex flex-col p-4">
              <div className="flex items-start justify-between gap-2">
                <div className="flex items-center gap-2.5">
                  <span
                    className={`flex h-9 w-9 items-center justify-center rounded-lg ${
                      role.system ? 'bg-violet-50 text-violet-600' : 'bg-brand-50 text-brand-600'
                    }`}
                  >
                    {role.system ? <ShieldCheck className="h-4.5 w-4.5" /> : <KeyRound className="h-4.5 w-4.5" />}
                  </span>
                  <div>
                    <p className="text-sm font-bold text-slate-800">{role.name}</p>
                    <p className="text-[11px] tracking-wide text-slate-400">{role.code}</p>
                  </div>
                </div>
                {role.system ? <Badge tone="purple">System</Badge> : <Badge tone="brand">Custom</Badge>}
              </div>

              <p className="mt-3 line-clamp-2 min-h-8 text-xs text-slate-500">
                {role.description || 'No description provided.'}
              </p>

              <div className="mt-3 flex flex-wrap gap-1">
                {role.permissions.slice(0, 4).map((permission) => (
                  <span
                    key={permission.id}
                    className="rounded-md bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-500"
                  >
                    {permission.name}
                  </span>
                ))}
                {role.permissions.length > 4 && (
                  <span className="rounded-md bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-500">
                    +{role.permissions.length - 4} more
                  </span>
                )}
                {role.permissions.length === 0 && <span className="text-[11px] text-slate-400">No permissions</span>}
              </div>

              <div className="mt-auto flex items-center justify-between border-t border-slate-100 pt-3">
                <span className="flex items-center gap-1.5 text-xs text-slate-500">
                  <Users className="h-3.5 w-3.5" /> {role.userCount} user{role.userCount === 1 ? '' : 's'}
                </span>
                {canManage && (
                  <Button size="sm" variant="outline" icon={<Pencil className="h-3.5 w-3.5" />} onClick={() => openEdit(role)}>
                    Edit role
                  </Button>
                )}
              </div>
            </Card>
          ))}
        </div>
      )}

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit role' : 'Create role'}
        description="Choose the modules and actions this role can access."
        size="xl"
        footer={
          <>
            <span className="mr-auto hidden text-xs text-slate-500 sm:block">
              {form.permissionCodes.length} permission{form.permissionCodes.length === 1 ? '' : 's'} selected
            </span>
            <Button variant="outline" onClick={() => setModalOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              {editing ? 'Save changes' : 'Create role'}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {formError && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
              {formError}
            </div>
          )}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <Field label="Role code" required error={errors.code} hint={editing ? 'Code cannot be changed' : 'e.g. CASHIER'}>
              <Input
                value={form.code}
                onChange={(event) => setForm({ ...form, code: event.target.value.toUpperCase() })}
                disabled={Boolean(editing)}
                className="uppercase"
                placeholder="CASHIER"
              />
            </Field>
            <Field label="Role name" required error={errors.name} className="sm:col-span-2">
              <Input value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} placeholder="Cashier" />
            </Field>
          </div>
          <Field label="Description" error={errors.description}>
            <Textarea
              value={form.description}
              onChange={(event) => setForm({ ...form, description: event.target.value })}
              rows={2}
              placeholder="What can this role do?"
            />
          </Field>

          {editing?.system && (
            <div className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
              <Sparkles className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              This is a system role. Be careful when removing permissions — it may affect existing users.
            </div>
          )}

          <div>
            <div className="mb-2 flex items-center justify-between">
              <span className="field-label mb-0">
                Permissions <span className="text-rose-500">*</span>
              </span>
              {errors.permissions && <span className="text-xs font-medium text-rose-600">{errors.permissions}</span>}
            </div>
            {permissionsLoading ? (
              <LoadingState label="Loading permissions…" />
            ) : (
              <div className="thin-scrollbar grid max-h-80 grid-cols-1 gap-3 overflow-y-auto rounded-xl border border-slate-200 p-3 sm:grid-cols-2">
                {permissionGroups.map((group) => {
                  const allChecked = group.items.every((item) => form.permissionCodes.includes(item.code))
                  return (
                    <div key={group.key} className="rounded-lg bg-slate-50/70 p-3">
                      <label className="mb-2 flex cursor-pointer items-center justify-between gap-2">
                        <span className="text-[11px] font-bold tracking-wide text-slate-600 uppercase">{group.label}</span>
                        <span className="flex items-center gap-1.5 text-[10px] font-medium text-slate-400">
                          select all
                          <input
                            type="checkbox"
                            checked={allChecked}
                            onChange={(event) => toggleGroup(group.items, event.target.checked)}
                            className="h-3.5 w-3.5 rounded border-slate-300 accent-brand-600"
                          />
                        </span>
                      </label>
                      <div className="space-y-1.5">
                        {group.items.map((permission) => (
                          <Checkbox
                            key={permission.code}
                            checked={form.permissionCodes.includes(permission.code)}
                            onChange={() => togglePermission(permission.code)}
                            className="flex w-full text-xs"
                            label={<span title={permission.description ?? undefined}>{permission.name}</span>}
                          />
                        ))}
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        </div>
      </Modal>
    </div>
  )
}
