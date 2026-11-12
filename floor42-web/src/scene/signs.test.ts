import { describe, expect, it } from 'vitest'
import {
  AWNING_TOP,
  brackets,
  clear,
  columnHeight,
  edgeOf,
  type Face,
  frontOf,
  glowRect,
  GAP,
  inFace,
  legsOf,
  lineWidth,
  overlaps,
  planFace,
  ROOF_INSET,
  type Solid,
  type Words,
} from './signs'

const words: Words = {
  shops: ['PIZZA', 'MOKA CAFÉ', 'PHARMACY', 'JAZZ BAR', 'GYM', 'LAUNDRY', 'SUSHI'],
  companies: ['NOVA BANK', 'ORBIT TELECOM', 'ZENITH', 'BYTE & CO'],
  colors: ['#ff3fd2', '#29f6ff', '#ffd319'],
}

function seeded(seed: number): () => number {
  let s = seed
  return () => {
    s = (s * 16807) % 2147483647
    return (s - 1) / 2147483646
  }
}

/** Faces like the city's: walk-ups, the tiers of a stepped tower, glass towers, on busy and quiet streets. */
function faces(): Face[] {
  const out: Face[] = []
  const rnd = seeded(7)
  for (let i = 0; i < 300; i++) {
    const width = 2.5 + rnd() * 9
    const height = 3 + rnd() * 34
    const bottom = -45.7 + (rnd() < 0.2 ? rnd() * 15 : 0)
    const ground = bottom < -45
    const tall = height > 14
    out.push({
      width,
      bottom,
      top: bottom + height,
      floor: height / 16,
      shops: ground ? Math.max(1, Math.round(width / 3.2)) : 0,
      street: rnd() < 0.4 ? 'dense' : 'sparse',
      company: tall && rnd() < 0.5 ? words.companies[i % 4] : undefined,
      roof: tall && rnd() < 0.5 ? { text: words.companies[(i + 1) % 4] } : undefined,
    })
  }
  return out
}

const plans = faces().map((f, i) => ({ f, signs: planFace(f, seeded(100 + i), words) }))
const all = plans.flatMap((p) => p.signs.map((s) => ({ f: p.f, s })))

