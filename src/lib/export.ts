export interface ExportColumn<T> {
  header: string
  value: (row: T) => string | number
  width?: number
}

function fileStem(name: string): string {
  return name.replace(/[\\/:*?"<>|]+/g, '-').trim()
}

export async function exportExcel<T>(
  filename: string,
  sheetName: string,
  columns: ExportColumn<T>[],
  rows: T[],
): Promise<void> {
  const XLSX = await import('xlsx')
  const data = rows.map((row) => {
    const entry: Record<string, string | number> = {}
    for (const column of columns) entry[column.header] = column.value(row)
    return entry
  })

  const sheet = XLSX.utils.json_to_sheet(data, { header: columns.map((column) => column.header) })
  const widths = columns.map((column) => ({ wch: column.width ?? Math.max(12, column.header.length + 4) }))
  sheet['!cols'] = widths

  const book = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(book, sheet, sheetName.slice(0, 31))
  XLSX.writeFile(book, `${fileStem(filename)}.xlsx`)
}

interface PdfOptions<T> {
  title: string
  filename: string
  columns: ExportColumn<T>[]
  rows: T[]
  subtitle?: string
  orientation?: 'portrait' | 'landscape'
  summary?: string[]
}

export async function exportPdf<T>({
  title,
  filename,
  columns,
  rows,
  subtitle,
  orientation = 'landscape',
  summary = [],
}: PdfOptions<T>): Promise<void> {
  const [{ default: jsPDF }, { default: autoTable }] = await Promise.all([
    import('jspdf'),
    import('jspdf-autotable'),
  ])
  const doc = new jsPDF({ orientation, unit: 'pt', format: 'a4' })
  const pageWidth = doc.internal.pageSize.getWidth()

  doc.setFontSize(16)
  doc.setFont('helvetica', 'bold')
  doc.text(title, 40, 44)

  doc.setFontSize(9)
  doc.setFont('helvetica', 'normal')
  doc.setTextColor(110)
  if (subtitle) doc.text(subtitle, 40, 60)
  const stamp = `Generated ${new Date().toLocaleString('en-IN')}`
  doc.text(stamp, pageWidth - 40, 60, { align: 'right' })

  let startY = 76
  if (summary.length > 0) {
    doc.setTextColor(60)
    doc.setFontSize(10)
    doc.text(summary.join('    |    '), 40, startY)
    startY += 16
  }

  autoTable(doc, {
    startY,
    head: [columns.map((column) => column.header)],
    body: rows.map((row) => columns.map((column) => String(column.value(row)))),
    styles: { fontSize: 8, cellPadding: 5, textColor: [45, 55, 72] },
    headStyles: { fillColor: [234, 88, 12], textColor: 255, fontStyle: 'bold' },
    alternateRowStyles: { fillColor: [250, 250, 252] },
    margin: { left: 40, right: 40 },
  })

  doc.setFontSize(8)
  doc.setTextColor(140)
  const pages = doc.getNumberOfPages()
  for (let index = 1; index <= pages; index += 1) {
    doc.setPage(index)
    doc.text(`Item Management System · Page ${index} of ${pages}`, pageWidth / 2, doc.internal.pageSize.getHeight() - 20, {
      align: 'center',
    })
  }

  doc.save(`${fileStem(filename)}.pdf`)
}

function escape(value: unknown): string {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
}

interface PrintTableOptions<T> {
  title: string
  subtitle?: string
  columns: ExportColumn<T>[]
  rows: T[]
  summary?: string[]
}

export function printTable<T>({ title, subtitle, columns, rows, summary = [] }: PrintTableOptions<T>): void {
  const head = columns.map((column) => `<th>${escape(column.header)}</th>`).join('')
  const body = rows
    .map(
      (row) =>
        `<tr>${columns
          .map((column) => `<td>${escape(column.value(row))}</td>`)
          .join('')}</tr>`,
    )
    .join('')

  printHtml(
    `<div class="title-row">
      <div><p class="brand">Item Management System</p><h1>${escape(title)}</h1>${subtitle ? `<p class="muted">${escape(subtitle)}</p>` : ''}</div>
      <p class="muted">${new Date().toLocaleString('en-IN')}</p>
    </div>
    ${summary.length > 0 ? `<p class="muted">${summary.map(escape).join(' &nbsp;|&nbsp; ')}</p>` : ''}
    <table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>
    <p class="muted" style="margin-top:14px">${rows.length} rows</p>`,
    title,
  )
}

export function printHtml(html: string, title: string): void {
  const frame = document.createElement('iframe')
  frame.style.position = 'fixed'
  frame.style.right = '0'
  frame.style.bottom = '0'
  frame.style.width = '0'
  frame.style.height = '0'
  frame.style.border = '0'
  document.body.appendChild(frame)

  const doc = frame.contentWindow?.document
  if (!doc) return

  doc.open()
  doc.write(`<!doctype html><html><head><title>${title}</title><style>
    * { box-sizing: border-box; }
    body { margin: 0; padding: 24px; font-family: 'Segoe UI', Arial, sans-serif; color: #1f2937; }
    h1, h2, h3 { margin: 0 0 6px; }
    table { width: 100%; border-collapse: collapse; margin-top: 16px; font-size: 12px; }
    th { background: #f3f4f6; text-align: left; padding: 8px; border-bottom: 1px solid #d1d5db; }
    td { padding: 7px 8px; border-bottom: 1px solid #e5e7eb; }
    .muted { color: #6b7280; }
    .right { text-align: right; }
    .title-row { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
    .brand { color: #ea580c; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; font-size: 11px; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; font-size: 12px; }
    .box { border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 12px; }
    .totals { margin-top: 14px; margin-left: auto; width: 260px; font-size: 12px; }
    .totals div { display: flex; justify-content: space-between; padding: 3px 0; }
    .totals .grand { border-top: 1px solid #d1d5db; margin-top: 4px; padding-top: 6px; font-weight: 700; font-size: 14px; }
    @media print { body { padding: 0; } }
  </style></head><body>${html}</body></html>`)
  doc.close()

  frame.contentWindow?.focus()
  setTimeout(() => {
    frame.contentWindow?.print()
    setTimeout(() => frame.remove(), 1000)
  }, 250)
}
