// cost-overview: the arithmetic behind the costs panel, the spend indicator and the plan's cap. Pure, so it is tested.
import type { CostLine, MissionView, PlanApproval } from '../api/types'

/** How a mission's spending stands against its cap; `uncapped` when it has none. */
export type SpendLevel = 'ok' | 'near' | 'over' | 'uncapped'

/** The share of the cap at which the indicator warns. */
export const NEAR = 0.8

export function spendLevel(spent: number, cap: number | null): SpendLevel {
  if (cap === null) return 'uncapped'
  if (spent >= cap) return 'over'
  return spent >= cap * NEAR ? 'near' : 'ok'
}

/**
 * What a mission's budget counts: Claude Code's estimates plus the caps of the sessions that reported no cost. The
 * sum is only compared with the cap; it is never shown as an estimate (the two parts are shown apart).
 */
export function counted(m: Pick<MissionView, 'spentUsdEstimated' | 'spentUsdBound'>): number {
  return m.spentUsdEstimated + (m.spentUsdBound ?? 0)
}

/** An amount in USD: four decimals below 1 USD (a session often costs cents), two above. */
export function usd(n: number): string {
  return Math.abs(n) < 1 ? n.toFixed(4) : n.toFixed(2)
}

/** "≥ " when some ended sessions reported no cost: the sum is then a lower bound, never shown as complete. */
export function lowerBound(line: Pick<CostLine, 'sessionsWithoutCost'>): string {
  return line.sessionsWithoutCost > 0 ? '≥ ' : ''
}

/** The amount of a line, with its lower-bound mark; null when no session reported a cost. */
export function amount(line: CostLine): string | null {
  return line.costBasis === 'unavailable' ? null : `${lowerBound(line)}${usd(line.usdEstimated)}`
}

/** The default cap the settings offer: a positive amount, or null for "none" (or anything unreadable). */
export function defaultCap(setting: string | null | undefined): number | null {
  if (!setting || setting === 'none') return null
  const n = Number(setting)
  return Number.isFinite(n) && n > 0 ? n : null
}

/** A typed amount: a positive number (comma or point), null when empty, NaN when it is not one. */
export function parseUsd(draft: string): number | null {
  const v = draft.trim().replace(',', '.')
  if (v === '') return null
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : NaN
}

/**
 * What the approval sends: the typed cap, or "no cap" when the director ticked it explicitly; null when neither is
 * valid, so the button stays disabled. A cap already set is kept as typed (the field starts from it).
 */
export function capChoice(draft: string, noCap: boolean): PlanApproval | null {
  if (noCap) return { budgetUsd: null, noCap: true }
  const n = parseUsd(draft)
  return n === null || Number.isNaN(n) ? null : { budgetUsd: n, noCap: null }
}

/** The basis of a simulated mission's lines (server Costs.SIMULATED): nothing was paid. */
export const SIMULATED_BASIS = 'simulated: no model was called'

/**
 * A line as the costs panel writes it: "≈ amount" (an estimate), "0" for a simulated mission, "—" when unknown; the
 * caps of the sessions that reported no cost follow apart as "+ ≤ bound" (an upper bound, never added to the estimate).
 */
export function shownAmount(line: CostLine): string {
  if (line.costBasis === SIMULATED_BASIS) return '0'
  const a = amount(line)
  const bound = line.usdBound !== null && line.usdBound > 0 ? `≤ ${usd(line.usdBound)}` : null
  if (a === null) return bound ?? '—'
  return bound === null ? `≈ ${a}` : `≈ ${a} + ${bound}`
}

/** A token count, short: 12 345 → "12.3k". */
export function tokens(n: number): string {
  if (n < 1000) return String(n)
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`
  return `${(n / 1_000_000).toFixed(2)}M`
}
