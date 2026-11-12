import { reactive } from 'vue'

/**
 * The panels the director pinned: they stay open and live while other panels open, and Escape leaves them alone.
 * Screen state only, for this page: nothing reaches the server. A pinned panel that closes (its cross) is unpinned.
 */
export const pinned = reactive<Record<string, boolean>>({})

export const isPinned = (id: string): boolean => !!pinned[id]

export function setPinned(id: string, on: boolean): void {
  if (on) pinned[id] = true
  else delete pinned[id]
}

export function togglePinned(id: string): boolean {
  setPinned(id, !pinned[id])
  return isPinned(id)
}
