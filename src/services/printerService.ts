import { api, errorMessage } from '../lib/api'
import { printSaleReceipt } from '../lib/documents'
import { buildSaleReceiptLines, type StoreInfo, type ThermalLine } from './thermalReceipt'
import type {
  PageResponse,
  PaperWidth,
  PrintJob,
  PrintJobRequest,
  PrintPlatform,
  PrinterConfig,
  Sale,
} from '../types'

export const BRIDGE_STORAGE_KEY = 'ims-print-bridge'
export const BRIDGE_KEYS_UPDATED_EVENT = 'ims:print-bridge-updated'
export const WINDOWS_BRIDGE_PORT = 17777
export const ANDROID_BRIDGE_PORT = 17778

export type DetectedPlatform = PrintPlatform | 'OTHER'

export interface BridgePlatformSettings {
  key: string
  port: number
}

export interface BridgeSettings {
  WINDOWS: BridgePlatformSettings
  ANDROID: BridgePlatformSettings
}

export interface BridgeStatus {
  ok: boolean
  platform: PrintPlatform
  version: string
  deviceName?: string
  defaultPrinter?: string | null
  paired?: boolean
}

export interface BridgeTestResult {
  success: boolean
  message: string
  defaultPrinter?: string | null
}

export type ThermalPrintStatus = 'PRINTED' | 'DUPLICATE' | 'NO_BRIDGE' | 'UNSUPPORTED' | 'FAILED'

export interface ThermalPrintOutcome {
  status: ThermalPrintStatus
  message?: string
  job?: PrintJob
}

export class BridgeError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'BridgeError'
  }
}

const defaultSettings: BridgeSettings = {
  WINDOWS: { key: '', port: WINDOWS_BRIDGE_PORT },
  ANDROID: { key: '', port: ANDROID_BRIDGE_PORT },
}

export function detectPlatform(): DetectedPlatform {
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent
  if (/Android/i.test(ua)) return 'ANDROID'
  if (/Windows/i.test(ua) || /Win32|Win64|Windows/i.test(navigator?.platform ?? '')) return 'WINDOWS'
  return 'OTHER'
}

export function loadBridgeSettings(): BridgeSettings {
  try {
    const raw = localStorage.getItem(BRIDGE_STORAGE_KEY)
    if (!raw) return { ...defaultSettings }
    const parsed = JSON.parse(raw) as Partial<BridgeSettings>
    return {
      WINDOWS: { ...defaultSettings.WINDOWS, ...(parsed.WINDOWS ?? {}) },
      ANDROID: { ...defaultSettings.ANDROID, ...(parsed.ANDROID ?? {}) },
    }
  } catch {
    return { ...defaultSettings }
  }
}

export function saveBridgeSettings(settings: BridgeSettings): void {
  localStorage.setItem(BRIDGE_STORAGE_KEY, JSON.stringify(settings))
  window.dispatchEvent(new CustomEvent(BRIDGE_KEYS_UPDATED_EVENT))
}

export function saveBridgeKey(platform: PrintPlatform, key: string): void {
  const settings = loadBridgeSettings()
  settings[platform] = { ...settings[platform], key: key.trim() }
  saveBridgeSettings(settings)
}

export function saveBridgePort(platform: PrintPlatform, port: number): void {
  const settings = loadBridgeSettings()
  settings[platform] = { ...settings[platform], port }
  saveBridgeSettings(settings)
}

export function isBridgeConfigured(platform: DetectedPlatform = detectPlatform()): boolean {
  if (platform === 'OTHER') return false
  return loadBridgeSettings()[platform].key.trim().length >= 8
}

export function activeBridgePlatform(): PrintPlatform | null {
  const platform = detectPlatform()
  return platform === 'OTHER' || !isBridgeConfigured(platform) ? null : platform
}

function bridgeUrl(platform: PrintPlatform): string {
  const settings = loadBridgeSettings()[platform]
  return `http://127.0.0.1:${settings.port || defaultSettings[platform].port}`
}

