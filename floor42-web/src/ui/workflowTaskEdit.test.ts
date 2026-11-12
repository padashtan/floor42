import { describe, expect, it } from 'vitest'
import type { StepView, TaskView } from '../api/types'
import { tasksOf, withTasks } from './workflowChecks'
import { problemsOf } from './workflowRows'
import { applyTask, blankTask, MAX_TASKS, moveTask, removeTask } from './workflowTaskEdit'
import { toggle } from './workflows'

const step = (s: StepView['step']): StepView => ({ step: s, maxTickets: null, check: null, task: null })
const base = (): StepView[] => ['approval', 'produce', 'review', 'accept'].map((s) => step(s as StepView['step']))
const task = (title: string, over: Partial<TaskView> = {}): TaskView => ({
  ...blankTask(),
  title,
  does: 'Look it up',
  why: 'We need the facts',
  handsOver: 'A report',
  ...over,
})

describe('the tasks in the editor (ADR-0061)', () => {
  it('adds a task last in its slot with a key of its own, and edits it keeping the key', () => {
    let steps = applyTask(base(), task('Research'), null)
    steps = applyTask(steps, task('Research'), null)
    expect(tasksOf(steps).map((k) => k.key)).toEqual(['research', 'research-2'])
    expect(steps.map((s) => s.step)).toEqual(['approval', 'produce', 'review', 'task', 'task', 'accept'])
    steps = applyTask(steps, { ...tasksOf(steps)[0], title: 'Look around' }, 0)
    expect(tasksOf(steps)[0]).toMatchObject({ key: 'research', title: 'Look around' })
  })

  it('moves a task between slots by editing its slot, and within a slot by up and down', () => {
    let steps = applyTask(base(), task('A'), null)
    steps = applyTask(steps, task('B'), null)
    steps = moveTask(steps, 1, -1)
    expect(tasksOf(steps).map((k) => k.key)).toEqual(['b', 'a'])
    expect(moveTask(steps, 0, -1)).toEqual(steps)
    steps = applyTask(steps, { ...tasksOf(steps)[0], slot: 'beforeProduction' }, 0)
    expect(steps.map((s) => s.step)).toEqual(['approval', 'task', 'produce', 'review', 'task', 'accept'])
    expect(tasksOf(steps).map((k) => [k.key, k.slot])).toEqual([['b', 'beforeProduction'], ['a', 'beforeAcceptance']])
    expect(moveTask(steps, 0, 1)).toEqual(steps)
  })

  it('removes a task, and a developer task is always reviewed', () => {
    let steps = applyTask(base(), task('Write', { runBy: 'developer', reviewed: false }), null)
    expect(tasksOf(steps)[0].reviewed).toBe(true)
    steps = removeTask(steps, 0)
    expect(tasksOf(steps)).toEqual([])
  })

  it('shows the daemon problems under the task: hidden text, unreviewed writer, more than four', () => {
    const bad = withTasks(base(), [task('X', { why: 'a​b' })])
    expect(problemsOf(bad, 'Name', false).find((p) => p.row === 'task')).toMatchObject({ key: 'workflows.chk.err.STEP_TEXT_HIDDEN_CHARACTER', index: 0 })
    const unreviewed = withTasks(base(), [task('X', { runBy: 'developer', reviewed: false })])
    expect(problemsOf(unreviewed, 'Name', false).find((p) => p.row === 'task')?.key).toBe('workflows.chk.err.WRITING_STEP_UNREVIEWED')
    const many = withTasks(base(), Array.from({ length: MAX_TASKS + 1 }, (_, i) => task(`T${i}`, { key: `t${i}` })))
    expect(problemsOf(many, 'Name', false).map((p) => p.key)).toContain('workflows.task.err.TOO_MANY')
  })

  it('keeps the tasks when another step is toggled', () => {
    const steps = applyTask(base(), task('Research'), null)
    expect(tasksOf(toggle(toggle(steps, 'retro'), 'retro')).map((k) => k.key)).toEqual(['research'])
  })
})
