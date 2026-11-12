import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { staveTexture, waterTowerGeometry, waterTowerPlan } from '../waterTower'
import { CEDAR, GRANITE, MEMBRANE, SLATE, TILES } from './colors'
import { Boxes, type CityContext, pick, seeded, shape } from './kit'
import { helipad, roofCovering } from './textures'

// The roofs of the city: what each kind of building carries on top, one instanced mesh per part for the whole city.

/** What the roofs are made of, each part one instanced mesh for the whole city. */
export interface RoofKit {
  /** Flat roofs and parapets. */
  caps: Boxes
  /** Chimneys, air conditioners, penthouses, decks, planters, skylight frames. */
  kit: Boxes
  glass: Boxes
  slopes: Boxes
  hips: Boxes
  ends: Boxes
  /** Boxes lying on a slope: roof windows. */
  tilted: Boxes
  /** Terrace parasols. */
  cones: Boxes
  beacons: Boxes
  helipads: THREE.Matrix4[]
  /** Water towers, each already in city coordinates: merged into one mesh. */
  towers: THREE.BufferGeometry[]
}

export function roofKit(): RoofKit {
  return {
    caps: new Boxes(),
    kit: new Boxes(),
    glass: new Boxes(),
    slopes: new Boxes(),
    hips: new Boxes(),
    ends: new Boxes(),
    tilted: new Boxes(),
    cones: new Boxes(),
    beacons: new Boxes(),
    helipads: [],
    towers: [],
  }
}

/** A surface from a list of triangles (x, y, z each), flat shaded; a texture runs across each face and up its slope. */
function surface(p: number[]): THREE.BufferGeometry {
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.Float32BufferAttribute(p, 3))
  g.computeVertexNormals()
  const n = g.getAttribute('normal')
  const uv: number[] = []
  for (let i = 0; i < p.length / 3; i++) {
    const across = Math.abs(n.getZ(i)) >= Math.abs(n.getX(i)) ? p[i * 3] : p[i * 3 + 2]
    uv.push(across + 0.5, p[i * 3 + 1])
  }
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
  return g
}

/**
 * A unit pitched roof standing on y = 0: 1 wide (x), 1 deep (z), 1 high, its ridge along x. `ridge` is half the
 * ridge's length: 0.5 for a gable roof (its ends are the walls' gables), less for a hipped roof (sloped ends).
 */
function pitched(ridge: number): THREE.BufferGeometry {
  const r = ridge
  const p = [-0.5, 0, 0.5, 0.5, 0, 0.5, r, 1, 0, -0.5, 0, 0.5, r, 1, 0, -r, 1, 0, 0.5, 0, -0.5, -0.5, 0, -0.5, -r, 1, 0, 0.5, 0, -0.5, -r, 1, 0, r, 1, 0]
  if (r < 0.5) p.push(0.5, 0, 0.5, 0.5, 0, -0.5, r, 1, 0, -0.5, 0, -0.5, -0.5, 0, 0.5, -r, 1, 0)
  return surface(p)
}

/** The two triangular wall ends under a unit gable roof. */
const gables = () => surface([0.5, 0, 0.5, 0.5, 0, -0.5, 0.5, 1, 0, -0.5, 0, -0.5, -0.5, 0, 0.5, -0.5, 1, 0])


/**
 * A walk-up's roof on top of its walls (`y`), ridge along x: `kind` below 0.25 is a Breton house (steep slate,
 * granite chimney stacks on both gables), below 0.45 a tiled roof (gable or hipped), else a flat roof.
 */
export function walkUpRoof(k: RoofKit, x: number, y: number, z: number, w: number, d: number, kind: number, wall: number, rnd: () => number): void {
  if (kind < 0.25) {
    const rise = 1.8 + rnd() * 0.6
    k.slopes.put(shape(x, y, z, w + 0.2, rise, d + 0.5), pick(SLATE, rnd))
    k.ends.put(shape(x, y, z, w, rise, d), wall)
    for (const s of [-1, 1]) {
      k.kit.add(x + s * (w / 2 - 0.25), y + rise - 0.5, z, 0.5, 1.1, 0.9, pick(GRANITE, rnd))
      k.kit.add(x + s * (w / 2 - 0.25), y + rise + 0.6, z, 0.56, 0.08, 0.96, 0x6b6e70)
    }
    if (rnd() < 0.45) rooflights(k, x, y, z, w, d + 0.5, rise, rnd)
  } else if (kind < 0.45) {
    const rise = 1.0 + rnd() * 0.5
    const hipped = rnd() < 0.5
    ;(hipped ? k.hips : k.slopes).put(shape(x, y, z, w + 0.2, rise, d + 0.4), pick(TILES, rnd))
    if (!hipped) k.ends.put(shape(x, y, z, w, rise, d), wall)
    if (rnd() < 0.6) k.kit.add(x + (rnd() - 0.5) * w * 0.5, y + rise * 0.4, z - d * 0.15, 0.35, rise * 0.6 + 0.5, 0.35, 0x7a3f33)
    if (rnd() < 0.5) rooflights(k, x, y, z, w, d + 0.4, rise, rnd)
  } else {
    flatRoof(k, x, y, z, w, d, wall, rnd)
  }
}

