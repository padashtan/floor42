// Where the neon signs of the city go, after the streets of Mong Kok and Manhattan. Pure placement, no three.js: a sign
// is laid out on one facade face, in the face's own coordinates (u along the face from its middle, y up, `out` away
// from the wall), and city.ts turns it into meshes. The rules, taken from real streets:
// - a wall sign lies flat on the wall, inside the face, a few centimetres proud of it, never wider than the face;
// - a blade sign stands perpendicular to the wall on a bracket, its letters stacked one above the other (Nathan Road,
//   Broadway hotels); on a busy street blades line up in columns and stack up several floors;
// - a rooftop sign stands on a steel frame set back from the roof's edge (the Pepsi-Cola sign): the only sign above
//   the roof line;
// - letters are sized from a storey's height and shrink to fit the face, so a sign is never longer than its wall.

/** How busy the street is: Mong Kok (blades everywhere, stacked) or Manhattan (a few signs, well spaced). */
export type Street = 'dense' | 'sparse'

/** One facade face, seen from the street: its width, its foot and its roof line. */
export interface Face {
  width: number
  bottom: number
  top: number
  /** One storey's height, as the facade's windows draw it. */
  floor: number
  /** How many shop fronts its ground floor holds, each under its awning; 0 for a face above a terrace. */
  shops: number
  street: Street
  /** A sign on a frame on the roof above this face, when the roof is flat. */
  roof?: { text: string; color?: string }
  /** A company's name near the top of the face. */
  company?: string
}

export type SignKind = 'fascia' | 'flush' | 'blade' | 'rooftop'

export interface Sign {
  kind: SignKind
  text: string
  color: string
  /** Centre along the face, from its middle. */
  u: number
  /** Centre height. */
  y: number
  /** What the sign covers on the face (along, up): the plate for a flat sign, the blade's thickness for a blade. */
  w: number
  h: number
  /** Letter height. */
  letter: number
  /** How far the plate's back stands from the wall: 0 when flat on it, the bracket's gap for a blade, negative when set back on the roof. */
  back: number
  /** The plate's depth away from the wall: its thickness for a flat sign, the blade's width for a blade. */
  depth: number
  /** The rooftop frame's legs under the plate; 0 for the others. */
  legs: number
}

/** The words and colours to draw signs from. */
export interface Words {
  shops: string[]
  companies: string[]
  colors: string[]
}

/** A letter's advance and a plate's margins, in letter heights: the sign textures are drawn with the same metrics. */
export const ADVANCE = 0.62
const PAD = 0.9
/** A one-line plate. */
export const lineWidth = (text: string, letter: number) => (text.length * ADVANCE + PAD) * letter
export const lineHeight = (letter: number) => 1.5 * letter
/** A blade: its letters stacked top to bottom. */
export const columnWidth = (letter: number) => 1.5 * letter
export const columnHeight = (text: string, letter: number) => (text.length + 0.6) * letter

/** A flat plate's thickness, a blade's thickness, and the bracket's gap between wall and blade. */
export const PLATE = 0.06
export const BLADE = 0.08
export const GAP = 0.15
/** The space kept between two signs on a face. */
export const CLEAR = 0.15
/** How far a rooftop frame stands back from the roof's edge, inside the parapet. */
export const ROOF_INSET = 0.6
/** The awning over a shop front ends this high above the street; the shop's name goes just above it. */
export const AWNING_TOP = 1.62
/** Where the ground floor's shop band ends: other signs start above it. */
export const SHOP_TOP = 2.4

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))
const EPS = 1e-6

/** The margin kept free along a face's sides. */
export const edgeOf = (f: Face) => Math.max(0.12, f.width * 0.03)

/** A sign lies inside its face: within the face's sides, between its foot and its roof line (a rooftop sign above). */
export function inFace(f: Face, s: Sign): boolean {
  if (s.kind === 'rooftop') {
    return Math.abs(s.u) + s.w / 2 <= f.width / 2 - ROOF_INSET + EPS && Math.abs(s.y - s.h / 2 - (f.top + s.legs)) < EPS && s.back < 0
  }
  return Math.abs(s.u) + s.w / 2 <= f.width / 2 - edgeOf(f) + EPS && s.y - s.h / 2 >= f.bottom - EPS && s.y + s.h / 2 <= f.top + EPS
}

/** Two signs come closer than `clear` on the face. */
export function overlaps(a: Sign, b: Sign, clear = 0): boolean {
  return (
    Math.abs(a.u - b.u) < (a.w + b.w) / 2 + clear - EPS && Math.abs(a.y - b.y) < (a.h + b.h) / 2 + clear - EPS
  )
}

