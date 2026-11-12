import * as THREE from 'three'

/**
 * The frame loop: one call per animation frame with the scene's clock (t, seconds since it was built) and the frame's
 * duration (dt, capped at a tenth of a second after a pause), and the frame rate it measures over each second.
 */
export class RenderLoop {
  /** Measured frames per second, 0 until the first second has passed. */
  fps = 0
  private readonly timer = new THREE.Timer()
  private handle = 0
  private frames = 0
  private window = 0
  private readonly frame: (t: number, dt: number) => void

  constructor(frame: (t: number, dt: number) => void) {
    this.frame = frame
  }

  /** The scene's clock, in seconds since it was built (as of the last frame). */
  get now(): number {
    return this.timer.getElapsed()
  }

  start(): void {
    this.handle = requestAnimationFrame(this.tick)
  }

  stop(): void {
    cancelAnimationFrame(this.handle)
  }

  private readonly tick = (): void => {
    this.timer.update()
    const dt = Math.min(this.timer.getDelta(), 0.1)
    this.frame(this.timer.getElapsed(), dt)
    this.frames++
    this.window += dt
    if (this.window >= 1) {
      this.fps = this.frames / this.window
      this.frames = 0
      this.window = 0
    }
    this.handle = requestAnimationFrame(this.tick)
  }
}
