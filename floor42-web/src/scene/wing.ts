import * as THREE from 'three'
import { box, cylinder, glow, markTarget, mat } from './kit'
import { P } from './palette'
import { officeChair } from './props'

// The south wing: a conference room for the big committees, and a kitchen with a games corner where idle agents go.
// Everything here is furniture. Nothing in it lights up or moves because of an event, except where agents sit or play,
// which follows their status like everywhere else.

/**
 * Seat offsets around the conference table, from its centre: six a side. The ends stay free, so agents walk round the
 * table to the far side instead of through the chairs.
 */
const SIDE = [-2.0, -1.2, -0.4, 0.4, 1.2, 2.0]
export const CONFERENCE_SEATS: { x: number; z: number; yaw: number }[] = [
  ...SIDE.map((x) => ({ x, z: -1.2, yaw: 0 })),
  ...SIDE.map((x) => ({ x, z: 1.2, yaw: Math.PI })),
]

/** A long table for twelve, its chairs around it. Clicking it opens the meeting room, like the holo table. */
export function conferenceTable(): THREE.Group {
  const g = new THREE.Group()
  box(g, [4.6, 0.08, 1.3], [0, 0.7, 0], mat(P.walnut))
  box(g, [4.66, 0.04, 1.36], [0, 0.66, 0], mat(P.walnutDark))
  for (const x of [-1.9, 1.9]) box(g, [0.5, 0.66, 0.9], [x, 0, 0], mat(P.walnutDark))
  // A strip of microphones and a glowing centre line: a room made for long debates.
  box(g, [3.6, 0.02, 0.08], [0, 0.78, 0], glow(P.cyanDim), false).userData.noOutline = true
  for (const s of CONFERENCE_SEATS) {
    const c = officeChair(P.steel)
    c.position.set(s.x, 0, s.z + Math.sign(s.z) * 0.12)
    c.rotation.y = s.yaw
    g.add(c)
  }
  markTarget(g, { kind: 'table', id: 'table-2' })
  return g
}

/** A wall screen in the conference room: a static slide, not driven by anything. */
export function wallScreen(): THREE.Group {
  const g = new THREE.Group()
  box(g, [0.06, 1.2, 2.4], [0, 0.9, 0], mat(P.steelDark))
  const slide = box(g, [0.02, 1.0, 2.2], [0.04, 1.0, 0], glow(P.screenBg), false)
  slide.userData.noOutline = true
  for (const [y, w] of [
    [1.75, 1.6],
    [1.55, 1.2],
    [1.4, 1.5],
    [1.25, 0.9],
  ] as const) {
    box(g, [0.01, 0.06, w], [0.06, y, -0.2 + (1.6 - w) / 2 - 0.3], glow(P.cyan), false).userData.noOutline = true
  }
  return g
}

/** The kitchen counter along the east wall, with a sink and cupboards. */
export function kitchenCounter(length: number): THREE.Group {
  const g = new THREE.Group()
  box(g, [0.6, 0.86, length], [0, 0, 0], mat(P.beige))
  box(g, [0.64, 0.06, length + 0.04], [0, 0.86, 0], mat(P.steelDark))
  for (let i = 0; i < Math.floor(length / 0.6); i++) {
    box(g, [0.02, 0.6, 0.5], [-0.31, 0.12, -length / 2 + 0.3 + i * 0.6], mat(P.beigeDark))
  }
  box(g, [0.4, 0.04, 0.6], [0, 0.9, length / 2 - 0.6], mat(P.chrome, { metal: true }))
  cylinder(g, 0.02, 0.3, [0.18, 0.9, length / 2 - 0.6], mat(P.chrome, { metal: true }), 6)
  return g
}

