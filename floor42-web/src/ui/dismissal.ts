import type { AgentView } from '../api/types'
import { t } from '../i18n'
import { why } from './why'

/** The ticket an agent is on now: in progress or in review. Shown in the menu, and handed back on dismissal. */
export function currentTicket<T extends { assignee: string | null; status: string }>(
  agent: string,
  tickets: T[],
): T | undefined {
  return tickets.find((x) => x.assignee === agent && (x.status === 'IN_PROGRESS' || x.status === 'IN_REVIEW'))
}

/**
 * Whether the director may dismiss an agent now, and what it means, for every place that offers it (the scene and
 * crew menu, the profile). Nobody leaves mid-session; the two chairs stay for good. Anyone else may go, whatever
 * tickets they hold. The server enforces the same rules and says why when it refuses (AGENT_BUSY, AGENT_NEEDED).
 */
export function dismissal(
  agent: Pick<AgentView, 'name' | 'stays' | 'atWork'>,
  current: { id: string; status: string } | undefined,
): { refused?: string; hint: string } {
  const refused = why(
    [agent.stays, t('menu.dismissNeeded', { name: agent.name })],
    [agent.atWork, t('menu.dismissAtWork', { name: agent.name })],
  )
  const hint =
    current?.status === 'IN_PROGRESS' ? t('menu.dismissHandsBack', { id: current.id }) : t('menu.dismissHint')
  return refused ? { refused, hint } : { hint }
}
