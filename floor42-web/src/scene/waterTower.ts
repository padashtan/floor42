import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'

// New York's wooden rooftop water towers (Rosenwach, Isseks): a tank of cedar staves, about as tall as it is wide
// (10 to 12 feet for the common 10,000 gallons), slightly narrower at the top, held by steel hoops set closer together
// near the bottom where the water presses hardest; a shallow conical roof with a finial; the whole on a steel stand of
// legs and cross bracing, raised above the roof on steel beams, and a ladder up its side. Here one unit is about ten
// feet: a tank of one unit or so on a walk-up three to five units wide.

/** A flat roof: its centre, its outer size and the height of its surface. */
export interface Roof {
  x: number
  z: number
  w: number
  d: number
  y: number
}

/** Something already on the roof that the tower must not stand on: a centre and a size. */
export interface Area {
  x: number
  z: number
  w: number
  d: number
}

export interface WaterTower {
  x: number
  z: number
  /** The roof plane the stand's beams rest on. */
  y: number
  /** The tank's radius at the bottom and at the top (the staves taper). */
  radius: number
  top: number
  /** Height of the steel stand, of the tank and of its conical roof. */
  stand: number
  tank: number
  cap: number
  legs: 4 | 6
  /** Heights of the hoops above the tank's floor. */
  hoops: number[]
  /** Round the axis, the angle the ladder stands at (towards the camera, south-east). */
  ladder: number
  /** Horizontal radius everything stays within, ladder included. */
  reach: number
  /** From the roof to the tip of the finial. */
  height: number
}

/** The parapet's thickness, and the gap kept between it and the tower: a tower never stands on the edge. */
export const PARAPET = 0.14
export const CLEARANCE = 0.3
const MIN_RADIUS = 0.35
const MAX_RADIUS = 0.62
const FINIAL = 0.16
/** The steel platform on the stand (0.04, centred on its top) and the tank's wooden floor on it. */
const PLATFORM = 0.02
const DECK = 0.06
/** How far out of the tank the ladder stands, and its rails' half spacing. */
const LADDER_OUT = 0.1
const LADDER_HALF = 0.07
const RAIL = 0.03

/** Shortest distance from a point to an area (0 inside). */
function distanceTo(a: Area, x: number, z: number): number {
  const dx = Math.max(Math.abs(x - a.x) - a.w / 2, 0)
  const dz = Math.max(Math.abs(z - a.z) - a.d / 2, 0)
  return Math.hypot(dx, dz)
}

/**
 * Where and how big a water tower stands on a flat roof, or null when the roof is too small or too busy for one.
 * Its whole footprint, ladder included, stays inside the parapet with a clearance, off the areas in `keepOut`.
 * `r` gives random numbers in [0, 1).
 */
export function waterTowerPlan(roof: Roof, r: () => number, keepOut: Area[] = []): WaterTower | null {
  const hx = roof.w / 2 - PARAPET - CLEARANCE
  const hz = roof.d / 2 - PARAPET - CLEARANCE
  const room = Math.min(hx, hz)
  const wanted = Math.min(MAX_RADIUS, 0.3 * Math.min(roof.w, roof.d)) * (0.85 + 0.3 * r())
  const radius = Math.min(wanted, room - LADDER_OUT - RAIL)
  if (radius < MIN_RADIUS) return null
  const shape = towerShape(radius, r)
  // Towards the back of the roof (north), away from the street: a few tries, the first that keeps off everything.
  const spanX = hx - shape.reach
  const spanZ = hz - shape.reach
  for (let i = 0; i < 12; i++) {
    const x = roof.x + (r() * 2 - 1) * spanX
    const z = roof.z - r() * spanZ
    if (keepOut.some((a) => distanceTo(a, x, z) < shape.reach + 0.05)) continue
    return { x, z, y: roof.y, ...shape }
  }
  return null
}

/** A tower of `radius`, wherever it stands: its tank, its stand, its legs and hoops, drawn from `r`. */
function towerShape(radius: number, r: () => number): Omit<WaterTower, 'x' | 'z' | 'y'> {
  const reach = radius + LADDER_OUT + RAIL
  const tank = 2 * radius * (0.95 + 0.25 * r())
  const stand = tank * (0.55 + 0.35 * r())
  const cap = 2 * radius * 0.26
  const legs = radius > 0.5 && r() < 0.4 ? 6 : 4
  const n = 3 + Math.floor(r() * 3)
  // Closer together near the bottom, where the water presses hardest.
  const hoops = Array.from({ length: n }, (_, i) => tank * (0.07 + 0.84 * Math.pow(i / (n - 1), 1.35)))
  const height = stand + PLATFORM + DECK + tank + cap + FINIAL
  return { radius, top: radius * 0.94, stand, tank, cap, legs, hoops, ladder: Math.PI / 4, reach, height }
}

