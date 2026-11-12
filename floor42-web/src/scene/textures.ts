import * as THREE from 'three';
import { P } from './palette';

// Tiny procedural textures, sampled with nearest filtering so each texel stays a hard pixel.

function canvas(w: number, h: number): [HTMLCanvasElement, CanvasRenderingContext2D] {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const ctx = c.getContext('2d')!;
  ctx.imageSmoothingEnabled = false;
  return [c, ctx];
}

export function pixelTexture(c: HTMLCanvasElement, repeat = 1): THREE.CanvasTexture {
  const t = new THREE.CanvasTexture(c);
  t.magFilter = THREE.NearestFilter;
  t.minFilter = THREE.NearestFilter;
  t.generateMipmaps = false;
  t.colorSpace = THREE.SRGBColorSpace;
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.repeat.set(repeat, repeat);
  return t;
}

const hex = (n: number) => '#' + n.toString(16).padStart(6, '0');

/** Metal deck plate: border seam, rivets in the corners, a little wear. */
export function deckPlate(base: number, dark: number, seam: number): THREE.CanvasTexture {
  const [c, g] = canvas(16, 16);
  g.fillStyle = hex(base);
  g.fillRect(0, 0, 16, 16);
  g.fillStyle = hex(dark);
  for (let i = 0; i < 10; i++) g.fillRect((i * 7) % 15, (i * 11) % 15, 1, 1);
  g.fillRect(3, 7, 10, 1);
  g.fillStyle = hex(seam);
  g.fillRect(0, 0, 16, 1);
  g.fillRect(0, 0, 1, 16);
  g.fillStyle = hex(dark);
  [[2, 2], [13, 2], [2, 13], [13, 13]].forEach(([x, y]) => g.fillRect(x, y, 1, 1));
  return pixelTexture(c);
}

/** 1970s computer-room raised floor tile. */
export function raisedTile(base: number, dark: number): THREE.CanvasTexture {
  const [c, g] = canvas(16, 16);
  g.fillStyle = hex(base);
  g.fillRect(0, 0, 16, 16);
  g.fillStyle = hex(dark);
  g.fillRect(0, 15, 16, 1);
  g.fillRect(15, 0, 1, 16);
  g.fillRect(6, 6, 4, 4);
  g.fillStyle = hex(base);
  g.fillRect(7, 7, 2, 2);
  return pixelTexture(c);
}

export function carpet(base: number, dark: number): THREE.CanvasTexture {
  const [c, g] = canvas(16, 16);
  g.fillStyle = hex(base);
  g.fillRect(0, 0, 16, 16);
  g.fillStyle = hex(dark);
  for (let y = 0; y < 16; y += 4) for (let x = (y / 4) % 2 ? 2 : 0; x < 16; x += 4) g.fillRect(x, y, 2, 2);
  return pixelTexture(c);
}

/** Cream wall panels with seams, a vent and a dark skirting, like a retro station corridor. */
export function wallPanel(): THREE.CanvasTexture {
  const [c, g] = canvas(16, 32);
  g.fillStyle = hex(P.wallPanel);
  g.fillRect(0, 0, 16, 32);
  g.fillStyle = hex(P.wallPanelDark);
  g.fillRect(0, 0, 1, 32);
  g.fillRect(0, 9, 16, 1);
  g.fillRect(4, 13, 8, 1);
  g.fillRect(4, 15, 8, 1);
  g.fillRect(4, 17, 8, 1);
  g.fillStyle = '#d4c9ac';
  g.fillRect(1, 10, 15, 1);
  g.fillStyle = hex(P.wallBase);
  g.fillRect(0, 27, 16, 5);
  g.fillStyle = '#3fe0f0';
  g.fillRect(0, 2, 16, 1);
  return pixelTexture(c);
}

