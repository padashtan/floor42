import { cssColor, lookOf, uniformOf } from '../scene/identity'
import { HAIR, SKIN } from '../scene/palette'
import type { TileState } from '../scene/remote'

/** The size of a webcam tile's own canvas, in its pixels; the page scales it up without smoothing. */
export const TILE = { width: 64, height: 48 } as const
/** Frames per second of the tile's small motion (blink, breath, keys). */
export const TILE_FPS = 4

/** The colours of one agent's tile, made once: the same skin, hair and shirt as its figure in the office. */
export interface TileLook {
  skin: string
  hair: string
  shirt: string
}

export function tileLook(agentId: string): TileLook {
  const look = lookOf(agentId)
  return { skin: cssColor(SKIN[look % SKIN.length]), hair: cssColor(HAIR[look % HAIR.length]), shirt: cssColor(uniformOf(agentId)) }
}

const WALL = '#17353f'
const DARK = '#071a21'

function rect(c: CanvasRenderingContext2D, color: string, x: number, y: number, w: number, h: number): void {
  c.fillStyle = color
  c.fillRect(x, y, w, h)
}

/**
 * One frame of a pixel-art home office seen like a webcam: head and shoulders facing the camera, the desk behind.
 * Only the recorded state moves the hands (typing keys only while a tool executes, a sheet held up while reviewing);
 * the rest is ambient (a breath, a blink). Nothing here allocates: rectangles on a canvas, colours made once.
 */
export function drawTile(c: CanvasRenderingContext2D, look: TileLook, state: TileState, frame: number): void {
  const breath = frame % 8 < 4 ? 0 : 1
  rect(c, WALL, 0, 0, 64, 48)
  // The window and the plant of the room behind.
  rect(c, '#7fb7d4', 4, 4, 14, 11)
  rect(c, DARK, 10, 4, 1, 11)
  rect(c, DARK, 4, 9, 14, 1)
  rect(c, '#3f9f4a', 50, 8, 8, 10)
  rect(c, '#2b7a35', 53, 5, 3, 6)
  rect(c, '#7a4a2a', 51, 18, 6, 6)
  // The desk edge, and the shoulders in the shirt of the agent's colour.
  rect(c, '#5a3d28', 0, 42, 64, 6)
  rect(c, look.shirt, 10, 36 + breath, 44, 12)
  rect(c, look.shirt, 14, 33 + breath, 36, 3)
  rect(c, look.skin, 28, 30 + breath, 8, 6)
  // The head: skin, hair on top and at the sides, eyes and mouth.
  rect(c, look.skin, 22, 11 + breath, 20, 20)
  rect(c, look.hair, 21, 8 + breath, 22, 6)
  rect(c, look.hair, 21, 12 + breath, 2, 8)
  rect(c, look.hair, 41, 12 + breath, 2, 8)
  const blink = frame % 17 === 0
  // Working, the eyes glance down to the screen; reading, to the sheet at the side.
  const dx = state === 'reading' ? 1 : 0
  const dy = state === 'typing' ? 1 : 0
  rect(c, '#111', 27 + dx, 20 + breath + dy + (blink ? 1 : 0), 3, blink ? 1 : 2)
  rect(c, '#111', 34 + dx, 20 + breath + dy + (blink ? 1 : 0), 3, blink ? 1 : 2)
  rect(c, '#8a4b3a', 29, 27 + breath, 6, 1)
  if (state === 'typing') {
    rect(c, '#222', 12, 44, 40, 4)
    rect(c, look.skin, 18 + (frame % 2) * 2, 41, 7, 4)
    rect(c, look.skin, 39 - (frame % 2) * 2, 41, 7, 4)
  } else if (state === 'reading') {
    const lift = frame % 4 < 2 ? 0 : 1
    rect(c, '#f2efe6', 43, 25 - lift, 13, 16)
    rect(c, '#9aa7ad', 45, 28 - lift, 9, 1)
    rect(c, '#9aa7ad', 45, 31 - lift, 9, 1)
    rect(c, '#9aa7ad', 45, 34 - lift, 6, 1)
    rect(c, look.skin, 41, 38 - lift, 5, 4)
  }
}
