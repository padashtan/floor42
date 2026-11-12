import { describe, expect, it } from 'vitest'
import type { ContributionView, ReportCheck } from '../api/types'
import { checksNewestFirst, latestFirst, roundsNewestFirst } from './newestFirst'

const check = (command: string, at: string) => ({ command, at }) as ReportCheck
const say = (member: string, round: number, kind: ContributionView['kind'] = 'ANALYSIS') =>
  ({ member, round, kind }) as ContributionView

describe('newest first', () => {
  it('orders the checks by time, the latest on top, without touching the source', () => {
    const src = [check('a', '2026-11-01T10:00:00Z'), check('c', '2026-11-03T10:00:00Z'), check('b', '2026-11-02T10:00:00Z')]
    expect(checksNewestFirst(src).map((c) => c.command)).toEqual(['c', 'b', 'a'])
    expect(src.map((c) => c.command)).toEqual(['a', 'c', 'b'])
  })

  it('puts the latest round first and leaves the decision records out', () => {
    const rounds = roundsNewestFirst([say('x', 1), say('y', 1), say('x', 2), say('z', 3, 'SYNTHESIS')])
    expect(rounds.map((r) => r.round)).toEqual([2, 1])
    expect(rounds[1].items.map((c) => c.member)).toEqual(['x', 'y'])
  })

  it('reverses any event list into a copy', () => {
    const src = [1, 2, 3]
    expect(latestFirst(src)).toEqual([3, 2, 1])
    expect(src).toEqual([1, 2, 3])
  })
})
