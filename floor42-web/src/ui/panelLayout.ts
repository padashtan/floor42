/**
 * The layout rule of the HUD, in one place. A panel is one of three kinds:
 *
 * - a main panel (every panel of the table in panelMounts.ts but the docked ones): one at a time. Opening one closes
 *   the others, so two main panels never sit on top of each other and none covers another's close button;
 * - a docked panel (the event log, the to-do list, the selection, the away summary), or a panel the director pinned
 *   (state/pinned.ts): always on screen, never closed
 *   by a main panel, nor by Escape for a pinned one;
 * - an overlay (the tutorial, the palette, the context menu, the toasts and the confirmations): above everything,
 *   mounted outside the table, so this rule never touches it.
 *
 * Escape closes the top one (panelMounts.ts, closeOnEscape). Pure functions: the rule is tested without a browser.
 */
import { isPinned } from '../state/pinned'

export const DOCKED: readonly string[] = ['log', 'todo', 'selection', 'away', 'remote']

export const isMain = (id: string): boolean => !DOCKED.includes(id) && !isPinned(id)

/**
 * The main panels to close after the set of shown panels changed from `before` to `now`: all the shown main panels
 * but the newest one. Opened in the same tick, the one asked for last stays (`recency`, a counter of the requests),
 * then the last of `order` (the table's order, later is above). Nothing is closed when nothing new opened.
 */
export function superseded(
  now: readonly string[],
  before: readonly string[],
  order: readonly string[],
  recency: (id: string) => number = () => 0,
): string[] {
  const main = now.filter(isMain)
  const fresh = main.filter((id) => !before.includes(id))
  if (fresh.length === 0) return []
  const keep = [...fresh].sort((a, b) => recency(a) - recency(b) || order.indexOf(a) - order.indexOf(b)).at(-1)!
  return main.filter((id) => id !== keep)
}

/** Panels opened from another panel: closing the child brings its parent back, as a step back. */
export const PARENTS: Readonly<Record<string, string | readonly string[]>> = {
  caps: 'settings',
  // The objections (ADR-0060), opened from the board: closing them brings the board back.
  objections: 'board',
  'agent-settings': 'profile',
  // The file viewer (ADR-0051), opened from a list of files: closing it brings that list back.
  viewer: ['deliverables', 'workshop', 'desk'],
}

/** The way back from a child panel to the parent it replaced. */
export class BackLinks {
  private back = new Map<string, string>()

  /** `kept` opened and closed `closed`: when it is a child of one of them, closing it will lead back. */
  replaced(kept: string | undefined, closed: readonly string[]): void {
    if (!kept) return
    const parents = PARENTS[kept]
    const parent = (typeof parents === 'string' ? [parents] : (parents ?? [])).find((p) => closed.includes(p))
    if (parent) this.back.set(kept, parent)
    else this.back.delete(kept)
  }

  /** `id` closed and `open` main panels remain: the parent to reopen, only when nothing else took the place. */
  closed(id: string, open: readonly string[]): string | null {
    const parent = this.back.get(id) ?? null
    this.back.delete(id)
    return parent && open.length === 0 ? parent : null
  }
}

/** The main panel kept after `closed` were closed for it. */
export const keptOf = (now: readonly string[], closed: readonly string[]): string | undefined =>
  now.filter(isMain).find((id) => !closed.includes(id))

/** The context menu after a press on `target`: still open when the press is inside it, gone for any other press. */
export function menuAfterPress<T>(menu: T | null, target: { closest(selector: string): unknown } | null): T | null {
  return menu !== null && !target?.closest('.panel.menu') ? null : menu
}

/** The part of an element the focus return needs: any DOM element has it, a test can fake it. */
export interface Focusable {
  isConnected: boolean
  focus(options?: { preventScroll: boolean }): void
  closest(selector: string): unknown
}

/** What had the focus when a main panel opened, so closing it gives the focus back (a keyboard user is not lost). */
export class FocusReturn {
  private target: Focusable | null = null

  /**
   * A panel opened while none was: remembers the control that opened it. A control inside a menu that is about to
   * close is replaced by `fallback` (the menu's button).
   */
  remember(active: Focusable | null, fallback: () => Focusable | null): void {
    if (this.target) return
    this.target = active?.closest('.bar-dropdown') ? fallback() : (active ?? fallback())
  }

  /** The last main panel closed: focus goes back to the remembered control, or to `fallback` when it is gone. */
  restore(fallback: () => Focusable | null = () => null): void {
    const el = this.target
    this.target = null
    const back = el?.isConnected ? el : fallback()
    back?.focus({ preventScroll: true })
  }

  get remembering(): boolean {
    return this.target !== null
  }
}
