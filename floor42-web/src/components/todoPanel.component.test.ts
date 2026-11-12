// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { nextTick } from 'vue'
import type { MissionProgress, QuestionView } from '../api/types'
import { setLang } from '../i18n'
import { wanted } from '../state/library'
import { office, putOnBoard } from '../state/office'
import { panels } from '../state/openPanels'
import { waits } from '../state/waits'
import TodoPanel from './TodoPanel.vue'

vi.mock('../state/office', async (actual) => ({ ...(await actual<typeof import('../state/office')>()), putOnBoard: vi.fn(async () => {}) }))

beforeAll(() => setLang('en'))
enableAutoUnmount(afterEach)
afterEach(() => {
  waits.missions = null
  office.questions = []
  localStorage.clear()
})

const plan = (): MissionProgress => ({
  mission: 'M-1',
  title: 'T',
  status: 'AWAITING_APPROVAL',
  ticketsDone: 0,
  ticketsAll: 0,
  running: [],
  forYou: [{ kind: 'APPROVE_PLAN', ticket: null, agent: null, question: null, text: null, status: null, count: null, since: new Date().toISOString(), runningSeconds: null, deadline: null }],
  stuck: [],
  stall: { state: 'WAITING', reason: null, work: null, ticket: null, agent: null, elsewhere: null, since: null, until: null },
})

describe('the to-do panel', () => {
  it('says nothing waits, then shows a waiting plan live with a button that opens its approval on the desk', async () => {
    const panel = mount(TodoPanel)
    expect(panel.text()).toContain('Nothing waits for you')
    waits.missions = [plan()]
    await nextTick()
    expect(panel.text()).not.toContain('Nothing waits for you')
    expect(panel.find('[data-category="approvals"] .count').text()).toBe('1')
    expect(panel.find('[data-category="questions"] .count').text()).toBe('0')
    await panel.find('[data-category="approvals"] button.action').trigger('click')
    // The plan's cap and resources are on the desk's Mission tab, for the board's mission: M-1 goes on the board.
    expect(putOnBoard).toHaveBeenCalledWith('M-1')
    expect(panels.desk).toBe(true)
    expect(wanted.missionTab).toBe(true)
  })

  it('opens the desk on the very question for an open question', async () => {
    office.questions = [{ id: 'Q-7', mission: 'M-1', kind: 'OPEN', askedBy: 'ada', subject: 'Which branch?', status: 'OPEN', askedAt: new Date().toISOString(), phraseCode: null, phraseParams: null } as QuestionView]
    const panel = mount(TodoPanel)
    await panel.find('[data-category="questions"] button.action').trigger('click')
    expect(panel.emitted('question')).toEqual([['Q-7']])
  })

  it('checks uncommitted work once per mission waiting for acceptance, and says so when it cannot read', async () => {
    vi.useFakeTimers()
    const spy = vi.spyOn(api, 'uncommitted').mockRejectedValue(new Error('down'))
    const closing = plan()
    closing.forYou = [{ ...closing.forYou[0]!, kind: 'CLOSE_MISSION' }]
    waits.missions = [closing]
    const panel = mount(TodoPanel)
    await flushPromises()
    expect(panel.find('[data-category="alerts"]').text()).toContain('cannot tell')
    waits.missions = [{ ...closing }]
    await flushPromises()
    expect(spy).toHaveBeenCalledTimes(1)
    // A commit changes no progress but it is an event: the work is read again at the next tick, once for a burst.
    office.log.unshift({ id: 'E-99' } as (typeof office.log)[number])
    office.log.unshift({ id: 'E-100' } as (typeof office.log)[number])
    await flushPromises()
    expect(spy).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(10_000)
    await flushPromises()
    expect(spy).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(10_000)
    await flushPromises()
    expect(spy).toHaveBeenCalledTimes(2)
    office.log.splice(0)
    spy.mockRestore()
    vi.useRealTimers()
  })

  it('folds to its title and remembers it, never folded by default', async () => {
    const panel = mount(TodoPanel)
    expect(panel.find('.empty').exists()).toBe(true)
    await panel.find('button.fold').trigger('click')
    expect(panel.find('.empty').exists()).toBe(false)
    expect(localStorage.getItem('floor42.todo.collapsed')).toBe('yes')
  })
})
