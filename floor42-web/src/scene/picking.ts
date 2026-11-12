import * as THREE from 'three'
import type { SceneTarget } from './kit'
import { easeOutBack } from './life'

/** Something the director can point at, and the object that carries it (an agent's figure, a machine...). */
export interface Hit {
  target: SceneTarget
  root: THREE.Object3D
}

/** The first object marked as a target with this kind and id, if the scene holds one. */
export function findRoot(scene: THREE.Object3D, target: SceneTarget): THREE.Object3D | null {
  let found: THREE.Object3D | null = null
  scene.traverse((o) => {
    const t = o.userData.target as SceneTarget | undefined
    if (!found && t && t.kind === target.kind && t.id === target.id) found = o
  })
  return found
}

const hiddenUp = (o: THREE.Object3D | null): boolean => {
  for (let p = o; p; p = p.parent) if (!p.visible) return true
  return false
}

const isMascot = (t: SceneTarget): boolean => t.kind === 'robot' || t.kind === 'pet'

/** How far in front of an agent, along the ray, the robot or a pet must be to take the click. */
const IN_FRONT = 1.2

/** What lies under a point of the canvas: the nearest visible object marked as a target, or one of its parents. */
export class Picker {
  private readonly raycaster = new THREE.Raycaster()
  private readonly canvas: HTMLCanvasElement
  private readonly camera: THREE.Camera
  private readonly scene: THREE.Object3D

  constructor(canvas: HTMLCanvasElement, camera: THREE.Camera, scene: THREE.Object3D) {
    this.canvas = canvas
    this.camera = camera
    this.scene = scene
  }

  /**
   * What lies under a point: the nearest target, except that an agent behind the robot or a pet wins when the little
   * one is not clearly in front of it (by {@link IN_FRONT} along the ray): a pet on a desk must not take the clicks
   * meant for the agent sitting there.
   */
  pick(clientX: number, clientY: number): Hit | null {
    const rect = this.canvas.getBoundingClientRect()
    const ndc = new THREE.Vector2(((clientX - rect.left) / rect.width) * 2 - 1, -((clientY - rect.top) / rect.height) * 2 + 1)
    this.raycaster.setFromCamera(ndc, this.camera)
    let first: { hit: Hit; distance: number } | null = null
    for (const hit of this.raycaster.intersectObjects(this.scene.children, true)) {
      // The raycaster ignores visibility: someone gone home for the night cannot be clicked.
      if (hiddenUp(hit.object)) continue
      for (let o: THREE.Object3D | null = hit.object; o; o = o.parent) {
        const t = o.userData.target as SceneTarget | undefined
        if (!t) continue
        if (!first) {
          first = { hit: { target: t, root: o }, distance: hit.distance }
          if (!isMascot(t)) return first.hit
        } else if (o !== first.hit.root) {
          // Behind the robot or a pet: an agent within reach of it takes the click; furniture in between does not stop it.
          if (hit.distance - first.distance >= IN_FRONT) return first.hit
          if (t.kind === 'agent') return { target: t, root: o }
        }
        break
      }
    }
    return first?.hit ?? null
  }
}

/** The ring's size under a target: a person's, a little one's, or a machine's. */
const ringSize = (kind: SceneTarget['kind']): number => (kind === 'agent' ? 0.7 : kind === 'robot' || kind === 'pet' ? 0.5 : 2.0)

const ring = (inner: number, outer: number, color: number, opacity?: number): THREE.Mesh => {
  const m = new THREE.Mesh(
    new THREE.RingGeometry(inner, outer, 20),
    new THREE.MeshBasicMaterial({ color, transparent: true, depthWrite: false, ...(opacity === undefined ? {} : { opacity }) }),
  )
  m.rotation.x = -Math.PI / 2
  m.visible = false
  m.userData.noOutline = true
  return m
}

/** The ring under the selection (it pulses, and pops in when chosen) and the faint ring under what the pointer is over. */
export class Selection {
  selected: Hit | null = null
  hovered: THREE.Object3D | null = null
  private readonly ring = ring(0.62, 0.74, 0x3fe0f0)
  private readonly hoverRing = ring(0.64, 0.72, 0xfff1d6, 0.4)
  /** When the selection ring last popped in. */
  private selectedAt = -10

  constructor(scene: THREE.Object3D) {
    scene.add(this.ring)
    scene.add(this.hoverRing)
  }

  /** Selects what `root` carries, or nothing. */
  select(hit: Hit | null, now: number): void {
    this.selected = hit
    this.ring.visible = !!hit
    if (!hit) return
    this.selectedAt = now
    this.ring.userData.size = ringSize(hit.target.kind)
    this.ring.scale.setScalar(this.ring.userData.size)
  }

  hover(hit: Hit | null): void {
    this.hovered = hit?.root ?? null
    if (hit) this.hoverRing.userData.size = ringSize(hit.target.kind)
  }

  update(t: number, calm: boolean): void {
    if (this.selected) {
      const p = this.selected.root.getWorldPosition(new THREE.Vector3())
      this.ring.position.set(p.x, 0.03, p.z)
      ;(this.ring.material as THREE.MeshBasicMaterial).opacity = 0.65 + 0.35 * Math.sin(t * 5)
      // Click feedback: the ring pops in from wide, with a small overshoot.
      const size = (this.ring.userData.size as number | undefined) ?? 1
      const pop = calm ? 1 : 1.6 - 0.6 * easeOutBack((t - this.selectedAt) / 0.3)
      this.ring.scale.setScalar(size * pop)
    }
    const hovering = !!this.hovered && this.hovered !== this.selected?.root && this.hovered.visible
    this.hoverRing.visible = hovering
    if (hovering) {
      const p = this.hovered!.getWorldPosition(new THREE.Vector3())
      this.hoverRing.position.set(p.x, 0.025, p.z)
      this.hoverRing.scale.setScalar((this.hoverRing.userData.size as number | undefined) ?? 1)
    }
  }
}
