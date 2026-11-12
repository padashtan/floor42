import { describe, expect, it } from 'vitest'
import type { MissionProgress, ProgressItem, StallView } from '../api/types'
import { badgedTitle, countdown, newWaits, stuckAgents, totals, stallNeedsYou, waited, waitedFor, waitKey } from './waits'

const at = Date.parse('2026-10-31T10:00:00Z')

function item(kind: ProgressItem['kind'], extra: Partial<ProgressItem> = {}): ProgressItem {
  return {
    kind,
    ticket: null,
    agent: null,
    question: null,
    text: null,
    status: null,
    count: null,
    since: null,
    runningSeconds: null,
    deadline: null,
    ...extra,
  }
}

function stall(state: StallView['state'], agent: string | null = null): StallView {
  return { state, reason: null, work: null, ticket: null, agent, elsewhere: null, since: null, until: null }
}

function mission(id: string, forYou: ProgressItem[], s: StallView): MissionProgress {
  return {
    mission: id,
    title: id,
    status: 'IN_PROGRESS',
    ticketsDone: 0,
    ticketsAll: 1,
    running: [],
    forYou,
    stuck: s.state === 'STUCK' ? [item('STUCK', { agent: s.agent })] : [],
    stall: s,
  }
}

describe('timers on what waits for the director', () => {
  it('says how long a wait has lasted, from seconds to days', () => {
    expect(waited(45_000)).toBe('45 s')
    expect(waited(12 * 60_000 + 59_000)).toBe('12 min')
    expect(waited(2 * 3600_000 + 5 * 60_000)).toBe('2 h 05')
    expect(waited(3 * 86400_000 + 4 * 3600_000)).toBe('3 d 4 h')
    expect(waited(-1)).toBe('—')
  })

  it('counts down to an ask_director question being withdrawn, and stops at zero', () => {
    const deadline = new Date(at + 30 * 60_000).toISOString()
    expect(countdown(deadline, at)).toBe('30:00')
    expect(countdown(deadline, at + 1000)).toBe('29:59')
    expect(countdown(new Date(at + 3 * 3600_000 + 5_000).toISOString(), at)).toBe('3:00:05')
    expect(countdown(deadline, at + 31 * 60_000)).toBe('0:00')
    expect(countdown(null, at)).toBeNull()
  })

  it('times an item from the moment its wait began, and only when the server said when', () => {
    expect(waitedFor(item('QUESTION', { since: new Date(at - 90_000).toISOString() }), at)).toBe('1 min')
    expect(waitedFor(item('APPROVE_PLAN'), at)).toBeNull()
  })
})

describe('badge, title and notifications', () => {
  const missions = [
    mission('M-1', [item('QUESTION', { question: 'Q-1' }), item('WORKSHOP', { agent: 'chris' })], stall('DUE')),
    mission('M-2', [], stall('STUCK', 'ada')),
    mission('M-3', [item('APPROVE_PLAN')], stall('WAITING', 'chris')),
  ]

  it('counts the waits and the stuck missions', () => {
    expect(totals(missions)).toEqual({ forYou: 3, stuck: 1 })
  })

  it('puts the count in front of the tab title, once', () => {
    expect(badgedTitle('Floor42', 4)).toBe('(4) Floor42')
    expect(badgedTitle('(4) Floor42', 2)).toBe('(2) Floor42')
    expect(badgedTitle('(2) Floor42', 0)).toBe('Floor42')
  })

  it('names the agents of stuck missions only, for the "?" in the scene', () => {
    expect(stuckAgents(missions)).toEqual(['ada'])
  })

  it('gives each wait one tag, the same in every tab', () => {
    expect(waitKey('M-1', item('QUESTION', { question: 'Q-1' }))).toBe('floor42:M-1:QUESTION:Q-1')
    expect(waitKey('M-1', item('WORKSHOP', { agent: 'chris' }))).toBe('floor42:M-1:WORKSHOP:chris')
  })

  it('notifies a wait once, and again only after it was settled and came back', () => {
    const seen = new Set<string>()
    expect(newWaits(missions, seen).map((f) => f.key)).toHaveLength(4)
    expect(newWaits(missions, seen)).toEqual([])
    const settled = [mission('M-1', [item('QUESTION', { question: 'Q-1' })], stall('DUE'))]
    expect(newWaits(settled, seen)).toEqual([])
    expect(newWaits(missions, seen).map((f) => f.key)).toEqual([
      'floor42:M-1:WORKSHOP:chris',
      'floor42:M-2:STUCK:ada',
      'floor42:M-3:APPROVE_PLAN:',
    ])
  })
})

describe('stallNeedsYou', () => {
  it('is true for the finished work held in the workshop, for the director to accept or reject', () => {
    expect(stallNeedsYou({ ...stall('WAITING', 'ada'), reason: 'HELD_PATCH' })).toBe(true)
  })

  it('is false for every other wait and for no stall', () => {
    expect(stallNeedsYou({ ...stall('WAITING'), reason: 'AGENT_BUSY' })).toBe(false)
    expect(stallNeedsYou({ ...stall('WAITING'), reason: 'QUESTION' })).toBe(false)
    expect(stallNeedsYou(stall('RUNNING'))).toBe(false)
    expect(stallNeedsYou(undefined)).toBe(false)
  })
})
