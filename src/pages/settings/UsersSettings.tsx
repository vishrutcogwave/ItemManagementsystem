import { Pencil, Plus, Power, ShieldCheck, UserRound, Users } from 'lucide-react'
import { useMemo, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  Checkbox,
  DataTable,
  EmptyState,
  ErrorState,
  Field,
  Input,
  Modal,
  Pagination,
  SearchInput,
  Select,
  StatusBadge,
  type Column,
} from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { useDebounce } from '../../hooks/useDebounce'
import { api, errorMessage, fieldErrorsOf } from '../../lib/api'
import { formatDate, initials, titleCase } from '../../lib/format'
import { passwordProblem, PASSWORD_HINT } from '../../lib/password'
import { useAuthStore } from '../../store/auth'
import { notify, notifyError } from '../../store/ui'
import type { Branch, PageResponse, Role, User } from '../../types'

interface FormState {
  username: string
  fullName: string
  email: string
  mobile: string
  password: string
  roleId: number | ''
  branchIds: number[]
  status: 'ACTIVE' | 'INACTIVE'
}

const empty: FormState = {
  username: '',
  fullName: '',
  email: '',
  mobile: '',
  password: '',
  roleId: '',
  branchIds: [],
  status: 'ACTIVE',
}

const BRANCH_BOUND_ROLES = new Set(['BRANCH_MANAGER', 'BRANCH_STAFF'])

