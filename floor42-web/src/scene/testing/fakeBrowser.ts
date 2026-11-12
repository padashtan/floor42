import * as THREE from 'three'
import { vi } from 'vitest'
import type { OfficeScene } from '../OfficeScene'
import { fakeGl, type FakeGl } from './fakeGl'
import { fakeDocument } from './sceneDigest'

/**
 * Test-only: just enough of a browser for OfficeScene to be built and to run frames in Node: a document whose canvases
 * record their 2D painting and hand a counting WebGL context ({@link fakeGl}), a window, and animation frames run by the
 * test. Undo with vi.unstubAllGlobals.
 */
export interface FakeBrowser {
  /** The canvas a scene draws on; a new one per call, as App.vue gives each decade its own. */
  canvas(): HTMLCanvasElement
  /** The WebGL context of each canvas made so far, oldest first. */
  contexts: FakeGl[]
  /** Runs the animation frames requested so far, `n` times, the clock moving `dt` seconds each. */
  frames(n: number, dt?: number): void
}

export function fakeBrowser(): FakeBrowser {
  const contexts: FakeGl[] = []
  let queue: FrameRequestCallback[] = []
  let now = 0
  vi.stubGlobal('document', fakeDocument().document)
  vi.stubGlobal('window', { addEventListener() {}, removeEventListener() {}, innerWidth: 1280, innerHeight: 720, devicePixelRatio: 1 })
  vi.stubGlobal('requestAnimationFrame', (f: FrameRequestCallback) => queue.push(f))
  vi.stubGlobal('cancelAnimationFrame', () => (queue = []))
  vi.spyOn(performance, 'now').mockImplementation(() => now)
  return {
    contexts,
    canvas() {
      const gl = fakeGl()
      contexts.push(gl)
      return fakeCanvas(gl)
    },
    frames(n, dt = 1 / 30) {
      for (let i = 0; i < n; i++) {
        now += dt * 1000
        const due = queue
        queue = []
        for (const f of due) f(now)
      }
    },
  }
}

/** A 1280x720 canvas on screen whose WebGL context is `gl`'s. */
function fakeCanvas(gl: FakeGl): HTMLCanvasElement {
  const canvas = {
    width: 1280,
    height: 720,
    clientWidth: 1280,
    clientHeight: 720,
    style: {},
    addEventListener() {},
    removeEventListener() {},
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 1280, height: 720 }),
    getContext: (kind: string) => (kind.startsWith('webgl') ? gl.gl : null),
  }
  return canvas as unknown as HTMLCanvasElement
}

/** The scene graph an OfficeScene draws: the office, the city, the sky, the elevator, the rings, the effects. */
export function graphOf(scene: OfficeScene): THREE.Scene {
  return (scene as unknown as { building: { scene: THREE.Scene } }).building.scene
}

/** No-op callbacks for a scene under test. */
export const QUIET = { onSelect() {}, onContextMenu() {}, onHover() {} }
