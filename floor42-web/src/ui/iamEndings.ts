/** Warnings before a delegation (30 min) and an agent token (10 min) end. Screen only: nothing is journaled. */
export const DELEGATION_LEAD_MS = 30 * 60 * 1000
export const TOKEN_LEAD_MS = 10 * 60 * 1000

export interface End {
  id: string
  endMs: number
}

/** Ends still to come that fall inside the lead, soonest first. An end already past is not announced. */
export function soonest(ends: End[], now: number, leadMs: number): End[] {
  return ends.filter((e) => e.endMs > now && e.endMs - now <= leadMs).sort((a, b) => a.endMs - b.endMs)
}

/** One thing and one end time announce once: a moved end gives a new key. */
export const warnKey = (kind: 'delegation' | 'token', id: string, endMs: number): string => `${kind}:${id}:${endMs}`
