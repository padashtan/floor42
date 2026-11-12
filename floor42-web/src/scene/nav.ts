// Grid navigation for agents: A* on a coarse grid, then path smoothing by line of sight.
// The floor is small (a few hundred cells), so a plain array-based A* is plenty.

export interface Point {
  x: number
  z: number
}

/** Axis-aligned obstacle on the floor, in world units. */
export interface Rect {
  x0: number
  z0: number
  x1: number
  z1: number
}

/**
 * Radius around a path's start and end where furniture may be crossed (getting up, sitting down), when that end stands
 * on furniture or its clearance: a chair, a bean bag.
 */
export const ESCAPE = 0.8

/** True when a figure of half width `margin` walking from `from` along `path` would touch `rect`. */
export function crosses(from: Point, path: readonly Point[], rect: Rect, margin = 0): boolean {
  let prev = from
  for (const p of path) {
    const steps = Math.max(1, Math.ceil(Math.hypot(p.x - prev.x, p.z - prev.z) / 0.05))
    for (let s = 0; s <= steps; s++) {
      const x = prev.x + ((p.x - prev.x) * s) / steps
      const z = prev.z + ((p.z - prev.z) * s) / steps
      if (x > rect.x0 - margin && x < rect.x1 + margin && z > rect.z0 - margin && z < rect.z1 + margin) return true
    }
    prev = p
  }
  return false
}

export class NavGrid {
  private readonly nx: number
  private readonly nz: number
  private readonly blocked: Uint8Array
  private readonly obstacles: Rect[]
  readonly cell: number
  readonly clearance: number

  /**
   * @param clearance kept free around every obstacle. With the default cell, a point anywhere on a free cell stays
   *     more than 0.15 (a figure's half width) away from furniture.
   */
  constructor(width: number, depth: number, obstacles: Rect[], cell = 0.25, clearance = 0.35) {
    this.cell = cell
    this.clearance = clearance
    this.nx = Math.ceil(width / cell)
    this.nz = Math.ceil(depth / cell)
    this.blocked = new Uint8Array(this.nx * this.nz)
    this.obstacles = []
    for (const r of obstacles) this.addObstacle(r)
  }

  /** Every footprint the grid has been given, props placed later included. */
  get footprints(): readonly Rect[] {
    return this.obstacles
  }

  /** Blocks a footprint from now on, with the grid's clearance around it: a prop placed after the grid was built. */
  addObstacle(r: Rect): void {
    this.obstacles.push(r)
    this.mark(r)
  }

