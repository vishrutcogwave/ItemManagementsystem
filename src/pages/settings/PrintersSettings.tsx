import { KeyRound, Pencil, Plus, Power, Printer, RefreshCw, Trash2, Unplug, Wifi } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Badge,
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  EmptyState,
  ErrorState,
  Field,
  Input,
  Modal,
  Select,
  Spinner,
  StatusBadge,
  type Column,
} from '../../components/ui'
import { useApi } from '../../hooks/useApi'
import { api, errorMessage, fieldErrorsOf } from '../../lib/api'
import { formatDateTime } from '../../lib/format'
import { useAuthStore } from '../../store/auth'
import { notify, notifyError } from '../../store/ui'
import {
  detectPlatform,
  fetchBridgeStatus,
  isBridgeConfigured,
  loadBridgeSettings,
  listPrinters,
  saveBridgeKey,
  saveBridgePort,
  testBridgePrinter,
  type BridgeStatus,
} from '../../services/printerService'
import type {
  PaperWidth,
  PrintDevice,
  PrinterConfig,
  PrinterPurpose,
  StoreStatus,
} from '../../types'

interface FormState {
  printerName: string
  ipAddress: string
  port: string
  paperWidth: PaperWidth
  purpose: PrinterPurpose
  status: StoreStatus
}

const emptyForm: FormState = {
  printerName: '',
  ipAddress: '',
  port: '9100',
  paperWidth: 'MM80',
  purpose: 'RECEIPT',
  status: 'ACTIVE',
}

const purposeLabels: Record<PrinterPurpose, string> = {
  RECEIPT: 'Sale receipts',
  PURCHASE: 'Purchase invoices',
  REPORT: 'Reports',
  ALL: 'All documents',
}

