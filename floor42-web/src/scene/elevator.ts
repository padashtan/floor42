import * as THREE from 'three'
import { box, glow, mat } from './kit'
import { P } from './palette'
import type { Figure } from './figures'

/** Where the street is, below the office floor. */
const BOTTOM = -46
/** Units per second: the whole height in about three seconds. */
const SPEED = 16

interface Ride {
  figure: Figure
  up: boolean
  done: () => void
}

/**
 * A glass elevator on the tower's east face. Agents arriving come up in it from the street, and leave down in it. It
 * moves only to carry someone who comes or goes, as the HUD reports: hiring, dismissal, the office opening and closing.
 */
export class Elevator {
  readonly root = new THREE.Group()
  private readonly cabin = new THREE.Group()
  private readonly doors: THREE.Mesh[] = []
  private readonly queue: Ride[] = []
  private ride: Ride | null = null
  private stage: 'fetch' | 'carry' | 'open' = 'fetch'
  private y = 0
  private opening = 0
  private pause = 0

  /** @param x,z the shaft's centre; the landing is on its west side, facing the office */
  private readonly x: number
  private readonly z: number

  constructor(x: number, z: number) {
    this.x = x
    this.z = z
    this.buildShaft()
    this.buildCabin()
    this.root.add(this.cabin)
    this.root.position.set(x, 0, z)
    this.root.traverse((o) => (o.raycast = () => {}))
  }

  /** The shaft: steel posts and tinted glass, from the street to above the floor. */
  private buildShaft(): void {
    const height = -BOTTOM + 3
    for (const [dx, dz] of [
      [-0.6, -0.6],
      [0.6, -0.6],
      [-0.6, 0.6],
      [0.6, 0.6],
    ]) {
      box(this.root, [0.1, height, 0.1], [dx, BOTTOM, dz], mat(P.steelDark))
    }
    const glass = new THREE.Mesh(
      new THREE.BoxGeometry(1.2, height, 1.2),
      new THREE.MeshBasicMaterial({ color: 0x9fd6f2, transparent: true, opacity: 0.18, depthWrite: false }),
    )
    glass.position.y = BOTTOM + height / 2
    glass.userData.noOutline = true
    this.root.add(glass)
    box(this.root, [1.4, 0.2, 1.4], [0, 2.9, 0], mat(P.steel))
  }

  /** The cabin: a floor, a ceiling light, a handrail; two doors on the office side. */
  private buildCabin(): void {
    box(this.cabin, [1.0, 0.08, 1.0], [0, 0, 0], mat(P.steelDark))
    box(this.cabin, [1.0, 0.06, 1.0], [0, 2.3, 0], mat(P.steel))
    box(this.cabin, [0.8, 0.02, 0.5], [0, 2.28, 0], glow(0xfff1d6), false).userData.noOutline = true
    box(this.cabin, [0.04, 0.04, 0.9], [0.45, 1.0, 0], mat(P.chrome, { metal: true }))
    for (const side of [-1, 1]) {
      const door = box(this.cabin, [0.04, 2.2, 0.48], [-0.52, 0.08, side * 0.24], mat(P.chrome, { metal: true }))
      door.userData.side = side
      this.doors.push(door)
    }
  }

  /** Where someone steps out onto the floor, and in from it. */
  get landing(): { x: number; z: number } {
    return { x: this.x - 1.05, z: this.z }
  }

  /** True while it carries this figure, or the figure waits for its turn. */
  busyWith(figure: Figure): boolean {
    return this.ride?.figure === figure || this.queue.some((r) => r.figure === figure)
  }

  /** Brings a figure up from the street, then calls `done` once it can step out. */
  bringUp(figure: Figure, done: () => void): void {
    figure.root.visible = false
    this.queue.push({ figure, up: true, done })
  }

  /** Takes a figure standing on the landing down to the street, then calls `done` (it is gone). */
  takeDown(figure: Figure, done: () => void): void {
    this.queue.push({ figure, up: false, done })
  }

  update(dt: number): void {
    if (!this.ride) {
      this.ride = this.queue.shift() ?? null
      this.stage = 'fetch'
    }
    const r = this.ride
    const target = r ? this.targetOf(r) : 0
    const step = Math.sign(target - this.y) * Math.min(Math.abs(target - this.y), dt * SPEED)
    this.y += step
    this.cabin.position.y = this.y
    const atFloor = Math.abs(this.y) < 0.01
    if (r && Math.abs(this.y - target) < 0.01) this.arrived(r, dt)
    // The rider travels with the cabin.
    if (r && this.stage === 'carry') r.figure.root.position.y = this.y
    const wantOpen = atFloor && !!r && (this.stage === 'open' || (this.stage === 'fetch' && !r.up))
    this.opening += Math.sign((wantOpen ? 1 : 0) - this.opening) * Math.min(Math.abs((wantOpen ? 1 : 0) - this.opening), dt * 3)
    for (const d of this.doors) d.position.z = (d.userData.side as number) * (0.24 + this.opening * 0.42)
  }

  /** Fetch: go where the rider is (the street to come up, the floor to go down); carry: the other end; open: the floor. */
  private targetOf(r: Ride): number {
    if (this.stage === 'open') return 0
    const from = r.up ? BOTTOM : 0
    const to = r.up ? 0 : BOTTOM
    return this.stage === 'fetch' ? from : to
  }

  /** The cabin stands where the stage of the ride wanted it: the rider steps in, out, or the doors wait. */
  private arrived(r: Ride, dt: number): void {
    if (this.stage === 'fetch') this.fetched(r, dt)
    else if (this.stage === 'carry') this.carried(r)
    else if (this.stage === 'open') {
      this.pause += dt
      if (this.pause > 0.5) {
        this.ride = null
        this.pause = 0
        r.done()
      }
    }
  }

  private fetched(r: Ride, dt: number): void {
    if (r.up) {
      r.figure.placeAt({ x: this.x, z: this.z }, -Math.PI / 2, 'stand')
      r.figure.root.visible = true
      this.stage = 'carry'
      return
    }
    // Doors open at the floor, the agent steps in, doors close.
    this.pause += dt
    if (this.pause > 0.8) {
      this.pause = 0
      this.stage = 'carry'
      r.figure.placeAt({ x: this.x, z: this.z }, Math.PI / 2, 'stand')
    }
  }

  private carried(r: Ride): void {
    if (r.up) {
      this.stage = 'open'
      this.pause = 0
      // On the floor exactly: the last carried frame lags one step behind the cabin, which a slow frame (up to 0.1 s)
      // made visible as an agent walking out half sunk into the floor.
      r.figure.root.position.y = 0
    } else {
      r.figure.root.visible = false
      this.ride = null
      r.done()
    }
  }
}
