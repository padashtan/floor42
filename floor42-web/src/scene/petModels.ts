import * as THREE from 'three'
import { box, cylinder, glow, markTarget, mat } from './kit'
import { P } from './palette'

// The cat, the dog and their baskets, in metres. An agent stands 1.8 high with its knee at about 0.4: the cat comes to
// the knee, the dog a little above. Built from a few dozen boxes and cones: modest in triangles, flat-shaded in the
// decade's palette. Everything hangs in the scene graph, which frees what it drew.

/** The parts of a beast the animation moves. */
export interface Beast {
  root: THREE.Group
  /** Torso, head and tail: sunk when it lies down, pitched when it sits, breathing when it sleeps. */
  body: THREE.Group
  head: THREE.Group
  /** Rear left, front left, rear right, front right: the diagonal pairs (0, 3) and (1, 2) swing together. */
  legs: THREE.Group[]
  /** The tail's segments, root first: they sway one after the other. */
  tail: THREE.Group[]
  /** Floppy ears (dog) or none. */
  ears: THREE.Group[]
  /** Something carried in the mouth, hidden unless it carries it. */
  mouth: THREE.Object3D | null
  kind: 'cat' | 'dog'
}

/** An invisible box to point at: the little ones are small to hit with the pointer otherwise. */
function hitBox(parent: THREE.Object3D, size: [number, number, number], z = 0): void {
  const m = new THREE.Mesh(new THREE.BoxGeometry(...size), new THREE.MeshBasicMaterial({ visible: false }))
  m.position.set(0, size[1] / 2, z)
  m.userData.noOutline = true
  parent.add(m)
}

/** A soft dark disc on the floor under a little one, so it reads as standing there (it never takes a pick). */
export function shadow(parent: THREE.Object3D, radius: number): void {
  const m = new THREE.Mesh(
    new THREE.CircleGeometry(radius, 12),
    new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.32, depthWrite: false }),
  )
  m.rotation.x = -Math.PI / 2
  m.position.y = 0.02
  m.userData.noOutline = true
  m.userData.shadow = true
  m.raycast = () => {}
  parent.add(m)
}

/** A cone with `sides` faces, base on `y`: an ear, a muzzle. */
function cone(parent: THREE.Object3D, radius: number, height: number, pos: [number, number, number], material: THREE.Material, sides = 4): THREE.Mesh {
  const m = new THREE.Mesh(new THREE.ConeGeometry(radius, height, sides), material)
  m.position.set(pos[0], pos[1] + height / 2, pos[2])
  m.rotation.y = Math.PI / 4
  m.castShadow = true
  parent.add(m)
  return m
}

/** A group hung from a pivot, with a box dropping `h` below it. */
function limb(parent: THREE.Object3D, at: [number, number, number], size: [number, number, number], material: THREE.Material): THREE.Group {
  const g = new THREE.Group()
  g.position.set(...at)
  box(g, size, [0, -size[1], 0], material)
  parent.add(g)
  return g
}

/** A tail of chained segments, each hung at the end of the one before. */
function tailOf(parent: THREE.Object3D, at: [number, number, number], lengths: number[], thick: number, materials: THREE.Material[]): THREE.Group[] {
  const segments: THREE.Group[] = []
  let host: THREE.Object3D = parent
  lengths.forEach((len, i) => {
    const g = new THREE.Group()
    g.position.set(i === 0 ? at[0] : 0, i === 0 ? at[1] : 0, i === 0 ? at[2] : -lengths[i - 1])
    box(g, [thick, thick, len], [0, -thick / 2, -len], materials[i % materials.length])
    host.add(g)
    segments.push(g)
    host = g
  })
  return segments
}

