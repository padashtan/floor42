import * as THREE from 'three'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OfficeScene } from './OfficeScene'
import { applyTheme, P } from './palette'
import { fakeBrowser, graphOf, QUIET } from './testing/fakeBrowser'

/** The robot, the cat and the dog: built with the scene after its theme, pickable, wandering, still when calm. */
interface Inside {
  mascots: { robot: THREE.Group; cat: THREE.Group; dog: THREE.Group; update(t: number, dt: number): void }
  crew: { nav: { isBlocked(p: { x: number; z: number }): boolean; footprints: readonly { x0: number; z0: number; x1: number; z1: number }[] } }
  calm: boolean
  rig: { camera: THREE.Camera }
  picker: { pick(x: number, y: number): { target: { kind: string }; root: THREE.Object3D } | null }
}

const targetOf = (o: THREE.Object3D) => o.userData.target as { kind: string; id: string }
/** Drawn: itself and every parent visible (the raycaster ignores visibility). */
const shown = (o: THREE.Object3D | null): boolean => (o ? o.visible && shown(o.parent) : true)
const spot = (o: THREE.Object3D) => ({ x: o.position.x, z: o.position.z })

// Each test builds a whole scene synchronously (CPU only): the slowest takes 2.5 s alone and 4.7 s in the full suite,
// next to Vitest's 5 s default (2026-11-03).
describe('the robot and the pets', { timeout: 30_000 }, () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    applyTheme('1990s')
  })

  it('are built after the theme, wear its colours and carry their targets', () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1980s')
    const { robot, cat, dog } = (scene as unknown as Inside).mascots
    expect([robot, cat, dog].map((o) => targetOf(o))).toEqual([
      { kind: 'robot', id: 'terminal' },
      { kind: 'pet', id: 'cat' },
      { kind: 'pet', id: 'dog' },
    ])
    const colours = new Set<number>()
    robot.traverse((o) => o instanceof THREE.Mesh && colours.add((o.material as THREE.MeshStandardMaterial).color.getHex()))
    expect(colours.has(P.orange), 'the robot wears the 1980s palette').toBe(true)
    expect(graphOf(scene).children).toContain(robot)
    scene.dispose()
  })

  it('are pointed at through their hit box alone, whatever their size', () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1990s')
    const inside = scene as unknown as Inside
    browser.frames(2)
    const camera = inside.rig.camera
    camera.updateMatrixWorld(true)
    // Where nothing stands between them and the camera (they wander off the first frame on).
    inside.mascots.cat.position.set(6, 0, 12.25)
    inside.mascots.dog.position.set(8, 0, 12.25)
    for (const [o, kind] of [[inside.mascots.robot, 'robot'], [inside.mascots.cat, 'pet'], [inside.mascots.dog, 'pet']] as const) {
      // Only the invisible box may take the pointer: what is drawn is made unpickable, so without a hit box nothing is hit.
      o.traverse((m) => {
        if (m instanceof THREE.Mesh && (m.material as THREE.Material).visible) m.raycast = () => {}
      })
      o.updateMatrixWorld(true)
      const ndc = new THREE.Vector3(0, 0.25, 0).applyMatrix4(o.matrixWorld).project(camera)
      const hit = inside.picker.pick(((ndc.x + 1) / 2) * 1280, ((1 - ndc.y) / 2) * 720)
      expect(hit?.target.kind, `${kind} is picked under the pointer`).toBe(kind)
      expect(hit?.root).toBe(o)
    }
    scene.dispose()
  })

  it('have hit boxes that hug their bodies', () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1990s')
    const { robot, cat, dog } = (scene as unknown as Inside).mascots
    for (const o of [robot, cat, dog]) {
      o.updateMatrixWorld(true)
      const body = new THREE.Box3()
      const hit = new THREE.Box3()
      o.traverse((m) => {
        if (!(m instanceof THREE.Mesh) || m.userData.shadow) return
        const box = new THREE.Box3().setFromObject(m)
        if ((m.material as THREE.Material).visible) body.union(box)
        else hit.union(box)
      })
      expect(hit.isEmpty(), `${targetOf(o).id} has a hit box`).toBe(false)
      const grown = body.clone().expandByScalar(0.15)
      expect(grown.containsBox(hit), `${targetOf(o).id}: the hit box stays within its body, near enough`).toBe(true)
      const size = hit.getSize(new THREE.Vector3())
      expect(Math.max(size.x, size.y, size.z), `${targetOf(o).id}: under two and a half metres in every direction`).toBeLessThan(2.5)
    }
    scene.dispose()
  })

  it('leave a seated agent its clicks when the cat sits on its desk', () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1990s')
    const inside = scene as unknown as Inside
    scene.setAgents([{ id: 'dev-1', name: 'Ada', status: 'WORKING' }])
    browser.frames(2)
    const figure = (scene as unknown as { crew: { figures: Map<string, { root: THREE.Object3D }> } }).crew.figures.get('dev-1')!
    figure.root.updateMatrixWorld(true)
    const agent = new THREE.Box3().setFromObject(figure.root)
    const centre = agent.getCenter(new THREE.Vector3())
    const camera = inside.rig.camera
    camera.updateMatrixWorld(true)
    const { cat } = inside.mascots
    // The pointer over the agent's head and shoulders: every screen point where the agent's own body is under it.
    const ray = new THREE.Raycaster()
    const head = new THREE.Vector3(centre.x, agent.max.y - 0.45, centre.z).project(camera)
    const aimed: [number, number][] = []
    for (let dx = -0.08; dx <= 0.08; dx += 0.01) {
      for (let dy = -0.1; dy <= 0.1; dy += 0.01) {
        ray.setFromCamera(new THREE.Vector2(head.x + dx, head.y + dy), camera)
        if (ray.intersectObject(figure.root, true).some((h) => shown(h.object))) aimed.push([head.x + dx, head.y + dy])
      }
    }
    expect(aimed.length, 'there are points of the agent to click').toBeGreaterThan(20)
    // The cat on the desk, right behind the agent and to either side of it: it must take none of those clicks.
    for (const [dx, dz] of [[0, 0], [0.5, 0], [-0.5, 0], [0, 0.3]]) {
      cat.position.set(centre.x + dx, 0.79, centre.z - 0.75 + dz)
      cat.updateMatrixWorld(true)
      const kinds = new Set(aimed.map(([x, y]) => inside.picker.pick(((x + 1) / 2) * 1280, ((1 - y) / 2) * 720)?.target.kind))
      expect(kinds, `the cat at (${dx}, ${dz}): every click on the agent picks the agent`).toEqual(new Set(['agent']))
    }
    scene.dispose()
  })

  it('wander on free floor, around furniture, and are still under reduced motion', () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1990s')
    const inside = scene as unknown as Inside
    const { robot, cat, dog } = inside.mascots
    const start = [robot, cat, dog].map(spot)
    expect(inside.crew.nav.isBlocked(start[0]), 'the robot starts on a free tile').toBe(false)
    inside.calm = true
    browser.frames(20)
    expect([robot, cat, dog].map(spot), 'calm: stands still').toEqual(start)
    inside.calm = false
    let moved = false
    // The frames of the scene would only repaint: drive the little ones' own clock, 20 minutes at 20 frames a second.
    for (let i = 0; i < 24000 && !moved; i++) {
      inside.mascots.update(i / 20, 0.05)
      moved = [robot, cat, dog].some((o, k) => Math.hypot(o.position.x - start[k].x, o.position.z - start[k].z) > 0.5)
      if (i % 20 === 0) expect(inside.crew.nav.isBlocked(spot(robot)), 'never inside furniture').toBe(false)
    }
    expect(moved, 'at least one walked away').toBe(true)
    scene.dispose()
  })

  it('keep to free floor for twenty minutes; the cat naps on a desk, the dog lies down', () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1990s')
    const inside = scene as unknown as Inside
    scene.setAgents([{ id: 'dev-1', name: 'Ada', status: 'WORKING' }])
    const { robot, cat, dog } = inside.mascots
    const stood = new Map<THREE.Object3D, string>()
    let onDesk = 0
    let dogLying = 0
    let travelled = 0
    let last = spot(robot)
    for (let i = 0; i < 24000; i++) {
      inside.mascots.update(i / 20, 0.05)
      if (cat.position.y > 0.5) onDesk++
      if (dog.children.some((c) => c.visible === false)) dogLying++
      travelled += Math.hypot(robot.position.x - last.x, robot.position.z - last.z)
      last = spot(robot)
      if (i % 10) continue
      // Where they stand still, they stand on free floor (a walk's smoothed path may graze a clearance).
      for (const o of [robot, cat, dog]) {
        const now = `${o.position.x.toFixed(3)},${o.position.y.toFixed(3)},${o.position.z.toFixed(3)}`
        if (stood.get(o) === now && o.position.y < 0.01 && o === robot) expect(inside.crew.nav.footprints.some((r) => o.position.x > r.x0 && o.position.x < r.x1 && o.position.z > r.z0 && o.position.z < r.z1), `${targetOf(o).id} at rest inside furniture on ${now}`).toBe(false)
        stood.set(o, now)
      }
    }
    expect(travelled, 'the robot walked').toBeGreaterThan(20)
    expect(onDesk, 'the cat sat on a desk').toBeGreaterThan(0)
    expect(dogLying, 'the dog lay down').toBeGreaterThan(0)
    scene.dispose()
  })

  it('give back their geometry and materials with the scene', () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '2000s')
    const { robot, cat, dog } = (scene as unknown as Inside).mascots
    browser.frames(3)
    const geometries = new Set<THREE.BufferGeometry>()
    const materials = new Set<THREE.Material>()
    for (const r of [robot, cat, dog]) {
      r.traverse((o) => {
        if (!(o instanceof THREE.Mesh)) return
        geometries.add(o.geometry)
        materials.add(o.material as THREE.Material)
      })
    }
    expect(geometries.size, 'they are made of many parts').toBeGreaterThan(30)
    expect(materials.size, 'and of several materials').toBeGreaterThan(8)
    const spies = [...geometries, ...materials].map((r) => vi.spyOn(r, 'dispose'))
    scene.dispose()
    expect(spies.filter((s) => s.mock.calls.length === 0).length, 'every geometry and material is disposed').toBe(0)
  })
})
