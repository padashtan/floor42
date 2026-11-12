import type { AgentStatus, EventView, TicketView } from '../api/types'

// Pure rules linking domain facts to what the scene shows. Kept apart from rendering so they can be tested and
// reviewed: an animation must always be explained by an event (spec section 3.3).

export type MachineId = 'terminal-1' | 'mainframe-1' | 'archive-1' | 'printer-1'
export type MachineState = 'IDLE' | 'ACTIVE' | 'ERROR'
/** `stuck`: the agent a stuck mission would put to work (the dispatcher's rule, never a status of the agent). */
export type Glyph = 'work' | 'wait' | 'alert' | 'idle' | 'stuck'
export type StationId =
  | 'table-seat'
  | 'director-front'
  | 'lounge'
  | 'board-front'
  | 'desk'
  | 'door'
  | 'conference-seat'
  /** Standing places around the meeting table, for a director's meeting larger than its chairs (ADR-0049). */
  | 'meeting-stand'
  /** Not a status's station: where a thinking agent and a colleague talk (the scene decides, see OfficeScene). */
  | 'chat'

const BUILD_TOOLS = /^(\.\/)?(mvnw?|gradlew?|npm|pnpm|yarn|make|pytest|cargo|go|dotnet)(\s|$)/

/** Which machine embodies a tool execution. Null when the scene has no machine for it yet. */
export function machineForExecution(category: string | null, summary: string | null): MachineId | null {
  switch (category) {
    case 'READ':
      return 'archive-1'
    case 'EXECUTE':
      return BUILD_TOOLS.test((summary ?? '').trim()) ? 'mainframe-1' : 'terminal-1'
    case 'WRITE':
      return 'terminal-1'
    default:
      return null // NETWORK, FLOOR42 and OTHER get their machines with the research room and the reports
  }
}

/**
 * The machine reaction to one event, if any. Only events recording a real run may light a machine; a request or an
 * authorization never does (ADR-0005). A submitted deliverable makes the printer run.
 */
export function machineReaction(ev: EventView): { machine: MachineId; state: MachineState } | null {
  if (ev.ran) {
    const machine = machineForExecution(ev.category, ev.summary)
    return machine ? { machine, state: ev.type === 'ActionFailed' ? 'ERROR' : 'ACTIVE' } : null
  }
  if (ev.type === 'TicketSubmittedForReview') return { machine: 'printer-1', state: 'ACTIVE' }
  return null
}

export function glyphFor(status: AgentStatus): Glyph {
  switch (status) {
    case 'WORKING':
    case 'EXECUTING':
    case 'IN_COMMITTEE':
    case 'IN_RETRO':
    case 'IN_MEETING':
      return 'work'
    case 'WAITING_FOR_DIRECTOR':
    case 'WAITING_FOR_AUTHORIZATION':
      return 'wait'
    default:
      return 'idle'
  }
}

/**
 * Where an agent goes for a given status. Waiting for the director means walking to the director's desk; work happens
 * at the agent's own desk in the open space (ADR-0019). An agent with nothing to do goes to the lounge: the ping-pong
 * table, the sofa or the kitchen.
 */
export function stationFor(status: AgentStatus, resident = false): StationId {
  switch (status) {
    // Between two sessions of a mission in progress: idle at its own desk, not typing (figures.ts); with no mission to
    // its name: in the lounge, in the idle poses of the sofa, the ping-pong table and the chat table.
    case 'AVAILABLE':
      return resident ? 'desk' : 'lounge'
    case 'WAITING_FOR_DIRECTOR':
      return 'director-front'
    // A committee may seat ten: it meets in the conference room; the retrospective stays at the holo table.
    case 'IN_COMMITTEE':
      return 'conference-seat'
    case 'IN_RETRO':
    // Called by the director (ADR-0047): to the meeting table, as for a retrospective.
    case 'IN_MEETING':
      return 'table-seat'
    case 'WORKING':
    case 'EXECUTING':
    case 'WAITING_FOR_AUTHORIZATION':
    case 'REVIEWING':
      return 'desk'
    case 'ASSIGNED':
      return 'board-front'
    default:
      return 'lounge'
  }
}

/**
 * Whether an agent is in the office at all. Whoever is on staff is: at work for what the journal records (a ticket on
 * a mission in progress, a review, a committee, a retrospective, each derived by the server), else idle, at its desk
 * between two sessions of a mission in progress (stationFor `resident`, presence.ts) or in the lounge. Not shown at
 * all: an agent the director suspended, or whose only tickets are on a suspended mission (PAUSED). Dismissed agents
 * are not listed and leave for good.
 */
export function presentInOffice(status: AgentStatus, suspended = false): boolean {
  if (status === 'AVAILABLE') return !suspended
  return status !== 'PAUSED'
}

export interface BoardColumns {
  /** Waiting for the director's approval (ADR-0017). */
  proposed: string[]
  todo: string[]
  doing: string[]
  done: string[]
  inReview: string[]
  /** The ticket being worked on right now, if any. */
  current: string | null
  /** Who each open or done ticket is assigned to: who celebrates when it is done. */
  assignees: Record<string, string>
}

/** Mission board content: persisted tickets, never decoration. Cancelled tickets leave the board. */
export function boardColumns(tickets: TicketView[]): BoardColumns {
  return {
    proposed: tickets.filter((t) => t.status === 'PROPOSED').map((t) => t.id),
    todo: tickets.filter((t) => t.status === 'TODO').map((t) => t.id),
    doing: tickets.filter((t) => t.status === 'IN_PROGRESS' || t.status === 'IN_REVIEW').map((t) => t.id),
    done: tickets.filter((t) => t.status === 'DONE').map((t) => t.id),
    inReview: tickets.filter((t) => t.status === 'IN_REVIEW').map((t) => t.id),
    current: tickets.find((t) => t.status === 'IN_PROGRESS')?.id ?? null,
    assignees: Object.fromEntries(
      tickets.filter((t) => t.assignee && t.status !== 'CANCELLED').map((t) => [t.id, t.assignee as string]),
    ),
  }
}

export interface Activity {
  text: string
  tone: 'work' | 'wait' | 'alert'
}

/**
 * The line shown under an agent's name for a live event about it: the journal's own words, without the agent's name,
 * shortened. Only a recorded event produces one; nothing is invented. The UI hands in the line and how to say "waits for
 * you: <subject>" in the screen's language (the scene does not know the catalogues).
 */
export function activityOf(
  ev: EventView,
  line: string,
  waits: (subject: string) => string = (subject) => 'waits for you: ' + subject,
): Activity | null {
  if (!ev.agent || ev.origin !== 'REAL' && ev.origin !== 'SIMULATED') return null
  let text = line.trim()
  const prefix = ev.agent + ' '
  if (text.toLowerCase().startsWith(prefix.toLowerCase())) text = text.slice(prefix.length)
  // A question for the director: what it asks, not its number.
  if (ev.type === 'QuestionAsked') text = waits(text.slice(text.indexOf(': ') + 2))
  if (text.length > 46) text = text.slice(0, 45).trimEnd() + '…'
  // A demo replays a recording: its lines say so, never passing for the real thing.
  if (ev.origin === 'SIMULATED') text = '[sim] ' + text
  const tone =
    ev.type === 'ActionDenied' ? 'alert' : ev.type === 'ActionRequested' || ev.type === 'QuestionAsked' ? 'wait' : 'work'
  return { text, tone }
}
