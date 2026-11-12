import { describe, expect, it } from 'vitest'

/**
 * D22 (docs/DEBT.md): a source file targets 300 lines; a panel that grows past it is split by responsibility.
 * A tolerance of 10 percent applies: up to 330 lines the test passes and warns, above 330 it fails.
 * The files below were already larger when the limit was enforced: each keeps the ceiling it had (same tolerance:
 * above it warns, above it plus 10 percent fails), and an entry goes as soon as its file is back at the limit. The language catalogues (i18n/*.en.ts, *.fr.ts) and the
 * API types (api/types.*.ts) are split by area, so they are under the limit like any other file (D27).
 */
const LIMIT = 300
const TOLERANCE = 1.1
const CEILINGS: Record<string, number> = {
  'src/api/client.ts': 496,
  'src/scene/mascots.ts': 613,
  'src/scene/OfficeScene.ts': 467,
  'src/scene/wander.ts': 433,
  'src/ui/prefill.test.ts': 396,
  'src/state/office.ts': 392,
  'src/components/DirectorTerminal.vue': 370,
  'src/scene/sky.ts': 367,
  'src/scene/figures.ts': 342,
  'src/scene/nav.ts': 324,
  'src/scene/crew.ts': 319,
}

const sources = import.meta.glob<string>('/src/**/*.{vue,ts}', { query: '?raw', import: 'default', eager: true })
const lines = (text: string) => text.split('\n').length - (text.endsWith('\n') ? 1 : 0)
const sized = Object.entries(sources).map(([path, text]) => [path.slice(1), lines(text)] as const)

describe('source file sizes (D22)', () => {
  it('sees the sources', () => {
    expect(sized.length).toBeGreaterThan(100)
  })

  it('keeps every file within its limit plus the tolerance, and warns above the limit', () => {
    const limitOf = (path: string) => CEILINGS[path] ?? LIMIT
    const warned = sized.filter(([path, n]) => n > limitOf(path)).map(([path, n]) => `${path}: ${n} lines, limit ${limitOf(path)}`)
    if (warned.length) console.warn(['Files above their line limit (tolerated up to 10 percent):', ...warned].join('\n'))
    const over = sized
      .filter(([path, n]) => n > Math.floor(limitOf(path) * TOLERANCE))
      .map(([path, n]) => `${path}: ${n} lines, limit ${limitOf(path)} + 10 percent`)
    expect(over).toEqual([])
  })

  it('drops a ceiling once its file is back within the limit', () => {
    const sizes = new Map(sized)
    const stale = Object.keys(CEILINGS).filter((path) => (sizes.get(path) ?? 0) <= LIMIT)
    expect(stale).toEqual([])
  })
})
