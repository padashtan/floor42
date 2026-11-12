import * as THREE from 'three'
import type { Point } from './nav'

/** A small deterministic generator: the office's little ones wander the same way each time, whatever Math.random is. */
export function seeded(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** A free point on the floor the nav grid can reach from `hub`, or null after a few tries. */
export function freeSpot(
  nav: NavLike,
  size: { width: number; depth: number },
  rng: () => number,
  hub: Point,
  ok: (p: Point) => boolean = () => true,
): Point | null {
  for (let i = 0; i < 40; i++) {
    const p = { x: 0.6 + rng() * (size.width - 1.2), z: 0.6 + rng() * (size.depth - 1.2) }
    if (!nav.isBlocked(p) && ok(p) && nav.path(hub, p).length > 0) return p
  }
  return null
}

/** What a wanderer does next: walk to `to`, then stay `rest` seconds (lying down, or up on a perch, if asked). */
export interface Plan {
  to: Point
  rest: number
  lie?: boolean
  /** After arriving, hop up to this place, stay there for the rest, then hop back down to `to`. */
  perch?: { at: THREE.Vector3 }
  /** Walk in a sprint (a faster gait). */
  run?: boolean
  /** Go by the agents' own corridors ({@link WanderOptions.corridorNav}): to a desk the cat hops onto. */
  corridor?: boolean
  /** What it does while it rests there: shown through {@link Wanderer.act} ('sleep' also lies it down). */
  act?: string
  /** Turn to look at this point once there. */
  face?: Point
  /** A sound or a word, at the start of the walk or on arrival. */
  say?: { tag: string; at: 'start' | 'arrive' }
}

/** Someone else on the floor: where it stands, how big it is (default 0.3) and how it moves (units per second). */
export interface Other extends Point {
  r?: number
  vx?: number
  vz?: number
  /** Where it is going next, when it is on a walk: pets keep out of the way of the whole road ahead. */
  route?: readonly Point[]
}

/** What the wanderer needs of a nav grid. */
export interface NavLike {
  path(from: Point, to: Point): Point[]
  isBlocked(p: Point): boolean
}

export interface WanderOptions {
  nav: NavLike
  /** The agents' grid, narrower: for the rare walk through a corridor, and out of one it stands in. */
  corridorNav?: NavLike
  root: THREE.Object3D
  speed: number
  seed: number
  /** Body radius: how far from others it keeps (default 0.25). */
  radius?: number
  /** Whether its body fits at this point of the floor (clear of furniture and walls); default: anywhere. */
  canStand?(p: Point): boolean
  /** The next thing to do, once the rest is over; null to rest a little longer. */
  plan(here: Point, rng: () => number, now: number): Plan | null
  /** Fills `out` with where others stand now (agents, the other little ones), returns how many: it waits rather than walk into them, and steps aside for them. */
  avoid(out: Other[]): number
  /** True while it should stay where it is on a perch (the way down is busy). */
  hold?(spot: Point): boolean
  /** The pose changed: lying (asleep, down) or standing. */
  onPose?(lying: boolean): void
  /** It says something: a word or a sound, by tag. */
  onSay?(tag: string): void
}

const HOP = 0.45
const TURN = 7
/** How much faster a sprint is than a walk. */
const SPRINT = 2.3
/** How fast it darts aside for someone coming. */
const DODGE = 3.2
const DEFAULT_R = 0.3
/** Slack kept past the sum of radii. */
const SLACK = 0.05

/** How far `p` is from the segment `a` to `b`. */
function distanceToSegment(p: Point, a: Point, b: Point): number {
  const ex = b.x - a.x
  const ez = b.z - a.z
  const len2 = ex * ex + ez * ez
  const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * ex + (p.z - a.z) * ez) / len2))
  return Math.hypot(p.x - (a.x + ex * t), p.z - (a.z + ez * t))
}

