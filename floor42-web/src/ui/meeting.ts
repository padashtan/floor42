import type { AgentView, MeetingView, MissionSummary } from '../api/types'

// The director's meeting (ADR-0047), as pure rules the panel reads: which missions a meeting can be about, who is
// proposed, which meeting the panel shows, whether everyone is seated. The server decides; these only shape the form.

/** A meeting is about a mission that is not closed: completed and cancelled ones are refused by the server. */
export function meetableMissions(missions: MissionSummary[]): MissionSummary[] {
  return missions.filter((m) => m.status !== 'COMPLETED' && m.status !== 'CANCELLED')
}

/** The mission the form starts on: the board's when it can meet, else the first that can. */
export function defaultMission(missions: MissionSummary[], board: string | null): string | null {
  const open = meetableMissions(missions)
  return open.find((m) => m.id === board)?.id ?? open[0]?.id ?? null
}

/** The agents the director may tick (dismissed agents are not listed): the proposed ones first, in the roster's order. */
export function choosable(agents: AgentView[], suggested: string[]): AgentView[] {
  const first = agents.filter((a) => suggested.includes(a.id))
  return [...first, ...agents.filter((a) => !suggested.includes(a.id))]
}

/** Attendees seated at the table, out of all: the rest still finish the call they were making. */
export function seated(meeting: MeetingView): { seated: number; of: number } {
  return { seated: meeting.attendees.filter((a) => a.status === 'IN_MEETING' && !a.finishing).length, of: meeting.attendees.length }
}

/** Who a note was said to, by name: null for everyone. */
export function addressee(meeting: MeetingView, attendee: string | null): string | null {
  if (attendee === null) return null
  return meeting.attendees.find((a) => a.id === attendee)?.name ?? attendee
}