describe('neon signs on a facade face', () => {
  it('places signs of every kind across the city', () => {
    const kinds = new Set(all.map((x) => x.s.kind))
    expect([...kinds].sort()).toEqual(['blade', 'fascia', 'flush', 'rooftop'])
  })

  it('keeps every sign inside its face: within its sides, between its foot and its roof line', () => {
    for (const { f, s } of all) {
      expect(inFace(f, s)).toBe(true)
      if (s.kind === 'rooftop') continue
      expect(Math.abs(s.u) + s.w / 2).toBeLessThanOrEqual(f.width / 2 - edgeOf(f) + 1e-9)
      expect(s.y - s.h / 2).toBeGreaterThanOrEqual(f.bottom - 1e-9)
      expect(s.y + s.h / 2).toBeLessThanOrEqual(f.top + 1e-9)
    }
  })

  it('never makes a sign wider than its face', () => {
    for (const { f, s } of all) expect(s.kind === 'blade' ? s.depth : s.w).toBeLessThan(f.width)
  })

  it('lays flat signs on the wall and holds blades on arms that start at the wall', () => {
    for (const { s } of all) {
      if (s.kind === 'fascia' || s.kind === 'flush') expect(s.back).toBe(0)
      if (s.kind === 'blade') {
        expect(s.back).toBe(GAP)
        const arms = brackets(s)
        expect(arms).toHaveLength(2)
        for (const a of arms) {
          expect(a.from).toBe(0)
          expect(a.to).toBeGreaterThan(s.back)
          expect(a.y).toBeGreaterThan(s.y - s.h / 2)
          expect(a.y).toBeLessThan(s.y + s.h / 2)
        }
      } else {
        expect(brackets(s)).toEqual([])
      }
    }
  })

  it('stands only rooftop signs above the roof, on legs that reach the roof, set back from the edge', () => {
    for (const { f, s } of all) {
      if (s.kind !== 'rooftop') {
        expect(s.y + s.h / 2).toBeLessThanOrEqual(f.top + 1e-9)
        continue
      }
      expect(s.y - s.h / 2 - s.legs).toBeCloseTo(f.top, 9)
      expect(s.legs).toBeGreaterThan(0.5)
      expect(s.back).toBe(-ROOF_INSET)
      // Narrow enough that the frames over the south and the east faces of one roof never cross at the corner.
      expect(s.w / 2).toBeLessThan(f.width / 2 - ROOF_INSET)
      for (const leg of legsOf(s)) expect(Math.abs(leg - s.u)).toBeLessThanOrEqual(s.w / 2)
    }
  })

  it('keeps signs on one face apart from each other', () => {
    for (const { signs } of plans) {
      for (let i = 0; i < signs.length; i++) {
        for (let j = i + 1; j < signs.length; j++) expect(overlaps(signs[i], signs[j])).toBe(false)
      }
    }
  })

  it('sizes each plate from its letters, so the texture is never stretched', () => {
    for (const { s } of all) {
      if (s.kind === 'blade') expect(s.h).toBeCloseTo(columnHeight(s.text, s.letter), 9)
      else expect(s.w).toBeCloseTo(lineWidth(s.text, s.letter), 9)
    }
  })

  it('puts a shop name just above its awning, centred on its shop front', () => {
    for (const { f, s } of all) {
      if (s.kind !== 'fascia') continue
      expect(s.y - s.h / 2).toBeGreaterThanOrEqual(f.bottom + AWNING_TOP)
      const slot = f.width / f.shops
      const k = (s.u + f.width / 2) / slot - 0.5
      expect(Math.abs(k - Math.round(k))).toBeLessThan(1e-9)
      expect(s.w).toBeLessThan(slot)
    }
  })

  it('fills a busy street with far more signs than a quiet one', () => {
    const count = (street: string) => plans.filter((p) => p.f.street === street).map((p) => p.signs.length)
    const mean = (l: number[]) => l.reduce((a, b) => a + b, 0) / l.length
    expect(mean(count('dense'))).toBeGreaterThan(2 * mean(count('sparse')))
    const stacked = plans.filter((p) => p.f.street === 'dense').some((p) => {
      const blades = p.signs.filter((s) => s.kind === 'blade')
      return blades.some((a) => blades.some((b) => a !== b && a.u === b.u))
    })
    expect(stacked).toBe(true)
  })

  it('keeps the glow of a sign on its face, around the sign', () => {
    for (const { f, s } of all) {
      const g = glowRect(f, s)
      if (s.kind === 'rooftop') {
        expect(g).toBeNull()
        continue
      }
      expect(g).not.toBeNull()
      expect(g!.u0).toBeGreaterThanOrEqual(-f.width / 2)
      expect(g!.u1).toBeLessThanOrEqual(f.width / 2)
      expect(g!.y0).toBeGreaterThanOrEqual(f.bottom)
      expect(g!.y1).toBeLessThanOrEqual(f.top)
      expect(g!.u0).toBeLessThanOrEqual(s.u)
      expect(g!.u1).toBeGreaterThanOrEqual(s.u)
    }
  })

  it('gives a face too small for any sign none at all', () => {
    expect(planFace({ width: 0.4, bottom: 0, top: 6, floor: 0.4, shops: 1, street: 'dense' }, seeded(3), words)).toEqual([])
  })

  it('shrinks a long name to fit a narrow roof rather than letting it overhang', () => {
    const f: Face = { width: 5.5, bottom: 0, top: 30, floor: 1.9, shops: 0, street: 'sparse', roof: { text: 'ORBIT TELECOM' } }
    const [s] = planFace(f, seeded(5), words).filter((x) => x.kind === 'rooftop')
    expect(s.w).toBeLessThanOrEqual(f.width - 2 * ROOF_INSET)
  })
})

describe('the room in front of a face', () => {
  const house = (x: number): Solid => ({ x, z: 0, w: 3.3, d: 4.6, bottom: 0, top: 6 })

  it('is taken by the next walk-up of a terrace, so the walls between houses carry no sign', () => {
    const row = [0, 3.5, 7, 10.5].map(house)
    const others = (i: number) => row.filter((_, j) => j !== i)
    expect(clear(frontOf(row[0], 'east', 2), others(0))).toBe(false)
    expect(clear(frontOf(row[3], 'east', 2), others(3))).toBe(true)
    for (let i = 0; i < 4; i++) expect(clear(frontOf(row[i], 'south', 2), others(i))).toBe(true)
  })

  it('is free above a lower neighbour', () => {
    const tier: Solid = { x: 0, z: 0, w: 6, d: 6, bottom: 10, top: 18 }
    const below: Solid = { x: 0, z: 0, w: 9, d: 9, bottom: 0, top: 10 }
    expect(clear(frontOf(tier, 'south', 2), [below])).toBe(true)
  })
})