/** How far `p` is from the road that starts at `from` and follows `route`, as far as `reach` along it. */
function distanceToRoad(p: Point, from: Point, route: readonly Point[], reach: number): number {
  let best = Infinity
  let prev = from
  let walked = 0
  for (const next of route) {
    best = Math.min(best, distanceToSegment(p, prev, next))
    walked += Math.hypot(next.x - prev.x, next.z - prev.z)
    if (walked > reach) break
    prev = next
  }
  return best
}

/**
 * One small creature that wanders the floor: idles, turns, walks the nav grid's paths (so around desks and walls),
 * waits for someone in its way and darts aside from someone coming, lies down, sleeps or hops onto a perch when its plan
 * says so. The robot, the cat and the dog share it; each brings its plan. The caller does not call `update` under
 * reduced motion: it then stands still.
 */
export class Wanderer {
  walking = false
  running = false
  lying = false
  /** What it does now, from the plan it is resting in ('sleep', 'rub', 'sit', 'play'...), else null. */
  act: string | null = null
  private readonly o: WanderOptions
  private readonly rng: () => number
  private readonly radius: number
  private path: Point[] = []
  private plan: Plan | null = null
  private rest = 0.5
  private now = 0
  private patience = 0
  private yaw = 0
  private hop: { from: THREE.Vector3; to: THREE.Vector3; t: number; done: () => void } | null = null
  private perched: Point | null = null
  private face: Point | null = null
  private escape: { to: Point | null; at: number } | null = null
  /** How many times it searched for a place to flee to (for the tests). */
  searches = 0
  private readonly others: Other[] = []

  constructor(o: WanderOptions, start: Point) {
    this.o = o
    this.rng = seeded(o.seed)
    this.radius = o.radius ?? 0.25
    o.root.position.set(start.x, 0, start.z)
  }

  get position(): Point {
    return { x: this.o.root.position.x, z: this.o.root.position.z }
  }

  get sleeping(): boolean {
    return this.act === 'sleep'
  }

  /** Springs onto a perch at once, because someone is coming: it comes down to where it was when the way is clear. */
  leap(to: THREE.Vector3): void {
    if (this.hop || this.perched) return
    this.path = []
    this.plan = null
    this.face = null
    this.act = null
    this.running = false
    this.perched = this.position
    this.startHop(this.o.root.position.clone(), to, () => {
      this.rest = 4 + this.rng() * 4
      this.setLying(true)
    })
  }

  /** Drops what it was doing (an agent started to walk): it picks something else at once. A hop in the air finishes. */
  interrupt(): void {
    if (this.hop || this.perched) return
    this.path = []
    this.plan = null
    this.face = null
    this.act = null
    this.running = false
    this.rest = 0
    this.setLying(false)
  }

  update(dt: number): void {
    this.now += dt
    if (this.hop) return this.hopping(dt)
    if (this.dodge(dt)) return
    this.walking = this.path.length > 0
    if (this.walking) return this.walk(dt)
    this.running = false
    this.rest -= dt
    if (this.face) this.turnTo(Math.atan2(this.face.x - this.o.root.position.x, this.face.z - this.o.root.position.z), dt)
    if (this.rest > 0) return
    if (this.perched) {
      if (!this.o.hold?.(this.perched)) return this.down(this.perched)
      this.rest = 0.5
      return
    }
    this.face = null
    this.act = null
    this.setLying(false)
    const here = this.position
    const plan = this.o.plan(here, this.rng, this.now)
    if (!plan) {
      this.rest = 1
      return
    }
    let path = (plan.corridor ? this.o.corridorNav : this.o.nav)?.path(here, plan.to) ?? []
    // Standing where its own grid is blocked (in a corridor, after a hop down): out by the agents' grid.
    if (path.length === 0 && this.o.corridorNav && this.o.nav.isBlocked(here)) path = this.o.corridorNav.path(here, plan.to)
    if (path.length === 0 && Math.hypot(plan.to.x - here.x, plan.to.z - here.z) > 0.3) {
      this.rest = 0.5
      return
    }
    this.plan = plan
    this.path = path
    this.running = !!plan.run
    if (plan.say?.at === 'start') this.o.onSay?.(plan.say.tag)
    if (path.length === 0) this.arrived()
  }

