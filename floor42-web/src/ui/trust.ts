import type { AgentView, TrustLevel } from '../api/types'
import { t } from '../i18n'
import type { TodoRow } from './todo'

/** From the most restricted to the most trusted, as the daemon's `TrustLevel` declares them. */
export const LEVELS: readonly TrustLevel[] = ['SHADOW', 'PROBATION', 'STANDARD']

/** The levels below this one, nearest first: where a plain click may narrow it to. */
export const levelsBelow = (level: TrustLevel): TrustLevel[] => LEVELS.slice(0, LEVELS.indexOf(level)).reverse()

/** The levels above this one, nearest first: where a proven click may widen it to. */
export const levelsAbove = (level: TrustLevel): TrustLevel[] => LEVELS.slice(LEVELS.indexOf(level) + 1)

/** The To do row that proposes raising an agent one level: only when the daemon says its record earned it. */
export function trustRow(a: Pick<AgentView, 'id' | 'name' | 'trust' | 'trustSince' | 'acceptedPatches' | 'raiseProposed'>): TodoRow | null {
  const to = levelsAbove(a.trust)[0]
  if (!a.raiseProposed || !to) return null
  return {
    key: `t:${a.id}:${a.trust}`,
    category: 'decisions',
    text: t('todo.trustRaise', { agent: a.name, count: a.acceptedPatches, from: t(`trust.level.${a.trust}`), to: t(`trust.level.${to}`) }),
    mission: null,
    agent: a.id,
    since: a.trustSince,
    go: { panel: 'profile', agent: a.id },
  }
}
