import type { StepView, WorkflowStepKind, WorkflowView } from '../api/types'
import type { Key } from '../i18n'
import { checksOf, tasksOf, withChecks, withTasks } from './workflowChecks'

/**
 * The list editor's rules (ADR-0050), pure: steps always stand in Floor42's order, the director's approval and
 * acceptance are always there, and production goes with its review. The daemon checks the same when it saves; these
 * keep the editor from offering what it would refuse.
 */
export const ORDER: readonly WorkflowStepKind[] = ['plan', 'approval', 'committee', 'produce', 'review', 'accept', 'retro']

/** Steps the director cannot take out. */
export const FIXED: readonly WorkflowStepKind[] = ['approval', 'accept']

/** What the director toggles: production and its review come and go together. */
export const OPTIONAL: readonly WorkflowStepKind[] = ['plan', 'committee', 'produce', 'retro']

export const MAX_TICKETS = 8

type Translate = (key: Key, params?: Record<string, string | number>) => string

/** The steps in words: "Plan (3 tickets) → Approval → ...". */
export function stepLine(w: WorkflowView, t: Translate): string {
  return w.steps
    .map((s) =>
      s.step === 'check' && s.check
        ? s.check.title
        : s.step === 'task' && s.task
          ? s.task.title
          : s.step === 'plan' && s.maxTickets !== null && s.maxTickets !== MAX_TICKETS
          ? t('workflows.planAtMost', { count: s.maxTickets })
          : t(`workflows.step.${s.step}` as Key),
    )
    .join(' → ')
}

export function has(steps: readonly StepView[], kind: WorkflowStepKind): boolean {
  return steps.some((s) => s.step === kind)
}

/** The steps with one optional step added or taken out, in Floor42's order; production brings or takes its review. */
export function toggle(steps: readonly StepView[], kind: WorkflowStepKind): StepView[] {
  if (FIXED.includes(kind)) return [...steps]
  const kinds = new Set(steps.map((s) => s.step))
  const on = !kinds.has(kind)
  for (const k of kind === 'produce' ? (['produce', 'review'] as const) : [kind]) {
    if (on) kinds.add(k)
    else kinds.delete(k)
  }
  const kept = ORDER.filter((k) => kinds.has(k)).map((k) => steps.find((s) => s.step === k) ?? { step: k, maxTickets: null, check: null, task: null })
  // The director's checks stay right after the review (ADR-0052); the timeline keeps production while one is there.
  // The director's tasks keep their slots (ADR-0061).
  return withTasks(withChecks(kept, checksOf(steps)), tasksOf(steps))
}

/** The plan step's limit set, 1 to {@link MAX_TICKETS}; null puts back the default. */
export function withMaxTickets(steps: readonly StepView[], value: number | null): StepView[] {
  const n = value === null || Number.isNaN(value) ? null : Math.min(MAX_TICKETS, Math.max(1, Math.round(value)))
  return steps.map((s) => (s.step === 'plan' ? { ...s, maxTickets: n } : s))
}

/** Why the daemon would refuse these steps, as a catalogue key; null when it would not. */
export function problem(steps: readonly StepView[]): Key | null {
  if (!has(steps, 'produce') && !has(steps, 'committee') && !has(steps, 'task')) return 'workflows.problemNothing'
  return null
}

/** A new workflow's steps: Floor42's own, which the director then trims. */
export function fresh(builtIn: WorkflowView): StepView[] {
  return builtIn.steps.map((s) => ({ ...s }))
}
