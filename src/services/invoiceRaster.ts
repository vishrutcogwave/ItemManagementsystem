import { buildInvoiceHtml, invoicePreviewSize, type InvoiceStoreInfo } from '../lib/invoiceTemplate'
import type { PaperWidth, Sale } from '../types'

export interface InvoiceRaster {
  width: number
  height: number
  bits: string
}

const MAX_RASTER_HEIGHT = 5000

export async function renderInvoiceRaster(
  sale: Sale,
  store: InvoiceStoreInfo,
  paperWidth: PaperWidth,
): Promise<InvoiceRaster> {
  const { default: html2canvas } = await import('html2canvas')
  const paper = { kind: paperWidth }
  const frame = document.createElement('iframe')
  frame.setAttribute('aria-hidden', 'true')
  frame.style.cssText = 'position:fixed;left:-10000px;top:0;border:0'
  frame.width = String(invoicePreviewSize(paper).width)
  frame.height = '1'
  frame.srcdoc = buildInvoiceHtml(sale, store, { template: 'RETAIL', paper })

  try {
    const loaded = new Promise<void>((resolve) => {
      frame.addEventListener('load', () => resolve(), { once: true })
    })
    document.body.append(frame)
    await loaded
    const invoice = frame.contentDocument?.querySelector<HTMLElement>('.doc')
    if (!invoice) throw new Error('The thermal invoice could not be rendered.')
    await invoice.ownerDocument.fonts.ready

    const width = paperWidth === 'MM58' ? 384 : 576
    const bounds = invoice.getBoundingClientRect()
    if (bounds.width <= 0 || bounds.height <= 0) {
      throw new Error('The thermal invoice has no printable content.')
    }
    const scale = width / bounds.width
    const height = Math.ceil(bounds.height * scale)
    if (height > MAX_RASTER_HEIGHT) {
      throw new Error('The invoice is too long for silent thermal printing.')
    }

    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const context = canvas.getContext('2d')
    if (!context) throw new Error('The thermal invoice could not be rendered.')

    await html2canvas(invoice, { canvas, scale, backgroundColor: '#ffffff', logging: false })
    const { data } = context.getImageData(0, 0, width, height)
    const packed = new Uint8Array(width * height / 8)
    for (let pixel = 0; pixel < width * height; pixel++) {
      const i = pixel * 4
      if (data[i + 3] > 0 && (data[i] * 3 + data[i + 1] * 6 + data[i + 2]) < 1920) {
        packed[pixel >> 3] |= 0x80 >> (pixel & 7)
      }
    }

    let binary = ''
    for (let offset = 0; offset < packed.length; offset += 8192) {
      binary += String.fromCharCode(...packed.subarray(offset, offset + 8192))
    }
    return { width, height, bits: btoa(binary) }
  } finally {
    frame.remove()
  }
}
