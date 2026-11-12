// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { defaultNote } from '../i18n/events'
import { setLang, t } from '../i18n'
import { pending } from '../state/confirm'
import AbandonMissionButton from './AbandonMissionButton.vue'
import MissionAbandoned from './MissionAbandoned.vue'

const office = vi.hoisted(() => ({ cancelMission: vi.fn() }))
vi.mock('../state/office', () => office)

beforeAll(() => setLang('en'))
beforeEach(() => {
  vi.clearAllMocks()
  pending.value = null
})

const mission = (status: string) => ({ id: 'M-3', title: 'Wrong one', status })

describe('abandoning a mission from the board', () => {
  it('asks first, then makes the desk’s call', async () => {
    office.cancelMission.mockResolvedValue(true)
    const w = mount(AbandonMissionButton, { props: { mission: mission('IN_PROGRESS') } })
    await w.find('button').trigger('click')
    expect(pending.value?.request.message).toBe(t('mission.abandonConfirm', { id: 'M-3', title: 'Wrong one' }))
    pending.value!.settle(true)
    await flushPromises()
    expect(office.cancelMission).toHaveBeenCalledWith('M-3', defaultNote('note.abandoned'))
  })

  it('does nothing when the director declines, and is hidden once the mission is closed', async () => {
    const w = mount(AbandonMissionButton, { props: { mission: mission('IN_PROGRESS') } })
    await w.find('button').trigger('click')
    pending.value!.settle(null)
    await flushPromises()
    expect(office.cancelMission).not.toHaveBeenCalled()
    for (const closed of ['COMPLETED', 'CANCELLED'])
      expect(mount(AbandonMissionButton, { props: { mission: mission(closed) } }).find('button').exists()).toBe(false)
  })

  it('a cancelled mission says so on the board, with the date and the reason', () => {
    const banner = mount(MissionAbandoned, {
      props: { mission: { status: 'CANCELLED', closedAt: '2026-11-04T13:51:16Z', outcome: 'wrong project' } },
    })
    expect(banner.text()).toContain('Mission abandoned on')
    expect(banner.text()).toContain('wrong project')
    expect(mount(MissionAbandoned, { props: { mission: { status: 'IN_PROGRESS', closedAt: null, outcome: null } } }).text()).toBe('')
  })
})
