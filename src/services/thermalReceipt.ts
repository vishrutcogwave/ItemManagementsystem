import type { PaperWidth, Sale } from '../types'
import { formatDate, qty } from '../lib/format'

export interface ThermalLine {
  text: string
  bold?: boolean
  align?: 'left' | 'center' | 'right'
  doubleSize?: boolean
}

export interface StoreInfo {
  name: string
  gstNumber?: string | null
  address?: string | null
  mobile?: string | null
}

/** Heading printed under the store details. Change to 'Tax Invoice' if you prefer. */
const INVOICE_TITLE = 'Retail Invoice'

/**
 * Character used to fill the gaps between columns and in "label ........ value" rows.
 * Keep ' ' normally. If your print bridge collapses repeated spaces into one (the receipt then
 * prints "Item Qty Amt" with no gaps), try '.' or '_' here, or use buildSpacingTestLines() to
 * find out which characters survive on your printer.
 */
const FILL = '.'

export function paperColumns(paperWidth: PaperWidth | null | undefined): number {
  return paperWidth === 'MM58' ? 32 : 48
}

function amount(value: number | string | null | undefined): string {
  return Number(value ?? 0).toFixed(2)
}

/** The bridges print ASCII only; replace characters that change width after their own sanitising. */
function safe(text: string): string {
  return text
    .replaceAll('\u20B9', 'Rs')
    .replaceAll('\u2013', '-')
    .replaceAll('\u2014', '-')
    .replaceAll('\u2018', "'")
    .replaceAll('\u2019', "'")
    .replaceAll('\u201C', '"')
    .replaceAll('\u201D', '"')
    .replaceAll('\u2026', '...')
    .replaceAll('\t', ' ')
    .replace(/[^\x20-\x7E]/g, '?')
}

function wrap(text: string, width: number): string[] {
  const words = String(text ?? '').split(/\s+/).filter(Boolean)
  if (words.length === 0) return ['']
  const lines: string[] = []
  let current = ''
  for (const word of words) {
    if (word.length > width) {
      if (current) {
        lines.push(current)
        current = ''
      }
      for (let index = 0; index < word.length; index += width) {
        lines.push(word.slice(index, index + width))
      }
      continue
    }
    const candidate = current ? `${current} ${word}` : word
    if (candidate.length <= width) {
      current = candidate
    } else {
      lines.push(current)
      current = word
    }
  }
  if (current) lines.push(current)
  return lines
}

function padLeft(text: string, width: number): string {
  return text.length >= width ? text : FILL.repeat(width - text.length) + text
}

function padRight(text: string, width: number): string {
  return text.length >= width ? text : text + FILL.repeat(width - text.length)
}

function pair(left: string, right: string, width: number): string {
  return `${left}${FILL.repeat(Math.max(1, width - left.length - right.length))}${right}`
}

function center(text: string): ThermalLine {
  return { text, align: 'center' }
}

function divider(width: number, char = '-'): ThermalLine {
  return { text: char.repeat(width) }
}

function labeled(label: string, value: string, width: number): ThermalLine[] {
  const prefix = `${label}: `
  return wrap(value, Math.max(1, width - prefix.length)).map((part, index) => ({
    text: index === 0 ? `${prefix}${part}` : `${' '.repeat(prefix.length)}${part}`,
  }))
}

function priceLine(left: string, right: string, width: number): ThermalLine[] {
  const text = left.trim()
  if (text.length + 1 + right.length <= width) {
    return [{ text: pair(text, right, width) }]
  }
  const wrapped = wrap(text, width)
  const last = wrapped[wrapped.length - 1]
  if (last !== undefined && last.length + 1 + right.length <= width) {
    wrapped[wrapped.length - 1] = pair(last, right, width)
  } else {
    wrapped.push(padLeft(right, width))
  }
  return wrapped.map((part) => ({ text: part }))
}

function saleTime(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date
    .toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true })
    .replace(/[\u202f\u00a0]/g, ' ')
    .toUpperCase()
}

interface Column {
  text: string
  width: number
  align?: 'left' | 'center' | 'right'
}

function padCenter(text: string, width: number): string {
  if (text.length >= width) return text
  const total = width - text.length
  const left = Math.floor(total / 2)
  return FILL.repeat(left) + text + FILL.repeat(total - left)
}

function columnsLine(columns: Column[]): string {
  return columns
    .map((column) => {
      if (column.align === 'right') return padLeft(column.text, column.width)
      if (column.align === 'center') return padCenter(column.text, column.width)
      return padRight(column.text, column.width)
    })
    .join(FILL)
}

/**
 * Three-part "space-between" table: ITEM on the left, QTY centred on the middle of the line,
 * AMT flush right. The Sub Total row shares the same columns so everything lines up.
 */
