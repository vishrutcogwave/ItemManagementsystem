import { FileSpreadsheet, Upload } from 'lucide-react'
import { useRef, useState } from 'react'
import { Button, Field, Modal, Select } from '../ui'
import { api, errorMessage } from '../../lib/api'
import { useAuthStore } from '../../store/auth'
import { notify } from '../../store/ui'
import type { ImportPreview } from '../../types'
import { ImportPreviewView } from './ImportPreviewView'

interface ItemImportDialogProps {
  open: boolean
  onClose: () => void
  onImported: () => void
}

export function ItemImportDialog({ open, onClose, onImported }: ItemImportDialogProps) {
  const branches = useAuthStore((state) => state.branches)
  const selectedBranchId = useAuthStore((state) => state.selectedBranchId)
  const fileInput = useRef<HTMLInputElement>(null)

  const [branchId, setBranchId] = useState<number | ''>(
    () => selectedBranchId ?? branches[0]?.id ?? '',
  )
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [busy, setBusy] = useState<'preview' | 'confirm' | null>(null)
  const [error, setError] = useState<string | null>(null)

  const canConfirm =
    preview?.status === 'PREVIEWED' &&
    preview.summary.invalidRows === 0 &&
    preview.summary.duplicateRows === 0 &&
    preview.summary.importableRows > 0

  const startPreview = async () => {
    if (!file || branchId === '') {
      setError('Select a branch and an Excel file to continue.')
      return
    }
    setBusy('preview')
    setError(null)
    try {
      const form = new FormData()
      form.append('file', file)
      form.append('branchId', String(branchId))
      const { data } = await api.post<ImportPreview>('/item-imports/preview', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      setPreview(data)
    } catch (err) {
      setError(errorMessage(err))
      setPreview(null)
    } finally {
      setBusy(null)
    }
  }

  const confirmImport = async () => {
    if (!preview) return
    setBusy('confirm')
    setError(null)
    try {
      const { data } = await api.post<ImportPreview>(`/item-imports/${preview.batchId}/confirm`)
      setPreview(data)
      notify('Import completed')
      onImported()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  const resetFile = () => {
    setFile(null)
    setPreview(null)
    setError(null)
    if (fileInput.current) fileInput.current.value = ''
  }

  return (
    <Modal
      open={open}
      onClose={() => {
        if (busy) return
        onClose()
      }}
      title="Import items from Excel"
      description="Upload the item template to create categories and items and post opening stock for the selected branch."
      size="full"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={Boolean(busy)}>
            {preview?.status === 'COMPLETED' ? 'Close' : 'Cancel'}
          </Button>
          {preview && preview.status === 'PREVIEWED' && (
            <Button
              icon={<Upload className="h-4 w-4" />}
              onClick={confirmImport}
              loading={busy === 'confirm'}
              disabled={!canConfirm || Boolean(busy)}
            >
              Confirm import
            </Button>
          )}
        </>
      }
    >
      <div className="space-y-4">
        {error && (
          <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
            {error}
          </div>
        )}

        {!preview && (
          <div className="grid gap-4 lg:grid-cols-[280px_1fr]">
            <div className="space-y-4">
              <Field label="Import into branch" required hint="Opening stock is posted to this branch only">
                <Select
                  value={branchId}
                  onChange={(event) => setBranchId(event.target.value ? Number(event.target.value) : '')}
                >
                  <option value="">Select branch</option>
                  {branches.map((branch) => (
                    <option key={branch.id} value={branch.id}>
                      {branch.branchName} ({branch.branchCode})
                    </option>
                  ))}
                </Select>
              </Field>
              <div className="rounded-lg border border-slate-200 bg-slate-50/70 p-3 text-[11px] leading-relaxed text-slate-500">
                Categories and items are shared at store level. Only the selected branch receives opening
                stock. Rows are validated before anything is written.
              </div>
            </div>

            <div>
              <label
                className="flex cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-slate-300 bg-white px-4 py-10 text-center transition hover:border-brand-400 hover:bg-brand-50/30"
                htmlFor="item-import-file"
              >
                <span className="flex h-11 w-11 items-center justify-center rounded-full bg-brand-50 text-brand-600">
                  <FileSpreadsheet className="h-5 w-5" />
                </span>
                <span className="text-sm font-semibold text-slate-700">
                  {file ? file.name : 'Choose an Excel file'}
                </span>
                <span className="text-[11px] text-slate-400">.xlsx or .xls · up to 10 MB · maximum 2000 rows</span>
                <input
                  ref={fileInput}
                  id="item-import-file"
                  type="file"
                  accept=".xlsx,.xls"
                  className="sr-only"
                  onChange={(event) => {
                    setFile(event.target.files?.[0] ?? null)
                    setError(null)
                  }}
                />
              </label>
              <div className="mt-3 flex justify-end">
                <Button onClick={startPreview} loading={busy === 'preview'} disabled={!file || branchId === ''}>
                  Validate & preview
                </Button>
              </div>
            </div>
          </div>
        )}

        {preview && (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-slate-200 bg-slate-50/70 px-3 py-2 text-xs text-slate-600">
              <span>
                {preview.fileName} · {preview.importNumber} · {preview.branchName}
              </span>
              {preview.status === 'PREVIEWED' && (
                <Button size="sm" variant="ghost" onClick={resetFile} disabled={Boolean(busy)}>
                  Upload a different file
                </Button>
              )}
            </div>
            <ImportPreviewView preview={preview} />
          </div>
        )}
      </div>
    </Modal>
  )
}
