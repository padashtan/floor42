import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { BLADE, brackets, clear, type Face, frontOf, glowRect, legsOf, planFace, PLATE, type Side, type Sign, type Solid } from '../signs'
import type { Front } from './buildings'
import { COMPANIES, IRON, PLATE_COLOR, WORDS } from './colors'
import { Boxes, type CityContext, pick, seeded } from './kit'
import { glowTexture, signTexture } from './textures'

/** Where a face stands: the middle of its foot line on the wall, and the way it looks (+z south, +x east). */
interface Wall {
  x: number
  z: number
  nx: number
  nz: number
}

/** A sign that flickers now and then (see City.update). */
export interface Flicker {
  mesh: THREE.Mesh
  phase: number
}

const SIDES: Side[] = ['south', 'east']
const ONE = new THREE.Vector3(1, 1, 1)

/** One wall's frame: from the face's coordinates (u along, out from the wall, y up) to the city's. */
class WallFrame {
  readonly yaw: number
  readonly q: THREE.Quaternion
  private readonly wall: Wall

  constructor(wall: Wall) {
    this.wall = wall
    this.yaw = Math.atan2(wall.nx, wall.nz)
    this.q = new THREE.Quaternion().setFromAxisAngle(THREE.Object3D.DEFAULT_UP, this.yaw)
  }

  at(u: number, out: number, y: number): THREE.Vector3 {
    const w = this.wall
    return new THREE.Vector3(w.x + u * w.nz + out * w.nx, y, w.z - u * w.nx + out * w.nz)
  }

  place(u: number, out: number, y: number, q = this.q, size = ONE): THREE.Matrix4 {
    return new THREE.Matrix4().compose(this.at(u, out, y), q, size)
  }
}

/**
 * The neon signs of the city: plates and steel instanced, letters and glows merged per material. Every face the camera
 * sees that looks onto a street (or over a terrace) and is not pressed against a neighbour gets its signs from
 * planFace, then each sign is built on its wall. One additive glow material per neon colour, dimmed by day.
 */
export class NeonSigns {
  readonly glows = new Map<string, THREE.MeshBasicMaterial>()
  private readonly plates = new Boxes()
  private readonly iron = new Boxes()
  private readonly letters = new Map<string, { material: THREE.MeshBasicMaterial; geometries: THREE.BufferGeometry[] }>()
  private readonly halos = new Map<string, THREE.BufferGeometry[]>()
  // Their own draws, so the signs can change without moving anything else in the city.
  private readonly rnd = seeded(42)

  /** Lays out the signs of every face, adds them to the city; their letters go to `flicker`. */
  build(ctx: CityContext, fronts: Front[], solids: Solid[], flicker: Flicker[]): void {
    for (const fr of fronts) {
      const height = fr.s.top - fr.s.bottom
      // A framed sign on a flat roof, over one of its faces (both for JAVA).
      const roofSide = fr.flat && height > 14 && this.rnd() < 0.3 ? pick(SIDES, this.rnd) : null
      for (const side of SIDES) this.face(fr, side, roofSide, solids)
    }
    const { root, unit, cache } = ctx
    root.add(this.plates.mesh(unit, cache.lambert()))
    root.add(this.iron.mesh(unit, cache.lambert()))
    for (const { material, geometries } of this.letters.values()) {
      const mesh = new THREE.Mesh(mergeGeometries(geometries), material)
      root.add(mesh)
      flicker.push({ mesh, phase: this.rnd() * 10 })
    }
    for (const [color, geometries] of this.halos) {
      const halo = new THREE.Mesh(mergeGeometries(geometries), this.glow(color))
      halo.renderOrder = 2
      root.add(halo)
    }
  }

  /** One side of a building box: no sign on a shop front onto a courtyard, or on a wall against the next house. */
  private face(fr: Front, side: Side, roofSide: Side | null, solids: Solid[]): void {
    const { s, ground } = fr
    const south = side === 'south'
    const plane = south ? s.z + s.d / 2 : s.x + s.w / 2
    if (ground && (south ? fr.edge.z : fr.edge.x) - plane > 7) return
    if (!clear(frontOf(s, side, 2), solids.filter((o) => o !== s))) return
    const width = south ? s.w : s.d
    const height = s.top - s.bottom
    const rnd = this.rnd
    const face: Face = {
      width,
      bottom: s.bottom,
      top: s.top,
      floor: height / 16,
      shops: ground ? Math.max(1, Math.round(width / 3.2)) : 0,
      street: fr.street,
      company: south && fr.crown && !fr.java && height > 14 && rnd() < 0.5 ? pick(COMPANIES, rnd) : undefined,
      roof: fr.java ? { text: 'JAVA', color: '#ff8a2a' } : side === roofSide ? { text: pick(COMPANIES, rnd) } : undefined,
    }
    const wall: Wall = south ? { x: s.x, z: plane, nx: 0, nz: 1 } : { x: plane, z: s.z, nx: 1, nz: 0 }
    this.mount(face, new WallFrame(wall), planFace(face, rnd, WORDS))
  }

