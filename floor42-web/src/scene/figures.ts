import * as THREE from 'three';
import { dress, holdables } from './figureBody';
import { markTarget } from './kit';
import { type DeskState, deskPose } from './deskLife';
import { easeOutBack, type Gesture, type Hands, turn } from './life';
import type { Glyph } from './mapping';
import type { Point } from './nav';
import { glyph } from './textures';

/**
 * `lounge`: seated on the sofa with a coffee, sipping now and then (idle agents). `play`: at the ping-pong table, a
 * paddle in hand (idle agents too).
 */
export type Pose = 'stand' | 'sit' | 'walk' | 'type' | 'lounge' | 'play';

const glyphTextures = new Map<string, THREE.Texture>();
function glyphTexture(kind: Glyph): THREE.Texture {
  if (!glyphTextures.has(kind)) glyphTextures.set(kind, glyph(kind));
  return glyphTextures.get(kind)!;
}

/**
 * Frees every emblem texture drawn so far. They are shared by all figures and swapped on a figure's emblem as its
 * status changes, so the one an emblem showed before is no longer in the scene graph when the scene is disposed: a
 * replaced scene frees them all here (the next scene uploads again those it shows).
 */
export function disposeGlyphs(): void {
  for (const t of glyphTextures.values()) t.dispose();
}

const GESTURE_SECONDS: Record<Gesture, number> = { celebrate: 1.8, frustrated: 1.6 };
const EMBLEM_SIZE = 0.46;

/**
 * A blocky low-poly collaborator. It only shows what it is told: an emblem derived from the agent status, a walk to
 * the station that status calls for, the hands of a tool that runs, and a gesture an event called for. It never
 * decides anything itself; what it adds alone is ambient life (breathing, looking around, a stretch now and then).
 */
export class Figure {
  /** Reduced motion (the director's system setting): no ambient motion, no gestures, no bounce. */
  static calm = false;

  readonly root = new THREE.Group();
  pose: Pose = 'stand';

  private readonly body = new THREE.Group();
  private readonly hips = new THREE.Group();
  private readonly head = new THREE.Group();
  private readonly legL = new THREE.Group();
  private readonly legR = new THREE.Group();
  private readonly armL = new THREE.Group();
  private readonly armR = new THREE.Group();
  private readonly marker: THREE.Sprite;
  private readonly mug = new THREE.Group();
  private readonly paddle = new THREE.Group();
  private readonly sheet = new THREE.Group();
  private path: Point[] = [];
  private arrival: { yaw: number; pose: Pose } = { yaw: 0, pose: 'stand' };
  /** The direction the figure turns towards; it eases there rather than snapping. */
  private heading = 0;
  private glyph: Glyph = 'idle';
  private stuck = false;
  private shownGlyph: Glyph | null = null;
  private glyphSince = -10;
  private speed = 1.6;
  private alertUntil = 0;
  private readonly ds: DeskState;
  private mugUp = false;
  private speakUntil = 0;
  private gesture: { kind: Gesture; start: number } | null = null;
  private readonly seed = Math.random() * 10;
  private nextStretch = 12 + Math.random() * 14;
  readonly id: string;

  constructor(id: string, uniform: number, look: number) {
    this.id = id;
    this.root.add(this.body);
    this.body.add(this.hips);
    this.hips.position.y = 0.72;
    dress({ hips: this.hips, head: this.head, legs: [this.legL, this.legR], arms: [this.armL, this.armR] }, uniform, look);
    holdables({ mug: this.mug, paddle: this.paddle, sheet: this.sheet }, this.armL, this.armR);
    this.ds = { armL: this.armL, armR: this.armR, head: this.head, busy: false, hands: 'type', resting: false, seed: this.seed };

    this.marker = new THREE.Sprite(new THREE.SpriteMaterial({ map: glyphTexture('idle'), depthTest: false }));
    this.marker.scale.set(EMBLEM_SIZE, EMBLEM_SIZE, 1);
    this.marker.position.y = 2.05;
    this.marker.renderOrder = 10;
    this.marker.userData.noOutline = true;
    this.root.add(this.marker);
    markTarget(this.root, { kind: 'agent', id });
  }

  /** True while the figure is still on its way. */
  get walking(): boolean {
    return this.path.length > 0
  }

  /** The rest of the walk, from where the figure stands; empty once arrived. */
  get route(): readonly Point[] {
    return this.path;
  }

  /** How the figure arrives at the end of its walk: the yaw and pose it takes there. */
  get arrivalPose(): { yaw: number; pose: Pose } {
    return this.arrival;
  }

  setGlyph(kind: Glyph): void {
    this.glyph = kind;
  }

  /** A "?" over the head while a stuck mission would put this agent to work; an alert still shows first. */
  setStuck(on: boolean): void {
    this.stuck = on;
  }

  /** Whether a tool of this agent runs now (status EXECUTING), and which hands it calls for. */
  setWork(busy: boolean, hands: Hands): void {
    this.ds.busy = busy;
    this.ds.hands = hands;
  }

