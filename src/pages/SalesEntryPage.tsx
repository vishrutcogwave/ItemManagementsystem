import { Plus, Printer, Trash2 } from 'lucide-react'
import { useState } from 'react'
import {
  Button,
  Card,
  Field,
  Input,
  PageHeader,
  Select,
} from '../components/ui'
import { ScanBar } from '../components/barcode/ScanBar'
import { useApi } from '../hooks/useApi'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { money, qty, today } from '../lib/format'
import { autoPrintSale, printSaleReceiptForDevice } from '../services/printerService'
import { billPreview, isInterState } from '../lib/tax'
import { unitOptionsFor } from '../lib/units'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type { Item, PaymentMethod, Sale, Unit } from '../types'

interface LineForm {
  key: number
  itemId: number | ''
  quantity: string
  unit: Unit
  sellingRate: string
  discount: string
}

let lineKey = 0
const newLine = (): LineForm => ({
  key: ++lineKey,
  itemId: '',
  quantity: '1',
  unit: 'PIECE',
  sellingRate: '',
  discount: '0',
})

const toNumber = (value: string) => {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

export function SalesEntryPage() {
  const { branches, selectedBranchId, store } = useAuthStore()
  const storeInfo = { name: store?.storeName ?? 'Store', gstNumber: store?.gstNumber }
  const [branchId, setBranchId] = useState<number | ''>(selectedBranchId ?? branches[0]?.id ?? '')
  const [salesDate, setSalesDate] = useState(today())
  const [customerName, setCustomerName] = useState('')
  const [customerMobile, setCustomerMobile] = useState('')
  const [customerState, setCustomerState] = useState('')
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>('CASH')
  const [discount, setDiscount] = useState('0')
  const [paidAmount, setPaidAmount] = useState('')
  const [lines, setLines] = useState<LineForm[]>([newLine()])
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [completedSale, setCompletedSale] = useState<Sale | null>(null)
  const [printingSale, setPrintingSale] = useState(false)

  const { data: items } = useApi<Item[]>(() => api.get('/items/options').then((response) => response.data), [])

  const inclusive = Boolean(store?.taxInclusive)
  const branchState = branches.find((branch) => branch.id === branchId)?.state ?? null
  const interState = isInterState(branchState, customerState)
  const preview = billPreview(
    lines.map((line) => ({
      gross: toNumber(line.quantity) * toNumber(line.sellingRate),
      discount: toNumber(line.discount),
      gstPercentage: items?.find((entry) => entry.id === line.itemId)?.gstPercentage ?? 0,
    })),
    toNumber(discount),
    inclusive,
    interState,
  )

  const updateLine = (key: number, patch: Partial<LineForm>) => {
    setLines((current) => current.map((line) => (line.key === key ? { ...line, ...patch } : line)))
  }

  const chooseItem = (key: number, itemIdRaw: string) => {
    const itemId = itemIdRaw ? Number(itemIdRaw) : ''
    const item = items?.find((entry) => entry.id === itemId)
    updateLine(key, { itemId, unit: item?.baseUnit ?? 'PIECE', sellingRate: '' })
  }

  const removeLine = (key: number) => {
    setLines((current) => (current.length === 1 ? current : current.filter((line) => line.key !== key)))
  }

  const scanItem = (code: string) => {
    const match = items?.find((entry) => entry.barcode.toLowerCase() === code.toLowerCase())
    if (!match) {
      notifyError(`No active item uses barcode ${code}`)
      return false
    }
    setLines((current) => {
      const empty = current.find((line) => !line.itemId)
      if (empty) {
        return current.map((line) =>
          line.key === empty.key
            ? { ...line, itemId: match.id, unit: match.baseUnit, sellingRate: '' }
            : line,
        )
      }
      return [
        ...current,
        {
          key: ++lineKey,
          itemId: match.id,
          quantity: '1',
          unit: match.baseUnit,
          sellingRate: '',
          discount: '0',
        },
      ]
    })
    notify(`${match.itemName} added to the bill`)
    return true
  }

  const reset = () => {
    setSalesDate(today())
    setCustomerName('')
    setCustomerMobile('')
    setCustomerState('')
    setPaymentMethod('CASH')
    setDiscount('0')
    setPaidAmount('')
    setLines([newLine()])
    setErrors({})
    setFormError(null)
  }

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!branchId) nextErrors.branchId = 'Select a branch'
    if (!salesDate) nextErrors.salesDate = 'Sale date is required'
    const valid = lines.every(
      (line) => line.itemId && toNumber(line.quantity) > 0 && toNumber(line.sellingRate) > 0,
    )
    if (!valid) nextErrors.items = 'Select an item and enter quantity and selling rate for every line'
    const paid = paidAmount === '' ? null : toNumber(paidAmount)
    if (paid !== null && paid > preview.grand) nextErrors.paidAmount = 'Paid amount cannot exceed the bill total'
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors)
      return
    }

    setSaving(true)
    setFormError(null)
    setErrors({})
    try {
      const { data } = await api.post<Sale>('/sales', {
        branchId,
        salesDate,
        customerName: customerName.trim() || undefined,
        customerMobile: customerMobile.trim() || undefined,
        customerState: customerState.trim() || undefined,
        paymentMethod,
        discount: toNumber(discount),
        paidAmount: paid ?? undefined,
        status: 'COMPLETED',
        items: lines.map((line) => ({
          itemId: line.itemId,
          quantity: toNumber(line.quantity),
          unit: line.unit,
          sellingRate: toNumber(line.sellingRate),
          discount: toNumber(line.discount),
        })),
      })
      notify(`Sale ${data.salesNumber} completed`)
      setCompletedSale(data)
      reset()
      void autoPrintSale(data, storeInfo).then((outcome) => {
        if (outcome?.status === 'FAILED') notifyError(outcome.message ?? 'The receipt could not be printed')
      })
    } catch (err) {
      setFormError(errorMessage(err))
      setErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  const print = async (sale: Sale) => {
    if (printingSale) return
    setPrintingSale(true)
    try {
      const outcome = await printSaleReceiptForDevice(sale, storeInfo)
      if (!outcome) return
      if (outcome.status === 'PRINTED') notify('Receipt sent to the printer')
      else if (outcome.status === 'DUPLICATE') notify('This receipt was already printed')
      else if (outcome.message) notifyError(outcome.message)
    } finally {
      setPrintingSale(false)
    }
  }

  return (
    <div>
      <PageHeader
        title="Sales entry"
        subtitle="Manual billing with a negotiated selling rate per line. The item master rate is not applied here."
      />

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
        <div className="space-y-4">
          <Card className="p-4">
            {formError && (
              <div className="mb-3 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
                {formError}
              </div>
            )}
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <Field label="Branch" required error={errors.branchId}>
                <Select
                  value={branchId}
                  onChange={(event) => setBranchId(event.target.value ? Number(event.target.value) : '')}
                >
                  <option value="">Select branch</option>
                  {branches.map((branch) => (
                    <option key={branch.id} value={branch.id}>
                      {branch.branchName}
                    </option>
                  ))}
                </Select>
              </Field>
              <Field label="Sale date" required error={errors.salesDate}>
                <Input type="date" value={salesDate} max={today()} onChange={(event) => setSalesDate(event.target.value)} />
              </Field>
              <Field label="Payment method">
                <Select
                  value={paymentMethod}
                  onChange={(event) => setPaymentMethod(event.target.value as PaymentMethod)}
                >
                  <option value="CASH">Cash</option>
                  <option value="UPI">UPI</option>
                  <option value="CARD">Card</option>
                  <option value="CREDIT">Credit</option>
                </Select>
              </Field>
              <Field label="Customer name">
                <Input value={customerName} onChange={(event) => setCustomerName(event.target.value)} placeholder="Walk-in" />
              </Field>
              <Field label="Customer mobile">
                <Input value={customerMobile} onChange={(event) => setCustomerMobile(event.target.value)} placeholder="Optional" />
              </Field>
              <Field label="Customer state" hint="Place of supply for GST">
                <Input
                  value={customerState}
                  onChange={(event) => setCustomerState(event.target.value)}
                  placeholder="Optional"
                />
              </Field>
            </div>
          </Card>

          <Card className="overflow-hidden">
            <div className="space-y-2 border-b border-slate-100 px-4 py-3">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <h3 className="text-sm font-bold text-slate-800">Sale items</h3>
                  {errors.items && <p className="text-xs font-medium text-rose-600">{errors.items}</p>}
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  icon={<Plus className="h-3.5 w-3.5" />}
                  onClick={() => setLines((current) => [...current, newLine()])}
                >
                  Add line
                </Button>
              </div>
              <ScanBar onDetected={scanItem} placeholder="Scan barcode to add an item line" />
            </div>
            <div className="thin-scrollbar overflow-x-auto">
              <table className="w-full min-w-[780px]">
                <thead className="bg-slate-50">
                  <tr>
                    <th className="th">Item</th>
                    <th className="th w-16">Qty</th>
                    <th className="th w-28">Unit</th>
                    <th className="th w-28">Selling rate ₹</th>
                    <th className="th w-16">Disc ₹</th>
                    <th className="th w-24">GST</th>
                    <th className="th w-20 text-right">Total</th>
                    <th className="th w-10" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {lines.map((line, index) => {
                    const item = items?.find((entry) => entry.id === line.itemId)
                    const linePreview = preview.lines[index]
                    return (
                      <tr key={line.key}>
                        <td className="px-2 py-2">
                          <Select value={line.itemId} onChange={(event) => chooseItem(line.key, event.target.value)}>
                            <option value="">Select item</option>
                            {items?.map((entry) => (
                              <option key={entry.id} value={entry.id}>
                                {entry.itemName} ({entry.itemCode}) · {entry.baseUnit}
                              </option>
                            ))}
                          </Select>
                          {item && (
                            <p className="mt-1 px-1 text-[10px] text-slate-400">
                              {item.itemName} · {item.baseUnit}
                            </p>
                          )}
                        </td>
                        <td className="px-2 py-2">
                          <Input
                            type="number"
                            min="0.001"
                            step="0.001"
                            value={line.quantity}
                            onChange={(event) => updateLine(line.key, { quantity: event.target.value })}
                          />
                        </td>
                        <td className="px-2 py-2">
                          <Select
                            value={line.unit}
                            onChange={(event) => updateLine(line.key, { unit: event.target.value as Unit })}
                          >
                            {unitOptionsFor(item?.baseUnit).map((option) => (
                              <option key={option.value} value={option.value}>
                                {option.label}
                              </option>
                            ))}
                          </Select>
                        </td>
                        <td className="px-2 py-2">
                          <Input
                            type="number"
                            min="0.01"
                            step="0.01"
                            value={line.sellingRate}
                            onChange={(event) => updateLine(line.key, { sellingRate: event.target.value })}
                            placeholder="0.00"
                          />
                        </td>
                        <td className="px-2 py-2">
                          <Input
                            type="number"
                            min="0"
                            step="0.01"
                            value={line.discount}
                            onChange={(event) => updateLine(line.key, { discount: event.target.value })}
                          />
                        </td>
                        <td className="px-2 py-2">
                          <div className="rounded-lg border border-slate-200 bg-slate-50 px-2 py-1.5 text-right">
                            <p className="text-[10px] font-semibold text-slate-400">
                              {item
                                ? item.taxType === 'EXEMPT'
                                  ? 'Exempt'
                                  : `${Number(item.gstPercentage)}% GST`
                                : '—'}
                            </p>
                            <p className="text-xs font-semibold text-slate-700">
                              {money(linePreview?.taxAmount ?? 0)}
                            </p>
                          </div>
                        </td>
                        <td className="td text-right font-semibold">{money(linePreview?.payableAmount ?? 0)}</td>
                        <td className="px-2 py-2 text-center">
                          <button
                            onClick={() => removeLine(line.key)}
                            disabled={lines.length === 1}
                            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-rose-50 hover:text-rose-600 disabled:opacity-30"
                            title="Remove line"
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </Card>
        </div>

        <Card className="h-fit space-y-3 p-4 xl:sticky xl:top-20">
          <div className="grid grid-cols-2 gap-2">
            <Field label="Bill discount ₹">
              <Input
                type="number"
                min="0"
                step="0.01"
                value={discount}
                onChange={(event) => setDiscount(event.target.value)}
              />
            </Field>
            <Field label="Paid amount ₹" error={errors.paidAmount} hint="Blank = full">
              <Input
                type="number"
                min="0"
                step="0.01"
                value={paidAmount}
                onChange={(event) => setPaidAmount(event.target.value)}
                placeholder={preview.grand.toFixed(2)}
              />
            </Field>
          </div>

          <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
            <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">GST mode</p>
            <p className="mt-0.5 text-xs font-semibold text-slate-700">
              {inclusive ? 'Inclusive' : 'Exclusive'} · {interState ? 'inter-state (IGST)' : 'intra-state (CGST+SGST)'}
            </p>
          </div>

          <div className="space-y-1 border-t border-slate-200 pt-3 text-sm">
            <div className="flex justify-between text-slate-500">
              <span>Subtotal</span>
              <span>{money(preview.subtotal)}</span>
            </div>
            <div className="flex justify-between text-slate-500">
              <span>Discount</span>
              <span>− {money(preview.discount)}</span>
            </div>
            <div className="flex justify-between text-slate-500">
              <span>Taxable value</span>
              <span>{money(preview.taxable)}</span>
            </div>
            {interState ? (
              <div className="flex justify-between text-slate-500">
                <span>IGST</span>
                <span>{money(preview.igst)}</span>
              </div>
            ) : (
              <>
                <div className="flex justify-between text-slate-500">
                  <span>CGST</span>
                  <span>{money(preview.cgst)}</span>
                </div>
                <div className="flex justify-between text-slate-500">
                  <span>SGST</span>
                  <span>{money(preview.sgst)}</span>
                </div>
              </>
            )}
            <div className="flex justify-between text-slate-500">
              <span>Total tax</span>
              <span>{money(preview.tax)}</span>
            </div>
            <div className="flex justify-between border-t border-slate-200 pt-2 text-base font-bold text-slate-900">
              <span>Grand total</span>
              <span>{money(preview.grand)}</span>
            </div>
          </div>

          <Button className="w-full" onClick={save} loading={saving}>
            Complete sale
          </Button>
          <p className="text-center text-[11px] text-slate-400">
            Stock is deducted from the selected branch when the sale is saved.
          </p>
        </Card>
      </div>

      {completedSale && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
          <div className="w-full max-w-md rounded-xl bg-white p-5 shadow-xl">
            <p className="text-[10px] font-bold tracking-wider text-emerald-600 uppercase">Sale completed</p>
            <h3 className="mt-1 text-lg font-bold text-slate-900">{completedSale.salesNumber}</h3>
            <p className="text-xs text-slate-500">
              {completedSale.branchName} · {qty(completedSale.items.reduce((sum, line) => sum + Number(line.quantity), 0))} items
            </p>
            <div className="mt-4 space-y-1 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm">
              <div className="flex justify-between text-slate-500">
                <span>Taxable value</span>
                <span>{money(completedSale.taxableAmount)}</span>
              </div>
              {Number(completedSale.igstAmount) > 0 ? (
                <div className="flex justify-between text-slate-500">
                  <span>IGST</span>
                  <span>{money(completedSale.igstAmount)}</span>
                </div>
              ) : (
                <>
                  <div className="flex justify-between text-slate-500">
                    <span>CGST</span>
                    <span>{money(completedSale.cgstAmount)}</span>
                  </div>
                  <div className="flex justify-between text-slate-500">
                    <span>SGST</span>
                    <span>{money(completedSale.sgstAmount)}</span>
                  </div>
                </>
              )}
              <div className="flex justify-between border-t border-slate-200 pt-1 font-bold text-slate-900">
                <span>Grand total</span>
                <span>{money(completedSale.grandTotal)}</span>
              </div>
            </div>
            <div className="mt-4 flex flex-col gap-2 sm:flex-row">
              <Button
                variant="outline"
                className="flex-1"
                icon={printingSale ? undefined : <Printer className="h-4 w-4" />}
                loading={printingSale}
                onClick={() => void print(completedSale)}
              >
                Print receipt
              </Button>
              <Button className="flex-1" onClick={() => setCompletedSale(null)}>
                New sale
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
