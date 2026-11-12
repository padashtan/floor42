import * as THREE from 'three'
import type { Bubble } from './petBubbles'
import type { Figure } from './figures'
import { DESKS, FLOOR, LANDING, PLANTS, around } from './floorPlan'
import { box, glow, markTarget, mat } from './kit'
import type { NavGrid, Point, Rect } from './nav'
import { P } from './palette'
import { animateBeast, type Beast, buildBasket, buildCat, buildDog, shadow } from './petModels'
import { PetNav } from './petNav'
import { freeSpot, type Other, type Plan, Wanderer } from './wander'

/** What the office's little ones need of the office: the nav grid (props placed later included) and the agents. */
export interface MascotWorld {
  nav: NavGrid
  figures: ReadonlyMap<string, Figure>
  /** Stands a prop on the floor where it blocks nobody's way, within `reach` of its footprint; false when it fits nowhere. */
  placeProp(prop: THREE.Object3D, footprint: Rect, reach?: number): boolean
  /** The agents in the office standing or sitting still between tools: the ones a pet may keep company. */
  idleFigures(): Figure[]
}

/** What the pets say, in the screen's language (the scene itself knows no catalogue). */
export interface PetWords {
  meow: string
  bark: string
  sleep: string
}

const SIZE = { width: FLOOR.width, depth: FLOOR.depth }
/** Desk top, as workDesk builds it. */
const DESK_TOP = 0.79
/** An agent's body radius, for the room a pet keeps from it. */
const AGENT_R = 0.3
const ROBOT_R = 0.36
/** Seconds a bubble stays up. */
const SAY = 1.8

/** An invisible box to point at: the robot is small to hit with the pointer otherwise. */
function hitBox(parent: THREE.Object3D, size: [number, number, number], z = 0): void {
  const m = new THREE.Mesh(new THREE.BoxGeometry(...size), new THREE.MeshBasicMaterial({ visible: false }))
  m.position.set(0, size[1] / 2, z)
  m.userData.noOutline = true
  parent.add(m)
}

/** The robot: a small boxy character of steel and chrome, with a screen face. Built after the theme, so it wears it. */
function buildRobot(): { root: THREE.Group; head: THREE.Group; arms: THREE.Group[]; lamp: THREE.Mesh } {
  const root = new THREE.Group()
  markTarget(root, { kind: 'robot', id: 'terminal' })
  const body = mat(P.orange, { metal: true })
  const dark = mat(P.steelDark)
  for (const x of [-0.12, 0.12]) box(root, [0.12, 0.14, 0.3], [x, 0, 0], dark)
  box(root, [0.34, 0.28, 0.24], [0, 0.14, 0], body)
  box(root, [0.36, 0.04, 0.26], [0, 0.1, 0], mat(P.hazard))
  box(root, [0.18, 0.1, 0.02], [0, 0.22, 0.125], glow(P.cyanDim), false)
  box(root, [0.12, 0.03, 0.02], [0, 0.17, 0.125], glow(P.amber), false)
  const head = new THREE.Group()
  head.position.set(0, 0.44, 0)
  box(head, [0.3, 0.22, 0.24], [0, 0, 0], mat(P.steelDark, { metal: true }))
  box(head, [0.22, 0.1, 0.02], [0, 0.07, 0.125], mat(P.screenBg), false)
  for (const x of [-0.06, 0.06]) box(head, [0.07, 0.07, 0.02], [x, 0.085, 0.135], glow(P.cyan), false)
  box(head, [0.02, 0.1, 0.02], [0, 0.22, 0], mat(P.hazard))
  const lamp = box(head, [0.08, 0.08, 0.08], [0, 0.32, 0], glow(P.amber), false)
  root.add(head)
  const arms = [-1, 1].map((side) => {
    const arm = new THREE.Group()
    arm.position.set(side * 0.22, 0.38, 0)
    box(arm, [0.06, 0.22, 0.06], [0, -0.22, 0], dark)
    root.add(arm)
    return arm
  })
  root.scale.setScalar(1.9)
  shadow(root, 0.3)
  hitBox(root, [0.4, 0.72, 0.34])
  return { root, head, arms, lamp }
}