async function bridgeFetch<T>(
  platform: PrintPlatform,
  path: string,
  body?: unknown,
  timeoutMs = 12000,
): Promise<T> {
  const settings = loadBridgeSettings()[platform]
  const key = settings.key.trim()
  if (!key) {
    throw new BridgeError(
      platform === 'WINDOWS'
        ? 'The Windows print bridge is not set up on this PC.'
        : 'The Android print bridge is not set up on this device.',
    )
  }
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(`${bridgeUrl(platform)}${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Bridge-Key': key,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
    const text = await response.text()
    let payload: { message?: string } & Record<string, unknown> = {}
    try {
      payload = text ? (JSON.parse(text) as typeof payload) : {}
    } catch {
      payload = {}
    }
    if (!response.ok) {
      if (response.status === 401) {
        throw new BridgeError('The print bridge key is invalid. Update it in Settings → Printers.')
      }
      throw new BridgeError(payload.message || `The print bridge returned an error (${response.status}).`)
    }
    return payload as T
  } catch (error) {
    if (error instanceof BridgeError) throw error
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new BridgeError('The print bridge did not respond in time. Check that it is running on this device.')
    }
    throw new BridgeError(
      platform === 'WINDOWS'
        ? `Cannot reach the Windows print bridge on 127.0.0.1:${settings.port}. Make sure the bridge is running.`
        : `Cannot reach the Android print bridge on 127.0.0.1:${settings.port}. Open the print bridge app.`,
    )
  } finally {
    window.clearTimeout(timer)
  }
}

export async function fetchBridgeStatus(platform: PrintPlatform = detectPlatform() as PrintPlatform): Promise<BridgeStatus> {
  return bridgeFetch<BridgeStatus>(platform, '/api/bridge/status', undefined, 5000)
}

export async function testBridgePrinter(
  platform: PrintPlatform,
  printer?: { ipAddress: string; port: number; paperWidth: PaperWidth } | null,
): Promise<BridgeTestResult> {
  return bridgeFetch<BridgeTestResult>(platform, '/api/bridge/test', { printer: printer ?? null }, 15000)
}

export interface ThermalJobPayload {
  jobId: number
  jobKey: string
  platform: PrintPlatform
  paperWidth: PaperWidth
  printer: { ipAddress: string; port: number } | null
  document: {
    type: 'SALE_RECEIPT'
    saleNumber: string
    lines: ThermalLine[]
  }
  requestedAt: string
}

async function sendJobToBridge(job: PrintJob, sale: Sale, store: StoreInfo, platform: PrintPlatform): Promise<void> {
  const paperWidth: PaperWidth = job.paperWidth ?? (platform === 'ANDROID' ? 'MM58' : 'MM80')
  if (platform === 'ANDROID' && (!job.printerIpAddress || !job.printerPort)) {
    throw new BridgeError(
      'No receipt printer is configured for this branch. Ask the store owner to add one in Settings → Printers.',
    )
  }
  const payload: ThermalJobPayload = {
    jobId: job.id,
    jobKey: job.jobKey,
    platform,
    paperWidth,
    printer:
      platform === 'ANDROID' && job.printerIpAddress && job.printerPort
        ? { ipAddress: job.printerIpAddress, port: job.printerPort }
        : null,
    document: {
      type: 'SALE_RECEIPT',
      saleNumber: sale.salesNumber,
      lines: buildSaleReceiptLines(sale, store, paperWidth),
    },
    requestedAt: new Date().toISOString(),
  }
  const result = await bridgeFetch<{ success: boolean; message?: string }>(
    platform,
    '/api/bridge/print',
    payload,
    20000,
  )
  if (!result.success) {
    throw new BridgeError(result.message || 'The print bridge could not print the receipt.')
  }
}

async function updateJobStatus(jobId: number, status: 'PRINTED' | 'FAILED', message?: string): Promise<void> {
  try {
    await api.put(`/printing/jobs/${jobId}/status`, {
      status,
      errorMessage: status === 'FAILED' ? message : undefined,
    })
  } catch {
    // The receipt result must not be hidden by a history update failure.
  }
}

function noBridgeMessage(platform: DetectedPlatform): string {
  if (platform === 'WINDOWS') {
    return 'The Windows print bridge is not connected. Install ItemManagementPrintBridge on this PC, open it and paste its bridge key in Settings → Printers.'
  }
  if (platform === 'ANDROID') {
    return 'The Android print bridge is not connected. Install and pair ItemManagementPrintBridge on this device, then paste its bridge key in Settings → Printers.'
  }
  return 'Silent thermal printing is available on Windows and Android only.'
}

export interface PrintSaleOptions {
  store: StoreInfo
  trigger?: 'AUTO' | 'MANUAL'
  printerConfigId?: number | null
  platform?: DetectedPlatform
}

/**
 * Creates the print job on the server first, so nothing can be printed
 * before the transaction is saved, then hands the job to the local bridge.
 * Automatic jobs are idempotent on the server: a duplicate sale response
 * or a double click can never send the same receipt to the printer twice.
 */
export async function printSaleThermal(sale: Sale, options: PrintSaleOptions): Promise<ThermalPrintOutcome> {
  const platform = options.platform ?? detectPlatform()
  const trigger = options.trigger ?? 'MANUAL'
  if (platform === 'OTHER') {
    return { status: 'UNSUPPORTED', message: noBridgeMessage(platform) }
  }
  if (!isBridgeConfigured(platform)) {
    return { status: 'NO_BRIDGE', message: noBridgeMessage(platform) }
  }

  let job: PrintJob
  try {
    const request: PrintJobRequest = {
      documentType: 'SALE_RECEIPT',
      documentId: sale.id,
      channel: platform,
      trigger,
      printerConfigId: options.printerConfigId ?? null,
    }
    const { data } = await api.post<PrintJob>('/printing/jobs', request)
    job = data
  } catch (error) {
    return { status: 'FAILED', message: errorMessage(error) }
  }

  if (job.duplicate) {
    return { status: 'DUPLICATE', job }
  }

  try {
    await sendJobToBridge(job, sale, options.store, platform)
    await updateJobStatus(job.id, 'PRINTED')
    return { status: 'PRINTED', job }
  } catch (error) {
    const message = errorMessage(error)
    await updateJobStatus(job.id, 'FAILED', message)
    return { status: 'FAILED', message, job }
  }
}

export async function autoPrintSale(sale: Sale, store: StoreInfo): Promise<ThermalPrintOutcome | null> {
  const platform = detectPlatform()
  if (platform === 'OTHER' || !isBridgeConfigured(platform)) return null
  return printSaleThermal(sale, { store, trigger: 'AUTO', platform })
}

export async function manualPrintSale(sale: Sale, store: StoreInfo): Promise<ThermalPrintOutcome> {
  return printSaleThermal(sale, { store, trigger: 'MANUAL' })
}

/**
 * Receipt button behaviour for every device. Windows and Android always use
 * the silent bridge (never a browser print dialog); other platforms keep the
 * existing browser receipt so no current functionality is lost.
 */
export async function printSaleReceiptForDevice(
  sale: Sale,
  store: StoreInfo,
): Promise<ThermalPrintOutcome | null> {
  if (detectPlatform() === 'OTHER') {
    printSaleReceipt(sale, store)
    return null
  }
  return manualPrintSale(sale, store)
}

export async function listPrintJobs(params: {
  page?: number
  size?: number
  status?: string
  branchId?: number | null
}): Promise<PageResponse<PrintJob>> {
  const { data } = await api.get<PageResponse<PrintJob>>('/printing/jobs', { params })
  return data
}

export async function getPrintJob(id: number): Promise<PrintJob> {
  const { data } = await api.get<PrintJob>(`/printing/jobs/${id}`)
  return data
}

export async function reprintPrintJob(job: PrintJob, sale: Sale, store: StoreInfo): Promise<ThermalPrintOutcome> {
  const platform = detectPlatform()
  if (platform === 'OTHER') return { status: 'UNSUPPORTED', message: noBridgeMessage(platform) }
  if (!isBridgeConfigured(platform)) return { status: 'NO_BRIDGE', message: noBridgeMessage(platform) }

  let reprint: PrintJob
  try {
    const { data } = await api.post<PrintJob>(`/printing/jobs/${job.id}/reprint`)
    reprint = data
  } catch (error) {
    return { status: 'FAILED', message: errorMessage(error) }
  }
  try {
    await sendJobToBridge(reprint, sale, store, platform)
    await updateJobStatus(reprint.id, 'PRINTED')
    return { status: 'PRINTED', job: reprint }
  } catch (error) {
    const message = errorMessage(error)
    await updateJobStatus(reprint.id, 'FAILED', message)
    return { status: 'FAILED', message, job: reprint }
  }
}

export async function listPrinters(branchId: number): Promise<PrinterConfig[]> {
  const { data } = await api.get<PrinterConfig[]>('/printing/printers', { params: { branchId } })
  return data
}
