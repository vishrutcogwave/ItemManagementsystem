import { Pencil, Plus, Power, Tags, Trash2 } from 'lucide-react'
import { useState } from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
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
  StatusBadge,
  Textarea,
  type Column,
} from '../components/ui'
import { useApi } from '../hooks/useApi'
import { useDebounce } from '../hooks/useDebounce'
import { api, errorMessage, fieldErrorsOf } from '../lib/api'
import { formatDate } from '../lib/format'
import { useAuthStore } from '../store/auth'
import { notify, notifyError } from '../store/ui'
import type { Category, PageResponse } from '../types'

interface FormState {
  categoryName: string
  description: string
  status: 'ACTIVE' | 'INACTIVE'
}

const emptyForm: FormState = { categoryName: '', description: '', status: 'ACTIVE' }

export function CategoriesPage() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const canManage = hasPermission('CATEGORY_MANAGE')

  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const debouncedSearch = useDebounce(search)

  const { data, loading, error, reload } = useApi<PageResponse<Category>>(
    () =>
      api
        .get('/categories', {
          params: {
            search: debouncedSearch || undefined,
            status: status || undefined,
            page,
            size,
          },
        })
        .then((response) => response.data),
    [debouncedSearch, status, page, size],
  )

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Category | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm)
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState<Category | null>(null)
  const [deleteLoading, setDeleteLoading] = useState(false)

  const openCreate = () => {
    setEditing(null)
    setForm(emptyForm)
    setFormErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const openEdit = (category: Category) => {
    setEditing(category)
    setForm({
      categoryName: category.categoryName,
      description: category.description ?? '',
      status: category.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    })
    setFormErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const save = async () => {
    if (!form.categoryName.trim()) {
      setFormErrors({ categoryName: 'Category name is required' })
      return
    }
    setSaving(true)
    setFormError(null)
    setFormErrors({})
    try {
      const payload = {
        categoryName: form.categoryName.trim(),
        description: form.description.trim() || undefined,
        status: form.status,
      }
      if (editing) {
        await api.put(`/categories/${editing.id}`, payload)
        notify('Category updated')
      } else {
        await api.post('/categories', payload)
        notify('Category created')
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

  const toggleStatus = async (category: Category) => {
    try {
      await api.put(`/categories/${category.id}/status`, {
        status: category.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      })
      notify(category.status === 'ACTIVE' ? 'Category deactivated' : 'Category activated')
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    }
  }

  const confirmDelete = async () => {
    if (!deleting) return
    setDeleteLoading(true)
    try {
      await api.delete(`/categories/${deleting.id}`)
      notify('Category deleted')
      setDeleting(null)
      reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setDeleteLoading(false)
    }
  }

  const columns: Column<Category>[] = [
    {
      key: 'name',
      header: 'Category',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
            <Tags className="h-4 w-4" />
          </span>
          <div>
            <p className="font-semibold text-slate-800">{row.categoryName}</p>
            <p className="text-[11px] text-slate-400">{row.description || 'No description'}</p>
          </div>
        </div>
      ),
    },
    { key: 'items', header: 'Items', align: 'right', render: (row) => <span className="font-semibold">{row.itemCount}</span> },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    { key: 'created', header: 'Created', render: (row) => <span className="text-slate-500">{formatDate(row.createdAt)}</span> },
    ...(canManage
      ? [
          {
            key: 'actions',
            header: '',
            align: 'right' as const,
            render: (row: Category) => (
              <div className="flex items-center justify-end gap-1">
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
                    row.status === 'ACTIVE' ? 'text-amber-500 hover:text-amber-600' : 'text-emerald-500'
                  }`}
                  title={row.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                >
                  <Power className="h-3.5 w-3.5" />
                </button>
                <button
                  onClick={() => setDeleting(row)}
                  disabled={row.itemCount > 0}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-30"
                  title={row.itemCount > 0 ? 'Remove items before deleting' : 'Delete'}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            ),
          },
        ]
      : []),
  ]

  return (
    <div>
      <PageHeader
        title="Categories"
        subtitle="Group your catalogue so teams can find and report on items quickly."
        actions={
          canManage ? (
            <Button icon={<Plus className="h-4 w-4" />} onClick={openCreate}>
              Add category
            </Button>
          ) : undefined
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center">
          <SearchInput
            className="sm:w-80"
            placeholder="Search categories…"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value)
              setPage(0)
            }}
          />
          <Select
            className="sm:w-44"
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
                  icon={<Tags className="h-6 w-6" />}
                  title="No categories yet"
                  message="Create categories like Dairy, Grocery or Beverages to organise your items."
                  action={canManage ? <Button size="sm" onClick={openCreate}>Add category</Button> : undefined}
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
                itemLabel="categories"
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit category' : 'Add category'}
        description="Categories are shared across all branches in this store."
        footer={
          <>
            <Button variant="outline" onClick={() => setModalOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              {editing ? 'Save changes' : 'Create category'}
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
          <Field label="Category name" required error={formErrors.categoryName}>
            <Input
              value={form.categoryName}
              onChange={(event) => setForm({ ...form, categoryName: event.target.value })}
              placeholder="e.g. Dairy & Frozen"
              autoFocus
            />
          </Field>
          <Field label="Description" error={formErrors.description}>
            <Textarea
              value={form.description}
              onChange={(event) => setForm({ ...form, description: event.target.value })}
              placeholder="Optional notes about this category"
              rows={3}
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
      </Modal>

      <ConfirmDialog
        open={Boolean(deleting)}
        onClose={() => setDeleting(null)}
        onConfirm={confirmDelete}
        title="Delete category"
        message={`Delete "${deleting?.categoryName ?? ''}"? This cannot be undone. Categories with items cannot be deleted.`}
        confirmLabel="Delete category"
        tone="danger"
        loading={deleteLoading}
      />
    </div>
  )
}
