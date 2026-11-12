// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import type { MissionReport, ReportTicket, ReportWarning } from '../api/types'
import { setLang, t } from '../i18n'
import { pending } from '../state/confirm'
import { useGuard } from '../ui/guard'
import MissionEndNext from './MissionEndNext.vue'
import MissionEndSummary from './MissionEndSummary.vue'
import MissionEndTickets from './MissionEndTickets.vue'

const office = vi.hoisted(() => ({ completeMission: vi.fn(), createMission: vi.fn(), workshopCall: vi.fn() }))
vi.mock('../state/office', () => ({ ...office, office: { mission: null, missions: [], agents: [] } }))

beforeAll(() => setLang('en'))

const measure = { value: 1, basis: 'MEASURED' } as const
const ticket = (extra: Partial<ReportTicket> = {}): ReportTicket => ({
  id: 'T-1',
  title: 'Write the notes',
  kind: 'WORK',
  status: 'DONE',
  producer: 'chris',
  reviews: [],
  rejections: 0,
  producerVerdict: null,
  directives: 0,
  sessions: 0,
  failedSessions: 0,
  workMs: measure,
  elapsedMs: measure,
  costUsd: measure,
  costBound: { costUsd: { value: null, basis: 'UPPER_BOUND' }, sessions: 0 },
  commit: null,
  reopenTo: null,
  reopenRefusedCode: null,
  reopenRefused: null,
  environmentBlocker: null,
  ...extra,
})
const warning = (code: string): ReportWarning => ({ code, ticket: 'T-1', agent: 'chris', path: null, count: null, text: 'x' })

function report(extra: Partial<MissionReport> = {}): MissionReport {
  return {
    mission: 'M-1',
    title: 'Mission',
    status: 'IN_PROGRESS',
    origin: 'REAL',
    outcome: null,
    repository: null,
    projectBranch: null,
    readAt: '2026-11-01T10:00:00Z',
    totals: { done: 1, dropped: 0, open: 0, sessions: 1, failedSessions: 0, elapsedMs: measure, workMs: measure, costUsd: measure, costBound: { costUsd: { value: null, basis: 'UPPER_BOUND' }, sessions: 0 }, budgetUsd: null },
    tickets: [ticket()],
    reviews: { reviews: 0, validated: 0, withReservations: 0, rejected: 0, unverifiable: 0 },
    checks: [],
    retro: { state: 'NONE', chair: null, conclusions: [], reason: null, lessons: [] },
    outputs: [],
    warnings: [],
    next: [{ kind: 'CLOSE', available: true, code: null, message: null }],
    followUp: { title: 't', objective: 'o', repository: null },
    ...extra,
  } as MissionReport
}

describe('the tickets section', () => {
  it('shows the sessions of a ticket, an interrupted one (closed as failed by the recovery) counted as failed', () => {
    const interrupted = ticket({ sessions: 3, failedSessions: 1 })
    const w = mount(MissionEndTickets, { props: { report: report({ tickets: [interrupted] }) } })
    expect(w.text()).toContain('3 session(s), 1 failed')
  })
})

describe('the upper bound of the cost', () => {
  it('says the bound apart from the estimate, never as an estimate, and says nothing without one', () => {
    const bounded = report()
    bounded.totals = { ...bounded.totals, costBound: { costUsd: { value: 0.4, basis: 'UPPER_BOUND' }, sessions: 1 } }
    const text = mount(MissionEndSummary, { props: { report: bounded } }).text()
    expect(text).toContain('+ up to 0.4000 USD for 1 session(s) with no reported cost')
    expect(mount(MissionEndSummary, { props: { report: report() } }).text()).not.toContain('up to')
  })
})

describe('the summary alerts', () => {
  it('keeps a warn alert visible and folds only the info ones', () => {
    const w = mount(MissionEndSummary, { props: { report: report({ warnings: [warning('PRODUCER_PARTIAL'), warning('NO_CHECKS')] }) } })
    const warn = w.find('.warning.warn')
    expect(warn.exists()).toBe(true)
    expect(warn.element.closest('details')).toBeNull()
    const info = w.find('.warning.info')
    expect(info.exists()).toBe(true)
    expect(info.element.closest('details')).not.toBeNull()
    expect(w.find('details summary').text()).toContain('(1)')
  })
})

describe('the next steps and the Read again button', () => {
  beforeEach(() => vi.clearAllMocks())

  /** The panel's side: it owns the guard, the next steps come and go with the section. */
  function mountPanel() {
    const shown = ref(true)
    const state = { acting: ref(false) }
    const Host = defineComponent({
      setup() {
        const { acting, guard } = useGuard()
        state.acting = acting
        return () => (shown.value ? h(MissionEndNext, { report: report(), acting: acting.value, guard }) : h('span'))
      },
    })
    return { w: mount(Host, { global: { config: { errorHandler: () => {} } } }), shown, state }
  }
  const closeButton = (w: ReturnType<typeof mount>) => w.find('button.recommended')

  it('is not left stuck when the section changes during the action', async () => {
    let finish: (v: boolean) => void = () => {}
    office.completeMission.mockReturnValue(new Promise<boolean>((r) => (finish = r)))
    const { w, shown, state } = mountPanel()
    await closeButton(w).trigger('click')
    expect(state.acting.value).toBe(true)
    shown.value = false
    await w.vm.$nextTick()
    finish(true)
    await flushPromises()
    expect(state.acting.value).toBe(false)
  })

  it('is enabled again after an error', async () => {
    office.completeMission.mockRejectedValue(new Error('refused'))
    const { w, state } = mountPanel()
    await closeButton(w).trigger('click')
    await flushPromises()
    expect(state.acting.value).toBe(false)
  })
})

describe('a directive on a ticket its environment blocks', () => {
  beforeEach(() => vi.clearAllMocks())

  const browser = { missing: 'a desktop browser', step: 'render report.pdf with tools/render.cmd' }
  async function send(environmentBlocker: ReportTicket['environmentBlocker']) {
    office.workshopCall.mockResolvedValue({})
    const r = report({
      tickets: [ticket({ reopenTo: 'T-1', environmentBlocker })],
      next: [{ kind: 'REOPEN', available: true, code: null, message: null }],
    })
    const guard = async (work: () => Promise<unknown>) => !!(await work())
    const w = mount(MissionEndNext, { props: { report: r, acting: false, guard } })
    await w.findAll('button').find((b) => b.text() === t('report.do.REOPEN'))!.trigger('click')
    await w.find('textarea').setValue('Render them again')
    await w.findAll('button').find((b) => b.text() === t('report.sendDirective'))!.trigger('click')
    await flushPromises()
  }

  it('warns, naming what is missing, and sends nothing unless the director confirms', async () => {
    await send(browser)
    expect(pending.value?.request.message).toContain('lacks a desktop browser')
    expect(office.workshopCall).not.toHaveBeenCalled()
    pending.value!.settle(null)
    await flushPromises()
    expect(office.workshopCall).not.toHaveBeenCalled()
  })

  it('sends at once when the last report names no blocker', async () => {
    await send(null)
    expect(pending.value).toBeNull()
    expect(office.workshopCall).toHaveBeenCalledOnce()
  })
})
