import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import {
  type Craft,
  CRAFTS,
  craftsOf,
  budgetFor,
  DEFAULT_GAP,
  type Flight,
  isDark,
  type LightKind,
  lightOn,
  positionAt,
  SPECS,
  startTraffic,
  stepTraffic,
  type Traffic,
} from './skyPaths'

// Aircraft passing in the sky behind the office: airliners blinking at night, helicopters with a spinning rotor and a
// searchlight, an airship with a scrolling light board, drones in the 2020s. Scenery only, like the city: they say
// nothing about the company. Low-poly boxes merged per craft and drawn instanced: at most nine draw calls with every
// kind in the air, none with the sky empty. The flight rules are in skyPaths.ts.

interface Light {
  kind: LightKind
  at: [number, number, number]
  color: number
  size: number
}

const RED = 0xff3b30
const GREEN = 0x3bff6a
const WHITE = 0xfff6e0

/** The lights each craft carries, in its own frame: nose along +x, up +y, starboard (right) +z. */
const LIGHTS: Record<Craft, Light[]> = {
  plane: [
    { kind: 'port', at: [0.1, -0.1, -2.05], color: RED, size: 0.32 },
    { kind: 'starboard', at: [0.1, -0.1, 2.05], color: GREEN, size: 0.32 },
    { kind: 'strobe', at: [-0.15, -0.1, -2.05], color: WHITE, size: 0.36 },
    { kind: 'strobe', at: [-0.15, -0.1, 2.05], color: WHITE, size: 0.36 },
    { kind: 'tail', at: [-2.1, 0.15, 0], color: WHITE, size: 0.28 },
    { kind: 'beacon', at: [0.2, 0.32, 0], color: RED, size: 0.34 },
  ],
  helicopter: [
    { kind: 'port', at: [0.2, 0, -0.4], color: RED, size: 0.24 },
    { kind: 'starboard', at: [0.2, 0, 0.4], color: GREEN, size: 0.24 },
    { kind: 'beacon', at: [-0.6, 0.3, 0], color: RED, size: 0.28 },
    { kind: 'tail', at: [-1.6, 0.15, 0], color: WHITE, size: 0.2 },
  ],
  blimp: [
    { kind: 'beacon', at: [0, 1.5, 0], color: RED, size: 0.34 },
    { kind: 'port', at: [0.3, -1.5, -0.3], color: RED, size: 0.26 },
    { kind: 'starboard', at: [0.3, -1.5, 0.3], color: GREEN, size: 0.26 },
    { kind: 'tail', at: [-4.6, 0, 0], color: WHITE, size: 0.28 },
  ],
  drone: [
    { kind: 'port', at: [0, 0, -0.3], color: RED, size: 0.2 },
    { kind: 'starboard', at: [0, 0, 0.3], color: GREEN, size: 0.2 },
    { kind: 'strobe', at: [0, 0.12, 0], color: WHITE, size: 0.22 },
  ],
}

const LIGHT_SLOTS = CRAFTS.reduce((n, c) => n + SPECS[c].max * LIGHTS[c].length, 0)

/** A part of a craft: a geometry moved into place, painted one colour. */
function part(g: THREE.BufferGeometry, color: number, at: [number, number, number], rotate?: (g: THREE.BufferGeometry) => void): THREE.BufferGeometry {
  const geo = g
  rotate?.(geo)
  geo.translate(...at)
  const c = new THREE.Color(color)
  const n = geo.getAttribute('position').count
  const colors = new Float32Array(n * 3)
  for (let i = 0; i < n; i++) colors.set([c.r, c.g, c.b], i * 3)
  geo.setAttribute('color', new THREE.BufferAttribute(colors, 3))
  return geo
}

const box = (w: number, h: number, d: number) => new THREE.BoxGeometry(w, h, d)

function airliner(): THREE.BufferGeometry {
  const body = 0xf2f4f6
  const livery = 0x2f6fb5
  return mergeGeometries([
    part(box(4, 0.45, 0.45), body, [0, 0, 0]),
    part(box(0.4, 0.34, 0.34), 0xb9bec2, [2.15, -0.02, 0]),
    part(box(3.7, 0.08, 0.47), livery, [0, 0.04, 0]),
    part(box(1.0, 0.08, 4.0), 0xd8dde2, [0.1, -0.1, 0]),
    part(box(0.6, 0.25, 0.25), 0x8a9096, [0.35, -0.3, -0.9]),
    part(box(0.6, 0.25, 0.25), 0x8a9096, [0.35, -0.3, 0.9]),
    part(box(0.7, 0.85, 0.08), livery, [-1.7, 0.6, 0]),
    part(box(0.5, 0.06, 1.4), 0xd8dde2, [-1.75, 0.12, 0]),
  ])!
}

