import * as THREE from 'three';
import { P } from '../palette';
import { box, glow, markTarget, mat } from '../kit';
import { officeChair } from './furniture';
import type { BoardColumns } from '../mapping';
import { Screen } from '../textures';

// ---- interactive pieces ---------------------------------------------------------------------------------------

/** Wall-mounted mission board. It mirrors the persisted tickets of the mission on display, nothing else. */
export class MissionBoard {
  readonly root = new THREE.Group();
  readonly screen = new Screen(64, 32);
  private columns: BoardColumns = { proposed: [], todo: [], doing: [], done: [], inReview: [], current: null, assignees: {} };

  constructor() {
    box(this.root, [3.2, 1.5, 0.12], [0, 0, 0], mat(P.steelDark));
    const face = box(this.root, [3.0, 1.3, 0.02], [0, 0.1, 0.07], new THREE.MeshBasicMaterial({ map: this.screen.texture }), false);
    face.userData.noOutline = true;
    box(this.root, [3.2, 0.06, 0.2], [0, -0.06, 0.04], mat(P.orange));
    markTarget(this.root, { kind: 'board', id: 'board-1' });
    this.draw(0);
  }

  setColumns(columns: BoardColumns): void {
    this.columns = columns;
  }

  draw(t: number): void {
    const g = this.screen.ctx;
    g.fillStyle = '#061a1f';
    g.fillRect(0, 0, 64, 32);
    // Proposed (waiting for the director) | to do | doing | done.
    const cols = [this.columns.proposed, this.columns.todo, this.columns.doing, this.columns.done];
    const blink = Math.floor(t * 2) % 2 === 0;
    cols.forEach((col, i) => {
      const x = 1 + i * 16;
      this.head(g, x, i, col.length, cols.reduce((n, c) => n + c.length, 0));
      col.slice(0, 4).forEach((id, j) => {
        if (i === 0) proposal(g, x, 8 + j * 6, blink);
        else this.card(g, x, 8 + j * 6, id, i === 3, blink, t);
      });
      if (col.length > 4) {
        g.fillStyle = HEADS[i];
        g.fillRect(x + 6, 31, 3, 1);
      }
    });
    this.screen.commit();
  }

  /** A column's head: its colour, how many tickets it holds (counted from the persisted tickets), and done's share. */
  private head(g: CanvasRenderingContext2D, x: number, i: number, count: number, total: number): void {
    g.fillStyle = HEADS[i];
    g.fillRect(x, 0, 14, 1);
    digits(g, Math.min(99, count), x + 1, 2);
    if (i !== 3) return;
    const share = total === 0 ? 0 : Math.round((count / total) * 6);
    g.fillStyle = '#1d3a2a';
    g.fillRect(x + 8, 4, 6, 2);
    g.fillStyle = '#6fe36a';
    g.fillRect(x + 8, 4, share, 2);
  }

  /** A ticket's card: blinking while in review; the one being worked on has a light running along it. */
  private card(g: CanvasRenderingContext2D, x: number, y: number, id: string, done: boolean, blink: boolean, t: number): void {
    const reviewing = this.columns.inReview.includes(id);
    g.fillStyle = reviewing && blink ? '#ffd27a' : done ? '#2f6b3a' : '#1d6f7a';
    g.fillRect(x, y, 14, 4);
    g.fillStyle = done ? '#6fe36a' : '#3fe0f0';
    g.fillRect(x + 1, y + 1, 5, 1);
    if (id !== this.columns.current) return;
    const p = Math.floor((t * 6) % 12);
    g.fillStyle = '#e8ffff';
    g.fillRect(x + 1 + p, y + 3, 2, 1);
  }
}

/** The heads of the columns: proposed, to do, doing, done. */
const HEADS = ['#f0a83f', '#6f8f99', '#3fe0f0', '#6fe36a'];

/** A proposal is an outline: nothing runs before the director approves it. */
function proposal(g: CanvasRenderingContext2D, x: number, y: number, blink: boolean): void {
  g.fillStyle = blink ? '#f0a83f' : '#8a5f22';
  g.fillRect(x, y, 14, 1);
  g.fillRect(x, y + 3, 14, 1);
  g.fillRect(x, y, 1, 4);
  g.fillRect(x + 13, y, 1, 4);
}

