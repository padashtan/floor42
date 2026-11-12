import { describe, expect, it } from 'vitest'
import type { CommitteeSummaryView, MissionView, TicketView } from '../api/types'
import {
  MAX_OBJECTIONS,
  MAX_TEXT,
  NEW_WORK,
  checksOf,
  heldFor,
  latestPerProducer,
  nextTarget,
  objectable,
  ready,
  sessionCounts,
  toRequest,
} from './objections'

const ticket = (id: string, assignee: string, endedAt: string, status: TicketView['status'] = 'DONE', kind = 'WORK'): TicketView =>
  ({ id, assignee, status, kind, title: id, endedAt }) as TicketView

const board = [
  ticket('T-1', 'chris', '2026-11-04T09:00:00Z'),
  ticket('T-2', 'dehren', '2026-11-04T09:10:00Z'),
  ticket('T-3', 'chris', '2026-11-04T09:20:00Z'),
  ticket('T-4', 'chris', '2026-11-04T09:30:00Z', 'CANCELLED'),
]
const mission = (tickets: TicketView[], committees: CommitteeSummaryView[] = []) => ({ tickets, committees }) as Pick<MissionView, 'tickets' | 'committees'>

describe('objections at acceptance (ADR-0060)', () => {
  it('offers the done work tickets only, and not a decision the director already ruled on', () => {
    const ruled = { ticket: 'T-2', stage: 'ADOPTED' } as CommitteeSummaryView
    const open = { ticket: 'T-3', stage: 'DEBATE' } as CommitteeSummaryView
    const all = [...board, ticket('T-5', 'geoffrey', '', 'IN_REVIEW'), ticket('T-6', 'geoffrey', '', 'DONE', 'PLANNING')]
    expect(objectable(all).map((x) => x.id)).toEqual(['T-1', 'T-2', 'T-3'])
    expect(objectable(all, [ruled, open]).map((x) => x.id)).toEqual(['T-1', 'T-3'])
  })

  it('starts each row on the latest done ticket of a producer, one producer after the other', () => {
    expect(latestPerProducer(board)).toEqual(['T-3', 'T-2'])
    const rows = [{ target: nextTarget(mission(board), []), text: 'a' }]
    expect(rows[0].target).toBe('T-3')
    expect(nextTarget(mission(board), rows)).toBe('T-2')
    rows.push({ target: 'T-2', text: 'b' })
    expect(nextTarget(mission(board), rows)).toBe('T-3')
    expect(nextTarget(mission([]), [])).toBe(NEW_WORK)
  })

  it('takes the ticket that ended last, not the last on the board: an older ticket reopened and done again', () => {
    const again = [
      ticket('T-1', 'chris', '2026-11-04T11:00:00Z'),
      ticket('T-3', 'chris', '2026-11-04T09:20:00Z'),
    ]
    expect(latestPerProducer(again)).toEqual(['T-1'])
  })

  it('counts the sessions a round starts: a ticket reopened once, each new work, a review and each check after', () => {
    const rows = [
      { target: 'T-3', text: 'a' },
      { target: 'T-3', text: 'b' },
      { target: NEW_WORK, text: 'c' },
      { target: NEW_WORK, text: 'd' },
    ]
    expect(sessionCounts(rows)).toEqual({ producers: 3, reviews: 3 })
    expect(sessionCounts(rows, 2)).toEqual({ producers: 3, reviews: 9 })
  })

  it('counts the checks of the pinned workflow', () => {
    const step = (check: boolean) => ({ step: 'REVIEW', maxTickets: null, check: check ? {} : null })
    expect(checksOf({ workflow: { workflow: { steps: [step(false), step(true), step(true)] } } } as unknown as MissionView)).toBe(2)
    expect(checksOf({ workflow: { workflow: null } } as unknown as MissionView)).toBe(0)
  })

  it('names the held developers only when a row asks for new work', () => {
    const held = [{ agent: 'chris', ticket: 'T-1' }]
    expect(heldFor([{ target: 'T-1', text: 'a' }], held)).toEqual([])
    expect(heldFor([{ target: 'T-1', text: 'a' }, { target: NEW_WORK, text: 'b' }], held)).toEqual(held)
  })

  it('sends new work without a ticket, texts trimmed, and holds the daemon limits', () => {
    expect(toRequest([{ target: NEW_WORK, text: ' x ' }, { target: 'T-3', text: 'y' }])).toEqual([
      { ticket: null, text: 'x' },
      { ticket: 'T-3', text: 'y' },
    ])
    expect(ready([])).toBe(false)
    expect(ready([{ target: 'T-3', text: '  ' }])).toBe(false)
    expect(ready([{ target: 'T-3', text: 'x'.repeat(MAX_TEXT + 1) }])).toBe(false)
    expect(ready([{ target: 'T-3', text: 'x'.repeat(MAX_TEXT) }])).toBe(true)
    expect(ready(Array.from({ length: MAX_OBJECTIONS }, () => ({ target: 'T-3', text: 'x' })))).toBe(true)
    expect(ready(Array.from({ length: MAX_OBJECTIONS + 1 }, () => ({ target: 'T-3', text: 'x' })))).toBe(false)
  })
})
