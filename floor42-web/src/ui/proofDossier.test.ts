import { beforeEach, describe, expect, it } from 'vitest'
import type { DossierRef, ProofDossier } from '../api/types'
import { setLang, t } from '../i18n'
import { inert, parts, SECTION_IDS, toMarkdown } from './proofDossier'

const journal = (basis: string | null, ...events: [number, string][]): DossierRef => ({
  source: 'JOURNAL',
  basis,
  origin: 'REAL',
  events: events.map(([seq, type]) => ({ seq, eventId: `e${seq}`, type })),
})

function dossier(over: Partial<ProofDossier> = {}): ProofDossier {
  return {
    format: 1,
    generator: 'Floor42',
    version: '1.1.0',
    generatedAt: '2026-11-13T12:00:00Z',
    statement: 'facts from the journal',
    mission: { id: 'M-1', title: 'T', objective: 'O', origin: 'REAL', status: 'ACTIVE', events: 9, real: 8, simulated: 1 },
    iam: true,
    identities: {
      count: 1,
      items: [
        {
          agent: 'ada',
          client: 'floor42-agent-ada',
          onBehalfOf: 'director',
          tokenAudience: 'floor42',
          session: 'L1',
          issuer: 'http://kc',
          expiresAt: '2026-11-13T18:00:00Z',
          jti: 'jti-1',
          derived: ['client', 'onBehalfOf', 'tokenAudience'],
          ref: journal(null, [1, 'AgentTokenIssued']),
        },
      ],
    },
    delegation: { resources: ['github'], grantedAt: '2026-11-13T09:00:00Z', expiresAt: '2026-11-14T09:00:00Z', revoked: false, ref: { source: 'STATE', events: [] } },
    brokerCalls: { count: 0, items: [] },
    refusals: {
      count: 1,
      items: [
        {
          at: '2026-11-13T10:00:03Z',
          agent: 'ada',
          tool: 'Bash',
          reason: 'POLICY',
          detail: 'x ![x](http://h/p) [a](b) | `c` <img src=x>',
          identity: false,
          ref: journal(null, [3, 'ActionDenied'], [2, 'ActionRequested']),
        },
      ],
    },
    approvals: { count: 1, items: [{ at: '2026-11-13T10:00:01Z', kind: 'PLAN_APPROVED', actor: 'director', ref: journal(null, [4, 'MissionPlanApproved']) }] },
    authorized: [{ basis: 'POLICY', count: 2 }],
    costs: {
      budgetUsd: 5,
      budgetRef: journal(null, [5, 'BudgetSet']),
      sessions: [{ agent: 'ada', session: 'S1', usd: 1.25, basis: 'ESTIMATED', ref: journal('ESTIMATED', [6, 'AgentSessionEnded']) }],
      total: { usd: 1.25, basis: 'ESTIMATED' },
      bound: { usd: 2, sessions: 1, basis: 'UPPER_BOUND' },
    },
    checks: { count: 1, items: [{ at: 'x', workspace: 'ada', command: 'mvn test', exitCode: 0, timedOut: false, durationMs: 1200, trigger: 'manual', kind: 'CHECK', ref: journal('MEASURED', [7, 'ChecksRan']) }] },
    deliverables: { count: 1, items: [{ at: 'x', kind: 'PATCH_ACCEPTED', text: 'fix', commit: 'abc123', files: 2, ref: journal(null, [8, 'PatchAccepted']) }] },
    diff: { count: 1, items: [{ agent: 'ada', branch: 'floor42/ada', readable: true, commits: 2, uncommitted: 1, ref: { source: 'GIT', events: [] } }] },
    gitReadAt: '2026-11-13T11:00:00Z',
    masked: [{ kind: 'SECRET', count: 2 }],
    limits: ['identity.claimsNotJournaled', 'diff.noLineCounts', 'personal.freeText'],
    ...over,
  }
}

describe('the proof dossier as Markdown', () => {
  beforeEach(() => setLang('en'))

  it('has the eight sections in order and the statement first', () => {
    const md = toMarkdown(dossier(), t)
    expect(md.startsWith('# Proof dossier of M-1\n\nFacts from the journal; any other source is named on its line.')).toBe(true)
    const headings = md.split('\n').filter((l) => l.startsWith('## '))
    expect(headings).toEqual(SECTION_IDS.map((id) => `## ${t(`dossier.section.${id}` as never)}`))
  })

  it('ends every line with its source, and a journal line with its event numbers', () => {
    const lines = toMarkdown(dossier(), t).split('\n').filter((l) => l.startsWith('- '))
    expect(lines.length).toBeGreaterThan(8)
    for (const l of lines) expect(l).toMatch(/\)$/)
    expect(lines.find((l) => l.includes('ada, Bash'))).toContain('#3 ActionDenied; #2 ActionRequested')
    expect(lines.find((l) => l.includes('Delegated resources'))).toContain('Floor42 state at export, not a journal event')
    expect(lines.find((l) => l.includes('floor42/ada'))).toContain('read from git at 2026-11-13T11:00:00Z')
    expect(lines.find((l) => l.includes('floor42-agent-ada'))).toContain('derived by Floor42, not recorded: client, onBehalfOf, tokenAudience')
  })

  it('never prints a figure without its basis word', () => {
    const lines = toMarkdown(dossier(), t).split('\n')
    expect(lines.find((l) => l.includes('Total: 1.25 USD'))).toContain('estimated by Claude Code')
    expect(lines.find((l) => l.includes('Cap of 1 session'))).toContain('upper bound, not added to the total')
    expect(lines.find((l) => l.includes('mvn test: exit 0'))).toContain('measured')
    expect(lines.find((l) => l.includes('ada, session S1'))).toContain('estimated by Claude Code')
  })

  it('makes agent text unable to form a link, an image, a table cell or HTML', () => {
    const md = toMarkdown(dossier(), t)
    const line = md.split('\n').find((l) => l.includes('ada, Bash'))!
    expect(line).not.toContain('](')
    expect(line).not.toContain('<img')
    expect(line).not.toContain('http://')
    expect(line).not.toMatch(/(^|[^\\])\|/)
    expect(line).not.toMatch(/(^|[^\\])`/)
    expect(inert('![x](http://h/p)')).toBe('\\!\\[x\\]\\(http:\\/\\/h/p\\)')
  })

  it('says IAM was off and prints no identity row', () => {
    const d = dossier({ iam: false, identities: { count: 0, items: [] }, delegation: null })
    const rows = parts(d, t)[0]!.rows
    expect(rows).toHaveLength(1)
    expect(rows[0]!.text).toBe('No identity event: IAM mode was off for this mission.')
  })

  it('says "200 of N" for a capped section', () => {
    const d = dossier({ checks: { count: 250, items: dossier().checks.items } })
    const checks = parts(d, t).find((p) => p.id === 'checks')!
    expect(checks.capped).toBe('1 of 250 shown')
    expect(toMarkdown(d, t)).toContain('1 of 250 shown')
  })

  it('gives French headings with the French catalogue', () => {
    setLang('fr')
    const md = toMarkdown(dossier(), t)
    expect(md).toContain('# Dossier de preuve de M-1')
    expect(md).toContain('## Actions refusées')
    expect(md).toContain('## Masqué ou laissé de côté')
    setLang('en')
  })
})
