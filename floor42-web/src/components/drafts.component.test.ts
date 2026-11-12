// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { pending } from '../state/confirm'
import { setLang } from '../i18n'
import { drafts, hireDrafted, missionDrafted, panelsWithDraft, resetAllDrafts, setVacantSeats } from '../state/drafts'
import DirectorMeeting from './DirectorMeeting.vue'
import HireAgent from './HireAgent.vue'
import MissionControl from './MissionControl.vue'
import SessionsPanel from './SessionsPanel.vue'

// Every form is mounted for real; only the daemon behind it is replaced. A submit the daemon accepts empties the
// draft, a refused one keeps what the director typed.
const api = vi.hoisted(() => ({
  committeeRoles: vi.fn(),
  initRepository: vi.fn(),
  meetings: vi.fn(),
  meetingInvitation: vi.fn(),
  callMeeting: vi.fn(),
  sessions: vi.fn(),
  signOut: vi.fn(),
  signOutEverywhere: vi.fn(),
}))
const office = vi.hoisted(() => ({
  state: {
    status: { runtime: { available: true, version: '9.9.9', reason: null } },
    mission: null,
    missions: [{ id: 'M-1', title: 'One', status: 'IN_PROGRESS' }],
    agents: [{ id: 'ada', name: 'Ada', role: 'Developer', status: 'AVAILABLE' }],
    error: null,
  },
  createMission: vi.fn(),
  hireAgent: vi.fn(),
}))

const keys = vi.hoisted(() => ({ next: 0 }))
vi.mock('../api/client', () => ({
  api,
  MISSION_TITLE_MAX: 200,
  MISSION_OBJECTIVE_MAX: 20_000,
  newIdempotencyKey: () => `key-${++keys.next}`,
  ApiError: class ApiError extends Error {
    needsLogin = false
  },
}))
vi.mock('../state/office', () => ({
  office: office.state,
  createMission: office.createMission,
  hireAgent: office.hireAgent,
  approvePlan: vi.fn(),
  archiveMission: vi.fn(),
  conveneRetro: vi.fn(),
  cancelMission: vi.fn(),
  completeMission: vi.fn(),
  rejectPlan: vi.fn(),
  resumeMission: vi.fn(),
  startDemo: vi.fn(),
  // The real one turns a refusal into null once shown; the panels only see the null.
  workshopCall: async <T,>(run: () => Promise<T>) => {
    try {
      return await run()
    } catch {
      return null
    }
  },
}))

const CHILDREN = {
  MissionFiles: true,
  NewSeat: true,
  PlanCap: true,
  PracticeMission: true,
  UnfamiliarLines: true,
  ProjectPicker: true,
  SandboxReadiness: true,
}

const role = (key: string, vacant = false) => ({
  key,
  name: key,
  title: key,
  lens: '',
  panels: ['architecture'],
  custom: false,
  vacant,
})

beforeAll(() => setLang('en'))
beforeEach(() => {
  setVacantSeats([])
  resetAllDrafts()
  vi.clearAllMocks()
  api.committeeRoles.mockResolvedValue([role('architect'), role('security'), role('developer')])
  api.meetings.mockResolvedValue([])
  api.meetingInvitation.mockResolvedValue({ suggested: ['ada'] })
  api.sessions.mockResolvedValue([])
})
afterEach(() => {
  setVacantSeats([])
  resetAllDrafts()
})

function typeMission() {
  drafts.mission.title = 'Ship it'
  drafts.mission.objective = 'Do the thing'
  drafts.mission.repository = 'C:/repo'
  drafts.mission.letGalenPlan = false
}

describe('the new mission form', () => {
  const mountDesk = async () => {
    const w = mount(MissionControl, { props: { section: 'new' }, global: { stubs: CHILDREN } })
    await flushPromises()
    return w
  }

  it('empties its draft once the mission is proposed', async () => {
    office.createMission.mockResolvedValue(true)
    const w = await mountDesk()
    typeMission()
    await w.find('form.new-mission').trigger('submit')
    await flushPromises()
    expect(office.createMission).toHaveBeenCalledWith(
      'Ship it',
      'Do the thing',
      'C:/repo',
      [],
      false,
      'architecture',
      null,
      'deliverables',
      expect.stringMatching(/^key-/),
      'loop',
    )
    expect(missionDrafted()).toBe(false)
    expect(drafts.mission.letGalenPlan).toBe(true)
  })

  it('keeps its draft when the proposal is refused', async () => {
    office.createMission.mockResolvedValue(false)
    const w = await mountDesk()
    typeMission()
    await w.find('form.new-mission').trigger('submit')
    await flushPromises()
    expect(drafts.mission.title).toBe('Ship it')
    expect(panelsWithDraft(['desk'])).toEqual(['desk'])
  })

  it('is not a draft when blank, even with a default seat left vacant', async () => {
    api.committeeRoles.mockResolvedValue([role('architect'), role('security'), role('developer', true)])
    await mountDesk()
    expect(drafts.mission.seated).toEqual(['security'])
    expect(missionDrafted()).toBe(false)
    drafts.mission.seated = []
    expect(missionDrafted(), 'unseating the last default seat is a choice').toBe(true)
  })
})

