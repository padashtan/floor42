import { describe, expect, it } from 'vitest'
import { DELEGATION_LEAD_MS, soonest, TOKEN_LEAD_MS, warnKey } from './iamEndings'

const NOW = 1_000_000_000
const min = (m: number) => m * 60_000

describe('IAM endings', () => {
  it('nothing to warn outside the lead', () => {
    expect(soonest([{ id: 'a', endMs: NOW + min(31) }], NOW, DELEGATION_LEAD_MS)).toEqual([])
    expect(soonest([{ id: 'a', endMs: NOW + min(11) }], NOW, TOKEN_LEAD_MS)).toEqual([])
  })

  it('picks every end inside its lead, soonest first, and not an end already past', () => {
    const ends = [
      { id: 'late', endMs: NOW + min(20) },
      { id: 'soon', endMs: NOW + min(5) },
      { id: 'past', endMs: NOW - min(1) },
    ]
    expect(soonest(ends, NOW, DELEGATION_LEAD_MS).map((e) => e.id)).toEqual(['soon', 'late'])
  })

  it('the same end is announced once; a moved end is announced again', () => {
    expect(warnKey('token', 'ana', NOW)).toBe(warnKey('token', 'ana', NOW))
    expect(warnKey('token', 'ana', NOW)).not.toBe(warnKey('token', 'ana', NOW + 1))
    expect(warnKey('token', 'ana', NOW)).not.toBe(warnKey('delegation', 'ana', NOW))
  })
})
