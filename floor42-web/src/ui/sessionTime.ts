import type { DirectorSessionView } from '../api/types'

/** The warning starts this long before the end. */
export const WARN_MS = 5 * 60 * 1000

/** When a browser session ends unless used again: the earlier of its idle and absolute limits. */
export function sessionEnd(s: Pick<DirectorSessionView, 'idleUntil' | 'expires'>): number | null {
  const ends = [s.idleUntil, s.expires].filter((x): x is string => !!x).map((x) => Date.parse(x))
  return ends.length ? Math.min(...ends) : null
}

/** A clock time, not a countdown: idle time is renewed by use, so a countdown would jump. */
export function clock(ms: number, locale: string): string {
  return new Date(ms).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
}

export const ending = (ms: number, now: number): boolean => ms - now <= WARN_MS
