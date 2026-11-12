import * as THREE from 'three'
import { box, cylinder, mat } from './kit'
import { HAIR, SKIN } from './palette'

// The blocks a figure (figures.ts) is made of, and what it may hold. Built once, in this order: the meshes and their
// materials come out the same for the same agent every time.

/** The parts of a figure that move: the hips carry the head, the legs and the arms. */
export interface Limbs {
  hips: THREE.Group
  head: THREE.Group
  legs: readonly [THREE.Group, THREE.Group]
  arms: readonly [THREE.Group, THREE.Group]
}

/** Dresses the limbs: skin and hair from the agent's look, its shirt the uniform of its role. */
export function dress(l: Limbs, uniform: number, look: number): void {
  const skin = mat(SKIN[look % SKIN.length])
  const hair = mat(HAIR[look % HAIR.length])
  const trousers = mat(0x2b3a44)
  const shoes = mat(0x15191c)
  const shirt = mat(uniform)
  for (const [leg, x] of [[l.legs[0], -0.11], [l.legs[1], 0.11]] as const) {
    leg.position.set(x, 0, 0)
    box(leg, [0.15, 0.66, 0.17], [0, -0.66, 0], trousers)
    box(leg, [0.16, 0.08, 0.24], [0, -0.72, 0.03], shoes)
    l.hips.add(leg)
  }
  box(l.hips, [0.44, 0.58, 0.25], [0, 0, 0], shirt)
  box(l.hips, [0.46, 0.07, 0.27], [0, 0.0, 0], mat(0x1d262c))
  box(l.hips, [0.12, 0.08, 0.13], [0, 0.58, 0], skin)
  dressHead(l.head, skin, hair)
  l.hips.add(l.head)
  for (const [arm, x] of [[l.arms[0], -0.29], [l.arms[1], 0.29]] as const) {
    arm.position.set(x, 0.55, 0)
    box(arm, [0.12, 0.5, 0.14], [0, -0.5, 0], shirt)
    box(arm, [0.11, 0.1, 0.12], [0, -0.6, 0], skin)
    l.hips.add(arm)
  }
}

function dressHead(head: THREE.Group, skin: THREE.Material, hair: THREE.Material): void {
  head.position.y = 0.66
  box(head, [0.3, 0.32, 0.3], [0, 0, 0], skin)
  box(head, [0.32, 0.12, 0.32], [0, 0.24, -0.01], hair)
  box(head, [0.32, 0.2, 0.08], [0, 0.1, -0.13], hair)
  box(head, [0.05, 0.05, 0.02], [-0.07, 0.17, 0.15], mat(0x111111))
  box(head, [0.05, 0.05, 0.02], [0.07, 0.17, 0.15], mat(0x111111))
}

/** What a figure may hold, each hidden until its pose calls for it. */
export interface Held {
  /** A coffee mug in the right hand, shown only on the sofa. */
  mug: THREE.Group
  /** A ping-pong paddle in the right hand, shown only at the table. */
  paddle: THREE.Group
  /** A printed sheet held up in the left hand while a read tool runs. */
  sheet: THREE.Group
}

/** Builds what the figure may hold and puts each in its hand, hidden. */
export function holdables(h: Held, left: THREE.Group, right: THREE.Group): void {
  cylinder(h.mug, 0.075, 0.14, [0, 0, 0], mat(0xf0ece2), 8)
  cylinder(h.mug, 0.062, 0.01, [0, 0.135, 0], mat(0x3b2416), 8)
  box(h.mug, [0.03, 0.08, 0.06], [0.09, 0.03, 0], mat(0xf0ece2))
  h.mug.position.set(0, -0.68, 0.06)
  h.mug.visible = false
  right.add(h.mug)
  box(h.paddle, [0.03, 0.12, 0.03], [0, 0, 0], mat(0x7a5236))
  box(h.paddle, [0.02, 0.2, 0.18], [0, 0.1, 0], mat(0xc8463c))
  h.paddle.position.set(0, -0.72, 0.05)
  h.paddle.visible = false
  right.add(h.paddle)
  box(h.sheet, [0.2, 0.26, 0.015], [0, 0, 0], mat(0xf4f0e4), false)
  box(h.sheet, [0.14, 0.012, 0.018], [0, 0.18, 0], mat(0x6f8f99), false)
  box(h.sheet, [0.14, 0.012, 0.018], [0, 0.13, 0], mat(0x6f8f99), false)
  h.sheet.position.set(0.12, -0.72, 0.08)
  h.sheet.rotation.x = -0.4
  h.sheet.visible = false
  left.add(h.sheet)
}
