import { Printer, ReceiptText, RotateCcw } from 'lucide-react'
import { useState } from 'react'
import {
  Badge,
  Button,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  PageHeader,
  Pagination,
  Select,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { api, errorMessage } from '../lib/api'
import { formatDateTime } from '../lib/format'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import { listPrintJobs, reprintPrintJob } from '../services/printerService'
import type { PageResponse, PrintJob, Sale } from '../types'

function printTone(status: PrintJob['status']) {
  if (status === 'PRINTED') return 'green' as const
  if (status === 'FAILED') return 'red' as const
  return 'amber' as const
}

export function PrintHistoryPage() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const allBranches = useAuthStore((state) => state.allBranches)
  const branches = useAuthStore((state) => state.branches)
  const store = useAuthStore((state) => state.store)
  const canReprint = hasPermission('SALES_MANAGE')
  const storeInfo = { name: store?.storeName ?? 'Store', gstNumber: store?.gstNumber }

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [status, setStatus] = useState('')
  const [branchId, setBranchId] = useState<number | ''>('')
  const [busyId, setBusyId] = useState<number | null>(null)

  const { data, loading, error, reload } = useApi<PageResponse<PrintJob>>(
    () =>
      listPrintJobs({
        page,
        size,
        status: status || undefined,
        branchId: branchId || undefined,
      }),
    [page, size, status, branchId],
  )

  const reprint = async (job: PrintJob) => {
    if (!job.documentId) {
      notifyError('This print job has no receipt to reprint.')
      return
    }
    setBusyId(job.id)
    try {
      const { data: sale } = await api.get<Sale>(`/sales/${job.documentId}`)
      const outcome = await reprintPrintJob(job, sale, storeInfo)
      if (outcome.status === 'PRINTED') notify('Reprint sent to the printer')
      else if (outcome.status === 'DUPLICATE') notify('This receipt was already printed')
      else if (outcome.message) notifyError(outcome.message)
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }

  const columns: Column<PrintJob>[] = [
    {
      key: 'created',
      header: 'Requested',
      render: (row) => (
        <div>
          <p className="text-xs font-semibold text-slate-700">{formatDateTime(row.createdAt)}</p>
          <p className="text-[11px] text-slate-400">{row.createdBy || 'System'}</p>
        </div>
      ),
    },
    {
      key: 'document',
      header: 'Document',
      render: (row) => (
        <div className="flex items-center gap-2">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
            <ReceiptText className="h-4 w-4" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-xs font-semibold text-slate-800">
              {row.documentNumber || `Sale #${row.documentId ?? '—'}`}
            </p>
            <p className="text-[11px] text-slate-400">{row.documentType.replaceAll('_', ' ')}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'branch',
      header: 'Branch',
      render: (row) => <span className="text-xs text-slate-600">{row.branchName || '—'}</span>,
    },
    {
      key: 'printer',
      header: 'Printer',
      render: (row) => (
        <div className="max-w-56">
          <p className="truncate text-xs font-medium text-slate-700">
            {row.channel === 'WINDOWS' ? 'Windows default printer' : row.printerName || '—'}
          </p>
          <p className="text-[11px] text-slate-400">
            {row.channel === 'WINDOWS'
              ? 'Silent local print'
              : row.printerIpAddress
                ? `${row.printerIpAddress}:${row.printerPort}`
                : 'Silent local print'}
            {row.paperWidth ? ` · ${row.paperWidth === 'MM58' ? '58 mm' : '80 mm'}` : ''}
          </p>
        </div>
      ),
    },
    {
      key: 'channel',
      header: 'Bridge',
      render: (row) => (
        <div className="flex flex-col items-start gap-1">
          <Badge tone="blue">{row.channel}</Badge>
          <span className="text-[10px] font-semibold tracking-wide text-slate-400 uppercase">{row.trigger}</span>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <div className="max-w-56">
          <Badge tone={printTone(row.status)}>{row.status}</Badge>
          <p className="mt-1 text-[11px] text-slate-400">
            {row.attempts} {row.attempts === 1 ? 'attempt' : 'attempts'}
          </p>
          {row.errorMessage && <p className="mt-0.5 line-clamp-2 text-[11px] text-rose-600">{row.errorMessage}</p>}
        </div>
      ),
    },
    ...(canReprint
      ? [
          {
            key: 'actions',
            header: '',
            align: 'right' as const,
            render: (row: PrintJob) => (
              <Button
                size="sm"
                variant="outline"
                icon={<RotateCcw className="h-3.5 w-3.5" />}
                loading={busyId === row.id}
                onClick={() => void reprint(row)}
              >
                Reprint
              </Button>
            ),
          },
        ]
      : []),
  ]

  return (
    <div>
      <PageHeader
        eyebrow="Silent printing"
        title="Print history"
        subtitle="Every automatic and manual receipt sent to a print bridge, with its printer, result and failures."
        actions={
          <Badge tone="brand">
            <span className="flex items-center gap-1">
              <Printer className="h-3.5 w-3.5" /> Thermal receipts
            </span>
          </Badge>
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <Select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value)
                setPage(0)
              }}
              aria-label="Status filter"
              className="w-full sm:w-44"
            >
              <option value="">All statuses</option>
              <option value="PENDING">Pending</option>
              <option value="PRINTED">Printed</option>
              <option value="FAILED">Failed</option>
            </Select>
            {allBranches && (
              <Select
                value={branchId}
                onChange={(event) => {
                  setBranchId(event.target.value ? Number(event.target.value) : '')
                  setPage(0)
                }}
                aria-label="Branch filter"
                className="w-full sm:w-52"
              >
                <option value="">All branches</option>
                {branches.map((branch) => (
                  <option key={branch.id} value={branch.id}>
                    {branch.branchName}
                  </option>
                ))}
              </Select>
            )}
            <Badge tone="slate">{data?.totalElements ?? 0} jobs</Badge>
          </div>
          <Button size="sm" variant="outline" onClick={reload}>
            Refresh
          </Button>
        </div>

        {error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : (
          <DataTable
            columns={columns}
            rows={data?.content ?? []}
            rowKey={(row) => row.id}
            loading={loading}
            empty={
              <EmptyState
                icon={<Printer className="h-6 w-6" />}
                title="No print jobs yet"
                message="Completed sales are queued here automatically once a print bridge is active for the branch."
              />
            }
          />
        )}

        {data && data.totalElements > 0 && (
          <Pagination
            page={page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            size={size}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next)
              setPage(0)
            }}
            itemLabel="print jobs"
          />
        )}
      </Card>
    </div>
  )
}