/** The two arms holding a blade, from the wall to the blade: their height, and how far out they reach. */
export function brackets(s: Sign): { y: number; from: number; to: number }[] {
  if (s.kind !== 'blade') return []
  const inset = Math.min(0.25, s.h * 0.15)
  return [s.y + s.h / 2 - inset, s.y - s.h / 2 + inset].map((y) => ({ y, from: 0, to: s.back + 0.05 }))
}

/** Where the legs of a rooftop frame stand, along the face. */
export function legsOf(s: Sign): number[] {
  if (s.kind !== 'rooftop') return []
  const end = s.w / 2 - 0.15
  return s.w > 4 ? [-end, 0, end].map((d) => s.u + d) : [-end, end].map((d) => s.u + d)
}

/** The light a sign throws on its wall, kept on the face: a little larger than the sign. None for a rooftop sign. */
export function glowRect(f: Face, s: Sign): { u0: number; u1: number; y0: number; y1: number } | null {
  if (s.kind === 'rooftop') return null
  const pad = Math.min(0.3, s.letter * 0.6)
  const half = s.kind === 'blade' ? s.depth * 0.6 + pad : s.w / 2 + pad
  return {
    u0: Math.max(-f.width / 2, s.u - half),
    u1: Math.min(f.width / 2, s.u + half),
    y0: Math.max(f.bottom, s.y - s.h / 2 - pad),
    y1: Math.min(f.top, s.y + s.h / 2 + pad),
  }
}

/** What the parts of one face's plan share: the face, the room it leaves, the draws, and the signs kept so far. */
interface Plan {
  f: Face
  /** Half the face's width, inside its side margins. */
  half: number
  height: number
  dense: boolean
  /** The band the blades and wall signs may take: above the shop band, under the roof line. */
  low: number
  high: number
  rnd: () => number
  words: Words
  signs: Sign[]
}

const pick = <T>(p: Plan, list: T[]) => list[Math.floor(p.rnd() * list.length)]

/** Keeps a candidate that stays inside the face and clear of every sign kept so far. */
function add(p: Plan, s: Sign): void {
  if (inFace(p.f, s) && p.signs.every((o) => !overlaps(o, s, CLEAR))) p.signs.push(s)
}

/** A one-line plate flat on the wall, its foot at `bottom`. */
function flat(p: Plan, kind: SignKind, text: string, u: number, bottom: number, letter: number, color = pick(p, p.words.colors)): Sign {
  const h = lineHeight(letter)
  return { kind, text, color, u, y: bottom + h / 2, w: lineWidth(text, letter), h, letter, back: 0, depth: PLATE, legs: 0 }
}

/**
 * Lays out the signs of one face: the shops' names over their awnings, blades on brackets, a few wall signs on the
 * floor lines, a company's name near the top and a framed sign on the roof. Every candidate that would leave the face
 * or come too close to another sign is dropped, so what comes back always holds the invariants above.
 */
export function planFace(f: Face, rnd: () => number, words: Words): Sign[] {
  const half = f.width / 2 - edgeOf(f)
  const height = f.top - f.bottom
  if (half <= 0.2 || height <= 0.5) return []
  const low = f.bottom + (f.shops > 0 ? SHOP_TOP : 0.3)
  const p: Plan = { f, half, height, dense: f.street === 'dense', low, high: f.top - 0.3, rnd, words, signs: [] }
  shopNames(p)
  companyName(p)
  blades(p)
  wallSigns(p)
  roofSign(p)
  return p.signs
}

/** The shops' names, one per shop front, just above its awning. */
function shopNames(p: Plan): void {
  const { f } = p
  if (f.shops <= 0 || p.height < SHOP_TOP + 0.3) return
  const slot = f.width / f.shops
  for (let k = 0; k < f.shops; k++) {
    if (p.rnd() >= (p.dense ? 0.95 : 0.55)) continue
    const u = -f.width / 2 + slot * (k + 0.5)
    const room = Math.min(slot * 0.86, 2 * (p.half - Math.abs(u)))
    const text = pick(p, p.words.shops)
    const letter = Math.min(0.36, room / lineWidth(text, 1))
    if (letter >= 0.16) add(p, flat(p, 'fascia', text, u, f.bottom + AWNING_TOP + 0.06, letter))
  }
}

/** A company's name across the top of the face. */
function companyName(p: Plan): void {
  const { f } = p
  if (!f.company || p.height <= 8) return
  const letter = Math.min(clamp(f.floor * 1.1, 0.5, 1.6), (2 * p.half * 0.85) / lineWidth(f.company, 1))
  if (letter >= 0.35) add(p, flat(p, 'flush', f.company, 0, f.top - Math.max(0.4, f.floor * 0.6) - lineHeight(letter), letter))
}