/** One or two roof windows on the slope the camera sees (south), half way up: a frame and its glass. */
function rooflights(k: RoofKit, x: number, y: number, z: number, w: number, depth: number, rise: number, rnd: () => number): void {
  const a = Math.atan2(rise, depth / 2)
  const q = new THREE.Quaternion().setFromEuler(new THREE.Euler(a, 0, 0))
  const n = rnd() < 0.5 ? 1 : 2
  for (let i = 0; i < n; i++) {
    const px = x + (n === 1 ? (rnd() - 0.5) * w * 0.3 : (i - 0.5) * w * 0.45)
    // Just above the slope, along its normal.
    const lift = (o: number) => [px, y + rise / 2 + Math.cos(a) * o, z + depth / 4 + Math.sin(a) * o] as const
    k.tilted.put(shape(...lift(0.03), 0.7, 0.06, 0.8, q), 0xd8dcde)
    k.tilted.put(shape(...lift(0.06), 0.55, 0.04, 0.62, q), 0x5f87a6)
  }
}

/** A flat roof's top: its centre, the height things stand on, its size. */
interface Flat {
  x: number
  top: number
  z: number
  w: number
  d: number
}

/** A flat roof behind its parapet, with what New York roofs carry: a water tower, a terrace, skylights, machines. */
function flatRoof(k: RoofKit, x: number, y: number, z: number, w: number, d: number, wall: number, rnd: () => number): void {
  k.caps.add(x, y, z, w + 0.1, 0.1, d + 0.1, pick(MEMBRANE, rnd))
  parapet(k, x, y, z, w, d, wall)
  const f: Flat = { x, top: y + 0.1, z, w, d }
  const what = rnd()
  if (what < 0.3) roofTower(k, f, rnd)
  else if (what < 0.55) terrace(k, f, rnd)
  else skylights(k, f, rnd)
  // An air conditioner with its fan, a vent pipe or two.
  const { top } = f
  if (rnd() < 0.5) {
    k.kit.add(x + w * 0.28, top, z - d * 0.32, 0.7, 0.45, 0.5, 0xb9bec2)
    k.kit.add(x + w * 0.28, top + 0.45, z - d * 0.32, 0.4, 0.02, 0.4, 0x3a3f44)
  }
  if (rnd() < 0.6) k.kit.add(x - w * 0.35, top, z + d * 0.35, 0.12, 0.5, 0.12, 0x6b6e70)
}

/** A wooden water tower on its steel stand, off the edges and off the machines placed after it (waterTower.ts). */
function roofTower(k: RoofKit, { x, top, z, w, d }: Flat, rnd: () => number): void {
  const own = seeded(1 + Math.floor(rnd() * 2147483645))
  const keepOut = [
    { x: x + w * 0.28, z: z - d * 0.32, w: 0.7, d: 0.5 },
    { x: x - w * 0.35, z: z + d * 0.35, w: 0.12, d: 0.12 },
  ]
  const tower = waterTowerPlan({ x, z, w, d, y: top }, own, keepOut)
  if (tower) k.towers.push(waterTowerGeometry(tower, pick(CEDAR, own)))
}

/** A terrace: a deck, planters, two chairs and a parasol. */
function terrace(k: RoofKit, { x, top, z, w, d }: Flat, rnd: () => number): void {
  k.kit.add(x, top, z + d * 0.08, w * 0.8, 0.06, d * 0.6, 0x9a6b44)
  for (const s of [-1, 1]) k.kit.add(x + s * (w * 0.4 - 0.25), top, z - d * 0.3, 0.45, 0.35, 0.45, 0x4f9a3a)
  k.kit.add(x - 0.4, top + 0.06, z + d * 0.2, 0.3, 0.3, 0.3, 0xf4f4f0)
  k.kit.add(x + 0.4, top + 0.06, z + d * 0.2, 0.3, 0.3, 0.3, 0xf4f4f0)
  k.kit.add(x, top + 0.06, z + d * 0.08, 0.05, 1.1, 0.05, 0x555555)
  k.cones.put(shape(x, top + 1.3, z + d * 0.08, 1.4, 0.35, 1.4), pick([0xc8463c, 0xf2c230, 0x3a8fd8, 0xf4f4f0], rnd))
}

