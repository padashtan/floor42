import { describe, expect, it } from 'vitest'
import type { SessionView, TicketView } from '../api/types'
import { duration, ticketTiming } from './time'

const at = (min: number) => new Date(Date.UTC(2026, 9, 21, 10, min)).toISOString()

function session(agent: string, ticket: string, from: number, to: number | null): SessionView {
  return {
    id: `${agent}-${from}`,
    agent,
    ticket,
    model: 'm',
    open: to === null,
    failed: null,
    turns: null,
    inputTokensProvided: null,
    outputTokensProvided: null,
    costUsdEstimated: null,
    costUsdBound: null,
    startedAt: at(from),
    endedAt: to === null ? null : at(to),
    outcome: to === null ? null : 'completed',
  }
}

const ticket = (startedAt: string | null, endedAt: string | null) =>
  ({ id: 'T-1', startedAt, endedAt, createdAt: at(0) }) as TicketView

describe('ticketTiming', () => {
  it('separates the time agents worked from the time the ticket took', () => {
    const t = ticketTiming(
      ticket(at(0), at(30)),
      [session('chris', 'T-1', 0, 10), session('darius', 'T-1', 12, 16), session('chris', 'T-2', 0, 50)],
      Date.parse(at(60)),
    )
    expect(t.elapsedMs).toBe(30 * 60_000)
    expect(t.open).toBe(false)
    expect(t.work).toEqual([
      { agent: 'chris', ms: 10 * 60_000, running: false },
      { agent: 'darius', ms: 4 * 60_000, running: false },
    ])
    expect(t.workMs).toBe(14 * 60_000)
  })

  it('counts an open ticket and a running session up to now', () => {
    const t = ticketTiming(ticket(at(0), null), [session('chris', 'T-1', 5, null)], Date.parse(at(20)))
    expect(t.elapsedMs).toBe(20 * 60_000)
    expect(t.open).toBe(true)
    expect(t.work).toEqual([{ agent: 'chris', ms: 15 * 60_000, running: true }])
  })

  it('does not count a session left open by a stopped daemon as work', () => {
    // Seen on M-7: the review died at once, and the session was closed 52 minutes later, at the next startup.
    const dead = { ...session('darius', 'T-1', 2, 54), failed: true, outcome: 'interrupted' }
    const t = ticketTiming(ticket(at(0), at(60)), [session('darius', 'T-1', 55, 58), dead], Date.parse(at(60)))
    expect(t.work).toEqual([{ agent: 'darius', ms: 3 * 60_000, running: false }])
  })

  it('has no elapsed time before the ticket starts', () => {
    expect(ticketTiming(ticket(null, null), [], Date.parse(at(5))).elapsedMs).toBeNull()
  })
})

describe('duration', () => {
  it('reads at a glance', () => {
    expect(duration(45_000)).toBe('45 s')
    expect(duration(12 * 60_000)).toBe('12 min')
    expect(duration(65 * 60_000)).toBe('1 h 05')
  })
})
