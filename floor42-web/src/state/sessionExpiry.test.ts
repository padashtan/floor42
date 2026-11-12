import { describe, expect, it, vi } from 'vitest'

vi.mock('../api/client', () => {
  class ApiError extends Error {
    readonly status: number
    constructor(status: number) {
      super('expired')
      this.status = status
    }
    get needsLogin(): boolean {
      return this.status === 401
    }
  }
  return { ApiError, api: { createMission: () => Promise.reject(new ApiError(401)) } }
})

const { office, createMission } = await import('./office')
const { drafts, panelsWithDraft, resetAllDrafts } = await import('./drafts')

describe('a session that expires', () => {
  it('asks to sign in again and keeps what the director typed', async () => {
    resetAllDrafts()
    drafts.mission.title = 'Typed before the expiry'
    drafts.hire.name = 'Grace'
    drafts.meeting.agenda = 'The release'

    expect(await createMission('Typed before the expiry', 'O', 'C:/repo')).toBe(false)

    expect(office.needsLogin).toBe(true)
    expect(drafts.mission.title).toBe('Typed before the expiry')
    expect(panelsWithDraft(['desk', 'hire', 'callMeeting'])).toEqual(['desk', 'hire', 'callMeeting'])
    resetAllDrafts()
  })
})
