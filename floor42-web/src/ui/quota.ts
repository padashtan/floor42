import type { UsageView } from '../api/types'
import { dateLocale, t, tr } from '../i18n'

/** One plan window as the top bar shows it: always a text, a colour and where the number comes from. */
export interface QuotaGauge {
  window: string
  text: string
  level: 'low' | 'medium' | 'high' | 'unknown'
  title: string
}

/** A clock time, with the day when it is not today: the director sees at once how old a reading is. */
export function clock(iso: string, now: number): string {
  const d = new Date(iso)
  const today = new Date(now).toDateString() === d.toDateString()
  const locale = dateLocale()
  return today
    ? d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleString(locale, { weekday: 'short', hour: '2-digit', minute: '2-digit' })
}

/**
 * The plan's windows for the top bar. A window is current while its reported reset is still ahead on this page's
 * clock (re-checked as time passes, not only when the server answered): past it, the last reading says nothing about
 * the new window, so it shows as unknown since that reset. Without any reading the gauge says why: Claude Code
 * reports the windows only during an agent session.
 */
export function quotaGauges(usage: UsageView | null, now: number): QuotaGauge[] {
  if (!usage) return []
  if (usage.windows.length === 0) {
    return [{ window: 'none', text: t('quota.none'), level: 'unknown', title: t('quota.noneTitle') }]
  }
  return usage.windows.map((w) => {
    const label = tr(`quota.window.${w.window}`, w.label)
    const read = clock(w.observedAt, now)
    const source = t('quota.source', { read, resets: clock(w.resetsAt, now) })
    if (Date.parse(w.resetsAt) <= now) {
      const since = clock(w.resetsAt, now)
      return {
        window: w.window,
        text: t('quota.stale', { label, since }),
        level: 'unknown',
        title: t('quota.staleTitle', { label, since, read, pct: Math.round(w.utilization * 100) }),
      }
    }
    const pct = Math.round(w.utilization * 100)
    const level = w.utilization >= usage.threshold ? 'high' : w.utilization >= 0.7 ? 'medium' : 'low'
    return {
      window: w.window,
      text: t('quota.current', { label, pct, read }),
      level,
      title: `${t('quota.currentTitle', { label, pct })}\n${source}`,
    }
  })
}
