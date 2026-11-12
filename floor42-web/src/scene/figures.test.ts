import * as THREE from 'three'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Figure, type Pose } from './figures'
import { digest, fakeDocument } from './testing/sceneDigest'

const POSES: Pose[] = ['stand', 'sit', 'walk', 'type', 'lounge', 'play']

/**
 * Characterisation of a figure's motion: for each pose, busy reading or idle, calm or not, with a gesture played, the
 * angle of every limb and the emblem's place a few moments in. Math.random is fixed: each figure's rhythm comes from it.
 */
describe('a figure', () => {
  beforeEach(() => {
    vi.stubGlobal('document', fakeDocument().document)
    vi.spyOn(Math, 'random').mockReturnValue(0.37)
  })
  afterEach(() => {
    Figure.calm = false
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('moves its limbs as its pose, its work and a gesture call for', () => {
    const r = (v: number) => Math.round(v * 1000) / 1000
    const angles = (o: THREE.Object3D) => `${r(o.rotation.x)},${r(o.rotation.y)},${r(o.rotation.z)}`
    const lines: string[] = []
    for (const calm of [false, true]) {
      Figure.calm = calm
      for (const pose of POSES) {
        for (const busy of [false, true]) {
          const f = new Figure('dev-1', 0x336699, 3)
          f.placeAt({ x: 1, z: 1 }, 0.5, pose)
          f.setWork(busy, busy ? 'read' : 'type')
          f.setGlyph(busy ? 'work' : 'wait')
          if (busy) f.play('celebrate', 1)
          else f.play('frustrated', 1)
          for (const t of [0.4, 1.3, 2.2, 16.9, 27.5]) {
            f.update(t, 0.05)
            const parts: string[] = []
            f.root.traverse((o) => {
              if (o instanceof THREE.Group || o instanceof THREE.Sprite) parts.push(`${angles(o)}@${r(o.position.y)}${o.visible ? '' : ' hidden'}`)
            })
            lines.push(`${calm ? 'calm' : 'live'} ${pose} ${busy ? 'busy' : 'idle'} t=${t}: ${parts.join(' ')} s${r(f.root.children[0].scale.y)}`)
          }
        }
      }
    }
    expect(lines.join('\n')).toMatchSnapshot()
  })

  it('walks a path, then takes the pose it arrives in', () => {
    const f = new Figure('dev-2', 0x993366, 1)
    f.placeAt({ x: 0, z: 0 }, 0, 'stand')
    f.goTo([{ x: 1, z: 0 }, { x: 1, z: 2 }], Math.PI, 'sit')
    const steps: string[] = []
    for (let i = 0; i < 40; i++) {
      f.update(i * 0.05, 0.05)
      steps.push(`${f.pose} ${Math.round(f.root.position.x * 100) / 100},${Math.round(f.root.position.z * 100) / 100} ${Math.round(f.root.rotation.y * 100) / 100}`)
    }
    expect({ steps, figure: digest(f.root) }).toMatchSnapshot()
  })
})
