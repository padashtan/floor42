import { beforeAll, describe, expect, it } from 'vitest'
import { setLang } from '../i18n'
import type { MissionProgress, ProgressItem, QuestionView } from '../api/types'
import { CATEGORIES, categoryOf, goLabel, inCategory, todoRows, uncommittedRow } from './todo'

beforeAll(() => setLang('en'))

const now = Date.parse('2026-11-03T12:00:00Z')
const ago = (min: number) => new Date(now - min * 60_000).toISOString()

function item(kind: ProgressItem['kind'], extra: Partial<ProgressItem> = {}): ProgressItem {
  return { kind, ticket: null, agent: null, question: null, text: null, status: null, count: null, since: null, runningSeconds: null, deadline: null, ...extra }
}

function mission(id: string, forYou: ProgressItem[], stuck: ProgressItem[] = []): MissionProgress {
  const stall = { state: 'IDLE', reason: null, work: null, ticket: null, agent: null, elsewhere: null, since: null, until: null } as const
  return { mission: id, title: id, status: 'IN_PROGRESS', ticketsDone: 0, ticketsAll: 1, running: [], forYou, stuck, stall }
}

function question(id: string, kind: QuestionView['kind'], extra: Partial<QuestionView> = {}): QuestionView {
  return {
    id, mission: 'M-1', kind, askedBy: 'ada', subject: `subject ${id}`, context: null, options: [], recommendation: null,
    criticality: 'LOW', blocking: true, ticket: null, action: null, status: 'OPEN', answer: null, askedAt: ago(5),
    phraseCode: null, phraseParams: null, askAgain: [], contextCode: null, contextParams: null,
    causeCode: null, causeParams: null, causeBy: null, writesAsked: null, writesNow: null, askBack: { rounds: 0, possible: false, text: null }, ...extra,
  }
}

