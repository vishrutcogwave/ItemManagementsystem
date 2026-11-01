import JsBarcode from 'jsbarcode'
import type { BarcodeFormat, Item } from '../types'

export const BARCODE_FORMATS: { value: BarcodeFormat; label: string }[] = [
  { value: 'CODE128', label: 'Code 128' },
  { value: 'EAN13', label: 'EAN-13' },
]

export interface BarcodeRenderOptions {
  height?: number
  width?: number
  margin?: number
  displayValue?: boolean
  fontSize?: number
}

export function ean13CheckDigit(twelveDigits: string): number {
  let sum = 0
  for (let index = 0; index < 12; index += 1) {
    const digit = Number(twelveDigits[index])
    sum += index % 2 === 0 ? digit : digit * 3
  }
  return (10 - (sum % 10)) % 10
}

export function isValidEan13(value: string): boolean {
  return /^\d{13}$/.test(value) && ean13CheckDigit(value.slice(0, 12)) === Number(value[12])
}

export function normalizeBarcode(value: string): string {
  return value.trim()
}

/** Mirrors the server-side BarcodeService validation so the form fails fast. */
export function validateBarcode(value: string, format: BarcodeFormat): string | null {
  const trimmed = normalizeBarcode(value)
  if (!trimmed) return 'Barcode is required'
  if (format === 'EAN13') {
    if (!/^\d{13}$/.test(trimmed)) return 'EAN-13 must contain exactly 13 digits'
    if (!isValidEan13(trimmed)) return 'The EAN-13 check digit is invalid'
    return null
  }
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(trimmed)) {
    return 'Code 128 allows 1-64 letters, numbers, dot, underscore or hyphen'
  }
  return null
}

export function barcodeFormatLabel(format: string): string {
  return format === 'EAN13' ? 'EAN-13' : 'Code 128'
}

export function renderBarcodeIntoSvg(
  svg: SVGSVGElement,
  value: string,
  format: BarcodeFormat,
  options: BarcodeRenderOptions = {},
): boolean {
  try {
    JsBarcode(svg, value, {
      format: format === 'EAN13' ? 'EAN13' : 'CODE128',
      width: options.width ?? 1.6,
      height: options.height ?? 48,
      margin: options.margin ?? 0,
      displayValue: options.displayValue ?? true,
      fontSize: options.fontSize ?? 12,
      font: 'monospace',
      textMargin: 0,
      background: '#ffffff',
      lineColor: '#111827',
    })
    return true
  } catch {
    svg.replaceChildren()
    return false
  }
}

export function createBarcodeSvg(
  value: string,
  format: BarcodeFormat,
  options: BarcodeRenderOptions = {},
): SVGSVGElement | null {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  if (!renderBarcodeIntoSvg(svg, value, format, options)) return null
  svg.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  return svg
}

export function barcodeSvgMarkup(
  value: string,
  format: BarcodeFormat,
  options: BarcodeRenderOptions = {},
): string {
  const svg = createBarcodeSvg(value, format, options)
  return svg ? new XMLSerializer().serializeToString(svg) : ''
}

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

export type LabelItem = Pick<Item, 'itemName' | 'itemCode' | 'barcode' | 'barcodeFormat' | 'rate'>

/**
 * Builds a print-ready sheet of 50 x 30 mm labels. Each label shows the item
 * name, item code, scannable symbol and selling price.
 */
export function buildLabelSheetHtml(item: LabelItem, copies: number): string {
  const barcode = barcodeSvgMarkup(item.barcode, item.barcodeFormat, {
    height: 46,
    width: 1.8,
    displayValue: true,
    fontSize: 12,
    margin: 6,
  })
  const label = `<div class="barcode-label">
    <p class="label-name">${escapeHtml(item.itemName)}</p>
    <p class="label-code">${escapeHtml(item.itemCode)}</p>
    <div class="label-symbol">${barcode}</div>
    <p class="label-price">&#8377; ${Number(item.rate).toFixed(2)}</p>
  </div>`
  return `<style>
    @page { margin: 6mm; }
    .label-sheet { display: flex; flex-wrap: wrap; gap: 4mm; padding: 0; }
    .barcode-label {
      width: 50mm; height: 30mm; box-sizing: border-box;
      display: flex; flex-direction: column; align-items: center; justify-content: space-between;
      padding: 1.6mm 2mm; border: 1px dashed #9ca3af; border-radius: 1.5mm;
      overflow: hidden; page-break-inside: avoid; break-inside: avoid; background: #fff;
    }
    .label-name { margin: 0; max-width: 100%; font-size: 8pt; font-weight: 700; line-height: 1.2; text-align: center;
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .label-code { margin: 0; font-size: 6.5pt; letter-spacing: 0.04em; color: #4b5563; text-transform: uppercase; }
    .label-symbol { width: 100%; height: 12.5mm; display: flex; align-items: center; justify-content: center; }
    .label-symbol svg { width: 100%; height: 12.5mm; }
    .label-price { margin: 0; font-size: 9.5pt; font-weight: 700; color: #111827; }
    @media print { .barcode-label { border-color: #d1d5db; } }
  </style>
  <div class="label-sheet">${label.repeat(Math.max(1, copies))}</div>`
}

/**
 * One-page test print used to check that the browser is printing at 100 %
 * scale. The outlined boxes must measure exactly 50 x 30 mm on paper before
 * the real label sheet is printed.
 */
