import * as THREE from 'three';
import { currentEra, P } from '../palette';
import { box, glow, markTarget, mat } from '../kit';

// ---- fittings: racks, lights, pipes and cable trays ---------------------------------------------------------------

/** Decorative rack: blinks, but is explicitly not linked to any tool, and says so when selected. */
export function serverRack(): THREE.Group {
  const g = new THREE.Group();
  // Each decade's servers: beige cabinets, dark grey racks, black 1U servers with blue lights, matte black with white.
  const look = {
    '1980s': { body: 0xb9ad8f, slot: 0x8f8670, leds: [0xff4b3e, 0xf2a93b] },
    '1990s': { body: 0x1d2b31, slot: 0x2a3a40, leds: [P.green, P.cyan] },
    '2000s': { body: 0x15171c, slot: 0x2a2d33, leds: [0x2f8bff, 0x8fd8ff] },
    '2020s': { body: 0x1b1d20, slot: 0x24272b, leds: [0xffffff, 0x9fd8b8] },
  }[currentEra()];
  box(g, [0.7, 1.9, 0.8], [0, 0, 0], mat(look.body));
  box(g, [0.72, 0.06, 0.82], [0, 1.9, 0], mat(P.steel));
  for (let i = 0; i < 7; i++) {
    box(g, [0.6, 0.18, 0.02], [0, 0.2 + i * 0.23, 0.41], mat(i % 3 === 0 ? P.steelDark : look.slot));
    const led = box(g, [0.05, 0.04, 0.02], [0.22, 0.27 + i * 0.23, 0.42], glow(look.leds[i % 2]), false);
    led.userData.noOutline = true;
    led.userData.blink = 0.5 + Math.random() * 2;
  }
  markTarget(g, { kind: 'decor', id: 'server-rack' });
  return g;
}

/** Wall lamp: a steel housing with a glowing underside, lighting the wall below it. */
export function lightStrip(length: number): THREE.Group {
  const g = new THREE.Group();
  box(g, [length, 0.14, 0.16], [0, 0, 0.02], mat(P.steelDark));
  box(g, [length - 0.08, 0.04, 0.12], [0, -0.03, 0.04], glow(0xbff8ff), false).userData.noOutline = true;
  const light = new THREE.PointLight(0xbff8ff, 1.6, 2.6, 1.6);
  light.position.set(0, -0.3, 0.4);
  g.add(light);
  return g;
}

export function pipeRun(length: number, color: number): THREE.Group {
  const g = new THREE.Group();
  const pipe = new THREE.Mesh(new THREE.CylinderGeometry(0.07, 0.07, length, 6), mat(color, { metal: true }));
  pipe.rotation.z = Math.PI / 2;
  pipe.castShadow = true;
  g.add(pipe);
  for (let x = -length / 2 + 0.5; x < length / 2; x += 1.5) box(g, [0.1, 0.22, 0.2], [x, -0.1, -0.05], mat(P.steelDark));
  return g;
}

export function cableTray(length: number): THREE.Group {
  const g = new THREE.Group();
  box(g, [length, 0.05, 0.3], [0, 0, 0], mat(0x1b1b1b), false);
  box(g, [length, 0.03, 0.06], [0, 0.05, 0.08], mat(P.hazard), false);
  box(g, [length, 0.03, 0.06], [0, 0.05, -0.08], mat(P.red), false);
  return g;
}
