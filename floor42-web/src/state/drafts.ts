import { reactive, ref } from 'vue'

/**
 * What the director has typed in the forms of the HUD, kept here and not in the panels: a panel that makes room for
 * another (ui/panelLayout.ts) is unmounted, and its fields must survive. Screen state only, nothing reaches the server.
 */
export interface MissionDraft {
  title: string
  objective: string
  repository: string
  /** Optional checks command, set on the mission once created (the desk's Checks section does the same later). */
  checks: string
  /** No committee, an architecture committee or an idea committee. */
  panel: 'none' | 'architecture' | 'idea'
  letGeoffreyPlan: boolean
  seated: string[]
  /** Where the accepted deliverables are copied (ADR-0051): inside the project, or an absolute folder; blank: none. */
  deliverTo: string
}

export interface HireDraft {
  name: string
  title: string
  workRole: string
  model: string
  effort: string
}

export interface MeetingDraft {
  agenda: string
  text: string
}

/** The committee seats whose member left: a blank form does not seat them (MissionControl reads them on mount). */
const vacantSeats = ref<readonly string[]>([])
export function setVacantSeats(keys: readonly string[]): void {
  vacantSeats.value = [...keys]
}

/** The default folder for a mission's deliverables, inside the project (ADR-0051). */
export const DEFAULT_DELIVER_TO = 'deliverables'

const emptyMission = (): MissionDraft => ({
  title: '',
  objective: '',
  repository: '',
  checks: '',
  panel: 'none',
  letGeoffreyPlan: true,
  seated: ['security', 'developer'].filter((k) => !vacantSeats.value.includes(k)),
  deliverTo: DEFAULT_DELIVER_TO,
})
const emptyHire = (): HireDraft => ({
  name: '',
  title: '',
  workRole: 'DEVELOPER',
  model: 'claude-sonnet-5-5',
  effort: 'medium',
})
const emptyMeeting = (): MeetingDraft => ({ agenda: '', text: '' })

export const drafts = reactive({ mission: emptyMission(), hire: emptyHire(), meeting: emptyMeeting() })

/** Back to a blank form, every field including the choices (the committee, "let Geoffrey plan"). */
export const resetMissionDraft = (): void => void Object.assign(drafts.mission, emptyMission())
export const resetHireDraft = (): void => void Object.assign(drafts.hire, emptyHire())
export const resetMeetingDraft = (): void => void Object.assign(drafts.meeting, emptyMeeting())

const filled = (...fields: string[]): boolean => fields.some((f) => f.trim() !== '')
const same = (a: readonly string[], b: readonly string[]): boolean => a.length === b.length && a.every((x) => b.includes(x))

/** Every form back to blank: signing out leaves nothing typed behind. */
export function resetAllDrafts(): void {
  resetMissionDraft()
  resetHireDraft()
  resetMeetingDraft()
}

/** A draft is something typed or chosen that differs from what a blank form shows. */
export const missionDrafted = (m: MissionDraft = drafts.mission): boolean => {
  const blank = emptyMission()
  return (
    filled(m.title, m.objective, m.repository, m.checks) ||
    m.panel !== blank.panel ||
    m.letGeoffreyPlan !== blank.letGeoffreyPlan ||
    m.deliverTo !== blank.deliverTo ||
    !same(m.seated, blank.seated)
  )
}
export const hireDrafted = (h: HireDraft = drafts.hire): boolean => {
  const blank = emptyHire()
  return filled(h.name, h.title) || h.workRole !== blank.workRole || h.model !== blank.model || h.effort !== blank.effort
}

/** The panels that hold something the director typed, by panel id (ui/panelMounts.ts). */
export const DRAFT_HOLDERS: Record<string, () => boolean> = {
  desk: () => missionDrafted(),
  hire: () => hireDrafted(),
  callMeeting: () => filled(drafts.meeting.agenda, drafts.meeting.text),
}

/** Which of the shown panels hold a draft: such a panel is never replaced by something that opens by itself. */
export function panelsWithDraft(shown: readonly string[]): string[] {
  return shown.filter((id) => DRAFT_HOLDERS[id]?.())
}
