// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MissionView, TicketView } from '../api/types'
import { setLang } from '../i18n'
import MissionGraph from './MissionGraph.vue'
import ProposedTickets from './ProposedTickets.vue'
import TeamMode from './TeamMode.vue'

vi.mock('../state/office', () => ({ approveTickets: vi.fn(), dropTicket: vi.fn(), editTicket: vi.fn() }))

const node = (id: string, dependsOn: string[], status: TicketView['status'], description = ''): TicketView =>
  ({
    id,
    title: `Do ${id}`,
    description,
    status,
    dependsOn,
    acceptanceCriteria: ['it works', 'Test: make check, exits 0'],
    reviews: [],
    kind: 'DELIVERY',
    waitingFor: null,
  }) as unknown as TicketView

const mission = (mode: string, status: TicketView['status'] = 'PROPOSED'): MissionView =>
  ({
    id: 'M-1',
    questions: [],
    tickets: [
      node('T-2', [], status, 'First.\nRisk: low: it only reads\nTier: light'),
      node('T-3', ['T-2'], status, 'Second.\nRisk: high: touches money\nTier: standard'),
    ],
    workflow: { pinned: true, workflow: { name: 'built-in', steps: [], mode }, schemaVersion: null, refusal: null, follows: null },
  }) as unknown as MissionView

afterEach(() => setLang('en'))

describe('the graph at the plan approval and on the board', () => {
  it('draws the nodes, the edge and the marks at the approval, beside the approval actions', () => {
    setLang('en')
    const w = mount(ProposedTickets, { props: { mission: mission('graph') } })
    expect(w.findAll('.graph-node')).toHaveLength(2)
    expect(w.findAll('.graph-edge')).toHaveLength(1)
    const marks = w.findAll('.proposal-marks').map((m) => m.text())
    expect(marks[0]).toContain('low risk')
    expect(marks[0]).toContain('light')
    expect(marks[1]).toContain('high risk : touches money')
    expect(w.text()).toContain('Test: make check, exits 0')
    expect(w.text()).not.toContain('Risk: low')
    expect(w.findAll('button').map((b) => b.text())).toEqual(
      expect.arrayContaining(['Edit', 'Drop', 'Approve selected (2)']),
    )
  })

  it('draws no graph for a loop mission', () => {
    const w = mount(ProposedTickets, { props: { mission: mission('loop') } })
    expect(w.find('.task-graph').exists()).toBe(false)
    expect(w.findAll('.proposal-marks')).toHaveLength(0)
  })

  it('shows the graph on the board for a graph mission only, and a click picks the node', async () => {
    const graph = mount(MissionGraph, { props: { mission: mission('graph', 'IN_PROGRESS') } })
    expect(graph.findAll('.st-IN_PROGRESS')).toHaveLength(2)
    await graph.find('.graph-node').trigger('click')
    expect(graph.emitted('pick')).toEqual([['T-2']])
    expect(mount(MissionGraph, { props: { mission: mission('loop') } }).find('.task-graph').exists()).toBe(false)
  })
})

describe('how the team works', () => {
  it('offers the graph only with the planner, and falls back to the loop without it', async () => {
    setLang('en')
    const w = mount(TeamMode, { props: { plan: true, modelValue: 'graph' } })
    const graph = w.findAll('input')[1]!
    expect(graph.attributes('disabled')).toBeUndefined()
    await w.setProps({ plan: false })
    expect(w.findAll('input')[1]!.attributes('disabled')).toBeDefined()
    expect(w.emitted('update:modelValue')).toEqual([['loop']])
    expect(w.text()).toContain('Graph needs the planner')
  })

  it('says it in French', () => {
    setLang('fr')
    const w = mount(TeamMode, { props: { plan: true, modelValue: 'loop' } })
    expect(w.text()).toContain('Comment l’équipe travaille')
    expect(w.text()).toContain('Graphe :')
  })
})
