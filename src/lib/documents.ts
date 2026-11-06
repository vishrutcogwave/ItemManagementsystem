import type { Purchase, Sale } from '../types'
import { printHtml } from './export'
import { formatDate, money, qty } from './format'

function escapeHtml(value: string | null | undefined): string {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

interface StoreInfo {
  name: string
  gstNumber?: string | null
  address?: string | null
  mobile?: string | null
}

export function printPurchase(purchase: Purchase, store: StoreInfo): void {
  const interState = Number(purchase.igstAmount) > 0
  const rows = purchase.items
    .map(
      (line) => `<tr>
        <td>${escapeHtml(line.itemName)}<div class="muted">${escapeHtml(line.itemCode)}</div></td>
        <td class="right">${qty(line.quantity)} ${line.unit}</td>
        <td class="right">${money(line.purchaseRate)}</td>
        <td class="right">${money(line.discount)}</td>
        <td class="right">${money(line.tax)}<div class="muted">${escapeHtml(line.taxName)} ${Number(line.gstPercentage ?? 0)}%</div></td>
        <td class="right">${money(line.totalAmount ?? 0)}</td>
      </tr>`,
    )
    .join('')

  printHtml(
    `<div class="title-row">
      <div>
        <p class="brand">${escapeHtml(store.name)}</p>
        <h1>Purchase invoice</h1>
        <p class="muted">${escapeHtml(purchase.purchaseNumber)}${purchase.invoiceNumber ? ` · Supplier invoice ${escapeHtml(purchase.invoiceNumber)}` : ''}</p>
      </div>
      <div class="right">
        <p><strong>${escapeHtml(purchase.branchName)}</strong></p>
        <p class="muted">${formatDate(purchase.purchaseDate)}</p>
        <p class="muted">Status: ${escapeHtml(purchase.status)}</p>
      </div>
    </div>
    <div class="grid">
      <div class="box"><strong>Supplier</strong><br/>${escapeHtml(purchase.supplierName)}</div>
      <div class="box"><strong>Payment</strong><br/>Paid ${money(purchase.paidAmount)} · Balance ${money(purchase.balanceAmount)}</div>
    </div>
    <table>
      <thead><tr><th>Item</th><th class="right">Qty</th><th class="right">Rate</th><th class="right">Discount</th><th class="right">Tax</th><th class="right">Amount</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
    <div class="totals">
      <div><span>Subtotal</span><span>${money(purchase.subtotal)}</span></div>
      <div><span>Discount</span><span>− ${money(purchase.discount)}</span></div>
      <div><span>Taxable value</span><span>${money(purchase.taxableAmount)}</span></div>
      ${
        interState
          ? `<div><span>IGST</span><span>${money(purchase.igstAmount)}</span></div>`
          : `<div><span>CGST</span><span>${money(purchase.cgstAmount)}</span></div>
             <div><span>SGST</span><span>${money(purchase.sgstAmount)}</span></div>`
      }
      <div><span>Total tax</span><span>${money(purchase.tax)}</span></div>
      <div class="grand"><span>Grand total</span><span>${money(purchase.grandTotal)}</span></div>
    </div>
    ${purchase.notes ? `<p class="muted" style="margin-top:14px">Notes: ${escapeHtml(purchase.notes)}</p>` : ''}`,
    `Purchase ${purchase.purchaseNumber}`,
  )
}

export function printSaleReceipt(sale: Sale, store: StoreInfo): void {
  const interState = Number(sale.igstAmount) > 0
  const time = sale.createdAt
    ? new Date(sale.createdAt)
        .toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true })
        .toUpperCase()
    : ''
  const rule = 'border-top:1px dashed #9ca3af;margin:8px 0'
  const totalRow = (label: string, value: string, strong = false) =>
    `<div style="display:flex;justify-content:space-between;gap:12px;${strong ? 'font-weight:700;' : ''}"><span>${label}</span><span style="white-space:nowrap">${value}</span></div>`
  const rows = sale.items
    .map(
      (line) => `<tr>
        <td style="padding:3px 6px 3px 0;vertical-align:top;border-bottom:0">${escapeHtml(line.itemName)}</td>
        <td style="padding:3px 0;text-align:right;white-space:nowrap;vertical-align:top;border-bottom:0">${escapeHtml(`${qty(line.quantity)} ${line.unit}`)}</td>
        <td style="padding:3px 0;text-align:right;white-space:nowrap;vertical-align:top;border-bottom:0">${money(line.sellingRate)}</td>
        <td style="padding:3px 0;text-align:right;white-space:nowrap;vertical-align:top;border-bottom:0">${money(line.totalAmount ?? 0)}</td>
      </tr>`,
    )
    .join('')

  printHtml(
    `<div style="max-width:300px;margin:0 auto;font-family:'Courier New',monospace;font-size:11px;line-height:1.45;color:#111">
      <div style="text-align:center;margin-bottom:8px">
        <p style="font-size:15px;font-weight:700;margin:0;letter-spacing:0.02em">${escapeHtml(store.name)}</p>
        ${store.address ? `<p style="margin:2px 0">${escapeHtml(store.address)}</p>` : ''}
        ${store.mobile ? `<p style="margin:2px 0">Ph: ${escapeHtml(store.mobile)}</p>` : ''}
        ${store.gstNumber ? `<p style="margin:2px 0">GSTIN: ${escapeHtml(store.gstNumber)}</p>` : ''}
      </div>
      <div style="${rule};padding-top:6px">
        <div style="display:flex;justify-content:space-between;gap:10px"><span>Bill No: ${escapeHtml(sale.salesNumber)}</span><span>Date: ${formatDate(sale.salesDate)}</span></div>
        <div style="display:flex;justify-content:space-between;gap:10px"><span>${time ? `Time: ${escapeHtml(time)}` : ''}</span><span>Branch: ${escapeHtml(sale.branchName)}</span></div>
      </div>
      <div style="${rule};padding:2px 0">
        <div>Customer: ${escapeHtml(sale.customerName || 'Walk-in')}</div>
        ${sale.customerMobile ? `<div>Mobile: ${escapeHtml(sale.customerMobile)}</div>` : ''}
        ${sale.customerState ? `<div>Place of supply: ${escapeHtml(sale.customerState)}</div>` : ''}
      </div>
      <table style="width:100%;border-collapse:collapse;margin:0;font-size:11px">
        <thead><tr>
          <th style="text-align:left;padding:3px 6px 3px 0;border-bottom:1px dashed #9ca3af;background:transparent;font-size:10px;letter-spacing:0.04em">ITEM</th>
          <th style="text-align:right;padding:3px 0;border-bottom:1px dashed #9ca3af;background:transparent;font-size:10px;letter-spacing:0.04em;white-space:nowrap">QTY</th>
          <th style="text-align:right;padding:3px 0;border-bottom:1px dashed #9ca3af;background:transparent;font-size:10px;letter-spacing:0.04em;white-space:nowrap">RATE</th>
          <th style="text-align:right;padding:3px 0;border-bottom:1px dashed #9ca3af;background:transparent;font-size:10px;letter-spacing:0.04em;white-space:nowrap">AMOUNT</th>
        </tr></thead>
        <tbody>${rows}</tbody>
      </table>
      <div style="${rule};padding-top:6px">
        ${totalRow('Subtotal', money(sale.subtotal))}
        ${totalRow('Discount', `− ${money(sale.discount)}`)}
        ${totalRow('Taxable value', money(sale.taxableAmount))}
        ${
          interState
            ? totalRow('IGST', money(sale.igstAmount))
            : `${totalRow('CGST', money(sale.cgstAmount))}${totalRow('SGST', money(sale.sgstAmount))}`
        }
        ${totalRow('Total tax', money(sale.tax))}
        <div style="display:flex;justify-content:space-between;gap:12px;border-top:1px dashed #9ca3af;margin-top:4px;padding-top:4px;font-weight:700;font-size:13px"><span>GRAND TOTAL</span><span style="white-space:nowrap">${money(sale.grandTotal)}</span></div>
      </div>
      <div style="${rule};padding-top:6px">
        <div>Payment: ${escapeHtml(sale.paymentMethod)}</div>
        ${totalRow('Paid', money(sale.paidAmount))}
        ${Number(sale.balanceAmount) > 0 ? totalRow('Balance', money(sale.balanceAmount), true) : ''}
      </div>
      <div style="text-align:center;margin-top:12px">
        <p style="margin:2px 0">Thank you for dining with us!</p>
        <p style="margin:2px 0">Please visit again</p>
        <p class="muted" style="margin:2px 0;font-size:10px">Item Management System</p>
      </div>
    </div>`,
    `Receipt ${sale.salesNumber}`,
  )
}
