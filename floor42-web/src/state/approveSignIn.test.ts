// @vitest-environment happy-dom
import { beforeAll, describe, expect, it, vi } from 'vitest'

vi.mock('../api/client', () => {
  class ApiError extends Error {
    needsLogin = false
    status = 403
    readonly code: string
    constructor(code: string) {
      super(code)
      this.code = code
    }
  }
  return {
    ApiError,
    api: { approve: () => Promise.reject(new ApiError('DELEGATION_NEEDS_SIGN_IN')) },
  }
})

import { setLang } from '../i18n'
import { en } from '../i18n/en'
import { approvePlan, office } from './office'

beforeAll(() => setLang('en'))

describe('approving a plan in IAM mode from a console-link session', () => {
  it('offers the Keycloak sign-in instead of a bare refusal', async () => {
    office.error = null
    office.toasts.splice(0)
    expect(await approvePlan('M-1', 3)).toBe(false)
    expect(office.error).toBeNull()
    expect(office.toasts).toHaveLength(1)
    expect(office.toasts[0]!.text).toBe(en['iam.signInToApprove'])
    expect(office.toasts[0]!.link).toEqual({ href: '/auth/keycloak', label: en['iam.login.keycloak'] })
  })
})
