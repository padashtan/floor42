import type { MissionView, TicketView } from '../api/types'

/**
 * The task graph of a graph-mode mission (ADR-0062): nodes and edges drawn from the tickets' own `dependsOn`, their
 * status and their reviews. Derived, never stored: no journal change, nothing here reaches the 3D scene.
 */

export const NODE_W = 168
export const NODE_H = 66
const GAP_X = 28
const GAP_Y = 44
const PAD = 12

export type Risk = 'low' | 'high'
export type Tier = 'light' | 'standard'

export interface GraphNode {
  id: string
  title: string
  status: TicketView['status']
  level: number
  col: number
  x: number
  y: number
  risk: Risk | null
  tier: Tier | null
  /** Reviews that rejected the node so far. */
  rejected: number
  waiting: boolean
  /** A done node one of whose dependencies was reopened since (ADR-0062): shown, never acted on. */
  toRecheck: boolean
  /** Floor42's test runs of the node: each command by its latest run. Null when the node has no test. */
  tests: NodeTests | null
}

export interface NodeTests {
  total: number
  passed: number
  failed: number
}

export interface GraphEdge {
  from: string
  to: string
  x1: number
  y1: number
  x2: number
  y2: number
}

export interface GraphLayout {
  nodes: GraphNode[]
  edges: GraphEdge[]
  width: number
  height: number
}

/** Whether the mission's pinned workflow is in graph mode. */
export function isGraph(mission: MissionView | null | undefined): boolean {
  return mission?.workflow?.workflow?.mode === 'graph'
}

/** Each test command by its latest run (a resubmission runs them again); null when the node has none. */
export function testsOf(x: TicketView): NodeTests | null {
  const node = x.node
  if (!node || node.tests.length === 0) return null
  const last = new Map<string, string>()
  for (const r of node.runs) last.set(r.command, r.outcome)
  const outcomes = node.tests.map((c) => last.get(c))
  return {
    total: node.tests.length,
    passed: outcomes.filter((o) => o === 'passed').length,
    failed: outcomes.filter((o) => o === 'failed').length,
  }
}

const RISK = /^Risk: (low|high)\b[.:]?\s*(.*)$/im
const TIER = /^Tier: (light|standard)\s*$/im

/** The planner's risk for a node, written in the ticket's description at planning; null when it gave none. */
export function riskOf(description: string): { risk: Risk; why: string } | null {
  const m = RISK.exec(description)
  return m ? { risk: m[1].toLowerCase() as Risk, why: m[2].trim() } : null
}

export function tierOf(description: string): Tier | null {
  const m = TIER.exec(description)
  return m ? (m[1].toLowerCase() as Tier) : null
}

/** The description without the risk and tier lines, which are shown as marks. */
export function plainDescription(description: string): string {
  return description
    .split('\n')
    .filter((l) => !RISK.test(l) && !TIER.test(l))
    .join('\n')
    .trim()
}

/** The planner's ticket is not a node of the work. */
function nodes(tickets: readonly TicketView[]): TicketView[] {
  return tickets.filter((x) => x.kind !== 'PLANNING')
}

/** Each node on the level after its deepest dependency; a dependency that is not a node counts for nothing. */
function levels(list: readonly TicketView[]): Map<string, number> {
  const byId = new Map(list.map((x) => [x.id, x]))
  const level = new Map<string, number>()
  const visiting = new Set<string>()
  const of = (id: string): number => {
    const known = level.get(id)
    if (known !== undefined) return known
    // A cycle cannot be journaled; the guard keeps a damaged one from looping.
    if (visiting.has(id)) return 0
    visiting.add(id)
    const deps = (byId.get(id)?.dependsOn ?? []).filter((d) => byId.has(d))
    const value = deps.length ? 1 + Math.max(...deps.map(of)) : 0
    visiting.delete(id)
    level.set(id, value)
    return value
  }
  for (const x of list) of(x.id)
  return level
}

/** Columns inside a level follow the average column of the dependencies, which keeps most edges short. */
function columns(list: readonly TicketView[], level: Map<string, number>): Map<string, number> {
  const col = new Map<string, number>()
  const depth = Math.max(-1, ...level.values())
  for (let l = 0; l <= depth; l++) {
    const row = list.filter((x) => level.get(x.id) === l)
    const score = (x: TicketView) => {
      const cols = x.dependsOn.map((d) => col.get(d)).filter((c): c is number => c !== undefined)
      return cols.length ? cols.reduce((a, b) => a + b, 0) / cols.length : Number.MAX_SAFE_INTEGER
    }
    row.sort((a, b) => score(a) - score(b))
    row.forEach((x, i) => col.set(x.id, i))
  }
  return col
}

function rejections(x: TicketView): number {
  return x.reviews.filter((r) => r.verdict === 'REJECTED').length
}

export function layoutGraph(mission: MissionView, includeCancelled = false): GraphLayout {
  const list = nodes(mission.tickets).filter((x) => includeCancelled || x.status !== 'CANCELLED')
  const level = levels(list)
  const col = columns(list, level)
  const open = new Set(mission.questions.filter((q) => q.status === 'OPEN').map((q) => q.ticket))
  const placed: GraphNode[] = list.map((x) => ({
    id: x.id,
    title: x.title,
    status: x.status,
    level: level.get(x.id) ?? 0,
    col: col.get(x.id) ?? 0,
    x: PAD + (col.get(x.id) ?? 0) * (NODE_W + GAP_X),
    y: PAD + (level.get(x.id) ?? 0) * (NODE_H + GAP_Y),
    risk: x.node?.risk ?? riskOf(x.description)?.risk ?? null,
    tier: x.node?.tier ?? tierOf(x.description),
    rejected: rejections(x),
    waiting: !!x.waitingFor || open.has(x.id),
    toRecheck: x.toRecheck,
    tests: testsOf(x),
  }))
  const at = new Map(placed.map((n) => [n.id, n]))
  const edges: GraphEdge[] = []
  for (const x of list) {
    const to = at.get(x.id)!
    for (const d of x.dependsOn) {
      const from = at.get(d)
      if (!from) continue
      edges.push({
        from: d,
        to: x.id,
        x1: from.x + NODE_W / 2,
        y1: from.y + NODE_H,
        x2: to.x + NODE_W / 2,
        y2: to.y,
      })
    }
  }
  const cols = Math.max(0, ...placed.map((n) => n.col + 1))
  const rows = Math.max(0, ...placed.map((n) => n.level + 1))
  return {
    nodes: placed,
    edges,
    width: PAD * 2 + cols * NODE_W + Math.max(0, cols - 1) * GAP_X,
    height: PAD * 2 + rows * NODE_H + Math.max(0, rows - 1) * GAP_Y,
  }
}
