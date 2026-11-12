import * as THREE from 'three'
import { columnHeight, columnWidth, lineHeight, lineWidth } from '../signs'
import { PLATE_COLOR } from './colors'
import { hex, seeded } from './kit'

// The city's pixel textures, painted once on small canvases: facades, clouds, neon, sign letters, roofs.

function pixelTexture(c: HTMLCanvasElement, repeatX = 1, repeatY = 1): THREE.CanvasTexture {
  const t = new THREE.CanvasTexture(c)
  t.magFilter = THREE.NearestFilter
  t.minFilter = THREE.NearestFilter
  t.wrapS = t.wrapT = THREE.RepeatWrapping
  t.repeat.set(repeatX, repeatY)
  t.colorSpace = THREE.SRGBColorSpace
  return t
}

/** A white facade with rows of windows, some lit; each building tints it with its own colour. */
export function facade(dark: number, lit: number, cols: number, seed: number): THREE.CanvasTexture {
  const c = document.createElement('canvas')
  c.width = 16
  c.height = 16
  const g = c.getContext('2d')!
  g.fillStyle = '#ffffff'
  g.fillRect(0, 0, 16, 16)
  const rnd = seeded(seed)
  const step = 16 / cols
  for (let y = 1; y < 16; y += 4) {
    for (let x = 0; x < cols; x++) {
      g.fillStyle = rnd() < 0.45 ? hex(lit) : hex(dark)
      g.fillRect(Math.floor(x * step + step / 4), y, Math.max(1, Math.floor(step / 2)), 2)
    }
  }
  return pixelTexture(c, 2, 4)
}

/** A puffy cloud: overlapping soft discs, lighter on top, on a transparent background. */
export function cloudTexture(seed: number, tint = '#ffffff'): THREE.CanvasTexture {
  const c = document.createElement('canvas')
  c.width = 128
  c.height = 64
  const g = c.getContext('2d')!
  const rnd = seeded(seed * 97)
  for (let i = 0; i < 9; i++) {
    const x = 24 + rnd() * 80
    const y = 28 + rnd() * 14 - (Math.abs(x - 64) < 24 ? 8 : 0)
    const r = 12 + rnd() * 14
    const d = g.createRadialGradient(x, y - r * 0.3, r * 0.2, x, y, r)
    d.addColorStop(0, tint)
    d.addColorStop(0.7, tint + 'dd')
    d.addColorStop(1, tint + '00')
    g.fillStyle = d
    g.beginPath()
    g.arc(x, y, r, 0, Math.PI * 2)
    g.fill()
  }
  // A faint grey underside, as clouds have.
  g.globalCompositeOperation = 'source-atop'
  const under = g.createLinearGradient(0, 30, 0, 64)
  under.addColorStop(0, 'rgba(0,0,0,0)')
  under.addColorStop(1, 'rgba(90,100,120,0.35)')
  g.fillStyle = under
  g.fillRect(0, 0, 128, 64)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  return t
}

/** A neon sign: glowing letters on a dark plate. */
export function neon(text: string, color: string, plate = '#120a1c'): THREE.CanvasTexture {
  const c = document.createElement('canvas')
  c.width = Math.max(64, text.length * 14)
  c.height = 24
  const g = c.getContext('2d')!
  g.fillStyle = plate
  g.fillRect(0, 0, c.width, c.height)
  g.font = 'bold 18px monospace'
  g.textAlign = 'center'
  g.textBaseline = 'middle'
  g.shadowColor = color
  g.shadowBlur = 6
  g.fillStyle = color
  g.fillText(text, c.width / 2, c.height / 2 + 1)
  g.shadowBlur = 0
  g.fillStyle = '#ffffff'
  g.globalAlpha = 0.5
  g.fillText(text, c.width / 2, c.height / 2 + 1)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  return t
}

