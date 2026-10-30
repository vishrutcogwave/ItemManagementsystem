import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { AUTH_STORAGE_KEY } from '../lib/api'
import type { AuthResponse } from '../types'

interface AuthState {
  token: string | null
  user: AuthResponse['user'] | null
  store: AuthResponse['store']
  branches: AuthResponse['branches']
  roles: string[]
  permissions: string[]
  allBranches: boolean
  platformAdmin: boolean
  selectedBranchId: number | null
  setSession: (auth: AuthResponse) => void
  setBranch: (branchId: number | null) => void
  logout: () => void
  hasPermission: (code: string) => boolean
  hasAnyPermission: (...codes: string[]) => boolean
}

const emptySession = {
  token: null,
  user: null,
  store: null,
  branches: [],
  roles: [],
  permissions: [],
  allBranches: false,
  platformAdmin: false,
  selectedBranchId: null,
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      ...emptySession,
      setSession: (auth) => {
        const selectedBranchId = auth.allBranches ? null : (auth.branches[0]?.id ?? null)
        set({
          token: auth.token,
          user: auth.user,
          store: auth.store,
          branches: auth.branches,
          roles: auth.roles,
          permissions: auth.permissions,
          allBranches: auth.allBranches,
          platformAdmin: auth.platformAdmin,
          selectedBranchId,
        })
      },
      setBranch: (branchId) => set({ selectedBranchId: branchId }),
      logout: () => set({ ...emptySession }),
      hasPermission: (code) => get().permissions.includes(code),
      hasAnyPermission: (...codes) => {
        const { permissions } = get()
        return codes.some((code) => permissions.includes(code))
      },
    }),
    {
      name: AUTH_STORAGE_KEY,
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({
        token: state.token,
        user: state.user,
        store: state.store,
        branches: state.branches,
        roles: state.roles,
        permissions: state.permissions,
        allBranches: state.allBranches,
        platformAdmin: state.platformAdmin,
        selectedBranchId: state.selectedBranchId,
      }),
    },
  ),
)
