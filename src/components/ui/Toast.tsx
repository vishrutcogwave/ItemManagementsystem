import { CheckCircle2, Info, X, XCircle } from 'lucide-react'
import { useUiStore, type ToastKind } from '../../store/ui'

const icons: Record<ToastKind, typeof CheckCircle2> = {
  success: CheckCircle2,
  error: XCircle,
  info: Info,
}

const toneClasses: Record<ToastKind, string> = {
  success: 'border-emerald-200 bg-white text-emerald-700',
  error: 'border-rose-200 bg-white text-rose-700',
  info: 'border-sky-200 bg-white text-sky-700',
}

export function Toaster() {
  const toasts = useUiStore((state) => state.toasts)
  const dismiss = useUiStore((state) => state.dismiss)

  if (toasts.length === 0) return null

  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-4 z-[60] flex flex-col items-center gap-2 px-4 sm:bottom-auto sm:top-4 sm:items-end">
      {toasts.map((toast) => {
        const Icon = icons[toast.kind]
        return (
          <div
            key={toast.id}
            className={`pointer-events-auto flex w-full max-w-sm items-start gap-2.5 rounded-xl border px-3.5 py-3 shadow-pop ${toneClasses[toast.kind]}`}
          >
            <Icon className="mt-0.5 h-4.5 w-4.5 shrink-0" />
            <p className="flex-1 text-[13px] font-medium text-slate-700">{toast.message}</p>
            <button onClick={() => dismiss(toast.id)} className="text-slate-400 hover:text-slate-600">
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        )
      })}
    </div>
  )
}