  /**
   * Builds the signs of one face on its wall: a flat sign is a plate flat on the wall with its letters in front; a
   * blade is a plate on two steel arms bolted to the wall, its letters on both sides; a rooftop sign stands on legs.
   * Each throws its glow on the wall around it, kept on the face.
   */
  private mount(f: Face, w: WallFrame, signs: Sign[]): void {
    for (const s of signs) {
      if (s.kind === 'blade') this.blade(w, s)
      else this.flat(f, w, s)
      const g = glowRect(f, s)
      if (!g) continue
      const list = this.halos.get(s.color) ?? []
      this.halos.set(s.color, list)
      list.push(new THREE.PlaneGeometry(g.u1 - g.u0, g.y1 - g.y0).applyMatrix4(w.place((g.u0 + g.u1) / 2, 0.03, (g.y0 + g.y1) / 2)))
    }
  }

  private box(list: Boxes, w: WallFrame, u: number, out: number, y: number, size: [number, number, number], color: number): void {
    list.put(w.place(u, out, y, w.q, new THREE.Vector3(...size)), color)
  }

  private blade(w: WallFrame, s: Sign): void {
    this.box(this.plates, w, s.u, s.back + s.depth / 2, s.y, [BLADE, s.h, s.depth], PLATE_COLOR)
    for (const arm of brackets(s)) {
      this.box(this.iron, w, s.u, (arm.from + arm.to) / 2, arm.y, [0.07, 0.07, arm.to - arm.from], IRON)
      this.box(this.iron, w, s.u, 0.02, arm.y, [0.18, 0.18, 0.04], IRON)
    }
    for (const side of [1, -1]) {
      const turn = new THREE.Quaternion().setFromAxisAngle(THREE.Object3D.DEFAULT_UP, w.yaw + (side * Math.PI) / 2)
      this.letter(s, true, w.place(s.u + side * (BLADE / 2 + 0.01), s.back + s.depth / 2, s.y, turn), s.depth, s.h)
    }
  }

  private flat(f: Face, w: WallFrame, s: Sign): void {
    this.box(this.plates, w, s.u, s.back + PLATE / 2, s.y, [s.w, s.h, PLATE], PLATE_COLOR)
    this.letter(s, false, w.place(s.u, s.back + PLATE + 0.01, s.y), s.w, s.h)
    for (const leg of legsOf(s)) this.box(this.iron, w, leg, s.back + PLATE / 2, f.top + s.legs / 2, [0.12, s.legs, 0.12], IRON)
    if (s.kind === 'rooftop') this.box(this.iron, w, s.u, s.back + PLATE / 2, f.top + s.legs / 2, [s.w - 0.2, 0.08, 0.08], IRON)
  }

  /** A sign's letters: one material per text, colour and direction, their planes merged into one mesh. */
  private letter(s: Sign, vertical: boolean, place: THREE.Matrix4, w: number, h: number): void {
    const key = s.text + s.color + (vertical ? '|' : '-')
    let entry = this.letters.get(key)
    if (!entry) {
      const material = new THREE.MeshBasicMaterial({
        map: signTexture(s.text, s.color, vertical),
        polygonOffset: true,
        polygonOffsetFactor: -1,
        polygonOffsetUnits: -1,
      })
      entry = { material, geometries: [] }
      this.letters.set(key, entry)
    }
    entry.geometries.push(new THREE.PlaneGeometry(w, h).applyMatrix4(place))
  }

  private glow(color: string): THREE.MeshBasicMaterial {
    let m = this.glows.get(color)
    if (!m) {
      m = new THREE.MeshBasicMaterial({
        map: glowTexture(color),
        transparent: true,
        blending: THREE.AdditiveBlending,
        depthWrite: false,
        opacity: 0.7,
        polygonOffset: true,
        polygonOffsetFactor: -1,
        polygonOffsetUnits: -1,
      })
      this.glows.set(color, m)
    }
    return m
  }
}