  /**
   * Where a prop of footprint `rect` can stand: `rect` itself, or the nearest shift of it (a widening ring, a cell at a
   * time) that stays on the floor, overlaps no other obstacle and leaves every one of `keys` reachable from `hub`. Null
   * when no spot within `reach` does. The grid is left as it was.
   */
  findSpot(rect: Rect, hub: Point, keys: readonly Point[], reach = 3): Rect | null {
    const width = this.nx * this.cell
    const depth = this.nz * this.cell
    const fits = (r: Rect): boolean => {
      if (r.x0 < 0 || r.z0 < 0 || r.x1 > width || r.z1 > depth) return false
      if (this.obstacles.some((o) => r.x0 < o.x1 && r.x1 > o.x0 && r.z0 < o.z1 && r.z1 > o.z0)) return false
      const saved = this.blocked.slice()
      this.mark(r)
      const ok = keys.every((k) => this.path(hub, k).length > 0)
      this.blocked.set(saved)
      return ok
    }
    const rings = Math.round(reach / this.cell)
    for (let ring = 0; ring <= rings; ring++) {
      for (let dz = -ring; dz <= ring; dz++) {
        for (let dx = -ring; dx <= ring; dx++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) !== ring) continue
          const r = {
            x0: rect.x0 + dx * this.cell,
            z0: rect.z0 + dz * this.cell,
            x1: rect.x1 + dx * this.cell,
            z1: rect.z1 + dz * this.cell,
          }
          if (fits(r)) return r
        }
      }
    }
    return null
  }

  private mark(r: Rect): void {
    const c = this.clearance
    for (let iz = 0; iz < this.nz; iz++) {
      for (let ix = 0; ix < this.nx; ix++) {
        const p = this.center(ix, iz)
        if (p.x > r.x0 - c && p.x < r.x1 + c && p.z > r.z0 - c && p.z < r.z1 + c) this.blocked[iz * this.nx + ix] = 1
      }
    }
  }

  isBlocked(p: Point): boolean {
    const [ix, iz] = this.cellOf(p)
    return this.blockedCell(ix, iz)
  }

  /**
   * Path from one point to another, ending exactly on the target. Close to an end that stands on furniture (a chair),
   * obstacles are crossable, so an agent can sit down at a desk or get up from a chair wedged against a table; an end
   * on free floor gets no such leeway. Returns an empty array when the target cannot be reached.
   */
  path(from: Point, to: Point): Point[] {
    const ends = [from, to].filter((p) => this.isBlocked(p))
    const passable = (ix: number, iz: number): boolean => {
      if (!this.blockedCell(ix, iz)) return true
      if (ix < 0 || iz < 0 || ix >= this.nx || iz >= this.nz) return false
      const c = this.center(ix, iz)
      return ends.some((e) => Math.hypot(c.x - e.x, c.z - e.z) <= ESCAPE)
    }
    const [sx, sz] = this.cellOf(from)
    const [tx, tz] = this.cellOf(to)
    const start = sz * this.nx + sx
    const goal = tz * this.nx + tx
    if (start === goal) return [to]
    const came = this.search(start, goal, passable)
    return came ? this.smooth(this.rebuild(came, goal), from, to) : []
  }

  /**
   * The free cells joined to the one under `from` by free cells: how many, and whether a point lies on one. Doorways
   * count only as wide as the clearance leaves them; an end standing on furniture is not given the leeway of {@link path}.
   */
  region(from: Point): { size: number; has(p: Point): boolean } {
    const [sx, sz] = this.cellOf(from)
    const seen = new Uint8Array(this.nx * this.nz)
    let size = 0
    if (!this.blockedCell(sx, sz)) {
      const stack = [sz * this.nx + sx]
      seen[stack[0]] = 1
      const passable = (ix: number, iz: number): boolean => !this.blockedCell(ix, iz)
      while (stack.length > 0) {
        const c = stack.pop()!
        size++
        for (const [n] of this.moves(c, seen, passable)) {
          seen[n] = 1
          stack.push(n)
        }
      }
    }
    return {
      size,
      has: (p) => {
        const [ix, iz] = this.cellOf(p)
        return seen[iz * this.nx + ix] === 1
      },
    }
  }

  /** A* from cell `start` to cell `goal`: each reached cell's predecessor, or null when `goal` cannot be reached. */
  private search(start: number, goal: number, passable: (ix: number, iz: number) => boolean): Map<number, number> | null {
    const [tx, tz] = [goal % this.nx, Math.floor(goal / this.nx)]
    const n = this.nx * this.nz
    const g = new Float64Array(n).fill(Infinity)
    const closed = new Uint8Array(n)
    const came = new Map<number, number>()
    const open = new CellHeap()
    g[start] = 0
    open.push(this.h(start % this.nx, Math.floor(start / this.nx), tx, tz), start)
    while (open.size > 0) {
      const current = open.pop()
      if (closed[current]) continue
      if (current === goal) return came
      closed[current] = 1
      for (const [next, step] of this.moves(current, closed, passable)) {
        const tentative = g[current] + step
        if (tentative >= g[next]) continue
        came.set(next, current)
        g[next] = tentative
        open.push(tentative + this.h(next % this.nx, Math.floor(next / this.nx), tx, tz), next)
      }
    }
    return null
  }

  /** The cells one step from `current` that are open and passable, with the step's length, in a fixed order. */
  private *moves(current: number, closed: Uint8Array, passable: (ix: number, iz: number) => boolean): Generator<[number, number]> {
    const cx = current % this.nx
    const cz = Math.floor(current / this.nx)
    for (let dz = -1; dz <= 1; dz++) {
      for (let dx = -1; dx <= 1; dx++) {
        if (dx === 0 && dz === 0) continue
        const ix = cx + dx
        const iz = cz + dz
        const next = iz * this.nx + ix
        if (ix < 0 || iz < 0 || ix >= this.nx || iz >= this.nz) continue
        if (closed[next] || !passable(ix, iz)) continue
        // No corner cutting: a diagonal move needs both orthogonal neighbours free.
        const diagonal = dx !== 0 && dz !== 0
        if (diagonal && (!passable(cx + dx, cz) || !passable(cx, cz + dz))) continue
        yield [next, diagonal ? Math.SQRT2 : 1]
      }
    }
  }

  private rebuild(came: Map<number, number>, end: number): Point[] {
    const cells = [end]
    let c = end
    while (came.has(c)) {
      c = came.get(c)!
      cells.unshift(c)
    }
    return cells.map((n) => this.center(n % this.nx, Math.floor(n / this.nx)))
  }

  /** Drops intermediate points whenever a straight line stays on free cells. */
  private smooth(points: Point[], from: Point, to: Point): Point[] {
    const raw = [from, ...points.slice(1, -1), to]
    const out: Point[] = []
    let anchor = raw[0]
    for (let i = 1; i < raw.length; i++) {
      const next = raw[i + 1]
      if (next && this.lineOfSight(anchor, next)) continue
      out.push(raw[i])
      anchor = raw[i]
    }
    return out
  }

  private lineOfSight(a: Point, b: Point): boolean {
    const steps = Math.ceil(Math.hypot(b.x - a.x, b.z - a.z) / (this.cell / 3))
    for (let s = 1; s < steps; s++) {
      const p = { x: a.x + ((b.x - a.x) * s) / steps, z: a.z + ((b.z - a.z) * s) / steps }
      if (this.isBlocked(p)) return false
    }
    return true
  }

  private blockedCell(ix: number, iz: number): boolean {
    if (ix < 0 || iz < 0 || ix >= this.nx || iz >= this.nz) return true
    return this.blocked[iz * this.nx + ix] === 1
  }

  private cellOf(p: Point): [number, number] {
    const ix = Math.min(this.nx - 1, Math.max(0, Math.floor(p.x / this.cell)))
    const iz = Math.min(this.nz - 1, Math.max(0, Math.floor(p.z / this.cell)))
    return [ix, iz]
  }

  private center(ix: number, iz: number): Point {
    return { x: (ix + 0.5) * this.cell, z: (iz + 0.5) * this.cell }
  }

  private h(ax: number, az: number, bx: number, bz: number): number {
    return Math.hypot(bx - ax, bz - az)
  }
}

