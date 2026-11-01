import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { AUTH_STORAGE_KEY } from '../lib/api'
import type { AuthResponse } from '../types'

/**
 * The JWT lives only in the HttpOnly session cookie, so it is never written to
 * localStorage or held in JavaScript state. Only the profile needed to render
 * the shell is persisted; the server re-validates the cookie on every request.
 */
interface AuthState {
  user: AuthResponse['user'] | null
  store: AuthResponse['store']
  branches: AuthResponse['branches']
  roles: string[]
  permissions: string[]
  allBranches: boolean
  platformAdmin: boolean
  mustChangePassword: boolean
  selectedBranchId: number | null
  sessionReady: boolean
  setSession: (auth: AuthResponse) => void
  requirePasswordChange: () => void
  markSessionReady: () => void
  setBranch: (branchId: number | null) => void
  logout: () => void
  hasPermission: (code: string) => boolean
  hasAnyPermission: (...codes: string[]) => boolean
}

const emptySession = {
  user: null,
  store: null,
  branches: [],
  roles: [],
  permissions: [],
  allBranches: false,
  platformAdmin: false,
  mustChangePassword: false,
  selectedBranchId: null,
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      ...emptySession,
      sessionReady: false,
      setSession: (auth) => {
        const selectedBranchId = auth.allBranches ? null : (auth.branches[0]?.id ?? null)
        set({
          user: auth.user,
          store: auth.store,
          branches: auth.branches,
          roles: auth.roles,
          permissions: auth.permissions,
          allBranches: auth.allBranches,
          platformAdmin: auth.platformAdmin,
          mustChangePassword: Boolean(auth.mustChangePassword),
          selectedBranchId,
          sessionReady: true,
        })
      },
      requirePasswordChange: () => set({ mustChangePassword: true }),
      markSessionReady: () => set({ sessionReady: true }),
      setBranch: (branchId) => set({ selectedBranchId: branchId }),
      logout: () => set({ ...emptySession, sessionReady: true }),
      hasPermission: (code) => get().permissions.includes(code),
      hasAnyPermission: (...codes) => {
        const { permissions } = get()
        return codes.some((code) => permissions.includes(code))
      },
    }),
    {
      name: AUTH_STORAGE_KEY,
      storage: createJSONStorage(() => localStorage),
      version: 2,
      // Version 1 stored the bearer token in localStorage. Removing it on
      // upgrade ensures no credential survives in JavaScript-accessible storage.
      migrate: (persisted) => {
        if (persisted && typeof persisted === 'object') {
          const state = { ...(persisted as Record<string, unknown>) }
          delete state.token
          return state as unknown as AuthState
        }
        return persisted as AuthState
      },
      partialize: (state) => ({
        user: state.user,
        store: state.store,
        branches: state.branches,
        roles: state.roles,
        permissions: state.permissions,
        allBranches: state.allBranches,
        platformAdmin: state.platformAdmin,
        mustChangePassword: state.mustChangePassword,
        selectedBranchId: state.selectedBranchId,
      }),
    },
  ),
)
