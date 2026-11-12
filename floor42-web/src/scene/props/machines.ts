import * as THREE from 'three';
import { currentEra, P } from '../palette';
import { box, cylinder, glow, markTarget, mat } from '../kit';
import type { MachineState } from '../mapping';
import { Screen } from '../textures';

// ---- machines -------------------------------------------------------------------------------------------------

/**
 * A tool made physical. Its state changes only through {@link pulse}, called for events that record a real run.
 * ACTIVE fades back to IDLE; ERROR stays until the next successful run on the same machine.
 */
export abstract class Machine {
  readonly root = new THREE.Group();
  readonly id: string;
  private activeUntil = 0;
  private error = false;

  constructor(id: string) {
    this.id = id;
    markTarget(this.root, { kind: 'machine', id });
  }

  pulse(state: MachineState, label: string, now: number, seconds = 2.5): void {
    if (state === 'ERROR') {
      this.error = true;
    } else if (state === 'ACTIVE') {
      this.error = false;
      this.activeUntil = now + seconds;
    }
    this.onPulse?.(state, label);
  }

  stateAt(now: number): MachineState {
    if (this.error) return 'ERROR';
    return now < this.activeUntil ? 'ACTIVE' : 'IDLE';
  }

  protected onPulse?(state: MachineState, label: string): void;

  abstract update(t: number, dt: number): void;
}

const LED_COLORS = [P.green, P.amber, P.green, P.cyan, P.red];

/** 1970s tape-drive mainframe: stands for the build and test runner. */
export class Mainframe extends Machine {
  private readonly reels: THREE.Group[] = [];
  private readonly leds: { mesh: THREE.Mesh; color: THREE.Color; phase: number }[] = [];
  private readonly beacon: THREE.Mesh;
  private readonly beaconLight: THREE.PointLight;

  constructor() {
    super('mainframe-1');
    // Tape reels until the nineties; later, cabinets of blades whose lights alone show the work.
    const era = currentEra();
    for (let c = 0; c < 3; c++) this.cabinet(c, era);
    this.beacon = cylinder(this.root, 0.16, 0.22, [0.95, 2.12, 0.1], glow(0x331111), 8);
    this.beaconLight = new THREE.PointLight(P.alarm, 0, 7, 1.2);
    this.beaconLight.position.set(0.95, 2.5, 0.6);
    this.root.add(this.beaconLight);
  }

  /** The `c`-th of three cabinets, left to right: its shell, its tape reels in the eighties and nineties, its lights. */
  private cabinet(c: number, era: string): void {
    const reels = era === '1980s' || era === '1990s';
    const shell = era === '2000s' ? 0x15171c : era === '2020s' ? 0x202326 : null;
    const x = (c - 1) * 0.95;
    box(this.root, [0.9, 2.0, 0.7], [x, 0, 0], mat(shell ?? (c === 1 ? P.beige : P.wallPanel)));
    box(this.root, [0.92, 0.12, 0.72], [x, 2.0, 0], mat(P.steelDark));
    box(this.root, [0.9, 0.08, 0.72], [x, 1.55, 0], mat(era === '2000s' ? 0x2f8bff : era === '2020s' ? 0x9fd8b8 : P.orange));
    box(this.root, [0.7, 0.75, 0.02], [x, 0.75, 0.36], mat(P.screenBg));
    for (let r = 0; r < (reels ? 2 : 0); r++) {
      const spin = new THREE.Group();
      spin.position.set(x + (r ? 0.17 : -0.17), 1.12, 0.38);
      const reel = new THREE.Mesh(new THREE.CylinderGeometry(0.15, 0.15, 0.04, 10), mat(P.steelDark));
      reel.rotation.x = Math.PI / 2;
      spin.add(reel);
      const spoke = new THREE.Mesh(new THREE.BoxGeometry(0.26, 0.04, 0.02), mat(P.chrome, { metal: true }));
      spoke.position.z = 0.03;
      spin.add(spoke);
      this.root.add(spin);
      this.reels.push(spin);
    }
    for (let i = 0; i < 12; i++) {
      const color = new THREE.Color(LED_COLORS[(i + c * 3) % LED_COLORS.length]);
      const led = box(this.root, [0.06, 0.05, 0.02], [x - 0.27 + (i % 6) * 0.11, 0.85 + Math.floor(i / 6) * 0.12, 0.37], glow(color.getHex()), false);
      led.userData.noOutline = true;
      this.leds.push({ mesh: led, color, phase: Math.random() * 10 });
    }
  }

