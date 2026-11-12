import * as THREE from 'three'
import { at, block, CELL, type CityContext, DEPTH, type Mover, SPAN, type Weather, wheels } from './kit'

const CARS = 80
const BUSES = 7

/** A light that flashes between two colours (patrol cars, the fire truck). */
export interface Flasher {
  mesh: THREE.Mesh
  colors: [number, number]
  rate: number
}

/** A part of a vehicle: one instanced mesh, placed at each of its offsets on every vehicle of the fleet. */
interface Part {
  mesh: THREE.InstancedMesh
  offsets: THREE.Matrix4[]
  /** Which vehicles carry it (a cab's roof sign); the others hide it. */
  only?: (i: number) => boolean
}

/**
 * Vehicles of one kind moving along the avenues, built from parts (body, cabin, wheels, headlights, tail lights), each
 * part one instanced mesh for the whole fleet. A vehicle's front is its local +x, turned to face where it drives.
 */
class Fleet {
  readonly parts: Part[] = []
  private readonly m = new THREE.Matrix4()
  private readonly q = new THREE.Quaternion()
  private readonly hidden = new THREE.Matrix4().makeScale(0, 0, 0)

  readonly movers: Mover[]

  constructor(movers: Mover[]) {
    this.movers = movers
  }

  part(geometry: THREE.BufferGeometry, material: THREE.Material, offsets: THREE.Matrix4[], only?: (i: number) => boolean): THREE.InstancedMesh {
    const mesh = new THREE.InstancedMesh(geometry, material, Math.max(1, this.movers.length * offsets.length))
    this.parts.push({ mesh, offsets, only })
    return mesh
  }

  /** Places every vehicle; `place` gives a mover's position on the ground. */
  update(y: number, place: (c: Mover) => [number, number]): void {
    this.movers.forEach((c, i) => {
      const [x, z] = place(c)
      const yaw = (c.axis === 'x' ? 0 : -Math.PI / 2) + (c.dir < 0 ? Math.PI : 0)
      this.q.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, yaw)
      this.m.compose(new THREE.Vector3(x, y, z), this.q, new THREE.Vector3(1, 1, 1))
      for (const p of this.parts) {
        p.offsets.forEach((o, k) => {
          const j = i * p.offsets.length + k
          if (p.only && !p.only(i)) p.mesh.setMatrixAt(j, this.hidden)
          else p.mesh.setMatrixAt(j, new THREE.Matrix4().multiplyMatrices(this.m, o))
        })
      }
    })
    for (const p of this.parts) p.mesh.instanceMatrix.needsUpdate = true
  }
}

/** What vehicles share: wheels, tyres, lamps, glass. */
interface Kit {
  wheel: THREE.BufferGeometry
  tyre: THREE.Material
  head: THREE.Material
  tail: THREE.Material
  glass: THREE.Material
  lamp: (w: number, h: number, d: number) => THREE.BufferGeometry
}

/**
 * The traffic of the avenues: mostly yellow cabs, city buses, and an ambulance and a police car on patrol, lights
 * flashing. Scenery: it says nothing about the company.
 */
export class Traffic {
  private readonly cars: Mover[] = []
  private readonly buses: Mover[] = []
  private readonly patrol: { mesh: THREE.Group; mover: Mover }[] = []
  private readonly fleets: Fleet[] = []
  private readonly lanes: number[] = []
  private readonly center: { x: number; z: number }
  private readonly ctx: CityContext
  /** The fire truck's wheels and tyres are the cars'. */
  readonly kit: Kit

  constructor(ctx: CityContext, flashers: Flasher[]) {
    this.ctx = ctx
    this.center = { x: ctx.cx, z: ctx.cz }
    for (let g = -SPAN; g <= SPAN; g += CELL) this.lanes.push(g)
    for (let i = 0; i < CARS; i++) this.spawn(this.cars, 3)
    const { cache } = ctx
    this.kit = {
      wheel: new THREE.CylinderGeometry(0.2, 0.2, 0.16, 10),
      tyre: new THREE.MeshLambertMaterial({ color: 0x141414 }),
      head: new THREE.MeshBasicMaterial({ color: 0xfff6c8 }),
      tail: new THREE.MeshBasicMaterial({ color: 0xff2a2a }),
      glass: new THREE.MeshLambertMaterial({ color: 0x2a3f52 }),
      lamp: (w, h, d) => cache.box(w, h, d),
    }
    this.fleets.push(this.cabs())
    for (let i = 0; i < BUSES; i++) this.spawn(this.buses, 6)
    this.fleets.push(this.busFleet())
    for (const f of this.fleets) for (const p of f.parts) ctx.root.add(p.mesh)
    this.patrols(flashers)
  }

