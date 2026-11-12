import { beforeAll, describe, expect, it } from 'vitest'
import type { ProofStepView, ProofView, ProofWorktree } from '../api/proof'
import { setLang } from '../i18n'
import { directorSteps, expected, noCopy, proofLines, resultOf, statusText, stepNote, witnessOf } from './proof'

beforeAll(() => setLang('en'))

const step = (index: number, runner: 'FLOOR42' | 'DIRECTOR', extra: Partial<ProofStepView> = {}): ProofStepView => ({
  index,
  title: `Step ${index}`,
  command: `cmd ${index}`,
  expectExit: 0,
  expectOutput: [],
  runner,
  reason: runner === 'DIRECTOR' ? 'NEEDS_DOCKER' : null,
  problems: [],
  ...extra,
})
const run = (outcome: 'PASSED' | 'FAILED', stale = false) => ({
  at: 'now',
  digest: 'd',
  head: 'abc',
  changesDigest: 'c',
  stale,
  steps: [{ index: 1, command: 'cmd 1', outcome, failure: null, exitCode: 0, timedOut: false, durationMs: 1, output: '' }],
})
const worktree = (extra: Partial<ProofWorktree> = {}): ProofWorktree => ({
  agent: 'chris',
  path: '/w',
  state: 'FOUND',
  digest: 'd',
  head: 'abc',
  changesDigest: 'c',
  problems: [],
  steps: [step(1, 'FLOOR42'), step(2, 'DIRECTOR')],
  lastRun: null,
  witnesses: [],
  target: 't',
  skipped: [],
  skippedCount: 0,
  ...extra,
})
const view = (status: ProofView['status'], w: ProofWorktree = worktree(), verdict: ProofView['verdict'] = null): ProofView => ({
  mission: 'M-1',
  origin: 'REAL',
  sandboxWorks: true,
  running: false,
  runError: null,
  status,
  directorSteps: 1,
  worktrees: [w],
  verdict,
  targets: 'ts',
  unprovenAccepted: null,
})
const proven = { verdict: 'PROVEN' as const, note: '', at: 'now', current: true }

describe('the proof of a delivery on screen (ADR-0058)', () => {
  it('says what a step must show and who runs it', () => {
    expect(expected(step(1, 'FLOOR42', { expectOutput: ['UP'] }))).toBe('exit code 0 · output containing "UP"')
    expect(stepNote(step(2, 'DIRECTOR'))).toBe('for you: it needs Docker, which the sandbox lacks')
    expect(stepNote(step(3, 'FLOOR42', { problems: ['NO_EXPECTATION'] }))).toBe('says nothing it must show')
  })

  it('offers no copy of a command cut or holding hidden characters', () => {
    expect(noCopy(step(1, 'DIRECTOR'))).toBe(false)
    expect(noCopy(step(1, 'DIRECTOR', { problems: ['HIDDEN_CHARACTER'] }))).toBe(true)
    expect(noCopy(step(1, 'DIRECTOR', { problems: ['COMMAND_TOO_LONG'] }))).toBe(true)
  })

  it('reads a result only from a run on the recipe and code as they are now', () => {
    expect(resultOf(worktree({ lastRun: run('PASSED') }), 1)?.outcome).toBe('PASSED')
    expect(resultOf(worktree({ lastRun: run('PASSED', true) }), 1)).toBeNull()
    expect(directorSteps(view('PASSED')).map((d) => d.step.index)).toEqual([2])
    expect(witnessOf(worktree({ witnesses: [{ step: 2, works: false, seen: '503', at: 'now' }] }), 2)?.seen).toBe('503')
    expect(statusText(view('PASSED'))).toBe('every step Floor42 can run passed')
    expect(statusText(view('PASSED', worktree({ steps: [step(2, 'DIRECTOR')] })))).toBe('nothing Floor42 can run: every step is yours')
  })

  it('lists what the acceptance asks about: nothing once proven by a current verdict', () => {
    const ran = worktree({ lastRun: run('PASSED') })
    expect(proofLines(view('PASSED', ran, proven))).toEqual([])
    expect(proofLines(view('PASSED', ran))).toEqual(['you have given no verdict on the proof as it is now'])
    expect(proofLines(view('PASSED', ran, { ...proven, current: false }))).toEqual([
      'you have given no verdict on the proof as it is now',
    ])
    expect(proofLines(view('PASSED', ran, { ...proven, verdict: 'NOT_PROVEN' }))).toEqual(['you judged it not proven'])
    expect(proofLines(view('NO_RECIPE', worktree({ state: 'MISSING', steps: [] }), proven))).toEqual(['chris left no PROVE.md'])
    expect(proofLines(view('NOT_RUN', worktree({ lastRun: run('PASSED', true) }), proven))[0]).toContain('not run')
    expect(proofLines(view('FAILED', worktree({ lastRun: run('FAILED') }), proven))[0]).toContain('failed')
    expect(proofLines(view('NO_RECIPE', worktree({ head: null }), proven))).toEqual([
      'Floor42 cannot tell what code chris delivered (git did not answer or a file could not be read)',
    ])
  })
})
