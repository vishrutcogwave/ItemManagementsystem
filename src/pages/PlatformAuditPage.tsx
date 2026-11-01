import { Download, ScrollText } from 'lucide-react'
import { useMemo, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  Input,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage } from '../lib/api'
import { formatDateTime } from '../lib/format'
import { notifyError } from '../store/ui'
import type { AuditLogEntry, PageResponse, Store } from '../types'

interface AuditFilters {
  storeId: string
  action: string
  entityType: string
  username: string
  search: string
  from: string
  to: string
}

const emptyFilters: AuditFilters = {
  storeId: '',
  action: '',
  entityType: '',
  username: '',
  search: '',
  from: '',
  to: '',
}

const actionTone: Record<string, 'green' | 'amber' | 'blue' | 'red' | 'slate' | 'purple'> = {
  LOGIN: 'green',
  LOGOUT: 'slate',
  REGISTER_STORE: 'blue',
  PASSWORD_CHANGE: 'amber',
  PASSWORD_RESET: 'red',
  PASSWORD_RESET_REQUEST: 'amber',
  USERNAME_UPDATE: 'blue',
  ACTIVATE: 'green',
  DEACTIVATE: 'red',
}

function startOfDay(value: string): string | undefined {
  if (!value) return undefined
  const date = new Date(`${value}T00:00:00`)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString()
}

function endOfDay(value: string): string | undefined {
  if (!value) return undefined
  const date = new Date(`${value}T23:59:59.999`)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString()
}

function filterParams(filters: AuditFilters) {
  return {
    storeId: filters.storeId ? Number(filters.storeId) : undefined,
    action: filters.action || undefined,
    entityType: filters.entityType || undefined,
    username: filters.username.trim() || undefined,
    search: filters.search.trim() || undefined,
    from: startOfDay(filters.from),
    to: endOfDay(filters.to),
  }
}

