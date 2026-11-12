import { describe, expect, it } from 'vitest'
import type { CheckView, StepView } from '../api/types'
import table from '../api/step-text-table.json'
import { allowed, applyCheck, blankCheck, checksAdded, checksOf, keyFor, moveCheck, removeCheck, savedSteps, textProblem, type TextField } from './workflowChecks'
import { problemsOf } from './workflowRows'
import { toggle } from './workflows'

/** The domain's own fixture of hidden texts (ADR-0052): the editor refuses each one where the reader does. */
// Read from disk: a lone surrogate is a JSON escape Vite's JSON plugin refuses and JSON.parse keeps.
const fs = (await import(/* @vite-ignore */ `node:${'fs'}`)) as { readFileSync: (path: URL, encoding: string) => string }
const hidden = fs.readFileSync(new URL('../../../floor42-domain/src/test/resources/workflow/hidden-step-texts.json', import.meta.url), 'utf-8')
const fixture = JSON.parse(hidden) as {
  cases: { name: string; field: TextField; text: string; code: string; position: number }[]
  accepted: { field: TextField; text: string }[]
}

const step = (s: StepView['step']): StepView => ({ step: s, maxTickets: null, check: null, task: null })
const base = (): StepView[] => ['approval', 'produce', 'review', 'accept'].map((s) => step(s as StepView['step']))
const check = (title: string, blocks = true): CheckView => ({
  ...blankCheck(),
  title,
  does: 'Look for secrets',
  why: 'Our contract requires it',
  handsOver: 'The findings',
  blocks,
})

describe('the step text rule, mirrored from the domain', () => {
  it.each(fixture.cases)('refuses $name with its code and position', (c) => {
    expect(textProblem(c.field, c.text)).toEqual({ code: c.code, position: c.position })
  })

  it.each(fixture.accepted)('accepts a plain $field', (c) => {
    expect(textProblem(c.field, c.text)).toBeNull()
  })

  it('agrees with the table of the daemon at every code point, and refuses what any engine knows as hidden', () => {
    // The table is the daemon's (StepTextTableContractTest); the engine's own categories check it from outside, Cn
    // aside: a code point this browser does not know may be one the daemon's Unicode assigns.
    const ranges = (table as unknown as { ranges: [number, number][] }).ranges
    const inTable = (cp: number) => ranges.some(([a, b]) => cp >= a && cp <= b)
    const hidden = /^[\p{Mn}\p{Me}\p{Cf}\p{Co}\p{Cs}\p{Cc}\p{Zl}\p{Zp}\p{Zs}]$/u
    const wrong: string[] = []
    let r = 0
    for (let cp = 0; cp <= 0x10ffff; cp++) {
      while (r < ranges.length && ranges[r][1] < cp) r++
      const held = r < ranges.length && ranges[r][0] <= cp
      if (held !== allowed(cp)) wrong.push(`table ${cp.toString(16)}`)
      const ch = String.fromCodePoint(cp)
      if (cp !== 0x20 && hidden.test(ch) && held) wrong.push(`hidden ${cp.toString(16)}`)
      const text = `x${ch}y`
      if (text.normalize('NFKC') === text && cp !== 0x0a && (textProblem('why', text) === null) !== held) wrong.push(`rule ${cp.toString(16)}`)
    }
    expect(wrong).toEqual([])
    expect(inTable(0x61)).toBe(true)
    for (const cp of [0x115f, 0x1160, 0x3164, 0xffa0, 0x2800, 0x1d159, 0x180e, 0x2060, 0x1d173]) expect(allowed(cp)).toBe(false)
    expect(textProblem('does', 'x\ny')).toBeNull()
    expect(textProblem('title', 'x\ny')).toEqual({ code: 'STEP_TEXT_HIDDEN_CHARACTER', position: 2 })
  })

  it('names a missing title and reason, and counts length in code points', () => {
    expect(textProblem('title', ' ')?.code).toBe('STEP_TITLE_MISSING')
    expect(textProblem('why', '')?.code).toBe('STEP_WHY_MISSING')
    expect(textProblem('handsOver', '')?.code).toBe('STEP_TEXT_MISSING')
    expect(textProblem('title', '😀'.repeat(60))).toBeNull()
    expect(textProblem('title', 'x'.repeat(61))?.code).toBe('STEP_TEXT_TOO_LONG')
  })
})

