import * as THREE from 'three';
import { currentEra, P } from '../palette';
import { box, cylinder, glow, mat } from '../kit';

// ---- static furniture -----------------------------------------------------------------------------------------

/**
 * A computer on a desk, as the decade had it, its screen facing south (+z): a green-phosphor terminal in the eighties,
 * a beige CRT in the nineties, a silver flat screen and its tower in the two-thousands, a wide thin screen and a laptop
 * today. Returns the screen, which goes dark at night.
 */
function computer(g: THREE.Group, x: number, top: number, z: number): THREE.Mesh {
  const era = currentEra();
  const build = era === '1980s' ? terminal : era === '2000s' ? flatScreen : era === '2020s' ? wideScreen : crt;
  const screen = build(g, x, top, z);
  screen.userData.noOutline = true;
  return screen;
}

/** The eighties: a green-phosphor terminal and its keyboard. */
function terminal(g: THREE.Group, x: number, top: number, z: number): THREE.Mesh {
  box(g, [0.5, 0.44, 0.44], [x, top, z - 0.1], mat(P.beige));
  const screen = box(g, [0.34, 0.26, 0.02], [x, top + 0.09, z + 0.13], glow(0x33ff66));
  box(g, [0.5, 0.06, 0.2], [x, top, z + 0.3], mat(P.beigeDark));
  return screen;
}

/** The nineties: a beige CRT and its keyboard. */
function crt(g: THREE.Group, x: number, top: number, z: number): THREE.Mesh {
  box(g, [0.45, 0.34, 0.36], [x, top, z - 0.12], mat(P.beige));
  const screen = box(g, [0.36, 0.24, 0.02], [x, top + 0.05, z + 0.07], glow(P.cyan));
  box(g, [0.42, 0.03, 0.14], [x, top, z + 0.24], mat(P.beigeDark));
  return screen;
}

/** The two-thousands: a silver flat screen on its foot, a black keyboard, the tower under the desk. */
function flatScreen(g: THREE.Group, x: number, top: number, z: number): THREE.Mesh {
  box(g, [0.1, 0.12, 0.1], [x, top, z - 0.08], mat(0xc9d2d8));
  box(g, [0.48, 0.36, 0.04], [x, top + 0.12, z - 0.08], mat(0xc9d2d8));
  const screen = box(g, [0.42, 0.3, 0.01], [x, top + 0.15, z - 0.055], glow(0x8fd8ff));
  box(g, [0.44, 0.02, 0.14], [x, top, z + 0.22], mat(0x2a2d33));
  box(g, [0.2, 0.46, 0.44], [x + 0.5, 0, z - 0.05], mat(0xb5c0c8));
  box(g, [0.02, 0.04, 0.04], [x + 0.5, 0.38, z + 0.18], glow(0x2f8bff), false).userData.noOutline = true;
  return screen;
}

/** Today: a wide thin screen, an open laptop beside it, a flat keyboard. */
function wideScreen(g: THREE.Group, x: number, top: number, z: number): THREE.Mesh {
  box(g, [0.05, 0.2, 0.05], [x, top, z - 0.15], mat(0x2a2d33));
  box(g, [0.66, 0.3, 0.02], [x, top + 0.18, z - 0.15], mat(0x15171a));
  const screen = box(g, [0.62, 0.26, 0.005], [x, top + 0.2, z - 0.138], glow(0xbfe8ff));
  box(g, [0.34, 0.015, 0.22], [x - 0.35, top, z + 0.15], mat(0xc9d2d8));
  box(g, [0.34, 0.2, 0.01], [x - 0.35, top, z + 0.04], mat(0xc9d2d8)).rotation.x = -0.25;
  box(g, [0.3, 0.012, 0.1], [x + 0.1, top, z + 0.2], mat(0xe8ecee));
  return screen;
}

export function directorDesk(): THREE.Group {
  const g = new THREE.Group();
  box(g, [2.2, 0.08, 0.9], [0, 0.72, 0], mat(P.walnut));
  box(g, [2.2, 0.06, 0.06], [0, 0.66, 0.42], mat(P.walnutDark));
  box(g, [0.5, 0.7, 0.85], [-0.82, 0, 0], mat(P.walnutDark));
  for (const x of [0.95]) for (const z of [-0.35, 0.35]) box(g, [0.06, 0.72, 0.06], [x, 0, z], mat(P.chrome, { metal: true }));
  // The director's computer, of the decade, screen facing the chair (south).
  computer(g, 0.2, 0.8, 0.05);
  // Paper stack and a desk lamp.
  box(g, [0.3, 0.06, 0.22], [-0.65, 0.8, 0.1], mat(0xe8e2cf));
  cylinder(g, 0.04, 0.35, [0.85, 0.8, -0.25], mat(P.steelDark));
  box(g, [0.2, 0.08, 0.14], [0.8, 1.13, -0.2], mat(P.orange));
  // Director's chair.
  const chair = officeChair(P.redDark);
  chair.position.set(0.2, 0, 0.85);
  chair.rotation.y = Math.PI;
  g.add(chair);
  return g;
}

