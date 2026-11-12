import table from '../api/step-text-table.json'
import type { CheckView, StepView, TaskView } from '../api/types'

/**
 * The director's checks in the editor (ADR-0052, increment 3), pure: the step text rule mirrored from the domain's
 * `StepText` to show a problem under its field, the order of the checks within their slot, and the document the save
 * route reads. The daemon still decides: it normalises the texts and refuses what this would refuse.
 */

/** At most this many checks in a workflow: each is one more paid session per ticket. */
export const MAX_CHECKS = 4

/** The texts of a check and their longest length, in code points. */
export const TEXT_MAX = { title: 60, does: 2000, why: 500, handsOver: 500 } as const
export type TextField = keyof typeof TEXT_MAX
export const TEXT_FIELDS = Object.keys(TEXT_MAX) as TextField[]

export interface TextProblem {
  code:
    | 'STEP_TITLE_MISSING'
    | 'STEP_WHY_MISSING'
    | 'STEP_TEXT_MISSING'
    | 'STEP_TEXT_HIDDEN_CHARACTER'
    | 'STEP_TEXT_NOT_NORMALISED'
    | 'STEP_TEXT_TOO_LONG'
  /** The first hidden character, counted in code points from 1; 0 for the other codes. */
  position: number
}

/**
 * The code points a step text may hold, as closed ranges: written by the daemon from its own rule and its JDK's Unicode
 * (StepTextTableContractTest), so this editor and the daemon agree whatever the browser's Unicode. Letters, numbers,
 * punctuation, symbols, spacing marks and the plain space, less the default-ignorable code points and the symbols drawn
 * blank; the line feed is allowed in "what it does" only.
 */
const RANGES: readonly (readonly [number, number])[] = (table as unknown as { ranges: [number, number][] }).ranges

/** Whether the daemon's table holds the code point. */
export function allowed(cp: number): boolean {
  let lo = 0
  let hi = RANGES.length - 1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (cp < RANGES[mid][0]) hi = mid - 1
    else if (cp > RANGES[mid][1]) lo = mid + 1
    else return true
  }
  return false
}

/** The text's problem, as the domain's rule decides it, in the same order; null when it may be saved as it is. */
export function textProblem(field: TextField, text: string): TextProblem | null {
  if (!text.trim()) {
    const code = field === 'title' ? 'STEP_TITLE_MISSING' : field === 'why' ? 'STEP_WHY_MISSING' : 'STEP_TEXT_MISSING'
    return { code, position: 0 }
  }
  if (text.normalize('NFKC') !== text) return { code: 'STEP_TEXT_NOT_NORMALISED', position: 0 }
  let position = 0
  for (const ch of text) {
    position++
    if (!allowed(ch.codePointAt(0)!) && !(ch === '\n' && field === 'does')) return { code: 'STEP_TEXT_HIDDEN_CHARACTER', position }
  }
  if (position > TEXT_MAX[field]) return { code: 'STEP_TEXT_TOO_LONG', position: 0 }
  return null
}

/** Every text problem of a check or a task, by field. */
export function checkProblems(c: Pick<CheckView, TextField>): Partial<Record<TextField, TextProblem>> {
  const out: Partial<Record<TextField, TextProblem>> = {}
  for (const f of TEXT_FIELDS) {
    const p = textProblem(f, c[f])
    if (p) out[f] = p
  }
  return out
}

export const checksOf = (steps: readonly StepView[]): CheckView[] =>
  steps.flatMap((s) => (s.step === 'check' && s.check ? [s.check] : []))

/** A key the workflow does not hold yet, made from the title: a slug of 1 to 40 characters, never shown. */
export function keyFor(title: string, taken: readonly string[], fallback = 'check'): string {
  const base =
    title
      .normalize('NFKD')
      .replace(/\p{M}/gu, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 34)
      .replace(/-+$/, '') || fallback
  let key = base
  for (let n = 2; taken.includes(key); n++) key = `${base}-${n}`
  return key
}

/** A new check's fields: run by a reviewer, after the review, blocking; its texts to write. */
export function blankCheck(): CheckView {
  return { key: '', title: '', does: '', why: '', handsOver: '', slot: 'checks', runBy: 'reviewer', blocks: true }
}

const asStep = (c: CheckView): StepView => ({ step: 'check', maxTickets: null, check: c, task: null })

