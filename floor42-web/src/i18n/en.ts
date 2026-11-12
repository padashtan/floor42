// The English catalogue: the reference, merged from the per-area files (*.en.ts). Every key must exist in the French
// catalogue (fr.ts, the type check enforces it). {name} placeholders are filled by t(key, { name: value }).
import { shellEn } from './shell.en'
import { missionsEn } from './missions.en'
import { workshopEn } from './workshop.en'
import { eventLinesEn } from './event-lines.en'
import { statusPanelsEn } from './status-panels.en'
import { deliverablesEn } from './deliverables.en'
import { terminalCostEn } from './terminal-cost.en'
import { tutorialEn } from './tutorial.en'
import { meetingVoicesEn } from './meeting-voices.en'
import { librariesEn } from './libraries.en'
import { proofEn } from './proof.en'
import { remoteEn } from './remote.en'
import { missionFormEn } from './missionForm.en'

export const en = {
  ...shellEn,
  ...missionsEn,
  ...workshopEn,
  ...eventLinesEn,
  ...statusPanelsEn,
  ...deliverablesEn,
  ...terminalCostEn,
  ...tutorialEn,
  ...meetingVoicesEn,
  ...librariesEn,
  ...proofEn,
  ...remoteEn,
  ...missionFormEn,
} as const
