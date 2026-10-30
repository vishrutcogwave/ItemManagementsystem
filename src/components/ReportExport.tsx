import { Download, FileText, Printer } from 'lucide-react'
import { Button } from './ui'
import { exportExcel, exportPdf, printTable, type ExportColumn } from '../lib/export'

interface ReportExportProps<T> {
  title: string
  filename: string
  subtitle?: string
  columns: ExportColumn<T>[]
  rows: T[]
  summary?: string[]
}

export function ReportExport<T>({ title, filename, subtitle, columns, rows, summary }: ReportExportProps<T>) {
  const disabled = rows.length === 0
  return (
    <>
      <Button
        variant="outline"
        size="sm"
        icon={<Download className="h-3.5 w-3.5" />}
        disabled={disabled}
        onClick={() => exportExcel(filename, title, columns, rows)}
      >
        Excel
      </Button>
      <Button
        variant="outline"
        size="sm"
        icon={<FileText className="h-3.5 w-3.5" />}
        disabled={disabled}
        onClick={() => exportPdf({ title, filename, subtitle, columns, rows, summary })}
      >
        PDF
      </Button>
      <Button
        variant="outline"
        size="sm"
        icon={<Printer className="h-3.5 w-3.5" />}
        disabled={disabled}
        onClick={() => printTable({ title, subtitle, columns, rows, summary })}
      >
        Print
      </Button>
    </>
  )
}
