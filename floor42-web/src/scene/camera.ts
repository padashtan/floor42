import * as THREE from 'three'
import { easeInOutCubic } from './life'

const GLIDE_SECONDS = 0.8
const ELEVATION = THREE.MathUtils.degToRad(30)
const VIEW_DIR = new THREE.Vector3(Math.cos(ELEVATION) * Math.SQRT1_2, Math.sin(ELEVATION), Math.cos(ELEVATION) * Math.SQRT1_2)
const SCREEN_RIGHT = new THREE.Vector3(1, 0, -1).normalize()
const SCREEN_UP = new THREE.Vector3(-1, 0, -1).normalize()

/** Where the camera looks and how far it is zoomed: carried over when a new decade rebuilds the scene. */
export interface View {
  x: number
  y: number
  z: number
  height: number
}

/**
 * The isometric camera: it looks at a point of the floor from 30 degrees up, pans in screen directions, zooms by its
 * view height, and glides (eased in and out) to what the director focuses on. Any pan or key cancels a glide.
 */
export class CameraRig {
  readonly camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 260)
  /** Where the camera looks now, and where it is heading. */
  readonly target: THREE.Vector3
  readonly desired: THREE.Vector3
  viewHeight = 12
  /** The low-resolution size the pixel pipeline renders at. */
  lowRes = new THREE.Vector2(1, 1)
  private glide: { from: THREE.Vector3; to: THREE.Vector3; h0: number; h1: number; start: number } | null = null

  constructor(start: THREE.Vector3) {
    this.target = start.clone()
    this.desired = start.clone()
  }

  get view(): View {
    return { x: this.desired.x, y: this.desired.y, z: this.desired.z, height: this.viewHeight }
  }

  set view(v: View) {
    this.desired.set(v.x, v.y, v.z)
    this.target.copy(this.desired)
    this.viewHeight = v.height
  }

  /** A drag: moves the view by screen pixels, at once. */
  pan(dxPx: number, dyPx: number, canvasHeight: number): void {
    this.glide = null
    const worldPerPx = this.viewHeight / canvasHeight
    this.desired.addScaledVector(SCREEN_RIGHT, dxPx * worldPerPx).addScaledVector(SCREEN_UP, (dyPx * worldPerPx) / Math.sin(ELEVATION))
    this.target.copy(this.desired)
  }

  /** The wheel: zoomed out far enough, the city below comes into view. */
  zoom(out: boolean): void {
    this.glide = null
    this.viewHeight = THREE.MathUtils.clamp(this.viewHeight * (out ? 1.12 : 1 / 1.12), 5, 60)
  }

  /** Glides to a point and a zoom; at once when the director reduces motion. */
  glideTo(to: THREE.Vector3, height: number, now: number, calm: boolean): void {
    if (calm) {
      this.desired.copy(to)
      this.viewHeight = height
      return
    }
    this.glide = { from: this.desired.clone(), to, h0: this.viewHeight, h1: height, start: now }
  }

  /** One frame: keys move the view (WASD, arrows), a glide runs, the camera follows. */
  update(t: number, dt: number, keys: ReadonlySet<string>): void {
    const speed = this.viewHeight * 0.8 * dt
    if (keys.has('w') || keys.has('arrowup')) this.desired.addScaledVector(SCREEN_UP, speed)
    if (keys.has('s') || keys.has('arrowdown')) this.desired.addScaledVector(SCREEN_UP, -speed)
    if (keys.has('d') || keys.has('arrowright')) this.desired.addScaledVector(SCREEN_RIGHT, speed)
    if (keys.has('a') || keys.has('arrowleft')) this.desired.addScaledVector(SCREEN_RIGHT, -speed)
    if (this.glide && keys.size > 0) this.glide = null
    if (this.glide) {
      // A focus glides in and out over 0.8 s, instead of chasing the target at constant pace.
      const k = (t - this.glide.start) / GLIDE_SECONDS
      const e = easeInOutCubic(k)
      this.desired.lerpVectors(this.glide.from, this.glide.to, e)
      this.target.copy(this.desired)
      this.viewHeight = this.glide.h0 + (this.glide.h1 - this.glide.h0) * e
      if (k >= 1) this.glide = null
    } else {
      this.target.lerp(this.desired, 1 - Math.exp(-dt * 8))
    }
    this.place()
  }

  /** Frames the view; snaps the look-at point to whole low-res texels so the pixel grid does not swim while panning. */
  private place(): void {
    const aspect = this.lowRes.x / this.lowRes.y
    const c = this.camera
    c.top = this.viewHeight / 2
    c.bottom = -this.viewHeight / 2
    c.left = (-this.viewHeight / 2) * aspect
    c.right = (this.viewHeight / 2) * aspect
    c.updateProjectionMatrix()
    c.position.copy(this.target).addScaledVector(VIEW_DIR, 50)
    c.lookAt(this.target)
    c.updateMatrixWorld()
    const texel = this.viewHeight / this.lowRes.y
    const right = new THREE.Vector3().setFromMatrixColumn(c.matrixWorld, 0)
    const up = new THREE.Vector3().setFromMatrixColumn(c.matrixWorld, 1)
    const r = this.target.dot(right)
    const u = this.target.dot(up)
    const snapped = this.target
      .clone()
      .addScaledVector(right, Math.round(r / texel) * texel - r)
      .addScaledVector(up, Math.round(u / texel) * texel - u)
    c.position.copy(snapped).addScaledVector(VIEW_DIR, 50)
    c.lookAt(snapped)
  }
}