/** A 3x5 pixel font for the board's counts: each digit is five rows of three bits. */
const DIGITS = [
  [7, 5, 5, 5, 7], [2, 6, 2, 2, 7], [7, 1, 7, 4, 7], [7, 1, 3, 1, 7], [5, 5, 7, 1, 1],
  [7, 4, 7, 1, 7], [7, 4, 7, 5, 7], [7, 1, 2, 2, 2], [7, 5, 7, 5, 7], [7, 5, 7, 1, 7],
];

function digits(g: CanvasRenderingContext2D, n: number, x: number, y: number): void {
  [...String(n)].forEach((ch, k) =>
    DIGITS[Number(ch)].forEach((row, r) => {
      for (let b = 0; b < 3; b++) if (row & (4 >> b)) g.fillRect(x + k * 4 + b, y + r, 1, 1);
    }),
  );
}

/** What the archive wall draws (W6): commits counted by git, never invented. */
export interface ArchiveDrawing {
  /** False when the mission has no repository git can read: the wall stays dark. */
  available: boolean;
  /** Commits shown on the repository's own line, newest first (at most the server's limit). */
  trunk: number;
  /** One line per agent worktree: its colour and the commits it holds beyond the repository. */
  branches: { color: string; ahead: number }[];
}

/**
 * Wall-mounted archive (W6): the repository's line of commits on the left, and one coloured branch per agent forking
 * from its tip, a dot per commit it holds beyond it. It mirrors what git said when asked.
 */
export class ArchiveWall {
  readonly root = new THREE.Group();
  readonly screen = new Screen(48, 32);
  private drawing: ArchiveDrawing = { available: false, trunk: 0, branches: [] };
  private dirty = true;

  constructor() {
    box(this.root, [2.2, 1.4, 0.1], [0, 0, 0], mat(P.walnutDark));
    const face = box(this.root, [2.0, 1.2, 0.02], [0, 0.02, 0.06], new THREE.MeshBasicMaterial({ map: this.screen.texture }), false);
    face.userData.noOutline = true;
    box(this.root, [2.2, 0.06, 0.16], [0, 0.72, 0.03], mat(P.orange));
    markTarget(this.root, { kind: 'archive', id: 'archive-wall' });
    this.draw();
  }

  setArchive(d: ArchiveDrawing): void {
    this.drawing = d;
    this.dirty = true;
    this.draw();
  }

  private draw(): void {
    if (!this.dirty) return;
    this.dirty = false;
    const g = this.screen.ctx;
    g.fillStyle = '#16100a';
    g.fillRect(0, 0, 48, 32);
    const d = this.drawing;
    if (!d.available) {
      g.fillStyle = '#3a2a14';
      for (let i = 0; i < 4; i++) g.fillRect(6, 8 + i * 5, 20 + ((i * 9) % 14), 1);
      this.screen.commit();
      return;
    }
    // The repository's line, newest commit at the top.
    const trunk = Math.min(d.trunk, 7);
    g.fillStyle = '#c9a25a';
    g.fillRect(5, 3, 1, 27);
    for (let i = 0; i < trunk; i++) g.fillRect(4, 4 + i * 4, 3, 3);
    d.branches.slice(0, 6).forEach((b, i) => drawBranch(g, b, 5 + i * 4));
    this.screen.commit();
  }
}

/** An agent's branch at height `y`: it forks from the tip and carries a dot per commit beyond it. */
function drawBranch(g: CanvasRenderingContext2D, b: ArchiveDrawing['branches'][number], y: number): void {
  g.fillStyle = b.color;
  g.fillRect(6, 5, 1, Math.max(1, y - 5));
  g.fillRect(6, y, 6, 1);
  const dots = Math.max(0, Math.min(b.ahead, 9));
  for (let k = 0; k < dots; k++) g.fillRect(13 + k * 4, y - 1, 3, 3);
  if (b.ahead > 9) g.fillRect(46, y, 1, 1);
  if (b.ahead === 0) g.fillRect(13, y, 2, 1);
}

