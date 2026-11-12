// The small ceremony at the end of a mission (ADR-0039), as a pure sequence the scene plays. It is called for by one
// recorded event, MissionCompleted; it invents nothing: who gathers is who worked on the mission (producers,
// reviewers, anyone with a session), and afterwards each agent goes where its status sends it.

export type CeremonyStepKind = 'gather' | 'cheer' | 'disperse'

export interface CeremonyStep {
  /** Seconds after the event. */
  at: number
  kind: CeremonyStepKind
  /** The agents the step concerns (all the gathered ones). */
  agents: string[]
}

export interface CeremonyInput {
  /** Who worked on the mission, in the order they first worked. */
  participants: string[]
  /** Seats at the meeting table: at most this many gather. */
  seats: number
  /** The director's reduced-motion setting: no walk for show, no confetti, no gesture. */
  calm: boolean
  /** The quota's night: everyone is home, nothing happens. */
  night: boolean
}

/** Time for those out of the office to come up in the elevator and walk to the table. */
export const GATHER_SECONDS = 12
/** How long the cheer lasts before everyone goes back. */
export const CHEER_SECONDS = 4

export function ceremonyPlan(input: CeremonyInput): CeremonyStep[] {
  if (input.night || input.calm) return []
  const agents = [...new Set(input.participants)].slice(0, Math.max(0, input.seats))
  if (agents.length === 0) return []
  return [
    { at: 0, kind: 'gather', agents },
    { at: GATHER_SECONDS, kind: 'cheer', agents },
    { at: GATHER_SECONDS + CHEER_SECONDS, kind: 'disperse', agents },
  ]
}

/** The steps due in the interval (from, to]: the scene asks once per frame with the time elapsed since the event. */
export function dueSteps(plan: CeremonyStep[], from: number, to: number): CeremonyStep[] {
  return plan.filter((s) => (s.at > from || (s.at === 0 && from < 0)) && s.at <= to)
}

/** Who worked on a mission, from its view: producers, reviewers, then anyone else with a session; first seen first. */
export function participantsOf(mission: {
  tickets: { assignee: string | null; reviews: { reviewer: string }[] }[]
  sessions: { agent: string; startedAt: string }[]
}): string[] {
  const bySession = [...mission.sessions].sort((a, b) => Date.parse(a.startedAt) - Date.parse(b.startedAt))
  const seen: string[] = []
  const add = (id: string | null) => {
    if (id && !seen.includes(id)) seen.push(id)
  }
  bySession.forEach((s) => add(s.agent))
  mission.tickets.forEach((t) => {
    add(t.assignee)
    t.reviews.forEach((r) => add(r.reviewer))
  })
  return seen
}