type PetId = 'cat' | 'dog'

/** One pet: its model, its walker, its basket and its small schedule. */
interface Pet {
  id: PetId
  beast: Beast
  walker: Wanderer
  /** Body radius: how much floor it needs. */
  r: number
  seed: number
  basket: Rect | null
  basketSpot: Point | null
  /** The last time it went to sleep (its clock), and how long it waits before the next nap. */
  lastNap: number
  napEvery: number
  /** The next time it speaks up on its own. */
  nextTalk: number
  /** The agent it keeps company, if any. */
  partner: string | null
  carrying: boolean
  chase: boolean
  /** Hurrying out of the agents' corridors because someone is on the move. */
  fleeing: boolean
  speech: { tag: string; until: number } | null
}

/** Where a pet may look for a basket: its own wall, nearest first. */
const BASKET_ANCHOR: Record<PetId, Point> = { cat: { x: 0.4, z: 12.4 }, dog: { x: 12.6, z: 20.8 } }
const BASKET_SIZE: Record<PetId, number> = { cat: 0.6, dog: 0.85 }

/**
 * The office's little ones: a robot that opens the director's terminal when clicked, a cat and a dog. They wander the
 * floor on the nav grid's paths, around furniture and agents ({@link Wanderer}); the pets keep to cells with room for
 * their bodies, step aside for agents, run now and then, speak, keep an idle agent company, and sleep in a basket on
 * a free cell by a wall. They stand still under reduced motion (the scene then does not call {@link update}). All of
 * them hang in the scene graph, which frees what they drew. The scene builds them after the decade's palette is in force.
 */
export class Mascots {
  readonly robot: THREE.Group
  readonly cat: THREE.Group
  readonly dog: THREE.Group
  /** Called when a pet makes a sound ('meow' or 'bark'), for the optional synthesised voice. */
  onVoice: ((tag: string) => void) | null = null
  words: PetWords = { meow: 'Meow', bark: 'Woof', sleep: 'z' }
  private readonly walkers: Wanderer[] = []
  private readonly animators: ((t: number) => void)[] = []
  private readonly pets: Pet[] = []
  private readonly petNav: PetNav
  private readonly baskets: THREE.Object3D[] = []
  private readonly world: MascotWorld
  private readonly roots: THREE.Object3D[]
  private readonly radii = new Map<THREE.Object3D, number>()
  private readonly last = new Map<THREE.Object3D, Point>()
  private readonly velocity = new Map<THREE.Object3D, Point>()
  private clock = 0
  private checked = 0
  private idle: Figure[] = []
  private hub: Point = { x: 6, z: 12.4 }
  private readonly seed: number

  constructor(parent: THREE.Object3D, world: MascotWorld, seed = 0) {
    this.world = world
    this.seed = seed
    this.petNav = new PetNav(world.nav, SIZE.width, SIZE.depth)
    const robot = buildRobot()
    const cat = buildCat()
    const dog = buildDog()
    this.robot = robot.root
    this.cat = cat.root
    this.dog = dog.root
    parent.add(this.robot, this.cat, this.dog)
    this.roots = [this.robot, this.cat, this.dog]
    this.radii.set(this.robot, ROBOT_R)
    this.radii.set(this.cat, 0.2)
    this.radii.set(this.dog, 0.28)
    this.hub = this.findHub()
    const rover = new Wanderer(
      {
        nav: world.nav,
        root: this.robot,
        speed: 0.9,
        seed: 11 + seed,
        radius: ROBOT_R,
        plan: (here, rng) => this.stroll(here, rng, 1.5, 5, world.nav),
        avoid: (out) => this.near(this.robot, out),
      },
      this.startSpot(11 + seed, world.nav, 0.3, this.robot),
    )
    this.walkers.push(rover)
    this.pets.push(this.makePet('cat', cat, 23 + seed, 1.1, 0.2), this.makePet('dog', dog, 37 + seed, 1.5, 0.28))
    this.placeBaskets()
    this.animators.push((t) => this.animateRobot(robot, rover, t))
    for (const p of this.pets) this.animators.push((t) => this.animatePet(p, t))
    this.syncBaskets()
  }

