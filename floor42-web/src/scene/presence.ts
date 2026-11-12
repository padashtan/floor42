import type { EventView, MissionSummary } from '../api/types'

// Pure rules for who stays in the office and who speaks, like mapping.ts: each is called for by a recorded fact.

/**
 * The agents who stay at their desks between two sessions: those who worked on any mission still in progress (producers,
 * reviewers, anyone with a session), whichever mission is on the board. On any other mission nobody stays: an agent
 * with nothing to do leaves.
 */
export function residentsOf(missions: Pick<MissionSummary, 'status' | 'agents'>[]): Set<string> {
  return new Set(missions.filter((m) => m.status === 'IN_PROGRESS').flatMap((m) => m.agents))
}

/** How long a recorded voice marks its speaker. */
export const SPEAK_SECONDS = 6

/**
 * The agent whose voice an event records at a committee (a contribution) or a meeting (a turn taken and heard). The
 * director's own words in the seat of a member (code ending `.director`) are not the member's: nobody is marked.
 */
export function speakerOf(ev: EventView): string | null {
  if (!ev.agent) return null
  if (ev.type === 'CommitteeContributed') return ev.code.endsWith('.director') ? null : ev.agent
  return ev.type === 'MeetingTurnTaken' && ev.code.endsWith('.heard') ? ev.agent : null
}