  private lane(axis: 'x' | 'z', dir: number): number {
    const { rnd, cx, cz } = this.ctx
    return (axis === 'x' ? cz : cx) + this.lanes[Math.floor(rnd() * this.lanes.length)] + (dir > 0 ? -0.9 : 0.9)
  }

  /** Vehicles of one lane share its speed and start apart, so they never drive through each other. */
  private spawn(list: Mover[], gap: number): void {
    const rnd = this.ctx.rnd
    const laneSpeed = (axis: string, l: number) => 6 + (Math.abs(Math.round(l * 7 + (axis === 'x' ? 3 : 0))) % 8)
    const free = (axis: string, l: number, pos: number) =>
      ![...this.cars, ...this.buses].some((o) => o.axis === axis && o.lane === l && Math.abs(o.pos - pos) < gap)
    for (let tries = 0; tries < 20; tries++) {
      const axis = rnd() < 0.5 ? 'x' : 'z'
      const dir = rnd() < 0.5 ? 1 : -1
      const l = this.lane(axis, dir)
      const pos = -SPAN + rnd() * SPAN * 2
      if (!free(axis, l, pos)) continue
      list.push({ lane: l, axis, dir, pos, speed: laneSpeed(axis, l) })
      return
    }
  }

  /** Cars, mostly yellow cabs; cabs carry their lit roof sign. */
  private cabs(): Fleet {
    const { wheel, tyre, head, tail, glass, lamp } = this.kit
    const fleet = new Fleet(this.cars)
    const others = [0xc8463c, 0x3a8fd8, 0xe8ecee, 0x2b2b2b, 0x6f7d84, 0x3f8f4a]
    const body = fleet.part(new THREE.BoxGeometry(1.9, 0.45, 0.9), this.ctx.cache.lambert(), [at(0, 0.42, 0)])
    const cabin = fleet.part(new THREE.BoxGeometry(1.0, 0.36, 0.8), this.ctx.cache.lambert(), [at(-0.12, 0.82, 0)])
    this.cars.forEach((_, i) => {
      const c = new THREE.Color(i % 5 < 3 ? 0xf2c230 : others[i % others.length])
      body.setColorAt(i, c)
      cabin.setColorAt(i, c)
    })
    fleet.part(new THREE.BoxGeometry(1.02, 0.22, 0.82), glass, [at(-0.12, 0.84, 0)])
    fleet.part(wheel, tyre, [at(0.6, 0.2, 0.42, Math.PI / 2), at(0.6, 0.2, -0.42, Math.PI / 2), at(-0.6, 0.2, 0.42, Math.PI / 2), at(-0.6, 0.2, -0.42, Math.PI / 2)])
    fleet.part(lamp(0.06, 0.14, 0.22), head, [at(0.96, 0.46, 0.28), at(0.96, 0.46, -0.28)])
    fleet.part(lamp(0.06, 0.12, 0.2), tail, [at(-0.96, 0.46, 0.3), at(-0.96, 0.46, -0.3)])
    fleet.part(lamp(0.3, 0.16, 0.5), new THREE.MeshBasicMaterial({ color: 0xffe9a0 }), [at(-0.12, 1.08, 0)], (i) => i % 5 < 3)
    return fleet
  }

