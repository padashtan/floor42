import type { SessionView, TicketView } from '../api/types'

/** A duration a person reads at a glance: 45 s, 12 min, 1 h 05. */
export function duration(ms: number): string {
  if (!(ms >= 0)) return '—'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s} s`
  const m = Math.round(s / 60)
  if (m < 60) return `${m} min`
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')}`
}

export interface TicketTiming {
  /** From the first start to done (or to now while open); null before it starts. Waiting for you counts. */
  elapsedMs: number | null
  open: boolean
  /** Sessions only: the time each agent actually worked on the ticket, a running session up to now. */
  work: { agent: string; ms: number; running: boolean }[]
  workMs: number
}

/** Every figure comes from Floor42's own clock (journal times and session start and end): measured, not estimated. */
export function ticketTiming(t: TicketView, sessions: SessionView[], now: number): TicketTiming {
  const start = t.startedAt ? Date.parse(t.startedAt) : null
  const end = t.endedAt ? Date.parse(t.endedAt) : null
  const byAgent = new Map<string, { ms: number; running: boolean }>()
  for (const s of sessions) {
    if (s.ticket !== t.id) continue
    // Its recorded end is the next startup of the daemon, not the end of any work.
    if (s.outcome === 'interrupted') continue
    const from = Date.parse(s.startedAt)
    const to = s.endedAt ? Date.parse(s.endedAt) : now
    const cur = byAgent.get(s.agent) ?? { ms: 0, running: false }
    byAgent.set(s.agent, { ms: cur.ms + Math.max(0, to - from), running: cur.running || !s.endedAt })
  }
  const work = [...byAgent.entries()].map(([agent, v]) => ({ agent, ...v }))
  return {
    elapsedMs: start === null ? null : Math.max(0, (end ?? now) - start),
    open: end === null,
    work,
    workMs: work.reduce((a, w) => a + w.ms, 0),
  }
}