export function PrintersSettings() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const branches = useAuthStore((state) => state.branches)
  const selectedBranchId = useAuthStore((state) => state.selectedBranchId)
  const canManage = hasPermission('PRINTER_MANAGE')
  const platform = detectPlatform()

  const [branchId, setBranchId] = useState<number | null>(selectedBranchId ?? branches[0]?.id ?? null)
  useEffect(() => {
    if (!branchId && branches[0]) setBranchId(branches[0].id)
  }, [branches, branchId])

  const printers = useApi<PrinterConfig[]>(
    () => (branchId ? listPrinters(branchId) : Promise.resolve([])),
    [branchId],
  )
  const devices = useApi<PrintDevice[]>(
    () =>
      canManage && branchId
        ? api
            .get<PrintDevice[]>('/printing/printers/devices', { params: { branchId } })
            .then((response) => response.data)
        : Promise.resolve([]),
    [branchId, canManage],
  )

  const [keyInput, setKeyInput] = useState('')
  const [portInput, setPortInput] = useState('')
  const [bridgeState, setBridgeState] = useState<'idle' | 'checking' | 'connected' | 'error'>('idle')
  const [bridgeStatus, setBridgeStatus] = useState<BridgeStatus | null>(null)
  const [bridgeMessage, setBridgeMessage] = useState<string | null>(null)
  const [testingDefault, setTestingDefault] = useState(false)

  useEffect(() => {
    if (platform === 'OTHER') return
    const settings = loadBridgeSettings()[platform]
    setKeyInput(settings.key)
    setPortInput(String(settings.port))
  }, [platform])

  const checkBridge = async () => {
    if (platform === 'OTHER') {
      setBridgeState('error')
      setBridgeMessage('Silent printing is supported on Windows and Android only.')
      return
    }
    if (!isBridgeConfigured(platform)) {
      setBridgeState('error')
      setBridgeMessage('Enter the bridge key from the print bridge app first.')
      return
    }
    setBridgeState('checking')
    setBridgeMessage(null)
    try {
      const status = await fetchBridgeStatus(platform)
      setBridgeStatus(status)
      setBridgeState('connected')
    } catch (err) {
      setBridgeStatus(null)
      setBridgeState('error')
      setBridgeMessage(errorMessage(err))
    }
  }

  useEffect(() => {
    if (platform !== 'OTHER' && isBridgeConfigured(platform)) {
      void checkBridge()
    }
    // Only probe once when this tab opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [platform])

  const saveBridge = () => {
    if (platform === 'OTHER') {
      notifyError('Silent printing is supported on Windows and Android only.')
      return
    }
    const port = Number(portInput)
    if (!keyInput.trim() || keyInput.trim().length < 8) {
      notifyError('Enter the bridge key shown in the print bridge app (at least 8 characters).')
      return
    }
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      notifyError('Bridge port must be between 1 and 65535.')
      return
    }
    saveBridgeKey(platform, keyInput)
    saveBridgePort(platform, port)
    notify('Bridge settings saved')
    void checkBridge()
  }

  const testDefaultPrinter = async () => {
    if (platform !== 'WINDOWS') return
    if (!isBridgeConfigured(platform)) {
      notifyError('Save the Windows bridge key first.')
      return
    }
    setTestingDefault(true)
    try {
      const result = await testBridgePrinter('WINDOWS', null)
      if (result.success) notify(result.message || 'Default printer is ready')
      else notifyError(result.message || 'The default printer is not available')
      void checkBridge()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setTestingDefault(false)
    }
  }

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<PrinterConfig | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState<PrinterConfig | null>(null)
  const [deletingBusy, setDeletingBusy] = useState(false)
  const [testingId, setTestingId] = useState<number | null>(null)
  const [pairing, setPairing] = useState<{ code: string; expiresAt: string } | null>(null)
  const [pairingBusy, setPairingBusy] = useState(false)
  const [revoking, setRevoking] = useState<PrintDevice | null>(null)
  const [revokingBusy, setRevokingBusy] = useState(false)

  const rows = useMemo(() => printers.data ?? [], [printers.data])

  const openCreate = () => {
    setEditing(null)
    setForm(emptyForm)
    setErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const openEdit = (printer: PrinterConfig) => {
    setEditing(printer)
    setForm({
      printerName: printer.printerName,
      ipAddress: printer.ipAddress,
      port: String(printer.port),
      paperWidth: printer.paperWidth,
      purpose: printer.purpose,
      status: printer.status,
    })
    setErrors({})
    setFormError(null)
    setModalOpen(true)
  }

  const save = async () => {
    if (!branchId) return
    const next: Record<string, string> = {}
    if (form.printerName.trim().length < 2) next.printerName = 'Printer name is required'
    if (!form.ipAddress.trim()) next.ipAddress = 'IP address or hostname is required'
    const port = Number(form.port)
    if (!Number.isInteger(port) || port < 1 || port > 65535) next.port = 'Port must be between 1 and 65535'
    if (Object.keys(next).length > 0) {
      setErrors(next)
      return
    }
    setSaving(true)
    setFormError(null)
    setErrors({})
    try {
      const payload = {
        printerName: form.printerName.trim(),
        ipAddress: form.ipAddress.trim(),
        port,
        paperWidth: form.paperWidth,
        purpose: form.purpose,
        status: form.status,
        branchId,
      }
      if (editing) {
        await api.put(`/printing/printers/${editing.id}`, payload)
        notify('Printer updated')
      } else {
        await api.post('/printing/printers', payload)
        notify('Printer added')
      }
      setModalOpen(false)
      printers.reload()
    } catch (err) {
      setFormError(errorMessage(err))
      setErrors(fieldErrorsOf(err))
    } finally {
      setSaving(false)
    }
  }

  const toggleStatus = async (printer: PrinterConfig) => {
    try {
      await api.put(`/printing/printers/${printer.id}/status`, {
        status: printer.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      })
      notify(printer.status === 'ACTIVE' ? 'Printer deactivated' : 'Printer activated')
      printers.reload()
    } catch (err) {
      notifyError(errorMessage(err))
    }
  }

  const removePrinter = async () => {
    if (!deleting) return
    setDeletingBusy(true)
    try {
      await api.delete(`/printing/printers/${deleting.id}`)
      notify('Printer removed')
      setDeleting(null)
      printers.reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setDeletingBusy(false)
    }
  }

  const testPrinter = async (printer: PrinterConfig) => {
    if (platform !== 'ANDROID') {
      notifyError('Network printer tests run from the Android print bridge. On Windows the bridge uses the default installed printer.')
      return
    }
    if (!isBridgeConfigured('ANDROID')) {
      notifyError('Save the Android bridge key before testing a printer.')
      return
    }
    setTestingId(printer.id)
    try {
      const result = await testBridgePrinter('ANDROID', {
        ipAddress: printer.ipAddress,
        port: printer.port,
        paperWidth: printer.paperWidth,
      })
      await api.put(`/printing/printers/${printer.id}/test-result`, {
        success: result.success,
        message: result.message,
      })
      if (result.success) notify(result.message || 'Printer connected')
      else notifyError(result.message || 'The printer could not be reached')
      printers.reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setTestingId(null)
    }
  }

  const generatePairingCode = async () => {
    if (!branchId) return
    setPairingBusy(true)
    try {
      const { data } = await api.post<{ code: string; expiresAt: string }>(
        '/printing/printers/pairing-code',
        null,
        { params: { branchId } },
      )
      setPairing({ code: data.code, expiresAt: data.expiresAt })
      devices.reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setPairingBusy(false)
    }
  }

  const revokeDevice = async () => {
    if (!revoking) return
    setRevokingBusy(true)
    try {
      await api.delete(`/printing/printers/devices/${revoking.id}`)
      notify('Print bridge revoked')
      setRevoking(null)
      devices.reload()
    } catch (err) {
      notifyError(errorMessage(err))
    } finally {
      setRevokingBusy(false)
    }
  }

  const columns: Column<PrinterConfig>[] = [
    {
      key: 'printer',
      header: 'Printer',
      render: (row) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
            <Printer className="h-4 w-4" />
          </span>
          <div className="min-w-0">
            <p className="truncate font-semibold text-slate-800">{row.printerName}</p>
            <p className="truncate text-[11px] text-slate-400">
              {row.ipAddress}:{row.port}
            </p>
          </div>
        </div>
      ),
    },
    {
      key: 'paper',
      header: 'Paper',
      render: (row) => <Badge tone="slate">{row.paperWidth === 'MM58' ? '58 mm' : '80 mm'}</Badge>,
    },
    {
      key: 'purpose',
      header: 'Purpose',
      render: (row) => <span className="text-xs text-slate-600">{purposeLabels[row.purpose]}</span>,
    },
    {
      key: 'test',
      header: 'Last test',
      render: (row) => (
        <div className="max-w-56 text-[11px]">
          {row.lastTestAt ? (
            <>
              <p className="text-slate-600">{row.lastTestMessage}</p>
              <p className="text-slate-400">{formatDateTime(row.lastTestAt)}</p>
            </>
          ) : (
            <span className="text-slate-400">Not tested</span>
          )}
        </div>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    ...(canManage
      ? [
          {
            key: 'actions',
            header: '',
            align: 'right' as const,
            render: (row: PrinterConfig) => (
              <div className="flex items-center justify-end gap-1">
                <button
                  onClick={() => void testPrinter(row)}
                  disabled={testingId === row.id}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700 disabled:opacity-50"
                  title="Test connection"
                >
                  {testingId === row.id ? <Spinner className="h-3.5 w-3.5" /> : <Wifi className="h-3.5 w-3.5" />}
                </button>
                <button
                  onClick={() => openEdit(row)}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
                  title="Edit"
                >
                  <Pencil className="h-3.5 w-3.5" />
                </button>
                <button
                  onClick={() => void toggleStatus(row)}
                  className={`flex h-7 w-7 items-center justify-center rounded-md transition hover:bg-slate-100 ${
                    row.status === 'ACTIVE' ? 'text-amber-500' : 'text-emerald-500'
                  }`}
                  title={row.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                >
                  <Power className="h-3.5 w-3.5" />
                </button>
                <button
                  onClick={() => setDeleting(row)}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-rose-50 hover:text-rose-600"
                  title="Remove"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            ),
          },
        ]
      : []),
  ]

  const deviceColumns: Column<PrintDevice>[] = [
    {
      key: 'device',
      header: 'Bridge device',
      render: (row) => (
        <div>
          <p className="font-semibold text-slate-800">{row.deviceName}</p>
          <p className="text-[11px] text-slate-400">Paired by {row.pairedBy || 'store user'}</p>
        </div>
      ),
    },
    { key: 'platform', header: 'Platform', render: (row) => <Badge tone="blue">{row.platform}</Badge> },
    {
      key: 'lastSeen',
      header: 'Last config pull',
      render: (row) => <span className="text-xs text-slate-500">{formatDateTime(row.lastSeenAt)}</span>,
    },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE'} /> },
    ...(canManage
      ? [
          {
            key: 'actions',
            header: '',
            align: 'right' as const,
            render: (row: PrintDevice) =>
              row.status === 'ACTIVE' ? (
                <Button size="sm" variant="outline" onClick={() => setRevoking(row)}>
                  Revoke
                </Button>
              ) : null,
          },
        ]
      : []),
  ]

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
          <div className="flex items-start gap-3">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-slate-900 text-white">
              <KeyRound className="h-4 w-4" />
            </span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h3 className="text-sm font-bold text-slate-900">Print bridge on this device</h3>
                <Badge tone={bridgeState === 'connected' ? 'green' : bridgeState === 'error' ? 'red' : 'slate'}>
                  {platform === 'WINDOWS' ? 'Windows PC' : platform === 'ANDROID' ? 'Android device' : 'This device'}
                </Badge>
                {bridgeState === 'connected' && <Badge tone="green">Connected</Badge>}
              </div>
              <p className="mt-1 text-xs text-slate-500">
                {platform === 'WINDOWS'
                  ? 'The Windows bridge prints silently on this PC’s default installed printer. No IP address or printer selection is needed in the website.'
                  : platform === 'ANDROID'
                    ? 'The Android bridge prints silently to the store printer configured below. Pair it once with a code, then keep the app running.'
                    : 'Silent thermal printing requires the Windows or Android print bridge. Receipts on this device keep using the existing browser print.'}
              </p>
              {bridgeState === 'connected' && bridgeStatus && (
                <p className="mt-2 flex items-center gap-1.5 text-xs font-medium text-emerald-600">
                  <Wifi className="h-3.5 w-3.5" />
                  Bridge {bridgeStatus.version}
                  {bridgeStatus.defaultPrinter ? ` · Default printer: ${bridgeStatus.defaultPrinter}` : ''}
                </p>
              )}
              {bridgeMessage && <p className="mt-2 text-xs font-medium text-rose-600">{bridgeMessage}</p>}
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {platform === 'WINDOWS' && (
              <Button size="sm" variant="outline" onClick={() => void testDefaultPrinter()} loading={testingDefault}>
                Test default printer
              </Button>
            )}
            <Button
              size="sm"
              variant="outline"
              icon={<RefreshCw className="h-3.5 w-3.5" />}
              onClick={() => void checkBridge()}
              loading={bridgeState === 'checking'}
            >
              Check status
            </Button>
            <Link
              to="/print-history"
              className="inline-flex h-8 items-center rounded-lg border border-slate-200 px-3 text-xs font-semibold text-slate-600 transition hover:bg-slate-50"
            >
              Print history
            </Link>
          </div>
        </div>

        {platform !== 'OTHER' && (
          <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-[minmax(0,1fr)_130px_auto]">
            <Field label="Bridge key" hint="Shown by the print bridge app on this device.">
              <Input
                type="password"
                autoComplete="off"
                value={keyInput}
                onChange={(event) => setKeyInput(event.target.value)}
                placeholder="Paste the bridge key"
              />
            </Field>
            <Field label="Bridge port">
              <Input
                inputMode="numeric"
                value={portInput}
                onChange={(event) => setPortInput(event.target.value.replace(/[^0-9]/g, ''))}
              />
            </Field>
            <div className="flex items-end">
              <Button className="w-full sm:w-auto" size="sm" onClick={saveBridge}>
                Save bridge
              </Button>
            </div>
          </div>
        )}
      </Card>

      <Card>
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-bold text-slate-900">Store printers</h3>
            <Badge tone="slate">{rows.length} configured</Badge>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Select
              className="w-full sm:w-52"
              value={branchId ?? ''}
              onChange={(event) => setBranchId(Number(event.target.value))}
              disabled={branches.length <= 1}
              aria-label="Branch"
            >
              {branches.map((branch) => (
                <option key={branch.id} value={branch.id}>
                  {branch.branchName}
                </option>
              ))}
            </Select>
            {canManage && (
              <Button size="sm" icon={<Plus className="h-4 w-4" />} onClick={openCreate} disabled={!branchId}>
                Add printer
              </Button>
            )}
          </div>
        </div>
        {printers.error ? (
          <ErrorState message={printers.error} onRetry={printers.reload} />
        ) : (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(row) => row.id}
            loading={printers.loading}
            empty={
              <EmptyState
                icon={<Printer className="h-6 w-6" />}
                title="No printers yet"
                message="Add the Wi-Fi/LAN receipt printer for this branch. Android devices print to it silently; Windows PCs use their own default printer."
              />
            }
          />
        )}
      </Card>

      {canManage && (
        <Card className="p-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <h3 className="text-sm font-bold text-slate-900">Pair an Android print bridge</h3>
              <p className="mt-1 max-w-2xl text-xs text-slate-500">
                Generate a one-time code, open ItemManagementPrintBridge on the Android phone or tablet and enter it there.
                The bridge then pulls this branch’s printer list securely and prints automatically.
              </p>
            </div>
            <Button
              size="sm"
              variant="outline"
              icon={<Unplug className="h-3.5 w-3.5" />}
              onClick={() => void generatePairingCode()}
              loading={pairingBusy}
              disabled={!branchId}
            >
              Generate code
            </Button>
          </div>

          {pairing && (
            <div className="mt-4 rounded-xl border border-brand-200 bg-brand-50/60 px-4 py-3">
              <p className="text-[11px] font-semibold tracking-wide text-brand-700 uppercase">One-time pairing code</p>
              <p className="mt-1 font-mono text-2xl font-bold tracking-[0.3em] text-slate-900">{pairing.code}</p>
              <p className="mt-1 text-xs text-slate-500">
                Expires {formatDateTime(pairing.expiresAt)}. The code works once and is never stored in plain text.
              </p>
            </div>
          )}

          <div className="mt-4 flex items-center justify-between">
            <h4 className="text-xs font-bold tracking-wide text-slate-500 uppercase">Paired bridges</h4>
            <Button size="sm" variant="outline" onClick={devices.reload}>
              Refresh
            </Button>
          </div>
          {devices.error ? (
            <p className="mt-3 text-xs font-medium text-rose-600">{devices.error}</p>
          ) : (
            <div className="mt-2">
              <DataTable
                columns={deviceColumns}
                rows={devices.data ?? []}
                rowKey={(row) => row.id}
                loading={devices.loading}
                empty={
                  <EmptyState
                    icon={<Unplug className="h-6 w-6" />}
                    title="No paired bridges"
                    message="Generate a pairing code and enter it in the Android print bridge app."
                  />
                }
              />
            </div>
          )}
        </Card>
      )}

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit printer' : 'Add printer'}
        footer={
          <>
            <Button variant="outline" onClick={() => setModalOpen(false)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={() => void save()} loading={saving}>
              {editing ? 'Save changes' : 'Add printer'}
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
          <Field label="Printer name" required error={errors.printerName}>
            <Input
              value={form.printerName}
              onChange={(event) => setForm({ ...form, printerName: event.target.value })}
              placeholder="Counter printer"
            />
          </Field>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-[minmax(0,1fr)_120px]">
            <Field label="IP address or hostname" required error={errors.ipAddress}>
              <Input
                value={form.ipAddress}
                onChange={(event) => setForm({ ...form, ipAddress: event.target.value })}
                placeholder="192.168.1.50"
                inputMode="url"
              />
            </Field>
            <Field label="Port" required error={errors.port} hint="Default 9100">
              <Input
                value={form.port}
                onChange={(event) => setForm({ ...form, port: event.target.value.replace(/[^0-9]/g, '') })}
                inputMode="numeric"
              />
            </Field>
          </div>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <Field label="Paper width">
              <Select
                value={form.paperWidth}
                onChange={(event) => setForm({ ...form, paperWidth: event.target.value as PaperWidth })}
              >
                <option value="MM58">58 mm (32 cols)</option>
                <option value="MM80">80 mm (48 cols)</option>
              </Select>
            </Field>
            <Field label="Purpose">
              <Select
                value={form.purpose}
                onChange={(event) => setForm({ ...form, purpose: event.target.value as PrinterPurpose })}
              >
                {Object.entries(purposeLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Status">
              <Select
                value={form.status}
                onChange={(event) => setForm({ ...form, status: event.target.value as StoreStatus })}
              >
                <option value="ACTIVE">Active</option>
                <option value="INACTIVE">Inactive</option>
              </Select>
            </Field>
          </div>
          <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
            Receipts print automatically on the first active printer whose purpose is Sale receipts or All documents for
            the selected branch. Inactive printers are never used.
          </p>
        </div>
      </Modal>

      <ConfirmDialog
        open={Boolean(deleting)}
        onClose={() => setDeleting(null)}
        onConfirm={() => void removePrinter()}
        title="Remove printer"
        message={`Remove ${deleting?.printerName ?? 'this printer'}? Print history stays intact and Android devices stop using it immediately.`}
        confirmLabel="Remove printer"
        tone="danger"
        loading={deletingBusy}
      />

      <ConfirmDialog
        open={Boolean(revoking)}
        onClose={() => setRevoking(null)}
        onConfirm={() => void revokeDevice()}
        title="Revoke print bridge"
        message={`Revoke ${revoking?.deviceName ?? 'this bridge'}? It will lose access to printer configuration until you pair it again.`}
        confirmLabel="Revoke"
        tone="danger"
        loading={revokingBusy}
      />
    </div>
  )
}
