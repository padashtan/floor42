import type { PreflightView } from '../api/types'

/** How many lines of one level the preflight holds. */
const count = (v: PreflightView | null, level: 'BLOCK' | 'WARN') => v?.lines.filter((l) => l.level === level).length ?? 0

export const blockCount = (v: PreflightView | null) => count(v, 'BLOCK')
export const warnCount = (v: PreflightView | null) => count(v, 'WARN')

/**
 * The checks command to propose: only while the field is empty, the last mission's on this project first, else the
 * build tool's default. Null when there is nothing to propose.
 */
export function proposal(v: PreflightView | null, checks: string): { command: string; fromLast: boolean } | null {
  if (!v || checks.trim()) return null
  if (v.profile.lastChecks) return { command: v.profile.lastChecks, fromLast: true }
  return v.profile.suggestedChecks ? { command: v.profile.suggestedChecks, fromLast: false } : null
}

/** The lines the card writes: everything but the "ready" line, which the summary says. */
export const shownLines = (v: PreflightView | null) => v?.lines.filter((l) => l.level !== 'OK') ?? []

/** A tool line that warns: the tools detail then opens by itself. */
export const toolWarning = (v: PreflightView | null) => !!v?.lines.some((l) => l.code.startsWith('tool.'))
