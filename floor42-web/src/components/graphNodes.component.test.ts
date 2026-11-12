// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MissionView, TicketView } from '../api/types'
import { setLang, t } from '../i18n'
import { approveTickets, extendGraph } from '../state/office'
import ExtendGraph from './ExtendGraph.vue'
import NodeFacts from './NodeFacts.vue'
import ProposedTickets from './ProposedTickets.vue'
import TaskGraph from './TaskGraph.vue'

vi.mock('../state/office', () => ({
  approveTickets: vi.fn(() => Promise.resolve(true)),
  extendGraph: vi.fn(() => Promise.resolve(true)),
  dropTicket: vi.fn(),
  editTicket: vi.fn(),
}))

const ticket = (id: string, over: Partial<TicketView> = {}): TicketView =>
  ({
    id,
    title: `Do ${id}`,
    description: '',
    status: 'PROPOSED',
    dependsOn: [],
    acceptanceCriteria: [],
    reviews: [],
    kind: 'WORK',
    waitingFor: null,
    node: null,
    toRecheck: false,
    ...over,
  }) as unknown as TicketView

const mission = (tickets: TicketView[], status = 'IN_PROGRESS'): MissionView =>
  ({
    id: 'M-1',
    origin: 'REAL',
    status,
    questions: [],
    tickets,
    workflow: { pinned: true, workflow: { name: 'built-in', steps: [], mode: 'graph' }, schemaVersion: null, refusal: null, follows: null },
  }) as unknown as MissionView

beforeEach(() => vi.clearAllMocks())
afterEach(() => setLang('en'))

describe('extend the graph', () => {
  it('says it is paid before the click, then asks for the extension with the goal', async () => {
    const w = mount(ExtendGraph, { props: { mission: mission([ticket('T-2', { status: 'DONE' })]) } })
    expect(w.text()).toContain(t('graph.extend.hint'))
    expect(w.text()).toContain('paid')
    await w.find('input').setValue('  Add the notes ')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(extendGraph).toHaveBeenCalledWith('M-1', 'Add the notes')
  })

  it('is not offered before the plan is approved, and not twice while the planner reads', () => {
    expect(mount(ExtendGraph, { props: { mission: mission([ticket('T-2')], 'AWAITING_APPROVAL') } }).find('form').exists()).toBe(false)
    const reading = mission([ticket('T-2', { status: 'DONE' }), ticket('T-3', { kind: 'PLANNING', title: 'Extend the graph', status: 'IN_PROGRESS' })])
    const w = mount(ExtendGraph, { props: { mission: reading } })
    expect(w.find('button').attributes('disabled')).toBeDefined()
    expect(w.text()).toContain(t('graph.extend.asked'))
  })

  it('speaks French too', () => {
    setLang('fr')
    const w = mount(ExtendGraph, { props: { mission: mission([ticket('T-2')]) } })
    expect(w.text()).toContain('payante')
  })
})

describe('the risk the director chooses at the approval', () => {
  it('flips a risk and sends only what changed', async () => {
    const m = mission([
      ticket('T-2', { description: 'First.\nRisk: low: it only reads\nTier: light' }),
      ticket('T-3', { description: 'Second.\nRisk: high: money\nTier: standard' }),
    ])
    const w = mount(ProposedTickets, { props: { mission: m }, global: { stubs: { TaskGraph: true } } })
    const marks = () => w.findAll('.proposal-marks').map((x) => x.text())
    expect(marks()[0]).toContain(t('graph.risk.low'))
    await w.findAll('.risk-flip')[0].trigger('click')
    expect(marks()[0]).toContain(t('graph.risk.high'))
    await w.find('button.recommended').trigger('click')
    await flushPromises()
    expect(approveTickets).toHaveBeenCalledWith('M-1', ['T-2', 'T-3'], { 'T-2': 'high' })
  })

  it('sends no risk when the director changed none', async () => {
    const m = mission([ticket('T-2', { description: 'First.\nRisk: low: it only reads' })])
    const w = mount(ProposedTickets, { props: { mission: m }, global: { stubs: { TaskGraph: true } } })
    await w.find('button.recommended').trigger('click')
    await flushPromises()
    expect(approveTickets).toHaveBeenCalledWith('M-1', ['T-2'], {})
  })
})

describe('what a node shows', () => {
  const run = (outcome: 'passed' | 'failed' | 'not run') => ({ command: 'make check', outcome, exitCode: null, durationMs: 4, at: '' })

  it('lists its tests with each run, and the to-recheck mark with its reason', () => {
    const x = ticket('T-3', {
      status: 'DONE',
      toRecheck: true,
      node: { risk: 'high', tier: 'standard', tests: ['make check'], runs: [run('failed'), run('passed')] },
    })
    const w = mount(NodeFacts, { props: { ticket: x } })
    expect(w.text()).toContain('make check')
    expect(w.text()).toContain(t('graph.run.failed'))
    expect(w.text()).toContain(t('graph.run.passed'))
    expect(w.find('.recheck').text()).toContain(t('graph.toRecheckHint'))
    expect(mount(NodeFacts, { props: { ticket: ticket('T-9') } }).find('.node-facts').exists()).toBe(false)
  })

  it('draws the test count and the to-recheck mark on the node of the graph', () => {
    const m = mission([
      ticket('T-2', { status: 'DONE', node: { risk: 'low', tier: 'light', tests: ['make check'], runs: [run('passed')] } }),
      ticket('T-3', { status: 'DONE', dependsOn: ['T-2'], toRecheck: true }),
    ])
    const w = mount(TaskGraph, { props: { mission: m } })
    const nodes = w.findAll('.graph-node')
    expect(nodes[0].text()).toContain(t('graph.tests', { ok: 1, total: 1 }))
    expect(nodes[0].find('.recheck').exists()).toBe(false)
    expect(nodes[1].find('.recheck').text()).toBe(t('graph.toRecheck'))
  })
})
