import * as THREE from 'three'
import type { Solid, Street } from '../signs'
import { BRICK, GLASS, GRANITE, MEMBRANE, PAINT, STONE } from './colors'
import { BLOCK, type Block, Boxes, CEILING, type CityContext, instanced, type Lot, own, park, pick, shape } from './kit'
import { machinery, parapet, type RoofKit, walkUpRoof } from './roofs'
import type { Tree } from './streets'
import { facade } from './textures'

// Kinds of buildings, Manhattan style: painted and brick walk-ups with water towers, stepped limestone towers with
// spires, glass towers; a park north-west of the tower, with trees and a pond.

/** A building box whose faces may carry signs (see citySigns.ts). */
export interface Front {
  s: Solid
  street: Street
  ground: boolean
  crown: boolean
  flat: boolean
  java?: boolean
  edge: { x: number; z: number }
}

/** Where the fire truck parks: by a walk-up near the tower. */
export interface FireSite {
  x: number
  z: number
  h: number
}

/** Everything the buildings of the city are made of, gathered block by block before the meshes are made. */
interface Skyline {
  brick: Boxes
  stone: Boxes
  glass: Boxes
  green: Boxes
  spires: THREE.Matrix4[]
  roofs: RoofKit
  trees: Tree[]
  lots: Lot[]
  /** Every building box, for the signs: a face pressed against a neighbour carries none. */
  solids: Solid[]
  /** `ground` faces have shops and must look onto a street (`edge`); `crown` is a building's highest box. */
  fronts: Front[]
  stepped: Set<Lot>
  fireSite: FireSite | null
}

/** What the rest of the city builds on: shops on the lots, signs on the fronts, the fire by a walk-up. */
export type Buildings = Pick<Skyline, 'lots' | 'solids' | 'fronts' | 'stepped' | 'fireSite'>

/** One block to build on: where it stands, its ground, its street's look and its south-east corner. */
interface Site {
  b: Block
  base: number
  street: Street
  edge: { x: number; z: number }
}

function front(k: Skyline, site: Site, s: Solid, ground: boolean, crown: boolean, flat: boolean, java = false): void {
  k.solids.push(s)
  k.fronts.push({ s, street: site.street, ground, crown, flat, java, edge: site.edge })
}

/** Lawn crossed by two gravel paths, benches along them, flower beds, broadleaf trees and a few firs. */
function parkBlock(k: Skyline, { b, base }: Site, rnd: () => number): void {
  k.green.add(b.x, base, b.z, BLOCK, 0.1, BLOCK, 0x3f8f4a)
  k.green.add(b.x, base + 0.02, b.z, BLOCK, 0.1, 1.2, 0xcdbf9a)
  k.green.add(b.x, base + 0.02, b.z, 1.2, 0.1, BLOCK, 0xcdbf9a)
  for (const s of [-1, 1]) {
    k.roofs.kit.add(b.x + s * 3.5, base + 0.1, b.z + 0.95, 1.1, 0.25, 0.35, 0x6b4a32)
    k.roofs.kit.add(b.x + 0.95, base + 0.1, b.z + s * 3.5, 0.35, 0.25, 1.1, 0x6b4a32)
    k.green.add(b.x + s * 4.5, base + 0.05, b.z - s * 4.5, 1.6, 0.18, 1.0, pick([0xff6b9a, 0xffd319, 0xc84a8a, 0xf4f4f0], rnd))
  }
  for (let i = 0; i < 12; i++) {
    let px = b.x - BLOCK / 2 + 1 + rnd() * (BLOCK - 2)
    let pz = b.z - BLOCK / 2 + 1 + rnd() * (BLOCK - 2)
    // Off the paths.
    if (Math.abs(px - b.x) < 1.2) px += 2.4 * Math.sign(px - b.x || 1)
    if (Math.abs(pz - b.z) < 1.2) pz += 2.4 * Math.sign(pz - b.z || 1)
    k.trees.push({ x: px, y: base + 0.1, z: pz, s: 0.8 + rnd() * 0.7, fir: rnd() < 0.3 })
  }
  if (b.gx === -2 && b.gz === -3) k.green.add(b.x, base + 0.05, b.z, 6, 0.1, 4, 0x3a7fb5)
}

