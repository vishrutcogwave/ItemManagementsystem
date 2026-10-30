// Mirrors the server GstCalculator so forms can preview totals before saving.
// The backend always recalculates and persists the authoritative values.

export interface LineTax {
  taxableAmount: number
  cgstAmount: number
  sgstAmount: number
  igstAmount: number
  taxAmount: number
  payableAmount: number
}

export interface BillLine {
  gross: number
  discount: number
  gstPercentage: number
}

export interface BillPreview {
  subtotal: number
  discount: number
  taxable: number
  cgst: number
  sgst: number
  igst: number
  tax: number
  grand: number
  lines: (LineTax & { discount: number })[]
}

function round2(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100
}

export function normalizeState(state: string | null | undefined): string | null {
  const normalized = (state ?? '').trim().replace(/\s+/g, ' ').toLowerCase()
  return normalized === '' ? null : normalized
}

export function isInterState(
  branchState: string | null | undefined,
  partyState: string | null | undefined,
): boolean {
  const left = normalizeState(branchState)
  const right = normalizeState(partyState)
  return left !== null && right !== null && left !== right
}

export function lineTax(
  gross: number,
  discount: number,
  gstPercentage: number,
  inclusive: boolean,
  interState: boolean,
): LineTax {
  const net = Math.max(0, round2(gross) - round2(discount))
  const gst = Math.max(0, Number(gstPercentage) || 0)
  let taxable: number
  let taxAmount: number
  if (gst <= 0) {
    taxable = net
    taxAmount = 0
  } else if (inclusive) {
    const divisor = 1 + gst / 100
    taxable = round2(net / divisor)
    taxAmount = round2(net - taxable)
  } else {
    taxable = net
    taxAmount = round2((taxable * gst) / 100)
  }
  let cgst = 0
  let sgst = 0
  let igst = 0
  if (interState) {
    igst = taxAmount
  } else {
    cgst = round2(taxAmount / 2)
    sgst = round2(taxAmount - cgst)
  }
  return {
    taxableAmount: taxable,
    cgstAmount: cgst,
    sgstAmount: sgst,
    igstAmount: igst,
    taxAmount,
    payableAmount: round2(taxable + taxAmount),
  }
}

export function billPreview(
  lines: BillLine[],
  headerDiscount: number,
  inclusive: boolean,
  interState: boolean,
): BillPreview {
  const subtotal = round2(lines.reduce((sum, line) => sum + Math.max(0, line.gross), 0))
  const header = Math.min(Math.max(0, round2(headerDiscount)), subtotal)
  let allocated = 0
  let discount = 0
  let taxable = 0
  let cgst = 0
  let sgst = 0
  let igst = 0
  let tax = 0
  const resultLines: (LineTax & { discount: number })[] = []
  lines.forEach((line, index) => {
    let share = 0
    if (header > 0) {
      if (index === lines.length - 1) {
        share = round2(header - allocated)
      } else if (subtotal > 0) {
        share = round2((header * Math.max(0, line.gross)) / subtotal)
      }
      allocated = round2(allocated + share)
    }
    const lineDiscount = round2(Math.max(0, line.discount) + share)
    const result = lineTax(line.gross, lineDiscount, line.gstPercentage, inclusive, interState)
    resultLines.push({ ...result, discount: lineDiscount })
    discount = round2(discount + lineDiscount)
    taxable = round2(taxable + result.taxableAmount)
    cgst = round2(cgst + result.cgstAmount)
    sgst = round2(sgst + result.sgstAmount)
    igst = round2(igst + result.igstAmount)
    tax = round2(tax + result.taxAmount)
  })
  return {
    subtotal,
    discount,
    taxable,
    cgst,
    sgst,
    igst,
    tax,
    grand: round2(taxable + tax),
    lines: resultLines,
  }
}
