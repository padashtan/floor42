// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import { closeLastPanel, panels, setPanel } from '../state/openPanels'
import { pinned, setPinned } from '../state/pinned'
import { useOnePanel } from './onePanel'
import { addPin, pinClicked } from './pinButton'
import { isMain, superseded } from './panelLayout'
import { closeOnEscape, type PanelMount } from './panelMounts'

afterEach(() => {
  for (const k of Object.keys(pinned)) setPinned(k, false)
  for (const k of Object.keys(panels)) setPanel(k, false)
  document.body.innerHTML = ''
})

describe('a pinned panel', () => {
  it('is not a main panel: another panel opening does not close it', () => {
    expect(superseded(['costs', 'usage'], ['costs'], ['costs', 'usage'])).toEqual(['costs'])
    setPinned('costs', true)
    expect(isMain('costs')).toBe(false)
    expect(superseded(['costs', 'usage'], ['costs'], ['costs', 'usage'])).toEqual([])
  })

  it('stays open and live through the real layout rule while another panel opens', async () => {
    const flags = { a: ref(true), b: ref(false) }
    const mounts = (['a', 'b'] as const).map((id) => ({ id, component: {}, shown: () => flags[id].value, close: () => (flags[id].value = false) })) as PanelMount[]
    const scope = effectScope()
    scope.run(() => useOnePanel(mounts))
    setPinned('a', true)
    flags.b.value = true
    await nextTick()
    expect([flags.a.value, flags.b.value]).toEqual([true, true])
    scope.stop()
  })

  it('is unpinned once it closes', async () => {
    const open = ref(true)
    const scope = effectScope()
    scope.run(() => useOnePanel([{ id: 'a', component: {}, shown: () => open.value, close: () => (open.value = false) }] as PanelMount[]))
    setPinned('a', true)
    open.value = false
    await nextTick()
    expect(pinned.a).toBeUndefined()
    scope.stop()
  })

  it('is left open by Escape, which goes on to the next panel', () => {
    const flags = { settings: true, costs: true }
    const table = (['settings', 'costs'] as const).map((id) => ({ id, component: {}, shown: () => flags[id], close: () => (flags[id] = false) })) as PanelMount[]
    setPinned('settings', true)
    expect(closeOnEscape(table)).toBe(true)
    expect(flags).toEqual({ settings: true, costs: false })
    expect(closeOnEscape(table)).toBe(false)
    setPinned('settings', false)
    expect(closeOnEscape(table)).toBe(true)

    setPanel('desk', true)
    setPanel('board', true)
    setPinned('board', true)
    expect(closeLastPanel()).toBe(true)
    expect([panels.desk, panels.board]).toEqual([false, true])
    expect(closeLastPanel()).toBe(false)
    setPinned('board', false)
    expect(closeLastPanel()).toBe(true)
  })
})

describe('the pin button of the frame', () => {
  const frame = (id: string, withClose = true) => {
    document.body.innerHTML = `<div class="panel" data-panel="${id}"><div class="title">T${withClose ? '<button class="close">x</button>' : ''}</div></div>`
    const panel = document.querySelector<HTMLElement>('.panel')!
    addPin(panel)
    return panel
  }

  it('sits next to the close cross, pins on a click and unpins on the next', () => {
    const panel = frame('costs')
    const pin = panel.querySelector<HTMLElement>('.title > button.pin')!
    expect(pin.nextElementSibling?.className).toBe('close')
    expect(pin.getAttribute('aria-pressed')).toBe('false')
    pinClicked({ target: panel } as unknown as Event)
    expect(pinned.costs).toBeUndefined()
    pinClicked({ target: pin } as unknown as Event)
    expect(pinned.costs).toBe(true)
    expect(pin.getAttribute('aria-pressed')).toBe('true')
    expect(panel.hasAttribute('data-pinned')).toBe(true)
    pinClicked({ target: pin } as unknown as Event)
    expect(pinned.costs).toBeUndefined()
    expect(panel.hasAttribute('data-pinned')).toBe(false)
  })

  it('is added once, and not to a docked panel or a panel without a cross', () => {
    const panel = frame('costs')
    addPin(panel)
    expect(panel.querySelectorAll('button.pin')).toHaveLength(1)
    expect(frame('log').querySelector('button.pin')).toBeNull()
    expect(frame('costs', false).querySelector('button.pin')).toBeNull()
  })
})
