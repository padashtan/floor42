import { describe, expect, it } from 'vitest'
import { nextTick, ref } from 'vue'
import { ACTIONS } from './actions'
import { awayActions, awayShown } from './away'
import { closeAwayOnEscape, closeOnEscape, listenersOf, type PanelContext, panelTable } from './panelMounts'
import { panels, setPanel } from '../state/openPanels'

const noop = () => {}
const context = (): PanelContext => ({
  selected: ref(null),
  deskFocus: ref(null),
  archive: ref(null),
  reportOf: ref(null),
  profileOf: ref(null),
  requestsOf: ref(null),
  outputsOf: ref(null),
  settingsFor: ref(null),
  openDesk: noop,
  selectAgent: noop,
  suspend: noop,
  resume: noop,
  dismiss: noop,
})

describe('the panel table', () => {
  it('mounts every registry panel once', () => {
    const ids = panelTable(context()).map((p) => p.id)
    expect(new Set(ids).size).toBe(ids.length)
    // The tutorial is an overlay over the whole screen, mounted by App.vue itself, not a panel of the table.
    const overlays = ['tutorial']
    expect(ACTIONS.filter((a) => !a.href && !overlays.includes(a.id) && !ids.includes(a.id)).map((a) => a.id)).toEqual(
      [],
    )
  })

  it('Escape closes the report before any registry panel, and the workshop before the desk', () => {
    const c = context()
    const table = panelTable(c)
    setPanel('desk', true)
    setPanel('workshop', true)
    c.reportOf.value = 'M-1'
    expect(closeOnEscape(table)).toBe(true)
    expect(c.reportOf.value).toBeNull()
    expect(closeOnEscape(table)).toBe(true)
    expect(panels.workshop).toBe(false)
    expect(panels.desk).toBe(true)
    expect(closeOnEscape(table)).toBe(true)
    expect(closeOnEscape(table)).toBe(false)
  })

  it('Escape closes the away summary last, and only while it is on screen', () => {
    const table = panelTable(context())
    expect(closeAwayOnEscape(table)).toBe(false)
    let closed = 0
    awayActions.close = () => closed++
    awayShown.value = true
    setPanel('desk', true)
    // A panel in the table goes first; the summary is closed by its own step, after the last-opened panels (App.vue).
    expect(closeOnEscape(table)).toBe(true)
    expect(closeOnEscape(table)).toBe(false)
    expect(closed).toBe(0)
    expect(closeAwayOnEscape(table)).toBe(true)
    expect(closed).toBe(1)
    awayShown.value = false
    expect(closeAwayOnEscape(table)).toBe(false)
  })

  it('a closable panel hears close; the event log, always shown, does not', () => {
    const table = panelTable(context())
    expect(Object.keys(listenersOf(table.find((p) => p.id === 'board')!))).toContain('close')
    expect(Object.keys(listenersOf(table.find((p) => p.id === 'log')!))).not.toContain('close')
  })

  it('Prove follows the report it was opened from, and the current mission when opened elsewhere', async () => {
    const c = context()
    const table = panelTable(c)
    const prove = table.find((p) => p.id === 'prove')!
    const report = table.find((p) => p.id === 'report')!
    expect(prove.props!()).toEqual({ mission: null })
    c.reportOf.value = 'M-7'
    const openProve = report.on!.prove as () => void
    openProve()
    expect(c.reportOf.value).toBeNull()
    expect(prove.props!()).toEqual({ mission: 'M-7' })
    // Opened again from the bar or the palette while open: the current mission, not the report's.
    setPanel('prove', true)
    expect(prove.props!()).toEqual({ mission: null })
    prove.close!()
    await nextTick()
    expect(prove.props!()).toEqual({ mission: null })
  })
})
