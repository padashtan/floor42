import { NavGrid, type Point, type Rect } from './nav'
import type { NavLike } from './wander'

/** Margin kept around furniture on the pets' own grid: for a body that has length, and so that pets keep out of the narrow corridors between the desks, which are the agents' roads. */
const CLEARANCE = 0.55
/** Finer than the agents' grid, for the doorways. */
const CELL = 0.125

const same = (a: Rect, b: Rect): boolean => Math.abs(a.x0 - b.x0) + Math.abs(a.z0 - b.z0) + Math.abs(a.x1 - b.x1) + Math.abs(a.z1 - b.z1) < 1e-6

/**
 * The pets' view of the floor: the agents' obstacles (props placed later included) on a grid with a wider clearance,
 * the edge of the floor counted as a wall, and their own baskets left out, since they step into those. Rebuilt when a
 * prop is placed.
 */
export class PetNav implements NavLike {
  private grid: NavGrid
  private count = -1
  private readonly baskets: Rect[] = []
  private rects: Rect[] = []
  private readonly width: number
  private readonly depth: number
  private readonly source: NavGrid

  constructor(source: NavGrid, width: number, depth: number) {
    this.source = source
    this.width = width
    this.depth = depth
    this.grid = new NavGrid(width, depth, [])
    this.sync()
  }

  /** A basket stands here: pets walk into it, others' paths still go round it. */
  addBasket(r: Rect): void {
    this.baskets.push(r)
    this.count = -1
    this.sync()
  }

  /** Brings the grid up to date with the agents' (props placed since). */
  sync(): void {
    const n = this.source.footprints.length
    if (n === this.count) return
    this.count = n
    this.rects = this.source.footprints.filter((r) => !this.baskets.some((b) => same(r, b)))
    const w = this.width
    const d = this.depth
    const edge: Rect[] = [
      { x0: -2, z0: -2, x1: 0, z1: d + 2 },
      { x0: w, z0: -2, x1: w + 2, z1: d + 2 },
      { x0: -2, z0: -2, x1: w + 2, z1: 0 },
      { x0: -2, z0: d, x1: w + 2, z1: d + 2 },
    ]
    this.grid = new NavGrid(w, d, [...this.rects, ...edge], CELL, CLEARANCE)
  }

  /** The floor the pets share: set to the biggest stretch of free cells, so that none is shut in a pocket. */
  private home: { has(p: Point): boolean } | null = null
  private homeFrom: Point | null = null

  /** Fixes the pets' common floor to what is joined to `from`. */
  setHome(from: Point): void {
    this.homeFrom = from
    this.home = null
  }

  /** Whether `p` is on the pets' common floor. */
  inHome(p: Point): boolean {
    this.sync()
    if (!this.homeFrom) return true
    this.home ??= this.grid.region(this.homeFrom)
    return this.home.has(p)
  }

  /** The biggest stretch of free cells (ties go to the one nearest `near`), as a point of it nearest `near`. */
  biggestRegion(near: Point): Point {
    this.sync()
    let best: { size: number; at: Point } | null = null
    const known: { has(p: Point): boolean }[] = []
    for (let x = 0.5; x < this.width; x += 0.5) {
      for (let z = 0.5; z < this.depth; z += 0.5) {
        const p = { x, z }
        if (this.grid.isBlocked(p) || known.some((k) => k.has(p))) continue
        const region = this.grid.region(p)
        known.push(region)
        // The point of this region nearest `near`.
        let at = p
        let d = Infinity
        for (let a = 0.5; a < this.width; a += 0.5) {
          for (let b = 0.5; b < this.depth; b += 0.5) {
            const q = { x: a, z: b }
            const dq = Math.hypot(a - near.x, b - near.z)
            if (dq < d && region.has(q) && this.clearOf(q, 0.5)) {
              d = dq
              at = q
            }
          }
        }
        if (!best || region.size > best.size) best = { size: region.size, at }
      }
    }
    return best?.at ?? near
  }

  path(from: Point, to: Point): Point[] {
    this.sync()
    return this.grid.path(from, to)
  }

  isBlocked(p: Point): boolean {
    this.sync()
    return this.grid.isBlocked(p)
  }

  /** Whether a body of radius `r` standing at `p` touches no furniture, no wall and not the edge of the floor. */
  clearOf(p: Point, r: number): boolean {
    this.sync()
    if (p.x < r || p.z < r || p.x > this.width - r || p.z > this.depth - r) return false
    for (const o of this.rects) {
      const dx = Math.max(o.x0 - p.x, 0, p.x - o.x1)
      const dz = Math.max(o.z0 - p.z, 0, p.z - o.z1)
      if (dx * dx + dz * dz < r * r) return false
    }
    return true
  }
}
