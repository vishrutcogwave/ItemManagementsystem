import { AlertTriangle, CheckCircle2 } from 'lucide-react'
import { Badge, DataTable, type Column } from '../ui'
import { money, qty } from '../../lib/format'
import type { ImportAction, ImportPreview, ImportRow } from '../../types'

const actionLabels: Record<ImportAction, string> = {
  NEW_ITEM: 'New Item',
  EXISTING_ITEM_OPENING_STOCK: 'Existing Item – Opening Stock Update',
  EXISTING_ITEM_NO_STOCK: 'Existing Item – No Stock Change',
  DUPLICATE_ROW: 'Duplicate Row',
  VALIDATION_ERROR: 'Validation Error',
  ALREADY_IMPORTED: 'Already Imported',
}

const actionTones: Record<ImportAction, 'brand' | 'green' | 'slate' | 'amber' | 'red' | 'purple'> = {
  NEW_ITEM: 'brand',
  EXISTING_ITEM_OPENING_STOCK: 'green',
  EXISTING_ITEM_NO_STOCK: 'slate',
  DUPLICATE_ROW: 'amber',
  VALIDATION_ERROR: 'red',
  ALREADY_IMPORTED: 'purple',
}

const summaryItems: { key: keyof ImportPreview['summary']; label: string; tone?: string }[] = [
  { key: 'totalRows', label: 'Total Excel Rows' },
  { key: 'newCategories', label: 'New Categories' },
  { key: 'newItems', label: 'New Items' },
  { key: 'existingItems', label: 'Existing Items' },
  { key: 'stockUpdates', label: 'Stock Updates' },
  { key: 'duplicateRows', label: 'Duplicate Rows', tone: 'text-amber-600' },
  { key: 'invalidRows', label: 'Invalid Rows', tone: 'text-rose-600' },
  { key: 'alreadyImportedRows', label: 'Already Imported', tone: 'text-violet-600' },
  { key: 'importableRows', label: 'Importable Rows', tone: 'text-emerald-600' },
]

const columns: Column<ImportRow>[] = [
  { key: 'row', header: 'Excel Row', render: (row) => <span className="font-semibold text-slate-700">{row.rowNumber}</span> },
  { key: 'name', header: 'Item Name', render: (row) => row.itemName || <span className="text-rose-500">—</span> },
  { key: 'code', header: 'Item Code', render: (row) => row.itemCode || <span className="text-slate-400">Generated</span> },
  { key: 'category', header: 'Category', render: (row) => row.categoryName || <span className="text-rose-500">—</span> },
  { key: 'unit', header: 'Unit', render: (row) => row.unit || <span className="text-rose-500">—</span> },
  { key: 'rate', header: 'Rate', align: 'right', render: (row) => (row.rate === null ? '—' : money(row.rate)) },
  { key: 'opening', header: 'Opening Stock', align: 'right', render: (row) => (row.openingStock === null ? '—' : qty(row.openingStock)) },
  {
    key: 'action',
    header: 'Import Action',
    render: (row) => (
      <Badge tone={actionTones[row.importAction]}>{actionLabels[row.importAction] ?? row.importAction}</Badge>
    ),
  },
  {
    key: 'validation',
    header: 'Validation',
    render: (row) => (
      <Badge tone={row.validationStatus === 'VALID' ? 'green' : 'red'}>
        {row.validationStatus === 'VALID' ? 'Valid' : 'Invalid'}
      </Badge>
    ),
  },
  {
    key: 'error',
    header: 'Error Message',
    render: (row) =>
      row.errorMessage ? (
        <span className="text-xs text-rose-600">{row.errorMessage}</span>
      ) : (
        <span className="text-slate-400">—</span>
      ),
  },
]

export function ImportSummaryGrid({ preview }: { preview: ImportPreview }) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
      {summaryItems.map((item) => (
        <div key={item.key} className="rounded-lg border border-slate-200 bg-slate-50/70 px-3 py-2">
          <p className="text-[10px] font-semibold tracking-wide text-slate-500 uppercase">{item.label}</p>
          <p className={`mt-0.5 text-lg font-bold ${item.tone ?? 'text-slate-800'}`}>
            {preview.summary[item.key]}
          </p>
        </div>
      ))}
    </div>
  )
}

export function ImportStatusBanner({ preview }: { preview: ImportPreview }) {
  if (
    preview.status === 'PREVIEWED' &&
    preview.summary.alreadyImportedRows > 0 &&
    preview.summary.alreadyImportedRows === preview.summary.totalRows
  ) {
    return (
      <div className="flex items-start gap-2 rounded-lg border border-violet-200 bg-violet-50 px-3 py-2 text-xs text-violet-800">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
        <span>
          This file has already been imported for {preview.branchName}.{' '}
          {preview.rows[0]?.errorMessage ?? 'Upload a different file to import more stock.'}
        </span>
      </div>
    )
  }
  if (preview.status === 'COMPLETED') {
    return (
      <div className="flex items-start gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-800">
        <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
        <span>
          {preview.importNumber} completed on {preview.branchName}. Opening stock was posted through stock
          transactions, so purchases and sales remain accurate.
        </span>
      </div>
    )
  }
  if (preview.status === 'FAILED') {
    return (
      <div className="flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
        <span>
          {preview.importNumber} was rolled back. {preview.failureMessage ?? 'No data was imported.'} Upload a
          corrected file to try again.
        </span>
      </div>
    )
  }
  const blocked = preview.summary.invalidRows > 0 || preview.summary.duplicateRows > 0
  return (
    <div
      className={`flex items-start gap-2 rounded-lg border px-3 py-2 text-xs ${
        blocked
          ? 'border-amber-200 bg-amber-50 text-amber-800'
          : 'border-sky-200 bg-sky-50 text-sky-800'
      }`}
    >
      {blocked ? <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" /> : <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />}
      <span>
        {blocked
          ? 'This file has rows that cannot be imported. Correct the errors in Excel and upload the file again. Nothing is imported until every row is valid.'
          : 'Preview only — nothing has been written yet. Review the actions below, then confirm to import.'}
      </span>
    </div>
  )
}

export function ImportRowsTable({ preview }: { preview: ImportPreview }) {
  return (
    <div className="overflow-hidden rounded-lg border border-slate-200">
      <DataTable
        columns={columns}
        rows={preview.rows}
        rowKey={(row) => row.id}
        dense
        empty={<div className="px-4 py-6 text-center text-xs text-slate-400">No rows found in this file.</div>}
      />
    </div>
  )
}

export function ImportPreviewView({ preview }: { preview: ImportPreview }) {
  return (
    <div className="space-y-4">
      <ImportStatusBanner preview={preview} />
      <ImportSummaryGrid preview={preview} />
      <div>
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <h3 className="text-xs font-bold tracking-wider text-slate-500 uppercase">Row preview</h3>
          <p className="text-[11px] text-slate-400">
            {preview.fileName} · {preview.importNumber}
          </p>
        </div>
        <ImportRowsTable preview={preview} />
      </div>
    </div>
  )
}
