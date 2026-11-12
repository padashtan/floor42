import * as THREE from 'three'
import { BRICK, NEON, PAINT } from './colors'
import { Boxes, type CityContext, type Lot, pick } from './kit'

// Shops on the ground floor, on the faces the camera sees: an awning and a lit shop window. Their names, with the other
// neon signs, are laid out face by face in citySigns.ts.

interface Fronts {
  awnings: Boxes
  windows: Boxes
  doors: Boxes
  goods: Boxes
  tubes: Boxes
  tubeGlow: Boxes
}

/** A long face holds a row of shops, each about three units wide, each with its own window, door and awning. */
function shopRow(f: Fronts, l: Lot, south: boolean, rnd: () => number): void {
  const length = south ? l.w : l.d
  const count = Math.max(1, Math.round(length / 3.2))
  const each = length / count
  const wx = (a: number, b: number) => (south ? a : b)
  for (let k = 0; k < count; k++) {
    const along = -length / 2 + each * (k + 0.5)
    const fx = south ? l.x + along : l.x + l.w / 2 + 0.05
    const fz = south ? l.z + l.d / 2 + 0.05 : l.z + along
    const len = each * 0.86
    f.windows.add(fx, l.base, fz, wx(len, 0.1), 1.3, wx(0.1, len), pick([0xffe3a0, 0xbfe3f2, 0xffc8a0, 0xd8f0c0, 0xffd0e8], rnd))
    f.doors.add(fx + wx(len / 2 - 0.35, 0.02), l.base, fz + wx(0.02, len / 2 - 0.35), wx(0.5, 0.1), 1.25, wx(0.1, 0.5), 0x3a2a24)
    // Goods behind the glass: crates of fruit, books, cakes; a few colours read as a shop from above.
    for (let g = 0; g < 3; g++) {
      const off = -len / 2 + 0.4 + g * 0.55
      f.goods.add(fx + wx(off, 0.3), l.base, fz + wx(0.3, off), 0.4, 0.35, 0.4, pick([0xf2a93b, 0xc8463c, 0x6fe36a, 0xf4f4f0, 0xffd319], rnd))
    }
    f.awnings.add(fx + wx(0, 0.4), l.base + 1.5, fz + wx(0.4, 0), wx(len, 0.8), 0.12, wx(0.8, len), pick(PAINT.concat(BRICK), rnd))
  }
}

/** Neon tubes up the corner the camera sees and along the roof line, with their glow. */
function neonTubes(f: Fronts, l: Lot, c: string): void {
  const top = l.base + l.h
  const ex = l.x + l.w / 2 + 0.05
  const ez = l.z + l.d / 2 + 0.05
  f.tubes.add(ex, l.base + 2.2, ez, 0.1, l.h - 2.4, 0.1, c)
  f.tubes.add(l.x, top - 0.3, ez, l.w, 0.1, 0.1, c)
  f.tubes.add(ex, top - 0.3, l.z, 0.1, 0.1, l.d, c)
  f.tubeGlow.add(ex, l.base + 2.0, ez, 0.8, l.h - 2, 0.8, c)
  f.tubeGlow.add(l.x, top - 0.65, ez, l.w + 0.8, 0.8, 0.8, c)
  f.tubeGlow.add(ex, top - 0.65, l.z, 0.8, 0.8, l.d + 0.8, c)
}

/**
 * The shops of every lot, and neon tubes on some buildings near the tower (not on a stepped tower, whose roof line
 * steps back from its foot). `between` builds what is drawn after the tubes and before their glow (the signs).
 */
export function shops(ctx: CityContext, lots: Lot[], stepped: Set<Lot>, between: () => void): void {
  const { root, unit, cache, rnd, cx, cz } = ctx
  const f: Fronts = { awnings: new Boxes(), windows: new Boxes(), doors: new Boxes(), goods: new Boxes(), tubes: new Boxes(), tubeGlow: new Boxes() }
  for (const l of lots) {
    shopRow(f, l, true, rnd)
    shopRow(f, l, false, rnd)
    if (l.h > 8 && Math.hypot(l.x - cx, l.z - cz) < 60 && rnd() < 0.22 && !stepped.has(l)) neonTubes(f, l, pick(NEON, rnd))
  }
  root.add(f.windows.mesh(unit, cache.basic()))
  root.add(f.awnings.mesh(unit, cache.lambert()))
  root.add(f.doors.mesh(unit, cache.lambert()))
  root.add(f.goods.mesh(unit, cache.lambert()))
  root.add(f.tubes.mesh(unit, cache.basic()))
  between()
  const glow = new THREE.MeshBasicMaterial({ transparent: true, opacity: 0.16, blending: THREE.AdditiveBlending, depthWrite: false })
  root.add(f.tubeGlow.mesh(unit, glow))
}
