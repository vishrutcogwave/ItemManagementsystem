import { buildInvoiceHtml, invoicePaperMarginMm, invoicePaperMm, type InvoicePrintSettings, type InvoiceStoreInfo } from '../lib/invoiceTemplate'
import type { Sale } from '../types'

export interface DriverInvoice {
  widthMm: number
  heightMm: number
  marginMm: number
  pages: string[]
}

const maxPages = 16
const maxEncodedBytes = 3_500_000

export async function renderDriverInvoice(
  sale: Sale, store: InvoiceStoreInfo, settings: InvoicePrintSettings,
): Promise<DriverInvoice> {
  const { default: html2canvas } = await import('html2canvas')
  const { paper, template } = settings
  const { widthMm, heightMm: configuredHeight } = invoicePaperMm(paper)
  const marginMm = invoicePaperMarginMm(paper)
  const contentWidth = widthMm - 2 * marginMm
  const pxPerMm = 96 / 25.4
  const frame = document.createElement('iframe')
  frame.setAttribute('aria-hidden', 'true')
  frame.style.cssText = 'position:fixed;left:-10000px;top:0;height:1px;border:0'
  frame.style.width = `${contentWidth}mm`
  frame.srcdoc = buildInvoiceHtml(sale, store, { paper, template })

  try {
    const loaded = new Promise<void>((resolve) => frame.addEventListener('load', () => resolve(), { once: true }))
    document.body.append(frame)
    await loaded
    const invoice = frame.contentDocument?.querySelector<HTMLElement>('.doc')
    if (!invoice) throw new Error('The invoice could not be rendered.')
    await invoice.ownerDocument.fonts.ready
    const bounds = invoice.getBoundingClientRect()
    const heightMm = configuredHeight === 'auto'
      ? Math.max(40, Math.min(1200, Math.ceil(bounds.height / pxPerMm + 2 * marginMm)))
      : configuredHeight
    const pageHeightPx = Math.floor((heightMm - 2 * marginMm) * pxPerMm)
    const breaks = [...invoice.querySelectorAll('.doc > *, tbody tr, .t-item')]
      .map((element) => Math.ceil(element.getBoundingClientRect().bottom - bounds.top))
      .sort((left, right) => left - right)
    const pages: string[] = []
    let offset = 0
    let encodedBytes = 0
    // Keep table rows together where possible. Very tall content falls back to a page-sized slice.
    while (offset < bounds.height) {
      if (pages.length >= maxPages) throw new Error('The invoice has too many pages for silent printing.')
      const limit = offset + pageHeightPx
      const breakpoint = breaks.findLast((point) => point > offset + pageHeightPx / 2 && point <= limit)
      const pageEnd = limit >= bounds.height ? bounds.height : breakpoint ?? limit
      const canvas = await html2canvas(invoice, {
        scale: Math.min(2, 4096 / bounds.width, 4096 / pageHeightPx),
        backgroundColor: '#fff', logging: false, y: offset, height: pageEnd - offset,
      })
      const png = canvas.toDataURL('image/png').split(',')[1]
      if (!png || (encodedBytes += png.length) > maxEncodedBytes) {
        throw new Error('The invoice is too large for silent printing. Open Print settings to print from the preview.')
      }
      pages.push(png)
      offset = pageEnd
    }
    return { widthMm, heightMm, marginMm, pages }
  } finally {
    frame.remove()
  }
}
