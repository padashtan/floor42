// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { proofApi } from '../api/proof'
import { panels } from '../state/openPanels'
import type { MissionView, QuestionView } from '../api/types'
import { setLang } from '../i18n'
import { office, putOnBoard } from '../state/office'
import { waits } from '../state/waits'
import type { MissionProgress } from '../api/types'
import { setClock } from '../ui/clock'
import WaitingBanner from './WaitingBanner.vue'

vi.mock('../state/office', async (actual) => ({ ...(await actual<typeof import('../state/office')>()), putOnBoard: vi.fn(async () => {}) }))

const question = (id: string, over: Partial<QuestionView>): QuestionView =>
  ({ id, mission: 'M-1', subject: `subject of ${id}`, status: 'OPEN', blocking: false, criticality: 'NORMAL', askedAt: '2026-11-05T10:00:00Z', phraseCode: null, phraseParams: null, ...over }) as QuestionView

describe('WaitingBanner', () => {
  afterEach(() => {
    office.questions = []
    waits.missions = null
    panels.board = false
  })

  it('measures a wait against the page clock, which the online demo sets to the tape clock', () => {
    setLang('en')
    setClock(() => Date.parse('2026-11-05T10:12:00Z'))
    office.questions = [question('Q-1', { blocking: true })]
    try {
      expect(mount(WaitingBanner).text()).toContain('for 12 min')
    } finally {
      setClock(() => Date.now())
    }
  })

  it('says nothing while nothing waits', () => {
    setLang('en')
    expect(mount(WaitingBanner).find('[data-testid="waiting-banner"]').exists()).toBe(false)
  })

  it('shows the blocking question first, unfolds the others, and opens the one clicked', async () => {
    setLang('en')
    office.questions = [question('Q-1', {}), question('Q-2', { blocking: true, mission: 'M-15', askedAt: '2026-11-05T11:00:00Z' })]
    const w = mount(WaitingBanner)
    expect(w.text()).toContain('M-15 waits for your decision: subject of Q-2')
    expect(w.text()).not.toContain('subject of Q-1')
    await w.find('.waiting-more').trigger('click')
    expect(w.text()).toContain('subject of Q-1')
    await w.findAll('.waiting-row button')[1].trigger('click')
    expect(w.emitted('open')).toEqual([['Q-1']])
  })

  it('shows a mission waiting for approval without any question, and opens the board', async () => {
    setLang('en')
    waits.missions = [
      {
        mission: 'M-9', title: 'T', status: 'ACTIVE', ticketsDone: 0, ticketsAll: 1, running: [], stuck: [],
        forYou: [{ kind: 'APPROVE_TICKET', ticket: 'T-1', agent: null, question: null, text: null, status: null, count: null, since: '2026-11-05T10:00:00Z', runningSeconds: null, deadline: null }],
        stall: { state: 'WAITING', reason: null, work: null, ticket: null, agent: null, elsewhere: null, since: null, until: null },
      } as MissionProgress,
    ]
    const w = mount(WaitingBanner)
    expect(w.find('[data-testid="waiting-banner"]').exists()).toBe(true)
    expect(w.text()).toContain('M-9')
    await w.find('.waiting-row button').trigger('click')
    expect(panels.board).toBe(true)
    expect(putOnBoard).toHaveBeenCalledWith('M-9')
    expect(w.emitted('open')).toBeUndefined()
  })

  describe('the delivery to prove', () => {
    const mission = (statuses: string[], status = 'ACTIVE') =>
      ({ id: 'M-2', status, tickets: statuses.map((s, i) => ({ id: `T-${i}`, status: s })) }) as unknown as MissionView
    const acceptance = (state: string | null) => vi.spyOn(proofApi, 'acceptance').mockResolvedValue({ state, token: state ? 'tok' : null })
    afterEach(() => {
      office.mission = null
      vi.restoreAllMocks()
      panels.prove = false
      panels.desk = false
    })

    it('says nothing while a ticket is left to do, or once the mission has ended', async () => {
      setLang('en')
      const read = acceptance('NOT_RUN')
      office.mission = mission(['DONE', 'IN_PROGRESS'])
      const w = mount(WaitingBanner)
      await flushPromises()
      expect(w.find('[data-testid="proof-line"]').exists()).toBe(false)
      expect(read).not.toHaveBeenCalled()
      office.mission = mission(['DONE'], 'COMPLETED')
      await flushPromises()
      expect(w.find('[data-testid="proof-line"]').exists()).toBe(false)
    })

    it('asks to prove an unproven delivery, and opens the Prove panel', async () => {
      setLang('en')
      acceptance('NOT_RUN')
      office.mission = mission(['DONE', 'CANCELLED'])
      const w = mount(WaitingBanner)
      await flushPromises()
      expect(w.text()).toContain('M-2: the work is done, prove the delivery before completing it')
      await w.find('[data-testid="proof-line"] button').trigger('click')
      expect(panels.prove).toBe(true)
    })

    it('says a proven delivery can be completed, and opens the desk', async () => {
      setLang('fr')
      acceptance(null)
      office.mission = mission(['DONE'])
      const w = mount(WaitingBanner)
      await flushPromises()
      expect(w.text()).toContain('M-2 : livraison prouvée, vous pouvez la terminer')
      await w.find('[data-testid="proof-line"] button').trigger('click')
      expect(panels.desk).toBe(true)
    })
  })
})
