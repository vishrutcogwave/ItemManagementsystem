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
  const rows = sale.items
    .map(
      (line) => `<tr>
        <td>${escapeHtml(line.itemName)}<div class="muted">${qty(line.quantity)} ${line.unit} × ${money(line.sellingRate)}</div></td>
        <td class="right">${money(line.totalAmount ?? 0)}</td>
      </tr>`,
    )
    .join('')

  printHtml(
    `<div style="max-width:300px;margin:0 auto;font-family:'Courier New',monospace;font-size:11px">
      <div style="text-align:center;margin-bottom:10px">
        <p style="font-size:14px;font-weight:700;margin:0">${escapeHtml(store.name)}</p>
        <p class="muted" style="margin:2px 0">${escapeHtml(sale.branchName)}</p>
        ${store.gstNumber ? `<p class="muted" style="margin:2px 0">GSTIN ${escapeHtml(store.gstNumber)}</p>` : ''}
        <p class="muted" style="margin:2px 0">${formatDate(sale.salesDate)} · ${escapeHtml(sale.salesNumber)}</p>
      </div>
      <div style="border-top:1px dashed #9ca3af;border-bottom:1px dashed #9ca3af;padding:6px 0;margin-bottom:6px">
        <p style="margin:2px 0">Customer: ${escapeHtml(sale.customerName || 'Walk-in')}</p>
        ${sale.customerMobile ? `<p style="margin:2px 0">Mobile: ${escapeHtml(sale.customerMobile)}</p>` : ''}
        ${sale.customerState ? `<p style="margin:2px 0">Place of supply: ${escapeHtml(sale.customerState)}</p>` : ''}
      </div>
      <table style="margin:0;font-size:11px">
        <tbody>${rows}</tbody>
      </table>
      <div style="border-top:1px dashed #9ca3af;margin-top:8px;padding-top:6px">
        <div style="display:flex;justify-content:space-between"><span>Subtotal</span><span>${money(sale.subtotal)}</span></div>
        <div style="display:flex;justify-content:space-between"><span>Discount</span><span>− ${money(sale.discount)}</span></div>
        <div style="display:flex;justify-content:space-between"><span>Taxable value</span><span>${money(sale.taxableAmount)}</span></div>
        ${
          interState
            ? `<div style="display:flex;justify-content:space-between"><span>IGST</span><span>${money(sale.igstAmount)}</span></div>`
            : `<div style="display:flex;justify-content:space-between"><span>CGST</span><span>${money(sale.cgstAmount)}</span></div>
               <div style="display:flex;justify-content:space-between"><span>SGST</span><span>${money(sale.sgstAmount)}</span></div>`
        }
        <div style="display:flex;justify-content:space-between"><span>Total tax</span><span>${money(sale.tax)}</span></div>
        <div style="display:flex;justify-content:space-between;font-weight:700;font-size:13px;margin-top:4px"><span>Total</span><span>${money(sale.grandTotal)}</span></div>
        <div style="display:flex;justify-content:space-between;margin-top:4px"><span>Paid (${escapeHtml(sale.paymentMethod)})</span><span>${money(sale.paidAmount)}</span></div>
        ${Number(sale.balanceAmount) > 0 ? `<div style="display:flex;justify-content:space-between"><span>Balance</span><span>${money(sale.balanceAmount)}</span></div>` : ''}
      </div>
      <p style="text-align:center;margin-top:14px" class="muted">Thank you for your business!</p>
      <p style="text-align:center;margin-top:4px" class="muted">Item Management System</p>
    </div>`,
    `Receipt ${sale.salesNumber}`,
  )
}
