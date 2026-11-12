import * as THREE from 'three'
import { type CityContext, seeded } from './kit'

const BIRDS = 36

/** Birds: pigeons and gulls circling between the street and the office, wings flapping. */
export class Birds {
  private readonly left: THREE.InstancedMesh
  private readonly right: THREE.InstancedMesh
  /** Per bird: its circle's radius, its height, its speed (signed: the way round), where it starts, its flap's phase. */
  private readonly flight = new Float32Array(BIRDS * 5)
  private readonly center: { x: number; z: number }
  private readonly matrix = new THREE.Matrix4()
  private readonly limb = new THREE.Quaternion()
  private readonly euler = new THREE.Euler()
  private readonly pos = new THREE.Vector3()
  private readonly scale = new THREE.Vector3(1, 1, 1)

  constructor({ root, rnd, cache, cx, cz }: CityContext) {
    this.center = { x: cx, z: cz }
    const wing = new THREE.BoxGeometry(0.16, 0.04, 0.5)
    this.left = new THREE.InstancedMesh(wing.clone().translate(0, 0, -0.25), cache.lambert(), BIRDS)
    this.right = new THREE.InstancedMesh(wing.clone().translate(0, 0, 0.25), cache.lambert(), BIRDS)
    wing.dispose()
    for (let i = 0; i < BIRDS; i++) {
      // A flock shares its circle: radius, height, speed; each bird is a little apart from the others.
      const flock = Math.floor(i / 6)
      const fr = seeded(100 + flock)
      this.flight.set([18 + fr() * 30 + rnd() * 2, -6 - fr() * 26 + rnd() * 1.5, (0.25 + fr() * 0.2) * (flock % 2 ? 1 : -1), fr() * 6.28 + i * 0.09, rnd() * 6], i * 5)
      const c = new THREE.Color(flock % 3 === 0 ? 0xf2f2ee : 0x55585e)
      this.left.setColorAt(i, c)
      this.right.setColorAt(i, c)
    }
    root.add(this.left, this.right)
  }

  update(t: number): void {
    for (let i = 0; i < BIRDS; i++) {
      const [r, y, speed, phase, flapPhase] = this.flight.subarray(i * 5, i * 5 + 5)
      const a = phase + t * speed
      const tx = -Math.sin(a) * Math.sign(speed)
      const tz = Math.cos(a) * Math.sign(speed)
      const yaw = Math.atan2(-tz, tx)
      const flap = Math.sin(t * 9 + flapPhase) * 0.7
      this.pos.set(this.center.x + Math.cos(a) * r, y + Math.sin(t * 0.7 + flapPhase) * 0.6, this.center.z + Math.sin(a) * r)
      for (const [mesh, s] of [
        [this.left, 1],
        [this.right, -1],
      ] as const) {
        this.limb.setFromEuler(this.euler.set(flap * s, yaw, 0, 'YXZ'))
        mesh.setMatrixAt(i, this.matrix.compose(this.pos, this.limb, this.scale))
      }
    }
    this.left.instanceMatrix.needsUpdate = true
    this.right.instanceMatrix.needsUpdate = true
  }
}