/** Skylights in a row. */
function skylights(k: RoofKit, { x, top, z, w, d }: Flat, rnd: () => number): void {
  const n = 1 + Math.floor(rnd() * 3)
  for (let i = 0; i < n; i++) {
    const sz = z - d * 0.25 + (i * d * 0.5) / Math.max(1, n - 1)
    k.kit.add(x - w * 0.15, top, n === 1 ? z : sz, 0.9, 0.18, 0.65, 0xc9d2d8)
    k.glass.add(x - w * 0.15, top + 0.18, n === 1 ? z : sz, 0.76, 0.03, 0.5, 0x6f9ab8)
  }
}

/** A low wall round a flat roof standing on `y`, with a pale coping on top. */
export function parapet(k: RoofKit, x: number, y: number, z: number, w: number, d: number, wall: number): void {
  const t = 0.14
  const h = 0.32
  const coping = new THREE.Color(wall).lerp(new THREE.Color(0xffffff), 0.35)
  for (const s of [-1, 1]) {
    k.caps.add(x, y, z + s * (d / 2 - t / 2 + 0.05), w + 0.1, h, t, coping)
    k.caps.add(x + s * (w / 2 - t / 2 + 0.05), y, z, t, h, d + 0.1, coping)
  }
}

/** On a tall building's roof: a machine room, cooling units, sometimes an antenna with its red warning light. */
export function machinery(k: RoofKit, x: number, y: number, z: number, w: number, rnd: () => number): void {
  const mw = w * (0.3 + rnd() * 0.15)
  const mh = 1 + rnd() * 0.8
  k.kit.add(x - w * 0.12, y, z - w * 0.1, mw, mh, mw * 0.8, pick([0x7a8288, 0x8a8478, 0x6b6e70], rnd))
  k.kit.add(x - w * 0.12, y + mh, z - w * 0.1, mw + 0.1, 0.08, mw * 0.8 + 0.1, 0x3a3f44)
  for (let i = 0; i < 3; i++) {
    k.kit.add(x + w * 0.22, y, z - w * 0.25 + i * w * 0.18, w * 0.16, 0.5, w * 0.13, 0xb9bec2)
    k.kit.add(x + w * 0.22, y + 0.5, z - w * 0.25 + i * w * 0.18, w * 0.09, 0.02, w * 0.09, 0x2b2d2f)
  }
  if (rnd() < 0.5) {
    const ax = x - w * 0.12
    const az = z - w * 0.1
    k.kit.add(ax, y + mh, az, 0.08, 3, 0.08, 0x9aa0a6)
    k.beacons.add(ax, y + mh + 3, az, 0.2, 0.2, 0.2, 0xff3b30)
  }
}

/** Adds the roofs of the whole city: one mesh per part, whatever building carries it. */
export function addRoofs(ctx: CityContext, roofs: RoofKit): void {
  const { root, unit, cache } = ctx
  const covered = cache.material('roof-covering', () => new THREE.MeshLambertMaterial({ map: roofCovering() }))
  root.add(roofs.caps.mesh(unit, cache.lambert()))
  root.add(roofs.kit.mesh(unit, cache.lambert()))
  root.add(roofs.glass.mesh(unit, new THREE.MeshLambertMaterial({ emissive: 0x16222c })))
  root.add(roofs.slopes.mesh(pitched(0.5), covered))
  root.add(roofs.hips.mesh(pitched(0.22), covered))
  root.add(roofs.ends.mesh(gables(), cache.lambert()))
  root.add(roofs.tilted.mesh(unit, cache.lambert()))
  root.add(roofs.cones.mesh(new THREE.ConeGeometry(0.5, 1, 10), cache.material('lambert-flat', () => new THREE.MeshLambertMaterial({ flatShading: true }))))
  root.add(roofs.beacons.mesh(unit, cache.basic()))
  if (roofs.towers.length) {
    root.add(new THREE.Mesh(mergeGeometries(roofs.towers), new THREE.MeshLambertMaterial({ map: staveTexture(), vertexColors: true })))
  }
  const pads = new THREE.InstancedMesh(
    new THREE.PlaneGeometry(1, 1).rotateX(-Math.PI / 2),
    new THREE.MeshLambertMaterial({ map: helipad() }),
    Math.max(1, roofs.helipads.length),
  )
  roofs.helipads.forEach((m, i) => pads.setMatrixAt(i, m))
  pads.count = roofs.helipads.length
  root.add(pads)
}
