import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { BLOCK, type Block, type CityContext, DEPTH, park, pick } from './kit'

const PEOPLE = 520
const TROUSERS = [0x1d2b44, 0x2b2b2b, 0x5a4636, 0x3a4a5a, 0x6f6a5f, 0x223322]
const COATS = [0x2b3a44, 0x7a3b22, 0x3f8f4a, 0xc8463c, 0xcbbf9c, 0x1a1a1a, 0x3a8fd8, 0x8a5aa8, 0xf2a93b, 0xe8ecee]
const SKINS = [0xe2b48c, 0xb07a52, 0x7c4f33, 0xf0c9a5]
const BROLLIES = [0x1a1a1a, 0xc8463c, 0x3a8fd8, 0xf2c230, 0xff3fd2]

/** An umbrella: a ribbed dome over its shaft and a tip on top. */
function umbrella(): THREE.BufferGeometry {
  const canopy = new THREE.SphereGeometry(0.62, 8, 3, 0, Math.PI * 2, 0, Math.PI / 2).scale(1, 0.55, 1)
  const shaft = new THREE.CylinderGeometry(0.025, 0.025, 0.75, 4).translate(0, -0.2, 0)
  const tip = new THREE.CylinderGeometry(0.02, 0.02, 0.18, 4).translate(0, 0.42, 0)
  return mergeGeometries([canopy, shaft, tip])!
}

/**
 * Passers-by: a body, a head, an umbrella when it rains; each walks round its own block, more of them near the tower.
 * Legs and arms, two of each per passer-by, swing as they walk.
 */
export class Walkers {
  readonly umbrellas: THREE.InstancedMesh
  private readonly bodies: THREE.InstancedMesh
  private readonly heads: THREE.InstancedMesh
  private readonly legs: THREE.InstancedMesh
  private readonly arms: THREE.InstancedMesh
  /** Per passer-by: its block's centre (x, z), where it starts round the block, and its pace (signed: the way). */
  private readonly walkers = new Float32Array(PEOPLE * 4)
  private readonly matrix = new THREE.Matrix4()
  private readonly quat = new THREE.Quaternion()
  private readonly limb = new THREE.Quaternion()
  private readonly euler = new THREE.Euler()
  private readonly pos = new THREE.Vector3()
  private readonly scale = new THREE.Vector3(1, 1, 1)

  constructor(ctx: CityContext, blocks: Block[]) {
    const { cache } = ctx
    this.bodies = new THREE.InstancedMesh(new THREE.BoxGeometry(0.46, 0.6, 0.3), cache.lambert(), PEOPLE)
    this.heads = new THREE.InstancedMesh(new THREE.BoxGeometry(0.3, 0.3, 0.3), cache.lambert(), PEOPLE)
    // Limbs hang from their joint (hip, shoulder): the geometry is moved down so a rotation swings it from the top.
    const leg = new THREE.BoxGeometry(0.17, 0.55, 0.17).translate(0, -0.275, 0)
    const arm = new THREE.BoxGeometry(0.13, 0.5, 0.13).translate(0, -0.25, 0)
    this.legs = new THREE.InstancedMesh(leg, cache.lambert(), PEOPLE * 2)
    this.arms = new THREE.InstancedMesh(arm, cache.lambert(), PEOPLE * 2)
    this.umbrellas = new THREE.InstancedMesh(umbrella(), new THREE.MeshLambertMaterial({ flatShading: true, side: THREE.DoubleSide }), PEOPLE)
    this.dress(ctx, blocks)
    ctx.root.add(this.bodies, this.heads, this.umbrellas, this.legs, this.arms)
  }

