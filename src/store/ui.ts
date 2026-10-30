import { create } from 'zustand'

export type ToastKind = 'success' | 'error' | 'info'

export interface ToastItem {
  id: number
  kind: ToastKind
  message: string
}

interface UiState {
  toasts: ToastItem[]
  notify: (message: string, kind?: ToastKind) => void
  notifyError: (message: string) => void
  dismiss: (id: number) => void
}

let counter = 0

export const useUiStore = create<UiState>()((set) => ({
  toasts: [],
  notify: (message, kind = 'success') => {
    const id = ++counter
    set((state) => ({ toasts: [...state.toasts, { id, kind, message }].slice(-4) }))
    window.setTimeout(() => {
      set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) }))
    }, 4200)
  },
  notifyError: (message) => {
    const id = ++counter
    set((state) => ({ toasts: [...state.toasts, { id, kind: 'error' as ToastKind, message }].slice(-4) }))
    window.setTimeout(() => {
      set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) }))
    }, 6000)
  },
  dismiss: (id) => set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) })),
}))

export const notify = (message: string, kind?: ToastKind) => useUiStore.getState().notify(message, kind)
export const notifyError = (message: string) => useUiStore.getState().notifyError(message)
