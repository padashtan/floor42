import { beforeEach, describe, expect, it, vi } from 'vitest'

/** One answer the test releases when it wants: a slow daemon, on demand. */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

const pending = new Map<string, ReturnType<typeof deferred<unknown>>>()
const calls: string[] = []
/** The meetings read fails while true. */
let failMeetings = false

vi.mock('../api/client', () => {
  class ApiError extends Error {
    needsLogin = false
    code = 'X'
    status = 500
  }
  const missions = [
    { id: 'M-1', title: 'One', status: 'IN_PROGRESS' },
    { id: 'M-2', title: 'Two', status: 'IN_PROGRESS' },
  ]
  const answer = (name: string, value: unknown) => {
    calls.push(name)
    return Promise.resolve(value)
  }
  return {
    ApiError,
    api: {
      agents: () => answer('agents', []),
      missions: () => answer('missions', missions),
      openQuestions: () => answer('openQuestions', []),
      usage: () => answer('usage', null),
      notes: () => answer('notes', []),
      lessons: () => answer('lessons', []),
      settings: () => answer('settings', []),
      meetings: () => (failMeetings ? Promise.reject(new ApiError()) : answer('meetings', [])),
      toolSettings: (id: string) => answer('toolSettings ' + id, []),
      mission: (id: string) => {
        calls.push('mission ' + id)
        const d = deferred<unknown>()
        pending.set(id, d)
        return d.promise
      },
    },
  }
})

const { office, putOnBoard } = await import('./office')

const flush = () => new Promise((r) => setTimeout(r, 0))

describe('reload', () => {
  beforeEach(() => {
    pending.clear()
    calls.length = 0
  })

  it('never lets a late answer for the previous mission overwrite the board', async () => {
    const first = putOnBoard('M-1')
    await flush()
    const second = putOnBoard('M-2')
    await flush()

    // The switch to M-2 answers first, then M-1's slow answer arrives.
    pending.get('M-2')!.resolve({ id: 'M-2' })
    await second
    pending.get('M-1')!.resolve({ id: 'M-1' })
    await first

    expect(office.mission?.id).toBe('M-2')
    expect(office.boardChoice).toBe('M-2')
  })

  it('puts nothing on the board on the explicit empty choice, and keeps it', async () => {
    await putOnBoard('@empty')
    expect(office.mission).toBeNull()
    expect(office.boardChoice).toBe('@empty')
  })

  it('keeps the office when only the meetings cannot be read', async () => {
    failMeetings = true
    const done = putOnBoard('M-1')
    await flush()
    pending.get('M-1')!.resolve({ id: 'M-1' })
    await done
    failMeetings = false
    expect(office.mission?.id).toBe('M-1')
    expect(office.error).toBeNull()
  })

  it('sends the independent reads together, before waiting for any of them', async () => {
    const done = putOnBoard('M-1')
    // Synchronously after the call: every independent read has gone out at once.
    expect(calls).toEqual(['agents', 'missions', 'openQuestions', 'usage', 'notes', 'lessons', 'settings', 'meetings'])
    await flush()
    expect(calls).toContain('mission M-1')
    expect(calls).toContain('toolSettings M-1')
    pending.get('M-1')!.resolve({ id: 'M-1' })
    await done
    expect(office.mission?.id).toBe('M-1')
  })
})