export function hazardStripes(): THREE.CanvasTexture {
  const [c, g] = canvas(16, 16);
  g.fillStyle = '#1b1b1b';
  g.fillRect(0, 0, 16, 16);
  g.fillStyle = '#e8c547';
  for (let i = -16; i < 16; i += 8) {
    for (let y = 0; y < 16; y++) g.fillRect(i + y, y, 4, 1);
  }
  return pixelTexture(c);
}

/** A live screen: draw on it every few frames, upload with needsUpdate. */
export class Screen {
  readonly texture: THREE.CanvasTexture;
  readonly ctx: CanvasRenderingContext2D;
  readonly w: number;
  readonly h: number;

  constructor(w: number, h: number) {
    const [c, g] = canvas(w, h);
    this.w = w;
    this.h = h;
    this.ctx = g;
    this.texture = pixelTexture(c);
    this.texture.wrapS = this.texture.wrapT = THREE.ClampToEdgeWrapping;
  }

  commit(): void {
    this.texture.needsUpdate = true;
  }
}

/**
 * Status emblems drawn above an agent's head. Shape and colour both tell the state, so it reads without colour too:
 * a cyan square badge with a wheel mark (working), an amber speech bubble with a question mark (waiting for the
 * director), a red warning triangle (blocked: an action refused or failed), a small grey pebble with dots (idle).
 * `x` is the dark outline, `o` the state colour, `.` a dark mark; spaces stay transparent.
 */
const EMBLEMS: Record<'work' | 'wait' | 'alert' | 'idle' | 'stuck', string[]> = {
  // A round red badge with a question mark: work its mission would start has not started (the stall rule).
  stuck: [
    '   xxxxx   ',
    '  xooooox  ',
    ' xoo...oox ',
    'xoo.ooo.oox',
    'xooooo.ooox',
    'xoooo.oooox',
    'xoooo.oooox',
    'xooooooooox',
    ' xooo.ooox ',
    '  xooooox  ',
    '   xxxxx   ',
  ],
  work: [
    ' xxxxxxxxx ',
    'xooooooooox',
    'xoo.....oox',
    'xo..o.o..ox',
    'xo.o...o.ox',
    'xo...o...ox',
    'xo.o...o.ox',
    'xo..o.o..ox',
    'xoo.....oox',
    'xooooooooox',
    ' xxxxxxxxx ',
  ],
  wait: [
    ' xxxxxxxxx ',
    'xooooooooox',
    'xooo...ooox',
    'xoo.ooo.oox',
    'xooooo.ooox',
    'xoooo.oooox',
    'xooooooooox',
    'xoooo.oooox',
    ' xxxoooxxx ',
    '    xox    ',
    '     x     ',
  ],
  alert: [
    '     x     ',
    '    xox    ',
    '    xox    ',
    '   xo.ox   ',
    '   xo.ox   ',
    '  xoo.oox  ',
    '  xoo.oox  ',
    ' xooooooox ',
    ' xooo.ooox ',
    'xooooooooox',
    'xxxxxxxxxxx',
  ],
  idle: [
    '           ',
    '           ',
    '   xxxxx   ',
    '  xooooox  ',
    ' xooooooox ',
    ' xo.o.o.ox ',
    ' xooooooox ',
    '  xooooox  ',
    '   xxxxx   ',
    '           ',
    '           ',
  ],
};

export function glyph(kind: 'work' | 'wait' | 'alert' | 'idle' | 'stuck'): THREE.CanvasTexture {
  const rows = EMBLEMS[kind];
  const [c, g] = canvas(11, 11);
  const colors = { work: '#3fe0f0', wait: '#f2a93b', alert: '#ff4b3e', idle: '#8aa3ab', stuck: '#e8406a' };
  const ink: Record<string, string> = { x: '#071015', o: colors[kind], '.': '#071015' };
  rows.forEach((row, y) =>
    [...row].forEach((ch, x) => {
      if (!ink[ch]) return;
      g.fillStyle = ink[ch];
      g.fillRect(x, y, 1, 1);
    }),
  );
  const t = pixelTexture(c);
  t.wrapS = t.wrapT = THREE.ClampToEdgeWrapping;
  return t;
}
