// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MissionSummary } from '../api/types'
import { setLang, t } from '../i18n'
import { offer } from '../state/afterArchive'
import { pending } from '../state/confirm'
import ArchiveMissionButton from './ArchiveMissionButton.vue'
import ConveneRetroButton from './ConveneRetroButton.vue'
import MissionArchives from './MissionArchives.vue'

const api = vi.hoisted(() => ({ archivedMissions: vi.fn(), archivableMissions: vi.fn(), retroOffer: vi.fn() }))
vi.mock('../api/client', () => ({ api, ApiError: class ApiError extends Error {} }))
const office = vi.hoisted(() => ({
  archiveMission: vi.fn(),
  archiveMissions: vi.fn(),
  conveneRetro: vi.fn(),
  unarchiveMission: vi.fn(),
  office: { missions: [] as MissionSummary[], error: null as string | null },
}))
vi.mock('../state/office', () => office)

beforeAll(() => setLang('en'))
beforeEach(() => {
  vi.clearAllMocks()
  pending.value = null
})

const summary = (id: string, status = 'CANCELLED', archived = true): MissionSummary => ({ agents: [],
  id,
  title: `Mission ${id}`,
  status,
  origin: 'REAL',
  tickets: 1,
  ticketsDone: 0,
  openQuestions: 0,
  archived,
})

describe('the desk’s archives', () => {
  it('lists the archived missions, newest first, and restores one', async () => {
    api.archivedMissions.mockResolvedValue([summary('M-1'), summary('M-2', 'COMPLETED')])
    office.unarchiveMission.mockResolvedValue(true)
    const w = mount(MissionArchives)
    await flushPromises()

    const rows = w.findAll('li.archived-mission')
    expect(rows.map((r) => r.attributes('data-mission'))).toEqual(['M-2', 'M-1'])
    expect(rows[0].text()).toContain(t('mstatus.COMPLETED'))
    await rows[1].find('button.restore').trigger('click')
    await flushPromises()
    expect(office.unarchiveMission).toHaveBeenCalledWith('M-1')
    expect(api.archivedMissions).toHaveBeenCalledTimes(2)
    // Restored: said, and offered its retrospective if it never had one; nothing is convened by the restore itself.
    expect(w.find('.restored').text()).toContain(t('archives.restored', { id: 'M-1', title: 'Mission M-1' }))
    expect(office.conveneRetro).not.toHaveBeenCalled()
  })

  it('says so when nothing is archived', async () => {
    api.archivedMissions.mockResolvedValue([])
    const w = mount(MissionArchives)
    await flushPromises()
    expect(w.find('.empty-state').text()).toBe(t('archives.none'))
  })

  it('archives every closed mission after a confirmation that gives the count, and says why one was left out', async () => {
    api.archivedMissions.mockResolvedValue([])
    api.archivableMissions.mockResolvedValue([summary('M-3', 'CANCELLED', false), summary('M-4', 'COMPLETED', false)])
    office.archiveMissions.mockResolvedValue([
      { mission: 'M-3', archived: true, code: null },
      { mission: 'M-4', archived: false, code: 'SESSION_RUNNING' },
    ])
    const w = mount(MissionArchives)
    await flushPromises()

    await w.find('button.clean-board').trigger('click')
    await flushPromises()
    expect(pending.value?.request.message).toBe(t('archives.cleanConfirm', { count: 2 }))
    expect(office.archiveMissions).not.toHaveBeenCalled()
    pending.value!.settle(true)
    await flushPromises()

    expect(office.archiveMissions).toHaveBeenCalledWith(['M-3', 'M-4'])
    const report = w.find('.clean-report').text()
    expect(report).toContain(t('archives.cleanedOne'))
    expect(report).toContain(t('archives.leftOut', { id: 'M-4', why: t('refusal.SESSION_RUNNING') }))
  })

  it('archives nothing when the director cancels, nor asks when no mission is closed', async () => {
    api.archivedMissions.mockResolvedValue([])
    api.archivableMissions.mockResolvedValue([summary('M-5', 'CANCELLED', false)])
    const w = mount(MissionArchives)
    await flushPromises()
    await w.find('button.clean-board').trigger('click')
    await flushPromises()
    expect(pending.value?.request.message).toBe(t('archives.cleanConfirmOne'))
    pending.value!.settle(null)
    await flushPromises()
    expect(office.archiveMissions).not.toHaveBeenCalled()

    api.archivableMissions.mockResolvedValue([])
    await w.find('button.clean-board').trigger('click')
    await flushPromises()
    expect(pending.value).toBeNull()
    expect(w.find('.clean-none').text()).toBe(t('archives.cleanNone'))
  })
})

