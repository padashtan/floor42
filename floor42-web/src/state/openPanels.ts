import { computed, reactive, ref, type WritableComputedRef } from 'vue'
import { isPinned } from './pinned'

/**
 * Which registry panels are open (ui/actions.ts), by action id: a new panel shows with `v-if="panels.<id>"` and closes
 * with `panels.<id> = false`; App.vue's older flags are views of the same record ({@link panelFlag}). Escape closes the
 * last one opened. Screen state only: nothing reaches the server.
 */
export const panels = reactive<Record<string, boolean>>({})
/** The end-of-mission report (ADR-0039) of this mission, open; null when closed. */
export const reportOf = ref<string | null>(null)
const opened: string[] = []
const asked = reactive(new Map<string, number>())
let requests = 0

/** When `id` was last asked to open, as a counter (0 when never): of two panels opened together, the larger stays. */
export const requestOf = (id: string): number => asked.get(id) ?? 0

/** Notes a request to open `id` (a panel held by a ref has no setPanel call). */
export const stampRequest = (id: string): void => void asked.set(id, ++requests)

export function setPanel(id: string, open: boolean): void {
  if (open) stampRequest(id)
  panels[id] = open
  const i = opened.indexOf(id)
  if (i >= 0) opened.splice(i, 1)
  if (open) opened.push(id)
}

/** Both panels are open and `id` was opened after `other`: Escape should close `id` first. */
export function openedAfter(id: string, other: string): boolean {
  return !!panels[id] && !!panels[other] && opened.lastIndexOf(id) > opened.lastIndexOf(other)
}

/** Closes the last panel opened that is still open; false when none is. */
export function closeLastPanel(): boolean {
  // A pinned panel stays in the list (it is closed by its cross or once unpinned), Escape goes on to the next one.
  for (let i = opened.length - 1; i >= 0; i--) {
    const id = opened[i]!
    if (!panels[id]) opened.splice(i, 1)
    else if (!isPinned(id)) {
      opened.splice(i, 1)
      panels[id] = false
      return true
    }
  }
  return false
}

/** A panel's open flag as a ref (`deskOpen.value = true`), read from and written to {@link panels}. */
export function panelFlag(id: string): WritableComputedRef<boolean> {
  return computed({ get: () => !!panels[id], set: (open) => setPanel(id, open) })
}
