import { describe, expect, it } from 'vitest'
import type { CostLine } from '../api/types'
import { shownAmount, SIMULATED_BASIS, amount, capChoice, counted, defaultCap, lowerBound, parseUsd, spendLevel, tokens, usd } from './costs'

const line = (over: Partial<CostLine> = {}): CostLine => ({
  key: 'M-1',
  name: 'x',
  usdEstimated: 0.1234,
  costBasis: 'estimated by Claude Code',
  inputTokens: 0,
  outputTokens: 0,
  tokensBasis: 'provided by Claude Code',
  sessions: 2,
  sessionsWithoutCost: 0,
  usdBound: null,
  sessionsOpen: 0,
  sessionsBasis: 'measured by Floor42',
  ...over,
})

describe('cost overview', () => {
  it('places spending against the cap, and says when there is none', () => {
    expect(spendLevel(1, null)).toBe('uncapped')
    expect(spendLevel(1, 5)).toBe('ok')
    expect(spendLevel(4, 5)).toBe('near')
    expect(spendLevel(5, 5)).toBe('over')
    expect(spendLevel(6, 5)).toBe('over')
  })

  it('writes cents with four decimals and larger amounts with two', () => {
    expect(usd(0.01234)).toBe('0.0123')
    expect(usd(12.345)).toBe('12.35')
  })

  it('never shows a sum missing some sessions as complete, nor an absent cost as zero', () => {
    expect(lowerBound(line())).toBe('')
    expect(amount(line({ sessionsWithoutCost: 1 }))).toBe('≥ 0.1234')
    expect(amount(line({ costBasis: 'unavailable', usdEstimated: 0 }))).toBeNull()
  })

  it('reads the default cap from the setting, none meaning none', () => {
    expect(defaultCap('5.0')).toBe(5)
    expect(defaultCap('none')).toBeNull()
    expect(defaultCap(undefined)).toBeNull()
    expect(defaultCap('abc')).toBeNull()
  })

  it('approves only with a valid cap or the explicit choice of none', () => {
    expect(parseUsd(' 2,5 ')).toBe(2.5)
    expect(parseUsd('')).toBeNull()
    expect(parseUsd('-1')).toBeNaN()
    expect(capChoice('3', false)).toEqual({ budgetUsd: 3, noCap: null })
    expect(capChoice('', false)).toBeNull()
    expect(capChoice('zero', false)).toBeNull()
    expect(capChoice('0', false)).toBeNull()
    expect(capChoice('', true)).toEqual({ budgetUsd: null, noCap: true })
    expect(capChoice('3', true)).toEqual({ budgetUsd: null, noCap: true })
  })

  it('writes a simulated mission as 0, never as an estimate', () => {
    const line = (costBasis: string, usdEstimated: number) =>
      ({ costBasis, usdEstimated, sessionsWithoutCost: 0 }) as Parameters<typeof shownAmount>[0]
    expect(shownAmount(line(SIMULATED_BASIS, 0))).toBe('0')
    expect(shownAmount(line('estimated by Claude Code', 0.25))).toBe('≈ 0.2500')
    expect(shownAmount(line('unavailable', 0))).toBe('—')
  })

  it('shortens token counts', () => {
    expect(tokens(999)).toBe('999')
    expect(tokens(12_345)).toBe('12.3k')
    expect(tokens(2_500_000)).toBe('2.50M')
  })
})

describe('counted', () => {
  it('adds the caps of the sessions that reported no cost to the estimates, for the budget only', () => {
    expect(counted({ spentUsdEstimated: 0.04, spentUsdBound: 0.46 })).toBeCloseTo(0.5)
    expect(spendLevel(counted({ spentUsdEstimated: 0, spentUsdBound: 0.5 }), 0.5)).toBe('over')
  })

  it('reads a view without bounds (an older daemon) as estimates only', () => {
    expect(counted({ spentUsdEstimated: 0.2 } as never)).toBe(0.2)
  })
})

describe('shownAmount with bounds', () => {
  it('writes the caps of the sessions without a cost apart from the estimate', () => {
    expect(shownAmount(line({ usdEstimated: 0.04, sessionsWithoutCost: 1, usdBound: 0.46 }))).toBe('≈ ≥ 0.0400 + ≤ 0.4600')
    expect(shownAmount(line({ costBasis: 'unavailable', usdEstimated: 0, sessionsWithoutCost: 1, usdBound: 0.5 }))).toBe('≤ 0.5000')
  })
})