describe('the new mission form, sent again', () => {
  it('sends one key until the mission is created, then a new one', async () => {
    const w = mount(MissionControl, { props: { section: 'new' }, global: { stubs: CHILDREN } })
    await flushPromises()
    const sentKey = () => office.createMission.mock.lastCall?.[8]
    office.createMission.mockResolvedValue(false)
    typeMission()
    await w.find('form.new-mission').trigger('submit')
    await flushPromises()
    const refused = sentKey()
    await w.find('form.new-mission').trigger('submit')
    await flushPromises()
    expect(sentKey(), 'a retry of the same proposal').toBe(refused)
    drafts.mission.title = 'Ship it today'
    await w.find('form.new-mission').trigger('submit')
    await flushPromises()
    expect(sentKey(), 'an edited proposal').not.toBe(refused)
    drafts.mission.title = 'Ship it'
    office.createMission.mockResolvedValue(true)
    await w.find('form.new-mission').trigger('submit')
    await flushPromises()
    typeMission()
    await w.find('form.new-mission').trigger('submit')
    await flushPromises()
    expect(sentKey(), 'the next mission').not.toBe(refused)
    expect(w.find('form.new-mission input').attributes('maxlength')).toBe('200')
  })
})

describe('the hire form', () => {
  it('refuses a name already at work, whatever its case', async () => {
    office.hireAgent.mockClear()
    const w = mount(HireAgent)
    drafts.hire.name = ' ADA '
    await flushPromises()
    expect(w.find('button[type=submit]').attributes('disabled')).toBeDefined()
    expect(w.text()).toContain('An agent with this name already works here')
    await w.find('form.hire-form').trigger('submit')
    await flushPromises()
    expect(office.hireAgent).not.toHaveBeenCalled()
  })

  it('empties its draft once the agent is hired, and keeps it when the hire is refused', async () => {
    office.hireAgent.mockResolvedValue(false)
    const w = mount(HireAgent)
    drafts.hire.name = 'Grace'
    drafts.hire.effort = 'max'
    await w.find('form.hire-form').trigger('submit')
    await flushPromises()
    expect(drafts.hire.name).toBe('Grace')
    expect(w.emitted('close')).toBeUndefined()

    office.hireAgent.mockResolvedValue(true)
    await w.find('form.hire-form').trigger('submit')
    await flushPromises()
    expect(office.hireAgent).toHaveBeenLastCalledWith('Grace', '', 'DEVELOPER', 'claude-sonnet-5-5', 'max')
    expect(hireDrafted()).toBe(false)
    expect(w.emitted('close')).toHaveLength(1)
  })
})

describe('the meeting form', () => {
  const mountMeeting = async () => {
    const w = mount(DirectorMeeting)
    await flushPromises()
    return w
  }

  it('keeps the agenda when the call is refused, and empties it once the meeting is called', async () => {
    const w = await mountMeeting()
    drafts.meeting.agenda = 'The release'
    api.callMeeting.mockRejectedValueOnce(new Error('refused'))
    await w.find('button.meeting-call').trigger('click')
    await flushPromises()
    expect(drafts.meeting.agenda).toBe('The release')
    expect(panelsWithDraft(['callMeeting'])).toEqual(['callMeeting'])

    api.callMeeting.mockResolvedValueOnce({ id: 'MT-1', mission: 'M-1', open: true, attendees: [], notes: [] })
    await w.find('button.meeting-call').trigger('click')
    await flushPromises()
    expect(api.callMeeting).toHaveBeenLastCalledWith('M-1', ['ada'], 'The release', 'TABLE')
    expect(drafts.meeting.agenda).toBe('')
    expect(panelsWithDraft(['callMeeting'])).toEqual([])
    w.unmount()
  })
})

// The confirmation is the page's own dialog (state/confirm.ts): the test answers yes to what is pending.
describe('signing out', () => {
  const reload = vi.fn()
  beforeEach(() => {
    reload.mockReset()
    vi.spyOn(window.location, 'reload').mockImplementation(reload)
  })
  afterEach(() => vi.unstubAllGlobals())

  const typeEverything = () => {
    drafts.mission.title = 'T'
    drafts.hire.name = 'Grace'
    drafts.meeting.text = 'Hello'
  }
  const button = (w: ReturnType<typeof mount>, text: string) => w.findAll('button').find((b) => b.text() === text)!

  for (const [what, label, call] of [
    ['this session', 'Sign out', 'signOut'],
    ['everywhere', 'Sign out everywhere', 'signOutEverywhere'],
  ] as const) {
    it(`${what} throws every draft away, and a refused sign-out keeps them`, async () => {
      const w = mount(SessionsPanel)
      await flushPromises()
      typeEverything()
      api[call].mockRejectedValueOnce(new Error('daemon down'))
      await button(w, label).trigger('click')
      await flushPromises()
      pending.value?.settle(true)
      await flushPromises()
      expect(panelsWithDraft(['desk', 'hire', 'callMeeting'])).toEqual(['desk', 'hire', 'callMeeting'])
      expect(reload).not.toHaveBeenCalled()

      api[call].mockResolvedValueOnce(undefined)
      await button(w, label).trigger('click')
      await flushPromises()
      pending.value?.settle(true)
      await flushPromises()
      expect(panelsWithDraft(['desk', 'hire', 'callMeeting'])).toEqual([])
      expect(reload).toHaveBeenCalledOnce()
    })
  }
})
