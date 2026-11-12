// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { firstPage } from './demoMode'
import { firstShow } from './checklist'
import { shouldOffer } from '../state/tutorial'

describe('first-run surfaces in the online demo', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    localStorage.clear()
  })

  it('may open on a signed-in page of the normal build', () => {
    expect(shouldOffer(firstPage(true))).toBe(true)
  })

  it('stay closed in the demo build', () => {
    vi.stubEnv('VITE_DEMO_TAPE', '/tape.json')
    expect(firstPage(true)).toBe(false)
    expect(shouldOffer(firstPage(true))).toBe(false)
    expect(firstShow(firstPage(true))).toBe(false)
  })
})