function itemTable(items: NonNullable<Sale['items']>, width: number, totalQtyText: string, subtotalText: string) {
  const qtyTexts = items.map((line) => safe(`${qty(line.quantity)} ${line.unit ?? ''}`.trim()))
  const amountTexts = items.map((line) => amount(line.totalAmount))
  const qtyWidth = Math.max(3, totalQtyText.length, ...qtyTexts.map((text) => text.length))
  const neededAmountWidth = Math.max(3, subtotalText.length, ...amountTexts.map((text) => text.length))

  const qtyStart = Math.floor((width - qtyWidth) / 2)
  let itemWidth = qtyStart - 1
  let amountWidth = width - qtyStart - qtyWidth - 1
  if (amountWidth < neededAmountWidth) {
    // Very large amounts: give the amount what it needs and let the item column shrink
    amountWidth = neededAmountWidth
    itemWidth = width - qtyWidth - amountWidth - 2
  }
  return { qtyTexts, amountTexts, qtyWidth, amountWidth, itemWidth }
}

/**
 * GST rows grouped by rate, e.g. "CGST @ 14.00%" then "SGST @ 14.00%".
 * Rates are read from optional per-item fields (cgstRate / sgstRate / igstRate with the matching
 * amounts). If your items don't carry them, this falls back to the sale-level CGST/SGST/IGST totals.
 */
interface ItemTaxFields {
  cgstRate?: number | string | null
  sgstRate?: number | string | null
  igstRate?: number | string | null
  cgstAmount?: number | string | null
  sgstAmount?: number | string | null
  igstAmount?: number | string | null
}

function taxLines(sale: Sale, items: NonNullable<Sale['items']>, interState: boolean, width: number): ThermalLine[] {
  const groups: { name: string; rate: number; total: number }[] = []
  const add = (name: string, rateValue: unknown, amountValue: unknown) => {
    const rate = Number(rateValue ?? 0)
    const value = Number(amountValue ?? 0)
    if (!(rate > 0)) return
    const existing = groups.find((group) => group.name === name && group.rate === rate)
    if (existing) existing.total += value
    else groups.push({ name, rate, total: value })
  }

  for (const item of items) {
    const fields = item as unknown as ItemTaxFields
    if (interState) {
      add('IGST', fields.igstRate, fields.igstAmount)
    } else {
      add('CGST', fields.cgstRate, fields.cgstAmount)
      add('SGST', fields.sgstRate, fields.sgstAmount)
    }
  }

  if (groups.length > 0) {
    groups.sort((a, b) => b.rate - a.rate || a.name.localeCompare(b.name))
    return groups.map((group) => ({
      text: pair(`${group.name} @ ${group.rate.toFixed(2)}%`, amount(group.total), width),
    }))
  }

  if (interState) return [{ text: pair('IGST', amount(sale.igstAmount), width) }]
  return [
    { text: pair('CGST', amount(sale.cgstAmount), width) },
    { text: pair('SGST', amount(sale.sgstAmount), width) },
  ]
}

/**
 * Builds the retail invoice as text lines sized for a 58mm (32 column)
 * or 80mm (48 column) printer. Values come from the same Sale fields as before;
 * only the presentation changed.
 */