  /** The pet's words and sounds, set to the screen's language. */
  setWords(words: PetWords): void {
    this.words = words
  }

  /** One frame. Not called under reduced motion. */
  update(t: number, dt: number): void {
    this.clock += dt
    this.track(dt)
    this.keepCompany()
    this.retreat()
    for (const w of this.walkers) w.update(dt)
    for (const p of this.pets) {
      p.walker.update(dt)
      this.talk(p)
    }
    for (const a of this.animators) a(t)
  }

  /** The bubbles to show this frame: a word after a meow or a bark, a "z" over a sleeper. */
  speech(): Bubble[] {
    const out: Bubble[] = []
    for (const p of this.pets) {
      const lift = p.id === 'cat' ? 0.6 : 0.78
      if (p.walker.sleeping) out.push({ id: p.id, root: p.beast.root, lift, text: this.words.sleep })
      else if (p.speech && p.speech.until > this.clock) out.push({ id: p.id, root: p.beast.root, lift, text: p.speech.tag === 'meow' ? this.words.meow : this.words.bark })
    }
    return out
  }

  /** What each pet is up to, for the browser tests (data only): 'cat:sleep;dog:run'. */
  states(): string {
    return this.pets.map((p) => `${p.id}:${p.walker.act ?? (p.walker.running ? 'run' : p.walker.walking ? 'walk' : 'still')}`).join(';')
  }

  /** The baskets' footprints, for the tests. */
  get basketRects(): Rect[] {
    return this.pets.flatMap((p) => (p.basket ? [p.basket] : []))
  }

  /** Each pet's body radius, by object, for the tests. */
  radiusOf(o: THREE.Object3D): number {
    return this.radii.get(o) ?? 0.25
  }

  private makePet(id: PetId, beast: Beast, seed: number, speed: number, r: number): Pet {
    const pet: Pet = {
      id,
      beast,
      walker: undefined as unknown as Wanderer,
      r,
      seed,
      basket: null,
      basketSpot: null,
      lastNap: 0,
      napEvery: 25 + (seed % 7) * 4,
      nextTalk: 8 + (seed % 5) * 3,
      partner: null,
      carrying: false,
      chase: false,
      fleeing: false,
      speech: null,
    }
    pet.walker = new Wanderer(
      {
        nav: this.petNav,
        corridorNav: this.world.nav,
        root: beast.root,
        speed,
        seed,
        radius: r,
        canStand: (p) => this.petNav.clearOf(p, r),
        hold: (spot) => this.trafficBusy() || this.crowded(spot, r + 0.5, beast.root),
        plan: (here, rng, now) => this.petPlan(pet, here, rng, now),
        avoid: (out) => this.near(beast.root, out),
        onSay: (tag) => this.say(pet, tag),
      },
      this.startSpot(seed, this.petNav, r, beast.root),
    )
    return pet
  }

  private say(pet: Pet, tag: string): void {
    pet.speech = { tag, until: this.clock + SAY }
    this.onVoice?.(tag)
  }

  /** Now and then a pet speaks up on its own, when it is awake. */
  private talk(p: Pet): void {
    if (this.clock < p.nextTalk || p.walker.sleeping) return
    p.nextTalk = this.clock + 25 + ((p.seed * 7 + Math.floor(this.clock)) % 40)
    this.say(p, p.id === 'cat' ? 'meow' : 'bark')
  }

  /** Where the agents and the other little ones stand and move, written into `out` (reused: no allocation per frame). */
  private near(me: THREE.Object3D, out: Other[]): number {
    let n = 0
    for (const f of this.world.figures.values()) {
      if (!f.root.visible) continue
      n = this.put(out, n, f.root, AGENT_R, f.route)
    }
    for (const r of this.roots) {
      // A pet up on a desk is out of the floor's way.
      if (r === me || r.position.y > 0.3) continue
      n = this.put(out, n, r, this.radii.get(r) ?? 0.3)
    }
    return n
  }

  private put(out: Other[], n: number, o: THREE.Object3D, r: number, route?: readonly Point[]): number {
    const p = out[n] ?? (out[n] = { x: 0, z: 0 })
    const v = this.velocity.get(o)
    p.x = o.position.x
    p.z = o.position.z
    p.r = r
    p.vx = v?.x ?? 0
    p.vz = v?.z ?? 0
    p.route = route
    return n + 1
  }

