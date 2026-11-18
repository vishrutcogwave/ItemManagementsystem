import { ReceiptText, RefreshCw, SearchX } from 'lucide-react'
import { useState } from 'react'
import { useApi } from '../../hooks/useApi'
import { useDebounce } from '../../hooks/useDebounce'
import { api } from '../../lib/api'
import { formatDate, money, today } from '../../lib/format'
import { Badge, Card, EmptyState, ErrorState, Input, SearchInput, Spinner, StatusBadge } from '../ui'
import { useAuthStore } from '../../store/auth'
import type { PageResponse, Sale, SaleStatus } from '../../types'

interface BillLookupProps {
  status?: SaleStatus
  selectedId?: number | null
  onSelect: (sale: Sale) => void
}

/** Shared bill finder for the modification and cancellation screens. */
export function BillLookup({ status = 'COMPLETED', selectedId, onSelect }: BillLookupProps) {
  const branchId = useAuthStore((state) => state.selectedBranchId)
  const [search, setSearch] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<Sale>>(
    () =>
      api
        .get('/sales', {
          params: {
            branchId: branchId ?? undefined,
            status,
            from: from || undefined,
            to: to || undefined,
            search: debouncedSearch || undefined,
            page: 0,
            size: 8,
          },
        })
        .then((response) => response.data),
    [branchId, status, from, to, debouncedSearch],
  )

  const rows = data?.content ?? []

  return (
    <Card>
      <div className="border-b border-slate-100 p-4">
        <div className="flex flex-col gap-3 xl:flex-row xl:items-center">
          <SearchInput
            className="xl:w-80"
            placeholder="Bill number, customer name or mobile…"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <div className="flex flex-wrap items-center gap-2">
            <Input
              type="date"
              aria-label="Bills from"
              className="w-36 min-w-0 flex-1 sm:flex-none"
              value={from}
              max={to || today()}
              onChange={(event) => setFrom(event.target.value)}
            />
            <span className="text-xs text-slate-400">to</span>
            <Input
              type="date"
              aria-label="Bills to"
              className="w-36 min-w-0 flex-1 sm:flex-none"
              value={to}
              min={from}
              max={today()}
              onChange={(event) => setTo(event.target.value)}
            />
            {(search || from || to) && (
              <button
                onClick={() => {
                  setSearch('')
                  setFrom('')
                  setTo('')
                }}
                className="rounded-lg px-2 py-1 text-xs font-semibold text-slate-500 hover:bg-slate-100 hover:text-slate-700"
              >
                Clear
              </button>
            )}
          </div>
          <button
            onClick={reload}
            className="ml-auto inline-flex items-center gap-1.5 self-start rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50"
            aria-label="Refresh bill list"
          >
            <RefreshCw className="h-3.5 w-3.5" /> Refresh
          </button>
        </div>
      </div>

      {error ? (
        <ErrorState message={error} onRetry={reload} />
      ) : loading && rows.length === 0 ? (
        <div className="flex min-h-32 items-center justify-center">
          <Spinner className="h-6 w-6" />
        </div>
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<SearchX className="h-6 w-6" />}
          title="No matching bills"
          message="Try another bill number, a customer name, a mobile number or a wider date range."
        />
      ) : (
        <ul className="divide-y divide-slate-100">
          {rows.map((sale) => (
            <li key={sale.id}>
              <button
                onClick={() => onSelect(sale)}
                className={`flex w-full flex-col gap-2 px-4 py-3 text-left transition sm:flex-row sm:items-center sm:justify-between ${
                  selectedId === sale.id ? 'bg-brand-50/70' : 'hover:bg-slate-50'
                }`}
              >
                <div className="flex min-w-0 items-center gap-3">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600">
                    <ReceiptText className="h-4 w-4" />
                  </span>
                  <div className="min-w-0">
                    <p className="truncate text-sm font-bold text-slate-800">{sale.salesNumber}</p>
                    <p className="truncate text-[11px] text-slate-500">
                      {formatDate(sale.salesDate)} · {sale.customerName || 'Walk-in'}
                      {sale.customerMobile ? ` · ${sale.customerMobile}` : ''}
                    </p>
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-2 sm:justify-end">
                  <Badge tone="slate">{sale.branchName}</Badge>
                  {sale.modificationCount > 0 && (
                    <Badge tone="amber">{sale.modificationCount} modified</Badge>
                  )}
                  <StatusBadge status={sale.status} />
                  <span className="text-sm font-bold text-slate-800">{money(sale.grandTotal)}</span>
                </div>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