/** Light cedar and darker, weathered staves; the plain right quarter of the texture is for every other part. */
export const STAVE_SPAN = 0.75
const PLAIN = new THREE.Vector2(0.88, 0.5)

/** Vertical staves with dark seams and a little grain, in white so each tower tints them with its wood. */
export function staveTexture(): THREE.CanvasTexture {
  const c = document.createElement('canvas')
  c.width = 96
  c.height = 16
  const g = c.getContext('2d')!
  g.fillStyle = '#ffffff'
  g.fillRect(0, 0, 96, 16)
  let s = 7
  const rnd = () => ((s = (s * 16807) % 2147483647) - 1) / 2147483646
  for (let i = 0; i < 24; i++) {
    const shade = 200 + Math.floor(rnd() * 55)
    g.fillStyle = `rgb(${shade},${shade},${shade})`
    g.fillRect(i * 3, 0, 2, 16)
    g.fillStyle = '#6a6a6a'
    g.fillRect(i * 3 + 2, 0, 1, 16)
    g.fillStyle = 'rgba(0,0,0,0.12)'
    for (let k = 0; k < 2; k++) g.fillRect(i * 3 + Math.floor(rnd() * 2), Math.floor(rnd() * 14), 1, 2)
  }
  const t = new THREE.CanvasTexture(c)
  t.magFilter = THREE.NearestFilter
  t.minFilter = THREE.NearestFilter
  t.colorSpace = THREE.SRGBColorSpace
  return t
}

const STEEL = 0x2c2f33
const HOOP = 0x1b1c1e
const ROOFING = 0x3a3330
const GALVANIZED = 0x9aa0a6
const Y = new THREE.Vector3(0, 1, 0)

/** One part, coloured, every vertex its own (the parts merge into one mesh); `staves` keeps its UVs for the texture. */
function part(g: THREE.BufferGeometry, color: THREE.ColorRepresentation, staves = false): THREE.BufferGeometry {
  const p = g.index ? g.toNonIndexed() : g
  const uv = p.getAttribute('uv') as THREE.BufferAttribute
  for (let i = 0; i < uv.count; i++) {
    if (staves) uv.setX(i, uv.getX(i) * STAVE_SPAN)
    else uv.setXY(i, PLAIN.x, PLAIN.y)
  }
  const c = new THREE.Color(color)
  const colors = new Float32Array(p.getAttribute('position').count * 3)
  for (let i = 0; i < colors.length; i += 3) colors.set([c.r, c.g, c.b], i)
  p.setAttribute('color', new THREE.BufferAttribute(colors, 3))
  return p
}

/** A square steel bar from `a` to `b`. */
function bar(a: THREE.Vector3, b: THREE.Vector3, t: number, color: THREE.ColorRepresentation): THREE.BufferGeometry {
  const dir = b.clone().sub(a)
  const g = new THREE.BoxGeometry(t, dir.length(), t)
  g.applyMatrix4(
    new THREE.Matrix4().compose(a.clone().add(b).multiplyScalar(0.5), new THREE.Quaternion().setFromUnitVectors(Y, dir.normalize()), new THREE.Vector3(1, 1, 1)),
  )
  return part(g, color)
}

/** Builds a tower's parts in city coordinates: `v` a point from the tower's foot, `at` a geometry lifted to `y`. */
interface Builder {
  t: WaterTower
  parts: THREE.BufferGeometry[]
  v: (x: number, y: number, z: number) => THREE.Vector3
  at: (g: THREE.BufferGeometry, y: number) => THREE.BufferGeometry
  /** The tank's floor: on the platform's deck. */
  floor: number
}

/**
 * The tower as one geometry in city coordinates (position, normal, uv, colour): every tower of the city merges into a
 * single mesh, one draw call, lit like the other roofs.
 */
