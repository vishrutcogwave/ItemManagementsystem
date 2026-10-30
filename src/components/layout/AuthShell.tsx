import { BarChart3, Boxes, ShoppingCart, Store } from 'lucide-react'
import type { ReactNode } from 'react'

const highlights = [
  { icon: ShoppingCart, title: 'Fast counter billing', text: 'Search, add and bill items in seconds with live stock checks.' },
  { icon: Boxes, title: 'Stock you can trust', text: 'Purchases, transfers and adjustments update inventory atomically.' },
  { icon: BarChart3, title: 'Decisions with data', text: 'Daily sales, profit and low-stock insights across every branch.' },
]

export function AuthShell({
  children,
  title,
  subtitle,
}: {
  children: ReactNode
  title: string
  subtitle: string
}) {
  return (
    <div className="flex min-h-screen bg-white">
      <div className="relative hidden w-[46%] flex-col justify-between overflow-hidden bg-ink-900 p-10 text-white lg:flex xl:p-14">
        <div className="absolute -top-24 -right-24 h-80 w-80 rounded-full bg-brand-600/30 blur-3xl" />
        <div className="absolute bottom-0 -left-20 h-72 w-72 rounded-full bg-brand-500/20 blur-3xl" />

        <div className="relative flex items-center gap-3">
          <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-brand-600 shadow-lg shadow-brand-600/40">
            <Store className="h-6 w-6" />
          </span>
          <div>
            <p className="text-base font-bold tracking-tight">Item Management</p>
            <p className="text-[10px] font-semibold tracking-[0.22em] text-slate-400 uppercase">Multi-store retail OS</p>
          </div>
        </div>

        <div className="relative max-w-md">
          <p className="text-[11px] font-bold tracking-[0.2em] text-brand-400 uppercase">One store. Every branch. In sync.</p>
          <h2 className="mt-3 text-3xl leading-tight font-bold tracking-tight xl:text-4xl">
            Run purchases, stock and billing from a single workspace.
          </h2>
          <p className="mt-4 text-sm leading-relaxed text-slate-400">
            Built for multi-branch retail teams that need accurate inventory, controlled access and clear reporting
            without spreadsheets.
          </p>

          <div className="mt-8 space-y-5">
            {highlights.map((item) => (
              <div key={item.title} className="flex gap-3.5">
                <span className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white/[0.07] text-brand-400">
                  <item.icon className="h-4.5 w-4.5" />
                </span>
                <div>
                  <p className="text-sm font-semibold text-white">{item.title}</p>
                  <p className="mt-0.5 text-xs leading-relaxed text-slate-400">{item.text}</p>
                </div>
              </div>
            ))}
          </div>
        </div>

        <p className="relative text-[11px] text-slate-500">
          © {new Date().getFullYear()} Item Management System · Secure JWT access with branch-level isolation
        </p>
      </div>

      <div className="flex flex-1 items-center justify-center px-5 py-10 sm:px-10">
        <div className="w-full max-w-md">
          <div className="mb-8 flex items-center gap-2.5 lg:hidden">
            <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-600 text-white">
              <Store className="h-5 w-5" />
            </span>
            <p className="text-sm font-bold text-slate-900">Item Management System</p>
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">{title}</h1>
          <p className="mt-1.5 text-sm text-slate-500">{subtitle}</p>
          <div className="mt-7">{children}</div>
        </div>
      </div>
    </div>
  )
}
