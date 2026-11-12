import { describe, expect, it } from 'vitest'
import {
  drafts,
  hireDrafted,
  missionDrafted,
  panelsWithDraft,
  resetAllDrafts,
  resetMissionDraft,
} from './drafts'
import { deliverablesOnEnd } from './missionEnd'

describe('what counts as a draft', () => {
  it('is only what differs from a blank form, choices included', () => {
    resetAllDrafts()
    expect(missionDrafted()).toBe(false)
    drafts.mission.letGalenPlan = false
    expect(missionDrafted()).toBe(true)
    resetMissionDraft()
    drafts.mission.panel = 'architecture'
    expect(missionDrafted()).toBe(true)
    resetMissionDraft()
    drafts.mission.seated = ['developer', 'security']
    expect(missionDrafted(), 'the same seats in another order are the defaults').toBe(false)
    drafts.mission.seated = ['developer']
    expect(missionDrafted()).toBe(true)
    resetAllDrafts()
    drafts.hire.effort = 'max'
    expect(hireDrafted()).toBe(true)
    resetAllDrafts()
    expect(panelsWithDraft(['desk', 'hire', 'callMeeting'])).toEqual([])
  })

  it('is gone after a submit: the next form opens blank and nothing is protected', () => {
    drafts.mission.title = 'T'
    drafts.mission.objective = 'O'
    drafts.mission.repository = 'C:/repo'
    drafts.mission.panel = 'idea'
    drafts.mission.seated = ['moderator', 'security']
    drafts.mission.letGalenPlan = false
    expect(panelsWithDraft(['desk'])).toEqual(['desk'])
    resetMissionDraft()
    expect(drafts.mission.repository).toBe('')
    expect(drafts.mission.panel).toBe('none')
    expect(drafts.mission.letGalenPlan).toBe(true)
    expect(drafts.mission.seated).toEqual(['security', 'developer'])
    expect(panelsWithDraft(['desk'])).toEqual([])
  })

  it('is gone for every form on sign-out', () => {
    drafts.mission.title = 'T'
    drafts.hire.name = 'Ada'
    drafts.meeting.text = 'Hello'
    resetAllDrafts()
    expect(panelsWithDraft(['desk', 'hire', 'callMeeting'])).toEqual([])
  })
})

describe('a mission that ends', () => {
  it('opens its deliverables when no shown panel holds a draft', () => {
    resetAllDrafts()
    const calls: string[] = []
    deliverablesOnEnd(['desk', 'log'], () => calls.push('open'), () => calls.push('offer'))
    expect(calls).toEqual(['open'])
  })

  it('offers them by toast, and replaces nothing, when a shown panel holds a draft', () => {
    resetAllDrafts()
    drafts.mission.title = 'Half typed'
    const calls: string[] = []
    deliverablesOnEnd(['desk'], () => calls.push('open'), () => calls.push('offer'))
    expect(calls).toEqual(['offer'])
    deliverablesOnEnd(['board'], () => calls.push('open'), () => calls.push('offer'))
    expect(calls, 'a draft in a panel that is not shown protects nothing').toEqual(['offer', 'open'])
    resetAllDrafts()
  })
})
