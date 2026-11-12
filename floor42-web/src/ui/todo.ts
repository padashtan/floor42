import type { MissionProgress, ProgressItem, QuestionView, UncommittedCheck } from '../api/types'
import { t } from '../i18n'
import { questionText } from '../i18n/events'
import { itemText } from '../state/waits'

/**
 * The director's to-do list: everything that waits for the director, in four categories, read from what the page
 * already holds (the progress of every mission, the open questions). Nothing is computed twice: a progress QUESTION
 * line is the open question itself and is left to the questions list. Pure functions, tested without a browser.
 *
 * Work left uncommitted is added for the missions that wait for acceptance ({@link uncommittedRow}, read once per mission by
 * the panel). "Blocked" in the away summary is a stalled or suspended mission: the stalls are already the alerts. Not in
 * the list: no question kind says the environment blocks, so none is routed there.
 */
export type TodoCategory = 'approvals' | 'questions' | 'alerts' | 'decisions'

export const CATEGORIES: readonly TodoCategory[] = ['approvals', 'questions', 'alerts', 'decisions']

/** Where a row's button leads: the panel that settles it, with what to show there. */
export type TodoGo =
  | { panel: 'desk'; question: string }
  | { panel: 'board'; mission: string }
  /** The plan's cap and delegated resources are set on the desk's Mission tab, for the mission on the board. */
  | { panel: 'plan'; mission: string }
  | { panel: 'workshop' }
  | { panel: 'report'; mission: string }
  | { panel: 'progress' }
  /** A risky session: the director confirms, then Floor42 revokes, suspends and stops. */
  | { panel: 'quarantine'; mission: string; agent: string }

export interface TodoRow {
  key: string
  category: TodoCategory
  text: string
  mission: string | null
  agent: string | null
  since: string | null
  go: TodoGo
}

/** Which category an open question belongs to, by its kind. */
export function categoryOf(q: Pick<QuestionView, 'kind'>): TodoCategory {
  switch (q.kind) {
    case 'ACTION_APPROVAL':
      return 'approvals'
    case 'COMMITTEE_ARBITRATION':
      return 'decisions'
    case 'BUDGET_REACHED':
    case 'PLAN_LIMIT':
      return 'alerts'
    default:
      return 'questions'
  }
}

export function progressGo(i: ProgressItem, mission: string): TodoGo | null {
  switch (i.kind) {
    case 'APPROVE_PLAN':
      return { panel: 'plan', mission }
    case 'APPROVE_TICKET':
      return { panel: 'board', mission }
    case 'WORKSHOP':
      return { panel: 'workshop' }
    case 'CLOSE_MISSION':
      return { panel: 'report', mission }
    default:
      return null
  }
}

/** Newest first; a row without a date goes last. */
const byNewest = (a: TodoRow, b: TodoRow) => (b.since ? Date.parse(b.since) : -Infinity) - (a.since ? Date.parse(a.since) : -Infinity)

export function todoRows(missions: MissionProgress[], questions: QuestionView[], now: number): TodoRow[] {
  const rows: TodoRow[] = []
  for (const m of missions) {
    for (const i of m.forYou) {
      const go = progressGo(i, m.mission)
      if (go) rows.push({ key: `p:${m.mission}:${i.kind}:${i.ticket ?? i.agent ?? ''}`, category: 'approvals', text: itemText(i, now), mission: m.mission, agent: i.agent, since: i.since, go })
    }
    for (const i of m.stuck) {
      const risk = i.kind === 'SESSION_RISK' && i.agent !== null
      rows.push({
        key: risk ? `r:${m.mission}:${i.agent}:${i.ticket ?? ''}:${i.since}` : `s:${m.mission}:${i.kind}:${i.ticket ?? i.agent ?? ''}`,
        category: 'alerts',
        text: itemText(i, now),
        mission: m.mission,
        agent: i.agent,
        since: i.since,
        go: risk && i.agent !== null ? { panel: 'quarantine', mission: m.mission, agent: i.agent } : { panel: 'progress' },
      })
    }
  }
  for (const q of questions.filter((x) => x.status === 'OPEN')) {
    rows.push({
      key: `q:${q.id}`,
      category: categoryOf(q),
      text: questionText(q.phraseCode, q.phraseParams, 'subject', q.subject),
      mission: q.mission,
      agent: q.askedBy,
      since: q.askedAt,
      go: { panel: 'desk', question: q.id },
    })
  }
  return rows.sort(byNewest)
}

/** The rows of one category, and how many there are. */
export function inCategory(rows: readonly TodoRow[], c: TodoCategory): TodoRow[] {
  return rows.filter((r) => r.category === c)
}

/** Approvals (plans, proposed tickets) of missions other than the board's: the desk's own tabs do not show them. */
export const elsewhere = (rows: readonly TodoRow[], board: string | null): TodoRow[] =>
  rows.filter((r) => r.category === 'approvals' && (r.go.panel === 'plan' || r.go.panel === 'board') && r.mission !== board)

/** The mission's acceptance rows: where uncommitted work is worth checking. */
export const acceptanceRows = (rows: readonly TodoRow[]): TodoRow[] => rows.filter((r) => r.go.panel === 'report')

/**
 * The alert for work left uncommitted in a mission waiting for acceptance: from the check, 'failed' when it could not
 * be read (said so, never shown as clean), null while it loads or when everything is committed.
 */
export function uncommittedRow(accept: TodoRow, check: UncommittedCheck | 'failed' | null | undefined): TodoRow | null {
  if (!check) return null
  const files = check === 'failed' ? 0 : check.worktrees.filter((w) => w.state === 'DIRTY').reduce((n, w) => n + w.count, 0)
  const unknown = check === 'failed' || check.worktrees.some((w) => w.state === 'UNKNOWN')
  if (files === 0 && !unknown) return null
  return {
    key: `u:${accept.mission}`,
    category: 'alerts',
    text: files > 0 ? t('todo.uncommitted', { count: files }) : t('todo.uncommittedUnknown'),
    mission: accept.mission,
    agent: null,
    since: accept.since,
    go: accept.go,
  }
}

/** Both lists as one, newest first. */
export const merged = (a: readonly TodoRow[], b: readonly TodoRow[]): TodoRow[] => [...a, ...b].sort(byNewest)

export type GoKey = `todo.go.${'approve' | 'answer' | 'decide' | 'look' | 'board' | 'workshop' | 'report' | 'progress' | 'quarantine'}`

/** The catalogue key of the button that opens the place to act. */
export function goLabel(row: Pick<TodoRow, 'category' | 'go'>): GoKey {
  switch (row.go.panel) {
    case 'desk':
      return { approvals: 'todo.go.approve', decisions: 'todo.go.decide', alerts: 'todo.go.look', questions: 'todo.go.answer' }[row.category] as GoKey
    case 'plan':
      return 'todo.go.approve'
    case 'board':
      return 'todo.go.board'
    case 'workshop':
      return 'todo.go.workshop'
    case 'report':
      return 'todo.go.report'
    case 'quarantine':
      return 'todo.go.quarantine'
    default:
      return 'todo.go.progress'
  }
}
