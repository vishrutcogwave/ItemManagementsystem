import { AlertTriangle, X } from 'lucide-react'
import { useEffect, useRef, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react'
import { Button } from './Button'

interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  description?: string
  children: ReactNode
  footer?: ReactNode
  size?: 'sm' | 'md' | 'lg' | 'xl' | 'full'
  bodyClassName?: string
}

const sizes = {
  sm: 'max-w-md',
  md: 'max-w-xl',
  lg: 'max-w-3xl',
  xl: 'max-w-5xl',
  full: 'max-w-[95vw]',
}

export function Modal({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  size = 'md',
  bodyClassName = '',
}: ModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const onCloseRef = useRef(onClose)
  const triggerRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])

  // Remember the trigger while closed: children may autoFocus, which makes
  // document.activeElement unreliable by the time the open effect runs.
  useEffect(() => {
    if (open) return
    const record = () => {
      const active = document.activeElement
      if (!(active instanceof HTMLElement) || active.closest('[role="dialog"]')) return
      triggerRef.current = active
    }
    record()
    document.addEventListener('focusin', record)
    return () => document.removeEventListener('focusin', record)
  }, [open])

  useEffect(() => {
    if (!open) return
    const previousFocus = triggerRef.current
    const previousOverflow = document.body.style.overflow
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCloseRef.current()
    }
    document.addEventListener('keydown', handler)
    document.body.style.overflow = 'hidden'
    const focusTimer = window.setTimeout(() => dialogRef.current?.focus(), 0)
    return () => {
      window.clearTimeout(focusTimer)
      document.removeEventListener('keydown', handler)
      document.body.style.overflow = previousOverflow
      previousFocus?.focus()
    }
  }, [open])

  const trapTab = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Tab' || !dialogRef.current) return
    const focusable = Array.from(
      dialogRef.current.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((node) => node.offsetParent !== null)
    if (focusable.length === 0) {
      event.preventDefault()
      return
    }
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center p-0 sm:items-center sm:p-4">
      <div className="absolute inset-0 bg-slate-950/50 backdrop-blur-[2px]" onClick={onClose} />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        tabIndex={-1}
        onKeyDown={trapTab}
        className={`relative flex max-h-[92vh] w-full flex-col overflow-hidden rounded-t-2xl bg-white shadow-pop outline-none sm:rounded-2xl ${sizes[size]}`}
      >
        <div className="flex items-start justify-between gap-4 border-b border-slate-100 px-5 py-4">
          <div>
            <h2 className="text-base font-bold text-slate-900">{title}</h2>
            {description && <p className="mt-0.5 text-xs text-slate-500">{description}</p>}
          </div>
          <button
            onClick={onClose}
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            aria-label="Close"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className={`thin-scrollbar flex-1 overflow-y-auto px-4 py-4 sm:px-5 ${bodyClassName}`}>{children}</div>
        {footer && (
          <div className="flex flex-wrap items-center justify-end gap-2 border-t border-slate-100 bg-slate-50/60 px-4 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] sm:px-5 sm:pb-3">
            {footer}
          </div>
        )}
      </div>
    </div>
  )
}

interface ConfirmDialogProps {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  tone?: 'danger' | 'primary'
  loading?: boolean
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  tone = 'primary',
  loading,
}: ConfirmDialogProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      size="sm"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading}>
            {cancelLabel}
          </Button>
          <Button variant={tone === 'danger' ? 'danger' : 'primary'} onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="flex gap-3">
        <span
          className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full ${
            tone === 'danger' ? 'bg-rose-50 text-rose-600' : 'bg-brand-50 text-brand-600'
          }`}
        >
          <AlertTriangle className="h-5 w-5" />
        </span>
        <p className="pt-1 text-sm leading-relaxed text-slate-600">{message}</p>
      </div>
    </Modal>
  )
}
