import { CalendarDays, LogOut, Mail, Phone, RefreshCw, ShieldAlert } from 'lucide-react'
import { Button } from '../ui'
import { formatDate } from '../../lib/format'
import type { SubscriptionExpiredDetail } from '../../lib/api'
import type { SubscriptionSummary } from '../../types'

export const SUBSCRIPTION_EXPIRED_MESSAGE =
  'Your subscription has expired. Please contact the Platform Administrator to renew your subscription.'

interface Props {
  summary: SubscriptionSummary | null
  fallback?: SubscriptionExpiredDetail | null
  checking: boolean
  onRetry: () => void
  onSignOut: () => void
}

export function SubscriptionExpiredScreen({ summary, fallback, checking, onRetry, onSignOut }: Props) {
  const email = summary?.platformContactEmail || fallback?.contactEmail || ''
  const phone = summary?.platformContactPhone || fallback?.contactPhone || ''

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#f3f5f9] px-4 py-10">
      <div className="w-full max-w-xl overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-pop">
        <div className="flex items-start gap-4 border-b border-rose-100 bg-rose-50 px-6 py-5">
          <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-rose-100 text-rose-600">
            <ShieldAlert className="h-6 w-6" />
          </span>
          <div>
            <p className="text-[11px] font-bold tracking-wider text-rose-500 uppercase">Access suspended</p>
            <h1 className="mt-0.5 text-lg font-bold text-slate-900">Subscription expired</h1>
            {summary && (
              <p className="mt-0.5 text-xs text-slate-500">
                {summary.storeName} · {summary.storeCode}
              </p>
            )}
          </div>
        </div>

        <div className="space-y-5 px-6 py-6">
          <p className="text-sm leading-relaxed font-medium text-slate-800">{SUBSCRIPTION_EXPIRED_MESSAGE}</p>

          {summary && (
            <div className="grid grid-cols-2 gap-3 text-xs">
              <div className="rounded-lg bg-slate-50 p-3">
                <p className="flex items-center gap-1.5 font-semibold text-slate-500">
                  <CalendarDays className="h-3.5 w-3.5" /> Subscription ended
                </p>
                <p className="mt-1 text-sm font-bold text-slate-900">{formatDate(summary.endDate)}</p>
              </div>
              <div className="rounded-lg bg-slate-50 p-3">
                <p className="text-[11px] font-semibold text-slate-500">Contact the administrator</p>
                <p className="mt-1 text-sm font-bold text-slate-900">Subscription renewal</p>
              </div>
            </div>
          )}

          <div className="rounded-lg border border-slate-200 p-3.5">
            <p className="mb-2 text-[11px] font-bold tracking-wider text-slate-500 uppercase">
              Platform administrator
            </p>
            <div className="space-y-1.5 text-sm">
              <p className="flex items-center gap-2 text-slate-700">
                <Mail className="h-4 w-4 shrink-0 text-slate-400" />
                {email ? (
                  <a className="font-medium text-brand-700 hover:underline" href={`mailto:${email}`}>
                    {email}
                  </a>
                ) : (
                  <span className="text-slate-400">Email not configured</span>
                )}
              </p>
              <p className="flex items-center gap-2 text-slate-700">
                <Phone className="h-4 w-4 shrink-0 text-slate-400" />
                {phone ? (
                  <a className="font-medium text-brand-700 hover:underline" href={`tel:${phone}`}>
                    {phone}
                  </a>
                ) : (
                  <span className="text-slate-400">Phone not configured</span>
                )}
              </p>
            </div>
          </div>

          <p className="text-[11px] leading-relaxed text-slate-500">
            Store data, inventory, purchases, sales and reports remain unchanged. Access is restored as soon as the
            Platform Administrator extends the subscription end date.
          </p>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 bg-slate-50/60 px-6 py-4">
          <Button variant="ghost" icon={<LogOut className="h-4 w-4" />} onClick={onSignOut}>
            Sign out
          </Button>
          <Button variant="outline" icon={<RefreshCw className="h-4 w-4" />} onClick={onRetry} loading={checking}>
            Check again
          </Button>
        </div>
      </div>
    </div>
  )
}
