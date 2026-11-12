import { describe, expect, it } from 'vitest'
import type { MeetingView, TurnView } from '../api/types'
import { conversation, defaultCap, moved, oneAtATime, panelView, placeOfTable, presences, roundUnderWay } from './meetingTurns'

const turn = (attendee: string, state: TurnView['state'], at: string | null, answer: string | null = null): TurnView => ({
  attendee,
  name: attendee.toUpperCase(),
  role: 'Developer',
  state,
  answer,
  why: state === 'NOT_HEARD' ? 'the meeting’s cost cap is reached' : null,
  costUsd: at ? 0.0123 : null,
  costBasis: at ? 'ESTIMATED_BY_CLAUDE_CODE' : null,
  at,
})

const meeting = (id: string, open: boolean, mission = 'M-1', extra: Partial<MeetingView> = {}): MeetingView => ({
  id,
  mission,
  missionTitle: 'Ship the release',
  agenda: 'Review the release notes',
  calledAt: '2026-11-01T10:00:00Z',
  closedAt: open ? null : '2026-11-01T11:00:00Z',
  open,
  attendees: [
    { id: 'a1', name: 'A1', status: 'IN_MEETING', finishing: false },
    { id: 'a2', name: 'A2', status: 'IN_MEETING', finishing: false },
  ],
  notes: [],
  place: 'CONFERENCE',
  spentUsdCharged: 0,
  rounds: [],
  ...extra,
})

describe('the meeting panel shows an open meeting whatever happens (ADR-0049)', () => {
  it('an open meeting always shows, with its close button, even when the latest read failed', () => {
    const open = meeting('MT-1', true)
    expect(panelView([open], null, 'M-9')).toEqual({ kind: 'meeting', meeting: open })
    expect(panelView([open], 'HTTP_500: down', 'M-1')).toEqual({ kind: 'meeting', meeting: open })
  })

  it('a failed read shows its error, never the call form; the form only once a read found no open meeting', () => {
    expect(panelView(null, 'HTTP_500: down', 'M-1')).toEqual({ kind: 'error', error: 'HTTP_500: down' })
    expect(panelView(null, null, 'M-1')).toEqual({ kind: 'loading' })
    expect(panelView([meeting('MT-1', false)], null, 'M-1')).toEqual({ kind: 'call' })
  })

  it('prefers the open meeting about the mission shown', () => {
    const other = meeting('MT-1', true, 'M-2')
    const mine = meeting('MT-2', true, 'M-1')
    expect(panelView([other, mine], null, 'M-1')).toEqual({ kind: 'meeting', meeting: mine })
  })
})

describe('the panel’s polls', () => {
  it('skip a read while one is under way, and read again once it has ended', async () => {
    let calls = 0
    let release: () => void = () => {}
    const read = oneAtATime(() => {
      calls++
      return new Promise<void>((r) => (release = r))
    })
    const first = read()
    await read()
    expect(calls, 'the second poll was skipped').toBe(1)
    release()
    await first
    const third = read()
    expect(calls).toBe(2)
    release()
    await third
  })
})

describe('the conversation', () => {
  const m = meeting('MT-1', true, 'M-1', {
    notes: [{ attendee: null, text: 'Be brief', at: '2026-11-01T10:05:00Z' }],
    rounds: [
      {
        number: 1,
        question: null,
        capUsd: 0.5,
        askedAt: '2026-11-01T10:01:00Z',
        turns: [turn('a2', 'DONE', '2026-11-01T10:03:00Z', 'Fine'), turn('a1', 'SPEAKING', null)],
      },
    ],
  })

  it('reads in the order things were said, the turns still to come last', () => {
    expect(conversation(m).map((s) => (s.kind === 'note' ? 'note' : `${s.turn.attendee}:${s.turn.state}`))).toEqual([
      'a2:DONE',
      'note',
      'a1:SPEAKING',
    ])
    expect(roundUnderWay(m)?.number).toBe(1)
    expect(roundUnderWay(meeting('MT-2', true))).toBeNull()
  })

  it('starts the cap above what was charged, moves a speaker, reads the table and the scene’s presences', () => {
    expect(defaultCap(meeting('MT-1', true, 'M-1', { spentUsdCharged: 0.1 }), 2)).toBe(0.6)
    expect(moved(['a', 'b', 'c'], 2, -1)).toEqual(['a', 'c', 'b'])
    expect(moved(['a', 'b'], 0, -1)).toEqual(['a', 'b'])
    expect(placeOfTable('conference-1')).toBe('CONFERENCE')
    expect(placeOfTable('table-1')).toBe('TABLE')
    expect(presences([meeting('MT-1', true), meeting('MT-0', false)])).toEqual([
      { place: 'CONFERENCE', attendees: ['a1', 'a2'] },
    ])
  })
})
