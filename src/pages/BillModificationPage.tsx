import {
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  FilePenLine,
  History,
  Plus,
  Printer,
  RefreshCw,
  RotateCcw,
  Trash2,
  TriangleAlert,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { BillLookup } from '../components/billing/BillLookup'
import { InvoicePrintPreview } from '../components/billing/InvoicePrintPreview'
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
  PageHeader,
  Select,
  StatusBadge,
  Textarea,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useUnits } from '../hooks/useUnits'
import { ApiError, api, errorMessage } from '../lib/api'
import { formatDate, formatDateTime, money, qty, titleCase } from '../lib/format'
import { billPreview, isInterState } from '../lib/tax'
import { unitOptionsFor, type UnitOption } from '../lib/units'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type {
  Item,
  ModificationChangeType,
  Sale,
  SaleLine,
  SaleModification,
  UnitMaster,
} from '../types'

interface StoreDetails {
  name: string
  gstNumber?: string | null
  address?: string | null
  mobile?: string | null
  email?: string | null
}

interface EditLine {
  key: number
  itemId: number | ''
  quantity: string
  unit: string
  sellingRate: string
  discount: string
  original: SaleLine | null
}

let lineKey = 0

const toNumber = (value: string | number) => {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

const toEditLine = (line: SaleLine): EditLine => ({
  key: ++lineKey,
  itemId: line.itemId,
  quantity: String(line.quantity),
  unit: line.unit,
  sellingRate: String(line.sellingRate),
  discount: String(line.discount),
  original: line,
})

const blankLine = (): EditLine => ({
  key: ++lineKey,
  itemId: '',
  quantity: '1',
  unit: '',
  sellingRate: '',
  discount: '0',
  original: null,
})

const changeTone: Record<ModificationChangeType, 'green' | 'red' | 'amber'> = {
  ADDED: 'green',
  REMOVED: 'red',
  UPDATED: 'amber',
}

function unitChoices(units: UnitMaster[], base: string | undefined, current: string): UnitOption[] {
  const options = unitOptionsFor(units, base)
  if (!current || options.some((option) => option.value === current)) return options
  return [{ value: current, label: current }, ...options]
}

export function BillModificationPage() {
  const navigate = useNavigate()
  const params = useParams()
  const saleId = params.id && Number(params.id) > 0 ? Number(params.id) : null
  const { store, branches } = useAuthStore()
  const { units } = useUnits()
  const { data: catalog } = useApi<Item[]>(
    () => api.get('/items/options').then((response) => response.data),
    [],
  )

  const [saved, setSaved] = useState<SaleModification | null>(null)
  const [reloadToken, setReloadToken] = useState(0)
  const [previewSale, setPreviewSale] = useState<Sale | null>(null)

  const saleQuery = useApi<Sale | null>(
    () =>
      saleId
        ? api.get<Sale>(`/sales/${saleId}`).then((response) => response.data)
        : Promise.resolve(null),
    [saleId, reloadToken],
  )
  const historyQuery = useApi<SaleModification[]>(
    () =>
      saleId
        ? api.get<SaleModification[]>(`/sales/${saleId}/modifications`).then((response) => response.data)
        : Promise.resolve([]),
    [saleId, saleQuery.data?.modificationCount ?? 0, reloadToken],
  )

  const storeInfo: StoreDetails = {
    name: store?.storeName ?? 'Store',
    gstNumber: store?.gstNumber,
    address: store?.address,
    mobile: store?.mobile,
    email: store?.email,
  }

  const header = (
    <PageHeader
      eyebrow="Sell"
      title="Modify bill"
      subtitle="Correct items, quantities, rates or discounts on a completed bill without cancelling it."
      actions={
        saleId ? (
          <Button
            variant="outline"
            icon={<ArrowLeft className="h-4 w-4" />}
            onClick={() => navigate('/sales/modify')}
          >
            Find another bill
          </Button>
        ) : undefined
      }
    />
  )

  if (!saleId) {
    return (
      <div>
        {header}
        <div className="space-y-3">
          <BillLookup onSelect={(bill) => navigate(`/sales/modify/${bill.id}`)} />
          <p className="px-1 text-xs text-slate-500">
            Search by bill number, date, customer name or mobile. Only completed bills can be modified; cancelled
            bills stay read-only.
          </p>
        </div>
      </div>
    )
  }

  const sale = saleQuery.data && saleQuery.data.id === saleId ? saleQuery.data : null

  if (!sale) {
    return (
      <div>
        {header}
        <Card>
          {saleQuery.error ? (
            <ErrorState message={saleQuery.error} onRetry={saleQuery.reload} />
          ) : (
            <LoadingState label="Loading bill…" />
          )}
        </Card>
      </div>
    )
  }

  const banner = saved && saved.sale.id === sale.id ? saved : null

  return (
    <div>
      {header}

      {banner && (
        <div className="mb-4 rounded-xl border border-emerald-200 bg-emerald-50 p-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="flex min-w-0 items-start gap-3">
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-700">
                <CheckCircle2 className="h-5 w-5" />
              </span>
              <div className="min-w-0">
                <p className="text-sm font-bold text-emerald-900">Bill modified successfully</p>
                <p className="mt-0.5 text-xs text-emerald-800">
                  Reference <strong>{banner.modificationNumber}</strong> · {formatDateTime(banner.modifiedAt)}
                </p>
                <p className="mt-1 text-xs text-emerald-800">
                  {money(banner.originalTotal)} → <strong>{money(banner.modifiedTotal)}</strong>
                  {Number(banner.differenceAmount) !== 0 && (
                    <>
                      {' '}
                      ({Number(banner.differenceAmount) > 0 ? 'increased' : 'decreased'} by{' '}
                      {money(Math.abs(Number(banner.differenceAmount)))})
                    </>
                  )}
                </p>
                {banner.reason && <p className="mt-1 text-xs text-emerald-800">Reason: {banner.reason}</p>}
              </div>
            </div>
            <Button
              variant="outline"
              size="sm"
              icon={<Printer className="h-4 w-4" />}
              onClick={() => setPreviewSale(banner.sale)}
            >
              Print updated invoice
            </Button>
          </div>
        </div>
      )}

      <BillEditor
        key={`${sale.id}:${sale.modificationCount}:${sale.status}:${reloadToken}`}
        sale={sale}
        history={historyQuery.data ?? []}
        historyLoading={historyQuery.loading}
        catalog={catalog}
        units={units.filter((unit) => unit.status === 'ACTIVE')}
        storeInfo={storeInfo}
        inclusive={Boolean(store?.taxInclusive)}
        branchState={branches.find((branch) => branch.id === sale.branchId)?.state ?? null}
        onSaved={(record) => {
          setSaved(record)
          setReloadToken((value) => value + 1)
        }}
        onReload={() => {
          setSaved(null)
          setReloadToken((value) => value + 1)
        }}
      />

      <InvoicePrintPreview
        open={Boolean(previewSale)}
        sale={previewSale}
        store={storeInfo}
        onClose={() => setPreviewSale(null)}
      />
    </div>
  )
}

interface BillEditorProps {
  sale: Sale
  history: SaleModification[]
  historyLoading: boolean
  catalog: Item[] | null
  units: UnitMaster[]
  storeInfo: StoreDetails
  inclusive: boolean
  branchState: string | null
  onSaved: (record: SaleModification) => void
  onReload: () => void
}

function BillEditor({
  sale,
  history,
  historyLoading,
  catalog,
  units,
  storeInfo,
  inclusive,
  branchState,
  onSaved,
  onReload,
}: BillEditorProps) {
  const [lines, setLines] = useState<EditLine[]>(() => sale.items.map(toEditLine))
  const [reason, setReason] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [preview, setPreview] = useState<SaleModification | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [saving, setSaving] = useState(false)

  const editable = sale.status === 'COMPLETED'
  const interState = isInterState(branchState, sale.customerState)

  const totals = useMemo(
    () =>
      billPreview(
        lines.map((line) => {
          const item = catalog?.find((entry) => entry.id === line.itemId)
          return {
            gross: toNumber(line.quantity) * toNumber(line.sellingRate),
            discount: Math.max(0, toNumber(line.discount)),
            gstPercentage: item ? Number(item.gstPercentage) : Number(line.original?.gstPercentage ?? 0),
          }
        }),
        0,
        inclusive,
        interState,
      ),
    [lines, catalog, inclusive, interState],
  )

  const usedItemIds = useMemo(
    () => new Set(lines.map((line) => line.itemId).filter((value): value is number => value !== '')),
    [lines],
  )

  const dirty = useMemo(() => {
    if (lines.length !== sale.items.length) return true
    return lines.some(
      (line) =>
        !line.original ||
        Number(line.quantity) !== Number(line.original.quantity) ||
        line.unit !== line.original.unit ||
        Number(line.sellingRate) !== Number(line.original.sellingRate) ||
        Number(line.discount) !== Number(line.original.discount),
    )
  }, [lines, sale.items])

  const difference = Math.round((totals.grand - Number(sale.grandTotal)) * 100) / 100

  const updateLine = (key: number, patch: Partial<EditLine>) => {
    setLines((current) => current.map((line) => (line.key === key ? { ...line, ...patch } : line)))
  }

  const chooseItem = (key: number, raw: string) => {
    const itemId = raw ? Number(raw) : ''
    const item = catalog?.find((entry) => entry.id === itemId)
    updateLine(key, {
      itemId,
      unit: item?.baseUnit ?? '',
      sellingRate: item ? String(item.rate) : '',
      discount: '0',
    })
  }

  const removeLine = (key: number) => {
    setLines((current) => (current.length === 1 ? current : current.filter((line) => line.key !== key)))
  }

  const resetLines = () => {
    setLines(sale.items.map(toEditLine))
    setReason('')
    setFormError(null)
  }

  const validate = (): string | null => {
    if (lines.length === 0) return 'A bill must keep at least one item'
    const seen = new Set<number>()
    for (const line of lines) {
      if (!line.itemId) return 'Select an item on every line'
      if (seen.has(line.itemId)) return 'The same item cannot appear twice'
      seen.add(line.itemId)
      const quantity = toNumber(line.quantity)
      if (!(quantity > 0)) return 'Every line needs a quantity greater than zero'
      const rate = toNumber(line.sellingRate)
      if (rate < 0) return 'Every line needs a rate of zero or more'
      const discount = toNumber(line.discount)
      if (discount < 0) return 'Discount cannot be negative'
      if (discount > Math.round((quantity * rate + Number.EPSILON) * 100) / 100) {
        return 'Discount cannot exceed the line amount'
      }
    }
    if (!dirty) return 'Change a quantity, rate, item or discount before saving'
    return null
  }

  const buildRequest = () => ({
    reason: reason.trim() || undefined,
    baseModificationCount: sale.modificationCount,
    items: lines.map((line) => ({
      itemId: Number(line.itemId),
      quantity: toNumber(line.quantity),
      unit: line.unit,
      sellingRate: toNumber(line.sellingRate),
      discount: toNumber(line.discount),
    })),
  })

  const handleFailure = (err: unknown) => {
    const message = errorMessage(err)
    setFormError(message)
    notifyError(message)
    if (err instanceof ApiError && err.status === 409) {
      // Someone else changed the bill between loading and saving: drop this
      // stale edit and reload the authoritative bill instead of overwriting it.
      setPreview(null)
      setConfirmOpen(false)
      onReload()
    }
  }

  const review = async () => {
    const problem = validate()
    if (problem) {
      setFormError(problem)
      return
    }
    setFormError(null)
    setPreviewing(true)
    try {
      const { data } = await api.post<SaleModification>(
        `/sales/${sale.id}/modifications/preview`,
        buildRequest(),
      )
      setPreview(data)
      setConfirmOpen(true)
    } catch (err) {
      handleFailure(err)
    } finally {
      setPreviewing(false)
    }
  }

  const confirm = async () => {
    setSaving(true)
    setFormError(null)
    try {
      const { data } = await api.post<SaleModification>(`/sales/${sale.id}/modifications`, buildRequest())
      setPreview(null)
      setConfirmOpen(false)
      setLines(data.sale.items.map(toEditLine))
      setReason('')
      notify(`Bill modified — reference ${data.modificationNumber}`)
      onSaved(data)
    } catch (err) {
      setConfirmOpen(false)
      handleFailure(err)
    } finally {
      setSaving(false)
    }
  }

  const [previewOpen, setPreviewOpen] = useState(false)

  const change = preview ? Number(preview.differenceAmount) : 0

  return (
    <>
      {sale.status === 'CANCELLED' && (
        <div className="mb-4 flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 p-4">
          <TriangleAlert className="mt-0.5 h-5 w-5 shrink-0 text-rose-500" />
          <div>
            <p className="text-sm font-bold text-rose-900">This bill is cancelled</p>
            <p className="mt-0.5 text-xs text-rose-700">
              Cancelled bills cannot be modified. The invoice below prints with a CANCELLED watermark.
            </p>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
        <div className="space-y-4">
          <Card className="p-4">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-base font-bold text-slate-900">{sale.salesNumber}</h2>
                  <StatusBadge status={sale.status} />
                  {sale.modificationCount > 0 && (
                    <Badge tone="amber">{sale.modificationCount}× modified</Badge>
                  )}
                </div>
                <p className="mt-1 text-xs text-slate-500">
                  {formatDate(sale.salesDate)} · {sale.branchName} · {sale.customerName || 'Walk-in'}
                  {sale.customerMobile ? ` · ${sale.customerMobile}` : ''}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" size="sm" icon={<Printer className="h-4 w-4" />} onClick={() => setPreviewOpen(true)}>
                  Print invoice
                </Button>
                <Button variant="ghost" size="sm" icon={<RefreshCw className="h-4 w-4" />} onClick={onReload}>
                  Reload
                </Button>
              </div>
            </div>
          </Card>

          {formError && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
              {formError}
            </div>
          )}

          <Card className="overflow-hidden">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-4 py-3">
              <div>
                <h3 className="text-sm font-bold text-slate-800">Bill items</h3>
                <p className="text-[11px] text-slate-500">
                  Edit quantities, units, rates or discounts. Removing every line is not allowed — cancel the bill
                  instead.
                </p>
              </div>
              {editable && (
                <Button
                  variant="outline"
                  size="sm"
                  icon={<Plus className="h-3.5 w-3.5" />}
                  onClick={() => setLines((current) => [...current, blankLine()])}
                >
                  Add item
                </Button>
              )}
            </div>
            <div className="thin-scrollbar overflow-x-auto">
              <table className="w-full min-w-[820px]">
                <thead className="bg-slate-50">
                  <tr>
                    <th className="th">Item</th>
                    <th className="th w-20">Qty</th>
                    <th className="th w-28">Unit</th>
                    <th className="th w-28">Rate ₹</th>
                    <th className="th w-24">Disc ₹</th>
                    <th className="th w-24">GST</th>
                    <th className="th w-24 text-right">Amount</th>
                    <th className="th w-10" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {lines.map((line, index) => {
                    const item = catalog?.find((entry) => entry.id === line.itemId)
                    const lineTotals = totals.lines[index]
                    const baseUnit = item?.baseUnit ?? line.original?.unit ?? line.unit
                    const gstPercentage = item
                      ? Number(item.gstPercentage)
                      : Number(line.original?.gstPercentage ?? 0)
                    return (
                      <tr key={line.key}>
                        <td className="px-2 py-2">
                          <Select
                            value={line.itemId}
                            disabled={!editable}
                            onChange={(event) => chooseItem(line.key, event.target.value)}
                          >
                            <option value="">Select item</option>
                            {catalog?.map((entry) => (
                              <option
                                key={entry.id}
                                value={entry.id}
                                disabled={entry.id !== line.itemId && usedItemIds.has(entry.id)}
                              >
                                {entry.itemName} ({entry.itemCode})
                              </option>
                            ))}
                          </Select>
                          {line.original && (
                            <p className="mt-1 px-1 text-[10px] text-slate-400">
                              Original: {qty(line.original.quantity)} {line.original.unit} @{' '}
                              {money(line.original.sellingRate)}
                            </p>
                          )}
                        </td>
                        <td className="px-2 py-2">
                          <Input
                            type="number"
                            min="0.001"
                            step="0.001"
                            value={line.quantity}
                            disabled={!editable}
                            onChange={(event) => updateLine(line.key, { quantity: event.target.value })}
                          />
                        </td>
                        <td className="px-2 py-2">
                          <Select
                            value={line.unit}
                            disabled={!editable}
                            onChange={(event) => updateLine(line.key, { unit: event.target.value })}
                          >
                            {unitChoices(units, baseUnit, line.unit).map((option) => (
                              <option key={option.value} value={option.value}>
                                {option.label}
                              </option>
                            ))}
                          </Select>
                        </td>
                        <td className="px-2 py-2">
                          <Input
                            type="number"
                            min="0"
                            step="0.01"
                            value={line.sellingRate}
                            disabled={!editable}
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
                            disabled={!editable}
                            onChange={(event) => updateLine(line.key, { discount: event.target.value })}
                          />
                        </td>
                        <td className="px-2 py-2">
                          <div className="rounded-lg border border-slate-200 bg-slate-50 px-2 py-1.5 text-right">
                            <p className="text-[10px] font-semibold text-slate-400">
                              {gstPercentage > 0 ? `${gstPercentage}% GST` : 'Exempt'}
                            </p>
                            <p className="text-xs font-semibold text-slate-700">
                              {money(lineTotals?.taxAmount ?? 0)}
                            </p>
                          </div>
                        </td>
                        <td className="td text-right font-semibold">{money(lineTotals?.payableAmount ?? 0)}</td>
                        <td className="px-2 py-2 text-center">
                          <button
                            onClick={() => removeLine(line.key)}
                            disabled={!editable || lines.length === 1}
                            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-rose-50 hover:text-rose-600 disabled:opacity-30"
                            title="Remove line"
                            aria-label="Remove line"
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

          <Card className="overflow-hidden">
            <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
              <div className="flex items-center gap-2">
                <History className="h-4 w-4 text-slate-400" />
                <h3 className="text-sm font-bold text-slate-800">Modification history</h3>
              </div>
              <Badge tone="slate">Immutable</Badge>
            </div>
            {historyLoading && history.length === 0 ? (
              <div className="flex min-h-24 items-center justify-center">
                <LoadingState label="Loading history…" />
              </div>
            ) : history.length === 0 ? (
              <EmptyState
                icon={<FilePenLine className="h-6 w-6" />}
                title="No modifications yet"
                message="Every saved modification is stored here with its original values and reason."
              />
            ) : (
              <ul className="divide-y divide-slate-100">
                {history.map((entry) => {
                  const entryChange = Number(entry.differenceAmount)
                  return (
                    <li key={entry.modificationId} className="px-4 py-3">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-xs font-bold text-slate-800">{entry.modificationNumber}</span>
                          <Badge tone={entryChange > 0 ? 'red' : entryChange < 0 ? 'green' : 'slate'}>
                            {entryChange > 0
                              ? `+ ${money(entryChange)}`
                              : entryChange < 0
                                ? `− ${money(Math.abs(entryChange))}`
                                : 'No total change'}
                          </Badge>
                        </div>
                        <span className="text-[11px] text-slate-400">{formatDateTime(entry.modifiedAt)}</span>
                      </div>
                      <p className="mt-1 text-xs text-slate-600">
                        {money(entry.originalTotal)} → <strong>{money(entry.modifiedTotal)}</strong>
                        <span className="text-slate-400">
                          {' '}
                          · {entry.itemsAdded} added · {entry.itemsRemoved} removed · {entry.itemsUpdated} updated
                        </span>
                      </p>
                      {entry.reason && <p className="mt-1 text-xs text-slate-500 italic">“{entry.reason}”</p>}
                      {entry.lines.length > 0 && (
                        <details className="mt-2">
                          <summary className="cursor-pointer text-[11px] font-semibold text-brand-600">
                            Changed lines ({entry.lines.length})
                          </summary>
                          <ul className="mt-2 space-y-1.5">
                            {entry.lines.map((line) => (
                              <li
                                key={`${entry.modificationId}-${line.itemId}-${line.changeType}`}
                                className="flex flex-wrap items-center gap-x-2 gap-y-1 rounded-md bg-slate-50 px-2 py-1.5 text-[11px] text-slate-600"
                              >
                                <Badge tone={changeTone[line.changeType]}>{titleCase(line.changeType)}</Badge>
                                <span className="font-semibold text-slate-700">{line.itemName}</span>
                                {Number(line.originalQuantity) !== Number(line.modifiedQuantity) && (
                                  <span>
                                    Qty {qty(line.originalQuantity)} → {qty(line.modifiedQuantity)} {line.unit}
                                  </span>
                                )}
                                {Number(line.originalRate) !== Number(line.modifiedRate) && (
                                  <span>
                                    Rate {money(line.originalRate)} → {money(line.modifiedRate)}
                                  </span>
                                )}
                                <span
                                  className={
                                    Number(line.differenceAmount) > 0
                                      ? 'ml-auto font-semibold text-rose-600'
                                      : Number(line.differenceAmount) < 0
                                        ? 'ml-auto font-semibold text-emerald-600'
                                        : 'ml-auto text-slate-400'
                                  }
                                >
                                  {money(line.differenceAmount)}
                                </span>
                              </li>
                            ))}
                          </ul>
                        </details>
                      )}
                    </li>
                  )
                })}
              </ul>
            )}
          </Card>
        </div>

        <Card className="h-fit space-y-3 p-4 xl:sticky xl:top-20">
          <Field
            label="Modification reason"
            hint="Stored with the audit trail and shown on the bill history."
          >
            <Textarea
              rows={2}
              maxLength={500}
              value={reason}
              disabled={!editable}
              onChange={(event) => setReason(event.target.value)}
              placeholder="e.g. Customer changed quantity after billing"
            />
          </Field>

          <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
            <p className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">GST mode</p>
            <p className="mt-0.5 text-xs font-semibold text-slate-700">
              {inclusive ? 'Inclusive' : 'Exclusive'} ·{' '}
              {interState ? 'inter-state (IGST)' : 'intra-state (CGST+SGST)'}
            </p>
          </div>

          <div className="space-y-1 text-sm">
            <div className="flex justify-between text-slate-500">
              <span>Subtotal</span>
              <span>{money(totals.subtotal)}</span>
            </div>
            <div className="flex justify-between text-slate-500">
              <span>Discount</span>
              <span>− {money(totals.discount)}</span>
            </div>
            <div className="flex justify-between text-slate-500">
              <span>Taxable value</span>
              <span>{money(totals.taxable)}</span>
            </div>
            {interState ? (
              <div className="flex justify-between text-slate-500">
                <span>IGST</span>
                <span>{money(totals.igst)}</span>
              </div>
            ) : (
              <>
                <div className="flex justify-between text-slate-500">
                  <span>CGST</span>
                  <span>{money(totals.cgst)}</span>
                </div>
                <div className="flex justify-between text-slate-500">
                  <span>SGST</span>
                  <span>{money(totals.sgst)}</span>
                </div>
              </>
            )}
            <div className="flex justify-between text-slate-500">
              <span>Total tax</span>
              <span>{money(totals.tax)}</span>
            </div>
            <div className="flex justify-between border-t border-slate-200 pt-2 text-base font-bold text-slate-900">
              <span>Modified total</span>
              <span>{money(totals.grand)}</span>
            </div>
          </div>

          <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs">
            <div className="flex justify-between text-slate-500">
              <span>Original total</span>
              <span>{money(sale.grandTotal)}</span>
            </div>
            <div className="flex justify-between text-slate-500">
              <span>Modified total</span>
              <span className="font-semibold text-slate-700">{money(totals.grand)}</span>
            </div>
            <div
              className={`mt-1 flex justify-between border-t border-slate-200 pt-1 font-bold ${
                difference > 0 ? 'text-rose-600' : difference < 0 ? 'text-emerald-600' : 'text-slate-500'
              }`}
            >
              <span>{difference > 0 ? 'Increase' : difference < 0 ? 'Decrease' : 'No change'}</span>
              <span>
                {difference > 0 ? '+' : difference < 0 ? '−' : ''}
                {money(Math.abs(difference))}
              </span>
            </div>
          </div>

          <div className="flex flex-col gap-2">
            <Button
              className="w-full"
              disabled={!editable || !dirty}
              loading={previewing}
              icon={previewing ? undefined : <ArrowRight className="h-4 w-4" />}
              onClick={() => void review()}
            >
              Review changes
            </Button>
            <Button
              variant="outline"
              className="w-full"
              icon={<RotateCcw className="h-4 w-4" />}
              disabled={!editable || !dirty}
              onClick={resetLines}
            >
              Discard edits
            </Button>
          </div>
          <p className="text-center text-[11px] text-slate-400">
            Totals are recalculated by the server with GST before the bill is saved. Stock is adjusted by the
            difference only.
          </p>
        </Card>
      </div>

      <Modal
        open={confirmOpen && Boolean(preview)}
        onClose={() => {
          if (!saving) setConfirmOpen(false)
        }}
        title="Confirm bill modification"
        description={`${sale.salesNumber} · ${formatDate(sale.salesDate)} · ${sale.branchName}`}
        size="lg"
        footer={
          <>
            <Button variant="outline" onClick={() => setConfirmOpen(false)} disabled={saving}>
              Back to edit
            </Button>
            <Button
              onClick={() => void confirm()}
              loading={saving}
              icon={saving ? undefined : <CheckCircle2 className="h-4 w-4" />}
            >
              Confirm modification
            </Button>
          </>
        }
      >
        {preview && (
          <div className="space-y-4">
            <div
              className={`rounded-xl border p-4 ${
                change > 0
                  ? 'border-rose-200 bg-rose-50'
                  : change < 0
                    ? 'border-emerald-200 bg-emerald-50'
                    : 'border-slate-200 bg-slate-50'
              }`}
            >
              <Badge tone={change > 0 ? 'red' : change < 0 ? 'green' : 'slate'}>
                {change > 0 ? 'Amount increased' : change < 0 ? 'Amount decreased' : 'No change in amount'}
              </Badge>
              <div className="mt-3 flex flex-wrap items-end gap-3">
                <div>
                  <p className="text-[10px] font-bold tracking-wider text-slate-500 uppercase">Original</p>
                  <p className="text-lg font-bold text-slate-700">{money(preview.originalTotal)}</p>
                </div>
                <ArrowRight className="mb-1.5 h-4 w-4 text-slate-400" />
                <div>
                  <p className="text-[10px] font-bold tracking-wider text-slate-500 uppercase">Modified</p>
                  <p className="text-lg font-bold text-slate-900">{money(preview.modifiedTotal)}</p>
                </div>
                <div className="ml-auto text-right">
                  <p className="text-[10px] font-bold tracking-wider text-slate-500 uppercase">Difference</p>
                  <p
                    className={`text-lg font-bold ${
                      change > 0 ? 'text-rose-600' : change < 0 ? 'text-emerald-600' : 'text-slate-500'
                    }`}
                  >
                    {change > 0 ? '+' : change < 0 ? '−' : ''}
                    {money(Math.abs(change))}
                  </p>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <div className="rounded-lg bg-emerald-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-emerald-700 uppercase">Items added</p>
                <p className="text-sm font-bold text-emerald-900">{preview.itemsAdded}</p>
              </div>
              <div className="rounded-lg bg-rose-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-rose-700 uppercase">Items removed</p>
                <p className="text-sm font-bold text-rose-900">{preview.itemsRemoved}</p>
              </div>
              <div className="rounded-lg bg-amber-50 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-amber-700 uppercase">Items updated</p>
                <p className="text-sm font-bold text-amber-900">{preview.itemsUpdated}</p>
              </div>
              <div className="rounded-lg bg-slate-100 px-3 py-2">
                <p className="text-[10px] font-bold tracking-wider text-slate-600 uppercase">Quantity</p>
                <p className="text-sm font-bold text-slate-800">
                  +{qty(preview.quantityIncreased)} / −{qty(preview.quantityDecreased)}
                </p>
              </div>
            </div>

            {Number(preview.additionalPayable) > 0 && (
              <p className="rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-700">
                Additional amount payable by the customer: <strong>{money(preview.additionalPayable)}</strong>
              </p>
            )}
            {Number(preview.refundDue) > 0 && (
              <p className="rounded-lg bg-sky-50 px-3 py-2 text-xs text-sky-700">
                Refund due to the customer: <strong>{money(preview.refundDue)}</strong>
              </p>
            )}

            <div className="overflow-hidden rounded-lg border border-slate-200">
              <div className="thin-scrollbar overflow-x-auto">
                <table className="w-full min-w-[640px]">
                  <thead className="bg-slate-50">
                    <tr>
                      <th className="th">Item</th>
                      <th className="th">Change</th>
                      <th className="th text-right">Quantity</th>
                      <th className="th text-right">Rate</th>
                      <th className="th text-right">Amount</th>
                      <th className="th text-right">Difference</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {preview.lines.map((line) => (
                      <tr key={`${line.itemId}-${line.changeType}`}>
                        <td className="td">
                          <p className="font-semibold text-slate-800">{line.itemName}</p>
                          <p className="text-[11px] text-slate-400">{line.itemCode}</p>
                        </td>
                        <td className="td">
                          <Badge tone={changeTone[line.changeType]}>{titleCase(line.changeType)}</Badge>
                        </td>
                        <td className="td text-right">
                          {qty(line.originalQuantity)} → {qty(line.modifiedQuantity)} {line.unit}
                        </td>
                        <td className="td text-right">
                          {money(line.originalRate)} → {money(line.modifiedRate)}
                        </td>
                        <td className="td text-right">
                          {money(line.originalAmount)} → {money(line.modifiedAmount)}
                        </td>
                        <td
                          className={`td text-right font-semibold ${
                            Number(line.differenceAmount) > 0
                              ? 'text-rose-600'
                              : Number(line.differenceAmount) < 0
                                ? 'text-emerald-600'
                                : 'text-slate-400'
                          }`}
                        >
                          {money(line.differenceAmount)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="rounded-lg border border-slate-200 px-3 py-2 text-xs">
              <span className="font-semibold text-slate-600">Reason: </span>
              {preview.reason ? (
                <span className="text-slate-700">{preview.reason}</span>
              ) : (
                <span className="text-slate-400">Not recorded</span>
              )}
            </div>

            <p className="rounded-lg bg-amber-50 px-3 py-2 text-[11px] text-amber-800">
              Saving records an immutable modification entry, updates the bill totals and adjusts branch stock by
              the difference. The original values stay in the history.
            </p>
          </div>
        )}
      </Modal>

      <InvoicePrintPreview
        open={previewOpen}
        sale={sale}
        store={storeInfo}
        onClose={() => setPreviewOpen(false)}
      />
    </>
  )
}
