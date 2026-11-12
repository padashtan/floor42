import { describe, expect, it } from 'vitest'
import type { AwayView } from '../api/types'
import { ACK_KEY, acknowledge, acknowledged, ago, costLine, hasNews } from './away'

function memory(): Pick<Storage, 'getItem' | 'setItem'> & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return { data, getItem: (k) => data.get(k) ?? null, setItem: (k, v) => void data.set(k, v) }
}

const blocked: Pick<Storage, 'getItem' | 'setItem'> = {
  getItem: () => {
    throw new Error('SecurityError')
  },
  setItem: () => {
    throw new Error('QuotaExceededError')
  },
}

function summary(extra: Partial<AwayView> = {}): AwayView {
  return {
    since: 10,
    until: 10,
    more: false,
    delivered: [],
    merged: [],
    waiting: [],
    blocked: [],
    sessions: 0,
    failedSessions: 0,
    costUsdEstimated: null,
    ...extra,
  }
}

describe('the acknowledged sequence, kept in this browser only', () => {
  it('is kept and read back', () => {
    const store = memory()
    expect(acknowledged(store)).toBeNull()
    expect(acknowledge(42, store)).toBe(true)
    expect(store.data.get(ACK_KEY)).toBe('42')
    expect(acknowledged(store)).toBe(42)
  })

  it('ignores a value that is not a sequence', () => {
    const store = memory()
    store.setItem(ACK_KEY, 'yesterday')
    expect(acknowledged(store)).toBeNull()
  })

  it('survives a browser that refuses storage', () => {
    expect(acknowledged(blocked)).toBeNull()
    expect(acknowledge(7, blocked)).toBe(false)
    expect(acknowledged(null)).toBeNull()
    expect(acknowledge(7, null)).toBe(false)
  })
})

describe('the summary', () => {
  it('has nothing to tell after a quiet absence', () => {
    expect(hasNews(summary())).toBe(false)
    expect(hasNews(summary({ sessions: 1 }))).toBe(true)
    const waiting = [{ kind: 'OPEN', mission: 'M-1', ticket: null, agent: null, text: 'Why?', at: null }]
    expect(hasNews(summary({ until: 12, waiting }))).toBe(true)
  })

  it('does not reopen for what already waited when the director last looked', () => {
    const waiting = [{ kind: 'OPEN', mission: 'M-1', ticket: null, agent: null, text: 'Why?', at: null }]
    expect(hasNews(summary({ waiting }))).toBe(false)
    expect(hasNews(summary({ blocked: waiting }))).toBe(false)
  })

  it('shows a cost only when Claude Code reported one, never a made-up zero', () => {
    const label = (amount: string) => `${amount} USD, estimated`
    expect(costLine(summary({ sessions: 2 }), label)).toBeNull()
    expect(costLine(summary({ sessions: 2, costUsdEstimated: 0.1234 }), label)).toBe('0.12 USD, estimated')
    expect(costLine(summary({ sessions: 1, costUsdEstimated: 0.004 }), label)).toBe('< 0.01 USD, estimated')
  })

  it('says briefly how long ago', () => {
    const now = Date.parse('2026-10-31T10:00:00Z')
    expect(ago('2026-10-31T09:59:50Z', now)).toBe('1 min')
    expect(ago('2026-10-31T09:15:00Z', now)).toBe('45 min')
    expect(ago('2026-10-31T07:00:00Z', now)).toBe('3 h')
    expect(ago('2026-10-28T10:00:00Z', now)).toBe('3 d')
    expect(ago(null, now)).toBe('')
  })
})
