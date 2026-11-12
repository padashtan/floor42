import * as THREE from 'three'
import { describe, expect, it, vi } from 'vitest'
import { buildOffice, DESKS, FLOOR, FURNITURE, LANDING, OBSTACLES, STATIONS, WALLS } from './layout'
import { crosses, ESCAPE, NavGrid, type Point, type Rect } from './nav'

describe('office layout', () => {
  const grid = new NavGrid(FLOOR.width, FLOOR.depth, OBSTACLES)
  const start = STATIONS.lounge[0]

  for (const [station, slots] of Object.entries(STATIONS)) {
    it(`every ${station} slot can be reached from the lounge`, () => {
      for (const slot of slots) {
        expect(grid.path(start, slot).length, `${station} @ ${slot.x},${slot.z}`).toBeGreaterThan(0)
      }
    })
  }

  it('crossing to the compute room goes through the doorway', () => {
    // The terminal's operator seat, in the compute room.
    const path = grid.path(start, { x: 10.05, z: 3.4 })
    const crossing = path.find((p, i) => i > 0 && (path[i - 1].x - 8.5) * (p.x - 8.5) <= 0) ?? path[0]
    expect(path.some((p) => p.z > 5.3 && p.z < 7.4 && Math.abs(p.x - 8.5) < 1.5) || crossing.z > 5.3).toBe(true)
  })
})

describe('collisions between figures and furniture', () => {
  const BODY = 0.15
  const grid = new NavGrid(FLOOR.width, FLOOR.depth, OBSTACLES)
  const slots = Object.entries(STATIONS).flatMap(([name, list]) => list.map((s, i) => ({ name: `${name}#${i}`, ...s })))

  function touches(p: { x: number; z: number }): boolean {
    return OBSTACLES.some((r) => p.x > r.x0 - BODY && p.x < r.x1 + BODY && p.z > r.z0 - BODY && p.z < r.z1 + BODY)
  }

  it('no walk between two stations goes through furniture, except to get up and sit down', { timeout: 60_000 }, () => {
    const offenders: string[] = []
    for (const a of slots) {
      for (const b of slots) {
        if (a === b) continue
        const path = grid.path(a, b)
        expect(path.length, `${a.name} -> ${b.name}`).toBeGreaterThan(0)
        let prev = { x: a.x, z: a.z }
        for (const p of path) {
          const steps = Math.ceil(Math.hypot(p.x - prev.x, p.z - prev.z) / 0.05)
          for (let s = 1; s <= steps; s++) {
            const q = { x: prev.x + ((p.x - prev.x) * s) / steps, z: prev.z + ((p.z - prev.z) * s) / steps }
            const nearEnd = Math.hypot(q.x - a.x, q.z - a.z) < 0.8 || Math.hypot(q.x - b.x, q.z - b.z) < 0.8
            if (!nearEnd && touches(q)) offenders.push(`${a.name} -> ${b.name} at ${q.x.toFixed(2)},${q.z.toFixed(2)}`)
          }
          prev = p
        }
      }
    }
    expect(offenders.slice(0, 10)).toEqual([])
  })
})

