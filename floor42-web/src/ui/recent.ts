import type { MissionSummary, ProjectView } from '../api/types'

/**
 * Recent projects, resuming work and the mission on the board (1.6.0). Pure functions, tested; the choice of the
 * mission on the board is a view of this browser (kept in localStorage), never a fact of the journal.
 */
export const BOARD_KEY = 'floor42.board.mission'

/** The explicit empty choice: the director left the board empty (after an archive); the automatic choice stays off. */
export const BOARD_EMPTY = '@empty'

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function storage(): Store | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

/** The mission the director put on the board in this browser, or null for the automatic choice. */
export function boardChoice(store: Store | null = storage()): string | null {
  try {
    return store?.getItem(BOARD_KEY) || null
  } catch {
    return null
  }
}

export function keepBoardChoice(id: string | null, store: Store | null = storage()): void {
  try {
    if (id) store?.setItem(BOARD_KEY, id)
    else store?.removeItem(BOARD_KEY)
  } catch {
    // Storage blocked: the choice lasts until the page reloads.
  }
}

/**
 * The mission shown on the board: the director's choice while it exists, else the most recent active one (in progress
 * or awaiting approval), else the most recent of all.
 */
export function boardMission(missions: MissionSummary[], chosen: string | null): MissionSummary | null {
  if (chosen === BOARD_EMPTY) return null
  // An archived mission has left the board and the lists: neither a choice nor the automatic pick may bring it back.
  const shown = missions.filter((m) => !m.archived)
  const picked = chosen ? shown.find((m) => m.id === chosen) : undefined
  if (picked) return picked
  return (
    [...shown].reverse().find((m) => m.status === 'IN_PROGRESS' || m.status === 'AWAITING_APPROVAL') ??
    shown.at(-1) ??
    null
  )
}

/** The recent list of the picker: what the director did not forget, nor a project Floor42 made for an idea. */
export function recentProjects(projects: ProjectView[]): ProjectView[] {
  return projects.filter((p) => !p.forgotten && !p.office)
}

/** The folder's last name, for a short label. */
export function folderName(path: string): string {
  return path.split(/[\\/]/).filter(Boolean).pop() ?? path
}