/**
 * Walk-ups along the block's north and south sides, each its own colour and its own roof: steep slate between granite
 * chimneys (Breton houses), tiles, or a flat roof with its water tower, terrace or skylights.
 */
function walkUps(k: Skyline, site: Site, rnd: () => number): void {
  const { b, base } = site
  for (const side of [-1, 1]) {
    for (let i = 0; i < 4; i++) {
      const h = 4 + rnd() * 5
      const x = b.x - BLOCK / 2 + 1.75 + i * 3.5
      const z = b.z + side * (BLOCK / 2 - 2.5)
      const roof = rnd()
      const wall = roof < 0.25 ? pick(GRANITE, rnd) : rnd() < 0.5 ? pick(BRICK, rnd) : pick(PAINT, rnd)
      k.brick.add(x, base, z, 3.3, h, 4.6, wall)
      k.lots.push({ x, z, w: 3.3, d: 4.6, h, base })
      front(k, site, { x, z, w: 3.3, d: 4.6, bottom: base, top: base + h }, true, true, false)
      walkUpRoof(k.roofs, x, base + h, z, 3.3, 4.6, roof, wall, rnd)
    }
  }
  if (!k.fireSite && Math.abs(b.gx + 1) <= 2 && b.gz >= 0) k.fireSite = { x: b.x - BLOCK / 2 + 1.75, z: b.z + BLOCK / 2 - 2.5, h: 6 }
}

/** A stepped limestone tower: two or three setbacks, sometimes a spire. */
function steppedTower(k: Skyline, site: Site, cap: number, y0: number, rnd: () => number): void {
  const { b, base } = site
  let w = 9 + rnd() * 3
  let y = base
  const steps = 2 + Math.floor(rnd() * 2)
  const total = Math.min(cap - 2, 10 + rnd() * 24)
  const color = pick(STONE, rnd)
  const lot = { x: b.x, z: b.z, w, d: w, h: total, base }
  k.lots.push(lot)
  k.stepped.add(lot)
  for (let s = 0; s < steps; s++) {
    const h = (total / steps) * (s === 0 ? 1.3 : 0.85)
    k.stone.add(b.x, y, b.z, w, h, w, color)
    front(k, site, { x: b.x, z: b.z, w, d: w, bottom: y, top: y + h }, s === 0, s === steps - 1, false)
    // Each setback is a terrace behind a parapet: gravel, or a garden on some of the lower ones.
    const garden = s < steps - 1 && rnd() < 0.3
    k.roofs.caps.add(b.x, y + h, b.z, w + 0.1, 0.12, w + 0.1, garden ? 0x4f8f4a : pick(MEMBRANE, rnd))
    parapet(k.roofs, b.x, y + h, b.z, w, w, color)
    y += h
    w *= 0.68
  }
  y += 0.12
  if (rnd() < 0.35 && y + 5 < y0 + cap + 4) {
    k.spires.push(new THREE.Matrix4().compose(new THREE.Vector3(b.x, y + 2.5, b.z), new THREE.Quaternion(), new THREE.Vector3(w * 0.8, 5, w * 0.8)))
  } else {
    machinery(k.roofs, b.x, y, b.z, w / 0.68, rnd)
  }
}