  update(t: number, dt: number): void {
    const state = this.stateAt(t);
    const speed = state === 'ACTIVE' ? 9 : 0;
    this.reels.forEach((r, i) => (r.rotation.z -= dt * speed * (i % 2 ? 1.3 : 1)));
    for (const led of this.leds) {
      const m = led.mesh.material as THREE.MeshBasicMaterial;
      if (state === 'ERROR') {
        m.color.setHex(Math.floor(t * 4) % 2 ? P.alarm : 0x330d0a);
      } else {
        const rate = state === 'ACTIVE' ? 9 : 1.2;
        const on = Math.sin(t * rate + led.phase * 7) > (state === 'ACTIVE' ? -0.2 : 0.3);
        m.color.copy(led.color).multiplyScalar(on ? 1 : 0.18);
      }
    }
    const alarm = state === 'ERROR' && Math.floor(t * 3) % 2 === 0;
    (this.beacon.material as THREE.MeshBasicMaterial).color.setHex(alarm ? P.alarm : state === 'ERROR' ? 0x661a14 : 0x331111);
    this.beaconLight.intensity = state === 'ERROR' ? (alarm ? 30 : 8) : 0;
  }
}

/** Operator console with a CRT: stands for the shell / terminal tool. */
export class TerminalConsole extends Machine {
  private readonly screen = new Screen(32, 24);
  private readonly glowLight: THREE.PointLight;
  private lines: string[] = ['$'];
  private lastDraw = -1;

  constructor() {
    super('terminal-1');
    box(this.root, [0.8, 0.75, 1.4], [0, 0, 0], mat(P.steel));
    box(this.root, [0.82, 0.06, 1.42], [0, 0.75, 0], mat(P.steelDark));
    const panel = box(this.root, [0.5, 0.06, 1.3], [-0.12, 0.8, 0], mat(P.beigeDark));
    panel.rotation.z = 0.35;
    for (let i = 0; i < 8; i++) box(this.root, [0.05, 0.05, 0.05], [0.05, 0.86, -0.5 + i * 0.14], glow(i % 3 ? P.amber : P.green), false).userData.noOutline = true;
    box(this.root, [0.6, 0.55, 0.65], [-0.15, 0.81, 0], mat(P.beige));
    const face = box(this.root, [0.02, 0.4, 0.5], [0.16, 0.89, 0], new THREE.MeshBasicMaterial({ map: this.screen.texture }), false);
    face.userData.noOutline = true;
    this.glowLight = new THREE.PointLight(P.green, 2, 2.5, 1.5);
    this.glowLight.position.set(0.7, 1.2, 0);
    this.root.add(this.glowLight);
  }

  protected override onPulse(state: MachineState, label: string): void {
    if (state === 'ACTIVE') this.lines = [...this.lines.slice(-3), label];
    if (state === 'ERROR') this.lines = [...this.lines.slice(-3), 'ERR'];
  }

  update(t: number): void {
    const state = this.stateAt(t);
    const frame = Math.floor(t * 8);
    if (frame === this.lastDraw) return;
    this.lastDraw = frame;
    const g = this.screen.ctx;
    const ink = state === 'ERROR' ? '#ff4b3e' : state === 'ACTIVE' ? '#6fe36a' : '#2f8a3a';
    g.fillStyle = '#061a0c';
    g.fillRect(0, 0, 32, 24);
    g.fillStyle = ink;
    // Text is drawn as pixel bars: legible as activity, not as words, at this resolution.
    this.lines.slice(-4).forEach((line, i) => {
      const len = Math.min(28, 4 + line.length);
      g.fillRect(2, 3 + i * 4, len, 2);
    });
    if (state === 'ACTIVE') {
      for (let i = 0; i < 5; i++) g.fillRect(2 + ((frame * 3 + i * 7) % 26), 20, 2, 1);
    } else if (frame % 2 === 0) {
      g.fillRect(2, 20, 2, 2);
    }
    this.screen.commit();
    this.glowLight.color.setHex(state === 'ERROR' ? P.alarm : P.green);
    this.glowLight.intensity = state === 'IDLE' ? 0.8 : 2.5;
  }
}