/** The cat: orange with a darker stripe, a white bib and paws, pointed ears and a long tail. Comes to an agent's knee. */
export function buildCat(): Beast {
  const root = new THREE.Group()
  markTarget(root, { kind: 'pet', id: 'cat' })
  const fur = mat(P.orange)
  const stripe = mat(P.walnut)
  const white = mat(P.beige)
  const pink = mat(P.redDark)
  const legs: THREE.Group[] = []
  for (const x of [-1, 1]) {
    for (const z of [-1, 1]) {
      const leg = limb(root, [x * 0.05, 0.13, z * 0.12], [0.045, 0.1, 0.045], fur)
      box(leg, [0.05, 0.035, 0.07], [0, -0.13, 0.012], white)
      legs.push(leg)
    }
  }
  const body = new THREE.Group()
  root.add(body)
  box(body, [0.15, 0.15, 0.2], [0, 0.12, 0.07], fur)
  box(body, [0.15, 0.15, 0.2], [0, 0.11, -0.1], fur)
  for (const z of [-0.13, -0.05, 0.03, 0.1]) box(body, [0.152, 0.02, 0.03], [0, 0.265, z], stripe)
  box(body, [0.09, 0.1, 0.02], [0, 0.13, 0.172], white, false)
  const head = new THREE.Group()
  head.position.set(0, 0.28, 0.18)
  box(head, [0.13, 0.11, 0.11], [0, -0.06, 0], fur)
  box(head, [0.07, 0.045, 0.04], [0, -0.075, 0.075], white)
  box(head, [0.022, 0.016, 0.01], [0, -0.04, 0.097], pink, false)
  for (const x of [-1, 1]) {
    box(head, [0.025, 0.025, 0.01], [x * 0.032, -0.025, 0.056], glow(P.green), false)
    cone(head, 0.04, 0.065, [x * 0.04, 0.045, -0.005], fur)
    cone(head, 0.022, 0.04, [x * 0.04, 0.045, 0.01], pink)
  }
  box(head, [0.134, 0.015, 0.03], [0, 0.0, -0.01], stripe)
  body.add(head)
  const tail = tailOf(body, [0, 0.21, -0.2], [0.13, 0.12, 0.1], 0.04, [fur, fur, stripe])
  root.scale.setScalar(1)
  shadow(root, 0.2)
  hitBox(root, [0.2, 0.4, 0.5], 0.0)
  return { root, body, head, legs, tail, ears: [], mouth: null, kind: 'cat' }
}

/** The dog: sand-coloured with brown patches, floppy ears, a snout and a tail that wags. A little above the knee. */
export function buildDog(): Beast {
  const root = new THREE.Group()
  markTarget(root, { kind: 'pet', id: 'dog' })
  const fur = mat(P.beige)
  const patch = mat(P.walnut)
  const dark = mat(P.beigeDark)
  const black = mat(P.steelDark)
  const legs: THREE.Group[] = []
  for (const x of [-1, 1]) {
    for (const z of [-1, 1]) {
      const leg = limb(root, [x * 0.07, 0.2, z * 0.17], [0.06, 0.17, 0.06], fur)
      box(leg, [0.066, 0.035, 0.085], [0, -0.2, 0.01], dark)
      legs.push(leg)
    }
  }
  const body = new THREE.Group()
  root.add(body)
  box(body, [0.19, 0.21, 0.42], [0, 0.19, 0], fur)
  box(body, [0.2, 0.06, 0.26], [0, 0.375, -0.03], patch)
  box(body, [0.11, 0.2, 0.1], [0, 0.3, 0.2], fur)
  const head = new THREE.Group()
  head.position.set(0, 0.52, 0.25)
  box(head, [0.15, 0.14, 0.14], [0, -0.14, 0], fur)
  box(head, [0.09, 0.075, 0.12], [0, -0.14, 0.12], dark)
  box(head, [0.045, 0.035, 0.02], [0, -0.07, 0.185], black, false)
  box(head, [0.152, 0.04, 0.1], [0, -0.04, -0.01], patch)
  const ears: THREE.Group[] = []
  for (const x of [-1, 1]) {
    box(head, [0.03, 0.03, 0.01], [x * 0.04, -0.075, 0.072], black, false)
    const ear = limb(head, [x * 0.085, -0.02, -0.01], [0.04, 0.14, 0.09], patch)
    ear.rotation.z = x * 0.25
    ears.push(ear)
  }
  body.add(head)
  const mouth = new THREE.Mesh(new THREE.IcosahedronGeometry(0.04, 0), mat(P.red))
  mouth.position.set(0, -0.2, 0.2)
  mouth.visible = false
  mouth.userData.noOutline = true
  mouth.raycast = () => {}
  head.add(mouth)
  const tail = tailOf(body, [0, 0.36, -0.21], [0.12, 0.1], 0.05, [patch, fur])
  root.scale.setScalar(1)
  shadow(root, 0.3)
  hitBox(root, [0.24, 0.56, 0.78], 0.02)
  return { root, body, head, legs, tail, ears, mouth, kind: 'dog' }
}

/** What a beast is doing, for its animation. */
export interface BeastState {
  walking: boolean
  running: boolean
  lying: boolean
  /** 'sleep', 'rub', 'sit', 'play'... or null. */
  act: string | null
  /** Holding its ball in its mouth. */
  carrying: boolean
  /** Tells two beasts' idle motions apart. */
  seed: number
}

