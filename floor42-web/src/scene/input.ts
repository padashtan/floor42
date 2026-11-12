import type { SceneTarget } from './kit'
import type { Hit } from './picking'

/** What the pointer, the wheel and the keys do to the scene; the scene decides what each one means. */
export interface InputHandlers {
  pick(clientX: number, clientY: number): Hit | null
  /** A click that did not drag: selects what is under it, or nothing. */
  click(hit: Hit | null): void
  /** A drag, in screen pixels since the last move. */
  pan(dx: number, dy: number): void
  /** What the pointer is over as it moves, button up. */
  hover(hit: Hit | null, clientX: number, clientY: number): void
  /** The pointer left the canvas, or drags: it is over nothing. */
  unhover(): void
  open(target: SceneTarget): void
  contextMenu(hit: Hit, clientX: number, clientY: number): void
  zoom(out: boolean): void
  resize(): void
}

type Target = HTMLElement | Window

/**
 * The director's pointer and keys on the canvas: click to select, drag to pan, wheel to zoom, double click to open,
 * right click for actions, and the keys held down (read each frame by the camera). Typing in a field moves nothing.
 */
export class SceneInput {
  /** Keys held down, lower case. */
  readonly keys = new Set<string>()
  private drag: { x: number; y: number; moved: boolean } | null = null
  private readonly cleanup: (() => void)[] = []
  private readonly canvas: HTMLCanvasElement
  private readonly h: InputHandlers

  constructor(canvas: HTMLCanvasElement, handlers: InputHandlers) {
    this.canvas = canvas
    this.h = handlers
    this.pointer()
    this.on(canvas, 'dblclick', (e) => {
      const hit = this.h.pick(e.clientX, e.clientY)
      if (hit) this.h.open(hit.target)
    })
    this.on(canvas, 'contextmenu', (e) => {
      e.preventDefault()
      const hit = this.h.pick(e.clientX, e.clientY)
      if (hit) this.h.contextMenu(hit, e.clientX, e.clientY)
    })
    this.on(canvas, 'wheel', (e) => this.h.zoom(e.deltaY > 0), { passive: true })
    this.on(window, 'keydown', (e) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      this.keys.add(e.key.toLowerCase())
    })
    this.on(window, 'keyup', (e) => this.keys.delete(e.key.toLowerCase()))
    this.on(window, 'resize', () => this.h.resize())
  }

  private on<K extends keyof HTMLElementEventMap>(el: Target, type: K, fn: (e: HTMLElementEventMap[K]) => void, opts?: AddEventListenerOptions): void {
    el.addEventListener(type, fn as EventListener, opts)
    this.cleanup.push(() => el.removeEventListener(type, fn as EventListener))
  }

  /** Press, move and release: a click when the pointer barely moved, a pan otherwise; a hover when no button is down. */
  private pointer(): void {
    this.on(this.canvas, 'pointerdown', (e) => {
      if (e.button === 0) this.drag = { x: e.clientX, y: e.clientY, moved: false }
    })
    this.on(window, 'pointerup', (e) => {
      if (this.drag && !this.drag.moved && e.button === 0) this.h.click(this.h.pick(e.clientX, e.clientY))
      this.drag = null
    })
    this.on(this.canvas, 'pointermove', (e) => {
      if (!this.drag) return this.h.hover(this.h.pick(e.clientX, e.clientY), e.clientX, e.clientY)
      const dx = e.clientX - this.drag.x
      const dy = e.clientY - this.drag.y
      if (Math.abs(dx) + Math.abs(dy) > 4) this.drag.moved = true
      if (!this.drag.moved) return
      this.h.unhover()
      this.h.pan(-dx, dy)
      this.drag.x = e.clientX
      this.drag.y = e.clientY
    })
    this.on(this.canvas, 'pointerleave', () => this.h.unhover())
  }

  dispose(): void {
    this.cleanup.forEach((f) => f())
    this.cleanup.length = 0
  }
}
