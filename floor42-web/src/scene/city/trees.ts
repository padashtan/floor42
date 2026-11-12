import * as THREE from 'three'
import { BARK, FIRS, LEAVES } from './colors'
import { Boxes, type CityContext, pick, shape } from './kit'
import type { Tree } from './streets'

/** A broadleaf crown: a few overlapping clumps, each its own shade of the tree's green. */
function crown(crowns: Boxes, t: Tree, rnd: () => number): void {
  const leaf = new THREE.Color(pick(LEAVES, rnd))
  const clumps = 3 + Math.floor(rnd() * 3)
  for (let k = 0; k < clumps; k++) {
    const r = (0.5 + rnd() * 0.3) * t.s
    const turn = new THREE.Quaternion().setFromEuler(new THREE.Euler(rnd() * 3, rnd() * 3, rnd() * 3))
    const lift = k === 0 ? 0.55 : rnd() * 0.5
    crowns.put(
      shape(t.x + (rnd() - 0.5) * 0.8 * t.s, t.y + (1.5 + lift) * t.s, t.z + (rnd() - 0.5) * 0.8 * t.s, r, r * 0.9, r, turn),
      leaf.clone().offsetHSL((rnd() - 0.5) * 0.03, 0, (rnd() - 0.4) * 0.08),
    )
  }
}

/** Trees: a trunk under a rounded crown of a few overlapping clumps, each its own green; firs as stacked cones. */
export function plantTrees(ctx: CityContext, trees: Tree[]): void {
  const { root, cache, rnd } = ctx
  const trunks = new Boxes()
  const crowns = new Boxes()
  const firs = new Boxes()
  for (const t of trees) {
    trunks.put(shape(t.x, t.y + 0.6 * t.s, t.z, t.s, 1.2 * t.s, t.s), pick(BARK, rnd))
    if (!t.fir) {
      crown(crowns, t, rnd)
      continue
    }
    const c = pick(FIRS, rnd)
    for (let k = 0; k < 3; k++) {
      const r = (1.5 - k * 0.4) * t.s
      firs.put(shape(t.x, t.y + (1.4 + k * 0.75) * t.s, t.z, r, 1.2 * t.s, r), c)
    }
  }
  const flat = cache.material('lambert-flat', () => new THREE.MeshLambertMaterial({ flatShading: true }))
  root.add(trunks.mesh(new THREE.CylinderGeometry(0.1, 0.15, 1, 6), cache.lambert()))
  root.add(crowns.mesh(new THREE.IcosahedronGeometry(1, 1), flat))
  root.add(firs.mesh(new THREE.ConeGeometry(0.5, 1, 8), flat))
}
