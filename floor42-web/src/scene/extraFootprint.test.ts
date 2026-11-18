import * as THREE from 'three'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentStatus } from '../api/types'
import { around, OBSTACLES } from './floorPlan'
import type { Rect } from './nav'
import { OfficeScene } from './OfficeScene'
import { applyTheme } from './palette'
import { fakeBrowser, QUIET } from './testing/fakeBrowser'
import { seeded } from './wander'

/**
 * D28: one more footprint in the compute room (the identity server moved to one of these spots) must not let the robot
 * rest inside furniture or a pet come within an agent's body, however far from that footprint they walk.
 */
interface Inside {
  mascots: { robot: THREE.Group; cat: THREE.Group; dog: THREE.Group; update(t: number, dt: number): void; radiusOf(o: THREE.Object3D): number; basketRects: Rect[] }
  crew: {
    nav: { footprints: readonly Rect[] }
    figures: Map<string, { root: THREE.Object3D; visible?: boolean }>
    update(t: number, dt: number, ceremony: (t: number) => void): void
  }
}

const WORK: AgentStatus[] = ['WORKING', 'EXECUTING', 'ASSIGNED', 'REVIEWING', 'WAITING_FOR_DIRECTOR', 'WAITING_FOR_AUTHORIZATION']
const NAMES = ['Ada', 'Bo', 'Cy', 'Di', 'Ed', 'Flo']
const SPOTS = [
  { x: 12.5, z: 5.0 },
  { x: 12.5, z: 6.6 },
  { x: 11.6, z: 7.4 },
]
const inside = (r: Rect, p: { x: number; z: number }): boolean => p.x > r.x0 && p.x < r.x1 && p.z > r.z0 && p.z < r.z1

describe.each(SPOTS)('with a footprint at $x, $z in the compute room', (spot) => {
  let added: Rect
  beforeEach(() => {
    added = around(spot.x, spot.z, 0.6, 0.7)
    OBSTACLES.push(added)
  })
  afterEach(() => {
    OBSTACLES.splice(OBSTACLES.indexOf(added), 1)
    vi.restoreAllMocks()
    applyTheme('1990s')
  })

  it('the robot never rests inside furniture', { timeout: 60_000 }, () => {
    const scene = new OfficeScene(fakeBrowser().canvas(), undefined, QUIET, '1990s')
    const it = scene as unknown as Inside
    scene.setAgents([{ id: 'dev-1', name: 'Ada', status: 'WORKING' }])
    const { robot } = it.mascots
    let still = ''
    for (let i = 0; i < 24000; i++) {
      it.mascots.update(i / 20, 0.05)
      if (i % 10) continue
      const now = `${robot.position.x.toFixed(3)},${robot.position.z.toFixed(3)}`
      if (still === now && robot.position.y < 0.01) expect(it.crew.nav.footprints.some((r) => inside(r, robot.position)), `robot at rest inside furniture on ${now}`).toBe(false)
      still = now
    }
    scene.dispose()
  })

  it('the pets never come within an agent or the robot', { timeout: 120_000 }, () => {
    vi.spyOn(Math, 'random').mockImplementation(seeded(901))
    const scene = new OfficeScene(fakeBrowser().canvas(), undefined, QUIET, '1990s', 1)
    const it = scene as unknown as Inside
    const crowd = (round: number) => NAMES.map((name, i) => ({ id: `dev-${i}`, name, status: WORK[(i + round) % WORK.length] }))
    scene.setAgents(crowd(0))
    for (let i = 0; i < 12000; i++) {
      if (i % 600 === 0) scene.setAgents(crowd(i / 600))
      it.crew.update(i / 20, 0.05, () => {})
      it.mascots.update(i / 20, 0.05)
      for (const p of [it.mascots.cat, it.mascots.dog]) {
        if (p.position.y > 0.02) continue
        for (const r of it.crew.nav.footprints.filter((f) => !it.mascots.basketRects.some((b) => Math.abs(b.x0 - f.x0) + Math.abs(b.z0 - f.z0) + Math.abs(b.x1 - f.x1) + Math.abs(b.z1 - f.z1) < 1e-6))) {
          if (Math.hypot(Math.max(r.x0 - p.position.x, 0, p.position.x - r.x1), Math.max(r.z0 - p.position.z, 0, p.position.z - r.z1)) < it.mascots.radiusOf(p) - 0.02) throw new Error(`tick ${i}: ${p.userData.target.id} inside furniture`)
        }
        const others = [it.mascots.robot, ...[...it.crew.figures.values()].filter((f) => f.root.visible).map((f) => f.root)] as THREE.Object3D[]
        for (const f of others) {
          if (f === p || f.position.y > 0.02) continue
          const d = Math.hypot(f.position.x - p.position.x, f.position.z - p.position.z) - it.mascots.radiusOf(p) - (f === it.mascots.robot ? it.mascots.radiusOf(f) : 0.3)
          if (d <= -0.1) throw new Error(`tick ${i}: ${p.userData.target.id} within a body (${d.toFixed(2)}) at ${p.position.x.toFixed(2)},${p.position.z.toFixed(2)}`)
        }
      }
    }
    scene.dispose()
  })
})