/** Blades on brackets, in columns along the face: stacked up several floors on a busy street, one now and then elsewhere. */
function blades(p: Plan): void {
  const most = Math.min(0.8, p.f.width * 0.14)
  const base = clamp(p.f.floor * 0.8, 0.26, most)
  if (most < 0.26 || p.high - p.low <= 1.5) return
  const spacing = Math.max(1.1, columnWidth(base) + GAP + 0.3)
  const columns = Math.max(1, Math.floor((2 * p.half) / spacing))
  const step = (2 * p.half) / columns
  for (let c = 0; c < columns; c++) {
    if (p.dense ? p.rnd() >= 0.85 : p.rnd() >= 0.12) continue
    bladeColumn(p, -p.half + step * (c + 0.5), most, base)
  }
}

/** One column of blades at `u`, stacked from a random foot up while they fit under the roof line. */
function bladeColumn(p: Plan, u: number, most: number, base: number): void {
  const { f, rnd } = p
  let foot = p.low + rnd() * f.floor
  for (let n = 0; n < (p.dense ? 3 : 1); n++) {
    const text = pick(p, p.words.shops)
    const letter = Math.min(most, base * (0.85 + rnd() * 0.3))
    const h = columnHeight(text, letter)
    if (foot + h > p.high) break
    const color = pick(p, p.words.colors)
    add(p, { kind: 'blade', text, color, u, y: foot + h / 2, w: BLADE, h, letter, back: GAP, depth: columnWidth(letter), legs: 0 })
    foot += h + 0.3 + rnd() * f.floor * 1.5
  }
}

/** Wall signs on the floor lines, between the blades. */
function wallSigns(p: Plan): void {
  const { f, rnd, words } = p
  const tries = p.dense ? 3 : rnd() < 0.4 ? 1 : 0
  for (let k = 0; k < tries; k++) {
    const text = rnd() < 0.5 ? pick(p, words.companies) : pick(p, words.shops)
    const letter = Math.min(clamp(f.floor * 0.7, 0.25, 1.0) * (0.8 + rnd() * 0.4), (2 * p.half * 0.7) / lineWidth(text, 1))
    const floors = Math.floor((p.high - p.low) / f.floor)
    if (letter < 0.2 || floors < 1) continue
    const bottom = p.low + Math.floor(rnd() * floors) * f.floor
    const w = lineWidth(text, letter)
    const u = (rnd() * 2 - 1) * Math.max(0, p.half - w / 2)
    add(p, flat(p, 'flush', text, u, bottom, letter))
  }
}

/** A framed sign on the roof, set back from the edge, never as wide as the roof so the frames of two faces never cross. */
function roofSign(p: Plan): void {
  const { f } = p
  if (!f.roof) return
  const room = f.width - 2 * ROOF_INSET - 0.3
  const letter = Math.min(2.2, clamp(f.width * 0.1, 0.6, 2.2) * 1.6, room / lineWidth(f.roof.text, 1))
  if (letter < 0.4) return
  const legs = 0.8 + p.rnd() * 0.6
  const h = lineHeight(letter)
  const color = f.roof.color ?? pick(p, p.words.colors)
  const w = lineWidth(f.roof.text, letter)
  p.signs.push({ kind: 'rooftop', text: f.roof.text, color, u: 0, y: f.top + legs + h / 2, w, h, letter, back: -ROOF_INSET, depth: PLATE, legs })
}

/** A building's box: its footprint's centre and size, its foot and its roof. */
export interface Solid {
  x: number
  z: number
  w: number
  d: number
  bottom: number
  top: number
}

/** The faces the camera sees: south (+z) and east (+x). */
export type Side = 'south' | 'east'

/** The room in front of a face that its signs may take, `reach` deep. */
export function frontOf(s: Solid, side: Side, reach: number): Solid {
  return side === 'south'
    ? { x: s.x, z: s.z + s.d / 2 + reach / 2, w: s.w, d: reach, bottom: s.bottom, top: s.top }
    : { x: s.x + s.w / 2 + reach / 2, z: s.z, w: reach, d: s.d, bottom: s.bottom, top: s.top }
}

/** No other building stands in that room: a face pressed against its neighbour carries no sign. */
export function clear(room: Solid, solids: Solid[]): boolean {
  const apart = (a0: number, a1: number, b0: number, b1: number) => a1 <= b0 + EPS || b1 <= a0 + EPS
  return solids.every(
    (o) =>
      apart(room.x - room.w / 2, room.x + room.w / 2, o.x - o.w / 2, o.x + o.w / 2) ||
      apart(room.z - room.d / 2, room.z + room.d / 2, o.z - o.d / 2, o.z + o.d / 2) ||
      apart(room.bottom, room.top, o.bottom, o.top),
  )
}
