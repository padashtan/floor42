// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MissionSummary } from '../api/types'
import { setLang, t } from '../i18n'
import { offer, offerAfterArchive } from '../state/afterArchive'
import { panels } from '../state/openPanels'
import AfterArchiveDialog from './AfterArchiveDialog.vue'

const office = vi.hoisted(() => ({ putOnBoard: vi.fn(), office: { missions: [] as MissionSummary[] } }))
vi.mock('../state/office', () => office)

const m = (id: string, status: string, archived = false): MissionSummary =>
  ({ id, title: `Mission ${id}`, status, archived }) as MissionSummary

beforeAll(() => setLang('en'))
beforeEach(() => {
  vi.clearAllMocks()
  offer.value = null
  panels.desk = false
})

describe('what to put on the board after an archive', () => {
  it('stays closed until an archive succeeded', () => {
    expect(mount(AfterArchiveDialog).find('.after-archive').exists()).toBe(false)
  })

  it('lists the open missions newest first and puts the chosen one on the board', async () => {
    office.office.missions = [m('M-1', 'IN_PROGRESS'), m('M-2', 'COMPLETED'), m('M-3', 'SUSPENDED'), m('M-4', 'CANCELLED'), m('M-5', 'PLANNING', true)]
    offerAfterArchive('M-6')
    const w = mount(AfterArchiveDialog)
    const rows = w.findAll('li.after-archive-row')
    expect(rows.map((r) => r.attributes('data-mission'))).toEqual(['M-3', 'M-1'])
    await rows[1].find('button').trigger('click')
    await flushPromises()
    expect(office.putOnBoard).toHaveBeenCalledWith('M-1')
    expect(offer.value).toBeNull()
  })

  it('leaves the board empty on its button, and on Escape', async () => {
    office.office.missions = [m('M-1', 'IN_PROGRESS')]
    offerAfterArchive('M-6')
    const w = mount(AfterArchiveDialog)
    await w.find('button.leave-empty').trigger('click')
    expect(office.putOnBoard).toHaveBeenLastCalledWith('@empty')
    offerAfterArchive('M-7')
    await flushPromises()
    await w.find('.after-archive').trigger('keydown', { key: 'Escape' })
    expect(office.putOnBoard).toHaveBeenCalledTimes(2)
    expect(office.putOnBoard).toHaveBeenLastCalledWith('@empty')
    expect(offer.value).toBeNull()
  })

  it('offers only an empty board and a new mission when nothing else is open', async () => {
    office.office.missions = [m('M-1', 'COMPLETED')]
    offerAfterArchive('M-6')
    const w = mount(AfterArchiveDialog)
    expect(w.findAll('li.after-archive-row')).toHaveLength(0)
    expect(w.findAll('.after-archive-actions button').map((b) => b.text())).toEqual([t('afterArchive.empty'), t('afterArchive.new')])
    await w.find('button.new-mission').trigger('click')
    expect(panels.desk).toBe(true)
    expect(office.putOnBoard).toHaveBeenCalledWith('@empty')
  })
})
