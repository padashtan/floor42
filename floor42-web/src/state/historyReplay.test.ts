import { describe, expect, it, vi } from 'vitest'

const past = {
  eventId: 'e-old',
  occurredAt: '2026-10-20T10:00:00Z',
  origin: 'REAL',
  type: 'CommitteeContributed',
  code: 'CommitteeContributed.analysis',
  params: {},
  agent: 'bobi',
}

vi.mock('../api/client', () => {
  class ApiError extends Error {
    needsLogin = false
  }
  const none = () => Promise.resolve([])
  return {
    ApiError,
    api: {
      status: () => Promise.resolve(null),
      agents: none,
      missions: none,
      openQuestions: none,
      usage: () => Promise.resolve(null),
      notes: none,
      lessons: none,
      settings: none,
      meetings: none,
      toolSettings: none,
      events: () => Promise.resolve([past]),
    },
  }
})

// The stream stays closed: only the history read at page load is under test.
vi.mock('../api/eventStream', () => ({ EventStream: class { open() {} } }))

const { office, onLiveEvent, start } = await import('./office')

describe('the history read at page load', () => {
  it('shows past events in the log but never calls the live listeners, so it marks no speaker', async () => {
    const heard: string[] = []
    onLiveEvent((e) => heard.push(e.eventId))
    await start()
    expect(office.log.map((l) => l.id)).toContain('e-old')
    expect(heard).toEqual([])
  })
})
