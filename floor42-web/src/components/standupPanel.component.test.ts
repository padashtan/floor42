// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Standup } from '../api/types'
import { setLang, t } from '../i18n'
import StandupPanel from './StandupPanel.vue'

const api = vi.hoisted(() => ({ standup: vi.fn() }))
vi.mock('../api/client', () => ({
  api,
  ApiError: class ApiError extends Error {
    needsLogin = false
  },
}))

const standup: Standup = {
  date: '2026-11-16',
  previousDay: '2026-11-13',
  zone: 'Europe/Paris',
  agents: [
    {
      agent: 'ada',
      name: 'Ada',
      yesterday: [{ code: 'COMPLETED', mission: 'M-1', ticket: 'T-1', text: '<b>Add</b> the parser', count: null }],
      today: [{ code: 'IN_PROGRESS', mission: 'M-1', ticket: 'T-2', text: 'Next', count: null }],
      blocked: [{ code: 'QUESTION', mission: 'M-1', ticket: null, text: 'May I run it', count: null }],
    },
    { agent: 'bob', name: 'Bob', yesterday: [], today: [], blocked: [] },
  ],
}

describe('the standup panel', () => {
  beforeEach(() => setLang('en'))
  afterEach(() => vi.restoreAllMocks())

  it('asks with the browser zone, then shows the first agent in three columns', async () => {
    api.standup.mockResolvedValue(standup)
    const w = mount(StandupPanel)
    expect(w.text()).toContain(t('standup.loading'))
    await flushPromises()
    expect(api.standup).toHaveBeenCalledWith(Intl.DateTimeFormat().resolvedOptions().timeZone)
    expect(w.findAll('.standup-col')).toHaveLength(3)
    expect(w.text()).toContain('Completed: T-1')
    expect(w.text()).toContain('In progress: T-2 Next')
    expect(w.text()).toContain('Waiting for your answer: May I run it')
    expect(w.text()).toContain(`${t('standup.yesterday')} (2026-11-13)`)
  })

  it('shows agent text as text, never as markup', async () => {
    api.standup.mockResolvedValue(standup)
    const w = mount(StandupPanel)
    await flushPromises()
    expect(w.find('.standup-yesterday b').exists()).toBe(false)
    expect(w.get('.standup-yesterday').text()).toContain('<b>Add</b> the parser')
  })

  it('says so for an agent with nothing recorded', async () => {
    api.standup.mockResolvedValue({ ...standup, agents: [standup.agents[1]!] })
    const w = mount(StandupPanel)
    await flushPromises()
    expect(w.findAll('.standup-col .hint')).toHaveLength(3)
    expect(w.text()).toContain(t('standup.none'))
  })

  it('shows the failure message on an error', async () => {
    api.standup.mockRejectedValue(new Error('boom'))
    const w = mount(StandupPanel)
    await flushPromises()
    expect(w.get('[role=alert]').text()).toBe(t('standup.failed', { message: 'boom' }))
  })
})
