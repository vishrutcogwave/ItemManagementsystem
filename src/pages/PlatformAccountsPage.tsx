import {
  Copy,
  History,
  KeyRound,
  Pencil,
  Power,
  ShieldCheck,
  UserCog,
  Users,
} from 'lucide-react'
import { useState } from 'react'
import {
  Badge,
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  EmptyState,
  ErrorState,
  Field,
  Input,
  LoadingState,
  Modal,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatusBadge,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage } from '../lib/api'
import { formatDate, formatDateTime } from '../lib/format'
import { notify, notifyError } from '../store/ui'
import type {
  CredentialHistoryEntry,
  PageResponse,
  PlatformAccount,
  PlatformPasswordReset,
} from '../types'

const actionLabels: Record<string, { label: string; tone: 'green' | 'amber' | 'blue' | 'red' | 'slate' }> = {
  PASSWORD_RESET: { label: 'Password reset', tone: 'amber' },
  USERNAME_UPDATE: { label: 'Username changed', tone: 'blue' },
  ACTIVATE: { label: 'Account enabled', tone: 'green' },
  DEACTIVATE: { label: 'Account disabled', tone: 'red' },
}

export function PlatformAccountsPage() {
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<PlatformAccount>>(
    () =>
      api
        .get('/platform/accounts', {
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

  const [usernameTarget, setUsernameTarget] = useState<PlatformAccount | null>(null)
  const [username, setUsername] = useState('')
  const [usernameError, setUsernameError] = useState<string | null>(null)
  const [usernameSaving, setUsernameSaving] = useState(false)

  const [statusTarget, setStatusTarget] = useState<PlatformAccount | null>(null)
  const [statusSaving, setStatusSaving] = useState(false)

  const [resetTarget, setResetTarget] = useState<PlatformAccount | null>(null)
  const [resetSaving, setResetSaving] = useState(false)
  const [resetResult, setResetResult] = useState<PlatformPasswordReset | null>(null)

  const [historyTarget, setHistoryTarget] = useState<PlatformAccount | null>(null)
  const [history, setHistory] = useState<CredentialHistoryEntry[]>([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState<string | null>(null)

  const openUsername = (account: PlatformAccount) => {
    setUsernameTarget(account)
    setUsername(account.username)
    setUsernameError(null)
  }

  const saveUsername = async () => {
    if (!usernameTarget) return
    const next = username.trim()
    if (!/^[A-Za-z0-9._-]{3,80}$/.test(next)) {
      setUsernameError('Use 3-80 letters, numbers, dot, underscore or hyphen')
      return
    }
    setUsernameSaving(true)
    try {
      await api.put(`/platform/accounts/${usernameTarget.id}/username`, { username: next })
      notify('Login username updated')
      setUsernameTarget(null)
      reload()
    } catch (err) {
      setUsernameError(errorMessage(err))
    } finally {
      setUsernameSaving(false)
    }
  }

  const confirmStatus = async () => {
    if (!statusTarget) return
    setStatusSaving(true)
    try {
      const next = statusTarget.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
      await api.put(`/platform/accounts/${statusTarget.id}/status`, { status: next })
      notify(next === 'ACTIVE' ? 'Login account enabled' : 'Login account disabled')
      setStatusTarget(null)
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setStatusSaving(false)
    }
  }

  const confirmReset = async () => {
    if (!resetTarget) return
    setResetSaving(true)
    try {
      const { data: result } = await api.post<PlatformPasswordReset>(
        `/platform/accounts/${resetTarget.id}/reset-password`,
        { confirm: true },
      )
      setResetTarget(null)
      setResetResult(result)
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setResetSaving(false)
    }
  }

  const openHistory = async (account: PlatformAccount) => {
    setHistoryTarget(account)
    setHistory([])
    setHistoryError(null)
    setHistoryLoading(true)
    try {
      const { data: entries } = await api.get<CredentialHistoryEntry[]>(
        `/platform/accounts/${account.id}/history`,
      )
      setHistory(entries)
    } catch (err) {
      setHistoryError(errorMessage(err))
    } finally {
      setHistoryLoading(false)
    }
  }

  const copyPassword = async () => {
    if (!resetResult) return
    try {
      await navigator.clipboard.writeText(resetResult.temporaryPassword)
      notify('Temporary password copied')
    } catch {
      notifyError('Copy failed. Select the password and copy it manually.')
    }
  }

  const columns: Column<PlatformAccount>[] = [
    {
      key: 'account',
      header: 'Login account',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
            <UserCog className="h-4 w-4" />
          </span>
          <div>
            <p className="flex items-center gap-1.5 font-semibold text-slate-800">
              {row.fullName}
              {row.owner && <Badge tone="purple">Owner</Badge>}
            </p>
            <p className="font-mono text-[11px] text-slate-500">@{row.username}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'store',
      header: 'Store',
      render: (row) => (
        <div className="text-xs">
          <p className="font-medium text-slate-700">{row.storeName ?? '—'}</p>
          <p className="font-mono text-[11px] tracking-wide text-slate-400">{row.storeCode ?? '—'}</p>
        </div>
      ),
    },
    {
      key: 'contact',
      header: 'Contact',
      render: (row) => (
        <div className="text-xs">
          <p className="text-slate-600">{row.email}</p>
          <p className="text-slate-400">{row.mobile ?? 'No mobile'}</p>
        </div>
      ),
    },
    {
      key: 'role',
      header: 'Role',
      render: (row) => (
        <div className="flex flex-wrap items-center gap-1">
          {row.roles.length === 0 ? (
            <Badge tone="amber">No role</Badge>
          ) : (
            row.roles.map((role) => (
              <Badge key={role.id} tone="slate">
                {role.name}
              </Badge>
            ))
          )}
        </div>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    {
      key: 'created',
      header: 'Created',
      render: (row) => <span className="text-xs text-slate-500">{formatDate(row.createdAt)}</span>,
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (row) => (
        <div className="flex items-center justify-end gap-1">
          <button
            onClick={() => openHistory(row)}
            className="flex h-8 w-8 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="Credential history"
            aria-label={`Credential history for ${row.username}`}
          >
            <History className="h-4 w-4" />
          </button>
          <button
            onClick={() => setResetTarget(row)}
            className="flex h-8 w-8 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="Reset password"
            aria-label={`Reset password for ${row.username}`}
          >
            <KeyRound className="h-4 w-4" />
          </button>
          <button
            onClick={() => openUsername(row)}
            className="flex h-8 w-8 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="Change username"
            aria-label={`Change username for ${row.username}`}
          >
            <Pencil className="h-4 w-4" />
          </button>
          <button
            onClick={() => setStatusTarget(row)}
            className={`flex h-8 w-8 items-center justify-center rounded-md transition hover:bg-slate-100 ${
              row.status === 'ACTIVE' ? 'text-amber-500' : 'text-emerald-500'
            }`}
            title={row.status === 'ACTIVE' ? 'Disable account' : 'Enable account'}
            aria-label={row.status === 'ACTIVE' ? `Disable ${row.username}` : `Enable ${row.username}`}
          >
            <Power className="h-4 w-4" />
          </button>
        </div>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        eyebrow="Platform console"
        title="Store login accounts"
        subtitle="Find a store's sign-in account, change usernames, control access and reset forgotten passwords."
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
            <SearchInput
              className="sm:w-80"
              placeholder="Search username, store name or code…"
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
          </div>
          <Badge tone="slate">{data?.totalElements ?? 0} accounts</Badge>
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
                  title="No store accounts found"
                  message="Try a different store name, store code or username."
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
                itemLabel="accounts"
              />
            )}
          </>
        )}
      </Card>

      <div className="mt-3 flex items-start gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] text-slate-500">
        <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-emerald-500" />
        Existing passwords and password hashes are never shown. A reset generates a new temporary password that is
        displayed once and stored only as a hash.
      </div>

      <Modal
        open={Boolean(usernameTarget)}
        onClose={() => setUsernameTarget(null)}
        title="Change login username"
        description={
          usernameTarget
            ? `${usernameTarget.fullName} · ${usernameTarget.storeName ?? 'Unknown store'}`
            : undefined
        }
        footer={
          <>
            <Button variant="outline" onClick={() => setUsernameTarget(null)} disabled={usernameSaving}>
              Cancel
            </Button>
            <Button onClick={saveUsername} loading={usernameSaving}>
              Save username
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field
            label="Username"
            required
            error={usernameError ?? undefined}
            hint="The user signs in with this username and their existing password."
          >
            <Input
              value={username}
              onChange={(event) => {
                setUsername(event.target.value)
                setUsernameError(null)
              }}
              autoComplete="off"
              placeholder="owner"
            />
          </Field>
          {usernameTarget && (
            <p className="rounded-lg bg-slate-50 px-3 py-2 text-[11px] text-slate-500">
              Store code <span className="font-mono font-semibold">{usernameTarget.storeCode ?? '—'}</span>. Usernames
              only need to be unique inside this store.
            </p>
          )}
        </div>
      </Modal>

      <Modal
        open={Boolean(historyTarget)}
        onClose={() => setHistoryTarget(null)}
        title="Credential change history"
        description={
          historyTarget
            ? `${historyTarget.fullName} · @${historyTarget.username}`
            : undefined
        }
        size="md"
        footer={
          <Button variant="outline" onClick={() => setHistoryTarget(null)}>
            Close
          </Button>
        }
      >
        {historyLoading ? (
          <LoadingState />
        ) : historyError ? (
          <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
            {historyError}
          </div>
        ) : history.length === 0 ? (
          <p className="rounded-lg bg-slate-50 px-3 py-3 text-xs text-slate-500">
            No credential changes have been recorded for this account.
          </p>
        ) : (
          <div className="max-h-80 space-y-2 overflow-y-auto">
            {history.map((entry) => {
              const meta = actionLabels[entry.action] ?? { label: entry.action, tone: 'slate' as const }
              return (
                <div key={entry.id} className="rounded-lg border border-slate-200 px-3 py-2.5 text-xs">
                  <div className="flex flex-wrap items-center justify-between gap-1">
                    <Badge tone={meta.tone}>{meta.label}</Badge>
                    <span className="text-[11px] text-slate-400">{formatDateTime(entry.createdAt)}</span>
                  </div>
                  <p className="mt-1.5 text-slate-600">{entry.details ?? 'No details recorded.'}</p>
                  <p className="mt-0.5 text-[11px] text-slate-400">
                    By {entry.actorUsername ?? 'Platform Administrator'}
                  </p>
                </div>
              )
            })}
          </div>
        )}
      </Modal>

      <Modal
        open={Boolean(resetResult)}
        onClose={() => setResetResult(null)}
        title="Temporary password generated"
        description="Give this password to the account owner through a secure channel."
        footer={
          <Button variant="outline" onClick={() => setResetResult(null)}>
            Close
          </Button>
        }
      >
        {resetResult && (
          <div className="space-y-4">
            <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
              <p>
                <span className="font-semibold text-slate-800">{resetResult.username}</span>
                {resetResult.storeCode && (
                  <>
                    {' '}· store code <span className="font-mono font-semibold">{resetResult.storeCode}</span>
                  </>
                )}
              </p>
              <p className="mt-0.5 text-[11px] text-slate-400">
                Generated {formatDateTime(resetResult.generatedAt)}
              </p>
            </div>
            <div>
              <p className="mb-1.5 text-[11px] font-semibold tracking-wide text-slate-500 uppercase">
                Temporary password
              </p>
              <div className="flex items-stretch gap-2">
                <code className="flex-1 select-all rounded-lg border border-slate-300 bg-white px-3 py-2.5 font-mono text-sm font-semibold tracking-wider text-slate-900">
                  {resetResult.temporaryPassword}
                </code>
                <Button variant="outline" icon={<Copy className="h-4 w-4" />} onClick={copyPassword}>
                  Copy
                </Button>
              </div>
            </div>
            <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[11px] font-medium text-amber-800">
              This password is shown only once and cannot be retrieved later. Ask the user to sign in and change it
              immediately. Only the hashed value is stored.
            </div>
          </div>
        )}
      </Modal>

      <ConfirmDialog
        open={Boolean(resetTarget)}
        onClose={() => setResetTarget(null)}
        onConfirm={confirmReset}
        title="Reset this account's password?"
        message={
          resetTarget
            ? `A new temporary password will replace the current password for @${resetTarget.username} (${resetTarget.storeName ?? 'unknown store'}). The existing password will stop working immediately.`
            : ''
        }
        confirmLabel="Reset password"
        tone="danger"
        loading={resetSaving}
      />

      <ConfirmDialog
        open={Boolean(statusTarget)}
        onClose={() => setStatusTarget(null)}
        onConfirm={confirmStatus}
        title={statusTarget?.status === 'ACTIVE' ? 'Disable this login account?' : 'Enable this login account?'}
        message={
          statusTarget?.status === 'ACTIVE'
            ? `@${statusTarget.username} (${statusTarget.storeName ?? 'unknown store'}) will be blocked from signing in. Their data and history are kept.`
            : `@${statusTarget?.username} will be able to sign in again with their existing password.`
        }
        confirmLabel={statusTarget?.status === 'ACTIVE' ? 'Disable account' : 'Enable account'}
        tone={statusTarget?.status === 'ACTIVE' ? 'danger' : 'primary'}
        loading={statusSaving}
      />
    </div>
  )
}
