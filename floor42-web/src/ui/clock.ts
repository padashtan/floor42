/** The page's "now" for every age and duration on screen: the real clock, or the replay's in the online demo (ADR-0031). */
let source: () => number = () => Date.now()

export const nowMs = (): number => source()

export function setClock(fn: () => number): void {
  source = fn
}