  /** Someone, an agent or another little one, stands or walks within `reach` of `spot`. */
  private crowded(spot: Point, reach: number, me: THREE.Object3D): boolean {
    for (const f of this.world.figures.values()) {
      if (f.root.visible && Math.hypot(f.root.position.x - spot.x, f.root.position.z - spot.z) < reach + AGENT_R) return true
    }
    return this.roots.some((o) => o !== me && o.position.y < 0.3 && Math.hypot(o.position.x - spot.x, o.position.z - spot.z) < reach + (this.radii.get(o) ?? 0.3))
  }

  /** How fast everyone moves, from where they stood a frame ago. */
  private track(dt: number): void {
    if (dt <= 0) return
    const watch = (o: THREE.Object3D): void => {
      const before = this.last.get(o)
      const v = this.velocity.get(o) ?? { x: 0, z: 0 }
      if (before && o.visible) {
        v.x = (o.position.x - before.x) / dt
        v.z = (o.position.z - before.z) / dt
      } else {
        v.x = 0
        v.z = 0
      }
      this.velocity.set(o, v)
      this.last.set(o, { x: o.position.x, z: o.position.z })
    }
    for (const f of this.world.figures.values()) watch(f.root)
    for (const r of this.roots) watch(r)
  }

  /** A pet that keeps an agent company leaves the moment that agent gets up and walks. */
  private keepCompany(): void {
    if (this.clock - this.checked < 0.25) return
    this.checked = this.clock
    this.idle = this.world.idleFigures()
    for (const p of this.pets) {
      if (!p.partner) continue
      if (this.idle.some((f) => this.idOf(f) === p.partner)) continue
      p.walker.interrupt()
      p.partner = null
    }
  }

  /** A pet caught in the agents' corridors (a desk the cat visits) hurries out the moment anyone starts to walk. */
  private retreat(): void {
    const busy = this.trafficBusy()
    for (const p of this.pets) {
      if (p.beast.root.position.y > 0.02 || p.walker.sleeping) continue
      const at = p.walker.position
      const near = p.basket ? Math.hypot(Math.max(p.basket.x0 - at.x, 0, at.x - p.basket.x1), Math.max(p.basket.z0 - at.z, 0, at.z - p.basket.z1)) < 0.9 : false
      if (near || this.petNav.inHome(at)) {
        p.fleeing = false
        continue
      }
      if (busy && !p.fleeing) {
        p.fleeing = true
        p.walker.interrupt()
      }
      if (p.fleeing && p.id === 'cat') this.leapIfCaught(p, at)
    }
  }

  /** Caught in a narrow gap between desks with an agent coming: the cat springs onto the nearest desk. */
  private leapIfCaught(pet: Pet, at: Point): void {
    const coming = [...this.world.figures.values()].some((f) => f.root.visible && f.walking && Math.hypot(f.root.position.x - at.x, f.root.position.z - at.z) < 2.4)
    if (!coming) return
    let best: { x: number; z: number; d: number } | null = null
    for (const desk of DESKS) {
      const x = Math.max(desk.x - 0.5, Math.min(desk.x + 0.5, at.x))
      const z = Math.max(desk.z - 0.2, Math.min(desk.z + 0.2, at.z))
      const d = Math.hypot(Math.max(desk.x - 0.7 - at.x, 0, at.x - desk.x - 0.7), Math.max(desk.z - 0.35 - at.z, 0, at.z - desk.z - 0.35))
      if (d < (best?.d ?? 1.1)) best = { x, z, d }
    }
    if (best) pet.walker.leap(new THREE.Vector3(best.x, DESK_TOP, best.z))
  }

  /** The nearest spot of the pets' own floor, for a pet leaving the corridors. */
  private homeward(pet: Pet): Point | null {
    const at = pet.walker.position
    let best: Point | null = null
    let near = Infinity
    for (let dx = -6; dx <= 6; dx += 0.25) {
      for (let dz = -6; dz <= 6; dz += 0.25) {
        const p = { x: at.x + dx, z: at.z + dz }
        const d = Math.hypot(dx, dz)
        if (d < near && this.petNav.inHome(p) && this.petNav.clearOf(p, pet.r + 0.1)) {
          near = d
          best = p
        }
      }
    }
    return best
  }

