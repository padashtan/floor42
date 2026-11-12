// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MissionProgress, StallView } from '../api/types'
import { setLang } from '../i18n'
import { waits } from '../state/waits'
import ProgressPanel from './ProgressPanel.vue'

vi.mock('../state/waits', async (original) => ({
  ...(await original<typeof import('../state/waits')>()),
  loadWaits: vi.fn(),
}))

function mission(stall: Partial<StallView>): MissionProgress {
  return {
    mission: 'M-1',
    title: 'Sample',
    status: 'IN_PROGRESS',
    ticketsDone: 0,
    ticketsAll: 1,
    running: [],
    forYou: [],
    stuck: [],
    stall: {
      state: 'WAITING',
      reason: null,
      work: null,
      ticket: 'T-1',
      agent: 'ada',
      elsewhere: null,
      since: null,
      until: null,
      ...stall,
    },
  }
}

describe('the progress panel, work held in the workshop', () => {
  beforeEach(() => setLang('en'))

  it('says it is the director turn and opens the workshop', async () => {
    waits.missions = [mission({ reason: 'HELD_PATCH' })]
    const panel = mount(ProgressPanel)
    const line = panel.find('.stall')
    expect(line.classes()).toContain('you')
    expect(line.find('.your-turn').text()).toBe('Your turn:')
    const open = line.find('button')
    expect(open.text()).toBe('Open the workshop')
    await open.trigger('click')
    expect(panel.emitted('workshop')).toHaveLength(1)
  })

  it('shows no label and no button for any other wait', () => {
    waits.missions = [mission({ reason: 'AGENT_BUSY', elsewhere: 'M-2' })]
    const line = mount(ProgressPanel).find('.stall')
    expect(line.exists()).toBe(true)
    expect(line.classes()).not.toContain('you')
    expect(line.find('.your-turn').exists()).toBe(false)
    expect(line.find('button').exists()).toBe(false)
  })
})

describe('the progress panel, approvals', () => {
  beforeEach(() => setLang('en'))

  it('routes a waiting plan through the shared to-do routing, not a board event', async () => {
    const go = vi.spyOn(await import('../ui/todoGo'), 'openTodoGo').mockImplementation(() => {})
    const m = mission({})
    m.forYou = [{ kind: 'APPROVE_PLAN', ticket: null, agent: null, since: null } as unknown as MissionProgress['forYou'][number]]
    waits.missions = [m]
    const panel = mount(ProgressPanel)
    await panel.find('.panel-scroll button:not(.close)').trigger('click')
    expect(go).toHaveBeenCalledWith({ panel: 'plan', mission: 'M-1' }, expect.any(Function))
    expect(panel.emitted('board')).toBeUndefined()
  })
})