/** An agent's own desk in the open space: screen facing south, chair on the south side. */
export function workDesk(): THREE.Group {
  const g = new THREE.Group();
  box(g, [1.4, 0.07, 0.7], [0, 0.72, 0], mat(P.walnut));
  for (const x of [-0.64, 0.64]) for (const z of [-0.3, 0.3]) box(g, [0.05, 0.72, 0.05], [x, 0, z], mat(P.steelDark));
  computer(g, 0, 0.79, 0).userData.screen = true;
  const chair = officeChair(P.steel);
  chair.position.set(0, 0, 0.75);
  chair.rotation.y = Math.PI;
  g.add(chair);
  return g;
}

export function officeChair(color: number): THREE.Group {
  const g = new THREE.Group();
  cylinder(g, 0.05, 0.4, [0, 0, 0], mat(P.steelDark));
  box(g, [0.5, 0.06, 0.5], [0, 0.0, 0], mat(P.steelDark));
  box(g, [0.5, 0.1, 0.48], [0, 0.42, 0], mat(color));
  box(g, [0.5, 0.55, 0.1], [0, 0.5, -0.22], mat(color));
  return g;
}

/** One almond-shaped leaf, flat shaded; it grows along +z from its base. */
const leafShape = new THREE.SphereGeometry(1, 6, 4).scale(0.07, 0.015, 0.2).translate(0, 0, 0.2);

/**
 * A potted plant: a flared pot with its rim and soil, an inner ring of upright leaves and an outer ring arching out,
 * and a new shoot in the middle.
 */
export function plant(): THREE.Group {
  const g = new THREE.Group();
  const pot = new THREE.Mesh(new THREE.CylinderGeometry(0.22, 0.16, 0.38, 10), mat(P.pot));
  pot.position.y = 0.19;
  pot.castShadow = true;
  g.add(pot);
  cylinder(g, 0.24, 0.05, [0, 0.36, 0], mat(P.pot), 10);
  cylinder(g, 0.2, 0.02, [0, 0.39, 0], mat(0x3b2a1e), 10);
  leaves(g);
  const shoot = new THREE.Mesh(leafShape, mat(P.plant));
  shoot.position.set(0, 0.4, 0);
  shoot.rotation.x = -Math.PI / 2 + 0.1;
  shoot.scale.setScalar(0.9);
  g.add(shoot);
  return g;
}

/** The two rings of leaves: upright inside, arching out around them, every other outer leaf darker. */
function leaves(g: THREE.Group): void {
  const rings: [number, number, number, number][] = [
    // count, tilt from upright, length, turn
    [5, 0.45, 1.2, 0],
    [8, 1.0, 1.4, 0.4],
  ];
  rings.forEach(([count, tilt, length, turn], r) => {
    for (let i = 0; i < count; i++) {
      const stem = new THREE.Group();
      stem.position.set(0, 0.4, 0);
      stem.rotation.set(0, (i / count) * Math.PI * 2 + turn, 0);
      const leaf = new THREE.Mesh(leafShape, mat(r === 0 ? P.plant : i % 2 ? P.plant : P.plantDark));
      // From upright, leaning out by `tilt`; the leaf's own z points up the stem.
      leaf.rotation.x = -Math.PI / 2 + tilt;
      leaf.scale.setScalar(length);
      leaf.castShadow = true;
      stem.add(leaf);
      g.add(stem);
    }
  });
}

export function filingCabinet(): THREE.Group {
  const g = new THREE.Group();
  box(g, [0.55, 1.3, 0.6], [0, 0, 0], mat(P.steel));
  for (let i = 0; i < 4; i++) box(g, [0.02, 0.24, 0.5], [0.28, 0.1 + i * 0.3, 0], mat(P.steelDark));
  for (let i = 0; i < 4; i++) box(g, [0.03, 0.04, 0.16], [0.29, 0.22 + i * 0.3, 0], mat(P.chrome, { metal: true }));
  return g;
}

/** The lounge sofa, facing the room: idle agents sit here with a coffee. Decor, not a target. */
export function sofa(): THREE.Group {
  const g = new THREE.Group();
  const fabric = mat(0x7a3b22);
  const cushion = mat(0xa55a32);
  box(g, [2.3, 0.28, 0.75], [0, 0.06, 0], fabric);
  for (const x of [-0.75, 0, 0.75]) box(g, [0.72, 0.1, 0.62], [x, 0.34, 0.05], cushion);
  box(g, [2.3, 0.7, 0.22], [0, 0.34, -0.27], fabric);
  for (const x of [-1.07, 1.07]) box(g, [0.18, 0.62, 0.75], [x, 0.06, 0], fabric);
  for (const x of [-1.05, 1.05]) for (const z of [-0.3, 0.3]) box(g, [0.08, 0.06, 0.08], [x, 0, z], mat(P.walnutDark));
  return g;
}

export function coffeeMachine(): THREE.Group {
  const g = new THREE.Group();
  box(g, [0.7, 0.85, 0.5], [0, 0, 0], mat(P.walnutDark));
  box(g, [0.45, 0.55, 0.4], [0, 0.85, 0], mat(P.red));
  box(g, [0.25, 0.12, 0.02], [0, 1.18, 0.21], glow(P.amber));
  cylinder(g, 0.06, 0.1, [0, 0.95, 0.12], mat(0xf0f0f0), 6);
  return g;
}
