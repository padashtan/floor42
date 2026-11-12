import type { Ref } from 'vue'
import type { SceneTarget } from '../scene/kit'
import { closeLastPanel } from '../state/openPanels'
import { byKey } from './actions'
import { closeAwayOnEscape, closeOnEscape, type PanelMount } from './panelMounts'

/** What the page's key handler reads and drives: App.vue's state, handed over once. */
export interface KeyContext {
  paletteOpen: Ref<boolean>
  menu: Ref<unknown>
  selected: Ref<SceneTarget | null>
  mounted: PanelMount[]
  select(t: SceneTarget | null): void
  runAction(id: string): void
  goToRoom(i: number): void
  focus(t: SceneTarget): void
}

/** The page's keyboard: Ctrl K for the palette, Escape closes the nearest thing, the registry's single keys act. */
export function keyHandler(c: KeyContext): (e: KeyboardEvent) => void {
  return (e) => {
    // Every key typed in the director's terminal is Claude Code's, Escape included.
    if (e.target instanceof Element && e.target.closest('.director-terminal .xterm')) return
    const typing =
      e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement || e.target instanceof HTMLSelectElement
    if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'k') {
      e.preventDefault()
      c.paletteOpen.value = !c.paletteOpen.value
      return
    }
    if (e.key !== 'Escape' && (typing || e.ctrlKey || e.metaKey || e.altKey)) return
    if (e.key === 'Escape') {
      if (c.paletteOpen.value) c.paletteOpen.value = false
      else if (c.menu.value) c.menu.value = null
      else if (closeOnEscape(c.mounted)) return
      else if (closeLastPanel()) return
      else if (closeAwayOnEscape(c.mounted)) return
      else c.select(null)
      return
    }
    if (['1', '2', '3', '4', '5'].includes(e.key)) c.goToRoom(Number(e.key) - 1)
    const action = byKey(e.key)
    if (action) c.runAction(action.id)
    if (e.key.toLowerCase() === 'f' && c.selected.value) c.focus(c.selected.value)
  }
}