  /** Idle at its desk between two sessions: hands off the keys, a sip of coffee now and then (deskLife.ts). */
  setResting(on: boolean): void {
    this.ds.resting = on;
  }

  /** Its voice was recorded at a committee or a meeting: it nods until `until`. */
  speak(until: number): void {
    this.speakUntil = until;
  }
  speaks(now: number): boolean {
    return now < this.speakUntil;
  }


  /** Shows the alert emblem for a moment, for instance when Floor42 denied or a tool failed one of this agent's actions. */
  alert(now: number, seconds = 2): void {
    this.alertUntil = now + seconds;
  }

  /** A short gesture an event called for. Under reduced motion it is not played: the emblem still tells. */
  play(kind: Gesture, now: number): void {
    if (Figure.calm) return;
    this.gesture = { kind, start: now };
  }

  /** Walks along the path, then faces `yaw` and takes `pose`. An empty path means: stay, but take the pose. */
  goTo(path: Point[], yaw: number, pose: Pose): void {
    this.path = [...path];
    this.arrival = { yaw, pose };
    // Long trips are walked faster so the figure is where the action happens when it happens (about two seconds).
    let length = 0;
    let from = { x: this.root.position.x, z: this.root.position.z };
    for (const p of this.path) {
      length += Math.hypot(p.x - from.x, p.z - from.z);
      from = p;
    }
    this.speed = Math.min(4.5, Math.max(1.6, length / 2));
    if (this.path.length === 0) {
      this.pose = pose;
      this.heading = yaw;
    }
  }

  placeAt(p: Point, yaw: number, pose: Pose): void {
    this.root.position.set(p.x, 0, p.z);
    this.root.rotation.y = yaw;
    this.heading = yaw;
    this.pose = pose;
    this.path = [];
  }

  update(t: number, dt: number): void {
    if (this.path.length > 0) this.followPath(dt);
    const calm = Figure.calm;
    // Turn towards the heading smoothly (a quarter turn takes about a sixth of a second).
    const d = turn(this.root.rotation.y, this.heading);
    this.root.rotation.y += calm ? d : d * (1 - Math.exp(-dt * 14));

    this.updateEmblem(t, calm);

    const walking = this.pose === 'walk';
    const swing = walking ? Math.sin(t * 9) * (calm ? 0.3 : 0.6) : 0;
    this.body.position.y = walking && !calm ? Math.abs(Math.sin(t * 9)) * 0.04 : 0;
    // Breathing: the chest rises a little, each figure on its own rhythm.
    this.body.scale.y = calm || walking ? 1 : 1 + Math.sin(t * 1.9 + this.seed) * 0.012;
    this.hips.rotation.set(0, 0, 0);
    this.head.rotation.set(0, 0, 0);
    this.poseLegs(swing);
    this.mug.visible = this.pose === 'lounge' || (this.pose === 'type' && this.mugUp)
    this.paddle.visible = this.pose === 'play'
    this.sheet.visible = this.pose === 'type' && this.ds.busy && this.ds.hands === 'read'
    this.poseArms(t, calm, swing);
    if (!calm && !walking) {
      this.lookAround(t);
      this.stretch(t);
      if (t < this.speakUntil) this.head.rotation.x += Math.sin(t * 7) * 0.07;
    }
    this.playGesture(t, this.seated);
  }

  /** Sitting on a chair, the sofa or at a desk: hips lower, legs forward. */
  private get seated(): boolean {
    return this.pose === 'sit' || this.pose === 'type' || this.pose === 'lounge';
  }

  /** Seated, legs forward; standing or walking, legs swinging by `swing`. */
  private poseLegs(swing: number): void {
    if (this.seated) {
      this.hips.position.y = 0.5;
      this.legL.rotation.x = this.legR.rotation.x = -Math.PI / 2;
    } else {
      this.hips.position.y = 0.72;
      this.legL.rotation.x = swing;
      this.legR.rotation.x = -swing;
    }
  }

  /** The arms as the pose has them: a paddle stroke, the keyboard, a sip of coffee, or swinging along. */
  private poseArms(t: number, calm: boolean, swing: number): void {
    if (this.pose === 'play') {
      // Ready stance, a stroke every 0.8 s: the same rhythm as the ball, which the scene moves.
      const stroke = Math.max(0, Math.sin(t * Math.PI * 2.5 + this.seed));
      this.armL.rotation.x = -0.4;
      this.armR.rotation.x = -0.7 - stroke * 0.8;
      this.hips.position.y = 0.66;
    } else if (this.pose === 'type') {
      this.mugUp = deskPose(this.ds, t, calm);
    } else if (this.pose === 'lounge') {
      // Mug held on the lap; a sip every few seconds, each figure on its own rhythm.
      const cycle = (t + this.seed * 3) % 7
      const sip = cycle < 1.6 ? Math.sin((cycle / 1.6) * Math.PI) : 0
      this.armL.rotation.x = -0.35
      this.armR.rotation.x = -0.9 - sip * 1.5
    } else {
      this.armL.rotation.x = -swing * 0.8;
      this.armR.rotation.x = swing * 0.8;
    }
    this.armL.rotation.z = 0;
    this.armR.rotation.z = 0;
  }

