import * as THREE from 'three'

/** What a burst looks like: confetti for a ticket done or a milestone, a small grey puff for a refusal. */
export type BurstKind = 'confetti' | 'puff'

const CONFETTI = [0x3fe0f0, 0xf2a93b, 0x6fe36a, 0xff6fa8, 0xfff1d6]
const PUFF = [0x8a969c, 0xa9b4b8, 0x6f7b80]
const MAX = 240

/**
 * Short-lived particles, all in one instanced mesh so ten agents celebrating at once stay one draw call. Only the
 * scene starts a burst, and only for an event that called for it; the particles say nothing by themselves.
 */
export class Effects {
  readonly mesh: THREE.InstancedMesh
  private readonly pos = new Float32Array(MAX * 3)
  private readonly vel = new Float32Array(MAX * 3)
  private readonly life = new Float32Array(MAX)
  private readonly span = new Float32Array(MAX)
  private readonly spin = new Float32Array(MAX)
  private readonly gravity = new Float32Array(MAX)
  private next = 0
  private alive = 0
  private readonly m = new THREE.Matrix4()
  private readonly q = new THREE.Quaternion()
  private readonly e = new THREE.Euler()
  private readonly p = new THREE.Vector3()
  private readonly s = new THREE.Vector3()
  private readonly c = new THREE.Color()

  constructor() {
    this.mesh = new THREE.InstancedMesh(
      new THREE.BoxGeometry(0.07, 0.07, 0.015),
      new THREE.MeshBasicMaterial({ color: 0xffffff }),
      MAX,
    )
    this.mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
    this.mesh.frustumCulled = false
    this.mesh.userData.noOutline = true
    this.mesh.count = 0
    for (let i = 0; i < MAX; i++) this.mesh.setColorAt(i, this.c.setHex(0xffffff))
  }

  /** Starts a burst at a point in the room, `size` particles (capped). */
  burst(kind: BurstKind, at: THREE.Vector3, size = kind === 'confetti' ? 36 : 14): void {
    const colors = kind === 'confetti' ? CONFETTI : PUFF
    for (let n = 0; n < size; n++) {
      const i = this.next
      this.next = (this.next + 1) % MAX
      const a = Math.random() * Math.PI * 2
      const r = kind === 'confetti' ? 0.8 + Math.random() * 1.2 : 0.2 + Math.random() * 0.3
      this.pos.set([at.x, at.y, at.z], i * 3)
      this.vel.set(
        [Math.cos(a) * r, kind === 'confetti' ? 2.4 + Math.random() * 1.6 : 0.5 + Math.random() * 0.4, Math.sin(a) * r],
        i * 3,
      )
      this.span[i] = this.life[i] = kind === 'confetti' ? 1.3 + Math.random() * 0.6 : 0.9 + Math.random() * 0.4
      this.spin[i] = (Math.random() - 0.5) * 14
      this.gravity[i] = kind === 'confetti' ? 5.5 : -0.4
      this.mesh.setColorAt(i, this.c.setHex(colors[n % colors.length]))
    }
    if (this.mesh.instanceColor) this.mesh.instanceColor.needsUpdate = true
    this.alive = Math.min(MAX, this.alive + size)
  }

  update(dt: number): void {
    if (this.alive === 0) return
    let live = 0
    for (let i = 0; i < MAX; i++) {
      if (this.life[i] <= 0) {
        this.mesh.setMatrixAt(i, this.m.makeScale(0, 0, 0))
        continue
      }
      this.life[i] -= dt
      this.move(i, dt)
      this.mesh.setMatrixAt(i, this.placed(i))
      if (this.life[i] > 0) live++
    }
    this.alive = live
    // The whole ring is drawn while anything lives (spent particles at scale 0); nothing at all once it is over.
    this.mesh.count = live > 0 ? MAX : 0
    this.mesh.instanceMatrix.needsUpdate = true
  }

  /** Moves particle `i` for `dt`: its fall, slowed by the air, never through the floor. */
  private move(i: number, dt: number): void {
    const k = i * 3
    this.vel[k + 1] -= this.gravity[i] * dt
    // Air drag: confetti flutters down slowly once it has popped up.
    const drag = Math.exp(-dt * 1.8)
    this.vel[k] *= drag
    this.vel[k + 2] *= drag
    this.pos[k] += this.vel[k] * dt
    this.pos[k + 1] = Math.max(0.02, this.pos[k + 1] + this.vel[k + 1] * dt)
    this.pos[k + 2] += this.vel[k + 2] * dt
  }

  /** Particle `i`'s matrix: where it is, spun by its age; a puff grows as it fades, confetti shrinks at the end. */
  private placed(i: number): THREE.Matrix4 {
    const k = i * 3
    const left = Math.max(0, this.life[i] / this.span[i])
    const size = this.gravity[i] < 0 ? 1.6 + (1 - left) * 2.5 : Math.min(1, left * 3)
    const age = this.span[i] - this.life[i]
    this.e.set(age * this.spin[i], age * this.spin[i] * 0.7, 0)
    return this.m.compose(
      this.p.set(this.pos[k], this.pos[k + 1], this.pos[k + 2]),
      this.q.setFromEuler(this.e),
      this.s.setScalar(this.life[i] > 0 ? size : 0),
    )
  }
}
