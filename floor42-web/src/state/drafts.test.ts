import { describe, expect, it } from 'vitest'
import { drafts, panelsWithDraft, resetHireDraft, resetMeetingDraft, resetMissionDraft } from './drafts'

describe('the drafts of the forms', () => {
  it('hold nothing at first, so nothing is protected', () => {
    expect(panelsWithDraft(['desk', 'hire', 'callMeeting', 'board'])).toEqual([])
  })

  it('protect the panel that holds what was typed, and only that one', () => {
    drafts.mission.title = 'Port the billing'
    drafts.meeting.text = '   '
    expect(panelsWithDraft(['desk', 'hire', 'callMeeting'])).toEqual(['desk'])
    drafts.hire.name = 'Ada'
    drafts.meeting.agenda = 'Plan'
    expect(panelsWithDraft(['hire', 'callMeeting', 'desk'])).toEqual(['hire', 'callMeeting', 'desk'])
  })

  it('go back to blank, choices included', () => {
    drafts.mission.letGeoffreyPlan = false
    drafts.mission.panel = 'idea'
    drafts.mission.seated = ['moderator']
    drafts.mission.deliverTo = 'C:/elsewhere'
    resetMissionDraft()
    resetHireDraft()
    resetMeetingDraft()
    expect(drafts.mission).toEqual({
      title: '',
      objective: '',
      repository: '',
      checks: '',
      panel: 'none',
      letGeoffreyPlan: true,
      seated: ['security', 'developer'],
      // The project's deliverables/ folder by default (ADR-0051).
      deliverTo: 'deliverables',
    })
    expect(drafts.hire.name).toBe('')
    expect(panelsWithDraft(['desk', 'hire', 'callMeeting'])).toEqual([])
  })
})
