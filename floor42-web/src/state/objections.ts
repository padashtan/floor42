import { objectionsApi } from '../api/objections'
import type { HeldDeveloper } from '../api/types'
import { t } from '../i18n'
import { agentName } from '../ui/ticketInfo'
import { type Row, heldFor, sessionCounts, toRequest } from '../ui/objections'
import { confirmed } from './confirm'
import { workshopCall } from './office'

/**
 * The director's objections at acceptance (ADR-0060). Said first how many sessions the round starts, which cost money,
 * and, for new work, whose changes must be accepted in the workshop before it can start; after the yes, one call, and
 * the company is read again. False when the director said no or the daemon refused (the refusal is shown on the board).
 */
export async function objectToDelivery(
  mission: string,
  rows: Row[],
  checks: number,
  held: HeldDeveloper[] = [],
): Promise<boolean> {
  const waits = heldFor(rows, held)
  const lines = [t('objections.confirm', { count: rows.length, ...sessionCounts(rows, checks) })]
  if (waits.length)
    lines.push(
      t('objections.held', {
        who: waits.map((h) => agentName(h.agent)).join(', '),
        tickets: waits.map((h) => h.ticket).join(', '),
      }),
    )
  const yes = await confirmed({ message: lines.join('\n'), confirmLabel: t('objections.confirmLabel') })
  if (!yes) return false
  return (await workshopCall(() => objectionsApi.object(mission, { objections: toRequest(rows) }))) !== null
}
