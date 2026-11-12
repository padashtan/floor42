import { describe, expect, it } from 'vitest'
import { setLang } from '../i18n'
import { contextTip, gauge, tokens } from './contextGauge'

describe('context gauge', () => {
  it('estimates the share of the documented window from the measured tokens', () => {
    expect(gauge({ usedTokens: 19_204, windowTokens: 1_000_000 })).toEqual({
      usedTokens: 19_204,
      windowTokens: 1_000_000,
      share: 0.019204,
      percent: 1,
      level: 'low',
    })
  })

  it('shows no percentage for an unknown window, never a guess', () => {
    const g = gauge({ usedTokens: 50_000, windowTokens: null })
    expect(g.share).toBeNull()
    expect(g.percent).toBeNull()
    expect(g.level).toBe('unknown')
    expect(gauge({ usedTokens: 50_000, windowTokens: 0 }).level).toBe('unknown')
  })

  it('turns amber from 60% and red from 85%', () => {
    expect(gauge({ usedTokens: 119_999, windowTokens: 200_000 }).level).toBe('low')
    expect(gauge({ usedTokens: 120_000, windowTokens: 200_000 }).level).toBe('medium')
    expect(gauge({ usedTokens: 170_000, windowTokens: 200_000 }).level).toBe('high')
  })

  it('rounds down and caps at the window', () => {
    expect(gauge({ usedTokens: 199_200, windowTokens: 200_000 }).percent).toBe(99)
    const over = gauge({ usedTokens: 250_000, windowTokens: 200_000 })
    expect(over.share).toBe(1)
    expect(over.percent).toBe(100)
    expect(over.usedTokens).toBe(250_000)
  })

  it('says the tokens are measured and the percentage only estimated', () => {
    setLang('en')
    const base = { session: 'M-1:s', agent: 'chris', mission: 'M-1', at: '2026-10-31T10:00:00Z' }
    const known = contextTip({ ...base, model: 'claude-opus-5-5', usedTokens: 19_204, windowTokens: 1_000_000 })
    expect(known).toContain('Measured by Claude Code: 19.2k tokens')
    expect(known).toContain('estimated against the documented window of claude-opus-5-5 (1M)')
    const unknown = contextTip({ ...base, model: 'sonnet', usedTokens: 19_204, windowTokens: null })
    expect(unknown).toContain('no percentage')
    expect(unknown).not.toContain('%')
  })

  it('writes tokens short', () => {
    expect(tokens(950)).toBe('950')
    expect(tokens(19_204)).toBe('19.2k')
    expect(tokens(20_000)).toBe('20k')
    expect(tokens(200_000)).toBe('200k')
    expect(tokens(1_000_000)).toBe('1M')
    expect(tokens(1_250_000)).toBe('1.25M')
  })
})