export function PlatformAuditPage() {
  const [filters, setFilters] = useState<AuditFilters>(emptyFilters)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)
  const [exporting, setExporting] = useState(false)

  const debouncedSearch = useDebounce(filters.search)
  const debouncedUsername = useDebounce(filters.username)

  const effectiveFilters = useMemo(
    () => ({ ...filters, search: debouncedSearch, username: debouncedUsername }),
    [filters, debouncedSearch, debouncedUsername],
  )

  const { data, loading, error, reload } = useApi<PageResponse<AuditLogEntry>>(
    () =>
      api
        .get('/platform/audit-logs', {
          params: { ...filterParams(effectiveFilters), page, size },
        })
        .then((response) => response.data),
    [
      effectiveFilters.storeId,
      effectiveFilters.action,
      effectiveFilters.entityType,
      debouncedUsername,
      debouncedSearch,
      effectiveFilters.from,
      effectiveFilters.to,
      page,
      size,
    ],
  )

  const { data: actions } = useApi<string[]>(
    () => api.get<string[]>('/platform/audit-logs/options').then((response) => response.data),
    [],
  )

  const { data: entityTypes } = useApi<string[]>(
    () => api.get<string[]>('/platform/audit-logs/entity-types').then((response) => response.data),
    [],
  )

  const { data: stores } = useApi<PageResponse<Store>>(
    () => api.get<PageResponse<Store>>('/stores', { params: { page: 0, size: 100 } }).then((response) => response.data),
    [],
  )

  const update = (patch: Partial<AuditFilters>) => {
    setFilters((current) => ({ ...current, ...patch }))
    setPage(0)
  }

  const reset = () => {
    setFilters(emptyFilters)
    setPage(0)
  }

  const exportCsv = async () => {
    setExporting(true)
    try {
      const response = await api.get('/platform/audit-logs/export', {
        params: filterParams({ ...effectiveFilters }),
        responseType: 'blob',
      })
      const blobUrl = URL.createObjectURL(response.data as Blob)
      const link = document.createElement('a')
      link.href = blobUrl
      link.download = `audit-log-${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(blobUrl)
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setExporting(false)
    }
  }

  const columns: Column<AuditLogEntry>[] = [
    {
      key: 'createdAt',
      header: 'When',
      render: (row) => (
        <span className="text-xs whitespace-nowrap text-slate-500">{formatDateTime(row.createdAt)}</span>
      ),
    },
    {
      key: 'action',
      header: 'Action',
      render: (row) => <Badge tone={actionTone[row.action] ?? 'slate'}>{row.action}</Badge>,
    },
    {
      key: 'store',
      header: 'Store',
      render: (row) =>
        row.storeId ? (
          <div className="text-xs">
            <p className="font-medium text-slate-700">{row.storeName ?? '—'}</p>
            <p className="font-mono text-[11px] tracking-wide text-slate-400">{row.storeCode ?? '—'}</p>
          </div>
        ) : (
          <span className="text-xs text-slate-400">Platform</span>
        ),
    },
    {
      key: 'user',
      header: 'User',
      render: (row) => (
        <span className="text-xs font-medium text-slate-600">{row.username ?? 'System'}</span>
      ),
    },
    {
      key: 'entity',
      header: 'Entity',
      render: (row) =>
        row.entityType ? (
          <div className="text-xs">
            <p className="font-medium text-slate-700">{row.entityType}</p>
            {row.entityId != null && <p className="text-[11px] text-slate-400">#{row.entityId}</p>}
          </div>
        ) : (
          <span className="text-xs text-slate-400">—</span>
        ),
    },
    {
      key: 'details',
      header: 'Details',
      className: 'max-w-90',
      render: (row) => (
        <p className="text-xs leading-relaxed break-words text-slate-600">{row.details ?? '—'}</p>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        eyebrow="Platform console"
        title="Audit log"
        subtitle="Review security-sensitive actions across stores. Entries are append-only and never edited or deleted."
        actions={
          <Button
            variant="outline"
            icon={<Download className="h-4 w-4" />}
            loading={exporting}
            onClick={exportCsv}
          >
            Export CSV
          </Button>
        }
      />

      <Card>
        <div className="space-y-3 border-b border-slate-100 p-4">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <SearchInput
              className="sm:w-72"
              placeholder="Search details or action…"
              value={filters.search}
              onChange={(event) => update({ search: event.target.value })}
            />
            <Input
              className="sm:w-48"
              placeholder="Username"
              value={filters.username}
              onChange={(event) => update({ username: event.target.value })}
              aria-label="Filter by username"
            />
            <Select
              className="sm:w-44"
              value={filters.storeId}
              onChange={(event) => update({ storeId: event.target.value })}
              aria-label="Filter by store"
            >
              <option value="">All stores</option>
              {(stores?.content ?? []).map((store) => (
                <option key={store.id} value={store.id}>
                  {store.storeName} ({store.storeCode})
                </option>
              ))}
            </Select>
          </div>

          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <Select
              className="sm:w-44"
              value={filters.action}
              onChange={(event) => update({ action: event.target.value })}
              aria-label="Filter by action"
            >
              <option value="">All actions</option>
              {(actions ?? []).map((action) => (
                <option key={action} value={action}>
                  {action}
                </option>
              ))}
            </Select>
            <Select
              className="sm:w-44"
              value={filters.entityType}
              onChange={(event) => update({ entityType: event.target.value })}
              aria-label="Filter by entity type"
            >
              <option value="">All entities</option>
              {(entityTypes ?? []).map((entityType) => (
                <option key={entityType} value={entityType}>
                  {entityType}
                </option>
              ))}
            </Select>
            <div className="flex items-center gap-2">
              <Input
                type="date"
                className="sm:w-40"
                value={filters.from}
                max={filters.to || undefined}
                onChange={(event) => update({ from: event.target.value })}
                aria-label="From date"
              />
              <span className="text-xs text-slate-400">to</span>
              <Input
                type="date"
                className="sm:w-40"
                value={filters.to}
                min={filters.from || undefined}
                onChange={(event) => update({ to: event.target.value })}
                aria-label="To date"
              />
            </div>
            <Button variant="ghost" size="sm" onClick={reset} className="sm:ml-auto">
              Reset filters
            </Button>
          </div>
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
                  icon={<ScrollText className="h-6 w-6" />}
                  title="No audit entries match these filters"
                  message="Widen the date range or clear a filter to see more activity."
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
                itemLabel="entries"
              />
            )}
          </>
        )}
      </Card>

      <div className="mt-3 flex items-start gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] text-slate-500">
        <ScrollText className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-400" />
        Export downloads the rows matching the current filters as CSV. Passwords, tokens and other credentials are
        never written to audit details.
      </div>
    </div>
  )
}
