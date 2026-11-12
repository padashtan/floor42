import * as THREE from 'three'
import { box, glow } from './kit'
import type { StationId } from './mapping'

// The director's meeting in the scene (ADR-0049): where its attendees sit, and its sign over the chosen table. Plain
// data from the server's meeting views; no Vue.

/** The table the director called the meeting at. */
export type MeetingPlace = 'TABLE' | 'CONFERENCE'

/** An open meeting as the scene needs it: its table and who was called. */
export interface MeetingPresence {
  place: MeetingPlace
  attendees: string[]
}

/** The table each attendee of an open meeting sits at; an agent attends one meeting at a time, the first one listed wins. */
export function placesOf(meetings: MeetingPresence[]): Map<string, MeetingPlace> {
  const places = new Map<string, MeetingPlace>()
  for (const m of meetings) for (const a of m.attendees) if (!places.has(a)) places.set(a, m.place)
  return places
}

/** The stations a seated attendee takes, in order: the table's chairs, then standing places around the meeting table. */
export function stationsAt(place: MeetingPlace): StationId[] {
  return place === 'CONFERENCE' ? ['conference-seat'] : ['table-seat', 'meeting-stand']
}

/** Whether a table's sign is lit: a meeting is open there with someone already seated at it. */
export function signLit(meetings: MeetingPresence[], seated: Set<string>, place: MeetingPlace): boolean {
  return meetings.some((m) => m.place === place && m.attendees.some((a) => seated.has(a)))
}

/** The conference table's meeting sign: the same amber ring on its post as the meeting table's, hidden until needed. */
export function meetingSign(): THREE.Group {
  const g = new THREE.Group()
  const ring = new THREE.Mesh(new THREE.TorusGeometry(0.28, 0.04, 6, 16), glow(0xffb000))
  ring.rotation.x = Math.PI / 2
  ring.position.y = 1.95
  ring.userData.noOutline = true
  g.add(ring)
  box(g, [0.05, 1.1, 0.05], [0, 1.25, 0], glow(0xffb000), false).userData.noOutline = true
  g.visible = false
  return g
}
