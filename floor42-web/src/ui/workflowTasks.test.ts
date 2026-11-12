import { describe, expect, it } from 'vitest'
import type { StepView, TaskView } from '../api/types'
import { keepsProduction, savedSteps, tasksAdded, tasksOf, withTasks } from './workflowChecks'
import { problem, stepLine, toggle } from './workflows'

const step = (s: StepView['step']): StepView => ({ step: s, maxTickets: null, check: null, task: null })
const task = (key: string, slot: TaskView['slot'], reviewed = false): TaskView => ({
  key,
  title: `Step ${key}`,
  does: 'Do it',
  why: 'Because',
  handsOver: 'A report',
  slot,
  runBy: reviewed ? 'reviewer' : 'researcher',
  reviewed,
})
const base = (): StepView[] => ['approval', 'produce', 'review', 'accept'].map((s) => step(s as StepView['step']))

describe('the director tasks (ADR-0061)', () => {
  it('puts each task in its slot: before production, or right before the acceptance', () => {
    const out = withTasks(base(), [task('b', 'beforeAcceptance', true), task('a', 'beforeProduction')])
    expect(out.map((s) => s.step)).toEqual(['approval', 'task', 'produce', 'review', 'task', 'accept'])
    expect(tasksOf(out).map((k) => k.key)).toEqual(['a', 'b'])
  })

  it('keeps the tasks when a step is toggled, and a research workflow needs nothing else', () => {
    const steps = withTasks(base(), [task('a', 'beforeAcceptance')])
    expect(toggle(steps, 'retro').map((s) => s.step)).toEqual(['approval', 'produce', 'review', 'task', 'accept', 'retro'])
    const research = withTasks([step('approval'), step('accept')], [task('a', 'beforeAcceptance')])
    expect(problem(research)).toBeNull()
    expect(problem([step('approval'), step('accept')])).not.toBeNull()
  })

  it('sends a task as the route reads it, and shows its title in the line', () => {
    const steps = withTasks(base(), [task('a', 'beforeAcceptance', true)])
    const sent = savedSteps(steps).find((s) => s.step === 'task')
    expect(sent).toMatchObject({ step: 'task', key: 'a', slot: 'beforeAcceptance', runBy: 'reviewer', reviewed: true })
    expect(stepLine({ name: 'x', steps, mode: 'loop' }, (k) => String(k))).toContain('Step a')
  })

  it('locks production while a task before production waits for it, so toggling cannot drop the task', () => {
    const steps = withTasks(base(), [task('a', 'beforeProduction')])
    expect(keepsProduction(steps)).toBe(true)
    expect(keepsProduction(withTasks(base(), [task('b', 'beforeAcceptance')]))).toBe(false)
    expect(tasksOf(toggle(toggle(steps, 'retro'), 'retro')).map((k) => k.key)).toEqual(['a'])
  })

  it('counts the sessions the approval adds: one per task, one more when it is reviewed', () => {
    const steps = withTasks(base(), [task('a', 'beforeAcceptance', true), task('b', 'beforeProduction')])
    expect(tasksAdded({ workflow: { steps } })).toEqual({ tasks: 2, sessions: 3 })
    expect(tasksAdded({ workflow: { steps: base() } })).toBeNull()
    expect(tasksAdded(null)).toBeNull()
  })
})
