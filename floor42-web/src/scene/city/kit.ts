import * as THREE from 'three'
import type { ResourceCache } from '../resources'

// The city far below the office: Floor42 sits on a high floor of a Manhattan tower. Everything here is scenery: the
// cars, the passers-by, the shops, the firefighters, the neon signs and the weather say nothing about the company.
// Only the lit windows follow the director's real clock. The company names are invented.

/** How far below the office floor the street is. */
export const DEPTH = 46
/** Half the width of the city shown around the tower. */
export const SPAN = 72
export const BLOCK = 14
export const ROAD = 4
export const CELL = BLOCK + ROAD
/** The tallest neighbour stays this far below the office floor: the view is from above. */
export const CEILING = DEPTH - 8

export type Weather = 'clear' | 'clouds' | 'rain'

/** Something driving along an avenue: its lane, its axis and way, where it is along it and how fast it goes. */
export interface Mover {
  lane: number
  axis: 'x' | 'z'
  dir: 1 | -1
  pos: number
  speed: number
}

/** One block of the street grid: its centre, and its place in the grid (0, 0 is the tower's south-east). */
export interface Block {
  x: number
  z: number
  gx: number
  gz: number
}

/** A building's footprint and height: shops and signs go on the faces the camera sees (east and south). */
export interface Lot {
  x: number
  z: number
  w: number
  d: number
  h: number
  base: number
}

/** What every builder of the city works with: where to add, the city's own draws and the shared resources. */
export interface CityContext {
  root: THREE.Group
  rnd: () => number
  cache: ResourceCache
  /** Centre of the tower's footprint. */
  cx: number
  cz: number
  /** The street's height. */
  y0: number
  unit: THREE.BoxGeometry
}

/** A small deterministic random generator, so the city is the same on every screen. */
export function seeded(seed: number): () => number {
  let s = seed
  return () => {
    s = (s * 16807) % 2147483647
    return (s - 1) / 2147483646
  }
}

export const hex = (n: number) => '#' + n.toString(16).padStart(6, '0')
export const pick = <T>(list: T[], r: () => number): T => list[Math.floor(r() * list.length)]

/** The tower's own block, and the park north-west of it. */
export const own = (b: { gx: number; gz: number }) => b.gx === -1 && b.gz === -1
export const park = (b: { gx: number; gz: number }) => b.gx <= -2 && b.gx >= -3 && b.gz <= -2 && b.gz >= -4

export const shape = (x: number, y: number, z: number, w: number, h: number, d: number, q = new THREE.Quaternion()) =>
  new THREE.Matrix4().compose(new THREE.Vector3(x, y, z), q, new THREE.Vector3(w, h, d))

export const at = (x: number, y: number, z: number, rx = 0) =>
  new THREE.Matrix4().compose(new THREE.Vector3(x, y, z), new THREE.Quaternion().setFromEuler(new THREE.Euler(rx, 0, 0)), new THREE.Vector3(1, 1, 1))

/** One instanced box per kind of building, so the whole skyline is a handful of draw calls. */
export class Boxes {
  readonly list: THREE.Matrix4[] = []
  readonly colors: THREE.Color[] = []

  /** A box standing on `y`. */
  add(x: number, y: number, z: number, w: number, h: number, d: number, color: THREE.ColorRepresentation): void {
    this.put(shape(x, y + h / 2, z, w, h, d), color)
  }

  /** Any placement of the mesh's geometry. */
  put(m: THREE.Matrix4, color: THREE.ColorRepresentation): void {
    this.list.push(m)
    this.colors.push(new THREE.Color(color))
  }

  mesh(geometry: THREE.BufferGeometry, material: THREE.Material): THREE.InstancedMesh {
    const m = new THREE.InstancedMesh(geometry, material, Math.max(1, this.list.length))
    this.list.forEach((x, i) => {
      m.setMatrixAt(i, x)
      m.setColorAt(i, this.colors[i])
    })
    m.count = this.list.length
    return m
  }
}

/** One instanced mesh of a single colour, placed at each matrix of the list. */
export function instanced(geometry: THREE.BufferGeometry, material: THREE.Material, list: THREE.Matrix4[]): THREE.InstancedMesh {
  const m = new THREE.InstancedMesh(geometry, material, Math.max(1, list.length))
  list.forEach((x, i) => m.setMatrixAt(i, x))
  m.count = list.length
  return m
}

/** A box of one colour, lit or glowing, at a place inside its group. */
export function block(size: [number, number, number], where: [number, number, number], color: number, glowing = false): THREE.Mesh {
  const m = new THREE.Mesh(
    new THREE.BoxGeometry(...size),
    glowing ? new THREE.MeshBasicMaterial({ color }) : new THREE.MeshLambertMaterial({ color }),
  )
  m.position.set(...where)
  return m
}

/** Four wheels under a vehicle group: `x` and `z` from its centre, `y` the axle height. */
export function wheels(g: THREE.Group, x: number, z: number, y: number, geometry: THREE.BufferGeometry, material: THREE.Material): void {
  for (const [sx, sz] of [
    [1, 1],
    [1, -1],
    [-1, 1],
    [-1, -1],
  ]) {
    const w = new THREE.Mesh(geometry, material)
    w.rotation.x = Math.PI / 2
    w.position.set(sx * x, y, sz * z)
    w.userData.wheel = true
    g.add(w)
  }
}
