import { ref } from 'vue'
import type { AwayView } from '../api/types'

/** Whether the summary is on screen, and how to close it: Escape (ui/panelMounts.ts) reaches it from there. */
export const awayShown = ref(false)
export const awayActions: { close: () => void } = { close: () => {} }

/**
 * "While you were away": the journal sequence the director last acknowledged is kept in this browser only, never in
 * the journal. Storage may be missing or blocked (a private window): then the summary simply starts from now.
 */
export const ACK_KEY = 'floor42.away.acknowledged'

type Store = Pick<Storage, 'getItem' | 'setItem'>

function storage(): Store | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

/** The last acknowledged sequence, or null when none was kept (first visit, storage blocked, a value not a number). */
export function acknowledged(store: Store | null = storage()): number | null {
  try {
    const v = store?.getItem(ACK_KEY)
    if (v === null || v === undefined || !/^\d+$/.test(v)) return null
    return Number(v)
  } catch {
    return null
  }
}

/** Keeps the sequence the director acknowledged; false when the browser would not keep it. */
export function acknowledge(sequence: number, store: Store | null = storage()): boolean {
  try {
    if (!store) return false
    store.setItem(ACK_KEY, String(sequence))
    return true
  } catch {
    return false
  }
}

/**
 * Whether the summary has anything new to tell: something happened after the acknowledged sequence. What waits or is
 * blocked now is told with it, but alone it is not news: it reopened the summary on every reload while a question
 * waited, although nothing had happened since the director last looked.
 */
export function hasNews(s: AwayView): boolean {
  if (s.delivered.length > 0 || s.merged.length > 0 || s.sessions > 0) return true
  return s.until > s.since && (s.waiting.length > 0 || s.blocked.length > 0)
}

/**
 * The cost line, only when Claude Code reported one: an estimate, said so. Null when no session reported a cost, so
 * nothing is shown rather than a made-up zero.
 */
export function costLine(s: AwayView, label: (amount: string) => string): string | null {
  if (s.costUsdEstimated === null || s.costUsdEstimated === undefined) return null
  return label(s.costUsdEstimated < 0.01 ? '< 0.01' : s.costUsdEstimated.toFixed(2))
}

/** How long ago something happened, said briefly: 5 min, 3 h, 2 d. */
export function ago(at: string | null, now: number): string {
  if (!at) return ''
  const s = Math.max(0, Math.floor((now - Date.parse(at)) / 1000))
  if (s < 3600) return `${Math.max(1, Math.floor(s / 60))} min`
  if (s < 86400) return `${Math.floor(s / 3600)} h`
  return `${Math.floor(s / 86400)} d`
}
