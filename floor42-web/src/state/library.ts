import { reactive } from 'vue'
import { api } from '../api/client'
import type { ProjectView } from '../api/types'
import { coverOf } from '../scene/cover'
import type { ThemeId } from '../scene/palette'
import { recentProjects } from '../ui/recent'
import { drafts } from './drafts'
import { setPanel } from './openPanels'

/**
 * The library (ADR-pending): the projects the director knows, as the picker lists them, shown as books on the panel
 * and as spines on the office's bookcase. Choosing one hands it to the new-mission form: the same `repository` draft
 * the project picker edits, so nothing is chosen twice.
 */
export const library = reactive<{ projects: ProjectView[]; loaded: boolean; error: string }>({ projects: [], loaded: false, error: '' })

/** What the desk and the project picker read once: the form the library chose, or the Mission tab a plan approval needs. */
export const wanted = reactive<{ newMission: boolean; browse: boolean; missionTab: boolean }>({ newMission: false, browse: false, missionTab: false })

/** Reads the projects again (a new mission, a pin, a forgotten folder changes the list). */
export async function loadLibrary(): Promise<void> {
  try {
    library.projects = recentProjects(await api.projects())
    library.error = ''
  } catch (e) {
    library.error = e instanceof Error ? e.message : String(e)
  } finally {
    library.loaded = true
  }
}

/** The spine colours of the bookcase: one per project, newest first, in the colours of the decade. */
export function spineColors(projects: readonly ProjectView[], era: ThemeId): number[] {
  return projects.map((p) => coverOf(p.path, era).spine)
}

/**
 * The director picked a book: the new-mission form opens with that project (or, for `null`, with the folder browser
 * open). The library closes: the form is the next step.
 */
export function startMissionOn(path: string | null): void {
  if (path !== null) drafts.mission.repository = path
  wanted.newMission = true
  wanted.browse = path === null
  setPanel('library', false)
  setPanel('desk', true)
}
