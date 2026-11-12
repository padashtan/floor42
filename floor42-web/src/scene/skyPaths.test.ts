import { describe, expect, it } from 'vitest'
import {
  BEHIND_MIN,
  behindOf,
  budgetFor,
  clearOfCity,
  CRAFTS,
  craftsOf,
  DEFAULT_GAP,
  HALF_SPAN,
  isDark,
  lightOn,
  MAX_CRAFT,
  pickCraft,
  planFlight,
  positionAt,
  seeded,
  SKY_FLOOR,
  SPECS,
  startTraffic,
  stepTraffic,
  type TrafficRules,
  wayOf,
} from './skyPaths'

const CENTER = { x: 6.5, z: 12 }
/** The office floor's footprint (layout.ts FLOOR), walls 2.8 high. */
const OFFICE = { x0: 0, x1: 13, z0: 0, z1: 24, top: 2.8 }

describe('planFlight', () => {
  it('keeps every crossing clear of the city and of the office, whatever the dice', () => {
    for (const craft of CRAFTS) {
      const r = seeded(7 + craft.length)
      for (let i = 0; i < 500; i++) {
        const f = planFlight(craft, CENTER, r, 0, i)
        expect(clearOfCity(f, CENTER)).toBe(true)
        // Sampled along the line too, and against the office's box itself.
        for (let k = 0; k <= 20; k++) {
          const { p } = positionAt(f, (f.duration * k) / 20)
          expect(p.y).toBeGreaterThanOrEqual(SKY_FLOOR - 1e-9)
          expect(behindOf(p, CENTER)).toBeGreaterThanOrEqual(BEHIND_MIN - 1e-9)
          const inside = p.x >= OFFICE.x0 && p.x <= OFFICE.x1 && p.z >= OFFICE.z0 && p.z <= OFFICE.z1 && p.y <= OFFICE.top
          expect(inside).toBe(false)
        }
      }
    }
  })

  it('flies within its kind\'s speed and height, and crosses the whole screen', () => {
    const r = seeded(3)
    for (const craft of CRAFTS) {
      for (let i = 0; i < 100; i++) {
        const f = planFlight(craft, CENTER, r, 10, i)
        const s = SPECS[craft]
        expect(f.speed).toBeGreaterThanOrEqual(s.speed[0])
        expect(f.speed).toBeLessThanOrEqual(s.speed[1])
        const mid = positionAt(f, f.start + f.duration / 2).p
        expect(mid.y).toBeGreaterThanOrEqual(s.altitude[0])
        expect(mid.y).toBeLessThanOrEqual(s.altitude[1])
        expect(behindOf(mid, CENTER)).toBeGreaterThanOrEqual(s.behind[0] - 1e-9)
        expect(behindOf(mid, CENTER)).toBeLessThanOrEqual(s.behind[1] + 1e-9)
        expect(Math.hypot(f.to.x - f.from.x, f.to.z - f.from.z)).toBeGreaterThanOrEqual(HALF_SPAN * 2 - 1e-6)
      }
    }
  })

  it('enters from either side', () => {
    const r = seeded(5)
    const sides = new Set<number>()
    for (let i = 0; i < 40; i++) sides.add(wayOf(planFlight('plane', CENTER, r, 0, i), CENTER))
    expect(sides).toEqual(new Set([1, -1]))
    expect(wayOf(planFlight('plane', CENTER, r, 0, 0, -1), CENTER)).toBe(-1)
  })

  it('puts an airliner higher and faster than a drone, and the airship slowest', () => {
    expect(SPECS.plane.speed[0]).toBeGreaterThan(SPECS.helicopter.speed[1])
    expect(SPECS.helicopter.speed[0]).toBeGreaterThan(SPECS.drone.speed[1])
    expect(SPECS.drone.speed[0]).toBeGreaterThan(SPECS.blimp.speed[1])
    expect(SPECS.plane.altitude[0]).toBeGreaterThan(SPECS.drone.altitude[1])
  })
})

describe('positionAt', () => {
  it('runs from start to end at the flight speed, then stays at the end', () => {
    const f = planFlight('helicopter', CENTER, seeded(9), 4, 1)
    expect(positionAt(f, 0).p).toEqual(f.from)
    expect(positionAt(f, 4).u).toBe(0)
    const later = positionAt(f, 5).p
    expect(Math.hypot(later.x - f.from.x, later.y - f.from.y, later.z - f.from.z)).toBeCloseTo(f.speed, 6)
    const end = positionAt(f, 4 + f.duration + 30).p
    for (const k of ['x', 'y', 'z'] as const) expect(end[k]).toBeCloseTo(f.to[k], 9)
    const h = positionAt(f, 5).heading
    expect(Math.hypot(h.x, h.y, h.z)).toBeCloseTo(1, 9)
  })
})