describe('the archive button of a mission', () => {
  it('is offered for a closed mission only, and archives it once confirmed', async () => {
    for (const status of ['PLANNING', 'AWAITING_APPROVAL', 'IN_PROGRESS', 'SUSPENDED']) {
      const open = mount(ArchiveMissionButton, { props: { mission: summary('M-6', status, false) } })
      expect(open.find('button.archive-mission').exists(), status).toBe(false)
    }
    const archived = mount(ArchiveMissionButton, { props: { mission: summary('M-6', 'COMPLETED', true) } })
    expect(archived.find('button.archive-mission').exists()).toBe(false)

    office.archiveMission.mockResolvedValue(true)
    const w = mount(ArchiveMissionButton, { props: { mission: summary('M-7', 'CANCELLED', false) } })
    await w.find('button.archive-mission').trigger('click')
    await flushPromises()
    expect(pending.value?.request.message).toBe(t('mission.archiveConfirm', { id: 'M-7', title: 'Mission M-7' }))
    pending.value!.settle(true)
    await flushPromises()
    expect(office.archiveMission).toHaveBeenCalledWith('M-7')
    expect(offer.value).toBe('M-7')
  })

  it('asks nothing when the daemon refuses', async () => {
    offer.value = null
    office.archiveMission.mockResolvedValue(false)
    const w = mount(ArchiveMissionButton, { props: { mission: summary('M-7', 'CANCELLED', false) } })
    await w.find('button.archive-mission').trigger('click')
    await flushPromises()
    pending.value!.settle(true)
    await flushPromises()
    expect(offer.value).toBeNull()
  })
})

describe('convening the retrospective of a closed mission', () => {
  it('is a button only when the daemon offers it, and convenes once confirmed', async () => {
    api.retroOffer.mockResolvedValue({ available: true, code: null })
    office.conveneRetro.mockResolvedValue(true)
    const w = mount(ConveneRetroButton, { props: { mission: summary('M-8', 'CANCELLED', false) } })
    await flushPromises()
    expect(api.retroOffer).toHaveBeenCalledWith('M-8')
    expect(office.conveneRetro).not.toHaveBeenCalled()

    await w.find('button.convene-retro').trigger('click')
    await flushPromises()
    expect(pending.value?.request.message).toBe(t('retro.conveneConfirm', { id: 'M-8' }))
    pending.value!.settle(true)
    await flushPromises()
    expect(office.conveneRetro).toHaveBeenCalledTimes(1)
    expect(office.conveneRetro).toHaveBeenCalledWith('M-8')
  })

  it('says why when it cannot be convened, and shows nothing once it had one', async () => {
    api.retroOffer.mockResolvedValue({ available: false, code: 'RETRO_DISABLED' })
    const off = mount(ConveneRetroButton, { props: { mission: summary('M-9', 'COMPLETED', false) } })
    await flushPromises()
    expect(off.find('button.convene-retro').exists()).toBe(false)
    expect(off.find('.convene-retro-why').text()).toBe(t('retro.conveneAbsent', { why: t('refusal.RETRO_DISABLED') }))

    api.retroOffer.mockResolvedValue({ available: false, code: 'DUPLICATE_RETRO' })
    const had = mount(ConveneRetroButton, { props: { mission: summary('M-9', 'COMPLETED', false) } })
    await flushPromises()
    expect(had.html()).not.toContain('convene-retro')
  })

  it('asks nothing for an open or archived mission', async () => {
    mount(ConveneRetroButton, { props: { mission: summary('M-10', 'IN_PROGRESS', false) } })
    mount(ConveneRetroButton, { props: { mission: summary('M-11', 'CANCELLED', true) } })
    await flushPromises()
    expect(api.retroOffer).not.toHaveBeenCalled()
  })
})