  private gap(o: Other): number {
    return this.radius + (o.r ?? DEFAULT_R) + SLACK
  }

  /**
   * How far inside its gap someone is (negative: clear). Being on top of someone counts four times as much as being
   * in the way of a mover, which is looked for a moment ahead: it steps aside from a walker, never into a bystander.
   */
  private margin(p: Point, o: Other): number {
    const hard = (this.gap(o) - 0.06 - Math.hypot(p.x - o.x, p.z - o.z)) * 4
    if (o.route && o.route.length > 0) return Math.max(hard, this.gap(o) + 0.45 - distanceToRoad(p, o, o.route, 5))
    const v = Math.hypot(o.vx ?? 0, o.vz ?? 0)
    if (v < 0.2) return hard
    const look = Math.min(2, v * 0.8) / v
    const end = { x: o.x + (o.vx ?? 0) * look, z: o.z + (o.vz ?? 0) * look }
    return Math.max(hard, this.gap(o) + 0.25 - distanceToSegment(p, o, end))
  }

  /** Steps aside, fast, from someone on top of it or heading its way. True when it did. */
  private dodge(dt: number): boolean {
    const root = this.o.root
    if (this.sleeping || this.perched || root.position.y > 0.05) return false
    const n = this.o.avoid(this.others)
    const here = this.position
    const worst = (p: Point): number => {
      let m = -Infinity
      for (let i = 0; i < n; i++) m = Math.max(m, this.margin(p, this.others[i]))
      return m
    }
    const now = worst(here)
    if (now <= 0) {
      this.escape = null
      return false
    }
    const stand = this.o.canStand
    const hereOk = !stand || stand(here)
    const step = DODGE * dt
    // The nearest place clear of everyone's road, reached without brushing anyone: across a road if need be, before it fills.
    let best: Point | null = null
    // Looked for again only now and then, or once it is reached: not every frame while someone threatens.
    if (!this.escape || this.now - this.escape.at > 0.25 || (this.escape.to !== null && Math.hypot(this.escape.to.x - here.x, this.escape.to.z - here.z) < 0.1)) {
      this.searches++
      this.escape = { to: this.safePlace(here, worst, stand), at: this.now }
    }
    const target = this.escape.to
    if (target) {
      const len = Math.hypot(target.x - here.x, target.z - here.z)
      const f = Math.min(step, len) / len
      best = { x: here.x + (target.x - here.x) * f, z: here.z + (target.z - here.z) * f }
    }
    let score = now
    for (let k = 0; k < 16 && !best; k++) {
      const a = (k / 16) * Math.PI * 2
      const q = { x: here.x + Math.sin(a) * step, z: here.z + Math.cos(a) * step }
      if (stand ? !stand(q) && hereOk : this.o.nav.isBlocked(q)) continue
      const m = worst(q)
      if (m < score) {
        score = m
        best = q
      }
    }
    if (!best) return false
    this.path = []
    this.plan = null
    this.face = null
    this.act = null
    this.setLying(false)
    this.rest = 0.3
    this.walking = true
    this.running = true
    this.turnTo(Math.atan2(best.x - here.x, best.z - here.z), dt)
    root.position.x = best.x
    root.position.z = best.z
    return true
  }

  /** The nearest standing place clear of every road ahead, with a lane to it that touches nobody now. */
  private safePlace(here: Point, worst: (p: Point) => number, stand: ((p: Point) => boolean) | undefined): Point | null {
    const n = this.o.avoid(this.others)
    const touching = (p: Point): boolean => {
      for (let i = 0; i < n; i++) if (Math.hypot(p.x - this.others[i].x, p.z - this.others[i].z) < this.gap(this.others[i]) - 0.08) return true
      return false
    }
    for (let r = 0.4; r <= 3.2; r += 0.4) {
      for (let k = 0; k < 20; k++) {
        const a = (k / 20) * Math.PI * 2
        const p = { x: here.x + Math.sin(a) * r, z: here.z + Math.cos(a) * r }
        if ((stand && !stand(p)) || worst(p) > -0.05) continue
        let clear = true
        for (let d = 0.12; d < r && clear; d += 0.12) {
          const q = { x: here.x + Math.sin(a) * d, z: here.z + Math.cos(a) * d }
          clear = (!stand || stand(q)) && !touching(q)
        }
        if (clear) return p
      }
    }
    return null
  }

