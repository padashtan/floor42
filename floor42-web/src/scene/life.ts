import type { AgentStatus, EventView } from '../api/types'

// Pure rules for the characters' body language and the room's mood. Like mapping.ts, every gesture here is called for
// by a recorded event or a derived status; only the ambient motion (breathing, looking around) needs no event.

/** A short one-off gesture: a ticket done, an action Floor42 refused. */
export type Gesture = 'celebrate' | 'frustrated'

/** What the hands do at the desk while a tool runs: typing for writes and commands, a sheet held up for reads. */
export type Hands = 'type' | 'read'

/** A milestone the room marks with a burst of confetti at the mission board. */
export type Milestone = 'plan-approved' | 'mission-completed'

/**
 * The gesture an event calls for, and who makes it. A completed ticket names no agent in the journal: its assignee is
 * looked up on the board the HUD handed in. Nothing else makes an agent gesture.
 */
export function gestureFor(
  ev: EventView,
  assigneeOf: (ticket: string) => string | null | undefined,
): { agent: string; gesture: Gesture } | null {
  if (ev.type === 'ActionDenied' && ev.agent) return { agent: ev.agent, gesture: 'frustrated' }
  if (ev.type === 'TicketCompleted' && ev.ticket) {
    const agent = ev.agent ?? assigneeOf(ev.ticket)
    return agent ? { agent, gesture: 'celebrate' } : null
  }
  return null
}

/** The milestone an event marks, if any. */
export function milestoneOf(ev: EventView): Milestone | null {
  if (ev.type === 'MissionPlanApproved') return 'plan-approved'
  if (ev.type === 'MissionCompleted') return 'mission-completed'
  return null
}

/** How the hands work, from the category of the agent's latest recorded tool call. */
export function handsFor(category: string | null | undefined): Hands {
  return category === 'READ' ? 'read' : 'type'
}

/** True while a tool authorized for the agent is running: only then do its hands work at full speed. */
export function toolRunning(status: AgentStatus): boolean {
  return status === 'EXECUTING'
}

/**
 * The room's mood from the director's local clock, like the city's windows: `warmth` 0 in the day, 1 in the evening;
 * `level` scales the ambient light (1 in the day). Scenery only; the quota's night (setNight) overrides it.
 */
export function daylight(hour: number): { warmth: number; level: number } {
  const h = ((hour % 24) + 24) % 24
  const ramp = (a: number, b: number) => Math.min(1, Math.max(0, (h - a) / (b - a)))
  let warmth: number
  if (h < 6) warmth = 0.8
  else if (h < 9) warmth = 0.8 * (1 - ramp(6, 9))
  else if (h < 17) warmth = 0
  else if (h < 20) warmth = ramp(17, 20)
  else warmth = 1 - 0.2 * ramp(20, 24)
  return { warmth, level: 1 - 0.18 * warmth }
}

// ---- easing ----------------------------------------------------------------------------------------------------

export function easeInOutCubic(x: number): number {
  const u = Math.min(1, Math.max(0, x))
  return u < 0.5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2
}

/** Overshoots a little before settling: for pops. */
export function easeOutBack(x: number): number {
  const u = Math.min(1, Math.max(0, x))
  const c1 = 1.70158
  const c3 = c1 + 1
  return 1 + c3 * Math.pow(u - 1, 3) + c1 * Math.pow(u - 1, 2)
}

/** The shortest signed turn from angle `from` to angle `to`, in radians, within (-π, π]. */
export function turn(from: number, to: number): number {
  let d = (to - from) % (Math.PI * 2)
  if (d > Math.PI) d -= Math.PI * 2
  if (d <= -Math.PI) d += Math.PI * 2
  return d
}
