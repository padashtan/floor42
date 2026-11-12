import { t } from '../i18n'
import { office } from '../state/office'

/** The name of the agent on a ticket, or what stands for nobody. */
export function agentName(id: string | null): string {
  return office.agents.find((a) => a.id === id)?.name ?? id ?? t('board.unassigned')
}
