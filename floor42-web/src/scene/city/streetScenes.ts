import * as THREE from 'three'
import type { FireSite } from './buildings'
import { block, BLOCK, CELL, type CityContext, hex, wheels } from './kit'
import { cloudTexture, stripes } from './textures'
import type { Flasher } from './traffic'

/** A puff of smoke rising from the fire: where it starts, and where it is in its rise (0 to 1). */
export interface Puff {
  sprite: THREE.Sprite
  base: THREE.Vector3
  phase: number
}

/** The fire truck, parked, its ladder up and its beacons flashing. */
function fireTruck(at: THREE.Vector3, tyres: { tyre: THREE.Material }, flashers: Flasher[]): THREE.Group {
  const truck = new THREE.Group()
  truck.position.copy(at)
  truck.add(block([4.2, 1.3, 1.5], [0, 0.65, 0], 0xc8231c))
  truck.add(block([1.2, 1.0, 1.5], [2.5, 0.5, 0], 0xc8231c))
  truck.add(block([4.4, 0.12, 1.55], [0, 0.9, 0], 0xf4f4f0))
  wheels(truck, 1.6, 0.75, 0.0, new THREE.CylinderGeometry(0.3, 0.3, 0.2, 10), tyres.tyre)
  truck.children.forEach((c) => {
    if (c.userData.wheel) c.position.y = 0.05
  })
  const ladder = block([6, 0.15, 0.6], [0, 0, 0], 0xc9d2d8)
  ladder.geometry.translate(3, 0, 0)
  ladder.position.set(-0.5, 1.8, 0)
  ladder.rotation.set(0, Math.PI / 2, 0.75)
  truck.add(ladder)
  for (const x of [-1.6, 1.6]) {
    const beacon = block([0.3, 0.2, 0.3], [x, 1.42, 0], 0xff3b30, true)
    truck.add(beacon)
    flashers.push({ mesh: beacon, colors: [0xff3b30, 0x330a08], rate: 5 + x })
  }
  return truck
}

/** A fire, a few blocks away: the truck parked, its ladder up, firefighters around it, smoke from a window. */
export function fire(ctx: CityContext, site: FireSite | null, tyres: { tyre: THREE.Material }, flashers: Flasher[]): Puff[] {
  const { root, cx, cz, y0 } = ctx
  const f = site ?? { x: cx + 20, z: cz + 30, h: 6 }
  root.add(fireTruck(new THREE.Vector3(f.x, y0 + 0.3, f.z + 3.6), tyres, flashers))
  for (let i = 0; i < 5; i++) {
    const man = new THREE.Group()
    man.add(block([0.5, 1.0, 0.35], [0, 0.5, 0], 0x2a2a2a))
    man.add(block([0.52, 0.15, 0.37], [0, 0.55, 0], 0xf2d13b))
    man.add(block([0.4, 0.25, 0.4], [0, 1.2, 0], 0xf2c230))
    man.position.set(f.x - 2 + i * 1.1, y0 + 0.3, f.z + 5.4 + (i % 2) * 0.6)
    man.rotation.y = Math.PI + (i - 2) * 0.3
    root.add(man)
  }
  const smokeShape = cloudTexture(7, '#8f8f8f')
  const puffs: Puff[] = []
  for (let i = 0; i < 8; i++) {
    const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: smokeShape, transparent: true, opacity: 0.6, depthWrite: false }))
    puffs.push({ sprite, base: new THREE.Vector3(f.x, y0 + 0.3 + f.h, f.z + 2.3), phase: i / 8 })
    root.add(sprite)
  }
  return puffs
}

/** Smoke rises, grows and fades from the window, puff after puff. */
export function smoke(puffs: Puff[], t: number): void {
  for (const { sprite, base, phase } of puffs) {
    const k = (t * 0.15 + phase) % 1
    sprite.position.set(base.x + k * 3, base.y + k * 12, base.z + Math.sin(k * 6) * 0.6)
    sprite.scale.setScalar(1.2 + k * 4)
    sprite.material.opacity = 0.65 * (1 - k)
  }
}

/** A hot-dog cart with its vendor and a striped parasol with its scalloped valance, a finial on top. */
function cart(color: number): THREE.Group {
  const g = new THREE.Group()
  g.add(block([1.4, 0.9, 0.8], [0, 0.45, 0], 0xc9d2d8))
  g.add(block([0.05, 1.2, 0.05], [0, 1.4, 0], 0x555555))
  const cloth = new THREE.MeshLambertMaterial({ map: stripes(hex(color)), side: THREE.DoubleSide })
  const parasol = new THREE.Mesh(new THREE.ConeGeometry(1.1, 0.45, 12, 1, true), cloth)
  parasol.position.set(0, 2.2, 0)
  const valance = new THREE.Mesh(new THREE.CylinderGeometry(1.1, 1.1, 0.14, 12, 1, true), cloth)
  valance.position.set(0, 1.91, 0)
  g.add(parasol, valance, block([0.08, 0.12, 0.08], [0, 2.48, 0], 0x555555))
  // Wheels, a counter top and the menu board.
  for (const s of [-1, 1]) {
    const w = new THREE.Mesh(new THREE.CylinderGeometry(0.22, 0.22, 0.06, 10), new THREE.MeshLambertMaterial({ color: 0x1a1a1a }))
    w.rotation.x = Math.PI / 2
    w.position.set(-0.4, 0.22, s * 0.43)
    g.add(w)
  }
  g.add(block([1.5, 0.06, 0.9], [0, 0.92, 0], 0x8a8f94))
  g.add(block([0.6, 0.35, 0.04], [-0.3, 0.55, 0.42], color))
  g.add(block([0.45, 1.0, 0.3], [0.9, 0.5, 0], 0xf4f4f0))
  g.add(block([0.32, 0.3, 0.32], [0.9, 1.15, 0], 0xe2b48c))
  return g
}

/** Hot-dog carts on corners near the tower, each with its vendor and parasol. */
export function carts(ctx: CityContext): void {
  for (const [dx, dz, color] of [
    [CELL / 2 + BLOCK / 2 - 0.8, CELL / 2 - BLOCK / 2 + 0.8, 0xf2a93b],
    [-CELL / 2 - BLOCK / 2 + 0.8, CELL / 2 + BLOCK / 2 - 0.8, 0x3a8fd8],
    [CELL / 2 + BLOCK / 2 - 0.8, -CELL / 2 - BLOCK / 2 + 0.8, 0xc8463c],
  ] as const) {
    const g = cart(color)
    g.position.set(ctx.cx + dx, ctx.y0 + 0.3, ctx.cz + dz)
    ctx.root.add(g)
  }
}
