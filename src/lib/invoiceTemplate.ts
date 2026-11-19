import type { Sale, SaleLine } from '../types'
import { amountInWords } from './amountInWords'
import { formatDate, formatDateTime, money, qty as formatQty } from './format'
import { paymentBreakdown, salePayments } from './salePayments'

/**
 * Universal invoice engine: one builder that reproduces the classic bordered
 * retail tax-invoice layout (header block, To/bill details, item grid, bank +
 * calculation summary, amount in words, payment, declaration and signature)
 * and reflows it for every paper size the shop prints on.
 */
export type InvoiceTemplateKind = 'SAREE' | 'RETAIL'
export type InvoicePaperKind = 'A4' | 'A5' | 'MM80' | 'MM58' | 'CUSTOM'

export interface InvoicePaper {
  kind: InvoicePaperKind
  orientation?: 'portrait' | 'landscape'
  /** Custom paper width in mm (CUSTOM and thermal kinds). */
  widthMm?: number
  /** Custom paper height in mm; omit/`auto` for continuous thermal rolls. */
  heightMm?: number | 'auto'
}

export interface InvoicePrintSettings {
  template: InvoiceTemplateKind
  paper: InvoicePaper
}

export interface InvoiceStoreInfo {
  name: string
  gstNumber?: string | null
  address?: string | null
  mobile?: string | null
  email?: string | null
}

export interface InvoiceBuildOptions {
  template?: InvoiceTemplateKind
  paper?: InvoicePaper
  cancellationNumber?: string | null
  cancellationReason?: string | null
}

interface PaperSpec {
  pageRule: string
  contentMm: number
  fontPx: number
  thermal: boolean
  /** Preview size at 96 dpi in CSS px. */
  previewPx: { width: number; height: number }
}

export const DEFAULT_INVOICE_PAPER: InvoicePaper = { kind: 'A4' }

