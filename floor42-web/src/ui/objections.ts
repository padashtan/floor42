// The rows of the objections form (ADR-0060): what one can object to, what a new row starts with, and what a round
// costs in sessions. The limits are the daemon's (1 to 10 objections, 1 to 4 000 characters each).
import type { ObjectionRequest } from '../api/objections'
import type { CommitteeSummaryView, HeldDeveloper, MissionView, TicketView } from '../api/types'

export const MAX_OBJECTIONS = 10
export const MAX_TEXT = 4000
/** The target of an objection that asks for new work. */
export const NEW_WORK = ''

export interface Row {
  /** A done work ticket's id, or `NEW_WORK`. */
  target: string
  text: string
}

/**
 * The done work tickets one can object to, in the order of the board. A committee's decision ticket the director
 * already ruled on is left out: nobody works on it any more, the daemon refuses a directive to it (DECIDED_TICKET).
 */
export function objectable(tickets: TicketView[], committees: CommitteeSummaryView[] = []): TicketView[] {
  const ruled = new Set(committees.filter((c) => c.stage === 'ADOPTED' || c.stage === 'REJECTED').map((c) => c.ticket))
  return tickets.filter((x) => x.status === 'DONE' && x.kind === 'WORK' && !ruled.has(x.id))
}

/**
 * Each producer's latest done ticket, the one its worktree holds (by the time it ended, as the daemon reads it for
 * held work), in the order the producers first delivered.
 */
export function latestPerProducer(tickets: TicketView[], committees: CommitteeSummaryView[] = []): string[] {
  const latest = new Map<string, TicketView>()
  for (const x of objectable(tickets, committees)) {
    const now = latest.get(x.assignee ?? '')
    if (!now || (x.endedAt ?? '') >= (now.endedAt ?? '')) latest.set(x.assignee ?? '', x)
  }
  return [...latest.values()].map((x) => x.id)
}

/** What a new row is about: the next producer's latest ticket nobody objects to yet, else the first, else new work. */
export function nextTarget(mission: Pick<MissionView, 'tickets' | 'committees'>, rows: Row[]): string {
  const defaults = latestPerProducer(mission.tickets, mission.committees)
  return defaults.find((id) => !rows.some((r) => r.target === id)) ?? defaults[0] ?? NEW_WORK
}

/** Whether the form can be sent: 1 to 10 rows, every text filled and within the limit. */
export const ready = (rows: Row[]): boolean =>
  rows.length >= 1 &&
  rows.length <= MAX_OBJECTIONS &&
  rows.every((r) => r.text.trim().length >= 1 && r.text.trim().length <= MAX_TEXT)

export const toRequest = (rows: Row[]): ObjectionRequest[] =>
  rows.map((r) => ({ ticket: r.target === NEW_WORK ? null : r.target, text: r.text.trim() }))

/** The workflow's checks, each run by a reviewer after the review (ADR-0052); none for an unpinned mission. */
export const checksOf = (mission: Pick<MissionView, 'workflow'>): number =>
  mission.workflow?.workflow?.steps.filter((s) => s.check).length ?? 0

/**
 * The sessions a round starts at least: a producer session for each ticket reopened (once, however many objections
 * name it) and for each new work, then a review and one reviewer session per check of the pinned workflow. More may
 * follow (a rejected review, a retry).
 */
export function sessionCounts(rows: Row[], checks = 0): { producers: number; reviews: number } {
  const reopened = new Set(rows.filter((r) => r.target !== NEW_WORK).map((r) => r.target))
  const producers = reopened.size + rows.filter((r) => r.target === NEW_WORK).length
  return { producers, reviews: producers * (1 + checks) }
}

/** The held developers a new work waits behind (HeldWork): who, and the tickets whose changes hold them. */
export const heldFor = (rows: Row[], held: HeldDeveloper[]): HeldDeveloper[] =>
  rows.some((r) => r.target === NEW_WORK) ? held : []
