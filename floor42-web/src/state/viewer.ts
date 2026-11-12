import { ref, watch } from 'vue'
import type { DeliverablesView, DeliveredFile } from '../api/types'

/**
 * The file on screen in the viewer panel (ADR-0051), or null when it is closed. Opened from a list of files (the
 * deliverables, the workshop's pending changes, the director's own pending files); read only through the daemon's routes,
 * which decide what may be read. Screen state only: nothing is journaled.
 */
export interface ViewedFile {
  mission: string
  agent: string
  path: string
  /** Committed on the agent's branch, or present only in its worktree. */
  committed: boolean
  /** One of the director's own pending files in the project (agent empty): read through the director's files route. */
  own?: boolean
  /** The listing's entry and caps when the caller has them; the panel reads the listing otherwise. */
  file?: DeliveredFile
  view?: Pick<DeliverablesView, 'maxViewText' | 'maxViewBytes'>
}

/**
 * Where the viewer was opened from, so the list it replaced comes back as it was and the focus returns to the button
 * that opened it (a keyboard user is not lost). `state` is the list's own: its section, its options.
 */
export interface Opener {
  from: 'deliverables' | 'workshop' | 'desk'
  key: string
  state: Record<string, string | boolean | null>
}

export const viewing = ref<ViewedFile | null>(null)

let opener: Opener | null = null
let closedAt = 0

/** The key of a View button: whose file, which path, committed or not. */
export const viewKey = (agent: string, path: string, committed: boolean): string =>
  `${agent}:${committed ? 'c' : 'w'}:${path}`

export function openViewer(file: ViewedFile, from: Opener | null = null): void {
  opener = from
  viewing.value = file
}

// Synchronous: a viewer opened and closed within one tick still stamps its close.
watch(
  viewing,
  (v) => {
    if (v === null) closedAt = Date.now()
  },
  { flush: 'sync' },
)

/**
 * The opener of the viewer that just closed, for the list `from` coming back in its place; null otherwise. Taken once,
 * and only right after the close: a list opened later starts as usual.
 */
export function takeOpener(from: Opener['from'], now = Date.now()): Opener | null {
  const o = opener
  if (!o || o.from !== from || viewing.value !== null || now - closedAt > 2000) return null
  opener = null
  return o
}

/** Focuses the View button of `key` inside `root` once it is on screen; false when it never showed. */
export async function focusViewButton(root: () => HTMLElement | null, key: string, tries = 20): Promise<boolean> {
  for (let i = 0; i < tries; i++) {
    const button = root()?.querySelector<HTMLElement>(`[data-view-key="${CSS.escape(key)}"]`)
    if (button) {
      button.focus({ preventScroll: false })
      return true
    }
    await new Promise((r) => setTimeout(r, 50))
  }
  return false
}
