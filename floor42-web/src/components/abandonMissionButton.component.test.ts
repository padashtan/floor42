// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AbandonMissionButton from './AbandonMissionButton.vue'

const confirmed = vi.hoisted(() => vi.fn())
const cancelMission = vi.hoisted(() => vi.fn(async () => undefined))
vi.mock('../state/confirm', () => ({ confirmed }))
vi.mock('../state/office', () => ({ cancelMission }))

const mission = (status: string) => ({ id: 'M-1', title: 'Port it', status })

describe('the board abandon button', () => {
  beforeEach(() => {
    confirmed.mockReset()
    cancelMission.mockClear()
  })

  it('shows no button for a completed or cancelled mission', () => {
    for (const status of ['COMPLETED', 'CANCELLED'])
      expect(mount(AbandonMissionButton, { props: { mission: mission(status) } }).find('button').exists()).toBe(false)
    expect(mount(AbandonMissionButton, { props: { mission: mission('IN_PROGRESS') } }).find('button').exists()).toBe(true)
  })

  it('asks before abandoning and calls nothing on a no', async () => {
    confirmed.mockResolvedValue(false)
    const w = mount(AbandonMissionButton, { props: { mission: mission('IN_PROGRESS') } })
    await w.find('button').trigger('click')
    expect(confirmed).toHaveBeenCalledOnce()
    expect(cancelMission).not.toHaveBeenCalled()
    confirmed.mockResolvedValue(true)
    await w.find('button').trigger('click')
    await Promise.resolve()
    expect(cancelMission).toHaveBeenCalledWith('M-1', expect.any(String))
  })
})
