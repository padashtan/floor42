import { describe, expect, it } from 'vitest'
import type { UsageView } from '../api/types'
import { setLang } from '../i18n'
import { quotaGauges } from './quota'

/** A journal sample: the last readings, taken at 13:54 UTC during a session. */
function usage(windows: UsageView['windows']): UsageView {
  return { windows, threshold: 0.95, days: [], missions: [], accountUsageUrl: 'https://claude.ai/settings/usage', night: null }
}

const fiveHour = {
  window: 'five_hour',
  label: '5-hour',
  utilization: 0.77,
  resetsAt: '2026-10-31T16:30:00Z',
  observedAt: '2026-10-31T13:54:40Z',
  current: true,
  status: 'allowed',
  expectedIncrease: null,
}
const week = {
  window: 'seven_day',
  label: 'weekly',
  utilization: 0.49,
  resetsAt: '2026-11-02T01:00:00Z',
  observedAt: '2026-10-31T13:54:40Z',
  current: true,
  status: null,
  expectedIncrease: null,
}

describe('quota gauges', () => {
  it('shows a measured window with its percentage, its reading time and source in the tooltip', () => {
    setLang('en')
    const [g] = quotaGauges(usage([fiveHour]), Date.parse('2026-10-31T14:00:00Z'))
    expect(g.level).toBe('medium')
    expect(g.text).toBe('5h 77%')
    expect(g.title).toMatch(/Read at \d\d:\d\d/)
    expect(g.title).toContain('measured by Claude Code')
    expect(g.title).toContain('rate_limit_event')
  })

  it('turns a window unknown once its reset has passed on the page clock, even if the server said current', () => {
    setLang('fr')
    const [g, w] = quotaGauges(usage([fiveHour, week]), Date.parse('2026-10-31T18:14:00Z'))
    expect(g.level).toBe('unknown')
    expect(g.text).toBe('5h ?')
    // When it turned unknown is in the tooltip.
    expect(g.title).toMatch(/\d\d:\d\d/)
    expect(g.text).not.toContain('77')
    expect(g.title).toContain('77 %')
    // The weekly window still runs: its reading stays.
    expect(w.text).toBe('7j 49%')
    expect(w.level).toBe('low')
  })

  it('says why there is nothing rather than showing nothing when no session has reported the windows', () => {
    setLang('en')
    const [g] = quotaGauges(usage([]), Date.now())
    expect(g.level).toBe('unknown')
    expect(g.text).toBe('quota –')
    expect(g.title).toContain('only during an agent session')
  })

  it('reds a window at the hold threshold and keeps an unknown window name as the server gives it', () => {
    setLang('en')
    const odd = { ...fiveHour, window: 'seven_day_opus', label: 'seven day opus', utilization: 0.96 }
    const [g] = quotaGauges(usage([odd]), Date.parse('2026-10-31T14:00:00Z'))
    expect(g.level).toBe('high')
    expect(g.text.startsWith('seven day opus 96%')).toBe(true)
  })

  it('shows nothing until the usage has loaded', () => {
    expect(quotaGauges(null, Date.now())).toEqual([])
  })
})
