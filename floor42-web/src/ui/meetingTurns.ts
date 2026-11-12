import type { MeetingNoteView, MeetingPlace, MeetingView, RoundView, TurnView } from '../api/types'
import type { MeetingPresence } from '../scene/OfficeScene'

// The director's meeting panel (ADR-0047, 0049), as pure rules: what the panel shows (never the call form over an open
// meeting, never an empty form over a failed read), the conversation in order, the default cap, the speaking order.

/** What the panel shows: the open meeting, the call form, the read still under way, or the error of a failed read. */
export type PanelView =
  | { kind: 'meeting'; meeting: MeetingView }
  | { kind: 'call' }
  | { kind: 'loading' }
  | { kind: 'error'; error: string }

/**
 * An open meeting always shows, from the last list read even when the latest read failed (with its error beside it);
 * the call form only once a read succeeded and found none open about the mission or any other.
 */
export function panelView(meetings: MeetingView[] | null, error: string | null, mission: string | null): PanelView {
  const open = (meetings ?? []).filter((m) => m.open)
  const shown = open.find((m) => m.mission === mission) ?? open[0]
  if (shown) return { kind: 'meeting', meeting: shown }
  if (error) return { kind: 'error', error }
  if (meetings === null) return { kind: 'loading' }
  return { kind: 'call' }
}

/** One line of the conversation: something the director said, or a speaker's turn of a round. */
export type Said = { kind: 'note'; note: MeetingNoteView } | { kind: 'turn'; round: number; turn: TurnView }

/** The director's notes and the turns, in the order they happened; turns not taken yet last, in their round's order. */
export function conversation(meeting: MeetingView): Said[] {
  const dated: { at: string; said: Said }[] = meeting.notes.map((n) => ({ at: n.at, said: { kind: 'note', note: n } }))
  const pending: Said[] = []
  for (const r of meeting.rounds) {
    for (const t of r.turns) {
      const said: Said = { kind: 'turn', round: r.number, turn: t }
      if (t.at) dated.push({ at: t.at, said })
      else pending.push(said)
    }
  }
  dated.sort((a, b) => a.at.localeCompare(b.at))
  return [...dated.map((d) => d.said), ...pending]
}

/** The round still under way, if any: one at a time. */
export function roundUnderWay(meeting: MeetingView): RoundView | null {
  const last = meeting.rounds.at(-1)
  return last && last.turns.some((t) => t.state === 'WAITING' || t.state === 'SPEAKING') ? last : null
}

/** A cap the director starts from: a quarter of a dollar per speaker, above what the meeting was charged so far. */
export function defaultCap(meeting: MeetingView, speakers: number): number {
  return Math.round((meeting.spentUsdCharged + 0.25 * Math.max(1, speakers)) * 100) / 100
}

/** The order with the speaker at `index` moved by `by` (−1 earlier, +1 later); unchanged at either end. */
export function moved(order: string[], index: number, by: -1 | 1): string[] {
  const to = index + by
  if (to < 0 || to >= order.length) return order
  const next = [...order]
  ;[next[index], next[to]] = [next[to], next[index]]
  return next
}

/** `read` run at most once at a time: a call while one is under way is skipped, not queued (the panel's polls). */
export function oneAtATime(read: () => Promise<void>): () => Promise<void> {
  let under = false
  return async () => {
    if (under) return
    under = true
    try {
      await read()
    } finally {
      under = false
    }
  }
}

/** The table a right-click chose: the conference table, or the meeting table. */
export function placeOfTable(id: string | undefined): MeetingPlace {
  return id === 'conference-1' ? 'CONFERENCE' : 'TABLE'
}

/** The open meetings as the scene seats them. */
export function presences(meetings: MeetingView[]): MeetingPresence[] {
  return meetings.filter((m) => m.open).map((m) => ({ place: m.place, attendees: m.attendees.map((a) => a.id) }))
}
