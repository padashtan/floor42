import { describe, expect, it } from 'vitest'
import type { NextStep, ReportOutput, ReportWarning } from '../api/types'
import { atRisk, levelOf, money, nextSteps, offered, recommendedStep, refusalKey, tagKey, time, warnings } from './missionReport'

const w = (code: string, extra: Partial<ReportWarning> = {}): ReportWarning => ({
  code,
  ticket: null,
  agent: null,
  path: null,
  count: null,
  text: null,
  ...extra,
})

const out = (extra: Partial<ReportOutput> = {}): ReportOutput => ({
  agent: 'bobi',
  path: 'C:/w/M-1-bobi',
  branch: 'floor42/m-1-bobi',
  readable: true,
  accepted: 0,
  uncommitted: [],
  uncommittedCount: 0,
  ignored: [],
  ignoredCount: 0,
  mergeCommand: null,
  ...extra,
})

describe('end-of-mission report formatting', () => {
  it('shows an estimated cost as an estimate, never as a bill', () => {
    expect(money({ value: 1.0360767, basis: 'ESTIMATED' })).toEqual({
      value: '≈ 1.04 USD',
      basisKey: 'report.basis.ESTIMATED',
    })
    expect(money({ value: 0.11, basis: 'ESTIMATED' }).value).toBe('≈ 0.1100 USD')
  })

  it('says "not reported" rather than zero when no session gave an estimate', () => {
    expect(money({ value: null, basis: 'ESTIMATED' }).value).toBeNull()
    expect(time({ value: null, basis: 'MEASURED' }).value).toBeNull()
  })

  it('keeps the basis of a measured time', () => {
    expect(time({ value: 125_000, basis: 'MEASURED' })).toEqual({ value: '2 min', basisKey: 'report.basis.MEASURED' })
  })

  it('puts outputs in no commit first, then what agents said was not done, then the rest', () => {
    const shown = warnings([
      w('NO_CHECKS'),
      w('REVIEW_VALIDATED_WITH_RESERVATIONS', { ticket: 'T-49', agent: 'margaret-2', text: 'PDFs missing' }),
      w('PRODUCER_PARTIAL', { ticket: 'T-49', text: 'PARTIAL — PDFs not rendered' }),
      w('OUTPUTS_IGNORED', { agent: 'bobi', path: 'C:/w/M-1-bobi', count: 2, text: 'docs/out/a.pdf, docs/out/b.pdf' }),
    ])
    expect(shown.map((s) => s.key)).toEqual([
      'report.warn.OUTPUTS_IGNORED',
      'report.warn.REVIEW',
      'report.warn.PRODUCER_PARTIAL',
      'report.warn.NO_CHECKS',
    ])
    expect(shown[0]).toMatchObject({ level: 'loud', params: { path: 'C:/w/M-1-bobi', count: 2 } })
    expect(shown[1].params.verdict).toBe('VALIDATED_WITH_RESERVATIONS')
  })

  it('classifies every warning code the server sends', () => {
    expect(levelOf('OUTPUTS_NOT_COMMITTED')).toBe('loud')
    expect(levelOf('WORKTREE_UNREADABLE')).toBe('loud')
    expect(levelOf('CHECKS_FAILED')).toBe('warn')
    expect(levelOf('REVIEW_UNVERIFIABLE')).toBe('warn')
    expect(levelOf('NO_CHECKS')).toBe('info')
  })

  it('names every worktree whose work no merge or pull request carries', () => {
    const clean = out({ agent: 'chris', accepted: 2 })
    const ignored = out({ ignored: ['docs/out/a.pdf'], ignoredCount: 1 })
    const pending = out({ agent: 'lucie', uncommitted: ['x.md'], uncommittedCount: 1 })
    const unreadable = out({ agent: 'gone', readable: false })
    expect(atRisk([clean, ignored, pending, unreadable]).map((o) => o.agent)).toEqual(['bobi', 'lucie', 'gone'])
  })

  it('orders the next steps the same way every time', () => {
    const s = (kind: NextStep['kind']): NextStep => ({ kind, available: true, code: null, message: null })
    expect(nextSteps([s('ARCHIVE'), s('MERGE'), s('CLOSE'), s('FOLLOW_UP'), s('REOPEN'), s('PULL_REQUEST')]).map((x) => x.kind))
      .toEqual(['CLOSE', 'REOPEN', 'PULL_REQUEST', 'MERGE', 'FOLLOW_UP', 'ARCHIVE'])
  })
})

describe('next steps of the report', () => {
  const refused = (kind: NextStep['kind'], code: string): NextStep => ({ kind, available: false, code, message: `${code}: raw` })
  const ok = (kind: NextStep['kind']): NextStep => ({ kind, available: true, code: null, message: null })

  it('phrases a refusal through the catalogue, never as the raw code and message', () => {
    expect(refusalKey(refused('CLOSE', 'MISSION_NOT_FINISHED'))).toBe('report.refused.MISSION_NOT_FINISHED')
    expect(refusalKey(refused('MERGE', 'SOMETHING_NEW'))).toBe('report.refused.OTHER')
    expect(refusalKey(ok('CLOSE'))).toBeNull()
    expect(refusalKey(undefined)).toBeNull()
  })

  it('offers neither closing nor a refused reopening on a closed mission', () => {
    const steps = [refused('CLOSE', 'INVALID_TRANSITION'), refused('REOPEN', 'MISSION_NOT_ACTIVE'), ok('ARCHIVE')]
    for (const status of ['COMPLETED', 'CANCELLED']) {
      expect(offered('CLOSE', steps, status)).toBe(false)
      expect(offered('REOPEN', steps, status)).toBe(false)
      expect(offered('ARCHIVE', steps, status)).toBe(true)
    }
    expect(offered('REOPEN', [ok('REOPEN')], 'COMPLETED')).toBe(true)
    expect(offered('CLOSE', steps, 'IN_PROGRESS')).toBe(true)
    expect(offered('REOPEN', steps, 'IN_PROGRESS')).toBe(true)
  })
})

describe('sections of the end-of-mission panel', () => {
  const step = (kind: NextStep['kind'], available: boolean): NextStep => ({ kind, available, code: null, message: null })

  it('recommends closing first while the mission is open', () => {
    const steps = [step('CLOSE', true), step('PULL_REQUEST', true), step('ARCHIVE', true)]
    expect(recommendedStep(steps, 'IN_PROGRESS')).toBe('CLOSE')
  })

  it('never recommends closing a closed mission, nor a step that is refused', () => {
    const steps = [step('CLOSE', true), step('PULL_REQUEST', false), step('MERGE', true)]
    expect(recommendedStep(steps, 'COMPLETED')).toBe('MERGE')
    expect(recommendedStep([step('CLOSE', false)], 'IN_PROGRESS')).toBeNull()
  })

  it('names the tag of a basis, simulated included', () => {
    expect(tagKey(money({ value: 0, basis: 'SIMULATED' }))).toBe('report.tag.SIMULATED')
    expect(tagKey(time({ value: 5, basis: 'MEASURED' }))).toBe('report.tag.MEASURED')
  })
})
