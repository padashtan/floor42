import * as THREE from 'three'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OfficeScene } from './OfficeScene'
import { applyTheme, P, THEME_IDS } from './palette'
import { fakeBrowser, QUIET } from './testing/fakeBrowser'

/** The part of an OfficeScene this test reaches into: the glass elevator's group. */
interface Inside {
  elevator: { root: THREE.Object3D }
}

/**
 * D17: the elevator was built before the decade's palette was put in force, so it kept the previous decade's steel.
 * Eight scenes built synchronously (CPU only, no timer, no I/O): 2.2 to 2.3 s alone, 4.0 to 5.1 s in the full suite,
 * where Vitest's 5 s default timed out once in three runs (2026-11-03).
 */
describe('the glass elevator wears the decade palette', { timeout: 30_000 }, () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    applyTheme('1990s')
  })

  it('has the steel, dark steel and chrome of the decade shown, for every decade, whichever was shown before', () => {
    const browser = fakeBrowser()
    for (const theme of [...THEME_IDS, ...THEME_IDS].reverse()) {
      const scene = new OfficeScene(browser.canvas(), undefined, QUIET, theme)
      const colours = new Set<number>()
      ;(scene as unknown as Inside).elevator.root.traverse((o) => {
        const m = (o as THREE.Mesh).material as THREE.MeshStandardMaterial | undefined
        if (m?.isMeshStandardMaterial) colours.add(m.color.getHex())
      })
      expect([...colours].sort(), theme).toEqual([P.steel, P.steelDark, P.chrome].sort())
      scene.dispose()
    }
  })
})
