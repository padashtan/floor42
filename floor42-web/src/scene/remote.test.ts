import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AgentStatus } from '../api/types'
import type { Crew } from './crew'
import { OfficeScene } from './OfficeScene'
import { applyTheme } from './palette'
import { dayKey, remoteSet, remoteToday, stripOf, tileState } from './remote'
import { DESKS } from './floorPlan'
import { fakeBrowser, QUIET } from './testing/fakeBrowser'

const ids = Array.from({ length: 2000 }, (_, i) => `agent-${i}`)

describe('who works from home today', () => {
  it('is stable within a day and about one agent in four over many', () => {
    const first = ids.filter((id) => remoteToday(id, '2026-11-04'))
    expect(ids.filter((id) => remoteToday(id, '2026-11-04'))).toEqual(first)
    expect(first.length / ids.length).toBeGreaterThan(0.2)
    expect(first.length / ids.length).toBeLessThan(0.3)
  })

  it('moves with the day', () => {
    const today = ids.filter((id) => remoteToday(id, '2026-11-04'))
    const tomorrow = ids.filter((id) => remoteToday(id, '2026-11-05'))
    expect(tomorrow).not.toEqual(today)
  })

  it('draws nobody when the director turned it off, and takes a forced list for the browser tests', () => {
    expect(remoteSet(ids, '2026-11-04', false).size).toBe(0)
    expect([...remoteSet(ids, '2026-11-04', true, ['agent-3'])]).toEqual(['agent-3'])
    expect(remoteSet(['a'], '2026-11-04', false, ['a']).size).toBe(0)
  })

  it('reads the local calendar day', () => {
    expect(dayKey(new Date(2026, 10, 4, 23, 59))).toBe('2026-11-04')
    expect(dayKey(new Date(2026, 0, 9, 0, 0))).toBe('2026-01-09')
  })
})

describe('what a webcam tile shows', () => {
  it('types only while a tool executes, reads during a review, is idle otherwise', () => {
    const state = (s: AgentStatus) => tileState(s)
    expect(state('EXECUTING')).toBe('typing')
    expect(state('REVIEWING')).toBe('reading')
    for (const s of ['WORKING', 'ASSIGNED', 'AVAILABLE', 'IN_MEETING', 'WAITING_FOR_DIRECTOR', 'PAUSED'] as const) {
      expect(state(s), s).toBe('idle')
    }
  })

  it('keeps four tiles and counts the rest', () => {
    expect(stripOf([1, 2, 3])).toEqual({ shown: [1, 2, 3], more: 0 })
    expect(stripOf([1, 2, 3, 4, 5, 6])).toEqual({ shown: [1, 2, 3, 4], more: 2 })
  })
})

describe('a remote agent in the office', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    applyTheme('1990s')
  })

  it('never sits at its desk nor walks to a meeting, and its chair is signed; the others stay', { timeout: 30_000 }, () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '2000s')
    const crew = (scene as unknown as { crew: Crew }).crew
    scene.setAgents([
      { id: 'home', name: 'Ada', status: 'WORKING', resident: true, remote: 'Remote' },
      { id: 'desk', name: 'Bob', status: 'WORKING', resident: true },
    ])
    browser.frames(120)
    expect(crew.figures.get('home')!.root.visible, 'not in the office').toBe(false)
    expect(crew.figures.get('desk')!.root.visible).toBe(true)
    expect([...crew.seating.remote.keys()], 'its empty chair is signed').toEqual(['home'])
    const chair = crew.seating.remote.get('home')!
    expect(DESKS.some((d) => Math.abs(d.x - chair.x) < 1 && Math.abs(d.z - chair.z) < 1.2), 'at a desk of the floor plan').toBe(true)
    // In a meeting: still not walking to a table (the strip shows it instead).
    scene.setAgents([
      { id: 'home', name: 'Ada', status: 'IN_MEETING', resident: true, remote: 'Remote' },
      { id: 'desk', name: 'Bob', status: 'WORKING', resident: true },
    ])
    browser.frames(120)
    expect(crew.figures.get('home')!.root.visible).toBe(false)
    // Back from home tomorrow: it is no longer signed.
    scene.setAgents([{ id: 'home', name: 'Ada', status: 'WORKING', resident: true }])
    expect(crew.seating.remote.size).toBe(0)
    scene.dispose()
  })
})