  /** The head turns slowly from side to side, more when the figure has nothing to type. */
  private lookAround(t: number): void {
    const typing = this.pose === 'type' && this.ds.busy;
    const range = typing ? 0.08 : this.pose === 'type' ? 0.25 : 0.5;
    const s = this.seed;
    this.head.rotation.y = range * Math.sin(t * 0.41 + s) * Math.sin(t * 0.17 + s * 2);
    this.head.rotation.x += 0.05 * Math.sin(t * 0.29 + s * 3);
  }

  /** Ambient: every 15 to 30 seconds, a figure with nothing running stretches its arms for a moment. */
  private stretch(t: number): void {
    if (this.ds.busy || this.pose === 'play' || this.pose === 'lounge') return;
    if (t > this.nextStretch + 1.8) this.nextStretch = t + 15 + Math.random() * 15;
    const k = (t - this.nextStretch) / 1.8;
    if (k < 0 || k > 1) return;
    const up = Math.sin(k * Math.PI);
    this.armL.rotation.x = this.armL.rotation.x * (1 - up) - 2.9 * up;
    this.armR.rotation.x = this.armR.rotation.x * (1 - up) - 2.9 * up;
    this.armL.rotation.z = -0.25 * up;
    this.armR.rotation.z = 0.25 * up;
    this.head.rotation.x -= 0.25 * up;
    this.body.scale.y += 0.03 * up;
  }

  private playGesture(t: number, seated: boolean): void {
    const g = this.gesture;
    if (!g) return;
    const k = (t - g.start) / GESTURE_SECONDS[g.kind];
    if (k >= 1 || k < 0) {
      this.gesture = null;
      return;
    }
    // In and out smoothly over the gesture.
    const env = Math.sin(Math.min(1, k * 4) * Math.PI / 2) * Math.sin(Math.min(1, (1 - k) * 4) * Math.PI / 2);
    if (g.kind === 'celebrate') {
      // Arms up, waving; two little hops (from the chair, a bounce on it).
      const hop = Math.abs(Math.sin(k * Math.PI * 3)) * (seated ? 0.08 : 0.22);
      this.body.position.y += hop * env;
      this.armL.rotation.x = -2.8 * env + this.armL.rotation.x * (1 - env);
      this.armR.rotation.x = -2.8 * env + this.armR.rotation.x * (1 - env);
      this.armL.rotation.z = (-0.35 + Math.sin(t * 16) * 0.2) * env;
      this.armR.rotation.z = (0.35 - Math.sin(t * 16) * 0.2) * env;
      this.hips.rotation.y = Math.sin(k * Math.PI * 4) * 0.25 * env;
      this.head.rotation.x = -0.2 * env;
    } else {
      // A hand to the forehead, the head shaking, the shoulders down.
      this.armR.rotation.x = -2.5 * env + this.armR.rotation.x * (1 - env);
      this.armR.rotation.z = -0.5 * env;
      this.armL.rotation.x = this.armL.rotation.x * (1 - env) + 0.1 * env;
      this.head.rotation.y = Math.sin(t * 17) * 0.3 * env;
      this.head.rotation.x = 0.2 * env;
      this.hips.rotation.x = 0.12 * env;
    }
  }

  /** The emblem pops when the state changes, then floats; waiting pulses, blocked shakes. */
  private updateEmblem(t: number, calm: boolean): void {
    const shown: Glyph = t < this.alertUntil ? 'alert' : this.stuck ? 'stuck' : this.glyph;
    if (shown !== this.shownGlyph) {
      (this.marker.material as THREE.SpriteMaterial).map = glyphTexture(shown);
      this.shownGlyph = shown;
      this.glyphSince = t;
    }
    const base = this.seated ? 1.8 : 2.05;
    if (calm) {
      this.marker.scale.set(EMBLEM_SIZE, EMBLEM_SIZE, 1);
      this.marker.position.set(0, base, 0);
      return;
    }
    const pop = easeOutBack((t - this.glyphSince) / 0.35);
    const pulse = shown === 'wait' ? 1 + 0.08 * Math.sin(t * 5) : 1;
    const s = EMBLEM_SIZE * Math.max(0.05, pop) * pulse;
    this.marker.scale.set(s, s, 1);
    const shake = shown === 'alert' ? Math.sin(t * 40) * 0.025 : 0;
    this.marker.position.set(shake, base + Math.sin(t * 3 + this.seed) * 0.03, 0);
  }

  private followPath(dt: number): void {
    const target = this.path[0];
    const dx = target.x - this.root.position.x;
    const dz = target.z - this.root.position.z;
    const dist = Math.hypot(dx, dz);
    if (dist < 0.04) {
      this.path.shift();
      if (this.path.length === 0) {
        this.pose = this.arrival.pose;
        this.heading = this.arrival.yaw;
      }
      return;
    }
    this.pose = 'walk';
    this.heading = Math.atan2(dx, dz);
    const step = Math.min(dist, dt * this.speed);
    this.root.position.x += (dx / dist) * step;
    this.root.position.z += (dz / dist) * step;
  }
}
