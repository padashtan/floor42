import type { Measure, MissionReport, NextStep, NextStepKind, ReportOutput, ReportWarning } from '../api/types'
import { duration } from './time'

// Pure formatting of the end-of-mission report (ADR-0039). Every figure keeps its basis next to it: a cost estimated by
// Claude Code reads as "≈ … (estimated)", never as a bill; a figure nobody reported reads as such, never as zero.

/** A figure as the screen shows it: the value (null: "not reported") and the key of its basis. */
export interface Shown {
  value: string | null
  basisKey: 'report.basis.MEASURED' | 'report.basis.ESTIMATED' | 'report.basis.REPORTED' | 'report.basis.SIMULATED'
}

export function money(m: Measure): Shown {
  const value =
    m.value === null ? null : `${m.basis === 'ESTIMATED' ? '≈ ' : ''}${m.value.toFixed(m.value < 1 ? 4 : 2)} USD`
  return { value, basisKey: `report.basis.${m.basis}` }
}

export function time(m: Measure): Shown {
  return { value: m.value === null ? null : duration(m.value), basisKey: `report.basis.${m.basis}` }
}

export type Level = 'loud' | 'warn' | 'info'

/** How loud a warning is: what no commit will carry first, then what agents said was not done, then the rest. */
export function levelOf(code: string): Level {
  if (code === 'OUTPUTS_IGNORED' || code === 'OUTPUTS_NOT_COMMITTED' || code === 'WORKTREE_UNREADABLE') return 'loud'
  if (code === 'PRODUCER_PARTIAL' || code.startsWith('REVIEW_') || code === 'CHECKS_FAILED') return 'warn'
  return 'info'
}

const ORDER: Record<Level, number> = { loud: 0, warn: 1, info: 2 }

export interface ShownWarning {
  level: Level
  key: string
  params: Record<string, string | number>
}

/** The warnings, loud ones first (stable within a level), with the catalogue key and parameters of each line. */
export function warnings(list: ReportWarning[]): ShownWarning[] {
  return list
    .map((w, i) => ({ w, i, level: levelOf(w.code) }))
    .sort((a, b) => ORDER[a.level] - ORDER[b.level] || a.i - b.i)
    .map(({ w, level }) => ({
      level,
      key: `report.warn.${w.code.startsWith('REVIEW_') ? 'REVIEW' : w.code}`,
      params: {
        ticket: w.ticket ?? '',
        agent: w.agent ?? '',
        path: w.path ?? '',
        count: w.count ?? 0,
        text: w.text ?? '',
        verdict: w.code.startsWith('REVIEW_') ? w.code.slice('REVIEW_'.length) : '',
      },
    }))
}

/** The worktrees holding work that no merge, push or pull request carries: the end is never silent about them. */
export function atRisk(outputs: ReportOutput[]): ReportOutput[] {
  return outputs.filter((o) => !o.readable || o.uncommittedCount > 0 || o.ignoredCount > 0)
}

/** The next steps in a fixed order, whatever order the server sent. */
const STEPS: NextStepKind[] = ['CLOSE', 'REOPEN', 'PULL_REQUEST', 'MERGE', 'FOLLOW_UP', 'ARCHIVE']
export function nextSteps(list: NextStep[]): NextStep[] {
  return [...list].sort((a, b) => STEPS.indexOf(a.kind) - STEPS.indexOf(b.kind))
}

/** The refusals the report's next steps can meet that the screen phrases itself, in both catalogues. */
export const REFUSAL_CODES = [
  'FORBIDDEN',
  'DIRECTOR_ONLY',
  'INVALID_TRANSITION',
  'MISSION_NOT_FINISHED',
  'ACTIONS_RUNNING',
  'MISSION_NOT_ACTIVE',
  'AGENT_AT_WORK',
  'DECIDED_TICKET',
  'NO_TICKET',
  'SIMULATED_MISSION',
  'NOTHING_ACCEPTED',
  'NO_REPOSITORY',
] as const

/**
 * The catalogue key that says why a step is not available, null when it is: the director never reads a raw
 * `CODE: message` from the server. An unknown code reads as a plain "not possible now".
 */
export function refusalKey(s: NextStep | undefined): `report.refused.${(typeof REFUSAL_CODES)[number] | 'OTHER'}` | null {
  if (!s || s.available) return null
  const code = (REFUSAL_CODES as readonly string[]).includes(s.code ?? '') ? (s.code as (typeof REFUSAL_CODES)[number]) : 'OTHER'
  return `report.refused.${code}`
}

/**
 * Whether the report offers a step at all. A closed or cancelled mission offers no closing, and a reopening only when
 * the domain would allow it: a disabled button explaining that a closed mission cannot be closed says nothing useful.
 */
export function offered(kind: NextStepKind, steps: NextStep[], status: string): boolean {
  if (status !== 'COMPLETED' && status !== 'CANCELLED') return true
  if (kind === 'CLOSE') return false
  if (kind === 'REOPEN') return steps.find((x) => x.kind === kind)?.available === true
  return true
}

/** The one-line summary for the board's delivery block: counts only, each from the report. */
export function headline(r: MissionReport): { done: number; dropped: number; open: number; atRisk: number; warn: number } {
  return {
    done: r.totals.done,
    dropped: r.totals.dropped,
    open: r.totals.open,
    atRisk: atRisk(r.outputs).length,
    warn: r.warnings.filter((w) => levelOf(w.code) === 'warn').length,
  }
}

/** The sections of the end-of-mission panel, in the order they are listed. */
export const REPORT_SECTIONS = ['deliverables', 'proof', 'tickets', 'reviews', 'cost', 'retro', 'next'] as const
export type ReportSectionId = (typeof REPORT_SECTIONS)[number]

/** The short name of a figure's basis, for the tag beside it ("measured", "estimated", "simulated"...). */
export function tagKey(s: Shown): `report.tag.${string}` {
  return s.basisKey.replace('report.basis.', 'report.tag.') as `report.tag.${string}`
}

/**
 * The step the summary points to: closing while the mission is open, then the pull request or the merge when the
 * work is accepted, else a follow-up or the archive. Only a step the report offers and allows is recommended.
 */
export function recommendedStep(steps: NextStep[], status: string): NextStepKind | null {
  const order: NextStepKind[] = ['CLOSE', 'PULL_REQUEST', 'MERGE', 'FOLLOW_UP', 'ARCHIVE']
  return order.find((k) => offered(k, steps, status) && steps.find((s) => s.kind === k)?.available) ?? null
}