/**
 * The open cells of a search, by f score, ties broken by insertion order: the same path every run, in a fraction of the
 * time a scan of the open set took (the layout tests walk every pair of stations). A binary heap of [f, order, cell].
 */
class CellHeap {
  private readonly items: [number, number, number][] = []
  private order = 0

  get size(): number {
    return this.items.length
  }

  push(f: number, cell: number): void {
    const heap = this.items
    heap.push([f, this.order++, cell])
    let i = heap.length - 1
    while (i > 0) {
      const up = (i - 1) >> 1
      if (!less(heap[i], heap[up])) break
      ;[heap[i], heap[up]] = [heap[up], heap[i]]
      i = up
    }
  }

  /** Takes the cell of lowest f score out. */
  pop(): number {
    const heap = this.items
    const top = heap[0]
    const last = heap.pop()!
    if (heap.length > 0) {
      heap[0] = last
      this.sink()
    }
    return top[2]
  }

  private sink(): void {
    const heap = this.items
    let i = 0
    for (;;) {
      const l = 2 * i + 1
      const r = l + 1
      let m = i
      if (l < heap.length && less(heap[l], heap[m])) m = l
      if (r < heap.length && less(heap[r], heap[m])) m = r
      if (m === i) return
      ;[heap[i], heap[m]] = [heap[m], heap[i]]
      i = m
    }
  }
}

const less = (a: [number, number, number], b: [number, number, number]) => a[0] < b[0] || (a[0] === b[0] && a[1] < b[1])
