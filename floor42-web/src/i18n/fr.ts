// The French catalogue, merged from the per-area files (*.fr.ts). Typed against the English one: a missing or unknown
// key fails the build.
import type { en } from './en'
import { shellFr } from './shell.fr'
import { missionsFr } from './missions.fr'
import { workshopFr } from './workshop.fr'
import { eventLinesFr } from './event-lines.fr'
import { statusPanelsFr } from './status-panels.fr'
import { deliverablesFr } from './deliverables.fr'
import { terminalCostFr } from './terminal-cost.fr'
import { tutorialFr } from './tutorial.fr'
import { meetingVoicesFr } from './meeting-voices.fr'
import { librariesFr } from './libraries.fr'
import { proofFr } from './proof.fr'
import { dossierFr } from './dossier.fr'
import { oversightFr } from './oversight.fr'
import { remoteFr } from './remote.fr'
import { missionFormFr } from './missionForm.fr'
import { mcpFr } from './mcp.fr'
import { standupFr } from './standup.fr'
import { trustFr } from './trust.fr'

export const fr: Record<keyof typeof en, string> = {
  ...shellFr,
  ...missionsFr,
  ...workshopFr,
  ...eventLinesFr,
  ...statusPanelsFr,
  ...deliverablesFr,
  ...terminalCostFr,
  ...tutorialFr,
  ...meetingVoicesFr,
  ...librariesFr,
  ...proofFr,
  ...dossierFr,
  ...oversightFr,
  ...remoteFr,
  ...missionFormFr,
  ...mcpFr,
  ...standupFr,
  ...trustFr,
}