/** Glass towers, one or two per block. The JAVA tower is one of them: its rooftop frames are laid out with the signs. */
function glassTowers(k: Skyline, site: Site, cap: number, java: boolean, rnd: () => number): void {
  const { b, base } = site
  const two = !java && rnd() < 0.5
  for (const dx of two ? [-3.5, 3.5] : [0]) {
    const h = java ? cap - 1 : Math.min(cap, 12 + rnd() * 26)
    const w = two ? 5.5 : 9 + rnd() * 2
    const z = b.z + (two ? (rnd() - 0.5) * 4 : 0)
    k.glass.add(b.x + dx, base, z, w, h, w, pick(GLASS, rnd))
    k.lots.push({ x: b.x + dx, z, w, d: w, h, base })
    front(k, site, { x: b.x + dx, z, w, d: w, bottom: base, top: base + h }, true, true, true, java)
    k.roofs.caps.add(b.x + dx, base + h, z, w + 0.1, 0.12, w + 0.1, 0x3a3f44)
    parapet(k.roofs, b.x + dx, base + h, z, w, w, 0xc9d2d8)
    if (java) {
      // This draw keeps the rest of the skyline where it stood when the JAVA frames were placed here.
      rnd()
    } else if (!two && h > 24 && rnd() < 0.3) {
      k.roofs.helipads.push(shape(b.x + dx, base + h + 0.14, z, w * 0.7, 1, w * 0.7))
    } else {
      machinery(k.roofs, b.x + dx, base + h + 0.12, z, w, rnd)
    }
  }
}

/** One block of buildings: Mong Kok along the row south of the tower and the avenue east of it; Manhattan elsewhere. */
function buildBlock(k: Skyline, ctx: CityContext, b: Block): void {
  const { rnd, cx, cz, y0 } = ctx
  const site: Site = { b, base: y0 + 0.3, street: b.gz === 0 || b.gx === 0 ? 'dense' : 'sparse', edge: { x: b.x + BLOCK / 2, z: b.z + BLOCK / 2 } }
  if (park(b)) return parkBlock(k, site, rnd)
  // Close to the tower the neighbours stay low, so the view down to the street is clear; taller further out.
  const dist = Math.hypot(b.x - cx, b.z - cz)
  const cap = dist < 30 ? 12 : dist < 52 ? 22 : CEILING
  // The joke of the skyline stands west of the tower, seen past the office from the camera: a glass tower that says
  // JAVA in huge orange neon.
  const java = b.gx === -3 && b.gz === 0
  const kind = java ? 0.99 : rnd()
  if (kind < 0.4) walkUps(k, site, rnd)
  else if (kind < 0.7) steppedTower(k, site, cap, y0, rnd)
  else glassTowers(k, site, cap, java, rnd)
}

/** Every building of the city but the tower's own; `trees` gets the park's. */
export function buildings(ctx: CityContext, blocks: Block[], trees: Tree[], roofs: RoofKit, lit: (m: THREE.MeshBasicMaterial) => void): Buildings {
  const k: Skyline = {
    brick: new Boxes(),
    stone: new Boxes(),
    glass: new Boxes(),
    green: new Boxes(),
    spires: [],
    roofs,
    trees,
    lots: [],
    solids: [],
    fronts: [],
    stepped: new Set<Lot>(),
    fireSite: null,
  }
  for (const b of blocks) if (!own(b)) buildBlock(k, ctx, b)
  const { root, unit, cache } = ctx
  const facadeOf = (dark: number, light: number, cols: number, seed: number) => {
    const m = new THREE.MeshBasicMaterial({ map: facade(dark, light, cols, seed), color: 0xffffff })
    lit(m)
    return m
  }
  root.add(k.brick.mesh(unit, facadeOf(0x3a2a24, 0xf6d98a, 3, 5)))
  root.add(k.stone.mesh(unit, facadeOf(0x4a4436, 0xf6d98a, 4, 7)))
  root.add(k.glass.mesh(unit, facadeOf(0x223344, 0xbfe3f2, 6, 9)))
  root.add(k.green.mesh(unit, cache.lambert()))
  root.add(instanced(new THREE.ConeGeometry(0.5, 1, 6), new THREE.MeshLambertMaterial({ color: 0xc9d2d8 }), k.spires))
  return k
}