/** Card catalogue with a microfiche reader: stands for reading files and searching the repository. */
export class ArchiveReader extends Machine {
  private readonly screen = new Screen(24, 18);
  private readonly drawer: THREE.Mesh;
  private readonly lamp: THREE.Mesh;
  private lastDraw = -1;

  constructor() {
    super('archive-1');
    box(this.root, [0.9, 1.0, 0.6], [0, 0, 0], mat(P.walnut));
    for (let r = 0; r < 3; r++) {
      for (let c = 0; c < 3; c++) box(this.root, [0.24, 0.22, 0.02], [-0.28 + c * 0.28, 0.12 + r * 0.28, 0.31], mat(P.walnutDark));
    }
    this.drawer = box(this.root, [0.24, 0.22, 0.3], [0, 0.4, 0.2], mat(P.beige));
    box(this.root, [0.7, 0.5, 0.5], [0, 1.0, -0.02], mat(P.beige));
    const face = box(this.root, [0.5, 0.36, 0.02], [0, 1.06, 0.24], new THREE.MeshBasicMaterial({ map: this.screen.texture }), false);
    face.userData.noOutline = true;
    this.lamp = box(this.root, [0.08, 0.06, 0.02], [0.28, 1.04, 0.24], glow(0x1b3a1b), false);
    this.lamp.userData.noOutline = true;
  }

  update(t: number): void {
    const state = this.stateAt(t);
    // The drawer slides out while a file is being read.
    const target = state === 'ACTIVE' ? 0.42 : 0.2;
    this.drawer.position.z += (target - this.drawer.position.z) * 0.2;
    (this.lamp.material as THREE.MeshBasicMaterial).color.setHex(state === 'ACTIVE' ? P.green : state === 'ERROR' ? P.alarm : 0x1b3a1b);
    const frame = Math.floor(t * 6);
    if (frame === this.lastDraw) return;
    this.lastDraw = frame;
    const g = this.screen.ctx;
    g.fillStyle = '#1a1205';
    g.fillRect(0, 0, 24, 18);
    g.fillStyle = state === 'ERROR' ? '#ff4b3e' : state === 'ACTIVE' ? '#f2a93b' : '#5a4012';
    const scroll = state === 'ACTIVE' ? frame : 0;
    for (let i = 0; i < 5; i++) g.fillRect(2, 2 + ((i * 3 + scroll) % 15), 6 + ((i * 7) % 14), 1);
    this.screen.commit();
  }
}

/** Line printer: a deliverable leaving the workshop for review prints a page. */
export class LinePrinter extends Machine {
  private readonly paper: THREE.Mesh;
  private readonly lamp: THREE.Mesh;

  constructor() {
    super('printer-1');
    box(this.root, [1.0, 0.8, 0.7], [0, 0, 0], mat(P.beige));
    box(this.root, [1.02, 0.1, 0.72], [0, 0.8, 0], mat(P.orange));
    box(this.root, [0.8, 0.02, 0.5], [0, 0.9, 0.05], mat(0xeeeadc));
    this.paper = box(this.root, [0.8, 0.35, 0.02], [0, 0.9, -0.2], mat(0xeeeadc));
    this.lamp = box(this.root, [0.3, 0.06, 0.02], [0.25, 0.55, 0.36], glow(0x3a2a08), false);
    this.lamp.userData.noOutline = true;
    box(this.root, [1.1, 0.4, 0.4], [0, 0, 0.6], mat(0xd8d2bf));
  }

  update(t: number): void {
    const state = this.stateAt(t);
    const active = state === 'ACTIVE';
    // Paper rises line by line while printing.
    const height = active ? 0.35 + ((t * 0.4) % 0.4) : 0.35;
    this.paper.scale.y = height / 0.35;
    this.paper.position.y = 0.9 + height / 2;
    (this.lamp.material as THREE.MeshBasicMaterial).color.setHex(active && Math.floor(t * 6) % 2 ? P.amber : 0x3a2a08);
  }
}
