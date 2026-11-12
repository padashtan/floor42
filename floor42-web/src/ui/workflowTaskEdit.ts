import type { StepView, TaskView } from '../api/types'
import type { Key } from '../i18n'
import { checkProblems, checksOf, keyFor, TEXT_FIELDS, TEXT_MAX, tasksOf, withTasks } from './workflowChecks'
import type { Problem } from './workflowRows'

/**
 * The director's tasks in the editor (ADR-0061), pure, like the checks' in `workflowChecks.ts`: a blank task, the
 * add, edit, remove and move within a slot, and the problems of a draft as the daemon would find them. The daemon
 * still decides.
 */

/** At most this many tasks in a workflow: each is a ticket of its own, with a paid session. */
export const MAX_TASKS = 4

/** A new task: a researcher's, before the acceptance, unreviewed; its texts to write. */
export function blankTask(): TaskView {
  return { key: '', title: '', does: '', why: '', handsOver: '', slot: 'beforeAcceptance', runBy: 'researcher', reviewed: false }
}

/** A developer's task writes, so it is always reviewed (the daemon refuses otherwise). */
const settled = (k: TaskView): TaskView => (k.runBy === 'developer' ? { ...k, reviewed: true } : k)

/** The task applied from the dialog: a new one goes last in its slot with a key of its own; an edited one keeps its key. */
export function applyTask(steps: readonly StepView[], edited: TaskView, index: number | null): StepView[] {
  const tasks = tasksOf(steps)
  const texts = Object.fromEntries(TEXT_FIELDS.map((f) => [f, edited[f].normalize('NFKC')]))
  if (index === null) {
    const taken = [...checksOf(steps), ...tasks].map((x) => x.key)
    return withTasks(steps, [...tasks, settled({ ...edited, ...texts, key: keyFor(edited.title, taken, 'task') })])
  }
  return withTasks(steps, tasks.map((k, i) => (i === index ? settled({ ...edited, ...texts, key: k.key }) : k)))
}

export const removeTask = (steps: readonly StepView[], index: number): StepView[] =>
  withTasks(steps, tasksOf(steps).filter((_, i) => i !== index))

/** The index of the nearest task of the same slot on that side; -1 when there is none. */
export function neighbour(tasks: readonly TaskView[], index: number, by: -1 | 1): number {
  for (let i = index + by; i >= 0 && i < tasks.length; i += by) if (tasks[i].slot === tasks[index].slot) return i
  return -1
}

/** One place up (-1) or down (+1) within its slot; nothing moves past the slot's edge. */
export function moveTask(steps: readonly StepView[], index: number, by: -1 | 1): StepView[] {
  const tasks = tasksOf(steps)
  const to = neighbour(tasks, index, by)
  if (to < 0) return [...steps]
  ;[tasks[index], tasks[to]] = [tasks[to], tasks[index]]
  return withTasks(steps, tasks)
}

/** The problems of the tasks of a draft, each under the tasks row. */
export function taskProblemsOf(steps: readonly StepView[]): Problem[] {
  const out: Problem[] = []
  const tasks = tasksOf(steps)
  tasks.forEach((k, index) => {
    const found = checkProblems(k)
    const first = TEXT_FIELDS.find((f) => found[f])
    if (first) {
      const params = { position: found[first]!.position, max: TEXT_MAX[first] }
      out.push({ row: 'task', key: `workflows.chk.err.${found[first]!.code}` as Key, index, params })
    } else if (k.runBy === 'developer' && !k.reviewed) {
      out.push({ row: 'task', key: 'workflows.chk.err.WRITING_STEP_UNREVIEWED', index })
    }
  })
  if (tasks.length > MAX_TASKS) out.push({ row: 'task', key: 'workflows.task.err.TOO_MANY' })
  return out
}
