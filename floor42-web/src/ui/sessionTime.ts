import type { DirectorSessionView } from '../api/types'
import { t } from '../i18n'

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

/** An end with its day when it is not today: "14:32", "tomorrow 09:10", "12 Nov 14:32" (a delegation lasts 30 days). */
export function when(ms: number, locale: string, now: number): string {
  const time = clock(ms, locale)
  const day = (x: number) => new Date(x).setHours(0, 0, 0, 0)
  const days = Math.round((day(ms) - day(now)) / 86_400_000)
  if (days === 0) return time
  if (days === 1) return t('session.when.tomorrow', { time })
  return `${new Date(ms).toLocaleDateString(locale, { day: 'numeric', month: 'short' })} ${time}`
}

export const ending = (ms: number, now: number, lead = WARN_MS): boolean => ms - now <= lead
