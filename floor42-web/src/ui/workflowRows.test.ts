import { describe, expect, it } from 'vitest'
import type { MissionWorkflowView, StepView } from '../api/types'
import { en } from '../i18n/en'
import { fr } from '../i18n/fr'
import { changed, followsKey, nameProblem, problemsOf, refusalKey, rowsOf, selectionId } from './workflowRows'
import { toggle } from './workflows'

const steps = (...kinds: StepView['step'][]): StepView[] => kinds.map((step) => ({ step, maxTickets: null, check: null, task: null }))
const full = steps('plan', 'approval', 'committee', 'produce', 'review', 'accept', 'retro')

describe('the workflow timeline rules', () => {
  it('locks approval, acceptance and the review, and nothing else', () => {
    const locked = rowsOf(full, true).filter((r) => r.locked).map((r) => r.kind)
    expect(locked).toEqual(['approval', 'review', 'accept'])
  })

  it('draws the left-out steps only when editing, and the review only with production', () => {
    const noProduction = toggle(full, 'produce')
    expect(rowsOf(noProduction, false).map((r) => r.kind)).toEqual(['plan', 'approval', 'committee', 'accept', 'retro'])
    expect(rowsOf(noProduction, true).map((r) => r.kind)).toEqual(['plan', 'approval', 'committee', 'produce', 'accept', 'retro'])
    expect(rowsOf(noProduction, true).find((r) => r.kind === 'produce')?.present).toBe(false)
  })

  it('puts each problem under its row, as catalogue keys that exist in both languages', () => {
    const none = toggle(toggle(full, 'produce'), 'committee')
    const all = [...problemsOf(none, '', false), ...problemsOf(full, "bad/name", true)]
    expect(all.map((p) => [p.row, p.key])).toEqual([
      ['name', 'workflows.nameMissing'],
      ['produce', 'workflows.problemNothing'],
      ['name', 'workflows.nameChars'],
    ])
    for (const key of [...all.map((p) => p.key), refusalKey('WORKFLOW_EXISTS'), refusalKey('INVALID_WORKFLOW'), refusalKey('x')]) {
      expect((en as Record<string, string>)[key], key).toBeTruthy()
      expect((fr as Record<string, string>)[key], key).toBeTruthy()
    }
  })

  it('mirrors the daemon on names', () => {
    expect(nameProblem('Light read', false)).toBeNull()
    expect(nameProblem('Light read', true)).toBe('workflows.nameTaken')
    expect(nameProblem(' Built-In ', false)).toBe('workflows.nameBuiltIn')
    expect(nameProblem('x'.repeat(61), false)).toBe('workflows.nameTooLong')
    expect(nameProblem("Revue d'équipe", false)).toBeNull()
  })

  it('translates a refusal by its code, never by the daemon sentence', () => {
    expect(refusalKey('WORKFLOW_EXISTS')).toBe('workflows.nameTaken')
    expect(refusalKey('HTTP_500')).toBe('workflows.err.generic')
  })

  it('tells a changed draft from an untouched one', () => {
    expect(changed({ name: 'a', steps: full }, { name: 'a', steps: full.map((s) => ({ ...s })) })).toBe(false)
    expect(changed({ name: 'a', steps: full }, { name: 'a', steps: toggle(full, 'retro') })).toBe(true)
    expect(changed({ name: 'b', steps: full }, { name: 'a', steps: full })).toBe(true)
  })

  it('tells which version a mission follows, a copy, or Floor42 own line', () => {
    const wf = (name: string) => ({ name, steps: full, mode: 'loop' })
    const mission = (over: Partial<MissionWorkflowView>): MissionWorkflowView => ({
      pinned: true,
      workflow: wf('Light'),
      schemaVersion: null,
      refusal: null,
      follows: null,
      ...over,
    })
    expect(followsKey(mission({ follows: { name: 'Light', version: 2, removed: false } }))).toBe('workflows.followsVersion')
    expect(followsKey(mission({ follows: { name: 'Light', version: 2, removed: true } }))).toBe('workflows.followsRemoved')
    expect(followsKey(mission({}))).toBe('workflows.followsCopy')
    expect(followsKey(mission({ workflow: wf('built-in') }))).toBe('workflows.followsPinned')
    expect(followsKey(mission({ pinned: false, workflow: wf('built-in') }))).toBe('workflows.followsBuiltIn')
  })

  it('keeps a template and a company workflow of the same name apart in the library', () => {
    expect(selectionId({ kind: 'template', key: 'decision' })).toBe('t:decision')
    expect(selectionId({ kind: 'company', name: 'decision' })).toBe('c:decision')
  })
})
