import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProofAcceptanceView, ProofView } from '../api/proof'

/** What the daemon answers for the proof, and the proof tokens the acceptances carry. */
const daemon = vi.hoisted(() => ({
  acceptance: [] as unknown[],
  proof: null as unknown,
  completes: [] as (string | null)[],
  refuseFirst: false,
}))

vi.mock('../api/client', () => {
  class ApiError extends Error {
    readonly code: string
    constructor(code: string) {
      super(code)
      this.code = code
    }
  }
  const empty = () => Promise.resolve([])
  return {
    ApiError,
    api: {
      uncommitted: () => Promise.resolve({ clean: true, token: 't', worktrees: [] }),
      complete: (_m: string, _s: string, _w: boolean, _a: string | null, proof: string | null) => {
        daemon.completes.push(proof)
        if (daemon.refuseFirst) {
          daemon.refuseFirst = false
          return Promise.reject(new ApiError('UNPROVEN_DELIVERY'))
        }
        return Promise.resolve({})
      },
      agents: empty,
      missions: empty,
      openQuestions: empty,
      usage: () => Promise.resolve(null),
      notes: empty,
      lessons: empty,
      settings: empty,
      meetings: empty,
    },
  }
})
vi.mock('../api/proof', () => ({
  proofApi: {
    acceptance: () => Promise.resolve(daemon.acceptance.shift()),
    proof: () => (daemon.proof instanceof Error ? Promise.reject(daemon.proof) : Promise.resolve(daemon.proof)),
  },
}))

import { setLang } from '../i18n'
import { pending } from './confirm'
import { completeMission } from './office'

const ask = (state: string | null, token: string): ProofAcceptanceView => ({ state, token })
const missing: ProofView = {
  mission: 'M-1',
  origin: 'REAL',
  sandboxWorks: true,
  running: false,
  runError: null,
  status: 'NO_RECIPE',
  directorSteps: 0,
  worktrees: [{ agent: 'chris', path: '/w', state: 'MISSING', digest: null, head: 'abc', changesDigest: 'c', problems: [], steps: [], lastRun: null, witnesses: [], target: null, skipped: [], skippedCount: 0 }],
  verdict: null,
  targets: 'ts',
  unprovenAccepted: null,
}

async function answer(yes: boolean) {
  await vi.waitFor(() => expect(pending.value).not.toBeNull())
  const message = pending.value!.request.message
  pending.value!.settle(yes ? true : null)
  return message
}

beforeAll(() => setLang('en'))
beforeEach(() => {
  daemon.completes = []
  daemon.acceptance = []
  daemon.refuseFirst = false
  daemon.proof = missing
})

describe('accepting a delivery that is not proven (ADR-0058)', () => {
  it('accepts a proven delivery without asking, and sends no proof token', async () => {
    daemon.acceptance.push(ask(null, 'aaaa'))
    expect(await completeMission('M-1', 'done')).toBe(true)
    expect(daemon.completes).toEqual([null])
  })

  it('asks with the reasons, and sends nothing on a no', async () => {
    daemon.acceptance.push(ask('NO_RECIPE', 'bbbb'))
    const done = completeMission('M-1', 'done')
    const message = await answer(false)
    expect(message).toContain('chris left no PROVE.md')
    expect(message).toContain('no verdict')
    expect(await done).toBe(false)
    expect(daemon.completes).toEqual([])
  })

  it('sends the token of what the director saw, and asks again when it changed meanwhile', async () => {
    daemon.acceptance.push(ask('NOT_RUN', 'cccc'), ask('FAILED', 'dddd'))
    daemon.refuseFirst = true
    const done = completeMission('M-1', 'done')
    await answer(true)
    await answer(true)
    expect(await done).toBe(true)
    expect(daemon.completes).toEqual(['cccc', 'dddd'])
  })

  it('says a proof it cannot read, never takes it for a pass', async () => {
    daemon.acceptance.push(ask('NOT_RUN', 'eeee'))
    daemon.proof = new Error('down')
    const done = completeMission('M-1', 'done')
    expect(await answer(false)).toContain('could not read the proof')
    expect(await done).toBe(false)
  })
})
