import * as THREE from 'three'

/** One bubble to show this frame: over what, saying what. */
export interface Bubble {
  id: string
  root: THREE.Object3D
  /** Height over the floor where the bubble hangs. */
  lift: number
  text: string
}

/**
 * The small speech bubbles over the cat and the dog ("Miaou", "Ouaf", a sleeper's "z"), in the HTML layer over the
 * canvas like the name tags. Without a layer (a test), there are none. They do not take the pointer.
 */
export class PetBubbles {
  private readonly els = new Map<string, HTMLDivElement>()
  private readonly layer: HTMLElement | undefined
  private readonly canvas: HTMLCanvasElement
  private readonly p = new THREE.Vector3()

  constructor(layer: HTMLElement | undefined, canvas: HTMLCanvasElement) {
    this.layer = layer
    this.canvas = canvas
  }

  /** Shows `bubbles` over their pets, hides every other one. */
  place(camera: THREE.Camera, bubbles: readonly Bubble[]): void {
    if (!this.layer) return
    const rect = this.canvas.getBoundingClientRect()
    const shown = new Set<string>()
    for (const b of bubbles) {
      let el = this.els.get(b.id)
      if (!el) {
        el = document.createElement('div')
        el.className = 'pet-bubble'
        el.setAttribute('aria-hidden', 'true')
        el.dataset.pet = b.id
        this.layer.append(el)
        this.els.set(b.id, el)
      }
      if (el.textContent !== b.text) el.textContent = b.text
      b.root.getWorldPosition(this.p)
      this.p.y += b.lift
      this.p.project(camera)
      const visible = b.root.visible && this.p.x > -1.05 && this.p.x < 1.05 && this.p.y > -1.05 && this.p.y < 1.05
      el.style.display = visible ? '' : 'none'
      if (!visible) continue
      shown.add(b.id)
      const x = Math.round(rect.left + ((this.p.x + 1) / 2) * rect.width)
      const y = Math.round(rect.top + ((1 - this.p.y) / 2) * rect.height)
      el.style.transform = `translate(${x}px, ${y}px) translate(-50%, -100%)`
    }
    for (const [id, el] of this.els) if (!shown.has(id)) el.style.display = 'none'
  }

  hide(): void {
    for (const el of this.els.values()) el.style.display = 'none'
  }

  dispose(): void {
    for (const el of this.els.values()) el.remove()
    this.els.clear()
  }
}