describe('stepTraffic', () => {
  const rules: TrafficRules = { max: MAX_CRAFT, crafts: CRAFTS, gap: DEFAULT_GAP }

  it('starts empty, with the first take-off a few seconds away', () => {
    const t = startTraffic(0, seeded(1))
    expect(t.flights).toEqual([])
    expect(t.next).toBeGreaterThanOrEqual(2)
    expect(t.next).toBeLessThanOrEqual(6)
    expect(stepTraffic(t, 1, rules, CENTER, seeded(2))).toBe(t)
  })

  it('never exceeds the caps, per kind or in all, over an hour of sky', () => {
    const r = seeded(42)
    let t = startTraffic(0, r)
    let most = 0
    const seen = new Set<string>()
    for (let now = 0; now < 3600; now += 0.5) {
      t = stepTraffic(t, now, rules, CENTER, r)
      most = Math.max(most, t.flights.length)
      expect(t.flights.length).toBeLessThanOrEqual(MAX_CRAFT)
      for (const c of CRAFTS) expect(t.flights.filter((f) => f.craft === c).length).toBeLessThanOrEqual(SPECS[c].max)
      for (const f of t.flights) {
        expect(now).toBeLessThan(f.start + f.duration)
        seen.add(f.craft)
      }
    }
    expect(most).toBeGreaterThan(1)
    expect(seen).toEqual(new Set(CRAFTS))
  })

  it('lets one craft take off per step, so a long pause does not release a swarm', () => {
    const r = seeded(8)
    const t = stepTraffic(startTraffic(0, r), 10_000, rules, CENTER, r)
    expect(t.flights).toHaveLength(1)
  })

  it('drops flights that have crossed', () => {
    const r = seeded(4)
    let t = stepTraffic(startTraffic(0, r), 6, rules, CENTER, r)
    const f = t.flights[0]
    t = stepTraffic({ ...t, next: Infinity }, f.start + f.duration + 0.01, rules, CENTER, r)
    expect(t.flights).toEqual([])
  })

  it('sends a second craft of the same kind the other way', () => {
    const r = seeded(21)
    let t = startTraffic(0, r)
    let pairs = 0
    for (let now = 0; now < 3600; now += 0.5) {
      t = stepTraffic(t, now, rules, CENTER, r)
      for (const c of CRAFTS) {
        const same = t.flights.filter((f) => f.craft === c)
        if (same.length === 2) {
          pairs++
          expect(wayOf(same[0], CENTER)).toBe(-wayOf(same[1], CENTER))
        }
      }
    }
    expect(pairs).toBeGreaterThan(0)
  })

  it('holds the sky to the budget and the era', () => {
    const r = seeded(11)
    let t = startTraffic(0, r)
    for (let now = 0; now < 1800; now += 1) {
      t = stepTraffic(t, now, { max: 1, crafts: craftsOf('1980s'), gap: [1, 2] }, CENTER, r)
      expect(t.flights.length).toBeLessThanOrEqual(1)
      expect(t.flights.some((f) => f.craft === 'drone')).toBe(false)
    }
  })
})

describe('pickCraft', () => {
  it('picks only among the allowed kinds, none when none is', () => {
    const r = seeded(6)
    for (let i = 0; i < 50; i++) expect(['blimp', 'drone']).toContain(pickCraft(['blimp', 'drone'], r))
    expect(pickCraft([], r)).toBeNull()
  })
})

describe('budget, era and night', () => {
  it('cuts the sky to one craft when the frame rate is low', () => {
    expect(budgetFor(0)).toBe(MAX_CRAFT)
    expect(budgetFor(60)).toBe(MAX_CRAFT)
    expect(budgetFor(20)).toBe(1)
  })

  it('shows drones in the 2020s only', () => {
    expect(craftsOf('2020s')).toContain('drone')
    for (const era of ['1980s', '1990s', '2000s']) expect(craftsOf(era)).not.toContain('drone')
  })

  it('is dark on the same hours as the city windows', () => {
    expect(isDark(6.9)).toBe(true)
    expect(isDark(12)).toBe(false)
    expect(isDark(19.4)).toBe(false)
    expect(isDark(19.5)).toBe(true)
  })

  it('keeps position lights steady, flashes the beacon briefly, double-flashes the strobes', () => {
    const on = (kind: Parameters<typeof lightOn>[0]) => {
      let n = 0
      for (let i = 0; i < 1200; i++) if (lightOn(kind, i / 100, 0)) n++
      return n / 1200
    }
    expect(on('port')).toBe(1)
    expect(on('beacon')).toBeGreaterThan(0.05)
    expect(on('beacon')).toBeLessThan(0.2)
    expect(on('strobe')).toBeGreaterThan(0.05)
    expect(on('strobe')).toBeLessThan(0.15)
  })
})
