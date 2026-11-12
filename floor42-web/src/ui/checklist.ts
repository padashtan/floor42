import type { ChecklistRow } from '../api/types'

/**
 * The machine checklist (what is missing for Floor42) shown once, at the first signed-in page of this browser; then
 * from Settings, in the sandbox panel. Remembered in this browser only: storage may be refused (private mode), and
 * then the list is simply shown again next time.
 */
export const SHOWN_KEY = 'floor42.checklist.shown'

function storage(): Storage | null {
  try {
    return window.localStorage ?? null
  } catch {
    return null
  }
}

export function checklistShown(): boolean {
  try {
    return storage()?.getItem(SHOWN_KEY) === '1'
  } catch {
    return false
  }
}

export function markChecklistShown(): void {
  try {
    storage()?.setItem(SHOWN_KEY, '1')
  } catch {
    // Refused storage: the list shows again at the next start, nothing else depends on it.
  }
}

/** True once per browser: the first time a signed-in page asks. */
export function firstShow(signedIn: boolean): boolean {
  if (!signedIn || checklistShown()) return false
  markChecklistShown()
  return true
}

/** The catalogue key of what a row is for; git inside the sandbox is told apart from the machine's own. */
export function purposeKey(row: ChecklistRow): string {
  return row.sandbox && row.id === 'git' ? 'checklist.for.git.sandbox' : `checklist.for.${row.id}`
}

/** The catalogue key of a row's name; a sandbox tool is its own name. */
export function itemKey(row: ChecklistRow): string | null {
  return row.sandbox ? null : `checklist.item.${row.id}`
}
