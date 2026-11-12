import * as THREE from 'three'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EventView } from '../api/types'
import { deskPose } from './deskLife'
import { Figure } from './figures'
import { CONFERENCE, DESKS, STATIONS } from './floorPlan'
import { officeMarks } from './marks'
import { residentsOf, speakerOf } from './presence'
import { stationFor } from './mapping'
import { Seating } from './seating'
import { fakeDocument } from './testing/sceneDigest'

const mission = (status: string, agents: string[]) => ({ status, agents })

const ev = (type: string, code: string, agent: string | null = 'bobi') => ({ type, code, agent }) as EventView

describe('who stays in the office', () => {
  it('keeps those who worked on any mission in progress, whichever one is on the board', () => {
    const missions = [
      mission('IN_PROGRESS', ['bobi', 'galen']),
      mission('COMPLETED', ['darius']),
      mission('AWAITING_APPROVAL', ['margaret']),
      mission('IN_PROGRESS', ['lucie', 'bobi']),
    ]
    expect([...residentsOf(missions)].sort()).toEqual(['bobi', 'galen', 'lucie'])
  })

  it('keeps nobody when no mission is in progress', () => {
    for (const status of ['AWAITING_APPROVAL', 'SUSPENDED', 'COMPLETED', 'CANCELLED']) {
      expect(residentsOf([mission(status, ['bobi'])]).size).toBe(0)
    }
    expect(residentsOf([]).size).toBe(0)
  })
})

describe('who speaks', () => {
  it('marks the agent of a recorded contribution or a heard turn, nothing else', () => {
    expect(speakerOf(ev('CommitteeContributed', 'CommitteeContributed.analysis'))).toBe('bobi')
    expect(speakerOf(ev('MeetingTurnTaken', 'MeetingTurnTaken.heard'))).toBe('bobi')
    expect(speakerOf(ev('MeetingTurnTaken', 'MeetingTurnTaken.notHeard'))).toBeNull()
    expect(speakerOf(ev('ActionExecuted', 'ActionExecuted.authorized'))).toBeNull()
    expect(speakerOf(ev('CommitteeContributed', 'CommitteeContributed.analysis', null))).toBeNull()
  })

  it('does not mark a member for the director’s own words in its seat', () => {
    expect(speakerOf(ev('CommitteeContributed', 'CommitteeContributed.rebuttal.director'))).toBeNull()
    expect(speakerOf(ev('CommitteeContributed', 'CommitteeContributed.synthesis.director'))).toBeNull()
  })
})

describe('where the agents are placed', () => {
  // Positions the floor plan defines on its own: the scene must put each agent there, not wherever it computed.
  it('seats an agent at its own desk, south of the desk, and a meeting at the conference table', () => {
    const seating = new Seating(STATIONS)
    const desk = seating.claim('bobi', 'desk')
    expect(desk).toMatchObject({ x: DESKS[0].x, z: DESKS[0].z + 0.75 })
    expect(seating.claim('lucie', 'desk')).toMatchObject({ x: DESKS[1].x, z: DESKS[1].z + 0.75 })
    const seat = seating.claimAny('bobi', ['conference-seat'])
    expect(Math.abs(seat.x - CONFERENCE.x)).toBeLessThan(3)
    expect(Math.abs(seat.z - CONFERENCE.z)).toBeLessThan(2)
    // Back from the meeting: the same desk.
    seating.leave('bobi')
    expect(seating.claim('bobi', 'desk')).toEqual(desk)
  })

  it('spreads agents with no mission over the lounge seats, two never on one seat', () => {
    const seating = new Seating(STATIONS)
    const seats = ['a', 'b', 'c'].map((id) => seating.claim(id, stationFor('AVAILABLE')))
    for (const seat of seats) expect(STATIONS.lounge).toContainEqual(seat)
    expect(new Set(seats.map((s) => `${s.x},${s.z}`)).size).toBe(3)
    // Work calls it back to its own desk.
    expect(seating.claim('a', stationFor('WORKING'))).toMatchObject({ x: DESKS[0].x, z: DESKS[0].z + 0.75 })
  })

  it('is far from the desks at the conference table, so a test can tell the two apart', () => {
    expect(Math.hypot(DESKS[0].x - CONFERENCE.x, DESKS[0].z - CONFERENCE.z)).toBeGreaterThan(5)
  })
})

describe('an idle agent at its desk', () => {
  beforeEach(() => {
    vi.stubGlobal('document', fakeDocument().document)
    vi.spyOn(Math, 'random').mockReturnValue(0.37)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  const desk = (resting: boolean, busy: boolean) => {
    const g = () => new THREE.Group()
    const s = { armL: g(), armR: g(), head: g(), busy, hands: 'type' as const, resting, seed: 1 }
    return { s, arms: (t: number) => (deskPose(s, t, false), [s.armL.rotation.x, s.armR.rotation.x]) }
  }

  it('does not type: the hands rest still, unlike with a tool running', () => {
    const rest = desk(true, false)
    expect(rest.arms(0.3)).toEqual(rest.arms(0.31))
    const work = desk(false, true)
    expect(work.arms(0.3)).not.toEqual(work.arms(0.31))
  })

  it('takes a sip of coffee now and then, with the mug in hand only then', () => {
    const rest = desk(true, false)
    const mug = [0, 3, 6, 9, 11.5, 14, 17].map((t) => deskPose(rest.s, t - 3, false))
    expect(mug.some(Boolean)).toBe(true)
    expect(mug.every(Boolean)).toBe(false)
    expect(deskPose(rest.s, 11.5 - 3, true)).toBe(false)
  })

  it('nods only while its voice is marked, and the marks list it', () => {
    const f = new Figure('bobi', 0x336699, 3)
    f.placeAt({ x: 1, z: 1 }, 0, 'sit')
    f.speak(10)
    expect(f.speaks(9.9)).toBe(true)
    expect(f.speaks(10.1)).toBe(false)
    const slot = STATIONS.desk[0]
    f.placeAt(slot, slot.yaw, slot.pose)
    expect(officeMarks([f], 5)).toEqual({ office: `bobi:1:${slot.x.toFixed(1)},${slot.z.toFixed(1)}`, speaking: 'bobi' })
    f.root.visible = false
    expect(officeMarks([f], 5).speaking).toBe('')
  })
})
