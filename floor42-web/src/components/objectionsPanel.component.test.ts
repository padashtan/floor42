// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import type { MissionView } from '../api/types'
import { setLang } from '../i18n'
import * as objections from '../state/objections'
import { office } from '../state/office'
import { panels } from '../state/openPanels'
import { toRequest } from '../ui/objections'
import ObjectionsButton from './ObjectionsButton.vue'
import ObjectionsPanel from './ObjectionsPanel.vue'

beforeAll(() => setLang('en'))
beforeEach(() => {
  vi.spyOn(api, 'held').mockResolvedValue([])
  office.mission = {
    id: 'M-1',
    committees: [],
    tickets: [{ id: 'T-1', title: 'One', status: 'DONE', kind: 'WORK', assignee: 'chris', endedAt: '2026-11-04T09:00:00Z' }],
  } as unknown as MissionView
})
afterEach(() => {
  office.mission = null
  panels.objections = false
  vi.restoreAllMocks()
})

describe('the Objections panel', () => {
  it('is opened by the button, as a panel of its own', async () => {
    const w = mount(ObjectionsButton, { props: { disabled: false } })
    await w.find('button.object').trigger('click')
    expect(panels.objections).toBe(true)
  })

  it('starts with one row, focused; adds up to 10 rows, removes one, and sends the body unchanged', async () => {
    const send = vi.spyOn(objections, 'objectToDelivery').mockResolvedValue(true)
    const w = mount(ObjectionsPanel, { attachTo: document.body })
    await flushPromises()
    expect(w.findAll('.objection')).toHaveLength(1)
    expect(document.activeElement).toBe(w.find('textarea').element)
    for (let i = 0; i < 12; i++) await w.findAll('.row button.action')[0]!.trigger('click')
    expect(w.findAll('.objection')).toHaveLength(10)
    expect(w.findAll('.row button.action')[0]!.attributes('disabled')).toBeDefined()
    expect(document.activeElement).toBe(w.findAll('textarea')[9]!.element)
    for (let i = 0; i < 9; i++) await w.findAll('button.remove')[0]!.trigger('click')
    expect(w.findAll('.objection')).toHaveLength(1)
    expect(w.find('button.send-objections').attributes('disabled')).toBeDefined()
    await w.find('textarea').setValue('  Also write the notes  ')
    await w.find('button.send-objections').trigger('click')
    await flushPromises()
    expect(send).toHaveBeenCalledWith('M-1', [{ target: 'T-1', text: '  Also write the notes  ' }], 0, [])
    expect(toRequest(send.mock.calls[0]![1])).toEqual([{ ticket: 'T-1', text: 'Also write the notes' }])
    expect(w.emitted('close')).toHaveLength(1)
    w.unmount()
  })

  it('stays open when the director says no', async () => {
    vi.spyOn(objections, 'objectToDelivery').mockResolvedValue(false)
    const w = mount(ObjectionsPanel)
    await flushPromises()
    await w.find('textarea').setValue('x')
    await w.find('button.send-objections').trigger('click')
    await flushPromises()
    expect(w.emitted('close')).toBeUndefined()
  })
})