/**
 * A sign's letters on its dark plate, drawn with the metrics of signs.ts so the texture fits its plate unstretched: one
 * line, or stacked top to bottom for a blade. A thin tube of the same colour frames the plate.
 */
export function signTexture(text: string, color: string, vertical: boolean): THREE.CanvasTexture {
  const px = 16
  const c = document.createElement('canvas')
  c.width = Math.round((vertical ? columnWidth(1) : lineWidth(text, 1)) * px)
  c.height = Math.round((vertical ? columnHeight(text, 1) : lineHeight(1)) * px)
  const g = c.getContext('2d')!
  g.fillStyle = hex(PLATE_COLOR)
  g.fillRect(0, 0, c.width, c.height)
  g.font = `bold ${px}px monospace`
  g.textAlign = 'center'
  g.textBaseline = 'middle'
  const draw = () => {
    if (vertical) [...text].forEach((ch, i) => g.fillText(ch, c.width / 2, (0.8 + i) * px + 1))
    else g.fillText(text, c.width / 2, c.height / 2 + 1)
  }
  g.shadowColor = color
  g.shadowBlur = 6
  g.fillStyle = color
  g.strokeStyle = color
  g.lineWidth = 1
  g.strokeRect(1.5, 1.5, c.width - 3, c.height - 3)
  draw()
  g.shadowBlur = 0
  g.fillStyle = '#ffffff'
  g.globalAlpha = 0.5
  draw()
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  return t
}

/** Rows of tiles or slates, staggered, in white so each roof tints them. */
export function roofCovering(): THREE.CanvasTexture {
  const c = document.createElement('canvas')
  c.width = 16
  c.height = 16
  const g = c.getContext('2d')!
  g.fillStyle = '#ffffff'
  g.fillRect(0, 0, 16, 16)
  g.fillStyle = '#a8a8a8'
  for (let y = 0; y < 16; y += 4) {
    g.fillRect(0, y, 16, 1)
    for (let x = (y / 4) % 2 ? 2 : 0; x < 16; x += 4) g.fillRect(x, y + 1, 1, 3)
  }
  return pixelTexture(c, 3, 4)
}

/** Stripes round a parasol: its colour and white, in turn. */
export function stripes(color: string, count = 12): THREE.CanvasTexture {
  const c = document.createElement('canvas')
  c.width = count * 2
  c.height = 1
  const g = c.getContext('2d')!
  for (let x = 0; x < count; x++) {
    g.fillStyle = x % 2 ? '#f4f4f0' : color
    g.fillRect(x * 2, 0, 2, 1)
  }
  return pixelTexture(c)
}

/** A rooftop helipad: a dark square, a yellow ring and a white H. */
export function helipad(): THREE.CanvasTexture {
  const c = document.createElement('canvas')
  c.width = 32
  c.height = 32
  const g = c.getContext('2d')!
  g.fillStyle = '#3a3f44'
  g.fillRect(0, 0, 32, 32)
  g.strokeStyle = '#ffd319'
  g.lineWidth = 2
  g.beginPath()
  g.arc(16, 16, 12, 0, Math.PI * 2)
  g.stroke()
  g.fillStyle = '#f4f4f0'
  g.fillRect(10, 9, 3, 14)
  g.fillRect(19, 9, 3, 14)
  g.fillRect(13, 15, 6, 2)
  return pixelTexture(c)
}

/** The soft light a neon throws around it: its colour, fading out from the middle; drawn additively. */
export function glowTexture(color: string): THREE.CanvasTexture {
  const c = document.createElement('canvas')
  c.width = 64
  c.height = 32
  const g = c.getContext('2d')!
  g.scale(2, 1)
  const d = g.createRadialGradient(16, 16, 2, 16, 16, 16)
  d.addColorStop(0, color + 'cc')
  d.addColorStop(0.5, color + '55')
  d.addColorStop(1, color + '00')
  g.fillStyle = d
  g.fillRect(0, 0, 32, 32)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  return t
}