  private idOf(f: Figure): string {
    for (const [id, g] of this.world.figures) if (g === f) return id
    return ''
  }

  /** Where the pets meet: on the biggest stretch of floor their grid leaves open, nearest the director's floor, in plain view. */
  private findHub(): Point {
    const hub = this.petNav.biggestRegion({ x: 6, z: 4.6 })
    this.petNav.setHome(hub)
    return hub
  }

  /** Where each one starts, in plain view in the open space, when that spot is free; else a seeded free spot. */
  private startSpot(seed: number, nav: NavGrid | PetNav, r: number, me: THREE.Object3D): Point {
    const placed = (p: Point): boolean =>
      this.roots.every((o) => o === me || (o.position.x === 0 && o.position.z === 0) || Math.hypot(o.position.x - p.x, o.position.z - p.z) > 1.4)
    if (seed - this.seed === 23 && placed(this.hub)) return this.hub
    const rng = ((a) => () => (a = (a * 16807) % 2147483647) / 2147483647)(seed + 1)
    const hub = nav === this.petNav ? this.hub : LANDING
    // The second pet starts beside the first, in plain view.
    const beside = (p: Point) => nav !== this.petNav || (Math.hypot(p.x - hub.x, p.z - hub.z) > 1.2 && Math.hypot(p.x - hub.x, p.z - hub.z) < 3.2)
    const ok = (p: Point) => this.clear(p) && placed(p) && beside(p) && (nav !== this.petNav || (this.petNav.inHome(p) && this.petNav.clearOf(p, r + 0.1)))
    return freeSpot(nav, SIZE, rng, hub, ok) ?? hub
  }

  /** Not on the elevator's landing, where agents step out. */
  private clear(p: Point): boolean {
    return Math.hypot(p.x - LANDING.x, p.z - LANDING.z) > 1.6
  }

  /** No agent stands within a pet's reach of this point. */
  private awayFromAgents(p: Point, r: number): boolean {
    for (const f of this.world.figures.values()) {
      if (f.root.visible && Math.hypot(f.root.position.x - p.x, f.root.position.z - p.z) < r + AGENT_R + 0.5) return false
    }
    return true
  }

  private stroll(here: Point, rng: () => number, min: number, max: number, nav: NavGrid | PetNav, r = 0): Plan | null {
    const to = freeSpot(nav, SIZE, rng, here, (p) => this.clear(p) && (nav !== this.petNav || (this.petNav.inHome(p) && this.petNav.clearOf(p, r + 0.1) && this.awayFromAgents(p, r))))
    return to ? { to, rest: min + rng() * (max - min) } : null
  }

  /** The baskets: a small bed for the cat, a big one for the dog, on a free cell by a wall that blocks nobody's way. */
  private placeBaskets(): void {
    for (const pet of this.pets) {
      const size = BASKET_SIZE[pet.id]
      const anchor = BASKET_ANCHOR[pet.id]
      const candidates: Rect[] = []
      const edge = 0.06
      for (let s = 0.5; s < SIZE.depth - 0.5; s += 0.5) {
        candidates.push(around(edge + size / 2, s, size, size), around(SIZE.width - edge - size / 2, s, size, size))
      }
      for (let s = 0.5; s < SIZE.width - 0.5; s += 0.5) {
        candidates.push(around(s, edge + size / 2, size, size), around(s, SIZE.depth - edge - size / 2, size, size))
      }
      const centre = (r: Rect): Point => ({ x: (r.x0 + r.x1) / 2, z: (r.z0 + r.z1) / 2 })
      candidates.sort((a, b) => Math.hypot(centre(a).x - anchor.x, centre(a).z - anchor.z) - Math.hypot(centre(b).x - anchor.x, centre(b).z - anchor.z))
      for (const rect of candidates) {
        const c = centre(rect)
        // Off the landing, clear of the plants (they hide a sleeper from the camera), and where the pets can get to.
        if (!this.clear(c) || PLANTS.some((pl) => Math.hypot(pl.x - c.x, pl.z - c.z) < 1.4) || this.petNav.path(this.hub, c).length === 0) continue
        const prop = buildBasket(size, pet.id === 'cat' ? P.red : P.cyanDim)
        if (!this.world.placeProp(prop, rect, 0)) {
          disposeTree(prop)
          continue
        }
        pet.basket = around(prop.position.x, prop.position.z, size, size)
        pet.basketSpot = { x: prop.position.x, z: prop.position.z }
        this.baskets.push(prop)
        this.petNav.addBasket(pet.basket)
        break
      }
    }
  }