function helicopter(): THREE.BufferGeometry {
  const paint = 0xc8463c
  return mergeGeometries([
    part(box(1.1, 0.7, 0.7), paint, [0.2, 0, 0]),
    part(box(0.4, 0.45, 0.62), 0x6f9ab8, [0.85, 0.05, 0]),
    part(box(1.3, 0.18, 0.18), paint, [-0.9, 0.12, 0]),
    part(box(0.3, 0.45, 0.06), 0xf4f4f0, [-1.5, 0.3, 0]),
    part(box(0.08, 0.2, 0.08), 0x3a3f44, [0.2, 0.45, 0]),
    part(box(1.3, 0.05, 0.06), 0x3a3f44, [0.2, -0.5, -0.32]),
    part(box(1.3, 0.05, 0.06), 0x3a3f44, [0.2, -0.5, 0.32]),
    part(box(0.05, 0.16, 0.6), 0x3a3f44, [0.5, -0.42, 0]),
    part(box(0.05, 0.16, 0.6), 0x3a3f44, [-0.1, -0.42, 0]),
  ])!
}

/** Two crossed blades spinning about y: the main rotor, and the tail rotor scaled down and turned on its side. */
function rotor(): THREE.BufferGeometry {
  return mergeGeometries([
    part(box(2.6, 0.03, 0.12), 0x2b2d2f, [0, 0, 0]),
    part(box(0.12, 0.03, 2.6), 0x2b2d2f, [0, 0, 0]),
  ])!
}

function airship(): THREE.BufferGeometry {
  const fin = 0x3a5a8a
  return mergeGeometries([
    part(new THREE.SphereGeometry(1, 12, 8), 0xd9dde2, [0, 0, 0], (g) => g.scale(4.5, 1.4, 1.4)),
    part(box(1.4, 0.4, 0.5), 0x3a3f44, [0.3, -1.5, 0]),
    part(box(1.0, 1.0, 0.08), fin, [-3.9, 0.9, 0]),
    part(box(1.0, 1.0, 0.08), fin, [-3.9, -0.9, 0]),
    part(box(1.0, 0.08, 1.0), fin, [-3.9, 0, 0.9]),
    part(box(1.0, 0.08, 1.0), fin, [-3.9, 0, -0.9]),
  ])!
}

function quadcopter(): THREE.BufferGeometry {
  const parts = [
    part(box(0.35, 0.12, 0.35), 0x2b2d2f, [0, 0, 0]),
    part(box(0.95, 0.04, 0.06), 0x3a3f44, [0, 0.02, 0], (g) => g.rotateY(Math.PI / 4)),
    part(box(0.95, 0.04, 0.06), 0x3a3f44, [0, 0.02, 0], (g) => g.rotateY(-Math.PI / 4)),
  ]
  for (const [x, z] of [[1, 1], [1, -1], [-1, 1], [-1, -1]]) {
    parts.push(part(new THREE.CylinderGeometry(0.17, 0.17, 0.02, 8), 0x9aa0a6, [x * 0.33, 0.06, z * 0.33]))
  }
  return mergeGeometries(parts)!
}

/** The airship's light board: amber dots on black, wider than it shows, so it scrolls. */
function boardTexture(text: string): THREE.CanvasTexture {
  const c = document.createElement('canvas')
  c.width = 256
  c.height = 16
  const g = c.getContext('2d')!
  g.fillStyle = '#120a02'
  g.fillRect(0, 0, c.width, c.height)
  g.fillStyle = '#ffb02a'
  g.font = 'bold 12px monospace'
  g.textBaseline = 'middle'
  g.fillText(text, 4, 9)
  const t = new THREE.CanvasTexture(c)
  t.magFilter = THREE.NearestFilter
  t.minFilter = THREE.NearestFilter
  t.wrapS = THREE.RepeatWrapping
  t.repeat.set(0.5, 1)
  t.colorSpace = THREE.SRGBColorSpace
  return t
}

/** How many instances of each pool one frame placed. */
interface Placed {
  used: Record<Craft, number>
  rotors: number
  beams: number
  lights: number
}

/** The searchlight's cone of light, from the helicopter's nose down, and its faint additive material. */
function searchlight(): [THREE.BufferGeometry, THREE.Material] {
  const cone = new THREE.ConeGeometry(1.1, 7, 10, 1, true).translate(0, -3.5, 0)
  const glow = { color: 0xfff1c0, transparent: true, opacity: 0.14, blending: THREE.AdditiveBlending, depthWrite: false }
  return [cone, new THREE.MeshBasicMaterial({ ...glow, side: THREE.DoubleSide })]
}

