import type { ContextReading } from '../api/types'
import { t } from '../i18n'

/**
 * What the context gauge shows (W18). The tokens are measured by Claude Code; the share is an estimate against the
 * model's documented window, and there is none when the window is unknown: never a percentage made up.
 */
export interface Gauge {
  usedTokens: number
  windowTokens: number | null
  /** 0 to 1, capped at 1; null without a known window. */
  share: number | null
  /** Rounded down, so 99.6% never shows as a full 100%; null without a known window. */
  percent: number | null
  level: 'low' | 'medium' | 'high' | 'unknown'
}

/** Amber from 60% of the window, red from 85%. */
export const MEDIUM = 0.6
export const HIGH = 0.85

export function gauge(reading: Pick<ContextReading, 'usedTokens' | 'windowTokens'>): Gauge {
  const used = Math.max(0, reading.usedTokens)
  const window = reading.windowTokens && reading.windowTokens > 0 ? reading.windowTokens : null
  if (window === null) return { usedTokens: used, windowTokens: null, share: null, percent: null, level: 'unknown' }
  const share = Math.min(1, used / window)
  return {
    usedTokens: used,
    windowTokens: window,
    share,
    percent: Math.floor(share * 100),
    level: share >= HIGH ? 'high' : share >= MEDIUM ? 'medium' : 'low',
  }
}

/** The tooltip: the tokens are measured, the percentage only estimated, and none without a known window. */
export function contextTip(reading: ContextReading): string {
  const g = gauge(reading)
  const params = { used: tokens(g.usedTokens), model: reading.model }
  return g.windowTokens === null
    ? t('ctx.tipUnknown', params)
    : t('ctx.tip', { ...params, window: tokens(g.windowTokens) })
}

/** Tokens a person reads at a glance: 950, 19.2k, 200k, 1M, 1.25M. */
export function tokens(n: number): string {
  if (n < 1000) return String(Math.round(n))
  if (n < 1_000_000) {
    const k = n / 1000
    return `${k < 100 ? trim(k.toFixed(1)) : Math.round(k)}k`
  }
  return `${trim((n / 1_000_000).toFixed(2))}M`
}

function trim(s: string): string {
  return s.includes('.') ? s.replace(/0+$/, '').replace(/\.$/, '') : s
}