  private syncBaskets(): void {
    this.petNav.sync()
  }

  /** For each desk, where the cat stands to hop onto it: the free spot nearest its perch within a hop, if any. */
  private readonly approaches = new Map<number, { from: Point; at: THREE.Vector3 } | null>()

  private approachOf(i: number, r: number): { from: Point; at: THREE.Vector3 } | null {
    if (this.approaches.has(i)) return this.approaches.get(i) ?? null
    const desk = DESKS[i]
    const at = new THREE.Vector3(desk.x + 0.5, DESK_TOP, desk.z - 0.1)
    let best: Point | null = null
    let near = 1.4
    for (let dx = -0.5; dx <= 1.2; dx += 0.1) {
      for (const z of [12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 14.0, 14.1, 14.2, 14.3, 14.4, 14.5]) {
        const p = { x: desk.x + dx, z }
        const d = Math.hypot(p.x - at.x, p.z - at.z)
        if (d < near && this.petNav.clearOf(p, r + 0.02)) {
          near = d
          best = p
        }
      }
    }
    const found = best && this.world.nav.path(LANDING, best).length > 0 ? { from: best, at } : null
    this.approaches.set(i, found)
    return found
  }

  /** Whether some agent is on the move: a pet then stays out of the way and keeps to its own business. */
  private trafficBusy(): boolean {
    for (const f of this.world.figures.values()) if (f.root.visible && f.walking) return true
    return false
  }

  /** A free spot beside an idle agent for a pet that wants company, at `lo` to `hi` past the room it needs. */
  private besideAgent(pet: Pet, rng: () => number, lo: number, hi: number): { at: Point; agent: Figure } | null {
    const agents = this.idle
    if (agents.length === 0) return null
    const agent = agents[Math.floor(rng() * agents.length)]
    const a = agent.root.position
    const room = pet.r + AGENT_R + 0.05
    for (let k = 0; k < 14; k++) {
      const angle = rng() * Math.PI * 2
      const d = room + lo + rng() * (hi - lo)
      const at = { x: a.x + Math.sin(angle) * d, z: a.z + Math.cos(angle) * d }
      // On the pets' own floor, with room to step away when the agent gets up.
      if (!this.petNav.inHome(at) || !this.petNav.clearOf(at, pet.r + 0.05)) continue
      const crowded = this.roots.some((o) => o !== pet.beast.root && Math.hypot(o.position.x - at.x, o.position.z - at.z) < pet.r + (this.radii.get(o) ?? 0.3) + 0.25)
      if (crowded || this.petNav.path(pet.walker.position, at).length === 0) continue
      return { at, agent }
    }
    return null
  }

  private petPlan(pet: Pet, here: Point, rng: () => number, now: number): Plan | null {
    pet.carrying = false
    pet.partner = null
    if (pet.fleeing) {
      const to = this.homeward(pet)
      pet.fleeing = false
      if (to) return { to, rest: 0.5, run: true, corridor: true }
    }
    if (pet.basketSpot && now - pet.lastNap > pet.napEvery) {
      pet.lastNap = now
      pet.napEvery = 70 + rng() * 60
      return { to: pet.basketSpot, rest: 30 + rng() * 30, act: 'sleep', lie: true }
    }
    const roll = rng()
    const company = !this.trafficBusy() || roll < 0.1
    if (pet.id === 'cat') return this.catPlan(pet, here, rng, roll, company)
    return this.dogPlan(pet, here, rng, roll, company)
  }

