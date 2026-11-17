import { ArrowLeft, Mail, Phone, ShieldCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { AuthShell } from '../components/layout/AuthShell'
import { api, errorMessage } from '../lib/api'
import type { PlatformContact } from '../types'

export function RegisterPage() {
  const [contact, setContact] = useState<PlatformContact | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    api
      .get<PlatformContact>('/platform/public-contact')
      .then(({ data }) => {
        if (active) setContact(data)
      })
      .catch((err) => {
        if (active) setError(errorMessage(err))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  const hasContact = Boolean(contact?.contactEmail || contact?.contactPhone)

  return (
    <AuthShell
      title="Get started with our IMS"
      subtitle="Store accounts are created by our platform administrator."
    >
      <div className="space-y-4">
        <div className="rounded-xl border border-brand-100 bg-brand-50 px-4 py-4">
          <div className="flex gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white text-brand-600 shadow-sm">
              <ShieldCheck className="h-5 w-5" />
            </span>
            <p className="pt-1 text-sm leading-relaxed font-medium text-slate-700">
              Interested in using our IMS software? Contact our administrator to create your business account.
            </p>
          </div>
        </div>

        <div className="rounded-xl border border-slate-200">
          <p className="border-b border-slate-100 px-4 py-3 text-[11px] font-bold tracking-wider text-slate-500 uppercase">
            Administrator contact
          </p>
          <div className="space-y-2 p-4">
            {loading && <p className="text-sm text-slate-500">Loading contact details…</p>}
            {!loading && error && (
              <p className="text-sm text-slate-500">
                Contact details are temporarily unavailable. Please try again later.
              </p>
            )}
            {!loading && !error && !hasContact && (
              <p className="text-sm text-slate-500">Contact details are not configured yet.</p>
            )}
            {!loading && !error && contact?.contactEmail && (
              <a
                href={`mailto:${contact.contactEmail}`}
                className="flex items-center gap-3 rounded-lg border border-slate-200 px-3.5 py-3 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
              >
                <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
                  <Mail className="h-4 w-4" />
                </span>
                <span className="min-w-0 break-all">{contact.contactEmail}</span>
              </a>
            )}
            {!loading && !error && contact?.contactPhone && (
              <a
                href={`tel:${contact.contactPhone}`}
                className="flex items-center gap-3 rounded-lg border border-slate-200 px-3.5 py-3 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
              >
                <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
                  <Phone className="h-4 w-4" />
                </span>
                <span>{contact.contactPhone}</span>
              </a>
            )}
          </div>
        </div>

        <p className="text-center text-xs text-slate-500">
          Already registered?{' '}
          <Link to="/login" className="link inline-flex items-center gap-1">
            <ArrowLeft className="h-3 w-3" /> Back to sign in
          </Link>
        </p>
      </div>
    </AuthShell>
  )
}
