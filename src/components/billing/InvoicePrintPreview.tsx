import { Download, Printer } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { Sale } from '../../types'
import { printDocument } from '../../lib/export'
import {
  buildInvoiceHtml,
  invoicePreviewSize,
  invoicePaperMm,
  type InvoicePaper,
  type InvoicePrintSettings,
  type InvoicePaperKind,
  type InvoiceTemplateKind,
  type InvoiceStoreInfo,
} from '../../lib/invoiceTemplate'
import { detectPlatform } from '../../services/printerService'
import { Button, Field, Input, Modal, Select } from '../ui'

interface InvoicePrintPreviewProps {
  open: boolean
  sale: Sale | null
  store: InvoiceStoreInfo
  cancellationNumber?: string | null
  cancellationReason?: string | null
  savedSilentSettings?: InvoicePrintSettings | null
  onSaveSilentSettings?: (settings: InvoicePrintSettings | null) => void
  onClose: () => void
}

const PAPER_OPTIONS: Array<{ value: InvoicePaperKind; label: string }> = [
  { value: 'A4', label: 'A4 — laser / inkjet' },
  { value: 'A5', label: 'A5 — small laser / inkjet' },
  { value: 'MM80', label: '80 mm — thermal roll' },
  { value: 'MM58', label: '58 mm — thermal roll' },
  { value: 'CUSTOM', label: 'Custom size' },
]

