// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ending, sessionEnd } from '../ui/sessionTime'
import DelegationUntil from './DelegationUntil.vue'
import SessionNote from './SessionNote.vue'

const api = vi.hoisted(() => ({ sessions: vi.fn(), signOut: vi.fn() }))
const request = vi.hoisted(() => vi.fn())
vi.mock('../api/client', () => ({ api }))
vi.mock('../api/request', () => ({ request }))

const NOW = Date.parse('2026-11-05T10:00:00Z')
const iso = (minutes: number) => new Date(NOW + minutes * 60_000).toISOString()
const session = (over: object) => ({ current: true, breakGlass: false, idleUntil: null, expires: null, keycloakUntil: null, ...over })

/** The director's session time (pilot irritant 11): the earlier limit, an amber warning 5 minutes before the end. */
describe('session time', () => {
  beforeEach(() => vi.useFakeTimers({ now: NOW, toFake: ['Date', 'setInterval', 'clearInterval'] }))
  afterEach(() => vi.useRealTimers())

  it('takes the earlier of the idle and the absolute limit', () => {
    expect(sessionEnd({ idleUntil: iso(480), expires: iso(60) })).toBe(NOW + 60 * 60_000)
    expect(sessionEnd({ idleUntil: iso(480), expires: null })).toBe(NOW + 480 * 60_000)
    expect(sessionEnd({ idleUntil: null, expires: null })).toBeNull()
  })

  it('warns from 5 minutes before the end', () => {
    expect(ending(NOW + 6 * 60_000, NOW)).toBe(false)
    expect(ending(NOW + 5 * 60_000, NOW)).toBe(true)
  })

  it('signed in with Keycloak: one padlock chip with the first end to come, both ends in its tooltip', async () => {
    api.sessions.mockResolvedValue([session({ idleUntil: iso(480), keycloakUntil: iso(120) })])
    request.mockResolvedValue({ enabled: true })
    const w = mount(SessionNote)
    await flushPromises()
    const chip = w.find('.id-chip.signed-in')
    expect(chip.classes('ending')).toBe(false)
    expect(w.findAll('.session-time')).toHaveLength(1)
    expect(chip.attributes('title')).toContain('idle time is renewed')
    expect(chip.attributes('title')).toContain('Keycloak')
    expect(w.find('.keycloak-sign-in').exists()).toBe(false)
    expect(w.find('.session-toast').exists()).toBe(false)
  })

  it('turns amber and toasts once near the end', async () => {
    api.sessions.mockResolvedValue([session({ idleUntil: iso(3), keycloakUntil: iso(120) })])
    request.mockResolvedValue({ enabled: true })
    const w = mount(SessionNote)
    await flushPromises()
    expect(w.find('.id-chip').classes('ending')).toBe(true)
    expect(w.findAll('.session-toast')).toHaveLength(1)
    await w.find('.session-toast button').trigger('click')
    expect(w.find('.session-toast').exists()).toBe(false)
  })

  it('signed in with the console link: an open padlock that leads to the Keycloak sign-in', async () => {
    api.sessions.mockResolvedValue([session({ breakGlass: true, idleUntil: iso(480) })])
    request.mockResolvedValue({ enabled: true })
    const w = mount(SessionNote)
    await flushPromises()
    const link = w.find('a.id-chip.warn')
    expect(link.attributes('href')).toBe('/auth/keycloak')
    expect(link.attributes('title')).toContain('console link')
  })

  it('shows no Keycloak time outside IAM mode', async () => {
    api.sessions.mockResolvedValue([session({ idleUntil: iso(480) })])
    request.mockResolvedValue({ enabled: false })
    const w = mount(SessionNote)
    await flushPromises()
    expect(w.findAll('.session-time')).toHaveLength(1)
  })

  it('shows the delegation end on the board only with IAM on and a delegation', async () => {
    request.mockImplementation(async (_m: string, path: string) => {
      if (path.endsWith('/status')) return { enabled: true }
      return { mission: 'M-1', expiresAt: iso(3), revoked: false }
    })
    const w = mount(DelegationUntil, { props: { mission: 'M-1' } })
    await flushPromises()
    expect(w.find('.session-time').classes('ending')).toBe(true)
    request.mockImplementation(async (_m: string, path: string) => {
      if (path.endsWith('/status')) return { enabled: true }
      throw new Error('404')
    })
    const none = mount(DelegationUntil, { props: { mission: 'M-2' } })
    await flushPromises()
    expect(none.find('.session-time').exists()).toBe(false)
  })
})
