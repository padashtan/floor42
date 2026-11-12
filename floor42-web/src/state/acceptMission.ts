import { ApiError, api } from '../api/client'
import { proofApi } from '../api/proof'
import type { UncommittedCheck } from '../api/types'
import { t } from '../i18n'
import { proofLines } from '../ui/proof'
import { uncommittedLines } from '../ui/uncommitted'
import { confirmed } from './confirm'

/** How an acceptance ended: accepted, the director said no, or the worktrees kept changing under the check. */
export type Acceptance = 'accepted' | 'declined' | 'changing'

/** The daemon's refusals that mean "what you saw changed": the director is asked again. */
const CHANGED = new Set(['UNCOMMITTED_DELIVERABLES', 'UNPROVEN_DELIVERY'])

/**
 * Accepts the mission. A delivery that is not proven is said first and needs the director's yes (ADR-0058); work not
 * in a commit, or a worktree Floor42 cannot read, is said next and needs it too (ADR-0055). Each yes sends the token of
 * what was seen, and the daemon records it with the completion; when either changed before the acceptance reached the
 * daemon, the director is asked again. Any other refusal is thrown, for the caller to show.
 */
export async function acceptMission(mission: string, summary: string, withIgnored: boolean): Promise<Acceptance> {
  for (let round = 0; round < 3; round++) {
    const proof = await proofApi.acceptance(mission)
    if (proof.state && !(await acceptUnproven(mission))) return 'declined'
    const check = await api.uncommitted(mission)
    if (!check.clean && !(await acceptUncommitted(check))) return 'declined'
    try {
      await api.complete(mission, summary, withIgnored, check.clean ? null : check.token, proof.state ? proof.token : null)
      return 'accepted'
    } catch (e) {
      if (!(e instanceof ApiError && CHANGED.has(e.code))) throw e
    }
  }
  return 'changing'
}

/** The reasons, read from the proof; a proof Floor42 cannot read is said as such, never taken for a pass. */
async function acceptUnproven(mission: string): Promise<boolean> {
  const lines = await proofApi.proof(mission).then(proofLines, () => [t('prove.line.cannotCheck')])
  return confirmed({
    message: t('prove.confirm', { lines: lines.join('\n') }),
    confirmLabel: t('prove.acceptAnyway'),
    danger: true,
  })
}

const acceptUncommitted = (check: UncommittedCheck) =>
  confirmed({
    message: t('uncommitted.confirm', { lines: uncommittedLines(check).join('\n') }),
    confirmLabel: t('uncommitted.acceptAnyway'),
    danger: true,
  })
