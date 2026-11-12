// @vitest-environment happy-dom
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { setLang } from '../i18n'
import { pending } from '../state/confirm'
import { openTodoGo } from './todoGo'

beforeAll(() => setLang('en'))
afterEach(() => vi.unstubAllGlobals())

const go = { panel: 'quarantine', mission: 'M-1', agent: 'ada' } as const

function stubFetch() {
  const fetch = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(async () => new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  return fetch
}
const posts = (fetch: ReturnType<typeof stubFetch>) =>
  fetch.mock.calls.filter(([url, init]) => String(url).endsWith('/quarantine') && init?.method === 'POST')

describe('proposing a quarantine', () => {
  it('a declined confirm sends nothing, and the dialog says what happens first', async () => {
    const fetch = stubFetch()
    openTodoGo(go, () => {})
    await flushPromises()
    const message = pending.value?.request.message ?? ''
    expect(message).toContain('revokes the mission')
    expect(message).toContain('suspends the mission')
    expect(message).toContain('stops the session')
    expect(pending.value?.request.danger).toBe(true)
    pending.value?.settle(null)
    await flushPromises()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('a confirmed quarantine posts the agent', async () => {
    const fetch = stubFetch()
    openTodoGo(go, () => {})
    await flushPromises()
    expect(posts(fetch)).toHaveLength(0)
    pending.value?.settle(true)
    await flushPromises()
    const sent = posts(fetch)
    expect(sent).toHaveLength(1)
    expect(sent[0]![0]).toBe('/api/missions/M-1/quarantine')
    expect(sent[0]![1]?.body).toBe('{"agent":"ada"}')
  })
})
