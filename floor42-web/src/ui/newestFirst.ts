import type { ContributionView, ReportCheck } from '../api/types'

// Event lists read newest first (the meeting room, the end-of-mission report): what just happened is at the top.

/** The checks Floor42 ran, the latest first; a check without a readable time keeps its place after the others. */
export function checksNewestFirst(checks: readonly ReportCheck[]): ReportCheck[] {
  return [...checks].sort((a, b) => (Date.parse(b.at) || 0) - (Date.parse(a.at) || 0))
}

/** A committee's contributions grouped by round, the latest round first (the decision records are apart). */
export function roundsNewestFirst(contributions: readonly ContributionView[]): { round: number; items: ContributionView[] }[] {
  const byRound = new Map<number, ContributionView[]>()
  for (const c of contributions) {
    if (c.kind === 'SYNTHESIS') continue
    byRound.set(c.round, [...(byRound.get(c.round) ?? []), c])
  }
  return [...byRound.entries()].sort((a, b) => b[0] - a[0]).map(([round, items]) => ({ round, items }))
}

/** Any list of events kept oldest first, shown latest first (a copy; the source is left alone). */
export function latestFirst<T>(events: readonly T[]): T[] {
  return [...events].reverse()
}
