import { beforeEach, describe, expect, it } from 'vitest'
import type { OversightLine, OversightReport } from '../api/types'
import { setLang, t } from '../i18n'
import { headerLine, parts, SECTION_IDS } from './oversight'

const line = (over: Partial<OversightLine> = {}): OversightLine => ({
  at: '2026-11-13T10:00:05Z',
  kind: 'APPROVAL_ANSWERED',
  actor: 'director',
  scope: 'NETWORK curl',
  text: 'Yes: curl https://example.org',
  origin: 'REAL',
  events: [{ seq: 5, eventId: 'e5', type: 'QuestionAnswered' }],
  ...over,
})
const section = (items: OversightLine[] = [], count = items.length) => ({ count, items })

function report(over: Partial<OversightReport> = {}): OversightReport {
  return {
    format: 1,
    generator: 'Floor42',
    version: '1.3.0',
    generatedAt: '2026-11-13T12:00:00Z',
    statement: 'facts from the journal',
    mission: { id: 'M-1', title: 'T', origin: 'REAL', status: 'ACTIVE', events: 9, real: 8, simulated: 1 },
    supervision: section([line()]),
    capabilities: section(),
    actions: section([line({ kind: 'ACTION', actor: 'ada', code: 'DENIED_POLICY', text: 'rm -rf build', events: [{ seq: 3, eventId: 'e3', type: 'ActionRequested' }, { seq: 4, eventId: 'e4', type: 'ActionDenied' }] })]),
    overrides: section(),
    masked: [{ kind: 'SECRET', count: 2 }],
    limits: ['signIns.notJournaled'],
    ...over,
  }
}

describe('the oversight report lines', () => {
  beforeEach(() => setLang('en'))

  it('has the four questions then the limits, in order', () => {
    expect(SECTION_IDS).toEqual(['supervision', 'capabilities', 'actions', 'overrides', 'limits'])
    expect(parts(report(), t).map((p) => p.id)).toEqual([...SECTION_IDS])
  })

  it('writes a line in words with its scope, its text and the numbers of its events', () => {
    const row = parts(report(), t)[0].rows[0]
    expect(row.text).toBe('Approval answered (director): scope: NETWORK curl: Yes: curl https://example.org')
    expect(row.notes).toEqual(['#5 QuestionAnswered'])
  })

  it('translates an outcome code and leaves a code the catalogue does not know as sent', () => {
    const rows = parts(report({ overrides: section([line({ kind: 'OBJECTION', code: '2', events: [{ seq: 1, eventId: 'e1', type: 'DirectiveGiven' }] })]) }), t)
    expect(parts(report(), t)[2].rows[0].text).toContain('refused by the permission table')
    expect(rows[3].rows[0].text).toContain(': 2:')
  })

  it('names a company-wide event without a number', () => {
    const company = line({ kind: 'BREAK_GLASS_SIGN_IN', scope: 'COMPANY', text: null, events: [{ eventId: 'e7', type: 'BreakGlassSignIn' }] })
    const row = parts(report({ supervision: section([company]) }), t)[0].rows[0]
    expect(row.text).toBe('Break-glass sign-in (director): company-wide')
    expect(row.notes).toEqual(['company-wide BreakGlassSignIn'])
  })

  it('shows "200 of N" for a capped section and the limits with the masked counts', () => {
    const p = parts(report({ actions: section([line()], 350) }), t)
    expect(p[2].capped).toBe(t('oversight.capped', { shown: 1, count: 350 }))
    expect(p[4].rows.map((r) => r.text)).toEqual([t('replayExport.kind.SECRET', { count: 2 }), t('oversight.limit.signIns.notJournaled')])
  })

  it('writes the header and the French words in French', () => {
    expect(headerLine(report(), t)).toContain('9 events (8 real, 1 simulated)')
    setLang('fr')
    expect(parts(report(), t)[0].title).toBe('Qui a supervisé')
    expect(parts(report(), t)[2].rows[0].text).toContain('refusée par la table de permissions')
  })
})
