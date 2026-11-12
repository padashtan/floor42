import type { Ref } from 'vue'
import type { MenuAction } from '../components/ContextMenu.vue'
import type { MachineId } from '../scene/mapping'
import type { SceneTarget } from '../scene/kit'
import { callAt } from '../state/meetingCall'
import { resumeAgent } from '../state/office'
import { panelFlag, setPanel } from '../state/openPanels'
import { placeOfTable } from './meetingTurns'
import { fire, suspend } from './useAgentConfirm'

/** What the context menu's choices drive: App.vue's panel targets and scene, handed over once. */
export interface MenuContext {
  profileOf: Ref<string | null>
  requestsOf: Ref<string | null>
  outputsOf: Ref<MachineId | null>
  settingsFor: Ref<string | null>
  focus(t: SceneTarget): void
  select(t: SceneTarget): void
  openDesk(question: string | null): void
}

/** A machine's outputs panel, or the mainframe's when the target is anything else. */
export function machineOf(t: SceneTarget): MachineId {
  return t.kind === 'machine' ? (t.id as MachineId) : 'mainframe-1'
}

/** The context menu's choice on a target: a panel opens on it, or the agent is resumed, dismissed, suspended. */
export function pickAction(c: MenuContext, action: MenuAction, target: SceneTarget): void {
  if (action === 'focus') c.focus(target)
  if (action === 'desk') c.openDesk(null)
  if (action === 'details') c.select(target)
  if (action === 'board') panelFlag('board').value = true
  if (action === 'profile') c.profileOf.value = target.id
  if (action === 'requests') c.requestsOf.value = target.id
  if (action === 'outputs') c.outputsOf.value = machineOf(target)
  if (action === 'workshop') panelFlag('workshop').value = true
  if (action === 'meeting') panelFlag('meeting').value = true
  if (action === 'callMeeting') callAt.value = placeOfTable(target.id)
  if (action === 'callMeeting') setPanel('callMeeting', true)
  if (action === 'identities' || action === 'iamExchanges') setPanel(action, true)
  if (action === 'usage') panelFlag('usage').value = true
  if (action === 'console') panelFlag('console').value = true
  if (action === 'settings') c.settingsFor.value = target.id
  if (action === 'resume') void resumeAgent(target.id)
  if (action === 'fire') void fire(target.id)
  if (action === 'suspend') void suspend(target.id)
}
