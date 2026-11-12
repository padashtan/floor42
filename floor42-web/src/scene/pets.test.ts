import * as THREE from 'three'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AgentStatus } from '../api/types'
import { OfficeScene } from './OfficeScene'
import { applyTheme } from './palette'
import type { Rect } from './nav'
import { fakeBrowser, QUIET } from './testing/fakeBrowser'
import { NavGrid } from './nav'
import { seeded, Wanderer } from './wander'

/** The cat and the dog, scaled against an agent, kept clear of everything, talking, sleeping in their baskets. */
interface Inside {
  mascots: {
    robot: THREE.Group
    cat: THREE.Group
    dog: THREE.Group
    basketRects: Rect[]
    onVoice: ((tag: string) => void) | null
    update(t: number, dt: number): void
    speech(): { id: string; text: string }[]
    states(): string
    radiusOf(o: THREE.Object3D): number
  }
  crew: {
    nav: { footprints: readonly Rect[] }
    figures: Map<string, { root: THREE.Object3D }>
    update(t: number, dt: number, ceremony: (t: number) => void): void
  }
  calm: boolean
  petVoice: { enabled: boolean }
}

const WORK: AgentStatus[] = ['WORKING', 'EXECUTING', 'ASSIGNED', 'REVIEWING', 'WAITING_FOR_DIRECTOR', 'WAITING_FOR_AUTHORIZATION']
const NAMES = ['Ada', 'Bo', 'Cy', 'Di', 'Ed', 'Flo']

const gap = (p: { x: number; z: number }, r: Rect): number => Math.hypot(Math.max(r.x0 - p.x, 0, p.x - r.x1), Math.max(r.z0 - p.z, 0, p.z - r.z1))
const same = (a: Rect, b: Rect): boolean => Math.abs(a.x0 - b.x0) + Math.abs(a.z0 - b.z0) + Math.abs(a.x1 - b.x1) + Math.abs(a.z1 - b.z1) < 1e-6

/** What stays on the floor the pets walk, baskets apart. */
const furniture = (inside: Inside): Rect[] => inside.crew.nav.footprints.filter((r) => !inside.mascots.basketRects.some((b) => same(r, b)))

function office(seed: number, agents = 6) {
  const browser = fakeBrowser()
  const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1990s', seed)
  const inside = scene as unknown as Inside
  const crowd = (round: number) =>
    NAMES.slice(0, agents).map((name, i) => ({ id: `dev-${i}`, name, status: WORK[(i + round) % WORK.length] }))
  scene.setAgents(crowd(0))
  return { browser, scene, inside, crowd }
}