/** The steps with the checks replaced, in their slot right after the review. */
export function withChecks(steps: readonly StepView[], checks: readonly CheckView[]): StepView[] {
  const rest = steps.filter((s) => s.step !== 'check')
  const at = rest.findIndex((s) => s.step === 'review')
  if (at < 0) return rest
  return [...rest.slice(0, at + 1), ...checks.map(asStep), ...rest.slice(at + 1)]
}

/** The check applied from the dialog: a new one goes last in its slot, with a key of its own; an edited one keeps its key. */
export function applyCheck(steps: readonly StepView[], edited: CheckView, index: number | null): StepView[] {
  const checks = checksOf(steps)
  const texts = Object.fromEntries(TEXT_FIELDS.map((f) => [f, edited[f].normalize('NFKC')]))
  if (index === null) {
    const key = keyFor(edited.title, checks.map((c) => c.key))
    return withChecks(steps, [...checks, { ...edited, ...texts, key }])
  }
  return withChecks(steps, checks.map((c, i) => (i === index ? { ...edited, ...texts, key: c.key } : c)))
}

export function removeCheck(steps: readonly StepView[], index: number): StepView[] {
  return withChecks(steps, checksOf(steps).filter((_, i) => i !== index))
}

/** One place up (-1) or down (+1) within the slot; nothing moves past its edge. */
export function moveCheck(steps: readonly StepView[], index: number, by: -1 | 1): StepView[] {
  const checks = checksOf(steps)
  const to = index + by
  if (to < 0 || to >= checks.length) return [...steps]
  ;[checks[index], checks[to]] = [checks[to], checks[index]]
  return withChecks(steps, checks)
}

/** The steps as the save route reads them: a check's fields at the step's own level, no empty value. */
export function savedSteps(steps: readonly StepView[]): Record<string, unknown>[] {
  return steps.map((s) =>
    s.step === 'check' && s.check
      ? { step: 'check', ...s.check }
      : s.step === 'task' && s.task
        ? { step: 'task', ...s.task }
        : s.maxTickets === null
          ? { step: s.step }
          : { step: s.step, maxTickets: s.maxTickets },
  )
}

/** Production stays while a check reads it or a task before production waits for it (ADR-0052, ADR-0061). */
export const keepsProduction = (steps: readonly StepView[]): boolean =>
  checksOf(steps).length > 0 || tasksOf(steps).some((k) => k.slot === 'beforeProduction')

/** The director's tasks (ADR-0061), in their order. Edited by `workflowTaskEdit.ts`. */
export const tasksOf = (steps: readonly StepView[]): TaskView[] => steps.flatMap((s) => (s.step === 'task' && s.task ? [s.task] : []))

/**
 * The steps with the tasks put back where Floor42's order holds them: before production, or right before the
 * acceptance. A step list without the step a slot hangs on keeps no task of that slot.
 */
export function withTasks(steps: readonly StepView[], tasks: readonly TaskView[]): StepView[] {
  const rest = steps.filter((s) => s.step !== 'task')
  const asTask = (k: TaskView): StepView => ({ step: 'task', maxTickets: null, check: null, task: k })
  const out: StepView[] = []
  for (const s of rest) {
    if (s.step === 'produce') out.push(...tasks.filter((k) => k.slot === 'beforeProduction').map(asTask))
    if (s.step === 'accept') out.push(...tasks.filter((k) => k.slot === 'beforeAcceptance').map(asTask))
    out.push(s)
  }
  return out
}

/** What the plan's approval opens besides the tickets (ADR-0061): the tasks, one session each and a reviewer's more when reviewed. */
export function tasksAdded(pin: { workflow: { steps: StepView[] } | null } | null | undefined): { tasks: number; sessions: number } | null {
  const tasks = tasksOf(pin?.workflow?.steps ?? [])
  return tasks.length ? { tasks: tasks.length, sessions: tasks.length + tasks.filter((k) => k.reviewed).length } : null
}

/**
 * What the plan's approval adds in sessions (ADR-0052): each work ticket is reviewed once and checked once per check of
 * the pinned workflow. Counts, never a cost. Null when the pin holds no check.
 */
export function checksAdded(
  pin: { workflow: { steps: StepView[] } | null } | null | undefined,
  tickets: readonly { kind: string }[],
): { tickets: number; reviews: number; checks: number } | null {
  const per = checksOf(pin?.workflow?.steps ?? []).length
  if (!per) return null
  const work = tickets.filter((x) => x.kind === 'WORK').length
  return { tickets: work, reviews: work, checks: work * per }
}