export function waterTowerGeometry(t: WaterTower, wood: THREE.ColorRepresentation): THREE.BufferGeometry {
  const b: Builder = {
    t,
    parts: [],
    v: (x, y, z) => new THREE.Vector3(t.x + x, t.y + y, t.z + z),
    at: (g, y) => g.translate(t.x, t.y + y, t.z),
    floor: t.stand + PLATFORM + DECK,
  }
  standOf(b, new THREE.Color(wood))
  tankOf(b, wood)
  ladderOf(b)
  return mergeGeometries(b.parts)!
}

/** Steel beams on the roof (dunnage) under the legs, the legs braced in X between each pair, a ring on top, the platform. */
function standOf({ t, parts, v, at }: Builder, woodColor: THREE.Color): void {
  const beam = 0.07
  const spread = t.radius * 0.82
  const feet = Array.from({ length: t.legs }, (_, i) => {
    const a = (i + 0.5) * ((Math.PI * 2) / t.legs)
    return [Math.cos(a) * spread, Math.sin(a) * spread] as const
  })
  for (const s of [-1, 1]) {
    const z = s * spread * Math.sin(Math.PI / t.legs)
    parts.push(bar(v(-spread - 0.05, beam / 2, z), v(spread + 0.05, beam / 2, z), beam, STEEL))
  }
  feet.forEach(([x, z], i) => {
    const [nx, nz] = feet[(i + 1) % feet.length]
    parts.push(bar(v(x, 0, z), v(x, t.stand, z), 0.06, STEEL))
    parts.push(bar(v(x, beam + 0.05, z), v(nx, t.stand - 0.05, nz), 0.025, STEEL))
    parts.push(bar(v(nx, beam + 0.05, nz), v(x, t.stand - 0.05, z), 0.025, STEEL))
    parts.push(bar(v(x, t.stand - 0.02, z), v(nx, t.stand - 0.02, nz), 0.05, STEEL))
  })
  // The platform the tank sits on, and its wooden floor.
  parts.push(part(at(new THREE.CylinderGeometry(spread * 1.12, spread * 1.12, PLATFORM * 2, t.legs * 2), t.stand), STEEL))
  const deck = new THREE.CylinderGeometry(t.radius * 1.02, t.radius * 1.02, DECK, 12)
  parts.push(part(at(deck, t.stand + PLATFORM + DECK / 2), woodColor.clone().multiplyScalar(0.7)))
}

/** The tank: tapered staves, open at the top under its roof; the hoops round it; the shallow conical roof and its finial. */
function tankOf({ t, parts, at, floor }: Builder, wood: THREE.ColorRepresentation): void {
  parts.push(part(at(new THREE.CylinderGeometry(t.top, t.radius, t.tank, 12, 1, true), floor + t.tank / 2), wood, true))
  for (const h of t.hoops) {
    const r = (t.radius + (t.top - t.radius) * (h / t.tank)) * 1.035
    parts.push(part(at(new THREE.CylinderGeometry(r, r, 0.045, 12, 1, true), floor + h), HOOP))
  }
  // The roof overhangs the staves.
  const eave = t.top * 1.1
  parts.push(part(at(new THREE.ConeGeometry(eave, t.cap, 12, 1, true), floor + t.tank + t.cap / 2), ROOFING))
  const tip = floor + t.tank + t.cap
  parts.push(part(at(new THREE.CylinderGeometry(0.02, 0.03, FINIAL * 0.6, 6), tip + FINIAL * 0.3 - 0.02), GALVANIZED))
  parts.push(part(at(new THREE.OctahedronGeometry(0.045), tip + FINIAL - 0.045), GALVANIZED))
}

/** The ladder, from the roof up past the eave, on the side the camera sees. */
function ladderOf({ t, parts, v, floor }: Builder): void {
  const out = t.radius + LADDER_OUT
  const ax = Math.cos(t.ladder)
  const az = Math.sin(t.ladder)
  const top = floor + t.tank + 0.12
  for (const s of [-1, 1]) {
    const x = ax * out - az * s * LADDER_HALF
    const z = az * out + ax * s * LADDER_HALF
    parts.push(bar(v(x, 0, z), v(x, top, z), RAIL, STEEL))
  }
  for (let h = 0.18; h < top - 0.05; h += 0.16) {
    parts.push(bar(v(ax * out + az * LADDER_HALF, h, az * out - ax * LADDER_HALF), v(ax * out - az * LADDER_HALF, h, az * out + ax * LADDER_HALF), 0.02, STEEL))
  }
}
