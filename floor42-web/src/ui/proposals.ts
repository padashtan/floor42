import type { ActionView } from '../api/types'
import { safeToType } from './prefill'

/** The code of the decision basis a shadow agent's call carries (ADR-0082, `DecisionBasis.CODE_TRUST_SHADOW`). */
export const SHADOW_BASIS = 'trustShadow'

/** The longest command typed for the director: a proposal is read before Enter, not pasted in bulk. */
export const TYPED_MAX = 1000

/** The shadow proposals among a mission's actions, in the order they were asked. */
export function proposalsOf(actions: readonly ActionView[]): ActionView[] {
  return actions.filter((a) => a.stage === 'DENIED' && a.decisionBasisCode === SHADOW_BASIS)
}

/**
 * What "Put in my terminal" types for a proposal: a shell command, behind Claude Code's `!` so the line runs as a shell
 * command once the director presses Enter, never ending in a return. Null for any other tool (a Write or an Edit holds
 * only a summary, not content) and for a command that is not plain printable ASCII, so nothing odd reaches the terminal.
 */
export function typedFor(a: Pick<ActionView, 'tool' | 'summary'>): string | null {
  if (a.tool !== 'Bash') return null
  const text = `! ${a.summary.trim()}`
  return a.summary.trim().length > 0 && text.length <= TYPED_MAX && safeToType(text) ? text : null
}
