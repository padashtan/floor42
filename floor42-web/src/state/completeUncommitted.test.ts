import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { UncommittedCheck } from '../api/types'

/** What the daemon answers next for the check, and what it does with the acceptance. */
const daemon = vi.hoisted(() => ({
  checks: [] as (UncommittedCheck | Error)[],
  completes: [] as (string | null)[],
  refuseFirst: false,
  refuseAlways: false,
}))

vi.mock('../api/client', () => {
  class ApiError extends Error {
    needsLogin = false
    status = 409
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
      uncommitted: () => {
        const next = daemon.checks.shift()!
        return next instanceof Error ? Promise.reject(next) : Promise.resolve(next)
      },
      complete: (_m: string, _s: string, _w: boolean, acknowledged: string | null) => {
        daemon.completes.push(acknowledged)
        if (daemon.refuseFirst || daemon.refuseAlways) {
          daemon.refuseFirst = false
          return Promise.reject(new ApiError('UNCOMMITTED_DELIVERABLES'))
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

// The delivery is proven here (ADR-0058): only the uncommitted question is asked.
vi.mock('../api/proof', () => ({ proofApi: { acceptance: () => Promise.resolve({ state: null, token: 'p' }) } }))

import { setLang } from '../i18n'
import { pending } from './confirm'
import { completeMission, office } from './office'

const clean: UncommittedCheck = { clean: true, token: 'aaaa', worktrees: [] }
const dirty = (token: string): UncommittedCheck => ({
  clean: false,
  token,
  worktrees: [{ agent: 'chris', path: '/w', state: 'DIRTY', reason: null, count: 2, paths: ['deliverables/a.md', 'deliverables/b.md'] }],
})
const unknown: UncommittedCheck = {
  clean: false,
  token: 'cccc',
  worktrees: [{ agent: 'chris', path: '/w', state: 'UNKNOWN', reason: 'GIT_FAILED', count: 0, paths: [] }],
}

/** Answers the confirmation the flow opens, once it is open. */
async function answer(yes: boolean) {
  await vi.waitFor(() => expect(pending.value).not.toBeNull())
  const message = pending.value!.request.message
  pending.value!.settle(yes ? true : null)
  return message
}

beforeAll(() => setLang('en'))

beforeEach(() => {
  daemon.checks = []
  daemon.completes = []
  daemon.refuseFirst = false
  daemon.refuseAlways = false
  office.error = null
})

describe('accepting a mission with work not in a commit (ADR-0055)', () => {
  it('accepts a clean mission without asking', async () => {
    daemon.checks.push(clean)
    expect(await completeMission('M-1', 'done')).toBe(true)
    expect(daemon.completes).toEqual([null])
  })

  it('asks first, and sends nothing when the director says no', async () => {
    daemon.checks.push(dirty('bbbb'))
    const done = completeMission('M-1', 'done')
    expect(await answer(false)).toContain('deliverables/a.md')
    expect(await done).toBe(false)
    expect(daemon.completes).toEqual([])
  })

  it('sends the token of what the director saw, and asks again when it changed meanwhile', async () => {
    daemon.checks.push(dirty('bbbb'), dirty('dddd'))
    daemon.refuseFirst = true
    const done = completeMission('M-1', 'done')
    await answer(true)
    await answer(true)
    expect(await done).toBe(true)
    expect(daemon.completes).toEqual(['bbbb', 'dddd'])
  })

  it('says an unknown state as unknown, never as clean', async () => {
    daemon.checks.push(unknown)
    const done = completeMission('M-1', 'done')
    expect(await answer(false)).toContain('git did not answer')
    expect(await done).toBe(false)
  })

  it('tells the director when the worktrees keep changing, never a silent no', async () => {
    daemon.checks.push(dirty('b1'), dirty('b2'), dirty('b3'))
    daemon.refuseAlways = true
    const done = completeMission('M-1', 'done')
    for (let i = 0; i < 3; i++) await answer(true)
    expect(await done).toBe(false)
    expect(office.error).toContain('kept changing')
  })

  it('accepts nothing when the check itself fails', async () => {
    daemon.checks.push(new Error('daemon unreachable'))
    expect(await completeMission('M-1', 'done')).toBe(false)
    expect(daemon.completes).toEqual([])
  })
})
