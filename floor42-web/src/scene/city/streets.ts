import * as THREE from 'three'
import { P } from '../palette'
import { BLOCK, type Block, Boxes, CELL, type CityContext, DEPTH, ROAD, SPAN } from './kit'
import { facade, neon } from './textures'

/** A tree to plant: its foot, its size, and whether it is a fir. */
export interface Tree {
  x: number
  y: number
  z: number
  s: number
  fir: boolean
}

/** The street lamps' bulbs (lit in the evening) and the traffic lights (they swap every few seconds). */
export interface StreetLights {
  bulbs: THREE.MeshBasicMaterial
  signals: THREE.InstancedMesh
}

/**
 * The tower under the office: a curtain wall all the way down, and the company's name in neon near the top; then the
 * asphalt of the whole city. Returns the tower's material, which the evening dims, and the two neon signs.
 */
export function ourTower(ctx: CityContext, width: number, depth: number): { wall: THREE.MeshBasicMaterial; signs: THREE.Mesh[] } {
  const { root, cx, cz, y0 } = ctx
  const wall = new THREE.MeshBasicMaterial({ map: facade(0x2b3f4c, 0xf6d98a, 4, 3), color: 0xffffff })
  wall.color.setHex(P.wallBase)
  const tower = new THREE.Mesh(new THREE.BoxGeometry(width + 0.4, DEPTH - 0.5, depth + 0.4), wall)
  tower.position.set(cx, y0 + (DEPTH - 0.5) / 2, cz)
  root.add(tower)
  const ours = neon('FLOOR42', '#29f6ff', '#06141a')
  const signs: THREE.Mesh[] = []
  // The glass elevator runs up the middle of the east face: the sign there keeps to its southern half.
  for (const [w, x, z, yaw] of [
    [Math.min(width, 11), cx, cz + depth / 2 + 0.25, 0],
    [Math.min(depth / 2 - 1.5, 10), cx + width / 2 + 0.25, cz + depth / 4 + 0.4, Math.PI / 2],
  ] as const) {
    const sign = new THREE.Mesh(new THREE.PlaneGeometry(w, w / 5), new THREE.MeshBasicMaterial({ map: ours }))
    sign.position.set(x, -3.2, z)
    sign.rotation.y = yaw
    root.add(sign)
    signs.push(sign)
  }
  const asphalt = new THREE.Mesh(new THREE.PlaneGeometry(SPAN * 2, SPAN * 2), new THREE.MeshLambertMaterial({ color: 0x2b2f33 }))
  asphalt.rotation.x = -Math.PI / 2
  asphalt.position.set(cx, y0, cz)
  root.add(asphalt)
  return { wall, signs }
}

/** The blocks of the street grid around the tower. */
export function blocksAround(cx: number, cz: number): Block[] {
  const blocks: Block[] = []
  for (let gx = -SPAN; gx < SPAN; gx += CELL) {
    for (let gz = -SPAN; gz < SPAN; gz += CELL) {
      blocks.push({ x: cx + gx + CELL / 2, z: cz + gz + CELL / 2, gx: Math.round(gx / CELL), gz: Math.round(gz / CELL) })
    }
  }
  return blocks
}

/** What the pavements carry, gathered block by block before the meshes are made. */
interface Pavements {
  ground: Boxes
  zebra: Boxes
  lamps: Boxes
  bulbs: Boxes
  signals: number[]
  trees: Tree[]
}