  private sprint(pet: Pet, here: Point, rng: () => number, tag: string, chance: number): Plan | null {
    const plan = this.stroll(here, rng, 1.5, 4, this.petNav, pet.r)
    if (!plan) return null
    const say = rng() < chance ? { tag, at: 'start' as const } : undefined
    return { ...plan, run: true, say }
  }

  /** The cat strolls, sprints now and then, rubs against an idle agent's legs or sits by one, or naps on a desk. */
  private catPlan(pet: Pet, here: Point, rng: () => number, roll: number, company: boolean): Plan | null {
    if (company && roll < 0.28) {
      const mode = rng() < 0.55 ? 'rub' : 'sit'
      const near = this.besideAgent(pet, rng, mode === 'rub' ? 0.0 : 0.1, mode === 'rub' ? 0.02 : 0.5)
      if (near) {
        pet.partner = this.idOf(near.agent)
        const a = near.agent.root.position
        // Rubbing, it stands alongside the legs (its flank to them); sitting, it looks at the agent.
        const face = mode === 'rub' ? { x: near.at.x - (a.z - near.at.z), z: near.at.z + (a.x - near.at.x) } : { x: a.x, z: a.z }
        return {
          to: near.at,
          rest: 6 + rng() * 6,
          act: mode,
          face,
          say: mode === 'rub' ? { tag: 'meow', at: 'arrive' } : undefined,
        }
      }
    }
    if (!this.trafficBusy() && roll < 0.4) {
      const found = this.approachOf(Math.floor(rng() * DESKS.length), pet.r)
      if (found) return { to: found.from, rest: 10 + rng() * 10, perch: { at: found.at }, corridor: true }
    }
    if (roll < 0.62) return this.sprint(pet, here, rng, 'meow', 0.3)
    const plan = this.stroll(here, rng, 2, 6, this.petNav, pet.r)
    return plan && rng() < 0.3 ? { ...plan, lie: true, rest: 8 + rng() * 6 } : plan
  }

  /** The dog strolls, sprints and barks, brings its ball to an idle agent, or lies down. */
  private dogPlan(pet: Pet, here: Point, rng: () => number, roll: number, company: boolean): Plan | null {
    if (pet.chase) {
      // After the ball is handed over, a dash away: the agent is chased, or does the chasing.
      pet.chase = false
      return this.sprint(pet, here, rng, 'bark', 0.7)
    }
    if (company && roll < 0.3) {
      const near = this.besideAgent(pet, rng, 0.1, 0.6)
      if (near) {
        pet.partner = this.idOf(near.agent)
        pet.carrying = true
        pet.chase = true
        const a = near.agent.root.position
        return { to: near.at, rest: 3 + rng() * 3, run: true, act: 'play', face: { x: a.x, z: a.z }, say: { tag: 'bark', at: 'arrive' } }
      }
    }
    if (roll < 0.55) return this.sprint(pet, here, rng, 'bark', 0.5)
    const plan = this.stroll(here, rng, 2, 5, this.petNav, pet.r)
    return plan && rng() < 0.3 ? { ...plan, lie: true, rest: 10 + rng() * 8 } : plan
  }

  private animateRobot(r: ReturnType<typeof buildRobot>, w: Wanderer, t: number): void {
    const swing = w.walking ? Math.sin(t * 7) * 0.5 : 0
    r.arms[0].rotation.x = swing
    r.arms[1].rotation.x = -swing
    r.head.rotation.y = w.walking ? 0 : Math.sin(t * 0.9) * 0.5
    r.head.position.y = 0.44 + Math.sin(t * 2.2) * 0.015
    r.lamp.visible = Math.sin(t * 4) > -0.3
  }

  private animatePet(p: Pet, t: number): void {
    const w = p.walker
    animateBeast(p.beast, { walking: w.walking, running: w.running, lying: w.lying, act: w.act, carrying: p.carrying && !w.sleeping, seed: p.seed }, t)
  }
}

/** Frees what a prop that found no place had drawn. */
function disposeTree(o: THREE.Object3D): void {
  o.traverse((c) => {
    if (c instanceof THREE.Mesh) c.geometry.dispose()
  })
}
