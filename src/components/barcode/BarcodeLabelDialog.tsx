import { Barcode, Copy, Download, Printer, RefreshCw, Ruler } from 'lucide-react'
import { useState } from 'react'
import { BarcodeSvg } from './BarcodeSvg'
import { Button, ConfirmDialog, Field, Input, Modal, Select } from '../ui'
import { api, errorMessage } from '../../lib/api'
import {
  BARCODE_FORMATS,
  barcodeFormatLabel,
  buildCalibrationSheetHtml,
  buildLabelSheetHtml,
  downloadLabelPng,
} from '../../lib/barcode'
import { printHtml } from '../../lib/export'
import { money } from '../../lib/format'
import { useAuthStore } from '../../store/auth'
import { notify, notifyError } from '../../store/ui'
import type { BarcodeFormat, Item } from '../../types'

interface BarcodeLabelDialogProps {
  item: Item | null
  onClose: () => void
  onUpdated: (item: Item) => void
}

export function BarcodeLabelDialog({ item, onClose, onUpdated }: BarcodeLabelDialogProps) {
  const canManage = useAuthStore((state) => state.hasPermission('ITEM_MANAGE'))
  const [copies, setCopies] = useState('1')
  const [format, setFormat] = useState<BarcodeFormat>(item?.barcodeFormat ?? 'CODE128')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [regenerating, setRegenerating] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const labelCount = Math.min(Math.max(Math.trunc(Number(copies) || 1), 1), 100)

  const printLabels = () => {
    if (!item) return
    printHtml(buildLabelSheetHtml(item, labelCount), `Barcode labels ${item.itemCode}`)
  }

  const printCalibration = () => {
    printHtml(buildCalibrationSheetHtml(), 'Barcode label calibration')
  }

  const download = async () => {
    if (!item) return
    setDownloading(true)
    try {
      await downloadLabelPng(item)
      notify('Barcode label downloaded')
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setDownloading(false)
    }
  }

  const copyValue = async () => {
    if (!item) return
    try {
      await navigator.clipboard.writeText(item.barcode)
      notify('Barcode copied')
    } catch {
      notifyError('The browser blocked clipboard access')
    }
  }

  const regenerate = async () => {
    if (!item) return
    setRegenerating(true)
    setError(null)
    try {
      const { data } = await api.post<Item>(`/items/${item.id}/barcode`, { format })
      onUpdated(data)
      setFormat(data.barcodeFormat)
      setConfirmOpen(false)
      notify(`New ${barcodeFormatLabel(data.barcodeFormat)} barcode generated`)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setRegenerating(false)
    }
  }

  return (
    <>
      <Modal
        open={Boolean(item)}
        onClose={onClose}
        title={item ? `Barcode label · ${item.itemName}` : 'Barcode label'}
        description={item ? `${item.itemCode} · ${barcodeFormatLabel(item.barcodeFormat)}` : undefined}
        size="md"
        footer={
          <Button variant="outline" onClick={onClose}>
            Close
          </Button>
        }
      >
        {item && (
          <div className="space-y-4">
            {error && (
              <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
                {error}
              </div>
            )}

            <div className="mx-auto w-full max-w-[330px] rounded-xl border border-dashed border-slate-300 bg-white p-3 text-center shadow-sm">
              <p className="line-clamp-2 text-sm font-bold text-slate-900">{item.itemName}</p>
              <p className="mt-0.5 text-[10px] font-semibold tracking-wider text-slate-500 uppercase">
                {item.itemCode}
              </p>
              <div className="my-2 flex justify-center overflow-hidden">
                <BarcodeSvg
                  value={item.barcode}
                  format={item.barcodeFormat}
                  height={44}
                  width={1.6}
                  fontSize={11}
                  className="h-14"
                />
              </div>
              <p className="text-base font-bold text-slate-900">{money(item.rate)}</p>
            </div>

            <div className="flex items-center justify-between gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
              <div className="flex min-w-0 items-center gap-2">
                <Barcode className="h-4 w-4 shrink-0 text-slate-400" />
                <span className="truncate font-mono text-xs font-semibold text-slate-700">{item.barcode}</span>
              </div>
              <button
                type="button"
                onClick={() => void copyValue()}
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-slate-400 transition hover:bg-white hover:text-slate-700"
                title="Copy barcode value"
                aria-label="Copy barcode value"
              >
                <Copy className="h-3.5 w-3.5" />
              </button>
            </div>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label="Labels to print" hint="Each label is 50 × 30 mm">
                <Input
                  type="number"
                  min="1"
                  max="100"
                  value={copies}
                  onChange={(event) => setCopies(event.target.value)}
                  onBlur={() => setCopies(String(labelCount))}
                />
              </Field>
              <Field label="Barcode format">
                <Select
                  value={format}
                  onChange={(event) => setFormat(event.target.value as BarcodeFormat)}
                  disabled={!canManage}
                >
                  {BARCODE_FORMATS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </Select>
              </Field>
            </div>

            {canManage && (
              <Button
                variant="outline"
                className="w-full justify-center"
                icon={<RefreshCw className="h-4 w-4" />}
                onClick={() => setConfirmOpen(true)}
              >
                {format === item.barcodeFormat
                  ? `Generate a new ${barcodeFormatLabel(format)} barcode`
                  : `Replace with a new ${barcodeFormatLabel(format)} barcode`}
              </Button>
            )}

            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <Button
                variant="outline"
                className="justify-center"
                icon={<Download className="h-4 w-4" />}
                onClick={() => void download()}
                loading={downloading}
              >
                Download PNG
              </Button>
              <Button className="justify-center" icon={<Printer className="h-4 w-4" />} onClick={printLabels}>
                Print {labelCount > 1 ? `${labelCount} labels` : 'label'}
              </Button>
            </div>

            <Button
              variant="ghost"
              size="sm"
              className="w-full justify-center"
              icon={<Ruler className="h-3.5 w-3.5" />}
              onClick={printCalibration}
            >
              Print calibration test page
            </Button>

            <p className="text-center text-[11px] leading-relaxed text-slate-400">
              Labels are 50 × 30 mm. In the print dialog set scale to 100 % (“Actual size”), disable “Fit to page”,
              and choose the label paper size. Run the calibration page first and verify the printed ruler measures
              50 mm. Test the first printed label with your scanner.
            </p>
          </div>
        )}
      </Modal>

      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={() => void regenerate()}
        title="Regenerate barcode?"
        message={
          item
            ? `This replaces ${item.barcode} with a new unique ${barcodeFormatLabel(format)} barcode for ${item.itemName}. Labels already printed with the old barcode will stop scanning.`
            : ''
        }
        confirmLabel="Generate new barcode"
        tone="danger"
        loading={regenerating}
      />
    </>
  )
}
