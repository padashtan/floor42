import { describe, expect, it } from 'vitest'
import type { StepView, WorkflowView } from '../api/types'
import { en } from '../i18n/en'
import { fresh, problem, stepLine, toggle, withMaxTickets } from './workflows'

const builtIn: WorkflowView = {
  name: 'built-in',
  mode: 'loop',
  steps: ['plan', 'approval', 'committee', 'produce', 'review', 'accept', 'retro'].map((step) => ({
    step: step as StepView['step'],
    maxTickets: null,
    check: null,
    task: null,
  })),
}
const t = (key: string, params: Record<string, string | number> = {}) =>
  (en as Record<string, string>)[key].replace(/\{(\w+)\}/g, (_, k) => String(params[k]))
const kinds = (steps: StepView[]) => steps.map((s) => s.step)

describe('the workflow editor', () => {
  it('keeps Floor42 order and the approval and acceptance whatever is toggled', () => {
    let steps = fresh(builtIn)
    steps = toggle(steps, 'committee')
    steps = toggle(steps, 'approval')
    steps = toggle(steps, 'accept')
    expect(kinds(steps)).toEqual(['plan', 'approval', 'produce', 'review', 'accept', 'retro'])
    expect(kinds(toggle(steps, 'committee'))).toEqual(kinds(builtIn.steps))
  })

  it('takes production and its review out together, and brings them back together', () => {
    const decide = toggle(fresh(builtIn), 'produce')
    expect(kinds(decide)).toEqual(['plan', 'approval', 'committee', 'accept', 'retro'])
    expect(kinds(toggle(decide, 'produce'))).toEqual(kinds(builtIn.steps))
  })

  it('names what the daemon would refuse: a workflow that neither produces nor decides', () => {
    expect(problem(fresh(builtIn))).toBeNull()
    expect(problem(toggle(toggle(fresh(builtIn), 'produce'), 'committee'))).toBe('workflows.problemNothing')
  })

  it('bounds the plan limit and keeps it when other steps change', () => {
    let steps = withMaxTickets(fresh(builtIn), 3)
    expect(steps[0].maxTickets).toBe(3)
    steps = toggle(steps, 'retro')
    expect(steps[0].maxTickets).toBe(3)
    expect(withMaxTickets(steps, 40)[0].maxTickets).toBe(8)
    expect(withMaxTickets(steps, 0)[0].maxTickets).toBe(1)
    expect(withMaxTickets(steps, null)[0].maxTickets).toBeNull()
  })

  it('writes the steps in words, the plan limit only when it is not the default', () => {
    expect(stepLine(builtIn, t)).toBe('Plan → Approval → Committee → Production → Review → Acceptance → Retrospective')
    expect(stepLine({ name: 'x', steps: withMaxTickets(fresh(builtIn), 2), mode: 'loop' }, t)).toMatch(/^Plan \(2 tickets at most\) → /)
  })
})