  /** Where each one walks, and what it wears. */
  private dress({ rnd, cx, cz }: CityContext, blocks: Block[]): void {
    const nearBlocks = blocks.filter((b) => Math.hypot(b.x - cx, b.z - cz) < 45 && !park(b))
    for (let i = 0; i < PEOPLE; i++) {
      const b = i % 3 === 0 ? pick(blocks, rnd) : pick(nearBlocks, rnd)
      this.walkers.set([b.x, b.z, rnd() * 4 * BLOCK, (0.6 + rnd() * 0.9) * (i % 2 ? 1 : -1)], i * 4)
      const coat = new THREE.Color(pick(COATS, rnd))
      const legColor = new THREE.Color(pick(TROUSERS, rnd))
      this.bodies.setColorAt(i, coat)
      this.heads.setColorAt(i, new THREE.Color(pick(SKINS, rnd)))
      this.umbrellas.setColorAt(i, new THREE.Color(pick(BROLLIES, rnd)))
      for (const k of [0, 1]) {
        this.legs.setColorAt(i * 2 + k, legColor)
        this.arms.setColorAt(i * 2 + k, coat)
      }
    }
  }

  /** Each passer-by walks round its block, either way, at its own pace; faster in the rain. */
  update(t: number, rain: boolean): void {
    const half = BLOCK / 2 - 0.5
    const loop = half * 8
    for (let i = 0; i < PEOPLE; i++) {
      const [bx, bz, start, pace] = this.walkers.subarray(i * 4, i * 4 + 4)
      const d = (((start + t * pace * (rain ? 1.5 : 1)) % loop) + loop) % loop
      const side = Math.floor(d / (half * 2))
      const u = (d % (half * 2)) - half
      const [x, z] = side === 0 ? [u, -half] : side === 1 ? [half, u] : side === 2 ? [-u, half] : [-half, -u]
      const stride = t * 8 * Math.abs(pace) * (rain ? 1.5 : 1) + i
      // Facing where it walks: the body's front is its local +z.
      const yaw = Math.PI / 2 - (side * Math.PI) / 2 + (pace < 0 ? Math.PI : 0)
      this.walk(i, bx + x, bz + z, yaw, stride, rain)
    }
    for (const m of [this.bodies, this.heads, this.legs, this.arms]) m.instanceMatrix.needsUpdate = true
    if (rain) this.umbrellas.instanceMatrix.needsUpdate = true
  }

  /**
   * One passer-by at (x, z), facing `yaw`. The body walks along its local z: limbs sit left and right on its local x and
   * swing about it, legs and arms in opposite phase, as people walk. An arm holding an umbrella stays up.
   */
  private walk(i: number, x: number, z: number, yaw: number, stride: number, rain: boolean): void {
    const bob = Math.abs(Math.sin(stride)) * 0.05
    const swing = Math.sin(stride) * 0.6
    this.quat.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, yaw)
    const ground = -DEPTH + 0.3 + bob
    this.bodies.setMatrixAt(i, this.matrix.compose(this.pos.set(x, ground + 0.85, z), this.quat, this.scale))
    this.heads.setMatrixAt(i, this.matrix.compose(this.pos.set(x, ground + 1.32, z), this.quat, this.scale))
    const lx = Math.cos(yaw)
    const lz = -Math.sin(yaw)
    for (const k of [0, 1]) {
      const s = k ? 1 : -1
      this.limb.setFromEuler(this.euler.set(swing * s, yaw, 0, 'YXZ'))
      this.legs.setMatrixAt(i * 2 + k, this.matrix.compose(this.pos.set(x + lx * 0.12 * s, ground + 0.55, z + lz * 0.12 * s), this.limb, this.scale))
      const armSwing = rain && k === 1 ? -2.6 : -swing * s * 0.8
      this.limb.setFromEuler(this.euler.set(armSwing, yaw, 0, 'YXZ'))
      this.arms.setMatrixAt(i * 2 + k, this.matrix.compose(this.pos.set(x + lx * 0.3 * s, ground + 1.12, z + lz * 0.3 * s), this.limb, this.scale))
    }
    if (rain) this.umbrellas.setMatrixAt(i, this.matrix.compose(this.pos.set(x + lx * 0.3, ground + 1.85, z + lz * 0.3), this.quat, this.scale))
  }
}
