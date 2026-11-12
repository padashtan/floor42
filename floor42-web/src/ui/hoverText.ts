import type { ArchiveView } from '../api/types'
import { MACHINES } from '../components/catalog'
import { t, tr } from '../i18n'
import type { SceneTarget } from '../scene/kit'
import { boardColumns } from '../scene/mapping'
import { office } from '../state/office'

const NAMES = { board: 'hover.board', desk: 'hover.desk', table: 'hover.table', decor: 'hover.decor', archive: 'hover.archive', library: 'hover.library', identity: 'hover.identity' } as const

/** The board's tooltip: the mission on display and its tickets per column. */
function boardText(): string | null {
  if (!office.mission) return null
  const c = boardColumns(office.mission.tickets)
  return t('hover.boardCounts', {
    mission: office.mission.id,
    proposed: c.proposed.length,
    todo: c.todo.length,
    doing: c.doing.length,
    done: c.done.length,
  })
}

/** The tooltip over what the pointer is on in the scene: an agent and its status, a machine, the board's counts... */
export function hoverText(target: SceneTarget, archive: ArchiveView | null): string {
  if (target.kind === 'agent') {
    const a = office.agents.find((x) => x.id === target.id)
    return a ? `${a.name} · ${tr(`status.${a.status}`, a.status.toLowerCase())}` : target.id
  }
  if (target.kind === 'robot') return t('hover.robot')
  if (target.kind === 'pet') return t(target.id === 'dog' ? 'hover.dog' : 'hover.cat')
  if (target.kind === 'machine') return MACHINES[target.id as keyof typeof MACHINES]?.name ?? target.id
  if (target.kind === 'archive' && archive?.available) {
    return t('hover.archiveCounts', {
      branches: archive.branches.length,
      commits: archive.branches.reduce((n, b) => n + Math.max(0, b.ahead), 0),
    })
  }
  if (target.kind === 'board') {
    const text = boardText()
    if (text) return text
  }
  return target.kind in NAMES ? t(NAMES[target.kind as keyof typeof NAMES]) : target.id
}
