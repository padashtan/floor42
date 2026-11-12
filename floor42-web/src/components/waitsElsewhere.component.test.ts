// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { MissionProgress } from '../api/types'
import { setLang } from '../i18n'
import { office, putOnBoard } from '../state/office'
import { panels } from '../state/openPanels'
import { waits } from '../state/waits'
import DirectorDesk from './DirectorDesk.vue'
import PutOnBoard from './PutOnBoard.vue'

vi.mock('../state/office', async (actual) => ({ ...(await actual<typeof import('../state/office')>()), putOnBoard: vi.fn(async () => {}) }))

beforeAll(() => setLang('en'))
enableAutoUnmount(afterEach)
afterEach(() => {
  waits.missions = null
  office.mission = null
})

const plan = (): MissionProgress => ({
  mission: 'M-17',
  title: 'T',
  status: 'AWAITING_APPROVAL',
  ticketsDone: 0,
  ticketsAll: 0,
  running: [],
  forYou: [{ kind: 'APPROVE_PLAN', ticket: null, agent: null, question: null, text: null, status: null, count: null, since: new Date().toISOString(), runningSeconds: null, deadline: null }],
  stuck: [],
  stall: { state: 'WAITING', reason: null, work: null, ticket: null, agent: null, elsewhere: null, since: null, until: null },
})

describe('a plan waiting off the board', () => {
  it('counts in To decide and lists a button that leads to the plan', async () => {
    waits.missions = [plan()]
    const desk = mount(DirectorDesk, { props: { focus: null } })
    expect(desk.find('[data-tab="decide"] .count').text()).toBe('1')
    expect(desk.text()).toContain('Waiting in other missions')
    await desk.find('.elsewhere button.action').trigger('click')
    expect(putOnBoard).toHaveBeenCalledWith('M-17')
    expect(panels.desk).toBe(true)
  })

  it('offers to put the mission on an empty board instead of saying there is none', async () => {
    waits.missions = [plan()]
    const box = mount(PutOnBoard, { slots: { default: '<i>No mission</i>' } })
    expect(box.text()).toContain('M-17 awaits your approval.')
    expect(box.text()).not.toContain('No mission')
    await box.find('button').trigger('click')
    expect(putOnBoard).toHaveBeenCalledWith('M-17')
    waits.missions = []
    await box.vm.$nextTick()
    expect(box.text()).toContain('No mission')
  })
})
