const currency = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

const compactCurrency = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  notation: 'compact',
  maximumFractionDigits: 1,
})

const number = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 3 })

export function money(value: string | number | null | undefined): string {
  return currency.format(Number(value ?? 0))
}

export function moneyCompact(value: string | number | null | undefined): string {
  const amount = Number(value ?? 0)
  return Math.abs(amount) >= 100000 ? compactCurrency.format(amount) : currency.format(amount)
}

export function qty(value: string | number | null | undefined): string {
  return number.format(Number(value ?? 0))
}

export function decimal(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return ''
  const parsed = Number(value)
  return Number.isFinite(parsed) ? String(parsed) : ''
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(`${value}`.length <= 10 ? `${value}T00:00:00` : value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return `${date.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })}, ${date.toLocaleTimeString(
    'en-IN',
    { hour: '2-digit', minute: '2-digit' },
  )}`
}

export function today(): string {
  const now = new Date()
  const offset = now.getTimezoneOffset()
  return new Date(now.getTime() - offset * 60000).toISOString().slice(0, 10)
}

export function daysAgo(days: number): string {
  const now = new Date()
  now.setDate(now.getDate() - days)
  const offset = now.getTimezoneOffset()
  return new Date(now.getTime() - offset * 60000).toISOString().slice(0, 10)
}

export function monthStart(): string {
  const now = new Date()
  const offset = now.getTimezoneOffset()
  return new Date(now.getFullYear(), now.getMonth(), 1, 0, 0, 0 - offset).toISOString().slice(0, 10)
}

export function titleCase(value: string | null | undefined): string {
  if (!value) return ''
  return value
    .toLowerCase()
    .split(/[_\s]+/)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

export function initials(name: string | null | undefined): string {
  if (!name) return '?'
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}