/** One block's pavement: zebra crossings at its corner, lamp posts with an arm over the road, a traffic light, trees. */
function pave(p: Pavements, b: Block, y0: number, rnd: () => number): void {
  p.ground.add(b.x, y0, b.z, BLOCK, 0.3, BLOCK, 0x8c8a84)
  for (let k = 0; k < 4; k++) p.zebra.add(b.x + BLOCK / 2 + ROAD / 2, y0, b.z - BLOCK / 2 + 0.6 + k * 0.7, ROAD - 0.6, 0.02, 0.35, 0xf0f0f0)
  for (let k = 0; k < 4; k++) p.zebra.add(b.x - BLOCK / 2 + 0.6 + k * 0.7, y0, b.z + BLOCK / 2 + ROAD / 2, 0.35, 0.02, ROAD - 0.6, 0xf0f0f0)
  for (const [dx, dz, ax, az] of [
    [BLOCK / 2 - 0.4, -3, 1, 0],
    [BLOCK / 2 - 0.4, 3, 1, 0],
    [-3, BLOCK / 2 - 0.4, 0, 1],
    [3, BLOCK / 2 - 0.4, 0, 1],
  ]) {
    p.lamps.add(b.x + dx, y0 + 0.3, b.z + dz, 0.14, 3.2, 0.14, 0x2a2f33)
    p.lamps.add(b.x + dx + ax * 0.45, y0 + 3.4, b.z + dz + az * 0.45, ax ? 0.9 : 0.1, 0.1, az ? 0.9 : 0.1, 0x2a2f33)
    p.bulbs.add(b.x + dx + ax * 0.85, y0 + 3.25, b.z + dz + az * 0.85, 0.35, 0.15, 0.35, 0xfff1c0)
  }
  const corner = [b.x + BLOCK / 2 - 0.4, b.z + BLOCK / 2 - 0.4]
  p.lamps.add(corner[0], y0 + 0.3, corner[1], 0.12, 2.2, 0.12, 0x2a2f33)
  p.lamps.add(corner[0], y0 + 2.5, corner[1], 0.3, 0.8, 0.3, 0x1d2226)
  p.signals.push(corner[0], y0 + 2.5, corner[1])
  for (const t of [-5, 0, 5]) {
    if (rnd() < 0.4) {
      p.trees.push({ x: b.x + t, y: y0 + 0.3, z: b.z + BLOCK / 2 - 0.6, s: 0.5 + rnd() * 0.15, fir: false })
      p.ground.add(b.x + t, y0 + 0.3, b.z + BLOCK / 2 - 0.6, 0.9, 0.03, 0.9, 0x4a3a2a)
    }
  }
}

/** Each traffic light shows red one way and green the other, and they swap together (see City.update). */
function trafficLights(unit: THREE.BufferGeometry, material: THREE.Material, signals: number[]): THREE.InstancedMesh {
  const lights = new THREE.InstancedMesh(unit, material, (signals.length / 3) * 2)
  const matrix = new THREE.Matrix4()
  const size = new THREE.Vector3(0.18, 0.18, 0.18)
  for (let i = 0; i < signals.length / 3; i++) {
    for (const k of [0, 1]) {
      const pos = new THREE.Vector3(signals[i * 3] + (k ? 0.16 : 0), signals[i * 3 + 1] + 0.55, signals[i * 3 + 2] + (k ? 0 : 0.16))
      lights.setMatrixAt(i * 2 + k, matrix.compose(pos, new THREE.Quaternion(), size))
      lights.setColorAt(i * 2 + k, new THREE.Color(0xff3b30))
    }
  }
  return lights
}

/** Pavements, zebra crossings at each corner, street lamps and street trees: returns the trees still to plant. */
export function streets(ctx: CityContext, blocks: Block[]): StreetLights & { trees: Tree[] } {
  const { root, unit, cache, y0, rnd } = ctx
  const p: Pavements = { ground: new Boxes(), zebra: new Boxes(), lamps: new Boxes(), bulbs: new Boxes(), signals: [], trees: [] }
  for (const b of blocks) pave(p, b, y0, rnd)
  root.add(p.ground.mesh(unit, cache.lambert()))
  root.add(p.zebra.mesh(unit, cache.basic()))
  root.add(p.lamps.mesh(unit, cache.lambert()))
  const bulbs = new THREE.MeshBasicMaterial({ color: 0xfff1c0 })
  root.add(p.bulbs.mesh(unit, bulbs))
  const signals = trafficLights(unit, cache.basic(), p.signals)
  root.add(signals)
  return { bulbs, signals, trees: p.trees }
}
