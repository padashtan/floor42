import type { Problem } from './types'

/** The daemon refused a request. `code` is the stable business rule code when the refusal comes from the domain. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }

  get needsLogin(): boolean {
    return this.status === 401
  }
}

/**
 * Sent on every request: the daemon refuses a change the browser's cookie authenticates without it (ADR-0044). A page
 * of another origin cannot set it without a CORS preflight, which the daemon never grants.
 */
export const REQUEST_HEADER = { 'X-Floor42-Request': '1' } as const

/** A JSON call to the daemon: its answer, or an ApiError carrying the refusal's code. */
export async function request<T>(method: string, path: string, body?: unknown, extra: Record<string, string> = {}): Promise<T> {
  // The session cookie (HttpOnly, SameSite=Strict) authenticates the browser; it is never read by this code.
  const response = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers:
      body === undefined ? { ...REQUEST_HEADER, ...extra } : { ...REQUEST_HEADER, 'Content-Type': 'application/json', ...extra },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = response.statusText || code
    try {
      const problem = (await response.json()) as Partial<Problem>
      code = problem.code ?? code
      message = problem.message ?? message
    } catch {
      message = (await response.text().catch(() => '')) || message
    }
    throw new ApiError(response.status, code, message)
  }
  // 204: the thing asked for does not exist yet, which is not a failure (a context reading before the first one)
  if (response.status === 204) return null as T
  return (await response.json()) as T
}

/**
 * IAM mode: approving a plan answers 202 {redirect}, the path of the password step-up. The page goes there; the plan is
 * approved when Keycloak has confirmed the password. Any other answer is returned as it is.
 */
export function followStepUp<T>(answer: T | { redirect: string }): T {
  const redirect = (answer as { redirect?: unknown } | null)?.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/auth/')) window.location.assign(redirect)
  return answer as T
}

/** A request whose success answers no body (204). */
export async function noContent(method: string, path: string): Promise<void> {
  const response = await fetch(path, { method, credentials: 'same-origin', headers: { ...REQUEST_HEADER } })
  if (!response.ok) {
    const problem: { code?: string; message?: string } = await response.json().catch(() => ({}))
    throw new ApiError(response.status, problem.code ?? `HTTP_${response.status}`, problem.message ?? response.statusText)
  }
}
