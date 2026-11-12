import { api } from '../lib/api'
import type { UnitMaster } from '../types'
import { useApi } from './useApi'

export function useUnits() {
  const { data, loading, error, reload } = useApi<UnitMaster[]>(
    () => api.get('/units/options').then((response) => response.data),
    [],
  )
  return { units: data ?? [], loading, error, reload }
}