  private walk(dt: number): void {
    const root = this.o.root
    const next = this.path[0]
    const dx = next.x - root.position.x
    const dz = next.z - root.position.z
    const dist = Math.hypot(dx, dz)
    const heading = Math.atan2(dx, dz)
    this.turnTo(heading, dt)
    if (Math.abs(this.diff(heading)) > 0.6) return
    const step = Math.min(dist, dt * this.o.speed * (this.plan?.run ? SPRINT : 1))
    const x = root.position.x + (dx / (dist || 1)) * step
    const z = root.position.z + (dz / (dist || 1)) * step
    if (this.blockedBySomeone(x, z)) {
      this.patience += dt
      if (this.patience > 2) this.giveUp()
      return
    }
    // A step that would put its body into furniture or a wall is not taken (a smoothed path may graze a corner).
    const stand = this.o.canStand
    if (stand && !stand({ x, z }) && stand(this.position)) {
      this.giveUp()
      return
    }
    this.patience = 0
    root.position.x = x
    root.position.z = z
    if (dist - step < 0.02) {
      this.path.shift()
      if (this.path.length === 0) this.arrived()
    }
  }

  private giveUp(): void {
    this.patience = 0
    this.path = []
    this.plan = null
    this.running = false
    this.rest = 0.5
  }

  /** Someone within its gap of where the next step lands, and the step brings it nearer. */
  private blockedBySomeone(x: number, z: number): boolean {
    const here = this.o.root.position
    const n = this.o.avoid(this.others)
    for (let i = 0; i < n; i++) {
      const p = this.others[i]
      const after = Math.hypot(p.x - x, p.z - z)
      if (after < this.gap(p) - 0.02 && after < Math.hypot(p.x - here.x, p.z - here.z)) return true
    }
    return false
  }

  private arrived(): void {
    const plan = this.plan
    this.plan = null
    this.walking = false
    this.running = false
    this.rest = plan?.rest ?? 1
    this.face = plan?.face ?? null
    this.act = plan?.act ?? (plan?.lie ? 'lie' : null)
    if (plan?.say?.at === 'arrive') this.o.onSay?.(plan.say.tag)
    if (plan?.perch) {
      this.perched = { ...plan.to }
      this.startHop(this.o.root.position.clone(), plan.perch.at, () => this.setLying(true))
    } else if (plan?.lie || plan?.act === 'sleep') this.setLying(true)
  }

  private down(spot: Point): void {
    this.perched = null
    this.act = null
    this.setLying(false)
    this.startHop(this.o.root.position.clone(), new THREE.Vector3(spot.x, 0, spot.z), () => (this.rest = 1 + this.rng() * 2))
  }

  private startHop(from: THREE.Vector3, to: THREE.Vector3, done: () => void): void {
    this.hop = { from, to, t: 0, done }
    this.yaw = Math.atan2(to.x - from.x, to.z - from.z)
    this.o.root.rotation.y = this.yaw
  }

  private hopping(dt: number): void {
    const h = this.hop!
    h.t = Math.min(1, h.t + dt / HOP)
    this.o.root.position.lerpVectors(h.from, h.to, h.t)
    this.o.root.position.y += 0.25 * Math.sin(Math.PI * h.t)
    if (h.t < 1) return
    this.hop = null
    h.done()
  }

  private setLying(on: boolean): void {
    if (on === this.lying) return
    this.lying = on
    this.o.onPose?.(on)
  }

  private diff(target: number): number {
    return Math.atan2(Math.sin(target - this.yaw), Math.cos(target - this.yaw))
  }

  private turnTo(target: number, dt: number): void {
    const d = this.diff(target)
    this.yaw += Math.sign(d) * Math.min(Math.abs(d), dt * TURN)
    this.o.root.rotation.y = this.yaw
  }
}
