import { afterEach, describe, expect, it, vi } from 'vitest'
import { followStepUp } from './request'

describe('followStepUp (IAM mode: the approval answers 202 with the path of the password step-up)', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('goes to the Keycloak step-up path of the daemon', () => {
    const assign = vi.fn()
    vi.stubGlobal('window', { location: { assign } })
    followStepUp({ redirect: '/auth/keycloak/approve?mission=M-3' })
    expect(assign).toHaveBeenCalledWith('/auth/keycloak/approve?mission=M-3')
  })

  it('never follows another address, and leaves an ordinary answer alone', () => {
    const assign = vi.fn()
    vi.stubGlobal('window', { location: { assign } })
    followStepUp({ redirect: 'https://evil.example/' })
    expect(followStepUp({ id: 'M-3' })).toEqual({ id: 'M-3' })
    expect(assign).not.toHaveBeenCalled()
  })
})
