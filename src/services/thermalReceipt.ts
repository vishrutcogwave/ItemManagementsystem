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
}

export function paperColumns(paperWidth: PaperWidth | null | undefined): number {
  return paperWidth === 'MM58' ? 32 : 48
}

function amount(value: number | string | null | undefined): string {
  return Number(value ?? 0).toFixed(2)
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

function pair(left: string, right: string, width: number): string {
  const gap = Math.max(1, width - left.length - right.length)
  return `${left}${' '.repeat(gap)}${right}`
}

function center(text: string): ThermalLine {
  return { text, align: 'center' }
}

function divider(width: number): ThermalLine {
  return { text: '-'.repeat(width) }
}

/**
 * Builds the same receipt content as the existing browser receipt, but as
 * text lines sized for a 58mm (32 column) or 80mm (48 column) printer.
 * The bridges only execute ESC/POS, so both platforms print identically.
 */
export function buildSaleReceiptLines(sale: Sale, store: StoreInfo, paperWidth: PaperWidth | null | undefined): ThermalLine[] {
  const width = paperColumns(paperWidth)
  const lines: ThermalLine[] = []
  const interState = Number(sale.igstAmount) > 0

  lines.push({ text: '' })
  lines.push({ text: store.name, bold: true, align: 'center', doubleSize: true })
  if (sale.branchName) lines.push(center(sale.branchName))
  if (store.gstNumber) lines.push(center(`GSTIN ${store.gstNumber}`))
  lines.push(center(`${formatDate(sale.salesDate)}  ${sale.salesNumber}`))
  lines.push(divider(width))

  lines.push({ text: `Customer: ${sale.customerName || 'Walk-in'}` })
  if (sale.customerMobile) lines.push({ text: `Mobile: ${sale.customerMobile}` })
  if (sale.customerState) lines.push({ text: `Place of supply: ${sale.customerState}` })
  lines.push(divider(width))

  for (const line of sale.items ?? []) {
    for (const wrapped of wrap(String(line.itemName ?? ''), width)) {
      lines.push({ text: wrapped })
    }
    lines.push({
      text: pair(
        `  ${qty(line.quantity)} ${line.unit} x ${amount(line.sellingRate)}`,
        amount(line.totalAmount),
        width,
      ),
    })
  }
  lines.push(divider(width))

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
  lines.push({ text: pair('TOTAL', `Rs ${amount(sale.grandTotal)}`, width), bold: true, doubleSize: width >= 48 })
  lines.push({ text: pair(`Paid (${sale.paymentMethod})`, amount(sale.paidAmount), width) })
  if (Number(sale.balanceAmount) > 0) {
    lines.push({ text: pair('Balance', amount(sale.balanceAmount), width), bold: true })
  }

  lines.push({ text: '' })
  lines.push(center('Thank you for your business!'))
  lines.push(center('Item Management System'))
  lines.push({ text: '' })
  return lines
}
