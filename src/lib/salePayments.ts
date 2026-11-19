import type { PaymentMethod, Sale, SalePayment } from '../types'
import { money } from './format'

export const collectionMethods = ['CASH', 'UPI', 'CARD', 'BANK'] as const

export const paymentLabels: Record<PaymentMethod, string> = {
  CASH: 'Cash',
  UPI: 'UPI',
  CARD: 'Card',
  BANK: 'Bank',
  CREDIT: 'Credit',
}

export function salePayments(sale: Sale): SalePayment[] {
  return sale.payments ?? (Number(sale.paidAmount) > 0
    ? [{ method: sale.paymentMethod, amount: Number(sale.paidAmount) }]
    : [])
}

export function paymentBreakdown(payments: SalePayment[]): string {
  return payments.length
    ? payments.map(({ method, amount }) => `${paymentLabels[method]} ${money(amount)}`).join(' · ')
    : 'Unpaid'
}
