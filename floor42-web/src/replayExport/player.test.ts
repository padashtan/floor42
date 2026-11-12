import { describe, expect, it } from 'vitest'
// The player is written into each exported file by the daemon (ADR-0035): the very script, loaded as text here.
import source from '../../../floor42-server/src/main/resources/replay/player.js?raw'

interface ReplayEvent {
  seq: number
  at: string
  origin: 'REAL' | 'SIMULATED'
  type: string
  text: string
  agent?: string
  ticket?: string
  ran: boolean
  cost?: { usd: number; basis: string }
}

interface ReplayData {
  events: ReplayEvent[]
  tickets?: { id: string; title: string }[]
  maxGapMs?: number
  labels?: Record<string, string>
}

interface PlayerApi {
  duration: number
  playing: boolean
  play(): void
  pause(): void
  setSpeed(speed: number): void
  seek(position: number): void
  seekEvent(n: number): void
  count(): number
  now(): number
}

interface State {
  tickets: { id: string; title: string; status: string }[]
  agents: Record<string, { last: ReplayEvent; ran: boolean }>
  origins: Record<string, number>
  cost: number | null
  costs: number
  last: ReplayEvent | null
}

interface ReplayApi {
  timeline(events: ReplayEvent[], maxGapMs: number): { offsets: number[]; duration: number }
  countAt(offsets: number[], position: number): number
  stateAt(data: ReplayData, count: number): State
  Player: new (data: ReplayData, clock: () => number) => PlayerApi
  label(data: ReplayData, key: string, params?: Record<string, string | number>): string
}

// Outside a page the script defines OfficeReplay and boots nothing.
const R = new Function(`${source}\nreturn OfficeReplay`)() as ReplayApi

const T0 = Date.parse('2026-10-31T10:00:00Z')
const at = (ms: number) => new Date(T0 + ms).toISOString()
const ev = (seq: number, ms: number, extra: Partial<ReplayEvent> = {}): ReplayEvent => ({
  seq,
  at: at(ms),
  origin: 'REAL',
  type: 'AgentRemarked',
  text: `event ${seq}`,
  ran: false,
  ...extra,
})

describe('replay timeline', () => {
  it('keeps the recorded gaps and shortens only the long silences', () => {
    const events = [ev(1, 0), ev(2, 500), ev(3, 60_000), ev(4, 61_000)]
    expect(R.timeline(events, 2000)).toEqual({ offsets: [0, 500, 2500, 3500], duration: 3500 })
  })

  it('never goes back in time for events recorded in the same instant or out of order', () => {
    const events = [ev(1, 1000), ev(2, 1000), ev(3, 400)]
    expect(R.timeline(events, 2000).offsets).toEqual([0, 0, 0])
  })

  it('shows the events whose offset is reached', () => {
    const offsets = [0, 500, 2500]
    expect(R.countAt(offsets, 0)).toBe(1)
    expect(R.countAt(offsets, 499)).toBe(1)
    expect(R.countAt(offsets, 500)).toBe(2)
    expect(R.countAt(offsets, 10_000)).toBe(3)
  })
})

describe('replay player', () => {
  const data: ReplayData = { events: [ev(1, 0), ev(2, 500), ev(3, 60_000), ev(4, 61_000)], maxGapMs: 2000 }

  it('plays at the chosen speed and stops at the end', () => {
    let now = 0
    const p = new R.Player(data, () => now)
    expect(p.count()).toBe(1)
    p.play()
    now = 1000
    expect(p.count()).toBe(2)
    p.setSpeed(4)
    now = 1500 // 1000 + 500 ms at ×4
    expect(p.now()).toBe(3000)
    expect(p.count()).toBe(3)
    now = 10_000
    expect(p.count()).toBe(4)
    expect(p.playing).toBe(false)
    expect(p.now()).toBe(p.duration)
  })

  it('holds still when paused and steps from event to event', () => {
    let now = 0
    const p = new R.Player(data, () => now)
    p.play()
    now = 600
    p.pause()
    now = 5000
    expect(p.count()).toBe(2)
    p.seekEvent(4)
    expect(p.count()).toBe(4)
    p.seekEvent(p.count() - 1)
    expect(p.count()).toBe(3)
    p.seekEvent(0)
    expect(p.count()).toBe(1)
    // Play again from the end starts over.
    p.seekEvent(4)
    p.play()
    expect(p.count()).toBe(1)
  })
})

describe('replay state', () => {
  it('builds the board, the desks and the origins from the events shown only', () => {
    const data: ReplayData = {
      tickets: [{ id: 'T-1', title: 'Fix wordCount' }],
      events: [
        ev(1, 0, { type: 'TicketCreated', ticket: 'T-1' }),
        ev(2, 10, { type: 'TicketStarted', ticket: 'T-1', agent: 'chris' }),
        ev(3, 20, { type: 'ActionExecuted', agent: 'chris', ran: true }),
        ev(4, 30, { type: 'AgentSessionEnded', origin: 'SIMULATED', cost: { usd: 0.0123, basis: 'estimated' } }),
        ev(5, 40, { type: 'TicketCompleted', ticket: 'T-1' }),
      ],
    }
    const before = R.stateAt(data, 2)
    expect(before.tickets).toEqual([{ id: 'T-1', title: 'Fix wordCount', status: 'inProgress' }])
    expect(before.cost).toBeNull()
    expect(before.origins).toEqual({ REAL: 2, SIMULATED: 0 })

    const after = R.stateAt(data, 5)
    expect(after.tickets[0].status).toBe('done')
    expect(after.agents.chris.last.seq).toBe(3)
    expect(after.agents.chris.ran).toBe(true)
    expect(after.origins).toEqual({ REAL: 4, SIMULATED: 1 })
    expect(after.cost).toBeCloseTo(0.0123)
    expect(after.costs).toBe(1)
  })

  it('writes the screen’s words and leaves an unknown one visible', () => {
    const data: ReplayData = { events: [], labels: { event: 'Event {n} of {total}' } }
    expect(R.label(data, 'event', { n: 2, total: 5 })).toBe('Event 2 of 5')
    expect(R.label(data, 'play')).toBe('play')
  })
})
