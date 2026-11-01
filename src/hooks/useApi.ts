import { useCallback, useEffect, useRef, useState } from 'react'
import { errorMessage } from '../lib/api'

interface UseApiResult<T> {
  data: T | null
  loading: boolean
  error: string | null
  reload: () => void
}

export function useApi<T>(fetcher: () => Promise<T>, deps: unknown[]): UseApiResult<T> {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)

  const fetcherRef = useRef(fetcher)
  useEffect(() => {
    fetcherRef.current = fetcher
  })

  const dependencyKey = deps.map((dep) => String(dep)).join('|')

  useEffect(() => {
    let active = true
    const run = async () => {
      await Promise.resolve()
      if (!active) return
      setLoading(true)
      setError(null)
      try {
        const result = await fetcherRef.current()
        if (active) setData(result)
      } catch (err) {
        if (active) setError(errorMessage(err))
      } finally {
        if (active) setLoading(false)
      }
    }
    void run()
    return () => {
      active = false
    }
  }, [dependencyKey, nonce])

  const reload = useCallback(() => setNonce((value) => value + 1), [])

  return { data, loading, error, reload }
}