export function buildSaleReceiptLines(sale: Sale, store: StoreInfo, paperWidth: PaperWidth | null | undefined): ThermalLine[] {
  const width = paperColumns(paperWidth)
  const lines: ThermalLine[] = []
  const interState = Number(sale.igstAmount) > 0
  const time = saleTime(sale.createdAt)
  const items = sale.items ?? []

  lines.push({ text: '' })

  // ---------- Header ----------
  const storeName = safe(store.name ?? '').toUpperCase()
  if (storeName) {
    if (storeName.length <= Math.floor(width / 2)) {
      lines.push({ text: storeName, bold: true, align: 'center', doubleSize: true })
    } else {
      for (const part of wrap(storeName, width)) lines.push({ text: part, bold: true, align: 'center' })
    }
  }
  if (store.address) {
    for (const part of wrap(safe(store.address), width)) lines.push(center(part))
  }
  if (store.mobile) lines.push(center(`Phone: ${safe(store.mobile)}`))
  if (store.gstNumber) lines.push(center(`GSTIN: ${safe(store.gstNumber)}`))

  lines.push(divider(width))
  lines.push({ text: INVOICE_TITLE, bold: true, align: 'center' })
  lines.push(divider(width))

  // ---------- Bill details ----------
  const dateText = [formatDate(sale.salesDate), time].filter(Boolean).join(', ')
  lines.push(...labeled('Date', dateText, width))
  for (const part of wrap(safe(sale.customerName || 'Walk-in'), width)) {
    lines.push({ text: part, bold: true })
  }
  if (sale.customerMobile) lines.push(...labeled('Mobile', safe(sale.customerMobile), width))
  if (sale.customerState) lines.push(...labeled('Place of supply', safe(sale.customerState), width))
  lines.push(...labeled('Bill No', safe(sale.salesNumber), width))
  if (sale.branchName) lines.push(...labeled('Branch', safe(sale.branchName), width))
  lines.push(...labeled('Payment Mode', safe(String(sale.paymentMethod ?? '')), width))
  lines.push(divider(width))

  // ---------- Items ----------
  const totalQty = items.reduce((sum, line) => sum + Number(line.quantity ?? 0), 0)
  const totalQtyText = qty(totalQty)
  const subtotalText = amount(sale.subtotal)

  if (items.length > 0) {
    const table = itemTable(items, width, totalQtyText, subtotalText)
    if (table.itemWidth >= 6) {
      lines.push({
        text: columnsLine([
          { text: 'Item', width: table.itemWidth },
          { text: 'Qty', width: table.qtyWidth, align: 'center' },
          { text: 'Amt', width: table.amountWidth, align: 'right' },
        ]),
        bold: true,
      })
      lines.push(divider(width))
      items.forEach((line, index) => {
        const nameParts = wrap(safe(String(line.itemName ?? '')), table.itemWidth)
        lines.push({
          text: columnsLine([
            { text: nameParts[0] ?? '', width: table.itemWidth },
            { text: table.qtyTexts[index], width: table.qtyWidth, align: 'center' },
            { text: table.amountTexts[index], width: table.amountWidth, align: 'right' },
          ]),
        })
        for (const extra of nameParts.slice(1)) lines.push({ text: extra })
      })
      lines.push(divider(width))
      lines.push({
        text: columnsLine([
          { text: 'Sub Total', width: table.itemWidth },
          { text: totalQtyText, width: table.qtyWidth, align: 'center' },
          { text: subtotalText, width: table.amountWidth, align: 'right' },
        ]),
      })
    } else {
      // Very narrow layout: name on its own lines, qty x rate with the amount flush right
      for (const line of items) {
        for (const part of wrap(safe(String(line.itemName ?? '')), width)) lines.push({ text: part })
        lines.push(
          ...priceLine(
            `  ${qty(line.quantity)} ${safe(line.unit ?? '')} x ${amount(line.sellingRate)}`,
            amount(line.totalAmount),
            width,
          ),
        )
      }
      lines.push(divider(width))
      lines.push({ text: pair(`Sub Total (${totalQtyText})`, subtotalText, width) })
    }
  } else {
    lines.push({ text: pair('Sub Total', subtotalText, width) })
  }

  // ---------- Discount and tax ----------
  if (Number(sale.discount) > 0) {
    const discountWidth = Math.min(width, Math.round(width * 0.6))
    lines.push({ text: pair('(-) Discount', amount(sale.discount), discountWidth), align: 'right' })
  }
  const taxWidth = Math.min(width, Math.max(Math.round(width * 0.5), 20))
  for (const taxLine of taxLines(sale, items, interState, taxWidth)) {
    lines.push({ ...taxLine, align: 'right' })
  }
  lines.push(divider(width))

  // ---------- Total ----------
  lines.push({ text: pair('TOTAL', `Rs ${amount(sale.grandTotal)}`, width), bold: true })
  lines.push(divider(width))

  // ---------- Payment ----------
  const method = safe(String(sale.paymentMethod ?? '')) || 'Paid'
  lines.push({ text: pair(`${method} :`, `Rs ${amount(sale.paidAmount)}`, width) })
  if (Number(sale.balanceAmount) > 0) {
    lines.push({ text: pair('Balance Due :', `Rs ${amount(sale.balanceAmount)}`, width), bold: true })
  }
  lines.push(divider(width))

  // ---------- Footer ----------
  lines.push({ text: 'E & O.E', align: 'right' })
  lines.push({ text: 'Thank you for dining with us!', bold: true, align: 'center' })
  lines.push(center('Please visit again'))
  lines.push({ text: '' })
  for (const part of wrap('Powered by Item Management System', width)) lines.push(center(part))
  return lines
}

/**
 * Diagnostic receipt: prints the same "Item ... Qty ... Amt" row using different filler characters.
 * Print it once and see which rows keep their gaps; use that character for FILL above.
 * (Characters are sent as-is, so the bridge's own sanitising decides what survives.)
 */
export function buildSpacingTestLines(paperWidth: PaperWidth | null | undefined): ThermalLine[] {
  const width = paperColumns(paperWidth)
  const fillers: [label: string, char: string][] = [
    ['A space', ' '],
    ['B nbsp', '\u00a0'],
    ['C dot', '.'],
    ['D underscore', '_'],
    ['E dash', '-'],
  ]
  const lines: ThermalLine[] = [{ text: 'SPACING TEST', bold: true, align: 'center' }, divider(width)]
  for (const [label, char] of fillers) {
    const gap = Math.floor((width - 14) / 2)
    lines.push({ text: `${label}:` })
    lines.push({ text: `Item${char.repeat(gap)}Qty${char.repeat(width - 10 - gap)}Amt` })
  }
  lines.push(divider(width))
  lines.push({ text: 'Tell which rows kept the gaps', align: 'center' })
  return lines
}
