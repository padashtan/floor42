import * as THREE from 'three'
import { describe, expect, it } from 'vitest'
import { NavGrid, type Point } from './nav'
import { seeded, Wanderer } from './wander'

/** The wanderer on an empty floor: it walks to its goal, and waits for someone standing in its way. */
function walker(blocker: { at: Point | null }) {
  const root = new THREE.Group()
  const nav = new NavGrid(12, 6, [])
  const w = new Wanderer(
    {
      nav,
      root,
      speed: 2,
      seed: 5,
      plan: () => ({ to: { x: 8, z: 3 }, rest: 100 }),
      avoid: (out) => {
        if (!blocker.at) return 0
        out[0] = blocker.at
        return 1
      },
    },
    { x: 1, z: 3 },
  )
  return { root, w }
}

describe('the wanderer', () => {
  it('walks to its goal and rests there', () => {
    const { root, w } = walker({ at: null })
    for (let i = 0; i < 400; i++) w.update(0.05)
    expect(root.position.x).toBeCloseTo(8, 1)
    expect(w.walking).toBe(false)
  })

  it('waits short of someone standing in its way, then goes on once the way is clear', () => {
    const someone = { at: { x: 4, z: 3 } as Point | null }
    const { root, w } = walker(someone)
    for (let i = 0; i < 400; i++) w.update(0.05)
    expect(root.position.x, 'it came close but never touched the other').toBeGreaterThan(2)
    expect(root.position.x, 'stopped before 0.6 of the other').toBeLessThan(3.5)
    someone.at = null
    for (let i = 0; i < 400; i++) w.update(0.05)
    expect(root.position.x, 'then reached its goal').toBeCloseTo(8, 1)
  })

  it('does not wait for someone behind it', () => {
    const { root, w } = walker({ at: { x: 0.8, z: 3 } })
    for (let i = 0; i < 400; i++) w.update(0.05)
    expect(root.position.x).toBeCloseTo(8, 1)
  })

  it('the seeded generator is the same each time and spread over 0 to 1', () => {
    const a = seeded(9)
    const b = seeded(9)
    const xs = Array.from({ length: 200 }, () => a())
    expect(xs).toEqual(Array.from({ length: 200 }, () => b()))
    expect(Math.min(...xs)).toBeGreaterThanOrEqual(0)
    expect(Math.max(...xs)).toBeLessThan(1)
  })
})
