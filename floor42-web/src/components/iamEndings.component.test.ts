// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { office } from '../state/office'
import IamEndings from './IamEndings.vue'

const request = vi.hoisted(() => vi.fn())
vi.mock('../api/request', () => ({ request }))

const NOW = Date.parse('2026-11-05T10:00:00Z')
const iso = (minutes: number) => new Date(NOW + minutes * 60_000).toISOString()
const mission = (id: string, status = 'RUNNING') => ({ id, title: `Title ${id}`, status, archived: false })

/** Serves the three IAM routes: the status, the tokens and one delegation per mission. */
function serve(enabled: boolean, tokens: object[], delegations: Record<string, object>) {
  request.mockImplementation(async (_m: string, path: string) => {
    if (path.endsWith('/status')) return { enabled }
    if (path.endsWith('/tokens')) return tokens
    const id = /missions\/([^/]+)\/delegation/.exec(path)?.[1] ?? ''
    if (delegations[id]) return delegations[id]
    throw new Error('404')
  })
}
const token = (agent: string, minutes: number) => ({ agent, expiresAt: iso(minutes) })
const deleg = (minutes: number, revoked = false) => ({ expiresAt: iso(minutes), revoked })

describe('IAM endings', () => {
  beforeEach(() => {
    vi.useFakeTimers({ now: NOW, toFake: ['Date', 'setInterval', 'clearInterval'] })
    request.mockReset()
    office.missions = []
  })
  afterEach(() => vi.useRealTimers())

  it('IAM off: no call to /api/iam/tokens and no toast', async () => {
    serve(false, [token('ana', 5)], {})
    const w = mount(IamEndings)
    await flushPromises()
    expect(request.mock.calls.some((c) => String(c[1]).endsWith('/tokens'))).toBe(false)
    expect(w.find('.session-toast').exists()).toBe(false)
  })

  it('a live token ending in 5 minutes raises the agent toast with the time', async () => {
    serve(true, [token('ana', 5), token('bob', 60)], {})
    const w = mount(IamEndings)
    await flushPromises()
    const toast = w.find('.iam-token')
    expect(toast.text()).toContain('ana')
    expect(toast.text()).not.toContain('bob')
    expect(toast.text()).toContain('refuses its actions')
  })

  it('a delegation ending in 20 minutes raises the mission toast, with the board closed', async () => {
    office.missions = [mission('M-1')] as never
    serve(true, [], { 'M-1': deleg(20) })
    const w = mount(IamEndings)
    await flushPromises()
    expect(w.find('.iam-delegation').text()).toContain('Title M-1')
  })

  it('a completed, a cancelled or a revoked delegation is not announced', async () => {
    office.missions = [mission('M-1', 'COMPLETED'), mission('M-2', 'CANCELLED'), mission('M-3')] as never
    serve(true, [], { 'M-1': deleg(5), 'M-2': deleg(5), 'M-3': deleg(5, true) })
    const w = mount(IamEndings)
    await flushPromises()
    expect(request.mock.calls.some((c) => String(c[1]).includes('M-1') || String(c[1]).includes('M-2'))).toBe(false)
    expect(w.find('.session-toast').exists()).toBe(false)
  })

  it('the cross closes the toast and it does not come back for the same end', async () => {
    serve(true, [token('ana', 5)], {})
    const w = mount(IamEndings)
    await flushPromises()
    await w.find('.session-toast button').trigger('click')
    expect(w.find('.session-toast').exists()).toBe(false)
    vi.advanceTimersByTime(60_000)
    await flushPromises()
    expect(w.find('.session-toast').exists()).toBe(false)
  })
})
