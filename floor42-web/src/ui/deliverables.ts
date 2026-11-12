import type { DeliverableRef } from '../api/client'
import type { DeliverablesView, DeliveredBranch, DeliveredFile, DeliveredTicket, DeliveryCopyView } from '../api/types'

/** One agent with its branch (if any) and the tickets it was assigned, in the order of the board. */
export interface AgentGroup {
  agent: string | null
  branch: DeliveredBranch | null
  tickets: DeliveredTicket[]
}

/** One ticket with the branch of its assignee, when that agent has one. */
export interface TicketGroup {
  ticket: DeliveredTicket
  branch: DeliveredBranch | null
}

/**
 * By agent: every branch, then the assignees without one; tickets nobody was assigned come last, under a null agent.
 * Nothing is dropped: each ticket appears exactly once.
 */
export function byAgent(view: DeliverablesView): AgentGroup[] {
  const groups = new Map<string | null, AgentGroup>()
  for (const b of view.branches) groups.set(b.agent, { agent: b.agent, branch: b, tickets: [] })
  for (const t of view.tickets) {
    const key = t.assignee ?? null
    if (!groups.has(key)) groups.set(key, { agent: key, branch: null, tickets: [] })
    groups.get(key)!.tickets.push(t)
  }
  const all = [...groups.values()]
  return [...all.filter((g) => g.agent !== null), ...all.filter((g) => g.agent === null)]
}

/** By ticket: each ticket with its assignee's branch. */
export function byTicket(view: DeliverablesView): TicketGroup[] {
  const branches = new Map(view.branches.map((b) => [b.agent, b]))
  return view.tickets.map((ticket) => ({ ticket, branch: ticket.assignee ? branches.get(ticket.assignee) ?? null : null }))
}

/** Bytes as the director reads them; "—" when unknown. */
export function formatSize(bytes: number | null): string {
  if (bytes === null || bytes === undefined || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`
}

export type Viewer = 'text' | 'image' | 'pdf'

/**
 * How the screen may show a file, or null for download only: never a link, a deleted file, an unknown kind, or a
 * file above the daemon's caps (the daemon checks the bytes again and refuses anything else).
 */
export function viewerOf(file: DeliveredFile, view: Pick<DeliverablesView, 'maxViewText' | 'maxViewBytes'>): Viewer | null {
  if (file.link || file.status === 'D') return null
  if (file.kind === 'TEXT') return file.size !== null && file.size > view.maxViewText ? null : 'text'
  if (file.kind === 'IMAGE') return file.size !== null && file.size > view.maxViewBytes ? null : 'image'
  if (file.kind === 'PDF') return file.size !== null && file.size > view.maxViewBytes ? null : 'pdf'
  return null
}

/** Why the viewer cannot show a file, said to the director with Download beside it; null when it can. */
export type NotViewable = 'link' | 'deleted' | 'tooBig' | 'unknownKind'

export function notViewable(
  file: DeliveredFile,
  view: Pick<DeliverablesView, 'maxViewText' | 'maxViewBytes'>,
): NotViewable | null {
  if (file.link) return 'link'
  if (file.status === 'D') return 'deleted'
  if (file.kind === 'OTHER') return 'unknownKind'
  return viewerOf(file, view) ? null : 'tooBig'
}

/** The cap that applies to a file's kind: the text cap for text, the other one for images and PDFs. */
export function capOf(file: DeliveredFile, view: Pick<DeliverablesView, 'maxViewText' | 'maxViewBytes'>): number {
  return file.kind === 'TEXT' ? view.maxViewText : view.maxViewBytes
}

/** The file of an agent's branch the listing names at this path, committed or not; null when it names none. */
export function findFile(view: DeliverablesView, agent: string, path: string, committed: boolean): DeliveredFile | null {
  const b = view.branches.find((x) => x.agent === agent)
  return (committed ? b?.committed : b?.uncommitted)?.find((f) => f.path === path) ?? null
}

/** A journaled copy into the destination (ADR-0051), counted by outcome; files past the journaled list count as left out. */
export function copyCounts(copy: DeliveryCopyView): { written: number; keptBoth: number; same: number; leftOut: number } {
  const n = (o: string) => copy.files.filter((f) => f.outcome === o).length
  return { written: n('WRITTEN'), keptBoth: n('KEPT_BOTH'), same: n('SAME'), leftOut: n('LEFT_OUT') + copy.leftOut }
}

/** Whether a file can be downloaded at all: not a link, not deleted. */
export function downloadable(file: DeliveredFile): boolean {
  return !file.link && file.status !== 'D'
}

/** The daemon's name for a file: whose branch, which path, committed or only in the worktree. */
export function refOf(agent: string, file: DeliveredFile): DeliverableRef {
  return { agent, path: file.path, source: file.committed ? 'COMMITTED' : 'WORKTREE' }
}

/** How many files a branch delivered: committed, and present only in its worktree (deletions not counted). */
export function counts(branch: DeliveredBranch): { committed: number; uncommitted: number; ignored: number } {
  const live = (f: DeliveredFile) => f.status !== 'D'
  return {
    committed: branch.committed.filter(live).length,
    uncommitted: branch.uncommitted.filter((f) => live(f) && !f.ignored).length,
    ignored: branch.uncommitted.filter((f) => f.ignored).length,
  }
}

/** Opened automatically once per mission, when it ends while the page watches: remembered in this browser only. */
const SHOWN = 'floor42.deliverables.shown'

export function shouldOpenOnEnd(previous: string | undefined, now: string, mission: string, storage: Storage | null): boolean {
  const ended = (s: string | undefined) => s === 'COMPLETED' || s === 'CANCELLED'
  if (!previous || ended(previous) || !ended(now)) return false
  try {
    const seen: string[] = JSON.parse(storage?.getItem(SHOWN) ?? '[]')
    if (seen.includes(mission)) return false
    storage?.setItem(SHOWN, JSON.stringify([...seen, mission].slice(-200)))
  } catch {
    // No storage: it opens, once for this page.
  }
  return true
}