describe('checks in their slot', () => {
  it('goes right after the review, with a key of its own, its texts written NFKC', () => {
    let s = applyCheck(base(), check('Security check'), null)
    s = applyCheck(s, { ...check('Security check'), does: 'Café menus' }, null)
    expect(s.map((x) => x.step)).toEqual(['approval', 'produce', 'review', 'check', 'check', 'accept'])
    expect(checksOf(s).map((c) => c.key)).toEqual(['security-check', 'security-check-2'])
    expect(checksOf(s)[1].does).toBe('Café menus')
  })

  it('keeps its key when edited, moves only within its slot, and goes when removed', () => {
    let s = applyCheck(applyCheck(base(), check('One'), null), check('Two', false), null)
    s = applyCheck(s, { ...checksOf(s)[0], title: 'First', key: 'ignored' }, 0)
    expect(checksOf(s).map((c) => [c.key, c.title])).toEqual([['one', 'First'], ['two', 'Two']])
    expect(checksOf(moveCheck(s, 0, -1)).map((c) => c.key)).toEqual(['one', 'two'])
    expect(checksOf(moveCheck(s, 1, -1)).map((c) => c.key)).toEqual(['two', 'one'])
    expect(checksOf(removeCheck(s, 0)).map((c) => c.key)).toEqual(['two'])
  })

  it('stays when another step is toggled', () => {
    const s = applyCheck(base(), check('Kept'), null)
    expect(toggle(toggle(s, 'retro'), 'retro').map((x) => x.step)).toEqual(['approval', 'produce', 'review', 'check', 'accept'])
  })

  it('is sent flat to the save route, every other step as before', () => {
    const s = applyCheck(base(), check('Flat'), null)
    expect(savedSteps(s)).toEqual([
      { step: 'approval' },
      { step: 'produce' },
      { step: 'review' },
      { step: 'check', key: 'flat', title: 'Flat', does: 'Look for secrets', why: 'Our contract requires it', handsOver: 'The findings', slot: 'checks', runBy: 'reviewer', blocks: true },
      { step: 'accept' },
    ])
  })

  it('shows a check text problem and a fifth check under the slot', () => {
    let s = base()
    for (const n of ['a', 'b', 'c', 'd', 'e']) s = applyCheck(s, check(n), null)
    s = applyCheck(s, { ...checksOf(s)[1], why: 'ma​in' }, 1)
    const found = problemsOf(s, 'Checked', false)
    expect(found).toContainEqual({ row: 'check', key: 'workflows.chk.err.STEP_TEXT_HIDDEN_CHARACTER', index: 1, params: { position: 3, max: 500 } })
    expect(found).toContainEqual({ row: 'check', key: 'workflows.chk.err.TOO_MANY_STEPS' })
  })

  it('makes a slug key from any title', () => {
    expect(keyFor('Sécurité des données!', [])).toBe('securite-des-donnees')
    expect(keyFor('***', ['check'])).toBe('check-2')
  })

  it('counts what the approval of the plan adds: a review and each check per work ticket, never a cost', () => {
    const s = applyCheck(applyCheck(base(), check('One'), null), check('Two'), null)
    const tickets = [{ kind: 'WORK' }, { kind: 'WORK' }, { kind: 'WORK' }, { kind: 'DECISION' }]
    expect(checksAdded({ workflow: { steps: s } }, tickets)).toEqual({ tickets: 3, reviews: 3, checks: 6 })
    expect(checksAdded({ workflow: { steps: base() } }, tickets)).toBeNull()
    expect(checksAdded(null, tickets)).toBeNull()
  })
})
