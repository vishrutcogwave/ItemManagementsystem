import { ArrowDownToLine, ArrowUpFromLine, Boxes, History, IndianRupee, SlidersHorizontal, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  Badge,
  Button,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  Field,
  Input,
  Modal,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  Textarea,
  Toggle,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { useUnits } from '../hooks/useUnits'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { formatDateTime, money, qty, titleCase } from '../lib/format'
import { unitOptionsFor } from '../lib/units'
import { useAuthStore } from '../store/auth'
import { notify } from '../store/ui'
import type {
  Category,
  InventoryRow,
  InventoryTransaction,
  Item,
  PageResponse,
  Unit,
} from '../types'

interface AdjustState {
  branchId: number | ''
  itemId: number | ''
  type: 'IN' | 'OUT'
  quantity: string
  unit: Unit
  reason: string
}

interface PricingState {
  minimumStock: string
  purchasePrice: string
  sellingPrice: string
}

export function InventoryPage() {
  const [params, setParams] = useSearchParams()
  const { branches, selectedBranchId, hasPermission } = useAuthStore()
  const { units } = useUnits()
  const activeUnits = units.filter((unit) => unit.status === 'ACTIVE')
  const canAdjust = hasPermission('INVENTORY_ADJUST')
  const branchId = selectedBranchId

  const [tab, setTab] = useState<'stock' | 'movements'>('stock')
  const [search, setSearch] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [lowOnly, setLowOnly] = useState(false)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [moveSearch, setMoveSearch] = useState('')
  const [movePage, setMovePage] = useState(0)
  const [moveSize, setMoveSize] = useState(20)
  const debouncedSearch = useDebounce(search)
  const debouncedMoveSearch = useDebounce(moveSearch)

  const { data: categories } = useApi<Category[]>(
    () => api.get('/categories/options').then((response) => response.data),
    [],
  )
  const { data: items } = useApi<Item[]>(() => api.get('/items/options').then((response) => response.data), [])

  const {
    data: stock,
    loading,
    error,
    reload,
  } = useApi<PageResponse<InventoryRow>>(
    () =>
      api
        .get('/inventory', {
          params: {
            branchId: branchId ?? undefined,
            categoryId: categoryId || undefined,
            search: debouncedSearch || undefined,
            lowOnly,
            page,
            size,
          },
        })
        .then((response) => response.data),
    [branchId, categoryId, debouncedSearch, lowOnly, page, size],
  )

  const {
    data: movements,
    loading: movementsLoading,
    error: movementsError,
    reload: reloadMovements,
  } = useApi<PageResponse<InventoryTransaction>>(
    () =>
      api
        .get('/inventory/transactions', {
          params: {
            branchId: branchId ?? undefined,
            search: debouncedMoveSearch || undefined,
            page: movePage,
            size: moveSize,
          },
        })
        .then((response) => response.data),
    [branchId, debouncedMoveSearch, movePage, moveSize, tab],
  )

  const [adjust, setAdjust] = useState<AdjustState | null>(() =>
    params.get('adjust') === '1'
      ? {
          branchId: selectedBranchId ?? branches[0]?.id ?? '',
          itemId: '',
          type: 'IN',
          quantity: '',
          unit: '',
          reason: '',
        }
      : null,
  )
  const [adjustErrors, setAdjustErrors] = useState<Record<string, string>>({})
  const [adjustError, setAdjustError] = useState<string | null>(null)
  const [adjusting, setAdjusting] = useState(false)

  const [pricing, setPricing] = useState<{ row: InventoryRow; form: PricingState } | null>(null)
  const [pricingErrors, setPricingErrors] = useState<Record<string, string>>({})
  const [pricingError, setPricingError] = useState<string | null>(null)
  const [pricingSaving, setPricingSaving] = useState(false)

  const closeAdjust = () => {
    setAdjust(null)
    if (params.get('adjust')) {
      const next = new URLSearchParams(params)
      next.delete('adjust')
      setParams(next, { replace: true })
    }
  }

  const openAdjustForRow = (row: InventoryRow) => {
    setAdjust({
      branchId: row.branchId,
      itemId: row.itemId,
      type: 'IN',
      quantity: '',
      unit: row.unit,
      reason: '',
    })
    setAdjustErrors({})
    setAdjustError(null)
  }

  const saveAdjust = async () => {
    if (!adjust) return
    const nextErrors: Record<string, string> = {}
    if (!adjust.branchId) nextErrors.branchId = 'Select a branch'
    if (!adjust.itemId) nextErrors.itemId = 'Select an item'
    if (!adjust.quantity || Number(adjust.quantity) <= 0) nextErrors.quantity = 'Enter a quantity above zero'
    if (!adjust.reason.trim()) nextErrors.reason = 'A reason is required for audit'
    if (Object.keys(nextErrors).length > 0) {
      setAdjustErrors(nextErrors)
      return
    }

    setAdjusting(true)
    setAdjustError(null)
    setAdjustErrors({})
    try {
      await api.post('/inventory/adjustments', {
        branchId: adjust.branchId,
        itemId: adjust.itemId,
        type: adjust.type,
        quantity: Number(adjust.quantity),
        unit: adjust.unit,
        reason: adjust.reason.trim(),
      })
      notify(`Stock ${adjust.type === 'IN' ? 'increased' : 'reduced'} successfully`)
      closeAdjust()
      reload()
      reloadMovements()
    } catch (err) {
      setAdjustError(errorMessage(err))
      setAdjustErrors(fieldErrorsOf(err))
    } finally {
      setAdjusting(false)
    }
  }

  const openPricing = (row: InventoryRow) => {
    setPricing({
      row,
      form: {
        minimumStock: String(row.minimumStock),
        purchasePrice: String(row.purchasePrice),
        sellingPrice: String(row.sellingPrice),
      },
    })
    setPricingErrors({})
    setPricingError(null)
  }

  const savePricing = async () => {
    if (!pricing) return
    const nextErrors: Record<string, string> = {}
    if (pricing.form.minimumStock === '') nextErrors.minimumStock = 'Required'
    if (pricing.form.purchasePrice === '') nextErrors.purchasePrice = 'Required'
    if (pricing.form.sellingPrice === '') nextErrors.sellingPrice = 'Required'
    if (Object.keys(nextErrors).length > 0) {
      setPricingErrors(nextErrors)
      return
    }
    setPricingSaving(true)
    setPricingError(null)
    try {
      await api.put(`/inventory/${pricing.row.branchId}/items/${pricing.row.itemId}/pricing`, {
        minimumStock: Number(pricing.form.minimumStock),
        purchasePrice: Number(pricing.form.purchasePrice),
        sellingPrice: Number(pricing.form.sellingPrice),
      })
      notify('Branch pricing updated')
      setPricing(null)
      reload()
    } catch (err) {
      setPricingError(errorMessage(err))
      setPricingErrors(fieldErrorsOf(err))
    } finally {
      setPricingSaving(false)
    }
  }

  const columns: Column<InventoryRow>[] = [
    {
      key: 'item',
      header: 'Item',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
            <Boxes className="h-4 w-4" />
          </span>
          <div>
            <p className="font-semibold text-slate-800">{row.itemName}</p>
            <p className="text-[11px] text-slate-400">
              {row.itemCode} · {row.categoryName}
            </p>
          </div>
        </div>
      ),
    },
    ...(branchId
      ? []
      : [
          {
            key: 'branch',
            header: 'Branch',
            render: (row: InventoryRow) => <Badge tone="slate">{row.branchName}</Badge>,
          },
        ]),
    {
      key: 'stock',
      header: 'On hand',
      align: 'right' as const,
      render: (row: InventoryRow) => (
        <span className={`font-bold ${row.lowStock ? 'text-rose-600' : 'text-slate-800'}`}>
          {qty(row.currentStock)} {row.unit}
        </span>
      ),
    },
    { key: 'min', header: 'Minimum', align: 'right' as const, render: (row: InventoryRow) => <span className="text-slate-500">{qty(row.minimumStock)}</span> },
    { key: 'purchase', header: 'Cost ₹', align: 'right' as const, render: (row: InventoryRow) => <span className="text-slate-600">{money(row.purchasePrice)}</span> },
    { key: 'selling', header: 'Selling ₹', align: 'right' as const, render: (row: InventoryRow) => <span className="font-semibold">{money(row.sellingPrice)}</span> },
    { key: 'value', header: 'Stock value', align: 'right' as const, render: (row: InventoryRow) => money(row.stockValue) },
    {
      key: 'status',
      header: 'Status',
      render: (row: InventoryRow) =>
        row.lowStock ? (
          <Badge tone="red">
            <TriangleAlert className="h-3 w-3" /> Low
          </Badge>
        ) : (
          <Badge tone="green">Healthy</Badge>
        ),
    },
    {
      key: 'actions',
      header: '',
      align: 'right' as const,
      render: (row: InventoryRow) =>
        canAdjust ? (
          <div className="flex items-center justify-end gap-1">
            <button
              onClick={() => openAdjustForRow(row)}
              className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
              title="Adjust stock"
            >
              <SlidersHorizontal className="h-3.5 w-3.5" />
            </button>
            <button
              onClick={() => openPricing(row)}
              className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
              title="Edit branch pricing"
            >
              <IndianRupee className="h-3.5 w-3.5" />
            </button>
          </div>
        ) : null,
    },
  ]

  const movementColumns: Column<InventoryTransaction>[] = [
    { key: 'date', header: 'When', render: (row) => <span className="text-slate-500">{formatDateTime(row.transactionDate)}</span> },
    {
      key: 'item',
      header: 'Item',
      render: (row) => (
        <div>
          <p className="font-semibold text-slate-800">{row.itemName}</p>
          <p className="text-[11px] text-slate-400">{row.branchName}</p>
        </div>
      ),
    },
    {
      key: 'type',
      header: 'Movement',
      render: (row) => (
        <Badge tone={Number(row.quantityIn) > 0 ? 'green' : 'red'}>
          {Number(row.quantityIn) > 0 ? <ArrowDownToLine className="h-3 w-3" /> : <ArrowUpFromLine className="h-3 w-3" />}
          {titleCase(row.transactionType)}
        </Badge>
      ),
    },
    {
      key: 'reference',
      header: 'Reference',
      render: (row) => <span className="text-xs text-slate-500">{row.referenceNumber || '—'}</span>,
    },
    {
      key: 'in',
      header: 'In',
      align: 'right',
      render: (row) =>
        Number(row.quantityIn) > 0 ? <span className="font-semibold text-emerald-600">+{qty(row.quantityIn)}</span> : <span className="text-slate-300">—</span>,
    },
    {
      key: 'out',
      header: 'Out',
      align: 'right',
      render: (row) =>
        Number(row.quantityOut) > 0 ? <span className="font-semibold text-rose-600">−{qty(row.quantityOut)}</span> : <span className="text-slate-300">—</span>,
    },
    { key: 'balance', header: 'Balance', align: 'right', render: (row) => <span className="font-medium">{qty(row.balanceAfter)} {row.unit}</span> },
    { key: 'notes', header: 'Notes', render: (row) => <span className="max-w-56 truncate text-xs text-slate-500">{row.notes || '—'}</span> },
  ]

  const adjustItem = items?.find((item) => item.id === adjust?.itemId)

  return (
    <div>
      <PageHeader
        title="Inventory"
        subtitle="Live stock levels by branch, with adjustments and movement history."
        actions={
          canAdjust ? (
            <Button
              icon={<SlidersHorizontal className="h-4 w-4" />}
              onClick={() => {
                setAdjust({
                  branchId: selectedBranchId ?? branches[0]?.id ?? '',
                  itemId: '',
                  type: 'IN',
                  quantity: '',
                  unit: '',
                  reason: '',
                })
                setAdjustErrors({})
                setAdjustError(null)
              }}
            >
              Adjust stock
            </Button>
          ) : undefined
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex gap-1 rounded-lg bg-slate-100 p-1 text-xs font-semibold">
            <button
              onClick={() => setTab('stock')}
              className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 transition ${tab === 'stock' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}
            >
              <Boxes className="h-3.5 w-3.5" /> Stock levels
            </button>
            <button
              onClick={() => setTab('movements')}
              className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 transition ${tab === 'movements' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}
            >
              <History className="h-3.5 w-3.5" /> Movements
            </button>
          </div>

          {tab === 'stock' ? (
            <div className="flex flex-wrap items-center gap-2.5">
              <SearchInput
                className="w-full sm:w-64"
                placeholder="Search item, code or barcode…"
                value={search}
                onChange={(event) => {
                  setSearch(event.target.value)
                  setPage(0)
                }}
              />
              <Select
                className="w-44"
                value={categoryId}
                onChange={(event) => {
                  setCategoryId(event.target.value)
                  setPage(0)
                }}
              >
                <option value="">All categories</option>
                {categories?.map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.categoryName}
                  </option>
                ))}
              </Select>
              <Toggle
                checked={lowOnly}
                onChange={(value) => {
                  setLowOnly(value)
                  setPage(0)
                }}
                label="Low stock only"
              />
            </div>
          ) : (
            <SearchInput
              className="w-full sm:w-72"
              placeholder="Search item, reference or notes…"
              value={moveSearch}
              onChange={(event) => {
                setMoveSearch(event.target.value)
                setMovePage(0)
              }}
            />
          )}
        </div>

        {tab === 'stock' ? (
          error ? (
            <ErrorState message={error} onRetry={reload} />
          ) : (
            <>
              <DataTable
                columns={columns}
                rows={stock?.content ?? []}
                rowKey={(row) => `${row.branchId}-${row.itemId}`}
                loading={loading}
                highlight={(row) => row.lowStock}
                empty={
                  <EmptyState
                    icon={<Boxes className="h-6 w-6" />}
                    title="No inventory records"
                    message="Stock appears once purchases, opening stock or adjustments are recorded."
                  />
                }
              />
              {stock && stock.totalElements > 0 && (
                <Pagination
                  page={page}
                  size={size}
                  totalPages={stock.totalPages}
                  totalElements={stock.totalElements}
                  onPageChange={setPage}
                  onSizeChange={(value) => {
                    setSize(value)
                    setPage(0)
                  }}
                  itemLabel="stock records"
                />
              )}
            </>
          )
        ) : movementsError ? (
          <ErrorState message={movementsError} onRetry={reloadMovements} />
        ) : (
          <>
            <DataTable
              columns={movementColumns}
              rows={movements?.content ?? []}
              rowKey={(row) => row.id}
              loading={movementsLoading}
              empty={<EmptyState icon={<History className="h-6 w-6" />} title="No stock movements" message="Purchases, sales and adjustments will be listed here." />}
            />
            {movements && movements.totalElements > 0 && (
              <Pagination
                page={movePage}
                size={moveSize}
                totalPages={movements.totalPages}
                totalElements={movements.totalElements}
                onPageChange={setMovePage}
                onSizeChange={(value) => {
                  setMoveSize(value)
                  setMovePage(0)
                }}
                itemLabel="movements"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={Boolean(adjust)}
        onClose={closeAdjust}
        title="Adjust stock"
        description="Every adjustment is logged with a reason for audit."
        footer={
          <>
            <Button variant="outline" onClick={closeAdjust} disabled={adjusting}>
              Cancel
            </Button>
            <Button onClick={saveAdjust} loading={adjusting}>
              {adjust?.type === 'IN' ? 'Add stock' : 'Reduce stock'}
            </Button>
          </>
        }
      >
        {adjust && (
          <div className="space-y-4">
            {adjustError && (
              <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
                {adjustError}
              </div>
            )}
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label="Branch" required error={adjustErrors.branchId}>
                <Select
                  value={adjust.branchId}
                  onChange={(event) => setAdjust({ ...adjust, branchId: event.target.value ? Number(event.target.value) : '' })}
                >
                  <option value="">Select branch</option>
                  {branches.map((branch) => (
                    <option key={branch.id} value={branch.id}>
                      {branch.branchName}
                    </option>
                  ))}
                </Select>
              </Field>
              <Field label="Item" required error={adjustErrors.itemId}>
                <Select
                  value={adjust.itemId}
                  onChange={(event) => {
                    const itemId = event.target.value ? Number(event.target.value) : ''
                    const item = items?.find((entry) => entry.id === itemId)
                    setAdjust({ ...adjust, itemId, unit: item?.baseUnit ?? '' })
                  }}
                >
                  <option value="">Select item</option>
                  {items?.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.itemName} ({item.itemCode})
                    </option>
                  ))}
                </Select>
              </Field>
            </div>

            <Field label="Adjustment type">
              <div className="grid grid-cols-2 gap-2">
                <button
                  type="button"
                  onClick={() => setAdjust({ ...adjust, type: 'IN' })}
                  className={`flex items-center justify-center gap-1.5 rounded-lg border px-3 py-2 text-xs font-semibold transition ${
                    adjust.type === 'IN' ? 'border-emerald-300 bg-emerald-50 text-emerald-700' : 'border-slate-200 text-slate-500 hover:bg-slate-50'
                  }`}
                >
                  <ArrowDownToLine className="h-4 w-4" /> Stock in
                </button>
                <button
                  type="button"
                  onClick={() => setAdjust({ ...adjust, type: 'OUT' })}
                  className={`flex items-center justify-center gap-1.5 rounded-lg border px-3 py-2 text-xs font-semibold transition ${
                    adjust.type === 'OUT' ? 'border-rose-300 bg-rose-50 text-rose-700' : 'border-slate-200 text-slate-500 hover:bg-slate-50'
                  }`}
                >
                  <ArrowUpFromLine className="h-4 w-4" /> Stock out
                </button>
              </div>
            </Field>

            <div className="grid grid-cols-2 gap-3">
              <Field label="Quantity" required error={adjustErrors.quantity}>
                <Input
                  type="number"
                  min="0.001"
                  step="0.001"
                  value={adjust.quantity}
                  onChange={(event) => setAdjust({ ...adjust, quantity: event.target.value })}
                  placeholder="0"
                />
              </Field>
              <Field label="Unit">
                <Select value={adjust.unit} onChange={(event) => setAdjust({ ...adjust, unit: event.target.value as Unit })}>
                  {unitOptionsFor(activeUnits, adjustItem?.baseUnit).map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </Select>
              </Field>
            </div>

            <Field label="Reason" required error={adjustErrors.reason}>
              <Textarea
                value={adjust.reason}
                onChange={(event) => setAdjust({ ...adjust, reason: event.target.value })}
                placeholder="e.g. Damaged goods, stock count correction"
                rows={2}
              />
            </Field>
          </div>
        )}
      </Modal>

      <Modal
        open={Boolean(pricing)}
        onClose={() => setPricing(null)}
        title={pricing ? `${pricing.row.itemName} · branch pricing` : 'Branch pricing'}
        description={pricing ? `${pricing.row.branchName} · stock tracked in ${pricing.row.unit}` : undefined}
        footer={
          <>
            <Button variant="outline" onClick={() => setPricing(null)} disabled={pricingSaving}>
              Cancel
            </Button>
            <Button onClick={savePricing} loading={pricingSaving}>
              Save pricing
            </Button>
          </>
        }
      >
        {pricing && (
          <div className="space-y-4">
            {pricingError && (
              <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
                {pricingError}
              </div>
            )}
            <Field label="Minimum stock level" required error={pricingErrors.minimumStock}>
              <Input
                type="number"
                min="0"
                step="0.001"
                value={pricing.form.minimumStock}
                onChange={(event) => setPricing({ ...pricing, form: { ...pricing.form, minimumStock: event.target.value } })}
              />
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="Purchase price ₹" required error={pricingErrors.purchasePrice}>
                <Input
                  type="number"
                  min="0"
                  step="0.01"
                  value={pricing.form.purchasePrice}
                  onChange={(event) => setPricing({ ...pricing, form: { ...pricing.form, purchasePrice: event.target.value } })}
                />
              </Field>
              <Field label="Selling price ₹" required error={pricingErrors.sellingPrice}>
                <Input
                  type="number"
                  min="0"
                  step="0.01"
                  value={pricing.form.sellingPrice}
                  onChange={(event) => setPricing({ ...pricing, form: { ...pricing.form, sellingPrice: event.target.value } })}
                />
              </Field>
            </div>
            <p className="rounded-lg bg-slate-50 px-3 py-2 text-[11px] leading-relaxed text-slate-500">
              Current stock: {qty(pricing.row.currentStock)} {pricing.row.unit}. Weighted average cost stays unchanged;
              purchase and selling prices here are used for billing and valuation at this branch.
            </p>
          </div>
        )}
      </Modal>
    </div>
  )
}