describe('the director to-do list', () => {
  it('sorts each open question into its category by kind', () => {
    expect(categoryOf({ kind: 'ACTION_APPROVAL' })).toBe('approvals')
    expect(categoryOf({ kind: 'COMMITTEE_ARBITRATION' })).toBe('decisions')
    expect(categoryOf({ kind: 'BUDGET_REACHED' })).toBe('alerts')
    expect(categoryOf({ kind: 'PLAN_LIMIT' })).toBe('alerts')
    for (const kind of ['OPEN', 'CLARIFICATION', 'REVIEW_ESCALATION', 'STAFFING'] as const) expect(categoryOf({ kind })).toBe('questions')
  })

  it('lists a waiting plan, a held patch and an acceptance as approvals, each leading to its place', () => {
    const rows = todoRows(
      [
        mission('M-1', [
          item('APPROVE_PLAN', { since: ago(3) }),
          item('WORKSHOP', { agent: 'ada', count: 2, since: ago(2) }),
          item('CLOSE_MISSION', { since: ago(1) }),
        ]),
      ],
      [],
      now,
    )
    expect(rows.map((r) => [r.category, r.go.panel])).toEqual([
      ['approvals', 'report'],
      ['approvals', 'workshop'],
      ['approvals', 'plan'],
    ])
    expect(rows[0]!.go).toEqual({ panel: 'report', mission: 'M-1' })
  })

  it('takes agent questions from the open questions only, never twice', () => {
    const rows = todoRows(
      [mission('M-1', [item('QUESTION', { question: 'Q-1', agent: 'ada' })])],
      [question('Q-1', 'OPEN'), question('Q-2', 'ACTION_APPROVAL'), question('Q-3', 'COMMITTEE_ARBITRATION'), question('Q-4', 'OPEN', { status: 'ANSWERED' })],
      now,
    )
    expect(rows.map((r) => r.key).sort()).toEqual(['q:Q-1', 'q:Q-2', 'q:Q-3'])
    expect(rows.find((r) => r.key === 'q:Q-2')!.go).toEqual({ panel: 'desk', question: 'Q-2' })
  })

  it('puts stalls under alerts and orders every row newest first, undated last', () => {
    const rows = todoRows(
      [mission('M-1', [item('APPROVE_PLAN')], [item('STUCK', { agent: 'bob', since: ago(30) })])],
      [question('Q-1', 'OPEN', { askedAt: ago(10) }), question('Q-2', 'BUDGET_REACHED', { askedAt: ago(2) })],
      now,
    )
    expect(rows.map((r) => r.key.split(':')[0] + ':' + r.category)).toEqual(['q:alerts', 'q:questions', 's:alerts', 'p:approvals'])
    expect(inCategory(rows, 'alerts')).toHaveLength(2)
    expect(CATEGORIES.map((c) => inCategory(rows, c).length)).toEqual([1, 1, 2, 0])
  })

  it('is empty when nothing waits', () => {
    expect(todoRows([], [], now)).toEqual([])
    expect(todoRows([mission('M-1', [item('SESSION')])], [], now)).toEqual([])
  })

  it('a session risk is an alert row that proposes a quarantine', () => {
    const risk = item('SESSION_RISK', { agent: 'ada', ticket: 'T-2', status: 'QUARANTINE', text: 'POLICY_DENIED, BURST', since: ago(1) })
    const rows = todoRows([mission('M-1', [], [risk])], [], now)
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ category: 'alerts', key: `r:M-1:ada:T-2:${ago(1)}`, go: { panel: 'quarantine', mission: 'M-1', agent: 'ada' } })
    expect(goLabel(rows[0]!)).toBe('todo.go.quarantine')
    expect(rows[0]!.text).toContain('three refusals in five minutes')
    expect(rows[0]!.text).toContain('(quarantine)')
    expect(rows[0]!.text).not.toContain('BURST')
  })

  it('words the button after the place it opens', () => {
    const row = (category: Parameters<typeof goLabel>[0]['category'], go: Parameters<typeof goLabel>[0]['go']) => goLabel({ category, go })
    expect(row('approvals', { panel: 'desk', question: 'Q' })).toBe('todo.go.approve')
    expect(row('questions', { panel: 'desk', question: 'Q' })).toBe('todo.go.answer')
    expect(row('decisions', { panel: 'desk', question: 'Q' })).toBe('todo.go.decide')
    expect(row('approvals', { panel: 'board', mission: 'M-1' })).toBe('todo.go.board')
    expect(row('approvals', { panel: 'plan', mission: 'M-1' })).toBe('todo.go.approve')
    expect(row('alerts', { panel: 'progress' })).toBe('todo.go.progress')
  })

  describe('work left uncommitted in a mission waiting for acceptance', () => {
    const accept = todoRows([mission('M-1', [item('CLOSE_MISSION', { since: ago(1) })])], [], now)[0]!
    const wt = (state: 'CLEAN' | 'DIRTY' | 'UNKNOWN', count = 0) => ({ agent: 'ada', path: 'p', state, reason: null, count, paths: [] })

    it('raises an alert with the count of files, and never for a clean mission', () => {
      expect(uncommittedRow(accept, { clean: false, token: 't', worktrees: [wt('DIRTY', 2), wt('DIRTY', 1), wt('CLEAN')] })).toMatchObject({
        category: 'alerts', key: 'u:M-1', go: { panel: 'report', mission: 'M-1' }, text: '3 file(s) not committed in the worktrees',
      })
      expect(uncommittedRow(accept, { clean: true, token: 't', worktrees: [wt('CLEAN')] })).toBeNull()
      expect(uncommittedRow(accept, null)).toBeNull()
    })

    it('says it cannot tell when the check failed or git did not answer, never clean', () => {
      expect(uncommittedRow(accept, 'failed')!.text).toContain('cannot tell')
      expect(uncommittedRow(accept, { clean: false, token: 't', worktrees: [wt('UNKNOWN')] })!.text).toContain('cannot tell')
    })
  })
})
