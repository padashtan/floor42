import type { CoreDismissalEntry, CoreDismissalOutcome } from '../api/types'

/**
 * The preview of "dismiss everyone except the core members", sorted for the director: who would leave, who stays (a
 * starred core member) and who cannot leave now. The fate of each agent is the daemon's (the same rules as a single
 * dismissal); this only groups and orders it.
 */
export interface PreviewGroups {
  leaving: CoreDismissalEntry[]
  staying: CoreDismissalEntry[]
  blocked: CoreDismissalEntry[]
}

const byName = (a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name)

export function groupPreview(entries: CoreDismissalEntry[]): PreviewGroups {
  const of = (fate: CoreDismissalEntry['fate']) => entries.filter((e) => e.fate === fate).slice().sort(byName)
  return { leaving: of('LEAVES'), staying: of('STAYS'), blocked: of('BLOCKED') }
}

/** The ids the director confirms: exactly those shown as leaving, nobody added since. */
export function confirmed(groups: PreviewGroups): string[] {
  return groups.leaving.map((e) => e.id)
}

/**
 * The catalogue key that says why an agent cannot leave (the single dismissal's own sentences), or why a confirmed one
 * was not dismissed; null when the code has no sentence of its own (the daemon's text is shown then).
 */
export function reasonKey(code: string | null): string | null {
  if (code === 'AGENT_BUSY' || code === 'AGENT_NEEDED' || code === 'ALREADY_DISMISSED') return `dismiss.refused.${code}`
  if (code === 'CORE') return 'core.skippedCore'
  return null
}

/** How many left, were refused or skipped: the report's headline. */
export function tally(outcomes: CoreDismissalOutcome[]): { dismissed: number; refused: number; skipped: number } {
  return {
    dismissed: outcomes.filter((o) => o.result === 'DISMISSED').length,
    refused: outcomes.filter((o) => o.result === 'REFUSED').length,
    skipped: outcomes.filter((o) => o.result === 'SKIPPED').length,
  }
}