export function fridge(): THREE.Group {
  const g = new THREE.Group()
  box(g, [0.7, 1.85, 0.7], [0, 0, 0], mat(0xe8ecee))
  box(g, [0.02, 0.5, 0.06], [-0.36, 1.1, 0.22], mat(P.chrome, { metal: true }))
  box(g, [0.02, 0.3, 0.06], [-0.36, 0.5, 0.22], mat(P.chrome, { metal: true }))
  box(g, [0.01, 0.02, 0.68], [-0.355, 0.85, 0], mat(P.steel))
  return g
}

/** A high round table with its stools, for a coffee standing up. */
export function bistroTable(): THREE.Group {
  const g = new THREE.Group()
  cylinder(g, 0.05, 1.0, [0, 0, 0], mat(P.steelDark), 8)
  cylinder(g, 0.45, 0.05, [0, 1.0, 0], mat(P.walnut), 12)
  cylinder(g, 0.25, 0.04, [0, 0, 0], mat(P.steelDark), 10)
  return g
}

export function stool(): THREE.Group {
  const g = new THREE.Group()
  cylinder(g, 0.04, 0.68, [0, 0, 0], mat(P.chrome, { metal: true }), 6)
  cylinder(g, 0.18, 0.06, [0, 0.68, 0], mat(P.red), 10)
  return g
}

export const PING_PONG = { length: 2.4, width: 1.3, height: 0.76 }

/** A ping-pong table, long side along x, the net across the middle. */
export function pingPongTable(): THREE.Group {
  const g = new THREE.Group()
  const { length: L, width: W, height: H } = PING_PONG
  box(g, [L, 0.05, W], [0, H - 0.05, 0], mat(0x1f6b4a))
  box(g, [L, 0.006, 0.03], [0, H, 0], mat(0xf4f4f0), false)
  box(g, [0.03, 0.006, W], [-L / 2 + 0.015, H, 0], mat(0xf4f4f0), false)
  box(g, [0.03, 0.006, W], [L / 2 - 0.015, H, 0], mat(0xf4f4f0), false)
  box(g, [0.02, 0.16, W + 0.1], [0, H, 0], mat(0xdedede))
  for (const x of [-L / 2 + 0.2, L / 2 - 0.2]) {
    for (const z of [-W / 2 + 0.15, W / 2 - 0.15]) box(g, [0.06, H - 0.05, 0.06], [x, 0, z], mat(P.steelDark))
  }
  return g
}

/** The ball, kept apart so the scene can move it while two players are at the table. */
export function pingPongBall(): THREE.Mesh {
  const ball = new THREE.Mesh(new THREE.SphereGeometry(0.045, 6, 4), mat(0xffffff))
  ball.userData.noOutline = true
  ball.visible = false
  return ball
}

/** An arcade cabinet in the games corner: its screen glows, decoration only. */
export function arcadeCabinet(): THREE.Group {
  const g = new THREE.Group()
  box(g, [0.7, 1.7, 0.7], [0, 0, 0], mat(0x2b2d6e))
  box(g, [0.72, 0.18, 0.72], [0, 1.7, 0], mat(P.orange))
  box(g, [0.5, 0.4, 0.02], [0, 1.15, 0.36], glow(0x6fe3ff), false).userData.noOutline = true
  box(g, [0.6, 0.06, 0.25], [0, 0.88, 0.42], mat(P.steelDark))
  for (const [x, c] of [
    [-0.12, P.red],
    [0.0, P.amber],
    [0.12, P.green],
  ] as const) {
    cylinder(g, 0.03, 0.03, [x, 0.94, 0.45], glow(c), 6)
  }
  box(g, [0.68, 0.12, 0.02], [0, 1.72, 0.36], glow(P.hazard), false).userData.noOutline = true
  return g
}

export function beanBag(color: number): THREE.Group {
  const g = new THREE.Group()
  cylinder(g, 0.42, 0.3, [0, 0, 0], mat(color), 10)
  cylinder(g, 0.3, 0.2, [0, 0.28, -0.08], mat(color), 10)
  return g
}