  /** City buses, blue and white, along the avenues. */
  private busFleet(): Fleet {
    const { tyre, head, tail, glass, lamp } = this.kit
    const fleet = new Fleet(this.buses)
    fleet.part(new THREE.BoxGeometry(4.4, 1.3, 1.1), new THREE.MeshLambertMaterial({ color: 0xe8ecee }), [at(0, 0.95, 0)])
    fleet.part(new THREE.BoxGeometry(4.42, 0.25, 1.12), new THREE.MeshLambertMaterial({ color: 0x2f6bd8 }), [at(0, 0.55, 0)])
    fleet.part(new THREE.BoxGeometry(4.0, 0.4, 1.14), glass, [at(-0.1, 1.25, 0)])
    fleet.part(new THREE.BoxGeometry(0.06, 0.2, 0.8), new THREE.MeshBasicMaterial({ color: 0xffb020 }), [at(2.22, 1.48, 0)])
    fleet.part(new THREE.CylinderGeometry(0.26, 0.26, 0.18, 10), tyre, [at(1.4, 0.26, 0.5, Math.PI / 2), at(1.4, 0.26, -0.5, Math.PI / 2), at(-1.4, 0.26, 0.5, Math.PI / 2), at(-1.4, 0.26, -0.5, Math.PI / 2)])
    fleet.part(lamp(0.06, 0.15, 0.22), head, [at(2.21, 0.6, 0.38), at(2.21, 0.6, -0.38)])
    fleet.part(lamp(0.06, 0.15, 0.22), tail, [at(-2.21, 0.6, 0.38), at(-2.21, 0.6, -0.38)])
    return fleet
  }

  /** Vehicles with a story: an ambulance and a police car on patrol, lights flashing. */
  private patrols(flashers: Flasher[]): void {
    const { wheel, tyre, head, lamp } = this.kit
    const rnd = this.ctx.rnd
    for (const [body, stripe, colors] of [
      [0xf4f4f0, 0xc8463c, [0xff3b30, 0xffffff]],
      [0x1d2b55, 0xf4f4f0, [0xff3b30, 0x2f6bff]],
    ] as [number, number, [number, number]][]) {
      const g = new THREE.Group()
      g.add(block([2.2, 0.8, 1.0], [0, 0.6, 0], body))
      g.add(block([2.22, 0.15, 1.02], [0, 0.6, 0], stripe))
      wheels(g, 0.7, 0.48, 0.2, wheel, tyre)
      g.add(new THREE.Mesh(lamp(0.06, 0.14, 0.22), head).translateX(1.11).translateY(0.6).translateZ(0.3))
      g.add(new THREE.Mesh(lamp(0.06, 0.14, 0.22), head).translateX(1.11).translateY(0.6).translateZ(-0.3))
      const light = block([0.5, 0.2, 0.8], [0.2, 1.1, 0], colors[0], true)
      g.add(light)
      const axis = rnd() < 0.5 ? 'x' : 'z'
      const dir = rnd() < 0.5 ? 1 : -1
      this.patrol.push({ mesh: g, mover: { lane: this.lane(axis, dir), axis, dir, pos: rnd() * SPAN, speed: 14 } })
      flashers.push({ mesh: light, colors, rate: 6 })
      this.ctx.root.add(g)
    }
  }

  private place(c: Mover): [number, number] {
    return [c.axis === 'x' ? this.center.x + c.pos : c.lane, c.axis === 'x' ? c.lane : this.center.z + c.pos]
  }

  private advance(c: Mover, dt: number, weather: Weather): void {
    c.pos += c.dir * c.speed * dt * (weather === 'rain' ? 0.6 : 1)
    if (c.pos > SPAN) c.pos = -SPAN
    if (c.pos < -SPAN) c.pos = SPAN
  }

  /** Everyone drives on; slower in the rain. */
  update(dt: number, weather: Weather): void {
    for (const c of this.cars) this.advance(c, dt, weather)
    for (const c of this.buses) this.advance(c, dt, weather)
    for (const f of this.fleets) f.update(-DEPTH, (c) => this.place(c))
    for (const p of this.patrol) {
      this.advance(p.mover, dt, weather)
      const [x, z] = this.place(p.mover)
      p.mesh.position.set(x, -DEPTH, z)
      p.mesh.rotation.y = (p.mover.axis === 'x' ? 0 : -Math.PI / 2) + (p.mover.dir < 0 ? Math.PI : 0)
    }
  }
}
