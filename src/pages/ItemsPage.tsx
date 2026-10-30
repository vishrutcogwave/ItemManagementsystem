import { Boxes, Package, Pencil, Plus, Power } from 'lucide-react'
import { useEffect, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  Field,
  Input,
  LoadingState,
  Modal,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatusBadge,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { formatDateTime, money, qty, titleCase } from '../lib/format'
import { UNIT_OPTIONS } from '../lib/units'
import { useAuthStore } from '../store/auth'
import { notify } from '../store/ui'
import type {
  BranchStock,
  Category,
  InventoryTransaction,
  Item,
  ItemRequest,
  PageResponse,
  TaxMaster,
  Unit,
} from '../types'

interface FormState {
  itemCode: string
  itemName: string
  categoryId: number | ''
  taxId: number | ''
  baseUnit: Unit
  rate: string
  minStockLevel: string
  status: 'ACTIVE' | 'INACTIVE'
}

const emptyForm: FormState = {
  itemCode: '',
  itemName: '',
  categoryId: '',
  taxId: '',
  baseUnit: 'PIECE',
  rate: '',
  minStockLevel: '0',
  status: 'ACTIVE',
}

export function ItemsPage() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const canManage = hasPermission('ITEM_MANAGE')

  const [search, setSearch] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<Item>>(
    () =>
      api
        .get('/items', {
          params: {
            search: debouncedSearch || undefined,
            categoryId: categoryId || undefined,
            status: status || undefined,
            page,
            size,
          },
        })
        .then((response) => response.data),
    [debouncedSearch, categoryId, status, page, size],
  )

  const { data: categories } = useApi<Category[]>(
    () => api.get('/categories/options').then((response) => response.data),
    [],
  )

  const { data: taxes } = useApi<TaxMaster[]>(
    () => api.get('/taxes/options').then((response) => response.data),
    [],
  )
  const activeTaxes = taxes?.filter((tax) => tax.status === 'ACTIVE') ?? []
  const defaultTaxId = activeTaxes.find((tax) => tax.taxType === 'GST')?.id ?? activeTaxes[0]?.id ?? ''
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Item | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm)
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const [stockItem, setStockItem] = useState<Item | null>(null)
  const [stock, setStock] = useState<BranchStock[] | null>(null)
  const [stockTxns, setStockTxns] = useState<InventoryTransaction[] | null>(null)

  useEffect(() => {
    if (!stockItem) return
    let active = true
    setStock(null)
    setStockTxns(null)
    Promise.all([
      api.get<BranchStock[]>(`/items/${stockItem.id}/stock`).then((response) => response.data),
      api.get<InventoryTransaction[]>(`/items/${stockItem.id}/transactions`).then((response) => response.data),
    ])
      .then(([branchStock, transactions]) => {
        if (!active) return
        setStock(branchStock)
        setStockTxns(transactions.slice(0, 15))
      })
      .catch(() => {
        if (!active) return
        setStock([])
        setStockTxns([])
      })
    return () => {
      active = false
    }
  }, [stockItem])

  const openCreate = () => {
    setEditing(null)
    setForm({ ...emptyForm, categoryId: categories?.[0]?.id ?? '', taxId: defaultTaxId })
    setFormErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const openEdit = (item: Item) => {
    setEditing(item)
    setForm({
      itemCode: item.itemCode,
      itemName: item.itemName,
      categoryId: item.categoryId,
      taxId: item.taxId,
      baseUnit: item.baseUnit,
      rate: String(item.rate),
      minStockLevel: String(item.minStockLevel),
      status: item.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    })
    setFormErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const save = async () => {
    const nextErrors: Record<string, string> = {}
    if (!form.itemCode.trim()) nextErrors.itemCode = 'Item code is required'
    if (!form.itemName.trim()) nextErrors.itemName = 'Item name is required'
    if (!form.categoryId) nextErrors.categoryId = 'Select a category'
    if (!form.taxId) nextErrors.taxId = 'Select a tax slab'
    if (form.rate === '' || Number(form.rate) <= 0) nextErrors.rate = 'Rate must be greater than zero'
    if (form.minStockLevel === '') nextErrors.minStockLevel = 'Minimum stock is required'
    if (Object.keys(nextErrors).length > 0) {
      setFormErrors(nextErrors)
      return
    }

    setSaving(true)
    setFormError(null)
    setFormErrors({})
    try {
      const payload: ItemRequest = {
        itemCode: form.itemCode.trim().toUpperCase(),
        itemName: form.itemName.trim(),
        categoryId: form.categoryId,
        taxId: form.taxId,
        baseUnit: form.baseUnit,
        rate: Number(form.rate),
        minStockLevel: Number(form.minStockLevel),
        status: form.status,
      }
      if (editing) {
        await api.put(`/items/${editing.id}`, payload)
        notify('Item updated')
      } else {
        await api.post('/items', payload)
        notify('Item created')
      }
      setModalOpen(false)
      reload()
    } catch (err) {
      setFormError(errorMessage(err))
      setFormErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  const toggleStatus = async (item: Item) => {
    try {
      await api.put(`/items/${item.id}/status`, {
        status: item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      })
      notify(item.status === 'ACTIVE' ? 'Item deactivated' : 'Item activated')
      reload()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Unable to update item')
    }
  }

  const columns: Column<Item>[] = [
    {
      key: 'item',
      header: 'Item',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
            <Package className="h-4 w-4" />
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
    { key: 'unit', header: 'Unit', render: (row) => <Badge tone="slate">{row.baseUnit}</Badge> },
    { key: 'rate', header: 'Rate ₹', align: 'right', render: (row) => <span className="font-semibold text-slate-800">{money(row.rate)}</span> },
    { key: 'min', header: 'Min stock', align: 'right', render: (row) => qty(row.minStockLevel) },
    {
      key: 'tax',
      header: 'Tax',
      render: (row) =>
        row.taxType === 'EXEMPT' ? (
          <Badge tone="slate">Exempt</Badge>
        ) : (
          <span className="text-xs font-medium text-slate-600">
            {row.taxName} · {Number(row.gstPercentage)}%
          </span>
        ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (row) => (
        <div className="flex items-center justify-end gap-1">
          <button
            onClick={() => setStockItem(row)}
            className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            title="View stock"
          >
            <Boxes className="h-3.5 w-3.5" />
          </button>
          {canManage && (
            <>
              <button
                onClick={() => openEdit(row)}
                className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
                title="Edit"
              >
                <Pencil className="h-3.5 w-3.5" />
              </button>
              <button
                onClick={() => toggleStatus(row)}
                className={`flex h-7 w-7 items-center justify-center rounded-md transition hover:bg-slate-100 ${
                  row.status === 'ACTIVE' ? 'text-amber-500' : 'text-emerald-500'
                }`}
                title={row.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
              >
                <Power className="h-3.5 w-3.5" />
              </button>
            </>
          )}
        </div>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="Items"
        subtitle="Your product catalogue with a single POS rate and reorder levels."
        actions={
          canManage ? (
            <Button icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
              Add item
            </Button>
          ) : undefined
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 lg:flex-row lg:items-center">
          <SearchInput
            className="lg:w-72"
            placeholder="Search by name or code…"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value)
              setPage(0)
            }}
          />
          <Select
            className="lg:w-48"
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
          <Select
            className="lg:w-40"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value)
              setPage(0)
            }}
          >
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </Select>
        </div>

        {error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={data?.content ?? []}
              rowKey={(row) => row.id}
              loading={loading}
              empty={
                <EmptyState
                  icon={<Package className="h-6 w-6" />}
                  title="No items found"
                  message="Add items to start purchasing, stocking and billing."
                  action={canManage ? <Button size="sm" onClick={openCreate}>Add item</Button> : undefined}
                />
              }
            />
            {data && data.totalElements > 0 && (
              <Pagination
                page={page}
                size={size}
                totalPages={data.totalPages}
                totalElements={data.totalElements}
                onPageChange={setPage}
                onSizeChange={(value) => {
                  setSize(value)
                  setPage(0)
                }}
                itemLabel="items"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit item' : 'Add item'}
        description="The rate is used automatically in POS billing. Purchase and sales entries can use different rates."
        size="lg"
        footer={
          <>
            <Button variant="outline" onClick={() => setModalOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              {editing ? 'Save changes' : 'Create item'}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {formError && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700">
              {formError}
            </div>
          )}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Item code" required error={formErrors.itemCode}>
              <Input
                value={form.itemCode}
                onChange={(event) => setForm({ ...form, itemCode: event.target.value.toUpperCase() })}
                placeholder="MILK500"
                className="uppercase"
                disabled={Boolean(editing)}
              />
            </Field>
            <Field label="Item name" required error={formErrors.itemName}>
              <Input
                value={form.itemName}
                onChange={(event) => setForm({ ...form, itemName: event.target.value })}
                placeholder="Milk 500 ml"
              />
            </Field>
            <Field label="Category" required error={formErrors.categoryId}>
              <Select
                value={form.categoryId}
                onChange={(event) =>
                  setForm({ ...form, categoryId: event.target.value ? Number(event.target.value) : '' })
                }
              >
                <option value="">Select category</option>
                {categories?.map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.categoryName}
                  </option>
                ))}
              </Select>
            </Field>
            <Field
              label="Tax slab"
              required
              error={formErrors.taxId}
              hint="GST is applied automatically from this slab"
            >
              <Select
                value={form.taxId}
                onChange={(event) =>
                  setForm({ ...form, taxId: event.target.value ? Number(event.target.value) : '' })
                }
              >
                <option value="">Select tax</option>
                {taxes?.length === 0 && <option disabled>No taxes configured</option>}
                {taxes?.map((tax) => (
                  <option
                    key={tax.id}
                    value={tax.id}
                    disabled={tax.status !== 'ACTIVE' && tax.id !== form.taxId}
                  >
                    {tax.taxName}
                    {tax.taxType === 'EXEMPT' ? ' · Exempt' : ` · ${Number(tax.gstPercentage)}%`}
                    {tax.status !== 'ACTIVE' ? ' (inactive)' : ''}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Base unit" required error={formErrors.baseUnit} hint="Stock is tracked in this unit">
              <Select
                value={form.baseUnit}
                onChange={(event) => setForm({ ...form, baseUnit: event.target.value as Unit })}
              >
                {UNIT_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {titleCase(option.value)}
                  </option>
                ))}
              </Select>
            </Field>
            <Field
              label="Rate ₹"
              required
              error={formErrors.rate}
              hint="Used automatically as the POS billing rate"
            >
              <Input
                type="number"
                min="0.01"
                step="0.01"
                value={form.rate}
                onChange={(event) => setForm({ ...form, rate: event.target.value })}
                placeholder="0.00"
              />
            </Field>
            <Field label="Minimum stock level" required error={formErrors.minStockLevel}>
              <Input
                type="number"
                min="0"
                step="0.001"
                value={form.minStockLevel}
                onChange={(event) => setForm({ ...form, minStockLevel: event.target.value })}
              />
            </Field>
            <Field label="Status">
              <Select
                value={form.status}
                onChange={(event) => setForm({ ...form, status: event.target.value as FormState['status'] })}
              >
                <option value="ACTIVE">Active</option>
                <option value="INACTIVE">Inactive</option>
              </Select>
            </Field>
          </div>
        </div>
      </Modal>

      <Modal
        open={Boolean(stockItem)}
        onClose={() => setStockItem(null)}
        title={stockItem ? `${stockItem.itemName} · stock by branch` : 'Stock'}
        description={stockItem ? `${stockItem.itemCode} · tracked in ${stockItem.baseUnit}` : undefined}
        size="lg"
      >
        {!stock || !stockTxns ? (
          <LoadingState />
        ) : (
          <div className="space-y-5">
            <div className="overflow-hidden rounded-lg border border-slate-200">
              <table className="w-full">
                <thead className="bg-slate-50">
                  <tr>
                    <th className="th">Branch</th>
                    <th className="th text-right">On hand</th>
                    <th className="th text-right">Minimum</th>
                    <th className="th text-right">Purchase ₹</th>
                    <th className="th text-right">Selling ₹</th>
                    <th className="th">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {stock.map((row) => (
                    <tr key={row.branchId}>
                      <td className="td font-semibold">
                        {row.branchName} <span className="text-[11px] font-normal text-slate-400">{row.branchCode}</span>
                      </td>
                      <td className="td text-right font-semibold">
                        {qty(row.currentStock)} {row.unit}
                      </td>
                      <td className="td text-right text-slate-500">{qty(row.minimumStock)}</td>
                      <td className="td text-right">{money(row.purchasePrice)}</td>
                      <td className="td text-right">{money(row.sellingPrice)}</td>
                      <td className="td">
                        {row.lowStock ? <Badge tone="red">Low</Badge> : <Badge tone="green">Healthy</Badge>}
                      </td>
                    </tr>
                  ))}
                  {stock.length === 0 && (
                    <tr>
                      <td colSpan={6} className="px-4 py-6 text-center text-xs text-slate-400">
                        No branch access or no stock records.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            <div>
              <h3 className="mb-2 text-xs font-bold tracking-wider text-slate-500 uppercase">Recent movements</h3>
              <div className="space-y-1.5">
                {stockTxns.map((txn) => (
                  <div key={txn.id} className="flex items-center justify-between gap-3 rounded-lg border border-slate-100 px-3 py-2">
                    <div className="min-w-0">
                      <p className="truncate text-xs font-semibold text-slate-700">
                        {titleCase(txn.transactionType)}
                        {txn.referenceNumber ? <span className="ml-1 font-normal text-slate-400">· {txn.referenceNumber}</span> : null}
                      </p>
                      <p className="text-[10px] text-slate-400">
                        {txn.branchName} · {formatDateTime(txn.transactionDate)}
                      </p>
                    </div>
                    <div className="text-right">
                      <p className={`text-xs font-bold ${Number(txn.quantityIn) > 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
                        {Number(txn.quantityIn) > 0 ? '+' : '−'}
                        {qty(Number(txn.quantityIn) > 0 ? txn.quantityIn : txn.quantityOut)} {txn.unit}
                      </p>
                      <p className="text-[10px] text-slate-400">bal {qty(txn.balanceAfter)}</p>
                    </div>
                  </div>
                ))}
                {stockTxns.length === 0 && <p className="py-3 text-center text-xs text-slate-400">No movements yet.</p>}
              </div>
            </div>
          </div>
        )}
      </Modal>
    </div>
  )
}
