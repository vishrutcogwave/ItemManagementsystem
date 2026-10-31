import { FileSpreadsheet, History } from 'lucide-react'
import { useState } from 'react'
import {
  Badge,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  LoadingState,
  Modal,
  PageHeader,
  Pagination,
  type Column,
} from '../components/ui'
import { ImportPreviewView } from '../components/imports/ImportPreviewView'
import { useApi } from '../hooks/useApi'
import { api, errorMessage } from '../lib/api'
import { formatDateTime } from '../lib/format'
import type { ImportBatch, ImportPreview, PageResponse } from '../types'

const statusTones: Record<ImportBatch['status'], 'amber' | 'green' | 'red'> = {
  PREVIEWED: 'amber',
  COMPLETED: 'green',
  FAILED: 'red',
}

const statusLabels: Record<ImportBatch['status'], string> = {
  PREVIEWED: 'Awaiting confirmation',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
}

export function ItemImportsPage() {
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [detailId, setDetailId] = useState<number | null>(null)
  const [detail, setDetail] = useState<ImportPreview | null>(null)
  const [detailError, setDetailError] = useState<string | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  const { data, loading, error, reload } = useApi<PageResponse<ImportBatch>>(
    () => api.get('/item-imports', { params: { page, size } }).then((response) => response.data),
    [page, size],
  )

  const openDetail = async (batch: ImportBatch) => {
    setDetailId(batch.batchId)
    setDetail(null)
    setDetailError(null)
    setDetailLoading(true)
    try {
      const response = await api.get<ImportPreview>(`/item-imports/${batch.batchId}`)
      setDetail(response.data)
    } catch (err) {
      setDetailError(errorMessage(err))
    } finally {
      setDetailLoading(false)
    }
  }

  const closeDetail = () => {
    setDetailId(null)
    setDetail(null)
    setDetailError(null)
  }

  const columns: Column<ImportBatch>[] = [
    {
      key: 'import',
      header: 'Import',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
            <FileSpreadsheet className="h-4 w-4" />
          </span>
          <div>
            <p className="font-semibold text-slate-800">{row.importNumber}</p>
            <p className="text-[11px] text-slate-400">{row.fileName}</p>
          </div>
        </div>
      ),
    },
    { key: 'branch', header: 'Branch', render: (row) => row.branchName },
    {
      key: 'uploaded',
      header: 'Uploaded',
      render: (row) => (
        <div>
          <p className="text-xs font-medium text-slate-700">{row.uploadedBy}</p>
          <p className="text-[11px] text-slate-400">{formatDateTime(row.createdAt)}</p>
        </div>
      ),
    },
    {
      key: 'rows',
      header: 'Rows',
      align: 'right',
      render: (row) => (
        <span className="text-xs">
          <span className="font-semibold text-slate-800">{row.successRows}</span>
          <span className="text-slate-400"> / {row.totalRows} imported</span>
          {row.failedRows > 0 && <span className="ml-1 text-rose-600">({row.failedRows} blocked)</span>}
        </span>
      ),
    },
    { key: 'newItems', header: 'New items', align: 'right', render: (row) => row.newItems },
    { key: 'stockUpdates', header: 'Stock updates', align: 'right', render: (row) => row.stockUpdates },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <div>
          <Badge tone={statusTones[row.status]}>{statusLabels[row.status]}</Badge>
          {row.failureMessage && (
            <p className="mt-1 max-w-56 truncate text-[11px] text-rose-600" title={row.failureMessage}>
              {row.failureMessage}
            </p>
          )}
        </div>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="Import history"
        subtitle="Every Excel upload, its validation result and the opening stock it posted."
      />

      <Card>
        {error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={data?.content ?? []}
              rowKey={(row) => row.batchId}
              loading={loading}
              onRowClick={openDetail}
              empty={
                <EmptyState
                  icon={<History className="h-6 w-6" />}
                  title="No imports yet"
                  message="Upload an Excel file from the Items page to see the import history here."
                />
              }
            />
            {data && data.totalElements > 0 && (
              <Pagination
                page={page}
                size={size}
                totalPages={data.totalPages}
                totalElements={data.totalElements}
                onPageChange={setPage}
                onSizeChange={(value) => {
                  setSize(value)
                  setPage(0)
                }}
                itemLabel="imports"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={detailId !== null}
        onClose={closeDetail}
        title={detail ? `${detail.importNumber} · ${detail.branchName}` : 'Import details'}
        description={detail ? `${detail.fileName} · uploaded ${formatDateTime(detail.createdAt)}` : undefined}
        size="full"
      >
        {detailLoading ? (
          <LoadingState />
        ) : detailError ? (
          <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
            {detailError}
          </div>
        ) : detail ? (
          <ImportPreviewView preview={detail} />
        ) : null}
      </Modal>
    </div>
  )
}
