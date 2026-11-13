import {
  Banknote,
  CreditCard,
  Minus,
  Plus,
  Printer,
  Search,
  ShoppingCart,
  Smartphone,
  Wallet,
  X,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  Field,
  Input,
  LoadingState,
  Modal,
  ScrollRow,
  Select,
  Spinner,
} from '../components/ui'
import { ScanBar } from '../components/barcode/ScanBar'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage } from '../lib/api'
import { money, qty, today } from '../lib/format'
import { autoPrintSale, printSaleReceiptForDevice } from '../services/printerService'
import { billPreview, isInterState } from '../lib/tax'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type { Category, PaymentMethod, PosItem, Sale } from '../types'

interface CartLine {
  item: PosItem
  quantity: number
  rate: number
}

const paymentOptions: { value: PaymentMethod; label: string; icon: typeof Banknote }[] = [
  { value: 'CASH', label: 'Cash', icon: Banknote },
  { value: 'UPI', label: 'UPI', icon: Smartphone },
  { value: 'CARD', label: 'Card', icon: CreditCard },
  { value: 'CREDIT', label: 'Credit', icon: Wallet },
]

export function PosPage() {
  const { branches, selectedBranchId, setBranch, store, user } = useAuthStore()
  const branchId = selectedBranchId ?? branches[0]?.id ?? null
  const storeInfo = {
    name: store?.storeName ?? 'Store',
    gstNumber: store?.gstNumber,
    address: store?.address,
    mobile: store?.mobile,
  }

  useEffect(() => {
    if (!selectedBranchId && branches[0]) setBranch(branches[0].id)
  }, [selectedBranchId, branches, setBranch])

  const [search, setSearch] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const debouncedSearch = useDebounce(search)

  const { data: categories } = useApi<Category[]>(
    () => api.get('/categories/options').then((response) => response.data),
    [],
  )

  const {
    data: items,
    loading,
    error,
    reload,
  } = useApi<PosItem[]>(
    () =>
      branchId
        ? api
            .get('/items/pos', {
              params: {
                branchId,
                categoryId: categoryId || undefined,
                search: debouncedSearch || undefined,
              },
            })
            .then((response) => response.data)
        : Promise.resolve([]),
    [branchId, categoryId, debouncedSearch],
  )

  const [cart, setCart] = useState<CartLine[]>([])
  const [customerName, setCustomerName] = useState('')
  const [customerMobile, setCustomerMobile] = useState('')
  const [customerState, setCustomerState] = useState('')
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>('CASH')
  const [discount, setDiscount] = useState('0')
  const [paid, setPaid] = useState('')
  const [selling, setSelling] = useState(false)
  const [completedSale, setCompletedSale] = useState<Sale | null>(null)
  const [printingReceipt, setPrintingReceipt] = useState(false)
  const billRef = useRef<HTMLDivElement>(null)

  const inclusive = cart[0]?.item.taxInclusive ?? Boolean(store?.taxInclusive)
  const branchState = branches.find((branch) => branch.id === branchId)?.state ?? null
  const interState = isInterState(branchState, customerState)
  const preview = billPreview(
    cart.map((line) => ({
      gross: line.quantity * line.rate,
      discount: 0,
      gstPercentage: line.item.gstPercentage,
    })),
    Number(discount || 0),
    inclusive,
    interState,
  )
  const grandTotal = preview.grand
  const paidAmount = paid === '' ? (paymentMethod === 'CREDIT' ? 0 : grandTotal) : Number(paid)
  const balance = Math.max(0, grandTotal - paidAmount)
  const change = Math.max(0, paidAmount - grandTotal)
  const totalItems = cart.reduce((sum, line) => sum + line.quantity, 0)

  const filteredItems = useMemo(() => items ?? [], [items])

  const addToCart = (item: PosItem): boolean => {
    if (Number(item.availableStock) <= 0) {
      notifyError(`${item.itemName} is out of stock at this branch`)
      return false
    }
    const existing = cart.find((line) => line.item.id === item.id)
    if (existing) {
      if (existing.quantity + 1 > Number(item.availableStock)) {
        notifyError(`Only ${qty(item.availableStock)} ${item.unit} of ${item.itemName} available`)
        return false
      }
      setCart((current) =>
        current.map((line) =>
          line.item.id === item.id ? { ...line, quantity: line.quantity + 1 } : line,
        ),
      )
      return true
    }
    setCart((current) => [...current, { item, quantity: 1, rate: Number(item.rate) }])
    return true
  }

  const scanBarcode = async (code: string) => {
    if (!branchId) {
      notifyError('Select a branch before scanning')
      return false
    }
    try {
      const { data } = await api.get<PosItem[]>('/items/pos', {
        params: { branchId, search: code },
      })
      const match = data.find((item) => item.barcode.toLowerCase() === code.toLowerCase())
      if (!match) {
        notifyError(`No active item with barcode ${code} is available at this branch`)
        return false
      }
      if (addToCart(match)) {
        notify(`${match.itemName} added to the bill`)
        return true
      }
      return false
    } catch (err) {
      notifyError(errorMessage(err))
      return false
    }
  }

  const setQuantity = (itemId: number, quantity: number) => {
    setCart((current) =>
      current.map((line) => {
        if (line.item.id !== itemId) return line
        const max = Number(line.item.availableStock)
        if (quantity > max) {
          notifyError(`Only ${qty(max)} ${line.item.unit} of ${line.item.itemName} available`)
          return line
        }
        return { ...line, quantity: Math.max(0.001, quantity) }
      }),
    )
  }

  const removeLine = (itemId: number) => setCart((current) => current.filter((line) => line.item.id !== itemId))

  const resetSale = () => {
    setCart([])
    setCustomerName('')
    setCustomerMobile('')
    setCustomerState('')
    setPaymentMethod('CASH')
    setDiscount('0')
    setPaid('')
  }

  const completeSale = async () => {
    if (!branchId) {
      notifyError('Select a branch before billing')
      return
    }
    if (cart.length === 0) {
      notifyError('Add at least one item to the bill')
      return
    }
    if (paidAmount < 0) {
      notifyError('Paid amount cannot be negative')
      return
    }
    if (paidAmount > grandTotal) {
      notifyError('Paid amount cannot exceed the bill total')
      return
    }

    setSelling(true)
    try {
      const { data } = await api.post<Sale>('/sales', {
        branchId,
        customerName: customerName.trim() || undefined,
        customerMobile: customerMobile.trim() || undefined,
        customerState: customerState.trim() || undefined,
        salesDate: today(),
        discount: Number(discount || 0),
        paidAmount,
        paymentMethod,
        status: 'COMPLETED',
        pos: true,
        items: cart.map((line) => ({
          itemId: line.item.id,
          quantity: line.quantity,
          unit: line.item.unit,
          sellingRate: line.rate,
          discount: 0,
        })),
      })
      setCompletedSale(data)
      notify(`Sale ${data.salesNumber} completed`)
      resetSale()
      reload()
      void autoPrintSale(data, storeInfo).then((outcome) => {
        if (outcome?.status === 'FAILED') notifyError(outcome.message ?? 'The receipt could not be printed')
      })
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setSelling(false)
    }
  }

  const activeBranch = branches.find((branch) => branch.id === branchId)

  const printCompletedReceipt = async () => {
    if (!completedSale || printingReceipt) return
    setPrintingReceipt(true)
    try {
      const outcome = await printSaleReceiptForDevice(completedSale, storeInfo)
      if (!outcome) return
      if (outcome.status === 'PRINTED') notify('Receipt sent to the printer')
      else if (outcome.status === 'DUPLICATE') notify('This receipt was already printed')
      else if (outcome.status !== 'UNSUPPORTED' || outcome.message) notifyError(outcome.message ?? 'Unable to print receipt')
    } finally {
      setPrintingReceipt(false)
    }
  }

  const revealBill = () => {
    billRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <div
      className={`grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_390px] ${
        cart.length > 0 ? 'pb-24 xl:pb-0' : ''
      }`}
    >
      <div className="flex min-w-0 flex-col gap-4">
        <div className="flex flex-col gap-3 rounded-xl border border-slate-200/80 bg-white p-3 shadow-card sm:flex-row sm:items-center">
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search items by name, code or barcode…"
              className="h-10 w-full rounded-lg border border-slate-200 bg-slate-50 pr-3 pl-9 text-sm outline-none transition focus:border-brand-500 focus:bg-white focus:ring-2 focus:ring-brand-500/20"
            />
          </div>
          <div className="flex items-center gap-2">
            <Select
              className="h-10 w-40 py-0"
              value={branchId ?? ''}
              onChange={(event) => setBranch(event.target.value ? Number(event.target.value) : null)}
              aria-label="Billing branch"
            >
              {branches.map((branch) => (
                <option key={branch.id} value={branch.id}>
                  {branch.branchName}
                </option>
              ))}
            </Select>
            <span className="hidden text-xs text-slate-500 sm:block">
              {user?.fullName?.split(' ')[0]} · {activeBranch?.branchCode}
            </span>
          </div>
          <ScanBar
            className="w-full sm:w-72"
            onDetected={scanBarcode}
            placeholder="Scan barcode to add to the bill"
            disabled={!branchId}
          />
        </div>

        <ScrollRow label="Item categories" className="gap-2 pb-1">
          <button
            onClick={() => setCategoryId('')}
            className={`shrink-0 rounded-full px-4 py-1.5 text-xs font-semibold transition ${
              categoryId === '' ? 'bg-brand-600 text-white shadow-sm' : 'bg-white text-slate-600 ring-1 ring-slate-200 hover:bg-slate-50'
            }`}
          >
            All items
          </button>
          {categories?.map((category) => (
            <button
              key={category.id}
              onClick={() => setCategoryId(String(category.id))}
              className={`shrink-0 rounded-full px-4 py-1.5 text-xs font-semibold transition ${
                categoryId === String(category.id)
                  ? 'bg-brand-600 text-white shadow-sm'
                  : 'bg-white text-slate-600 ring-1 ring-slate-200 hover:bg-slate-50'
              }`}
            >
              {category.categoryName}
            </button>
          ))}
        </ScrollRow>

        {error ? (
          <Card>
            <ErrorState message={error} onRetry={reload} />
          </Card>
        ) : loading ? (
          <Card>
            <LoadingState label="Loading items for this branch…" />
          </Card>
        ) : filteredItems.length === 0 ? (
          <Card>
            <EmptyState
              icon={<Search className="h-6 w-6" />}
              title="No items found"
              message="Try a different search or category. Only active items with a master rate appear here."
            />
          </Card>
        ) : (
          <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-5">
            {filteredItems.map((item) => {
              const out = Number(item.availableStock) <= 0
              const inCart = cart.find((line) => line.item.id === item.id)
              return (
                <button
                  key={item.id}
                  onClick={() => addToCart(item)}
                  disabled={out}
                  className={`group relative flex flex-col rounded-xl border bg-white p-3 text-left shadow-sm transition ${
                    out
                      ? 'cursor-not-allowed border-slate-200 opacity-60'
                      : 'border-slate-200 hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-md'
                  }`}
                >
                  {inCart && (
                    <span className="absolute top-2 right-2 flex h-5 min-w-5 items-center justify-center rounded-full bg-brand-600 px-1 text-[10px] font-bold text-white">
                      {qty(inCart.quantity)}
                    </span>
                  )}
                  <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-50 text-xs font-bold text-brand-700">
                    {item.itemName.slice(0, 2).toUpperCase()}
                  </span>
                  <p className="mt-2 line-clamp-2 min-h-9 text-xs font-semibold text-slate-800">{item.itemName}</p>
                  <div className="mt-auto flex items-end justify-between pt-2">
                    <div>
                      <p className="text-sm font-bold text-slate-900">{money(item.rate)}</p>
                      <p className="text-[10px] text-slate-400">
                        per {item.unit} ·{' '}
                        {item.taxType === 'EXEMPT' ? 'exempt' : `${Number(item.gstPercentage)}% GST`}
                      </p>
                    </div>
                    {out ? (
                      <Badge tone="red">Out</Badge>
                    ) : item.lowStock ? (
                      <Badge tone="amber">Low</Badge>
                    ) : (
                      <span className="text-[10px] font-medium text-slate-500">
                        {qty(item.availableStock)} left
                      </span>
                    )}
                  </div>
                </button>
              )
            })}
          </div>
        )}
      </div>

      <div ref={billRef} className="scroll-mt-20 xl:sticky xl:top-20 xl:self-start">
      <Card className="flex h-fit flex-col overflow-hidden">
        <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/60 px-4 py-3">
          <div className="flex items-center gap-2">
            <ShoppingCart className="h-4 w-4 text-brand-600" />
            <h2 className="text-sm font-bold text-slate-900">Current bill</h2>
            <span className="rounded-full bg-brand-100 px-2 py-0.5 text-[10px] font-bold text-brand-700">
              {cart.length} items
            </span>
          </div>
          {cart.length > 0 && (
            <button onClick={resetSale} className="text-[11px] font-semibold text-slate-400 hover:text-rose-600">
              Clear
            </button>
          )}
        </div>

        <div className="thin-scrollbar max-h-[38vh] min-h-28 overflow-y-auto xl:max-h-[34vh]">
          {cart.length === 0 ? (
            <div className="flex h-32 flex-col items-center justify-center gap-2 px-6 text-center">
              <ShoppingCart className="h-7 w-7 text-slate-300" />
              <p className="text-xs text-slate-400">Tap items to add them to this bill.</p>
            </div>
          ) : (
            <div className="divide-y divide-slate-100">
              {cart.map((line) => (
                <div key={line.item.id} className="px-4 py-2.5">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="truncate text-xs font-semibold text-slate-800">{line.item.itemName}</p>
                      <p className="text-[10px] text-slate-400">
                        {line.item.unit} · {qty(line.item.availableStock)} available
                      </p>
                    </div>
                    <button
                      onClick={() => removeLine(line.item.id)}
                      className="flex h-6 w-6 shrink-0 items-center justify-center rounded text-slate-300 hover:bg-rose-50 hover:text-rose-500"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  </div>
                  <div className="mt-1.5 flex items-center gap-2">
                    <div className="flex items-center rounded-lg border border-slate-200">
                      <button
                        onClick={() => setQuantity(line.item.id, Math.max(0.001, line.quantity - 1))}
                        className="flex h-7 w-7 items-center justify-center text-slate-500 hover:bg-slate-50"
                      >
                        <Minus className="h-3 w-3" />
                      </button>
                      <input
                        type="number"
                        min="0.001"
                        step="0.001"
                        value={line.quantity}
                        onChange={(event) => setQuantity(line.item.id, Number(event.target.value))}
                        className="h-7 w-12 border-x border-slate-200 text-center text-xs font-semibold outline-none"
                      />
                      <button
                        onClick={() => setQuantity(line.item.id, line.quantity + 1)}
                        className="flex h-7 w-7 items-center justify-center text-slate-500 hover:bg-slate-50"
                      >
                        <Plus className="h-3 w-3" />
                      </button>
                    </div>
                    <div className="flex items-center gap-1 text-[11px] text-slate-500">
                      <span className="text-slate-400">×</span>
                      <span className="font-semibold">{money(line.rate)}</span>
                      <span className="text-[10px] text-slate-400">item rate</span>
                    </div>
                    <p className="ml-auto text-xs font-bold text-slate-900">{money(line.quantity * line.rate)}</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="space-y-3 border-t border-slate-100 p-4">
          <div className="grid grid-cols-2 gap-2">
            <Input
              className="h-8 text-xs"
              placeholder="Customer name"
              value={customerName}
              onChange={(event) => setCustomerName(event.target.value)}
            />
            <Input
              className="h-8 text-xs"
              placeholder="Mobile"
              value={customerMobile}
              onChange={(event) => setCustomerMobile(event.target.value)}
            />
          </div>
          <Input
            className="h-8 text-xs"
            placeholder="Customer state (place of supply)"
            value={customerState}
            onChange={(event) => setCustomerState(event.target.value)}
          />

          <div className="grid grid-cols-2 gap-2">
            <Field label="Discount ₹">
              <Input
                type="number"
                min="0"
                step="0.01"
                className="h-8 text-xs"
                value={discount}
                onChange={(event) => setDiscount(event.target.value)}
              />
            </Field>
            <div className="rounded-lg border border-slate-200 bg-slate-50 px-2 py-1">
              <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">GST</p>
              <p className="mt-0.5 text-[11px] font-semibold text-slate-700">
                {inclusive ? 'Inclusive' : 'Exclusive'} · {interState ? 'IGST' : 'CGST+SGST'}
              </p>
            </div>
          </div>

          <div>
            <p className="mb-1.5 text-[11px] font-semibold text-slate-500">Payment method</p>
            <div className="grid grid-cols-4 gap-1.5">
              {paymentOptions.map((option) => (
                <button
                  key={option.value}
                  onClick={() => setPaymentMethod(option.value)}
                  className={`flex flex-col items-center gap-1 rounded-lg border py-1.5 text-[10px] font-semibold transition ${
                    paymentMethod === option.value
                      ? 'border-brand-500 bg-brand-50 text-brand-700'
                      : 'border-slate-200 text-slate-500 hover:bg-slate-50'
                  }`}
                >
                  <option.icon className="h-3.5 w-3.5" />
                  {option.label}
                </button>
              ))}
            </div>
          </div>

          <div className="space-y-1 rounded-lg bg-slate-50 px-3 py-2 text-xs">
            <div className="flex justify-between text-slate-500">
              <span>
                Subtotal · {qty(totalItems)} units
              </span>
              <span className="font-medium text-slate-700">{money(preview.subtotal)}</span>
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
            <div className="flex justify-between border-t border-slate-200 pt-1.5 text-base font-bold text-slate-900">
              <span>Total</span>
              <span>{money(grandTotal)}</span>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Field label="Amount received" className="flex-1">
              <Input
                type="number"
                min="0"
                step="0.01"
                className="h-9 text-xs"
                value={paid}
                onChange={(event) => setPaid(event.target.value)}
                placeholder={String(grandTotal.toFixed(2))}
              />
            </Field>
            <div className="w-24 text-right">
              <p className="text-[10px] font-semibold tracking-wide text-slate-400 uppercase">
                {balance > 0 ? 'Balance' : 'Change'}
              </p>
              <p className={`text-sm font-bold ${balance > 0 ? 'text-rose-600' : 'text-emerald-600'}`}>
                {money(balance > 0 ? balance : change)}
              </p>
            </div>
          </div>

          <Button
            size="lg"
            className="w-full justify-center"
            loading={selling}
            disabled={cart.length === 0}
            onClick={completeSale}
            icon={selling ? undefined : <ShoppingCart className="h-4 w-4" />}
          >
            Complete sale · {money(grandTotal)}
          </Button>
          {totalItems > 0 && paymentMethod === 'CREDIT' && (
            <p className="text-center text-[10px] text-amber-600">
              Credit sale records the unpaid balance against the customer.
            </p>
          )}
        </div>
      </Card>
      </div>

      <Modal
        open={Boolean(completedSale)}
        onClose={() => setCompletedSale(null)}
        title="Sale completed"
        description={completedSale ? `${completedSale.salesNumber} · ${completedSale.branchName}` : undefined}
        size="sm"
        footer={
          <>
            <Button variant="outline" onClick={() => setCompletedSale(null)}>
              New sale
            </Button>
            <Button
              icon={printingReceipt ? undefined : <Printer className="h-4 w-4" />}
              loading={printingReceipt}
              onClick={() => void printCompletedReceipt()}
            >
              Print receipt
            </Button>
          </>
        }
      >
        {completedSale ? (
          <div className="space-y-4 text-center">
            <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-emerald-50 text-emerald-600">
              <ShoppingCart className="h-6 w-6" />
            </span>
            <div>
              <p className="text-sm font-semibold text-slate-800">{money(completedSale.grandTotal)} collected</p>
              <p className="mt-1 text-xs text-slate-500">
                {completedSale.items?.length ?? 0} lines · {completedSale.paymentMethod} · {completedSale.branchName}
              </p>
            </div>
            <div className="rounded-lg border border-slate-100 bg-slate-50 px-4 py-3 text-left text-xs">
              <div className="flex justify-between py-0.5 text-slate-500">
                <span>Subtotal</span>
                <span>{money(completedSale.subtotal)}</span>
              </div>
              <div className="flex justify-between py-0.5 text-slate-500">
                <span>Discount</span>
                <span>− {money(completedSale.discount)}</span>
              </div>
              <div className="flex justify-between py-0.5 text-slate-500">
                <span>Taxable value</span>
                <span>{money(completedSale.taxableAmount)}</span>
              </div>
              {Number(completedSale.igstAmount) > 0 ? (
                <div className="flex justify-between py-0.5 text-slate-500">
                  <span>IGST</span>
                  <span>{money(completedSale.igstAmount)}</span>
                </div>
              ) : (
                <>
                  <div className="flex justify-between py-0.5 text-slate-500">
                    <span>CGST</span>
                    <span>{money(completedSale.cgstAmount)}</span>
                  </div>
                  <div className="flex justify-between py-0.5 text-slate-500">
                    <span>SGST</span>
                    <span>{money(completedSale.sgstAmount)}</span>
                  </div>
                </>
              )}
              <div className="flex justify-between py-0.5 text-slate-500">
                <span>Total tax</span>
                <span>{money(completedSale.tax)}</span>
              </div>
              <div className="flex justify-between border-t border-slate-200 pt-1.5 font-bold text-slate-800">
                <span>Total</span>
                <span>{money(completedSale.grandTotal)}</span>
              </div>
              <div className="flex justify-between pt-1 text-slate-500">
                <span>Paid</span>
                <span>{money(completedSale.paidAmount)}</span>
              </div>
              {Number(completedSale.balanceAmount) > 0 && (
                <div className="flex justify-between pt-1 font-semibold text-rose-600">
                  <span>Balance</span>
                  <span>{money(completedSale.balanceAmount)}</span>
                </div>
              )}
            </div>
          </div>
        ) : (
          <Spinner />
        )}
      </Modal>

      {cart.length > 0 && (
        <div className="fixed inset-x-0 bottom-0 z-20 border-t border-slate-200 bg-white px-3 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] shadow-[0_-8px_24px_-12px_rgba(15,23,42,0.25)] xl:hidden">
          <div className="flex items-center gap-3">
            <div className="min-w-0 flex-1">
              <p className="text-[10px] font-semibold tracking-wide text-slate-400 uppercase">{cart.length} items</p>
              <p className="truncate text-base font-bold text-slate-900">{money(grandTotal)}</p>
            </div>
            <Button
              className="flex-1 justify-center"
              onClick={revealBill}
              icon={<ShoppingCart className="h-4 w-4" />}
            >
              Review & bill
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
