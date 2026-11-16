import { afterEach, describe, expect, it, vi } from 'vitest'

async function fresh() {
  vi.resetModules()
  return import('./dayNight')
}

afterEach(() => vi.unstubAllGlobals())

describe('day and night state', () => {
  it('reads auto when nothing or an unknown value is stored', async () => {
    vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {} })
    expect((await fresh()).dayNight.mode).toBe('auto')
    vi.stubGlobal('localStorage', { getItem: () => 'dusk', setItem: () => {} })
    expect((await fresh()).dayNight.mode).toBe('auto')
  })

  it('a toggle stores the next choice', async () => {
    const store = new Map<string, string>()
    vi.stubGlobal('localStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) })
    const m = await fresh()
    m.toggleDayNight()
    expect(store.get(m.DAY_NIGHT_KEY)).toBe('day')
    expect((await fresh()).dayNight.mode).toBe('day')
  })

  it('a storage that throws keeps the choice in memory', async () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
    })
    const m = await fresh()
    expect(m.dayNight.mode).toBe('auto')
    m.toggleDayNight()
    m.toggleDayNight()
    expect(m.dayNight.mode).toBe('night')
  })
})