/** Scenery only: no shadows, no outline, never picked. */
function sceneryOnly(root: THREE.Object3D): void {
  root.traverse((o) => {
    o.castShadow = false
    o.receiveShadow = false
    o.userData.noOutline = true
    o.raycast = () => {}
  })
}

export class Sky {
  readonly root = new THREE.Group()
  private traffic: Traffic
  private clock = 0
  private readonly center: { x: number; z: number }
  private readonly crafts: Craft[]
  private readonly bodies: Record<Craft, THREE.InstancedMesh>
  private readonly rotors: THREE.InstancedMesh
  private readonly beams: THREE.InstancedMesh
  private readonly lights: THREE.InstancedMesh
  private readonly board: THREE.Mesh
  private readonly boardMap: THREE.CanvasTexture
  private readonly boardMat: THREE.MeshBasicMaterial
  private readonly rnd: () => number
  private readonly m = new THREE.Matrix4()
  private readonly local = new THREE.Matrix4()
  private readonly q = new THREE.Quaternion()
  private readonly e = new THREE.Euler()
  private readonly p = new THREE.Vector3()
  private readonly s = new THREE.Vector3()
  private readonly c = new THREE.Color()
  private readonly hidden = new THREE.Matrix4().makeScale(0, 0, 0)

  /**
   * @param cx,cz centre of the tower's footprint
   * @param era the decade the office is furnished in: drones fly in the 2020s only
   */
  constructor(cx: number, cz: number, era: string, rnd: () => number = Math.random) {
    this.center = { x: cx, z: cz }
    this.crafts = craftsOf(era)
    this.rnd = rnd
    this.traffic = startTraffic(0, rnd)
    const lambert = () => new THREE.MeshLambertMaterial({ vertexColors: true })
    this.bodies = {
      plane: this.pool(airliner(), lambert(), SPECS.plane.max),
      helicopter: this.pool(helicopter(), lambert(), SPECS.helicopter.max),
      blimp: this.pool(airship(), lambert(), SPECS.blimp.max),
      drone: this.pool(quadcopter(), lambert(), SPECS.drone.max),
    }
    this.rotors = this.pool(rotor(), lambert(), SPECS.helicopter.max * 2)
    // The searchlight: a faint cone of light under the helicopter's nose, at night only.
    this.beams = this.pool(...searchlight(), SPECS.helicopter.max)
    // Lights shine through the haze, as they do at night: no fog on them.
    this.lights = this.pool(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshBasicMaterial({ fog: false }), LIGHT_SLOTS)
    for (let i = 0; i < LIGHT_SLOTS; i++) this.lights.setColorAt(i, this.c.setHex(WHITE))
    this.boardMap = boardTexture('FLOOR42 * CIRRUS AIR * FLY NOVA * ')
    this.boardMat = new THREE.MeshBasicMaterial({ map: this.boardMap })
    const face = (z: number, yaw: number) => new THREE.PlaneGeometry(4, 0.7).rotateY(yaw).translate(0.2, 0, z)
    this.board = new THREE.Mesh(mergeGeometries([face(1.42, 0), face(-1.42, Math.PI)])!, this.boardMat)
    this.board.matrixAutoUpdate = false
    this.board.visible = false
    this.root.add(this.board)
    sceneryOnly(this.root)
  }

  /** An instanced pool of `n`, empty and hidden until a frame places some. */
  private pool(geo: THREE.BufferGeometry, material: THREE.Material, n: number): THREE.InstancedMesh {
    const mesh = new THREE.InstancedMesh(geo, material, n)
    mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
    mesh.count = 0
    mesh.visible = false
    // Instances move across the whole sky: the geometry's bounds say nothing of where they are.
    mesh.frustumCulled = false
    this.root.add(mesh)
    return mesh
  }

  /** The craft in the air, for the HUD or a test: kind and position. */
  get flights(): readonly Flight[] {
    return this.traffic.flights
  }

  /**
   * @param hour the director's local hour: lights, the board and the searchlight show at night
   * @param calm the director's "reduce motion": an empty sky
   * @param fps the measured frame rate: a struggling scene keeps a single craft
   */
  update(dt: number, hour: number, calm: boolean, fps: number): void {
    if (calm) {
      if (this.root.visible) {
        this.root.visible = false
        this.traffic = startTraffic(this.clock, this.rnd)
      }
      return
    }
    // A hidden tab stops the browser's frames; this also holds the sky still if a throttled frame comes anyway.
    if (typeof document !== 'undefined' && document.hidden) return
    this.root.visible = true
    this.clock += dt
    const t = this.clock
    this.traffic = stepTraffic(this.traffic, t, { max: budgetFor(fps), crafts: this.crafts, gap: DEFAULT_GAP }, this.center, this.rnd)
    const dark = isDark(hour)
    const placed: Placed = { used: { plane: 0, helicopter: 0, blimp: 0, drone: 0 }, rotors: 0, beams: 0, lights: 0 }
    this.board.visible = false
    for (const f of this.traffic.flights) this.place(f, t, dark, placed)
    this.show(placed, dark)
  }