/** Glowing holographic meeting table, after the reference sprite: dark red frame, cyan top. */
export class HoloTable {
  readonly root = new THREE.Group();
  private readonly light: THREE.PointLight;
  /** The director's meeting (ADR-0047): a still amber ring and its post over the table, shown only while one is held. */
  private readonly meeting = new THREE.Group();
  private readonly holo = new Screen(40, 24);
  private lastDraw = -1;

  constructor() {
    box(this.root, [2.0, 0.55, 1.2], [0, 0.1, 0], mat(P.steelDark));
    box(this.root, [2.3, 0.12, 1.5], [0, 0.62, 0], mat(P.red));
    box(this.root, [2.36, 0.05, 1.56], [0, 0.6, 0], mat(P.redDark));
    const top = box(this.root, [2.0, 0.04, 1.2], [0, 0.74, 0], new THREE.MeshBasicMaterial({ map: this.holo.texture }), false);
    top.userData.noOutline = true;
    box(this.root, [1.6, 0.04, 1.0], [0, 0.04, 0], glow(0x1b6fae), false).userData.noOutline = true;
    this.light = new THREE.PointLight(0x3bb8ff, 6, 4, 1.5);
    this.light.position.set(0, 1.2, 0);
    this.root.add(this.light);
    const seats: [number, number, number][] = [
      [-0.8, -1.05, 0],
      [0.8, -1.05, 0],
      [-0.8, 1.05, Math.PI],
      [0.8, 1.05, Math.PI],
    ];
    for (const [x, z, r] of seats) {
      const c = officeChair(P.red);
      c.position.set(x, 0, z);
      c.rotation.y = r;
      this.root.add(c);
    }
    markTarget(this.root, { kind: 'table', id: 'table-1' });
    this.buildMeeting();
  }

  /** The meeting's sign: a still amber ring on its post over the table, hidden until a meeting is held. */
  private buildMeeting(): void {
    const ring = new THREE.Mesh(new THREE.TorusGeometry(0.28, 0.04, 6, 16), glow(0xffb000));
    ring.rotation.x = Math.PI / 2;
    ring.position.y = 1.75;
    ring.userData.noOutline = true;
    this.meeting.add(ring);
    box(this.meeting, [0.05, 0.8, 0.05], [0, 0.9, 0], glow(0xffb000), false).userData.noOutline = true;
    this.meeting.visible = false;
    this.root.add(this.meeting);
  }

  /** Still, so reduced motion needs nothing more: it is there or it is not. */
  setMeeting(on: boolean): void {
    this.meeting.visible = on;
  }

  meetingShown(): boolean {
    return this.meeting.visible;
  }

  update(t: number): void {
    const flicker = 0.9 + 0.1 * Math.sin(t * 7) * Math.sin(t * 3.1);
    this.light.intensity = 5 + flicker * 2;
    const frame = Math.floor(t * 10);
    if (frame === this.lastDraw) return;
    this.lastDraw = frame;
    // A slowly rotating "system map": nodes and links, with a sweeping scanline.
    const g = this.holo.ctx;
    g.fillStyle = '#1b6fae';
    g.fillRect(0, 0, 40, 24);
    g.fillStyle = '#2a8fd6';
    for (let y = 0; y < 24; y += 3) g.fillRect(0, y, 40, 1);
    const nodes: [number, number][] = [];
    for (let i = 0; i < 6; i++) {
      const a = t * 0.3 + (i / 6) * Math.PI * 2;
      nodes.push([Math.round(20 + Math.cos(a) * 13), Math.round(12 + Math.sin(a) * 7)]);
    }
    g.fillStyle = '#7fd8ff';
    nodes.forEach(([x, y], i) => {
      const [nx, ny] = nodes[(i + 1) % nodes.length];
      for (let s = 0; s <= 10; s++) g.fillRect(Math.round(x + ((nx - x) * s) / 10), Math.round(y + ((ny - y) * s) / 10), 1, 1);
    });
    g.fillStyle = '#e6fbff';
    nodes.forEach(([x, y]) => g.fillRect(x - 1, y - 1, 3, 3));
    g.fillRect(19, 11, 2, 2);
    g.fillStyle = 'rgba(230,251,255,0.5)';
    g.fillRect(0, frame % 24, 40, 1);
    this.holo.commit();
  }
}
