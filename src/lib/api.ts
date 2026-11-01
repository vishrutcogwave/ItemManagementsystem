import axios, { AxiosError } from 'axios'
import type { ApiErrorPayload } from '../types'

export const AUTH_STORAGE_KEY = 'ims-auth'
export const UNAUTHORIZED_EVENT = 'ims:unauthorized'
export const PASSWORD_CHANGE_REQUIRED_EVENT = 'ims:password-change-required'
export const SUBSCRIPTION_EXPIRED_EVENT = 'ims:subscription-expired'

export interface SubscriptionExpiredDetail {
  message: string
  contactEmail: string
  contactPhone: string
}

export class ApiError extends Error {
  status: number
  code?: string
  fieldErrors: Record<string, string>

  constructor(message: string, status = 0, fieldErrors: Record<string, string> = {}, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
    this.code = code
  }
}

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
})

api.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiErrorPayload>) => {
    const status = error.response?.status ?? 0
    const payload = error.response?.data
    const url = String(error.config?.url ?? '')

    if (status === 401 && !url.includes('/auth/login') && !url.includes('/auth/register-store')
      && !url.includes('/auth/me') && !url.includes('/auth/logout')) {
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
    }

    if (status === 403 && payload?.code === 'PASSWORD_CHANGE_REQUIRED') {
      window.dispatchEvent(new CustomEvent(PASSWORD_CHANGE_REQUIRED_EVENT))
    }

    if (status === 403 && payload?.code === 'SUBSCRIPTION_EXPIRED') {
      const detail: SubscriptionExpiredDetail = {
        message: payload.message,
        contactEmail: payload.contactEmail ?? '',
        contactPhone: payload.contactPhone ?? '',
      }
      window.dispatchEvent(new CustomEvent(SUBSCRIPTION_EXPIRED_EVENT, { detail }))
    }

    let message = payload?.message
    if (!message) {
      message =
        status === 0
          ? 'Unable to reach the server. Please make sure the backend is running.'
          : status === 403
            ? 'You do not have permission to perform this action.'
            : 'Something went wrong. Please try again.'
    }

    return Promise.reject(
      new ApiError(message, status, payload?.fieldErrors ?? {}, payload?.code),
    )
  },
)

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return 'Something went wrong'
}

export function fieldErrorsOf(error: unknown): Record<string, string> {
  return error instanceof ApiError ? error.fieldErrors : {}
}