function escapeHtml(value: string | number | null | undefined): string {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

export function invoicePaperMm(paper: InvoicePaper): { widthMm: number; heightMm: number | 'auto' } {
  switch (paper.kind) {
    case 'A4':
      return paper.orientation === 'landscape' ? { widthMm: 297, heightMm: 210 } : { widthMm: 210, heightMm: 297 }
    case 'A5':
      return paper.orientation === 'landscape' ? { widthMm: 210, heightMm: 148 } : { widthMm: 148, heightMm: 210 }
    case 'MM80':
      return { widthMm: 80, heightMm: 'auto' }
    case 'MM58':
      return { widthMm: 58, heightMm: 'auto' }
    case 'CUSTOM':
    default: {
      const widthMm = Math.min(600, Math.max(40, Number(paper.widthMm) || 210))
      const heightMm = paper.heightMm === 'auto' || paper.heightMm === undefined
        ? 'auto'
        : Math.min(1200, Math.max(40, Number(paper.heightMm)))
      return { widthMm, heightMm }
    }
  }
}

export function invoicePaperMarginMm(paper: InvoicePaper): number {
  return paper.kind === 'A4' ? 10 : paper.kind === 'A5' ? 6 : paper.kind === 'MM80' ? 3 : paper.kind === 'MM58' ? 2 : paper.widthMm && paper.widthMm <= 90 ? 2 : 5
}

function paperSpec(paper: InvoicePaper): PaperSpec {
  const orientation = paper.orientation === 'landscape' ? 'landscape' : 'portrait'
  switch (paper.kind) {
    case 'A5':
      return orientation === 'landscape'
        ? spec('A5 landscape', 198, 13.333, false, { width: 1123, height: 794 })
        : spec('A5 portrait', 136, 13.333, false, { width: 559, height: 794 })
    case 'MM80':
      return spec('auto', 74, 12, true, { width: 302, height: 1000 })
    case 'MM58':
      return spec('auto', 54, 12, true, { width: 220, height: 1000 })
    case 'CUSTOM': {
      const rawWidth = clamp(paper.widthMm ?? 210, 40, 600)
      const height = paper.heightMm === 'auto' || paper.heightMm === undefined
        ? 'auto'
        : clamp(paper.heightMm, 40, 1200)
      const margin = rawWidth <= 90 ? 2 : 5
      const pageRule = height === 'auto' ? 'size: auto' : `size: ${rawWidth}mm ${height}mm`
      const fontPx = rawWidth > 90 ? 13.333 : 12
      return {
        pageRule,
        contentMm: rawWidth - margin * 2,
        fontPx,
        thermal: rawWidth <= 90,
        previewPx: {
          width: mmToPx(rawWidth),
          height: height === 'auto' ? 1000 : mmToPx(height),
        },
      }
    }
    case 'A4':
    default:
      return orientation === 'landscape'
        ? spec('A4 landscape', 190, 14.667, false, { width: 1123, height: 794 })
        : spec('A4 portrait', 190, 14.667, false, { width: 794, height: 1123 })
  }
}

function spec(
  sizeRule: string,
  contentMm: number,
  fontPx: number,
  thermal: boolean,
  previewPx: { width: number; height: number },
): PaperSpec {
  return {
    pageRule: `size: ${sizeRule}`,
    contentMm,
    fontPx,
    thermal,
    previewPx,
  }
}

function mmToPx(mm: number): number {
  return Math.round((mm * 96) / 25.4)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

const BILL_TYPE_BY_PAYMENT: Record<string, string> = {
  CASH: 'Cash Bill',
  UPI: 'UPI Bill',
  CARD: 'Card Bill',
  BANK: 'Bank Bill',
  CREDIT: 'Credit Bill',
}

function num(value: number | string | null | undefined): number {
  return Number(value ?? 0)
}

function lineSubText(line: SaleLine, template: InvoiceTemplateKind): string {
  const parts = [line.itemCode ? `Code: ${line.itemCode}` : '', line.taxName ?? '']
    .filter(Boolean)
    .map((part) => escapeHtml(part))
  if (template === 'RETAIL' && num(line.gstPercentage) > 0) {
    return parts.join(' · ')
  }
  return parts.join(' · ')
}

function buildItemColumns(lines: SaleLine[], thermal: boolean): {
  showDiscount: boolean
  showGst: boolean
} {
  const showDiscount = lines.some((line) => num(line.discount) > 0)
  const showGst = !thermal && lines.some((line) => num(line.gstPercentage) > 0)
  return { showDiscount, showGst }
}

function itemRowsA4(
  sale: Sale,
  template: InvoiceTemplateKind,
  { showDiscount, showGst }: { showDiscount: boolean; showGst: boolean },
): string {
  return (sale.items ?? [])
    .map((line, index) => {
      const sub = lineSubText(line, template)
      const gstRate = num(line.gstPercentage)
      return `<tr>
        <td class="c">${index + 1}</td>
        <td>${escapeHtml(line.itemName ?? '')}${sub ? `<div class="sub">${sub}</div>` : ''}</td>
        <td></td>
        <td class="c">${escapeHtml(formatQty(line.quantity))} ${escapeHtml(line.unit ?? '')}</td>
        <td class="r">${money(line.sellingRate)}</td>
        ${showDiscount ? `<td class="r">${num(line.discount) > 0 ? money(line.discount) : ''}</td>` : ''}
        ${showGst ? `<td class="c">${gstRate > 0 ? `${gstRate}%` : 'Exempt'}</td>` : ''}
        <td class="r">${money(line.totalAmount ?? 0)}</td>
      </tr>`
    })
    .join('')
}

function itemRowsThermal(sale: Sale, wide: boolean): string {
  const items = sale.items ?? []
  if (wide) {
    return items
      .map(
        (line, index) => `<tr>
          <td class="c">${index + 1}</td>
          <td>${escapeHtml(line.itemName ?? '')}</td>
          <td class="c">${escapeHtml(formatQty(line.quantity))}</td>
          <td class="r">${money(line.sellingRate)}</td>
          <td class="r">${money(line.totalAmount ?? 0)}</td>
        </tr>`,
      )
      .join('')
  }
  return items
    .map(
      (line) => `<div class="t-item">
        <div class="t-name">${escapeHtml(line.itemName ?? '')}</div>
        <div class="t-line"><span>${escapeHtml(formatQty(line.quantity))} ${escapeHtml(line.unit ?? '')} x ${money(line.sellingRate)}</span><span>${money(line.totalAmount ?? 0)}</span></div>
      </div>`,
    )
    .join('')
}

function summaryRows(sale: Sale, interState: boolean, roundOff: number): string {
  const rows: Array<[string, string, string?]> = [['Gross Amount', money(sale.subtotal)]]
  if (num(sale.discount) > 0) rows.push(['Discount', `− ${money(sale.discount)}`])
  rows.push(['Taxable Amount', money(sale.taxableAmount)])
  if (num(sale.tax) > 0) {
    if (interState) rows.push(['IGST', money(sale.igstAmount)])
    else {
      rows.push(['CGST', money(sale.cgstAmount)])
      rows.push(['SGST', money(sale.sgstAmount)])
    }
  }
  if (roundOff !== 0) rows.push(['Round Off', `${roundOff > 0 ? '+' : '−'} ${money(Math.abs(roundOff))}`])
  return rows
    .map(
      ([label, value, extraClass]) =>
        `<div class="sum-row${extraClass ?? ''}"><span>${label}</span><span>${value}</span></div>`,
    )
    .join('')
}

const INVOICE_CSS = `
  * { box-sizing: border-box; }
  html, body { margin: 0; padding: 0; background: #fff; color: #000; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  body { font-family: 'Segoe UI', Arial, sans-serif; font-size: var(--fs); line-height: 1.35; }
  .doc { position: relative; z-index: 1; border: 1px solid #000; }
  .doc > * { border-top: 1px solid #000; }
  .doc > *:first-child { border-top: 0; }

  /* header */
  .hdr { padding: 8px 10px; text-align: center; }
  .hdr .title { font-size: calc(var(--fs) * 1.15); font-weight: 700; letter-spacing: 0.35em; text-indent: 0.35em; }
  .hdr .shop { font-size: calc(var(--fs) * 1.5); font-weight: 700; text-transform: uppercase; letter-spacing: 0.02em; margin-top: 2px; }
  .hdr p { margin: 1px 0; }
  .hdr .meta { font-size: calc(var(--fs) * 0.9); }
  .hdr .gstin { font-weight: 700; }

  .cancelled-banner { border-top: 1px solid #000; background: #fff1f2; color: #9f1239; padding: 4px 10px; text-align: center; font-size: calc(var(--fs) * 0.9); }
  .cancelled-banner strong { letter-spacing: 0.08em; }

  /* to / bill details */
  .row2 { display: grid; grid-template-columns: 1fr 1fr; }
  .row2 > div { padding: 6px 10px; }
  .row2 > div + div { border-left: 1px solid #000; }
  .block-label { font-weight: 700; margin-bottom: 2px; }
  .kv { display: grid; grid-template-columns: auto 1fr; column-gap: 8px; }
  .kv > span:nth-child(odd) { font-weight: 600; }
  .to .party { font-weight: 700; font-size: calc(var(--fs) * 1.05); }

  /* items grid */
  table.items { width: 100%; border-collapse: collapse; margin: 0; }
  table.items thead { display: table-header-group; }
  table.items th { border: 0; border-bottom: 1px solid #000; border-left: 1px solid #000; padding: 4px 6px; text-align: left; font-size: calc(var(--fs) * 0.9); text-transform: uppercase; letter-spacing: 0.04em; background: #fff; }
  table.items th:first-child { border-left: 0; }
  table.items td { border-left: 1px solid #000; border-bottom: 1px solid #000; padding: 4px 6px; vertical-align: top; word-wrap: break-word; overflow-wrap: anywhere; }
  table.items td:first-child { border-left: 0; }
  table.items tbody tr { page-break-inside: avoid; break-inside: avoid; }
  table.items tbody tr:last-child td { border-bottom: 0; }
  td.r, th.r { text-align: right; white-space: nowrap; }
  td.c, th.c { text-align: center; white-space: nowrap; }
  .sub { color: #444; font-size: calc(var(--fs) * 0.82); }

  /* bank + calculation */
  .summary { display: grid; grid-template-columns: 1fr auto; }
  .summary > div { padding: 6px 10px; }
  .summary > div + div { border-left: 1px solid #000; }
  .bank { min-width: 30%; }
  .bank .kv { grid-template-columns: auto 1fr; row-gap: 2px; }
  .calc { min-width: 45%; max-width: 62%; }
  .sum-row { display: flex; justify-content: space-between; gap: 16px; padding: 1.5px 0; }
  .sum-row.net { border-top: 1px solid #000; margin-top: 3px; padding-top: 3px; font-weight: 700; font-size: calc(var(--fs) * 1.25); }

  /* words + payment */
  .words, .pay { padding: 5px 10px; display: flex; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
  .words .label, .pay .label { font-weight: 700; white-space: nowrap; }
  .pay { font-size: calc(var(--fs) * 0.95); }

  /* declaration + signature */
  .foot { display: grid; grid-template-columns: 1fr auto; }
  .foot > div { padding: 6px 10px; }
  .foot > div + div { border-left: 1px solid #000; }
  .decl .label { font-weight: 700; }
  .decl ol { margin: 2px 0 0; padding-left: 18px; }
  .decl li { margin: 1px 0; font-size: calc(var(--fs) * 0.88); }
  .sign { text-align: center; min-width: 30%; display: flex; flex-direction: column; justify-content: flex-end; }
  .sign .for { font-weight: 700; }
  .sign .line { margin-top: 34px; border-top: 1px solid #000; padding-top: 3px; }

  .note { padding: 3px 10px; text-align: center; font-size: calc(var(--fs) * 0.8); color: #333; border-top: 1px solid #000; }

  .watermark { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; z-index: 0; pointer-events: none; }
  .watermark span { display: inline-block; transform: rotate(-28deg); font-size: calc(var(--fs) * 9); font-weight: 800; letter-spacing: 0.08em; color: rgba(220, 38, 38, 0.09); border: 8px solid rgba(220, 38, 38, 0.09); padding: 6px 24px; }

  @media print { body { padding: 0; } .words, .pay, .summary, .foot, .row2 { break-inside: avoid; } }

  .doc.thermal { overflow-wrap: anywhere; }
  .thermal table.items td, .thermal table.items th { padding-inline: 3px; }
  .thermal table.items td.r { white-space: normal; }
  .thermal .note { font-size: calc(var(--fs) * 0.9); }

  /* thermal reflow */
  .t-item { padding: 2px 0; }
  .t-name { font-weight: 700; }
  .t-line { display: flex; justify-content: space-between; gap: 8px; }
  .t-line span:last-child { white-space: nowrap; }
  .t-divider { border-top: 1px dashed #000; margin: 4px 0; }
`

function buildA5Layout(
  sale: Sale,
  store: InvoiceStoreInfo,
  template: InvoiceTemplateKind,
  options: InvoiceBuildOptions,
): string {
  const cancelled = sale.status === 'CANCELLED'
  const interState = num(sale.igstAmount) > 0
  const grand = num(sale.grandTotal)
  const payable = Math.round(grand)
  const roundOff = Math.round((payable - grand) * 100) / 100
  const paid = num(sale.paidAmount)
  const balance = Math.round((payable - paid) * 100) / 100
  const lines = sale.items ?? []
  const { showDiscount, showGst } = buildItemColumns(lines, false)
  const party = sale.customerName?.trim() || 'Cash Sales'

  const billRows: Array<[string, string]> = [
    ['Bill No', escapeHtml(sale.salesNumber)],
    ['Bill Date', formatDate(sale.salesDate)],
    ['Bill Type', escapeHtml(salePayments(sale).length > 1 ? 'Split Payment' : BILL_TYPE_BY_PAYMENT[sale.paymentMethod] ?? sale.paymentMethod)],
    ['Location', escapeHtml(sale.branchName ?? '')],
  ]
  if (sale.createdAt) billRows.push(['Billed at', formatDateTime(sale.createdAt)])

  const contact = [store.mobile && `Ph: ${escapeHtml(store.mobile)}`, store.email && escapeHtml(store.email)]
    .filter(Boolean)
    .join(' &middot; ')

  const bankRows = ['Bank Name', 'Account No', 'Branch', 'IFSC Code']
    .map((label) => `<span>${label}</span><span></span>`)
    .join('')

  return `
    ${cancelled ? '<div class="watermark"><span>CANCELLED</span></div>' : ''}
    <div class="hdr">
      <div class="title">TAX INVOICE</div>
      <div class="shop">${escapeHtml(store.name)}</div>
      ${store.address ? `<p class="meta">${escapeHtml(store.address)}</p>` : ''}
      ${contact ? `<p class="meta">${contact}</p>` : ''}
      <p class="meta"><span class="gstin">GSTIN : ${escapeHtml(store.gstNumber ?? '—')}</span></p>
    </div>
    ${cancelled ? `<div class="cancelled-banner"><strong>BILL CANCELLED</strong>${options.cancellationNumber ? ` &middot; ${escapeHtml(options.cancellationNumber)}` : ''}${sale.cancelledAt ? ` &middot; ${escapeHtml(formatDateTime(sale.cancelledAt))}` : ''}${(options.cancellationReason || sale.cancellationReason) ? `<div>Reason: ${escapeHtml(options.cancellationReason || sale.cancellationReason || '')}</div>` : ''}</div>` : ''}
    <div class="row2">
      <div class="to">
        <div class="block-label">To :</div>
        <div class="party">${escapeHtml(party)}</div>
        ${sale.customerMobile ? `<div>Phone : ${escapeHtml(sale.customerMobile)}</div>` : ''}
        ${sale.customerState ? `<div>Place of Supply : ${escapeHtml(sale.customerState)}</div>` : ''}
      </div>
      <div class="bill">
        <div class="block-label">Bill Details</div>
        <div class="kv">${billRows.map(([k, v]) => `<span>${k}</span><span>${v}</span>`).join('')}</div>
      </div>
    </div>
    <table class="items">
      <thead><tr>
        <th class="c" style="width:5%">S.No</th>
        <th style="width:auto">Particulars</th>
        <th style="width:8%">HSN/SAC</th>
        <th class="c" style="width:9%">Qty</th>
        <th class="r" style="width:11%">Rate</th>
        ${showDiscount ? '<th class="r" style="width:9%">Discount</th>' : ''}
        ${showGst ? '<th class="c" style="width:7%">GST</th>' : ''}
        <th class="r" style="width:13%">Amount</th>
      </tr></thead>
      <tbody>${itemRowsA4(sale, template, { showDiscount, showGst })}</tbody>
    </table>
    <div class="summary">
      <div class="bank">
        <div class="block-label">BANK DETAILS</div>
        <div class="kv">${bankRows}</div>
      </div>
      <div class="calc">
        ${summaryRows(sale, interState, roundOff)}
        <div class="sum-row net"><span>NET AMOUNT</span><span>${money(payable)}</span></div>
      </div>
    </div>
    <div class="words">
      <span class="label">Amount in words :</span>
      <span>${escapeHtml(amountInWords(payable))}</span>
    </div>
    <div class="pay">
      <span><span class="label">Payment Mode :</span> ${escapeHtml(paymentBreakdown(salePayments(sale)))}</span>
      <span><span class="label">Paid :</span> ${money(paid)}</span>
      ${balance !== 0 ? `<span><span class="label">${balance < 0 ? 'Refund Due' : 'Outstanding Amount'} :</span> ${money(Math.abs(balance))}</span>` : ''}
      ${sale.modificationCount > 0 ? `<span><span class="label">Modified :</span> ${sale.modificationCount}&times;</span>` : ''}
    </div>
    <div class="foot">
      <div class="decl">
        <div class="label">DECLARATION</div>
        <ol>
          <li>Goods once sold will not be taken back.</li>
          <li>Exchange, if any, is subject to store policy and this invoice.</li>
          <li>All disputes are subject to ${escapeHtml(sale.branchName ?? 'local')} jurisdiction.</li>
        </ol>
      </div>
      <div class="sign">
        <div class="for">For ${escapeHtml(store.name)}</div>
        <div class="line">Authorised Signatory</div>
      </div>
    </div>
    <div class="note">This is a computer generated tax invoice &middot; Generated by Item Management System</div>
  `
}

function buildThermalLayout(
  sale: Sale,
  store: InvoiceStoreInfo,
  spec_: PaperSpec,
): string {
  const interState = num(sale.igstAmount) > 0
  const grand = num(sale.grandTotal)
  const payable = Math.round(grand)
  const roundOff = Math.round((payable - grand) * 100) / 100
  const paid = num(sale.paidAmount)
  const balance = Math.round((payable - paid) * 100) / 100
  const wide = spec_.contentMm >= 65
  const items = sale.items ?? []
  const totalUnits = items.reduce((sum, line) => sum + num(line.quantity), 0)

  const billRows: Array<[string, string]> = [
    ['Bill No', escapeHtml(sale.salesNumber)],
    ['Date', formatDate(sale.salesDate)],
    ['Type', escapeHtml(salePayments(sale).length > 1 ? 'Split Payment' : BILL_TYPE_BY_PAYMENT[sale.paymentMethod] ?? sale.paymentMethod)],
    ['Location', escapeHtml(sale.branchName ?? '')],
  ]
  if (sale.customerName?.trim() || sale.customerMobile) {
    billRows.unshift(['To', escapeHtml(sale.customerName?.trim() || 'Cash Sales')])
    if (sale.customerMobile) billRows.splice(2, 0, ['Phone', escapeHtml(sale.customerMobile)])
  }

  return `
    <div class="hdr" style="border:0">
      <div class="shop">${escapeHtml(store.name)}</div>
      ${store.address ? `<p class="meta">${escapeHtml(store.address)}</p>` : ''}
      ${store.mobile ? `<p class="meta">Ph: ${escapeHtml(store.mobile)}</p>` : ''}
      ${store.gstNumber ? `<p class="meta"><span class="gstin">GSTIN : ${escapeHtml(store.gstNumber)}</span></p>` : ''}
      <div class="title" style="margin-top:3px">TAX INVOICE</div>
    </div>
    <div style="border-top:1px solid #000; padding:4px 6px">
      <div class="kv">${billRows.map(([k, v]) => `<span>${k}</span><span>${v}</span>`).join('')}</div>
    </div>
    <div style="border-top:1px solid #000; padding:4px 6px">
      ${wide ? `<table class="items"><thead><tr>
          <th class="c" style="width:8%">S.No</th>
          <th style="width:auto">Particulars</th>
          <th class="c" style="width:12%">Qty</th>
          <th class="r" style="width:20%">Rate</th>
          <th class="r" style="width:24%">Amount</th>
        </tr></thead><tbody>${itemRowsThermal(sale, wide)}</tbody></table>` : itemRowsThermal(sale, wide)}
    </div>
    <div style="border-top:1px solid #000; padding:4px 6px; display:flex; justify-content:space-between; gap:8px">
      <span>Items: ${items.length} &middot; Qty: ${escapeHtml(formatQty(totalUnits))}</span>
      <span>Gross: ${money(sale.subtotal)}</span>
    </div>
    <div style="border-top:1px solid #000; padding:4px 6px">
      ${summaryRows(sale, interState, roundOff)}
      <div class="sum-row net"><span>NET AMOUNT</span><span>${money(payable)}</span></div>
      <div class="sum-row"><span>Paid</span><span>${money(paid)}</span></div>
      ${balance !== 0 ? `<div class="sum-row"><span>${balance < 0 ? 'Refund Due' : 'Outstanding'}</span><span>${money(Math.abs(balance))}</span></div>` : ''}
    </div>
    <div style="border-top:1px solid #000; padding:4px 6px">
      <div style="font-weight:700">Amount in words</div>
      <div>${escapeHtml(amountInWords(payable))}</div>
      <div class="t-divider"></div>
      <div>Payment Mode: ${escapeHtml(paymentBreakdown(salePayments(sale)))}</div>
      <div>Goods once sold will not be taken back.</div>
      <div style="text-align:center; margin-top:6px">
        <div style="font-weight:700">For ${escapeHtml(store.name)}</div>
        <div style="border-top:1px solid #000; margin-top:18px; padding-top:2px">Authorised Signatory</div>
      </div>
    </div>
    <div class="note" style="border-top:1px solid #000">Computer generated invoice &middot; Item Management System</div>
  `
}

/** Builds a complete standalone HTML document for the invoice. */
export function buildInvoiceHtml(
  sale: Sale,
  store: InvoiceStoreInfo,
  options: InvoiceBuildOptions = {},
): string {
  const paper = options.paper ?? DEFAULT_INVOICE_PAPER
  const template = options.template ?? 'RETAIL'
  const spec = paperSpec(paper)
  const margin = invoicePaperMarginMm(paper)

  const body = spec.thermal
    ? buildThermalLayout(sale, store, spec)
    : buildA5Layout(sale, store, template, options)

  const widthCss = spec.thermal || paper.kind === 'CUSTOM'
    ? `width: ${spec.contentMm * 96 / 25.4}px; margin: 0 auto;`
    : ''

  return `<!doctype html>
<html>
<head>
<meta charset="utf-8" />
<title>Invoice ${escapeHtml(sale.salesNumber)}</title>
<style>
  @page { ${spec.pageRule}; margin: ${margin}mm; }
  :root { --fs: ${spec.fontPx}px; }
${INVOICE_CSS}
</style>
</head>
<body>
  <div class="doc${spec.thermal ? ' thermal' : ''}" style="${widthCss}">
    ${body.replace(/^[\s\n]+/, '')}
  </div>
</body>
</html>`
}

/** Preview dimensions for a paper at 96 dpi, used by the on-screen preview. */
export function invoicePreviewSize(paper: InvoicePaper): { width: number; height: number } {
  return paperSpec(paper).previewPx
}
