import { describe, expect, it } from 'vitest'
import { en } from '../i18n/en'
import { fr } from '../i18n/fr'
import { grouped } from './actions'
import { barBudget, barFits, barMinimum, buttonWidth, COMPACT_BAND_END, COMPACT_UPTO, directText, isCompact, EDGE, LOG_WIDTH, menuLeft } from './topBarLayout'

/**
 * The bar's buttons as TopBar.vue writes them at this width, at their widest: shortcut, label (not when compact), a
 * two-digit count, the menu arrow.
 */
function buttons(catalogue: Record<string, string>, viewport = 2560): string[] {
  const { direct, menus } = grouped()
  return [
    '●',
    ...direct.map((a) => `${directText(a.key, catalogue[a.label], viewport)} 99`),
    ...menus.map((m) => `${catalogue[`palette.g.${m.group}`]} ! ▾`),
    '⌕',
  ]
}
/** Build, daemon and mission badges: they shrink. */
const BADGES = 3

/** The measured numbers at their longest, as quota.ts and SpendIndicator.vue write them: they never shrink. */
function measures(catalogue: Record<string, string>): string[] {
  const fill = (key: string, params: Record<string, string>) =>
    catalogue[key].replace(/\{(\w+)\}/g, (_, name: string) => params[name] ?? '')
  const gauge = (window: string) =>
    [fill('quota.current', { label: catalogue[`quota.window.${window}`], pct: '100' }), fill('quota.stale', { label: catalogue[`quota.window.${window}`] })]
      .sort((a, b) => b.length - a.length)[0]
  const money = '0.0000'
  const spend = [
    fill('cost.spend', { spent: money, cap: money }),
    fill('cost.spendNoCap', { spent: money }),
    fill('cost.spendUndecided', { spent: money }),
    catalogue['cost.top'],
  ].sort((a, b) => b.length - a.length)[0]
  // Both windows in one button, a gap (about one letter) between them.
  return [`${gauge('five_hour')} ${gauge('seven_day')}`, spend]
}

describe('top bar layout', () => {
  it('leaves room for the log beside the bar only when the log sits at the top', () => {
    expect(barBudget(1280)).toBe(1280 - 2 * EDGE)
    expect(barBudget(1700)).toBe(1700 - 2 * EDGE)
    expect(barBudget(1920)).toBe(1920 - 2 * EDGE - LOG_WIDTH - EDGE)
  })

  it('keeps every button and every measured number whole, on screen and clear of the log from 1280 to 2560 px, in both languages', () => {
    for (const catalogue of [en, fr] as Record<string, string>[]) {
      const m = measures(catalogue)
      for (let width = 1280; width <= 2560; width++) {
        expect(barFits(width, buttons(catalogue, width), BADGES, m), `${catalogue['palette.g.work']} at ${width} px`).toBe(
          true,
        )
      }
    }
  })

  it('shows the badges whole on a wide screen, even at their longest', () => {
    // The shrinking badges at their longest (a dirty build, SIMULATION SEULEMENT, a mission), the quotas and the spend
    // being measures already counted whole.
    const longest = ['v1.6.0-SNAPSHOT · 0123abc*', 'SIMULATION SEULEMENT', 'M-99 · SIMULATED'].reduce(
      (n, b) => n + buttonWidth(b),
      0,
    )
    expect(barBudget(2560) - barMinimum(buttons(fr), BADGES, measures(fr)) + BADGES * 40).toBeGreaterThan(longest)
  })

  it('would refuse a bar crowded with buttons', () => {
    const crowded = [...buttons(en), ...Array.from({ length: 12 }, () => 'A new panel')]
    expect(barFits(1920, crowded, BADGES, measures(en))).toBe(false)
  })

  it('shows the direct buttons with their labels where the bar has room, at 1600 and 1920 px in both languages', () => {
    expect(directText('D', 'Desk', 1280)).toBe('□')
    expect(directText('D', 'Desk', 1600)).toBe('□ Desk')
    expect(directText('D', 'Desk', 1800)).toBe('□')
    expect(directText(undefined, 'Resume', 1920)).toBe('□ Resume')
    expect(isCompact(COMPACT_UPTO)).toBe(true)
    expect(isCompact(COMPACT_UPTO + 1)).toBe(false)
    expect(isCompact(COMPACT_BAND_END + 1)).toBe(false)
  })

  it('would refuse a measured number too long to stay whole at 1280 px', () => {
    expect(barFits(1280, buttons(fr, 1280), BADGES, [...measures(fr), 'x'.repeat(200)])).toBe(false)
  })

  it('keeps an open menu inside the window', () => {
    expect(menuLeft(100, 280, 1280)).toBe(100)
    expect(menuLeft(1150, 280, 1280)).toBe(1280 - EDGE - 280)
    expect(menuLeft(-20, 280, 1280)).toBe(EDGE)
  })
})