describe('props are obstacles', () => {
  const BODY = 0.15
  const grid = new NavGrid(FLOOR.width, FLOOR.depth, OBSTACLES)
  const inside = (p: Point, r: Rect) => p.x > r.x0 && p.x < r.x1 && p.z > r.z0 && p.z < r.z1
  /** A desk's own pieces: the desk and the chair its agent sits on. */
  const own = (seat: Point): Rect[] =>
    FURNITURE.filter((r) => {
      const desk = DESKS.find((d) => d.x === seat.x && Math.abs(d.z + 0.75 - seat.z) < 1e-9)
      return desk !== undefined && (inside(seat, r) || inside(desk, r))
    })
  const seats = STATIONS.desk.map((s, i) => ({ name: `desk#${i}`, x: s.x, z: s.z }))
  const landing = { name: 'landing', x: LANDING.x, z: LANDING.z }

  /** Where a walk from `a` to `b` touches a prop, other than the pieces of its own desk right where it sits down. */
  function hits(a: Point & { name: string }, b: Point & { name: string }): string[] {
    const path = grid.path(a, b)
    if (path.length === 0) return [`${a.name} -> ${b.name}: no path`]
    const out: string[] = []
    for (const r of [...WALLS, ...FURNITURE]) {
      if (!crosses(a, path, r, BODY)) continue
      // Getting up and sitting down cross the agent's own chair, nothing else.
      const mine = [...own(a), ...own(b)].includes(r)
      if (mine && !walksAcross(a, b, path, r)) continue
      out.push(`${a.name} -> ${b.name} crosses ${r.x0.toFixed(2)},${r.z0.toFixed(2)}..${r.x1.toFixed(2)},${r.z1.toFixed(2)}`)
    }
    return out
  }

  /** True when the walk touches `r` further than ESCAPE from both of its ends. */
  function walksAcross(a: Point, b: Point, path: Point[], r: Rect): boolean {
    let prev: Point = a
    for (const p of path) {
      const steps = Math.ceil(Math.hypot(p.x - prev.x, p.z - prev.z) / 0.05)
      for (let s = 1; s <= steps; s++) {
        const q = { x: prev.x + ((p.x - prev.x) * s) / steps, z: prev.z + ((p.z - prev.z) * s) / steps }
        const far = Math.hypot(q.x - a.x, q.z - a.z) > ESCAPE && Math.hypot(q.x - b.x, q.z - b.z) > ESCAPE
        if (far && crosses(q, [], r, BODY)) return true
      }
      prev = p
    }
    return false
  }

  it('no walk between two desks crosses a prop, a chair, a plant or a wall', { timeout: 30_000 }, () => {
    const offenders = seats.flatMap((a) => seats.filter((b) => b !== a).flatMap((b) => hits(a, b)))
    expect(offenders.slice(0, 10)).toEqual([])
  })

  it('every desk is reachable from the elevator and back, around every prop', () => {
    const offenders = seats.flatMap((s) => [...hits(landing, s), ...hits(s, landing)])
    expect(offenders.slice(0, 10)).toEqual([])
  })

  it('every mesh buildOffice stands on the floor lies on a declared footprint', () => {
    // The textures paint on a canvas; nothing is drawn here, only the geometry is measured.
    const noop = () => undefined
    const ctx = new Proxy({} as Record<PropertyKey, unknown>, { get: (t, k) => (k in t ? t[k] : noop) })
    vi.stubGlobal('document', { createElement: () => ({ width: 1, height: 1, getContext: () => ctx }) })
    try {
      const { scene } = buildOffice()
      scene.updateMatrixWorld(true)
      // A wall's capping overhangs its footprint by 0.05 a side and 0.1 at its ends: never in a walkway.
      const on = (x: number, z: number, r: Rect, m: number) => x >= r.x0 - m && x <= r.x1 + m && z >= r.z0 - m && z <= r.z1 + m
      const covered = (x: number, z: number) =>
        FURNITURE.some((r) => on(x, z, r, 0.05)) || WALLS.some((r) => on(x, z, r, 0.11))
      const strays: string[] = []
      scene.traverse((o) => {
        if (!(o instanceof THREE.Mesh) || !o.visible) return
        const b = new THREE.Box3().setFromObject(o)
        // Floors, the rug and the cable tray are walked on; boards, lamps and pipes hang above the agents' heads.
        if (b.max.y < 0.1 || b.min.y >= 0.9) return
        for (let i = 0; i <= 4; i++) {
          for (let j = 0; j <= 4; j++) {
            const x = b.min.x + ((b.max.x - b.min.x) * i) / 4
            const z = b.min.z + ((b.max.z - b.min.z) * j) / 4
            // Beyond the floor's edge: the outer walls, which the grid's border already stops.
            if (x <= 0.2 || z <= 0.2 || x >= FLOOR.width - 0.2 || z >= FLOOR.depth - 0.2) continue
            if (!covered(x, z)) strays.push(`${x.toFixed(2)},${z.toFixed(2)}`)
          }
        }
      })
      expect([...new Set(strays)].slice(0, 10)).toEqual([])
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
