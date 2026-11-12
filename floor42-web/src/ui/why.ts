/**
 * Why a control is disabled, for its tooltip: the first reason whose condition holds, else undefined (no title).
 * A disabled button that says nothing reads as a broken one.
 */
export function why(...reasons: [boolean, string][]): string | undefined {
  return reasons.find(([holds]) => holds)?.[1]
}
