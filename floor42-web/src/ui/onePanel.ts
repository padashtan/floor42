import { computed, nextTick, watch } from 'vue'
import { requestOf } from '../state/openPanels'
import { setPinned } from '../state/pinned'
import { BackLinks, FocusReturn, isMain, keptOf, superseded } from './panelLayout'
import type { PanelMount } from './panelMounts'

const BAR_BUTTON = '.bar-nav button[data-menu]'
const OPEN_MENU_BUTTON = '.bar-nav button[data-menu][aria-expanded="true"]'
/** A mounted panel's root: App.vue marks each one with its id (the attribute falls through to the component's root). */
const PANEL = (id: string) => `.hud > [data-panel="${id}"]`

const find = (selector: string): HTMLElement | null => document.querySelector<HTMLElement>(selector)

/**
 * The layout rule at work (ui/panelLayout.ts) over the mounted panels: the panel that opens closes the others, a child
 * panel (Caps from the settings) leads back to its parent when it closes, and the focus goes back to the control that
 * opened the first panel, or to the bar when that control is gone.
 */
export function useOnePanel(mounted: PanelMount[]): { shown: () => string[] } {
  const focus = new FocusReturn()
  const links = new BackLinks()
  const shown = computed(() => mounted.filter((p) => p.shown()).map((p) => p.id))
  const mount = (id: string) => mounted.find((p) => p.id === id)

  watch(shown, (now, before) => {
    const closed = superseded(now, before, mounted.map((p) => p.id), requestOf)
    for (const id of closed) mount(id)?.close?.()
    links.replaced(closed.length ? keptOf(now, closed) : undefined, closed)
    const open = now.filter(isMain)
    const parent = before.filter((id) => isMain(id) && !now.includes(id)).map((id) => links.closed(id, open)).find(Boolean)
    if (parent) return comeBack(parent)
    if (open.length) {
      // A panel that stays open (its subject changed, agent A to B) keeps the focus: only a real close gives it back.
      focus.remember(active(), () => find(OPEN_MENU_BUTTON))
      const opened = open.filter((id) => !before.includes(id) && !closed.includes(id)).at(-1)
      if (opened) void focusIn(opened)
    } else if (focus.remembering) focus.restore(() => find(BAR_BUTTON))
  })

  // A pinned panel that closed (its cross) is no longer pinned.
  watch(shown, (now, before) => before.filter((id) => !now.includes(id)).forEach((id) => setPinned(id, false)))

  async function comeBack(parent: string): Promise<void> {
    mount(parent)?.open?.()
    await nextTick()
    find(`${PANEL(parent)} > .title button.close`)?.focus({ preventScroll: true })
  }

  /**
   * A panel that just opened takes the focus (its frame, when it did not put the focus on a control of its own): a
   * keyboard or a screen reader user lands in it, and Tab goes through its controls. Closing it gives the focus back.
   * Found by its id, so a docked panel (the away summary) never takes the focus meant for the one that opened.
   */
  async function focusIn(id: string): Promise<void> {
    await nextTick()
    const panel = find(PANEL(id))
    if (!panel || panel.contains(document.activeElement)) return
    panel.setAttribute('tabindex', '-1')
    panel.focus({ preventScroll: true })
  }

  return { shown: () => shown.value }
}

function active(): HTMLElement | null {
  const el = document.activeElement
  return el instanceof HTMLElement && el !== document.body ? el : null
}
