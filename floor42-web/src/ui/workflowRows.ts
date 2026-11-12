import type { MissionWorkflowView, StepView, WorkflowStepKind } from '../api/types'
import type { Key } from '../i18n'
import { checkProblems, checksOf, MAX_CHECKS, TEXT_FIELDS, TEXT_MAX } from './workflowChecks'
import { taskProblemsOf } from './workflowTaskEdit'
import { has, ORDER, problem } from './workflows'

/**
 * The timeline's rules (ADR-0052, increment 1), pure: which rows stand on the line, which ones are locked and why,
 * and the problems of a draft as catalogue keys. The daemon's refusals are mirrored here to show them under the row
 * they belong to; the daemon still decides.
 */

/** The row a problem sits under: a step, or the workflow's name. */
export type RowKey = WorkflowStepKind | 'name'

export interface Problem {
  row: RowKey
  key: Key
  /** For a check's problem: which check, in the slot's order, and the text's position and longest length. */
  index?: number
  params?: Record<string, number>
}

export interface Row {
  kind: WorkflowStepKind
  present: boolean
  /** Why the director cannot take it out, as a catalogue key; null for a step that can be kept or left out. */
  locked: Key | null
}

const LOCKS: Partial<Record<WorkflowStepKind, Key>> = {
  approval: 'workflows.why.approval',
  accept: 'workflows.why.accept',
  review: 'workflows.why.review',
}

const MAX_NAME = 60
const NAME = /^[\p{Script=Latin}\p{Nd} .'_-]+$/u

/** Names compared as the daemon compares them: NFKC, trimmed, lower case. */
export const nameKey = (s: string): string => s.normalize('NFKC').trim().toLowerCase()

/** The rows to draw: all of Floor42's steps when editing, the kept ones when reading; the review follows production. */
export function rowsOf(steps: readonly StepView[], editing: boolean): Row[] {
  return ORDER.filter((k) => k !== 'review' || has(steps, 'produce'))
    .map((kind) => ({ kind, present: has(steps, kind), locked: LOCKS[kind] ?? null }))
    .filter((r) => editing || r.present)
}

/** Why the daemon would refuse a name, as a catalogue key; null when it would not. `taken`: another workflow has it. */
export function nameProblem(name: string, taken: boolean): Key | null {
  const n = name.normalize('NFKC').trim()
  if (!n) return 'workflows.nameMissing'
  if (n.length > MAX_NAME) return 'workflows.nameTooLong'
  if (!NAME.test(n)) return 'workflows.nameChars'
  if (nameKey(n) === 'built-in') return 'workflows.nameBuiltIn'
  return taken ? 'workflows.nameTaken' : null
}

/** Every problem of a draft, in the order of the line, each with the row it belongs to. */
export function problemsOf(steps: readonly StepView[], name: string, taken: boolean): Problem[] {
  const out: Problem[] = []
  const n = nameProblem(name, taken)
  if (n) out.push({ row: 'name', key: n })
  const p = problem(steps)
  if (p) out.push({ row: 'produce', key: p })
  checksOf(steps).forEach((c, index) => {
    const found = checkProblems(c)
    const first = TEXT_FIELDS.find((f) => found[f])
    if (first) {
      const params = { position: found[first]!.position, max: TEXT_MAX[first] }
      out.push({ row: 'check', key: `workflows.chk.err.${found[first]!.code}` as Key, index, params })
    }
  })
  if (checksOf(steps).length > MAX_CHECKS) out.push({ row: 'check', key: 'workflows.chk.err.TOO_MANY_STEPS' })
  out.push(...taskProblemsOf(steps))
  return out
}

/** The save errors the daemon answers by code, as catalogue keys; any other code gets the generic text. */
export function refusalKey(code: string): Key {
  switch (code) {
    case 'WORKFLOW_EXISTS':
      return 'workflows.nameTaken'
    case 'INVALID_ARGUMENT':
    case 'INVALID_WORKFLOW':
    case 'MISSING_FIELDS':
    case 'MISSING_NAME':
      return 'workflows.err.invalid'
    case 'UNKNOWN_WORKFLOW':
    case 'UNKNOWN_PROPOSAL':
      return 'workflows.err.unknown'
    case 'STEP_TITLE_MISSING':
    case 'STEP_WHY_MISSING':
    case 'STEP_TEXT_MISSING':
    case 'STEP_TEXT_HIDDEN_CHARACTER':
    case 'STEP_TEXT_NOT_NORMALISED':
    case 'STEP_TEXT_TOO_LONG':
    case 'STEP_KEY_INVALID':
    case 'STEP_KEY_TAKEN':
    case 'STEP_RUNNER_NOT_ALLOWED':
    case 'STEP_NEEDS_PRODUCTION':
    case 'TOO_MANY_STEPS':
    case 'WRITING_STEP_UNREVIEWED':
      return `workflows.chk.err.${code}` as Key
    default:
      return 'workflows.err.generic'
  }
}

/** What the library has selected: the built-in workflow, a template by key, a company workflow by name, a proposal by number, a new one. */
export type Selection =
  | { kind: 'builtin' }
  | { kind: 'template'; key: string }
  | { kind: 'company'; name: string }
  | { kind: 'proposal'; number: number }
  | { kind: 'new' }

export const selectionId = (s: Selection): string =>
  s.kind === 'company' ? `c:${s.name}` : s.kind === 'proposal' ? `p:${s.number}` : s.kind === 'template' ? `t:${s.key}` : s.kind

/** Whether the draft differs from what it started from. */
export function changed(
  a: { name: string; steps: readonly StepView[] },
  b: { name: string; steps: readonly StepView[] },
): boolean {
  return a.name !== b.name || JSON.stringify(a.steps) !== JSON.stringify(b.steps)
}

/**
 * How a mission's workflow line starts (ADR-0052): Floor42's own for a mission never pinned, the saved version its pin
 * copied when one matches, a copy of the name when none does, and the plain pinned line for Floor42's own pin.
 */
export function followsKey(w: MissionWorkflowView): Key {
  if (!w.pinned) return 'workflows.followsBuiltIn'
  if (w.follows) return w.follows.removed ? 'workflows.followsRemoved' : 'workflows.followsVersion'
  return w.workflow && nameKey(w.workflow.name) !== 'built-in' ? 'workflows.followsCopy' : 'workflows.followsPinned'
}
