import { describe, expect, it } from 'vitest'
import * as THREE from 'three'
import { CLEARANCE, PARAPET, type Area, type Roof, waterTowerGeometry, waterTowerPlan } from './waterTower'

function seeded(seed: number): () => number {
  let s = seed
  return () => {
    s = (s * 16807) % 2147483647
    return (s - 1) / 2147483646
  }
}

const walkUp: Roof = { x: 10, z: -4, w: 3.3, d: 4.6, y: 7.2 }
const ac = (roof: Roof): Area => ({ x: roof.x + roof.w * 0.28, z: roof.z - roof.d * 0.32, w: 0.7, d: 0.5 })

describe('water tower plan', () => {
  it('keeps the whole tower inside the parapet, with a clearance, over many seeds', () => {
    let placed = 0
    for (let seed = 1; seed < 400; seed++) {
      const t = waterTowerPlan(walkUp, seeded(seed))
      if (!t) continue
      placed++
      const hx = walkUp.w / 2 - PARAPET - CLEARANCE
      const hz = walkUp.d / 2 - PARAPET - CLEARANCE
      expect(Math.abs(t.x - walkUp.x) + t.reach).toBeLessThanOrEqual(hx + 1e-9)
      expect(Math.abs(t.z - walkUp.z) + t.reach).toBeLessThanOrEqual(hz + 1e-9)
      expect(t.y).toBe(walkUp.y)
      expect(t.top).toBeLessThan(t.radius)
      expect(t.hoops.length).toBeGreaterThanOrEqual(3)
      expect(t.hoops.length).toBeLessThanOrEqual(5)
      // About as tall as it is wide.
      expect(t.tank / (2 * t.radius)).toBeGreaterThan(0.9)
      expect(t.tank / (2 * t.radius)).toBeLessThan(1.25)
    }
    expect(placed).toBeGreaterThan(390)
  })

  it('sets the hoops closer together near the bottom', () => {
    const t = waterTowerPlan(walkUp, seeded(5))!
    const gaps = t.hoops.slice(1).map((h, i) => h - t.hoops[i])
    for (let i = 1; i < gaps.length; i++) expect(gaps[i]).toBeGreaterThan(gaps[i - 1])
    expect(t.hoops[t.hoops.length - 1]).toBeLessThan(t.tank)
  })

  it('refuses a roof too small for a tower', () => {
    expect(waterTowerPlan({ x: 0, z: 0, w: 1.4, d: 3, y: 0 }, seeded(3))).toBeNull()
  })

  it('stays off what is already on the roof', () => {
    for (let seed = 1; seed < 200; seed++) {
      const t = waterTowerPlan(walkUp, seeded(seed), [ac(walkUp)])
      if (!t) continue
      const a = ac(walkUp)
      const dx = Math.max(Math.abs(t.x - a.x) - a.w / 2, 0)
      const dz = Math.max(Math.abs(t.z - a.z) - a.d / 2, 0)
      expect(Math.hypot(dx, dz)).toBeGreaterThanOrEqual(t.reach)
    }
  })

  it('stands on the back half of the roof', () => {
    for (let seed = 1; seed < 100; seed++) {
      const t = waterTowerPlan(walkUp, seeded(seed))
      if (t) expect(t.z).toBeLessThanOrEqual(walkUp.z)
    }
  })
})

describe('water tower geometry', () => {
  it('rests on the roof plane, never floats or sinks, and stays within its reach', () => {
    for (let seed = 1; seed < 40; seed++) {
      const t = waterTowerPlan(walkUp, seeded(seed))
      if (!t) continue
      const g = waterTowerGeometry(t, 0x8a6a4c)
      g.computeBoundingBox()
      const box = g.boundingBox!
      expect(box.min.y).toBeCloseTo(walkUp.y, 6)
      expect(box.max.y).toBeCloseTo(walkUp.y + t.height, 6)
      const p = g.getAttribute('position')
      const v = new THREE.Vector3()
      for (let i = 0; i < p.count; i++) {
        v.fromBufferAttribute(p, i)
        expect(Math.hypot(v.x - t.x, v.z - t.z)).toBeLessThanOrEqual(t.reach + 1e-6)
      }
      for (const name of ['position', 'normal', 'uv', 'color']) expect(g.getAttribute(name)).toBeDefined()
    }
  })
})
