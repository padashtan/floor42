import * as THREE from 'three'
import { afterEach, describe, expect, it } from 'vitest'
import { around, OBSTACLES } from './floorPlan'
import { OfficeScene } from './OfficeScene'
import { applyTheme } from './palette'
import { fakeBrowser, QUIET } from './testing/fakeBrowser'

/** A pet or the robot at rest is where the pointer can reach it: no machine or table between it and the camera. */
interface Inside {
  mascots: { robot: THREE.Group; cat: THREE.Group; dog: THREE.Group; update(t: number, dt: number): void; pickable(p: { x: number; z: number }): boolean }
  rig: { camera: THREE.Camera }
  picker: { pick(x: number, y: number): { root: THREE.Object3D } | null }
}

const pickedAt = (inside: Inside, o: THREE.Object3D): THREE.Object3D | undefined => {
  o.updateMatrixWorld(true)
  const n = new THREE.Vector3(0, 0.25, 0).applyMatrix4(o.matrixWorld).project(inside.rig.camera)
  return inside.picker.pick(((n.x + 1) / 2) * 1280, ((1 - n.y) / 2) * 720)?.root
}

describe('rest spots', { timeout: 180_000 }, () => {
  afterEach(() => applyTheme('1990s'))

  it('refuse a spot behind a machine or a table, and accept exactly the spots the pointer reaches', () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1990s')
    const inside = scene as unknown as Inside
    browser.frames(2)
    inside.rig.camera.updateMatrixWorld(true)
    const { robot, cat, dog } = inside.mascots
    robot.position.set(-50, 0, -50)
    cat.position.set(-50, 0, -60)
    robot.updateMatrixWorld(true)
    cat.updateMatrixWorld(true)
    const grid = (scene as unknown as { crew: { nav: { isBlocked(p: { x: number; z: number }): boolean } } }).crew.nav
    let refused = 0
    for (let x = 0.5; x < 13; x += 0.5) {
      for (let z = 0.5; z < 24; z += 0.5) {
        if (grid.isBlocked({ x, z })) continue
        dog.position.set(x, 0, z)
        const reached = pickedAt(inside, dog) === dog
        if (!reached) refused++
        expect(inside.mascots.pickable({ x, z }), `(${x}, ${z}) ${reached ? 'reachable' : 'behind ' + JSON.stringify((inside.picker.pick(0,0), pickedAt(inside, dog)?.userData.target))}`).toBe(reached)
      }
    }
    expect(refused, 'some spots are hidden behind targets').toBeGreaterThan(20)
    scene.dispose()
  })

  it('start the robot and the pets where the pointer reaches them, under any floor plan and seed (reduced motion keeps them there)', () => {
    const extra = [null, around(12.5, 5.0, 0.6, 0.7), around(12.5, 6.6, 0.6, 0.7), around(11.6, 7.4, 0.6, 0.7), around(7, 9, 1.5, 1.5)]
    for (const rect of extra) {
      if (rect) OBSTACLES.push(rect)
      try {
        for (const seed of [0, 2, 4]) {
          const browser = fakeBrowser()
          const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1990s', seed)
          const inside = scene as unknown as Inside
          browser.frames(2)
          inside.rig.camera.updateMatrixWorld(true)
          const { robot, cat, dog } = inside.mascots
          for (const o of [robot, cat, dog]) {
            const hit = pickedAt(inside, o)
            expect([robot, cat, dog], `seed ${seed}${rect ? ` with a footprint at ${rect.x0}, ${rect.z0}` : ''}: ${o.userData.target.id} is hidden behind ${JSON.stringify(hit?.userData.target)}`).toContain(hit)
          }
          scene.dispose()
        }
      } finally {
        if (rect) OBSTACLES.splice(OBSTACLES.indexOf(rect), 1)
      }
    }
  })
})