export function UsersSettings() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const currentUserId = useAuthStore((state) => state.user?.id)
  const canManage = hasPermission('USER_MANAGE')
  const canListRoles = canManage || hasPermission('ROLE_VIEW')
  const canListAllBranches = hasPermission('BRANCH_MANAGE')

  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<User>>(
    () =>
      api
        .get('/users', {
          params: {
            search: debouncedSearch || undefined,
            status: status || undefined,
            page,
            size,
          },
        })
        .then((response) => response.data),
    [debouncedSearch, status, page, size],
  )

  const { data: roles } = useApi<Role[]>(
    () =>
      canListRoles
        ? api.get('/users/roles/available').then((response) => response.data)
        : Promise.resolve([]),
    [canListRoles],
  )

  const { data: branches } = useApi<Branch[]>(
    () =>
      api
        .get(canListAllBranches ? '/branches/all' : '/branches/my-branches')
        .then((response) => response.data),
    [canListAllBranches],
  )

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<User | null>(null)
  const [form, setForm] = useState<FormState>(empty)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const roleOptions = useMemo(() => roles ?? [], [roles])
  const branchOptions = useMemo(
    () => (branches ?? []).filter((branch) => branch.status === 'ACTIVE' || editing?.branches.some((b) => b.id === branch.id)),
    [branches, editing],
  )

  const selectedRole = roleOptions.find((role) => role.id === form.roleId)
  const requiresBranch = selectedRole ? BRANCH_BOUND_ROLES.has(selectedRole.code) : false

  const openCreate = () => {
    setEditing(null)
    setForm({ ...empty, branchIds: [] })
    setErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const openEdit = (user: User) => {
    setEditing(user)
    setForm({
      username: user.username,
      fullName: user.fullName,
      email: user.email,
      mobile: user.mobile ?? '',
      password: '',
      roleId: user.roles[0]?.id ?? '',
      branchIds: user.branches.map((branch) => branch.id),
      status: user.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    })
    setErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const toggleBranch = (branchId: number) => {
    setForm((current) => ({
      ...current,
      branchIds: current.branchIds.includes(branchId)
        ? current.branchIds.filter((id) => id !== branchId)
        : [...current.branchIds, branchId],
    }))
  }

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!editing && !/^[A-Za-z0-9._-]{3,40}$/.test(form.username.trim())) {
      nextErrors.username = 'Use 3-40 letters, numbers, dot, underscore or hyphen'
    }
    if (!form.fullName.trim()) nextErrors.fullName = 'Full name is required'
    if (!form.email.trim()) nextErrors.email = 'Email is required'
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) nextErrors.email = 'Enter a valid email'
    if (!editing || form.password) {
      const problem = passwordProblem(form.password, form.username, form.email)
      if (problem) nextErrors.password = problem
    }
    if (!form.roleId) nextErrors.roleId = 'Select a role'
    if (requiresBranch && form.branchIds.length === 0) nextErrors.branchIds = 'Assign at least one branch for this role'
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors)
      return
    }

    setSaving(true)
    setFormError(null)
    setErrors({})
    try {
      if (editing) {
        await api.put(`/users/${editing.id}`, {
          fullName: form.fullName.trim(),
          email: form.email.trim(),
          mobile: form.mobile.trim() || undefined,
          roleId: form.roleId,
          branchIds: form.branchIds,
          status: form.status,
          password: form.password || undefined,
        })
        notify('User updated')
      } else {
        await api.post('/users', {
          username: form.username.trim(),
          email: form.email.trim(),
          password: form.password,
          fullName: form.fullName.trim(),
          mobile: form.mobile.trim() || undefined,
          roleId: form.roleId,
          branchIds: form.branchIds,
        })
        notify('User created')
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

  const toggleStatus = async (user: User) => {
    try {
      await api.put(`/users/${user.id}/status`, {
        status: user.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      })
      notify(user.status === 'ACTIVE' ? 'User deactivated' : 'User activated')
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    }
  }

  const columns: Column<User>[] = [
    {
      key: 'user',
      header: 'Team member',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-brand-50 text-[11px] font-bold text-brand-700">
            {initials(row.fullName)}
          </span>
          <div>
            <p className="font-semibold text-slate-800">
              {row.fullName}
              {row.id === currentUserId && <span className="ml-1.5 text-[10px] font-semibold text-brand-600">(you)</span>}
            </p>
            <p className="text-[11px] text-slate-400">@{row.username}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'contact',
      header: 'Contact',
      render: (row) => (
        <div className="text-xs">
          <p className="text-slate-600">{row.email}</p>
          <p className="text-slate-400">{row.mobile || '—'}</p>
        </div>
      ),
    },
    {
      key: 'roles',
      header: 'Role',
      render: (row) =>
        row.roles.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {row.roles.map((role) => (
              <Badge key={role.id} tone="brand">
                <ShieldCheck className="h-3 w-3" /> {role.name}
              </Badge>
            ))}
          </div>
        ) : (
          <span className="text-xs text-slate-400">No role</span>
        ),
    },
    {
      key: 'branches',
      header: 'Branches',
      render: (row) =>
        row.branches.length === 0 ? (
          <Badge tone="purple">All branches</Badge>
        ) : (
          <div className="flex flex-wrap gap-1">
            {row.branches.map((branch) => (
              <Badge key={branch.id} tone="slate">
                {branch.branchCode}
              </Badge>
            ))}
          </div>
        ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    {
      key: 'created',
      header: 'Added',
      render: (row) => <span className="text-slate-500">{formatDate(row.createdAt)}</span>,
    },
    ...(canManage
      ? [
          {
            key: 'actions',
            header: '',
            align: 'right' as const,
            render: (row: User) => (
              <div className="flex items-center justify-end gap-1">
                <button
                  onClick={() => openEdit(row)}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
                  title="Edit user"
                >
                  <Pencil className="h-3.5 w-3.5" />
                </button>
                <button
                  onClick={() => toggleStatus(row)}
                  disabled={row.id === currentUserId}
                  className={`flex h-7 w-7 items-center justify-center rounded-md transition hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-30 ${
                    row.status === 'ACTIVE' ? 'text-amber-500' : 'text-emerald-500'
                  }`}
                  title={row.status === 'ACTIVE' ? 'Deactivate user' : 'Activate user'}
                >
                  <Power className="h-3.5 w-3.5" />
                </button>
              </div>
            ),
          },
        ]
      : []),
  ]

  return (
    <div>
      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
            <SearchInput
              className="sm:w-64"
              placeholder="Search name, username or email…"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value)
                setPage(0)
              }}
            />
            <Select
              className="sm:w-40"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value)
                setPage(0)
              }}
            >
              <option value="">All statuses</option>
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
            </Select>
            <Badge tone="slate">{data?.totalElements ?? 0} users</Badge>
          </div>
          {canManage && (
            <Button size="sm" icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
              Add user
            </Button>
          )}
        </div>
        {error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={data?.content ?? []}
              rowKey={(row) => row.id}
              loading={loading}
              empty={
                <EmptyState
                  icon={<Users className="h-6 w-6" />}
                  title="No team members found"
                  message="Invite managers and staff so they can bill sales and manage stock at their branches."
                />
              }
            />
            {data && data.totalElements > 0 && (
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                totalElements={data.totalElements}
                size={data.size}
                onPageChange={setPage}
                onSizeChange={(next) => {
                  setSize(next)
                  setPage(0)
                }}
                itemLabel="users"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit user' : 'Add team member'}
        description={
          editing ? 'Update profile, access role and branch assignments.' : 'Create a login and assign an access role.'
        }
        size="lg"
        footer={
          <>
            <Button variant="outline" onClick={() => setModalOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              {editing ? 'Save changes' : 'Create user'}
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
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="Username" required={!editing} error={errors.username} hint={editing ? 'Username cannot be changed' : undefined}>
              <Input
                value={form.username}
                onChange={(event) => setForm({ ...form, username: event.target.value })}
                disabled={Boolean(editing)}
                placeholder="priya.manager"
                autoComplete="off"
              />
            </Field>
            <Field label="Full name" required error={errors.fullName}>
              <Input value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} placeholder="Priya Sharma" />
            </Field>
            <Field label="Email" required error={errors.email}>
              <Input
                type="email"
                value={form.email}
                onChange={(event) => setForm({ ...form, email: event.target.value })}
                placeholder="priya@example.com"
                autoComplete="off"
              />
            </Field>
            <Field label="Mobile" error={errors.mobile}>
              <Input value={form.mobile} onChange={(event) => setForm({ ...form, mobile: event.target.value })} placeholder="9876543210" />
            </Field>
            <Field
              label={editing ? 'New password' : 'Password'}
              required={!editing}
              error={errors.password}
              hint={editing ? 'Leave blank to keep the current password' : PASSWORD_HINT}
            >
              <Input
                type="password"
                value={form.password}
                onChange={(event) => setForm({ ...form, password: event.target.value })}
                placeholder="••••••••"
                autoComplete="new-password"
              />
            </Field>
            <Field label="Access role" required error={errors.roleId}>
              <Select
                value={form.roleId}
                onChange={(event) => setForm({ ...form, roleId: event.target.value ? Number(event.target.value) : '' })}
              >
                <option value="">Select a role</option>
                {roleOptions.map((role) => (
                  <option key={role.id} value={role.id}>
                    {role.name}
                  </option>
                ))}
              </Select>
            </Field>
            {editing && (
              <Field label="Status">
                <Select
                  value={form.status}
                  onChange={(event) => setForm({ ...form, status: event.target.value as FormState['status'] })}
                >
                  <option value="ACTIVE">Active</option>
                  <option value="INACTIVE">Inactive</option>
                </Select>
              </Field>
            )}
          </div>

          <div>
            <div className="field-label flex items-center justify-between">
              <span>
                Branch access
                {requiresBranch && <span className="ml-0.5 text-rose-500">*</span>}
              </span>
              <span className="text-[11px] font-normal text-slate-400">
                {form.branchIds.length === 0 ? 'No branch selected' : `${form.branchIds.length} selected`}
              </span>
            </div>
            <div className="thin-scrollbar max-h-44 space-y-1 overflow-y-auto rounded-lg border border-slate-200 p-3">
              {branchOptions.length === 0 ? (
                <p className="text-xs text-slate-500">No branches available.</p>
              ) : (
                branchOptions.map((branch) => (
                  <Checkbox
                    key={branch.id}
                    checked={form.branchIds.includes(branch.id)}
                    onChange={() => toggleBranch(branch.id)}
                    className="flex w-full rounded-md px-1.5 py-1 hover:bg-slate-50"
                    label={
                      <span className="flex items-center gap-2">
                        <UserRound className="h-3.5 w-3.5 text-slate-400" />
                        {branch.branchName}
                        <span className="text-[11px] text-slate-400">({branch.branchCode})</span>
                        {branch.status !== 'ACTIVE' && <Badge tone="red">Inactive</Badge>}
                      </span>
                    }
                  />
                ))
              )}
            </div>
            <p className="mt-1 text-xs text-slate-500">
              {requiresBranch
                ? 'Branch manager and branch staff roles must be assigned at least one branch.'
                : 'Leave all branches unchecked to grant store-wide access.'}
            </p>
            {errors.branchIds && <p className="mt-1 text-xs font-medium text-rose-600">{errors.branchIds}</p>}
          </div>

          {selectedRole && (
            <div className="rounded-lg bg-slate-50 px-3 py-2 text-[11px] text-slate-500">
              <span className="font-semibold text-slate-700">{titleCase(selectedRole.name)}</span> grants{' '}
              {selectedRole.permissions.length} permissions
              {selectedRole.description ? ` — ${selectedRole.description}` : ''}.
            </div>
          )}
        </div>
      </Modal>
    </div>
  )
}