// Each test builds a whole scene synchronously (CPU only): those on the default timeout take up to 2.1 s alone and
// 2.2 s in the full suite; the walk of five seeds keeps its own (5.4 s alone, 9.8 s in the full suite) (2026-11-03).
describe('the pets', { timeout: 30_000 }, () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    applyTheme('1990s')
  })

  it('are scaled against an agent: the cat to its knee, the dog a little higher', () => {
    const { scene, inside } = office(0, 1)
    const height = (o: THREE.Object3D): number => {
      o.updateMatrixWorld(true)
      const box = new THREE.Box3()
      o.traverse((m) => {
        if (m instanceof THREE.Mesh && (m.material as THREE.Material).visible && !m.userData.shadow) box.union(new THREE.Box3().setFromObject(m))
      })
      return box.max.y - box.min.y
    }
    const agent = height(inside.crew.figures.get('dev-0')!.root)
    const cat = height(inside.mascots.cat)
    const dog = height(inside.mascots.dog)
    expect(agent, 'an agent stands about 1.8 high').toBeGreaterThan(1.5)
    expect(cat, 'the cat comes to about the knee').toBeLessThan(agent * 0.26)
    expect(cat).toBeGreaterThan(agent * 0.15)
    expect(dog, 'the dog is a little taller').toBeGreaterThan(cat * 1.15)
    expect(dog).toBeLessThan(agent * 0.36)
    scene.dispose()
  })

  it('have a basket each, on free cells near a wall, clear of furniture and of each other', () => {
    const { scene, inside } = office(0)
    const [cat, dog] = inside.mascots.basketRects
    expect(inside.mascots.basketRects.length, 'both found a place').toBe(2)
    const others = furniture(inside)
    for (const b of [cat, dog]) {
      expect(others.filter((o) => b.x0 < o.x1 && b.x1 > o.x0 && b.z0 < o.z1 && b.z1 > o.z0), 'overlaps no furniture').toEqual([])
      const edge = Math.min(b.x0, b.z0, 13 - b.x1, 24 - b.z1, ...others.map((o) => gap({ x: (b.x0 + b.x1) / 2, z: (b.z0 + b.z1) / 2 }, o) - (b.x1 - b.x0) / 2))
      expect(edge, 'by a wall').toBeLessThan(0.3)
    }
    expect(cat.x1 <= dog.x0 || dog.x1 <= cat.x0 || cat.z1 <= dog.z0 || dog.z1 <= cat.z0, 'two baskets, side by side at most').toBe(true)
    scene.dispose()
  })

  it('keep clear of furniture, agents, each other and the robot at every tick, whatever the seed', () => {
    const tally = { run: 0, sleep: 0, rub: 0, sit: 0, play: 0, meow: 0, bark: 0, z: 0, voice: 0 }
    for (const seed of [0, 1, 2, 3, 4]) {
      vi.spyOn(Math, 'random').mockImplementation(seeded(900 + seed))
      const { scene, inside, crowd } = office(seed)
      const { mascots, crew } = inside
      const solid = furniture(inside)
      const baskets = mascots.basketRects
      const pets = [mascots.cat, mascots.dog]
      const roots = [mascots.robot, ...pets]
      const asleep = new Map<THREE.Object3D, number>()
      mascots.onVoice = () => tally.voice++
      let worst = Infinity
      let worstAt = ''
      const note = (d: number, what: string): void => {
        if (d < worst) {
          worst = d
          worstAt = what
        }
      }
      for (let i = 0; i < 12000; i++) {
        const t = i / 20
        if (i % 600 === 0) scene.setAgents(crowd(i / 600))
        crew.update(t, 0.05, () => {})
        mascots.update(t, 0.05)
        for (const p of pets) {
          const r = mascots.radiusOf(p)
          if (p.position.y < 0.02) {
            for (const f of solid) if (gap({ x: p.position.x, z: p.position.z }, f) < r - 0.02) throw new Error(`seed ${seed} tick ${i}: ${p.userData.target.id} inside furniture at ${p.position.x.toFixed(2)},${p.position.z.toFixed(2)}`)
            for (const f of crew.figures.values()) {
              if (!f.root.visible) continue
              const d = Math.hypot(f.root.position.x - p.position.x, f.root.position.z - p.position.z)
              note(d - r - 0.3, `tick ${i} ${p.userData.target.id} at ${p.position.x.toFixed(2)},${p.position.z.toFixed(2)} vs agent at ${f.root.position.x.toFixed(2)},${f.root.position.z.toFixed(2)}`)
            }
          }
        }
        for (const a of roots) {
          for (const b of roots) {
            if (a === b || a.position.y > 0.02 || b.position.y > 0.02) continue
            note(Math.hypot(a.position.x - b.position.x, a.position.z - b.position.z) - mascots.radiusOf(a) - mascots.radiusOf(b), `tick ${i} ${a.userData.target.id} vs ${b.userData.target.id}`)
          }
        }
        if (i % 10) continue
        const states = mascots.states()
        for (const k of ['run', 'rub', 'sit', 'play']) if (states.includes(`:${k}`)) tally[k as 'run']++
        const bubbles = mascots.speech()
        for (const b of bubbles) {
          if (b.text === 'Meow') tally.meow++
          if (b.text === 'Woof') tally.bark++
          if (b.text === 'z') tally.z++
        }
        pets.forEach((p, k) => {
          if (!states.split(';')[k].endsWith(':sleep')) return
          tally.sleep++
          const b = baskets[k]
          if (b && p.position.x > b.x0 - 0.05 && p.position.x < b.x1 + 0.05 && p.position.z > b.z0 - 0.05 && p.position.z < b.z1 + 0.05) asleep.set(p, (asleep.get(p) ?? 0) + 1)
        })
      }
      // Nobody ever stood inside anybody else's body (a tenth of slack: the radii are generous, and an agent that gets up and walks off needs a step to be left behind).
      expect(worst, `seed ${seed}: the closest anyone came to another, past touching (${worstAt})`).toBeGreaterThan(-0.1)
      expect(asleep.get(mascots.cat) ?? 0, `seed ${seed}: the cat slept in its basket`).toBeGreaterThan(0)
      expect(asleep.get(mascots.dog) ?? 0, `seed ${seed}: the dog slept in its basket`).toBeGreaterThan(0)
      scene.dispose()
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
    expect(tally.run, 'they sprinted').toBeGreaterThan(0)
    expect(tally.meow, 'the cat meowed in a bubble').toBeGreaterThan(0)
    expect(tally.bark, 'the dog barked in a bubble').toBeGreaterThan(0)
    expect(tally.z, 'sleepers show a z').toBeGreaterThan(0)
    expect(tally.voice, 'and made their sounds').toBeGreaterThan(0)
    expect(tally.rub + tally.sit + tally.play, 'they kept an agent company').toBeGreaterThan(0)
  }, 240000)

  it('leave an agent alone when it gets up and walks', () => {
    const { scene, inside, crowd } = office(0, 3)
    const { mascots, crew } = inside
    let kept = 0
    let left = 0
    for (let i = 0; i < 24000; i++) {
      const t = i / 20
      if (i % 300 === 0) scene.setAgents(crowd(i / 300))
      crew.update(t, 0.05, () => {})
      mascots.update(t, 0.05)
      const company = /:(rub|sit|play)/.test(mascots.states())
      if (company) kept++
      // A pet that keeps company never stands at an agent that is on the move.
      const walking = [...crew.figures.values()].some((f) => (f as unknown as { walking: boolean }).walking && f.root.visible)
      if (company && walking) left++
    }
    expect(kept, 'they did keep company').toBeGreaterThan(0)
    expect(left / Math.max(kept, 1), 'and left within moments of an agent getting up').toBeLessThan(0.5)
    scene.dispose()
  }, 240000)

  it('look for a place to flee to now and then, not every frame while someone threatens', () => {
    const root = new THREE.Group()
    // The threat follows the pet, so it never clears: only the cache keeps the searches down.
    const threat = (out: unknown[]) => {
      const p = root.position
      out[0] = { x: p.x - 0.6, z: p.z, r: 0.3, route: [{ x: p.x + 3, z: p.z }] }
      return 1
    }
    const w = new Wanderer(
      { nav: new NavGrid(12, 6, []), root, speed: 1, seed: 1, plan: () => null, avoid: threat as never },
      { x: 3, z: 3 },
    )
    for (let i = 0; i < 100; i++) w.update(0.05)
    expect(w.searches, 'five seconds of threat').toBeLessThan(30)
    expect(w.searches).toBeGreaterThan(0)
  })

  it('make no sound until asked, then a short synthesised one; none under reduced motion', () => {
    const made: string[] = []
    let closed = 0
    const handlers: unknown[] = []
    class FakeAudio {
      state = 'running'
      currentTime = 0
      destination = {}
      constructor() {
        made.push('context')
      }
      resume() {
        return { catch: (f: unknown) => (handlers.push(f), this) }
      }
      close() {
        closed++
        return { catch: (f: unknown) => (handlers.push(f), this) }
      }
      createOscillator() {
        made.push('oscillator')
        return { type: '', frequency: { setValueAtTime() {}, linearRampToValueAtTime() {} }, connect: (n: unknown) => n, start() {}, stop() {} }
      }
      createGain() {
        return { gain: { setValueAtTime() {}, linearRampToValueAtTime() {} }, connect: (n: unknown) => n }
      }
      createBiquadFilter() {
        return { type: '', frequency: { value: 0 }, connect: (n: unknown) => n }
      }
    }
    const { scene, inside, browser } = office(0, 1)
    ;(window as unknown as { AudioContext: unknown }).AudioContext = FakeAudio
    inside.mascots.onVoice!('meow')
    expect(made, 'off by default: nothing is even created').toEqual([])
    scene.setPetSound(true)
    inside.mascots.onVoice!('meow')
    expect(made, 'on, but no gesture yet: no context is made from the frame loop').toEqual([])
    scene.unlockPetSound()
    expect(made, 'the gesture makes it').toEqual(['context'])
    made.length = 0
    inside.mascots.onVoice!('meow')
    inside.mascots.onVoice!('bark')
    made.unshift('context')
    expect(made.filter((m) => m === 'context').length).toBe(1)
    expect(made.filter((m) => m === 'oscillator').length, 'one for the meow, two for the bark').toBe(3)
    scene.setPetSound(false)
    expect(closed, 'turning it off closes the context').toBe(1)
    made.length = 0
    scene.setPetSound(true)
    inside.calm = true
    inside.mascots.onVoice!('meow')
    expect(made, 'reduced motion: silent').toEqual([])
    expect(handlers.length, 'close() has a rejection handler').toBeGreaterThan(0)
    browser.frames(5)
    expect(inside.mascots.speech(), 'and no bubble').toEqual([])
    ;(window as unknown as { AudioContext: unknown }).AudioContext = class {
      constructor() {
        throw new Error('blocked')
      }
    }
    inside.calm = false
    expect(() => inside.mascots.onVoice!('meow'), 'a blocked audio context never breaks the scene').not.toThrow()
    scene.dispose()
  })
})
