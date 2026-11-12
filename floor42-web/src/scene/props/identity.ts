import * as THREE from 'three';
import { box, glow, markTarget, mat } from '../kit';
import { P } from '../palette';

/** What IAM mode said last: the daemon's health of it, and each agent's Keycloak client. */
export type IdentityHealth = 'OFF' | 'UP' | 'UNREACHABLE' | 'REFUSED_TO_START';
export type ClientState = 'ready' | 'missing' | 'revoked';

/** The server's footprint in tiles (width along x, depth along z, before it is turned): floorPlan.ts reserves it. */
export const IDENTITY_SERVER = { width: 0.6, depth: 0.7, height: 1.5 };

const LAMP: Record<IdentityHealth, number> = { OFF: 0x3a3f45, UP: 0x3bd16f, UNREACHABLE: 0xf2a93b, REFUSED_TO_START: 0xff4b3e };
const LED: Record<ClientState, number> = { ready: 0x3bd16f, missing: 0xf2a93b, revoked: 0x2a2d33 };
const PER_ROW = 6;

/**
 * The identity server in the compute room: Keycloak, a padlock on its door. Its lamp pulses with the state of IAM mode
 * and one LED per agent's Keycloak client says whether that client is ready, missing or revoked. It shows only what
 * the daemon measured (useOfficeScene feeds it); clicking it opens the identities panel (target kind `identity`).
 * The door faces +z, south, where the camera stands.
 */
export class IdentityServer {
  readonly root = new THREE.Group();
  private readonly leds = new THREE.Group();
  private readonly lampMaterial = glow(LAMP.OFF);
  private health: IdentityHealth = 'OFF';

  constructor() {
    const { width: w, depth: d, height: h } = IDENTITY_SERVER;
    box(this.root, [w, h, d], [0, 0, 0], mat(0x1b1d20));
    box(this.root, [w + 0.02, 0.05, d + 0.02], [0, h, 0], mat(P.steel, { metal: true }));
    box(this.root, [w - 0.08, h - 0.16, 0.02], [0, 0.08, d / 2], mat(0x2a2d33));
    // The padlock: a brass body with its keyhole, and the steel shackle above it.
    const brass = mat(0xd9a521, { metal: true });
    box(this.root, [0.24, 0.2, 0.06], [0, 0.92, d / 2 + 0.03], brass);
    box(this.root, [0.03, 0.07, 0.01], [0, 0.97, d / 2 + 0.065], mat(0x111111), false);
    const shackle = new THREE.Mesh(new THREE.TorusGeometry(0.075, 0.02, 6, 12, Math.PI), mat(P.steel, { metal: true }));
    shackle.position.set(0, 1.12, d / 2 + 0.03);
    shackle.castShadow = true;
    this.root.add(shackle);
    // The lamp under the padlock: steady when IAM mode is off, breathing otherwise.
    const lamp = box(this.root, [0.4, 0.05, 0.02], [0, 0.3, d / 2 + 0.01], this.lampMaterial, false);
    lamp.userData.noOutline = true;
    lamp.onBeforeRender = () => {
      const k = this.health === 'OFF' ? 1 : 0.55 + 0.45 * Math.sin(performance.now() / 350);
      this.lampMaterial.color.setHex(LAMP[this.health]).multiplyScalar(k);
    };
    this.root.add(this.leds);
    markTarget(this.root, { kind: 'identity', id: 'keycloak' });
  }

  /** The state to show: replaces the LEDs, one per client, in rows of six from the top. */
  setState(health: IdentityHealth, clients: ClientState[]): void {
    this.health = health;
    for (const child of [...this.leds.children]) {
      const led = child as THREE.Mesh;
      led.geometry.dispose();
      (led.material as THREE.Material).dispose();
      this.leds.remove(led);
    }
    const front = IDENTITY_SERVER.depth / 2 + 0.01;
    clients.forEach((c, i) => {
      const x = -0.2 + (i % PER_ROW) * 0.08;
      const y = 0.78 - Math.floor(i / PER_ROW) * 0.08;
      box(this.leds, [0.04, 0.035, 0.02], [x, y, front], glow(LED[c]), false).userData.noOutline = true;
    });
  }
}
