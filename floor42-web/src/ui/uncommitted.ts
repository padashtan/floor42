import type { UncommittedCheck, UncommittedWorktreeState } from '../api/types'
import { t, tr } from '../i18n'

/** At most this many file names in one line; the rest is counted. */
const SHOWN = 3

/** The worktrees that are not clean, as the screen lists them (ADR-0055). */
export function notClean(check: UncommittedCheck): UncommittedWorktreeState[] {
  return check.worktrees.filter((w) => w.state !== 'CLEAN')
}

/** One line per worktree that is not clean, in the screen's language: what is not committed, or why it is unknown. */
export function uncommittedLines(check: UncommittedCheck): string[] {
  return notClean(check).map((w) => {
    if (w.state === 'UNKNOWN') {
      const reason = tr(`uncommitted.reason.${w.reason ?? ''}`, w.reason ?? '')
      return t('uncommitted.unknown', { agent: w.agent, reason })
    }
    const more = w.count - Math.min(w.paths.length, SHOWN)
    const files = w.paths.slice(0, SHOWN).join(', ') + (more > 0 ? ' ' + t('uncommitted.more', { count: more }) : '')
    return t('uncommitted.dirty', { agent: w.agent, count: w.count, files })
  })
}
