import type { Key } from '../i18n'
import type { StandupLine } from '../api/types'
import { standupEn } from '../i18n/standup.en'

/**
 * The 9 o'clock standup (ADR-0078): when it opens by itself, and how a line reads. The day it last opened is kept in
 * this browser only, never in the journal; storage may be missing or blocked, then it opens at each load after 09:00.
 */
export const STANDUP_KEY = 'floor42.standup.shown'
export const STANDUP_HOUR = 9

type Store = Pick<Storage, 'getItem' | 'setItem'>

function storage(): Store | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

/** The local calendar day, YYYY-MM-DD. */
export const localDay = (d: Date): string =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

/** After 09:00 local and not yet opened today. */
export function due(now: Date, store: Store | null = storage()): boolean {
  if (now.getHours() < STANDUP_HOUR) return false
  try {
    return store?.getItem(STANDUP_KEY) !== localDay(now)
  } catch {
    return true
  }
}

/** Keeps that it opened today; false when the browser would not keep it. */
export function markShown(now: Date, store: Store | null = storage()): boolean {
  try {
    if (!store) return false
    store.setItem(STANDUP_KEY, localDay(now))
    return true
  } catch {
    return false
  }
}

/** The text of one line in the page's language: the server sent a code, never a sentence. */
export function lineText(l: StandupLine, t: (key: Key, params?: Record<string, string | number>) => string): string {
  // A code this page does not know (a newer daemon) is shown as its code rather than as a missing key.
  const key = (`standup.code.${l.code}` in standupEn ? `standup.code.${l.code}` : 'standup.code.UNKNOWN') as Key
  return t(key, { ticket: l.ticket ?? '', text: l.text ?? '', count: l.count ?? 0, code: l.code })
    .replace(/ {2,}/g, ' ')
    .replace(/[ :：]+$/, '')
    .trim()
}
