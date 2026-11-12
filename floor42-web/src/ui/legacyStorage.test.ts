import { describe, expect, it } from 'vitest'
import { migrateLegacyKeys } from './legacyStorage'

function memory(entries: Record<string, string>) {
  const m = new Map(Object.entries(entries))
  return {
    get length() {
      return m.size
    },
    key: (i: number) => [...m.keys()][i] ?? null,
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
    removeItem: (k: string) => void m.delete(k),
    all: () => Object.fromEntries(m),
  }
}

describe('keys kept by this browser before Floor42', () => {
  it('move once to floor42.*, the new value winning', () => {
    const s = memory({
      'office.lang': 'fr',
      'office.away.acknowledged': '412',
      'office.meeting.topicOpen.M-3': '1',
      'floor42.away.acknowledged': '500',
      'other.key': 'x',
    })

    expect(migrateLegacyKeys(s)).toBe(2)
    expect(s.all()).toEqual({
      'floor42.lang': 'fr',
      'floor42.away.acknowledged': '500',
      'floor42.meeting.topicOpen.M-3': '1',
      'other.key': 'x',
    })
    expect(migrateLegacyKeys(s)).toBe(0)
  })

  it('do nothing without storage', () => {
    expect(migrateLegacyKeys(null)).toBe(0)
  })
})
