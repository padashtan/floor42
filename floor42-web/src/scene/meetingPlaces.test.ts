import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AgentStatus } from '../api/types'
import { STATIONS } from './floorPlan'
import { placesOf, signLit, stationsAt } from './meetingPlaces'
import { OfficeScene } from './OfficeScene'
import { applyTheme } from './palette'
import { fakeBrowser, QUIET } from './testing/fakeBrowser'

/** Eight attendees, as at a director's meeting larger than the meeting table's six chairs. */
const IDS = ['a1', 'a2', 'a3', 'a4', 'a5', 'a6', 'a7', 'a8']
const crew = (status: AgentStatus) => IDS.map((id) => ({ id, name: id.toUpperCase(), status }))

interface Inside {
  crew: {
    agents: { id: string; status: AgentStatus }[]
    signShown(place: 'TABLE' | 'CONFERENCE'): boolean
    /** Where the agent's status and meeting send it: the slot it walks to (or is placed at). */
    slotOf(a: { id: string; status: AgentStatus }): { x: number; z: number }
  }
}

const near = (p: { x: number; z: number }, slots: { x: number; z: number }[]) =>
  slots.some((s) => Math.hypot(s.x - p.x, s.z - p.z) < 0.05)

describe('the director’s meeting in the scene (ADR-0049)', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    applyTheme('1990s')
  })

  it('reads where each attendee sits and which sign is lit', () => {
    const meetings = [
      { place: 'CONFERENCE' as const, attendees: ['a1', 'a2'] },
      { place: 'TABLE' as const, attendees: ['a2', 'a3'] },
    ]
    expect([...placesOf(meetings)]).toEqual([
      ['a1', 'CONFERENCE'],
      ['a2', 'CONFERENCE'],
      ['a3', 'TABLE'],
    ])
    expect(stationsAt('TABLE')).toEqual(['table-seat', 'meeting-stand'])
    expect(signLit(meetings, new Set(['a3']), 'TABLE')).toBe(true)
    expect(signLit(meetings, new Set(['a3']), 'CONFERENCE')).toBe(false)
    expect(STATIONS['conference-seat'].length, 'the attendee limit').toBe(12)
  })

  it('seats every attendee at the conference table under its sign, then sends them back to work', { timeout: 60_000 }, () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1990s')
    const inside = scene as unknown as Inside
    scene.setMeetings([{ place: 'CONFERENCE', attendees: IDS }])
    scene.setAgents(crew('IN_MEETING'))
    browser.frames(2)
    for (const a of inside.crew.agents) expect(near(inside.crew.slotOf(a), STATIONS['conference-seat'])).toBe(true)
    expect(inside.crew.signShown('CONFERENCE')).toBe(true)
    expect(inside.crew.signShown('TABLE')).toBe(false)

    scene.setMeetings([])
    scene.setAgents(crew('WORKING'))
    for (const a of inside.crew.agents) expect(near(inside.crew.slotOf(a), STATIONS.desk)).toBe(true)
    expect(inside.crew.signShown('CONFERENCE')).toBe(false)
    scene.dispose()
  })

  it('at the meeting table, the chairs first, then standing places around it: nobody waits at a desk', { timeout: 60_000 }, () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '1990s')
    const inside = scene as unknown as Inside
    scene.setMeetings([{ place: 'TABLE', attendees: IDS }])
    scene.setAgents(crew('IN_MEETING'))
    const at = inside.crew.agents.map((a) => inside.crew.slotOf(a))
    expect(at.filter((p) => near(p, STATIONS['table-seat']))).toHaveLength(STATIONS['table-seat'].length)
    expect(at.filter((p) => near(p, STATIONS['meeting-stand']))).toHaveLength(IDS.length - STATIONS['table-seat'].length)
    expect(inside.crew.signShown('TABLE')).toBe(true)
    scene.dispose()
  })
})
