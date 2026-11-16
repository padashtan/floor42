import { describe, expect, it } from 'vitest'
import type { PreflightLine, PreflightView } from '../api/types'
import { blockCount, proposal, shownLines, warnCount } from './preflight'

const view = (lines: PreflightLine[], profile: Partial<PreflightView['profile']> = {}): PreflightView => ({
  project: '/p',
  verdict: 'READY',
  profile: { buildTool: 'Maven', files: ['pom.xml'], branch: 'main', suggestedChecks: './mvnw -q verify', lastChecks: null, ...profile },
  lines,
})
const line = (code: string, level: PreflightLine['level']): PreflightLine => ({ code, level, params: {} })

describe('preflight', () => {
  it('a blocker disables the creation and gives its reason', () => {
    const v = view([line('sandbox.required', 'BLOCK'), line('repo.dirty', 'WARN')])
    expect(blockCount(v)).toBe(1)
    expect(blockCount(null)).toBe(0)
  })

  it('counts warnings', () => {
    expect(warnCount(view([line('repo.dirty', 'WARN'), line('checks.none', 'WARN'), line('offline.unknown', 'INFO')]))).toBe(2)
  })

  it('suggestion is offered only while the field is empty', () => {
    const v = view([])
    expect(proposal(v, '')?.command).toBe('./mvnw -q verify')
    expect(proposal(v, '  ')?.command).toBe('./mvnw -q verify')
    expect(proposal(v, 'make test')).toBeNull()
  })

  it("prefers the last mission's command over the tool default", () => {
    expect(proposal(view([], { lastChecks: 'make check' }), '')).toEqual({ command: 'make check', fromLast: true })
    expect(proposal(view([]), '')?.fromLast).toBe(false)
  })

  it('proposes nothing when no command is known, and never says ready as a line', () => {
    expect(proposal(view([], { suggestedChecks: null }), '')).toBeNull()
    expect(shownLines(view([line('ready', 'OK')]))).toEqual([])
  })
})