export function InvoicePrintPreview({
  open,
  sale,
  store,
  cancellationNumber,
  cancellationReason,
  savedSilentSettings,
  onSaveSilentSettings,
  onClose,
}: InvoicePrintPreviewProps) {
  const [template, setTemplate] = useState<InvoiceTemplateKind>(savedSilentSettings?.template ?? 'RETAIL')
  const [paperKind, setPaperKind] = useState<InvoicePaperKind>(savedSilentSettings?.paper.kind ?? 'A4')
  const [orientation, setOrientation] = useState<'portrait' | 'landscape'>(savedSilentSettings?.paper.orientation ?? 'portrait')
  const [customWidth, setCustomWidth] = useState(String(savedSilentSettings?.paper.widthMm ?? 210))
  const [customHeight, setCustomHeight] = useState(String(typeof savedSilentSettings?.paper.heightMm === 'number' ? savedSilentSettings.paper.heightMm : 297))
  const [customRoll, setCustomRoll] = useState(savedSilentSettings?.paper.heightMm === 'auto')
  const [exporting, setExporting] = useState(false)
  const [measured, setMeasured] = useState<{ html: string; height: number } | null>(null)
  const frameRef = useRef<HTMLIFrameElement>(null)

  const paper: InvoicePaper = useMemo(() => {
    if (paperKind === 'CUSTOM') {
      return {
        kind: 'CUSTOM',
        widthMm: Number(customWidth) || 210,
        heightMm: customRoll ? 'auto' : Number(customHeight) || 297,
      }
    }
    return { kind: paperKind, orientation }
  }, [paperKind, orientation, customWidth, customHeight, customRoll])

  const html = useMemo(
    () =>
      sale
        ? buildInvoiceHtml(sale, store, {
            template,
            paper,
            cancellationNumber,
            cancellationReason,
          })
        : '',
    [sale, store, template, paper, cancellationNumber, cancellationReason],
  )

  // Auto size the iframe to the rendered document so thermal rolls show their
  // true length instead of a fixed page.
  useEffect(() => {
    const frame = frameRef.current
    if (!open || !frame) return
    const onLoad = () => {
      const doc = frame.contentDocument
      if (!doc) return
      const mmWidth = invoicePaperMm(paper).widthMm
      const contentPx = (mmWidth * 96) / 25.4
      const actual = doc.documentElement.scrollHeight
      const rendered = doc.body.scrollHeight
      setMeasured({ html, height: Math.max(actual, rendered, contentPx * 1.2) })
    }
    frame.addEventListener('load', onLoad)
    return () => frame.removeEventListener('load', onLoad)
  }, [open, html, paper])

  const preview = invoicePreviewSize(paper)
  const frameWidth = preview.width
  const frameHeight = measured && measured.html === html ? measured.height : preview.height
  const mm = invoicePaperMm(paper)

  const handlePrint = () => {
    if (!html) return
    try {
      printDocument(html)
    } catch {
      // The browser blocked the print; the user can retry from the dialog.
    }
  }

  const handleDownloadPdf = async () => {
    const frame = frameRef.current
    const doc = frame?.contentDocument
    if (!doc || !sale || exporting) return
    setExporting(true)
    try {
      const { default: html2canvas } = await import('html2canvas')
      const { jsPDF } = await import('jspdf')
      const canvas = await html2canvas(doc.body, { scale: 2, backgroundColor: '#ffffff' })
      const pxPerMm = canvas.width / mm.widthMm
      const heightMm = mm.heightMm === 'auto' ? canvas.height / pxPerMm : mm.heightMm
      const pdf = new jsPDF({
        orientation: mm.widthMm > heightMm ? 'landscape' : 'portrait',
        unit: 'mm',
        format: [mm.widthMm, Math.min(heightMm, 15000)],
      })
      const pageHeightPx = canvas.width * (heightMm / mm.widthMm)
      let offset = 0
      let first = true
      while (offset < canvas.height) {
        const sliceHeight = Math.min(pageHeightPx, canvas.height - offset)
        const page = document.createElement('canvas')
        page.width = canvas.width
        page.height = sliceHeight
        const ctx = page.getContext('2d')
        if (!ctx) break
        ctx.fillStyle = '#ffffff'
        ctx.fillRect(0, 0, page.width, page.height)
        ctx.drawImage(canvas, 0, offset, canvas.width, sliceHeight, 0, 0, canvas.width, sliceHeight)
        if (!first) pdf.addPage([mm.widthMm, Math.min(heightMm, 15000)], mm.widthMm > heightMm ? 'landscape' : 'portrait')
        pdf.addImage(page.toDataURL('image/png'), 'PNG', 0, 0, mm.widthMm, (sliceHeight * heightMm) / canvas.height)
        offset += pageHeightPx
        first = false
      }
      pdf.save(`invoice-${sale.salesNumber}.pdf`)
    } finally {
      setExporting(false)
    }
  }

  if (!sale) return null

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={`Invoice ${sale.salesNumber}`}
      description={`${sale.branchName} · ${sale.customerName || 'Cash Sales'}`}
      size="full"
      bodyClassName="flex flex-col"
      footer={
        <>
          <Button variant="outline" onClick={handleDownloadPdf} loading={exporting} icon={<Download className="h-4 w-4" />}>
            Download PDF
          </Button>
          <Button onClick={handlePrint} icon={<Printer className="h-4 w-4" />}>
            Print
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4 xl:flex-row">
        <div className="grid shrink-0 grid-cols-1 gap-3 sm:grid-cols-2 xl:w-72 xl:grid-cols-1">
          <Field label="Invoice template">
            <Select value={template} onChange={(event) => setTemplate(event.target.value as InvoiceTemplateKind)}>
              <option value="RETAIL">Retail / supermarket</option>
              <option value="SAREE">Saree shop</option>
            </Select>
          </Field>
          <Field label="Paper size">
            <Select value={paperKind} onChange={(event) => setPaperKind(event.target.value as InvoicePaperKind)}>
              {PAPER_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </Field>
          {paperKind === 'CUSTOM' ? (
            <>
              <Field label="Width (mm)">
                <Input
                  type="number"
                  min={40}
                  max={600}
                  value={customWidth}
                  onChange={(event) => setCustomWidth(event.target.value)}
                />
              </Field>
              <Field label="Height (mm)">
                <Input
                  type="number"
                  min={40}
                  max={1200}
                  value={customHeight}
                  disabled={customRoll}
                  onChange={(event) => setCustomHeight(event.target.value)}
                />
              </Field>
              <label className="flex items-center gap-2 text-sm text-slate-600">
                <input
                  type="checkbox"
                  className="h-4 w-4 rounded border-slate-300"
                  checked={customRoll}
                  onChange={(event) => setCustomRoll(event.target.checked)}
                />
                Continuous roll (auto height)
              </label>
            </>
          ) : (paperKind === 'A4' || paperKind === 'A5') ? (
            <Field label="Orientation">
              <Select value={orientation} onChange={(event) => setOrientation(event.target.value as 'portrait' | 'landscape')}>
                <option value="portrait">Portrait</option>
                <option value="landscape">Landscape</option>
              </Select>
            </Field>
          ) : null}
          {onSaveSilentSettings && (
            <div className="space-y-2">
              <p className="text-xs text-slate-600">
                {detectPlatform() === 'WINDOWS'
                  ? 'Silent printing uses the Windows default printer. Set that printer to match the selected paper.'
                  : 'Silent printing on this device requires a paired Android print bridge and a matching 58 mm or 80 mm printer.'}
              </p>
              <Button variant="outline"
                disabled={detectPlatform() !== 'WINDOWS' &&
                  !(detectPlatform() === 'ANDROID' && (paperKind === 'MM58' || paperKind === 'MM80'))}
                onClick={() => onSaveSilentSettings({ template, paper })}>
                Save for silent printing
              </Button>
              {savedSilentSettings && (
                <Button variant="ghost" onClick={() => onSaveSilentSettings(null)}>
                  Turn off silent printing
                </Button>
              )}
            </div>
          )}
          <div className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
            {mm.widthMm} mm × {mm.heightMm === 'auto' ? 'roll' : `${mm.heightMm} mm`}
            {paperKind === 'MM58' ? ' · compact 32-column layout' : paperKind === 'MM80' ? ' · compact table layout' : ''}
            {sale.status === 'CANCELLED' ? ' · prints with CANCELLED watermark' : ''}
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-auto rounded-lg border border-slate-200 bg-slate-100 p-4">
          <div className="flex justify-center">
            <iframe
              ref={frameRef}
              title={`Invoice preview ${sale.salesNumber}`}
              srcDoc={html}
              className="origin-top rounded-sm bg-white shadow-lg"
              style={{ width: frameWidth, height: frameHeight, border: 0, maxWidth: 'none' }}
            />
          </div>
        </div>
      </div>
    </Modal>
  )
}
