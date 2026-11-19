export type Session = {
  username: string | null
  csrfToken: string
  csrfHeader: string
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly field?: string,
    readonly details?: unknown,
  ) {
    super(message)
  }
}

export async function request<T>(path: string, data?: unknown, session?: Session, signal?: AbortSignal,
  commandKey?: string): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  let body: string | URLSearchParams | FormData | undefined
  if (session) {
    headers.set(session.csrfHeader, session.csrfToken)
    if (commandKey) headers.set('Idempotency-Key', commandKey)
    if (data instanceof URLSearchParams || data instanceof FormData) {
      body = data
    } else if (data !== undefined) {
      headers.set('Content-Type', 'application/json')
      body = JSON.stringify(data)
    }
  }
  const response = await fetch(`/api${path}`, { headers, method: session ? 'POST' : 'GET', body, signal })
  if (!response.ok) {
    if (response.status === 401 && /^\/(orders|order-imports|documents|tasks)(?:[/?]|$)/.test(path)) {
      window.dispatchEvent(new Event('labreview:session-expired'))
    }
    const error = await response.json().catch(() => null)
    throw new ApiError(response.status, error?.code ?? 'REQUEST_FAILED',
      error?.message ?? `请求失败（${response.status}）`, error?.field, error?.details)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export function errorText(error: unknown) {
  if (error instanceof ApiError) return error.message
  return '连接失败，请检查本地服务后重试'
}

export function localDate() {
  const date = new Date()
  const pad = (number: number) => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export const MAX_QUANTITY = 2_147_483_647
