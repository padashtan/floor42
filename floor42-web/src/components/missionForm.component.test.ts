// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { setLang } from '../i18n'
import { drafts, resetAllDrafts } from '../state/drafts'
import MissionControl from './MissionControl.vue'

const api = vi.hoisted(() => ({ committeeRoles: vi.fn(), initRepository: vi.fn() }))
const office = vi.hoisted(() => ({
  state: { status: { runtime: { available: true, version: '9.9.9', reason: null } }, mission: null as unknown, error: null },
  createMission: vi.fn(),
  configureChecks: vi.fn(),
}))
vi.mock('../api/client', () => ({
  api,
  MISSION_TITLE_MAX: 200,
  MISSION_OBJECTIVE_MAX: 20_000,
  newIdempotencyKey: () => 'key',
  ApiError: class ApiError extends Error {},
}))
vi.mock('../state/office', () => ({
  office: office.state,
  createMission: office.createMission,
  configureChecks: office.configureChecks,
  approvePlan: vi.fn(),
  cancelMission: vi.fn(),
  completeMission: vi.fn(),
  rejectPlan: vi.fn(),
  resumeMission: vi.fn(),
  startDemo: vi.fn(),
}))

const STUBS = { PracticeMission: true, UnfamiliarLines: true, ProjectPicker: true, SandboxReadiness: true, NewSeat: true }
const init = (files = 0) => ({ path: 'C:/empty', repository: false, hasCommit: false, files, refused: null })

async function mountDesk() {
  const w = mount(MissionControl, { props: { section: 'new' }, global: { stubs: STUBS } })
  await flushPromises()
  return w
}
const preview = (w: Awaited<ReturnType<typeof mountDesk>>, p: ReturnType<typeof init>) =>
  w.findComponent({ name: 'MissionProject' }).vm.$emit('update:preview', p)

beforeAll(() => setLang('en'))
beforeEach(() => {
  resetAllDrafts()
  vi.clearAllMocks()
  api.committeeRoles.mockResolvedValue([])
  office.state.mission = null
})

describe('the new mission form (P1d)', () => {
  it('offers a title from the objective, filled by a click and never silently', async () => {
    const w = await mountDesk()
    drafts.mission.objective = 'Add a login page\nwith details'
    await flushPromises()
    expect(drafts.mission.title).toBe('')
    await w.find('.suggestion button').trigger('click')
    expect(drafts.mission.title).toBe('Add a login page')
    expect(w.find('.suggestion').exists(), 'no suggestion once a title is there').toBe(false)
  })

  it('says a new project will be created, and blocks a folder chosen as an existing project', async () => {
    const w = await mountDesk()
    drafts.mission.title = 'T'
    drafts.mission.objective = 'O'
    drafts.mission.repository = 'C:/empty'
    preview(w, init())
    await flushPromises()
    expect(w.find('button[type=submit]').attributes('disabled')).toBeDefined()
    expect(w.text()).toContain('is not a git repository')
    await w.find('input[type=radio][value=new]').setValue(true)
    expect(w.find('.new-project').text()).toBe('Floor42 will create a new empty project in C:/empty.')
    expect(w.find('button[type=submit]').attributes('disabled')).toBeUndefined()
  })

  it('warns that the files named in the objective are absent from an empty new project', async () => {
    const w = await mountDesk()
    drafts.mission.objective = 'Change floor42-web/src/scene/x.ts'
    preview(w, init())
    await flushPromises()
    expect(w.find('.missing-files').text()).toContain('floor42-web/src/scene/x.ts')
    preview(w, init(3))
    await flushPromises()
    expect(w.find('.missing-files').exists(), 'a folder with files may hold them').toBe(false)
  })

  it('sets the checks command on the mission once created', async () => {
    office.createMission.mockImplementation(async () => {
      office.state.mission = { id: 'M-7', title: 'Ship it' }
      return true
    })
    office.configureChecks.mockResolvedValue(true)
    const w = await mountDesk()
    Object.assign(drafts.mission, { title: 'Ship it', objective: 'O', repository: 'C:/repo', checks: ' npm test ' })
    await w.find('form.new-mission').trigger('submit')
    await flushPromises()
    expect(office.configureChecks).toHaveBeenCalledWith('M-7', 'npm test', false)
  })
})