  /** Puts one craft where its flight is at `t`, with its rotors, searchlight, light board and lights. */
  private place(f: Flight, t: number, dark: boolean, placed: Placed): void {
    this.m.compose(this.p.set(...this.bodyAt(f, t)), this.q, this.s.set(1, 1, 1))
    this.bodies[f.craft].setMatrixAt(placed.used[f.craft]++, this.m)
    if (f.craft === 'helicopter') {
      const spin = t * 22 + f.phase
      this.rotors.setMatrixAt(placed.rotors++, this.at(0.2, 0.56, 0, 0, spin, 1))
      this.rotors.setMatrixAt(placed.rotors++, this.at(-1.55, 0.3, 0.07, Math.PI / 2, spin * 1.6, 0.22))
      if (dark) this.beams.setMatrixAt(placed.beams++, this.at(0.9, -0.3, 0, 0, 0, 1, 0.45))
    } else if (f.craft === 'blimp') {
      this.board.matrix.copy(this.m)
      this.board.matrixWorldNeedsUpdate = true
      this.board.visible = true
      this.boardMap.offset.x = (t * 0.04) % 1
      this.boardMat.color.setScalar(dark ? 1 : 0.75)
    }
    for (const l of LIGHTS[f.craft]) {
      const on = dark && lightOn(l.kind, t, f.phase)
      this.lights.setMatrixAt(placed.lights, on ? this.at(...l.at, 0, 0, l.size) : this.hidden)
      this.lights.setColorAt(placed.lights++, this.c.setHex(l.color))
    }
  }

  /** A craft's position at `t`, with its sway; its attitude goes to `this.q`. */
  private bodyAt(f: Flight, t: number): [number, number, number] {
    const { p, heading } = positionAt(f, t)
    const yaw = Math.atan2(-heading.z, heading.x)
    let pitch = Math.asin(heading.y)
    let roll = 0
    let y = p.y
    if (f.craft === 'helicopter') {
      // Nose a little down in forward flight, a slow sway.
      pitch -= 0.1
      roll = Math.sin(t * 0.8 + f.phase) * 0.04
      y += Math.sin(t * 1.3 + f.phase) * 0.12
    } else if (f.craft === 'drone') {
      pitch -= 0.12
      roll = Math.sin(t * 2.1 + f.phase) * 0.08
      y += Math.sin(t * 1.7 + f.phase) * 0.2
    } else if (f.craft === 'blimp') {
      pitch += Math.sin(t * 0.3 + f.phase) * 0.03
    }
    this.q.setFromEuler(this.e.set(roll, yaw, pitch, 'YZX'))
    return [p.x, y, p.z]
  }

  /** Draws as many of each pool as the frame placed; an empty pool is not drawn at all, no draw call for it. */
  private show(placed: Placed, dark: boolean): void {
    const { used, lights } = placed
    for (const craft of Object.keys(used) as Craft[]) {
      const mesh = this.bodies[craft]
      mesh.count = used[craft]
      mesh.visible = used[craft] > 0
      if (used[craft]) mesh.instanceMatrix.needsUpdate = true
    }
    for (const [mesh, n] of [
      [this.rotors, placed.rotors],
      [this.beams, placed.beams],
      [this.lights, dark ? lights : 0],
    ] as const) {
      mesh.count = n
      mesh.visible = n > 0
      if (n) mesh.instanceMatrix.needsUpdate = true
    }
    if (lights && this.lights.instanceColor) this.lights.instanceColor.needsUpdate = true
  }

  /**
   * A matrix in the current craft's frame (`this.m`): moved to (x, y, z), turned `tilt` about x then `spin` about y,
   * pitched `lean` about z, scaled `size`.
   */
  private at(x: number, y: number, z: number, tilt: number, spin: number, size: number, lean = 0): THREE.Matrix4 {
    this.local.compose(
      this.p.set(x, y, z),
      this.q.setFromEuler(this.e.set(tilt, spin, lean, 'XYZ')),
      this.s.setScalar(size),
    )
    return this.local.premultiply(this.m)
  }
}
