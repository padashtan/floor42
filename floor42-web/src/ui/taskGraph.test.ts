import { describe, expect, it } from 'vitest'
import type { MissionView, TicketView } from '../api/types'
import { isGraph, layoutGraph, NODE_H, NODE_W, plainDescription, riskOf, testsOf, tierOf } from './taskGraph'

function ticket(id: string, dependsOn: string[], over: Partial<TicketView> = {}): TicketView {
  return {
    id,
    title: `Title of ${id}`,
    description: '',
    priority: 'NORMAL',
    status: 'TODO',
    assignee: null,
    dependsOn,
    acceptanceCriteria: [],
    deliverable: null,
    reviews: [],
    directives: [],
    consultations: [],
    createdAt: '2026-11-04T09:00:00Z',
    startedAt: null,
    endedAt: null,
    kind: 'DELIVERY',
    waitingFor: null,
    environmentBlocker: null,
    node: null,
    toRecheck: false,
    ...over,
  }
}

function mission(tickets: TicketView[], mode = 'graph'): MissionView {
  return {
    tickets,
    questions: [],
    workflow: { pinned: true, workflow: { name: 'built-in', steps: [], mode }, schemaVersion: null, refusal: null, follows: null },
  } as unknown as MissionView
}

describe('the task graph', () => {
  it('puts each node one level under its deepest dependency and draws one edge per dependency', () => {
    const g = layoutGraph(mission([ticket('T-1', []), ticket('T-2', []), ticket('T-3', ['T-1']), ticket('T-4', ['T-3', 'T-2'])]))
    const level = Object.fromEntries(g.nodes.map((n) => [n.id, n.level]))
    expect(level).toEqual({ 'T-1': 0, 'T-2': 0, 'T-3': 1, 'T-4': 2 })
    expect(g.edges.map((e) => `${e.from}>${e.to}`).sort()).toEqual(['T-1>T-3', 'T-2>T-4', 'T-3>T-4'])
    const e = g.edges.find((x) => x.from === 'T-1')!
    expect(e.y1).toBeLessThan(e.y2)
  })

  it('never overlaps two nodes and fits them in the box it reports', () => {
    const g = layoutGraph(mission([ticket('T-1', []), ticket('T-2', []), ticket('T-3', []), ticket('T-4', ['T-1', 'T-2', 'T-3'])]))
    const cells = new Set(g.nodes.map((n) => `${n.level}:${n.col}`))
    expect(cells.size).toBe(g.nodes.length)
    for (const n of g.nodes) {
      expect(n.x + NODE_W).toBeLessThanOrEqual(g.width)
      expect(n.y + NODE_H).toBeLessThanOrEqual(g.height)
    }
  })

  it('leaves the planner ticket and dropped nodes out, and ignores a dependency that is not a node', () => {
    const g = layoutGraph(
      mission([ticket('T-0', [], { kind: 'PLANNING' }), ticket('T-1', ['T-0']), ticket('T-2', [], { status: 'CANCELLED' })]),
    )
    expect(g.nodes.map((n) => n.id)).toEqual(['T-1'])
    expect(g.nodes[0]!.level).toBe(0)
    expect(g.edges).toEqual([])
  })

  it('does not loop on a damaged cycle', () => {
    const g = layoutGraph(mission([ticket('T-1', ['T-2']), ticket('T-2', ['T-1'])]))
    expect(g.nodes).toHaveLength(2)
  })

  it('reads the rejections, the wait and the planner marks of a node', () => {
    const rejected = { reviewer: 'a', verdict: 'REJECTED', findings: '', items: null }
    const m = mission([
      ticket('T-1', [], { reviews: [rejected, { ...rejected, verdict: 'VALIDATED' }], description: 'Why.\nRisk: high: a wrong licence\nTier: light' }),
      ticket('T-2', ['T-1'], { waitingFor: 'Q-1' }),
    ])
    const [one, two] = layoutGraph(m).nodes
    expect(one).toMatchObject({ rejected: 1, risk: 'high', tier: 'light', waiting: false })
    expect(two).toMatchObject({ rejected: 0, risk: null, tier: null, waiting: true })
  })

  it('knows a graph mission from a loop one', () => {
    expect(isGraph(mission([], 'graph'))).toBe(true)
    expect(isGraph(mission([], 'loop'))).toBe(false)
    expect(isGraph(null)).toBe(false)
  })
})

describe('the node of a graph mission (increment 2)', () => {
  const node = (over: Partial<NonNullable<TicketView['node']>> = {}): NonNullable<TicketView['node']> => ({
    risk: 'low',
    tier: 'light',
    tests: ['cat A', 'cat B'],
    runs: [],
    ...over,
  })

  it('takes risk and tier from the journaled node, not from a description', () => {
    const m = mission([ticket('T-1', [], { description: 'Risk: high: old\nTier: standard', node: node() })])
    expect(layoutGraph(m).nodes[0]).toMatchObject({ risk: 'low', tier: 'light' })
  })

  it('counts each test by its latest run', () => {
    const run = (command: string, outcome: 'passed' | 'failed' | 'not run') => ({ command, outcome, exitCode: null, durationMs: 1, at: '' })
    const x = ticket('T-1', [], {
      node: node({ runs: [run('cat A', 'failed'), run('cat A', 'passed'), run('cat B', 'failed')] }),
    })
    expect(testsOf(x)).toEqual({ total: 2, passed: 1, failed: 1 })
    expect(testsOf(ticket('T-2', [], { node: node({ tests: [] }) }))).toBeNull()
    expect(testsOf(ticket('T-3', []))).toBeNull()
  })

  it('marks a done node to recheck, and only what the server says', () => {
    const m = mission([ticket('T-1', [], { status: 'DONE' }), ticket('T-2', ['T-1'], { status: 'DONE', toRecheck: true })])
    const [one, two] = layoutGraph(m).nodes
    expect(one.toRecheck).toBe(false)
    expect(two.toRecheck).toBe(true)
  })
})

describe('the planner marks in a description', () => {
  it('reads risk and tier, and shows the rest as the description', () => {
    const d = 'Know what the code may reuse (size S)\nRisk: High: a wrong licence is costly\nTier: standard'
    expect(riskOf(d)).toEqual({ risk: 'high', why: 'a wrong licence is costly' })
    expect(tierOf(d)).toBe('standard')
    expect(plainDescription(d)).toBe('Know what the code may reuse (size S)')
  })

  it('reads nothing from a loop description', () => {
    expect(riskOf('Risk of rain')).toBeNull()
    expect(tierOf('A light touch')).toBeNull()
    expect(plainDescription('A light touch')).toBe('A light touch')
  })
})