export function buildCalibrationSheetHtml(): string {
  const testCode = 'IMS0000000001'
  const barcode = barcodeSvgMarkup(testCode, 'CODE128', {
    height: 40,
    width: 1.5,
    displayValue: true,
    fontSize: 10,
    margin: 6,
  })
  const ruler = Array.from({ length: 11 }, (_, index) => {
    const left = index * 5
    return `<span class="tick" style="left:${left}mm">${index * 5}</span>`
  }).join('')
  const sampleLabels = Array.from({ length: 3 }, () => `<div class="calibration-label">
      <span class="corner tl"></span><span class="corner tr"></span>
      <span class="corner bl"></span><span class="corner br"></span>
      <p class="calibration-title">50 × 30 mm</p>
      <div class="calibration-symbol">${barcode}</div>
      <p class="calibration-foot">${testCode}</p>
    </div>`).join('')
  return `<style>
    @page { margin: 6mm; }
    body { font-family: 'Segoe UI', Arial, sans-serif; color: #111827; }
    .calibration { display: flex; flex-direction: column; gap: 6mm; }
    .calibration h2 { margin: 0 0 2mm; font-size: 12pt; }
    .calibration p { margin: 0 0 2mm; font-size: 9pt; color: #4b5563; }
    .ruler { position: relative; width: 50mm; height: 14mm; border-bottom: 1px solid #111827; }
    .tick { position: absolute; bottom: 0; width: 1px; height: 3mm; background: #111827; font-size: 6pt; text-align: center;
      transform: translateX(-50%); padding-bottom: 4mm; }
    .ruler-label { font-size: 8pt; color: #4b5563; }
    .calibration-labels { display: flex; flex-wrap: wrap; gap: 4mm; }
    .calibration-label {
      position: relative; width: 50mm; height: 30mm; box-sizing: border-box; padding: 2mm;
      border: 1px dashed #9ca3af; display: flex; flex-direction: column; align-items: center;
      justify-content: space-between; background: #fff; page-break-inside: avoid; break-inside: avoid;
    }
    .calibration-title { margin: 0; font-size: 8pt; font-weight: 700; }
    .calibration-symbol { width: 100%; height: 12mm; display: flex; align-items: center; justify-content: center; }
    .calibration-symbol svg { width: 100%; height: 12mm; }
    .calibration-foot { margin: 0; font-size: 6.5pt; color: #4b5563; letter-spacing: 0.04em; }
    .corner { position: absolute; width: 3mm; height: 3mm; border: 0.4mm solid #111827; }
    .corner.tl { top: 0; left: 0; border-right: 0; border-bottom: 0; }
    .corner.tr { top: 0; right: 0; border-left: 0; border-bottom: 0; }
    .corner.bl { bottom: 0; left: 0; border-right: 0; border-top: 0; }
    .corner.br { bottom: 0; right: 0; border-left: 0; border-top: 0; }
    @media print { .calibration-label { border-color: #d1d5db; } }
  </style>
  <div class="calibration">
    <div>
      <h2>Barcode label calibration sheet</h2>
      <p>Print this page with scale set to 100 % (not “Fit to page”) and margins set to Default or None.</p>
      <p>Measure the ruler below. It must read exactly 50 mm and each dashed box must measure 50 × 30 mm.
        If the sizes differ, correct the print scale before printing product labels.</p>
    </div>
    <div>
      <div class="ruler">${ruler}</div>
      <p class="ruler-label">Ruler should measure 50 mm end to end.</p>
    </div>
    <div class="calibration-labels">${sampleLabels}</div>
  </div>`
}

function loadImage(source: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('The barcode image could not be prepared'))
    image.src = source
  })
}

/** Renders a single label as a PNG download at 600 x 360 px. */
export async function downloadLabelPng(item: LabelItem): Promise<void> {
  if (typeof document === 'undefined') return
  const svg = createBarcodeSvg(item.barcode, item.barcodeFormat, {
    height: 64,
    width: 2.2,
    displayValue: true,
    fontSize: 16,
    margin: 8,
  })
  if (!svg) throw new Error('This barcode cannot be rendered')

  const blob = new Blob([new XMLSerializer().serializeToString(svg)], {
    type: 'image/svg+xml;charset=utf-8',
  })
  const url = URL.createObjectURL(blob)
  try {
    const image = await loadImage(url)
    const canvas = document.createElement('canvas')
    canvas.width = 600
    canvas.height = 360
    const context = canvas.getContext('2d')
    if (!context) throw new Error('This browser cannot create the label image')

    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.fillStyle = '#111827'
    context.textAlign = 'center'
    context.textBaseline = 'middle'

    const drawFitted = (text: string, y: number, font: string, maxWidth: number) => {
      let size = Number(font.match(/(\d+)px/)?.[1] ?? 20)
      context.font = font
      while (context.measureText(text).width > maxWidth && size > 10) {
        size -= 1
        context.font = font.replace(/\d+px/, `${size}px`)
      }
      context.fillText(text, canvas.width / 2, y)
    }

    drawFitted(item.itemName, 48, 'bold 26px "Segoe UI", Arial, sans-serif', 520)
    drawFitted(item.itemCode, 82, '18px "Segoe UI", Arial, sans-serif', 520)
    context.drawImage(image, 60, 108, 480, 140)
    drawFitted(`₹ ${Number(item.rate).toFixed(2)}`, 310, 'bold 28px "Segoe UI", Arial, sans-serif', 520)

    const link = document.createElement('a')
    link.download = `barcode-${item.itemCode || item.barcode}.png`
    link.href = canvas.toDataURL('image/png')
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}
