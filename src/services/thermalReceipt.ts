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
  return text.length >= width ? text : ' '.repeat(width - text.length) + text
}

function padRight(text: string, width: number): string {
  return text.length >= width ? text : text + ' '.repeat(width - text.length)
}

function pair(left: string, right: string, width: number): string {
  return `${left}${' '.repeat(Math.max(1, width - left.length - right.length))}${right}`
}

function center(text: string): ThermalLine {
  return { text, align: 'center' }
}

function divider(width: number, char = '-'): ThermalLine {
  return { text: char.repeat(width) }
}

function pushMeta(
  lines: ThermalLine[],
  left: [label: string, value: string],
  right: [label: string, value: string],
  width: number,
): void {
  const leftText = left[1] ? `${left[0]}: ${left[1]}` : ''
  const rightText = right[1] ? `${right[0]}: ${right[1]}` : ''
  if (leftText && rightText && leftText.length + 1 + rightText.length <= width) {
    lines.push({ text: pair(leftText, rightText, width) })
    return
  }
  if (leftText) lines.push(...labeled(left[0], left[1], width))
  if (rightText) lines.push(...labeled(right[0], right[1], width))
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

function labeled(label: string, value: string, width: number): ThermalLine[] {
  const prefix = `${label}: `
  return wrap(value, Math.max(1, width - prefix.length)).map((part, index) => ({
    text: index === 0 ? `${prefix}${part}` : `${' '.repeat(prefix.length)}${part}`,
  }))
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
  align?: 'left' | 'right'
}

function columnsLine(columns: Column[]): string {
  return columns
    .map((column) => (column.align === 'right' ? padLeft(column.text, column.width) : padRight(column.text, column.width)))
    .join(' ')
}

function itemTable(items: NonNullable<Sale['items']>, width: number) {
  const qtyTexts = items.map((line) => safe(`${qty(line.quantity)} ${line.unit ?? ''}`.trim()))
  const rateTexts = items.map((line) => amount(line.sellingRate))
  const amountTexts = items.map((line) => amount(line.totalAmount))
  const qtyWidth = Math.max(3, ...qtyTexts.map((text) => text.length))
  const rateWidth = Math.max(4, ...rateTexts.map((text) => text.length))
  const amountWidth = Math.max(6, ...amountTexts.map((text) => text.length))
  return {
    qtyTexts,
    rateTexts,
    amountTexts,
    qtyWidth,
    rateWidth,
    amountWidth,
    itemWidth: width - qtyWidth - rateWidth - amountWidth - 3,
  }
}

/**
 * Builds the restaurant-style receipt as text lines sized for a 58mm (32 column)
 * or 80mm (48 column) printer. Values come from the same Sale fields as before;
 * only the presentation changed.
 */
export function buildSaleReceiptLines(sale: Sale, store: StoreInfo, paperWidth: PaperWidth | null | undefined): ThermalLine[] {
  const width = paperColumns(paperWidth)
  const lines: ThermalLine[] = []
  const interState = Number(sale.igstAmount) > 0
  const time = saleTime(sale.createdAt)

  lines.push({ text: '' })

  const storeName = safe(store.name ?? '')
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
  if (store.mobile) lines.push(center(`Ph: ${safe(store.mobile)}`))
  if (store.gstNumber) lines.push(center(`GSTIN: ${safe(store.gstNumber)}`))

  lines.push(divider(width))
  pushMeta(lines, ['Bill No', safe(sale.salesNumber)], ['Date', formatDate(sale.salesDate)], width)
  pushMeta(lines, ['Time', time], ['Branch', safe(sale.branchName ?? '')], width)
  lines.push(divider(width))

  lines.push(...labeled('Customer', safe(sale.customerName || 'Walk-in'), width))
  if (sale.customerMobile) lines.push(...labeled('Mobile', safe(sale.customerMobile), width))
  if (sale.customerState) lines.push(...labeled('Place of supply', safe(sale.customerState), width))
  lines.push(divider(width))

  const items = sale.items ?? []
  if (items.length > 0) {
    const table = itemTable(items, width)
    if (table.itemWidth >= 6) {
      lines.push({
        text: columnsLine([
          { text: 'ITEM', width: table.itemWidth },
          { text: 'QTY', width: table.qtyWidth, align: 'right' },
          { text: 'RATE', width: table.rateWidth, align: 'right' },
          { text: 'AMOUNT', width: table.amountWidth, align: 'right' },
        ]),
        bold: true,
      })
      lines.push(divider(width))
      items.forEach((line, index) => {
        const nameParts = wrap(safe(String(line.itemName ?? '')), table.itemWidth)
        lines.push({
          text: columnsLine([
            { text: nameParts[0] ?? '', width: table.itemWidth },
            { text: table.qtyTexts[index], width: table.qtyWidth, align: 'right' },
            { text: table.rateTexts[index], width: table.rateWidth, align: 'right' },
            { text: table.amountTexts[index], width: table.amountWidth, align: 'right' },
          ]),
        })
        for (const extra of nameParts.slice(1)) lines.push({ text: extra })
      })
    } else {
      for (const line of items) {
        for (const part of wrap(safe(String(line.itemName ?? '')), width)) lines.push({ text: part })
        lines.push(
          ...priceLine(
            `  ${qty(line.quantity)} ${line.unit ?? ''} x ${amount(line.sellingRate)}`,
            amount(line.totalAmount),
            width,
          ),
        )
      }
    }
    lines.push(divider(width))
  }

  lines.push({ text: pair('Subtotal', amount(sale.subtotal), width) })
  if (Number(sale.discount) > 0) {
    lines.push({ text: pair('Discount', `- ${amount(sale.discount)}`, width) })
  }
  lines.push({ text: pair('Taxable value', amount(sale.taxableAmount), width) })
  if (interState) {
    lines.push({ text: pair('IGST', amount(sale.igstAmount), width) })
  } else {
    lines.push({ text: pair('CGST', amount(sale.cgstAmount), width) })
    lines.push({ text: pair('SGST', amount(sale.sgstAmount), width) })
  }
  lines.push({ text: pair('Total tax', amount(sale.tax), width) })
  lines.push(divider(width, '='))
  lines.push({ text: pair('GRAND TOTAL', amount(sale.grandTotal), width), bold: true })
  lines.push(divider(width))

  lines.push({ text: `Payment: ${sale.paymentMethod}` })
  lines.push({ text: pair('Paid', amount(sale.paidAmount), width) })
  if (Number(sale.balanceAmount) > 0) {
    lines.push({ text: pair('Balance', amount(sale.balanceAmount), width), bold: true })
  }
  lines.push(divider(width))

  lines.push(center('Thank you for dining with us!'))
  lines.push(center('Please visit again'))
  lines.push(center('Item Management System'))
  return lines
}