/** A basket: a woven rim round a cushion, `size` across. Cat baskets are small, dog baskets big. */
export function buildBasket(size: number, cushion: number): THREE.Group {
  const g = new THREE.Group()
  const wicker = mat(P.walnut)
  const r = size / 2
  cylinder(g, r, 0.03, [0, 0, 0], mat(P.walnutDark), 10)
  const sides = 10
  for (let i = 0; i < sides; i++) {
    const a = (i / sides) * Math.PI * 2
    const rim = box(g, [0.06, 0.1, size * 0.34], [Math.sin(a) * (r - 0.03), 0.03, Math.cos(a) * (r - 0.03)], wicker)
    rim.rotation.y = a
  }
  cylinder(g, r - 0.07, 0.06, [0, 0.03, 0], mat(cushion), 10)
  g.userData.noOutline = true
  return g
}

/**
 * One frame of a beast: legs swing (a trot, or a gallop when it runs), the tail sways or wags, the head looks around,
 * and the pose follows what it does: lying, curled up asleep and breathing slowly, sitting, rubbing against a leg.
 */
export function animateBeast(b: Beast, s: BeastState, t: number): void {
  const cat = b.kind === 'cat'
  const sleeping = s.act === 'sleep'
  const sitting = s.act === 'sit'
  const down = s.lying || sleeping
  const rate = s.running ? (cat ? 21 : 18) : cat ? 10 : 11
  const amp = s.running ? 1 : 0.55
  const swing = s.walking ? Math.sin(t * rate) * amp : 0
  b.legs.forEach((l, i) => {
    l.visible = !down && !(sitting && i % 2 === 0)
    l.rotation.x = s.walking ? (i === 0 || i === 3 ? swing : -swing) : 0
  })
  const bob = s.walking && !down ? Math.abs(Math.sin(t * rate)) * (s.running ? 0.035 : 0.012) : 0
  b.body.position.set(0, (down ? (cat ? -0.1 : -0.15) : sitting ? 0.04 : 0) + bob, 0)
  b.body.rotation.set(sitting ? -0.5 : s.running ? -0.06 * Math.sin(t * rate) : 0, 0, 0)
  const breath = sleeping ? Math.sin(t * 1.3 + s.seed) : 0
  b.body.scale.set(1 + breath * 0.025, 1 + breath * 0.05, 1)
  if (s.act === 'rub') {
    b.body.position.x = Math.sin(t * 2.4 + s.seed) * 0.05
    b.body.rotation.z = Math.sin(t * 2.4 + s.seed) * 0.12
  } else b.body.rotation.z = 0
  b.head.rotation.set(0, 0, 0)
  b.head.position.set(0, cat ? 0.28 : 0.52, cat ? 0.18 : 0.25)
  if (sleeping) {
    // Curled up (cat) or chin on the floor (dog): the head drops and turns in.
    b.head.position.set(cat ? 0.06 : 0, cat ? 0.15 : 0.3, cat ? 0.13 : 0.3)
    b.head.rotation.set(cat ? 0.5 : 0.7, cat ? 0.9 : 0.3, 0)
  } else if (down) {
    b.head.position.y = cat ? 0.2 : 0.3
    b.head.rotation.x = 0.25
  } else if (!s.walking) {
    b.head.rotation.y = Math.sin(t * 0.6 + s.seed) * 0.5
    b.head.rotation.x = Math.sin(t * 0.37 + s.seed * 2) * 0.1
  } else b.head.rotation.x = s.running ? Math.sin(t * rate) * 0.1 : 0
  for (const [i, ear] of b.ears.entries()) ear.rotation.z = (i === 0 ? -1 : 1) * (0.25 + (s.running ? 0.5 : 0) + (sleeping ? 0.1 : 0))
  b.tail.forEach((seg, i) => {
    if (cat) {
      const curled = sleeping
      seg.rotation.x = i === 0 ? (curled ? 0 : s.act === 'rub' ? 1.25 : s.walking ? 0.55 : 0.75) : curled ? 0 : 0.25
      seg.rotation.y = curled ? 0.95 : Math.sin(t * (s.walking ? 3 : 1.7) - i * 0.9 + s.seed) * (s.walking ? 0.18 : 0.32 + i * 0.12)
    } else {
      const happy = s.act === 'play' || s.running
      seg.rotation.x = i === 0 ? (sleeping ? 0.1 : 0.7) : 0.2
      seg.rotation.y = sleeping ? 0.3 : Math.sin(t * (happy ? 17 : 5) - i * 0.6) * (happy ? 0.75 : 0.3)
    }
  })
  if (b.mouth) b.mouth.visible = s.carrying
}
