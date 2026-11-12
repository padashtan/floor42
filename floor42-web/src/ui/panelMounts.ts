import { type Component, type Ref, ref, watch } from 'vue'
import type { ArchiveView } from '../api/types'
import AgentProfile from '../components/AgentProfile.vue'
import AgentRequests from '../components/AgentRequests.vue'
import AgentSettings from '../components/AgentSettings.vue'
import ApiConsole from '../components/ApiConsole.vue'
import ArchivePanel from '../components/ArchivePanel.vue'
import AwayPanel from '../components/AwayPanel.vue'
import BoardPanel from '../components/BoardPanel.vue'
import LibraryPanel from '../components/LibraryPanel.vue'
import CapsPanel from '../components/CapsPanel.vue'
import CoreDismissal from '../components/CoreDismissal.vue'
import CostsPanel from '../components/CostsPanel.vue'
import DeliverablesPanel from '../components/DeliverablesPanel.vue'
import DirectorDesk from '../components/DirectorDesk.vue'
import DirectorMeeting from '../components/DirectorMeeting.vue'
import DirectorTerminal from '../components/DirectorTerminal.vue'
import EventLog from '../components/EventLog.vue'
import FileViewerPanel from '../components/FileViewerPanel.vue'
import HireAgent from '../components/HireAgent.vue'
import MachinePanel from '../components/MachinePanel.vue'
import MeetingRoom from '../components/MeetingRoom.vue'
import MissionEnd from '../components/MissionEnd.vue'
import ObjectionsPanel from '../components/ObjectionsPanel.vue'
import ProgressPanel from '../components/ProgressPanel.vue'
import ProvePanel from '../components/ProvePanel.vue'
import ResumePanel from '../components/ResumePanel.vue'
import SandboxReadiness from '../components/SandboxReadiness.vue'
import SelectionPanel from '../components/SelectionPanel.vue'
import IdentitiesPanel from '../components/IdentitiesPanel.vue'
import IdpExchangesPanel from '../components/IdpExchangesPanel.vue'
import SessionsPanel from '../components/SessionsPanel.vue'
import SettingsPanel from '../components/SettingsPanel.vue'
import SkillsCatalogue from '../components/SkillsCatalogue.vue'
import RemoteStrip from '../components/RemoteStrip.vue'
import TodoPanel from '../components/TodoPanel.vue'
import UsagePanel from '../components/UsagePanel.vue'
import WorkflowsPanel from '../components/WorkflowsPanel.vue'
import WorkshopPanel from '../components/WorkshopPanel.vue'
import type { MachineId } from '../scene/mapping'
import type { SceneTarget } from '../scene/kit'
import { office } from '../state/office'
import { panels, requestOf, setPanel, stampRequest } from '../state/openPanels'
import { isPinned } from '../state/pinned'
import { remoteTiles } from '../state/remote'
import { viewing } from '../state/viewer'
import { awayActions, awayShown } from './away'
import { closeWorkflows } from './workflowGuard'

/** A listener of a panel's event: its argument, if any, is the panel's own (an id, a question). */
type Listener = (arg: never) => void

/** One panel of the HUD: what it is, when it shows, what it is given, what its events do. */
export interface PanelMount {
  id: string
  component: Component
  shown(): boolean
  props?(): Record<string, unknown>
  on?: Record<string, Listener>
  /** Closes it (its `close` event, Escape); absent for what always shows. */
  close?(): void
  /** Whether Escape closes it now, when that is not simply `shown()` (a panel that is always mounted). */
  escapable?(): boolean
  /** Opens it again as it was closed: the way back from a child panel (ui/panelLayout.ts, PARENTS). */
  open?(): void
}

/** What the panels act on in App.vue: its selection, what it opens, the agent actions it confirms. */
export interface PanelContext {
  selected: Ref<SceneTarget | null>
  deskFocus: Ref<string | null>
  archive: Ref<ArchiveView | null>
  /** The end-of-mission report (ADR-0039) of this mission, open; null when closed. */
  reportOf: Ref<string | null>
  profileOf: Ref<string | null>
  requestsOf: Ref<string | null>
  outputsOf: Ref<MachineId | null>
  settingsFor: Ref<string | null>
  openDesk(question: string | null): void
  selectAgent(id: string): void
  suspend(id: string): void
  resume(id: string): void
  dismiss(id: string): void
}

const open = (id: string) => () => setPanel(id, true)

/** A registry panel (ui/actions.ts): shown while its flag is set, closed by clearing it. */
function flagged(id: string, component: Component, extra: Partial<PanelMount> = {}): PanelMount {
  return { id, component, shown: () => !!panels[id], close: () => setPanel(id, false), open: () => setPanel(id, true), ...extra }
}

/** A panel about one thing (an agent, a machine, a mission): shown while its ref holds it, closed by clearing it. */
function held<T>(id: string, component: Component, ref: Ref<T | null>, prop: string, extra: Partial<PanelMount> = {}): PanelMount {
  // What it held when it was closed, so that a way back (a child panel closing) can put it back.
  let last: T | null = null
  watch(ref, (v) => v !== null && stampRequest(id), { flush: 'sync' })
  return {
    id,
    component,
    shown: () => ref.value !== null,
    props: () => ({ [prop]: ref.value }),
    close: () => ((last = ref.value), (ref.value = null)),
    open: () => void (last !== null && (ref.value = last)),
    ...extra,
  }
}

/** Closes one panel and selects an agent: a click on an agent's name inside a panel. */
const toAgent = (c: PanelContext, id: string) => (agent: string) => {
  setPanel(id, false)
  c.selectAgent(agent)
}

/** The panels of the director's floor and of the work. */
function workPanels(c: PanelContext): PanelMount[] {
  const mission = () => office.mission?.workspace ?? null
  return [
    flagged('hire', HireAgent),
    flagged('core', CoreDismissal),
    flagged('settings', SettingsPanel),
    flagged('progress', ProgressPanel, {
      on: { question: (q: string) => c.openDesk(q), desk: () => c.openDesk(null), workshop: open('workshop'), meeting: open('meeting'), report: (id: string) => (c.reportOf.value = id) },
    }),
    flagged('resume', ResumePanel, { on: { desk: (q: string) => (setPanel('resume', false), c.openDesk(q)) } }),
    flagged('caps', CapsPanel),
    flagged('workflows', WorkflowsPanel, { close: closeWorkflows }),
    flagged('costs', CostsPanel),
    flagged('sessions', SessionsPanel),
    flagged('identities', IdentitiesPanel),
    flagged('iamExchanges', IdpExchangesPanel),
    flagged('sandbox', SandboxReadiness, { props: () => ({ framed: true, project: mission(), check: office.mission?.checkCommand ?? null }) }),
    { id: 'away', component: AwayPanel, shown: () => !office.needsLogin, close: () => awayActions.close(), escapable: () => awayShown.value, on: { progress: open('progress'), desk: () => c.openDesk(null) } },
    flagged('terminal', DirectorTerminal, { props: () => ({ project: mission() }) }),
    flagged('skills', SkillsCatalogue, { props: () => ({ mission: office.mission?.id ?? null }) }),
    { id: 'log', component: EventLog, shown: () => true, on: { agent: (id: string) => c.selectAgent(id), question: (q: string) => c.openDesk(q) } },
    { id: 'todo', component: TodoPanel, shown: () => true, on: { question: (q: string) => c.openDesk(q), workshop: open('workshop'), report: (id: string) => (c.reportOf.value = id), progress: open('progress') } },
    { id: 'remote', component: RemoteStrip, shown: () => remoteTiles.value.length > 0, on: { agent: (id: string) => c.selectAgent(id) } },
    { id: 'selection', component: SelectionPanel, shown: () => true, props: () => ({ target: c.selected.value }), on: { desk: () => c.openDesk(null) } },
    flagged('desk', DirectorDesk, { props: () => ({ focus: c.deskFocus.value }), on: { agent: toAgent(c, 'desk') } }),
    flagged('workshop', WorkshopPanel),
    flagged('meeting', MeetingRoom),
    flagged('library', LibraryPanel),
    // The director's meeting (ADR-0047).
    flagged('callMeeting', DirectorMeeting),
    flagged('console', ApiConsole),
    flagged('usage', UsagePanel),
  ]
}

/** The panels of the mission's results, and those about one agent or machine. */
function resultPanels(c: PanelContext): PanelMount[] {
  const report = (then: string) => () => ((c.reportOf.value = null), setPanel(then, true))
  // The mission of the report Prove was opened from, which may not be the current one; cleared as Prove closes.
  const proveOf = ref<string | null>(null)
  // The request that came with it: Prove opened again by anything else (the bar, the palette) is for the current mission.
  let proveRequest = -1
  watch(() => panels.prove, (on) => on || (proveOf.value = null))
  return [
    flagged('archive', ArchivePanel, { props: () => ({ archive: c.archive.value }), on: { agent: toAgent(c, 'archive'), deliverables: open('deliverables') } }),
    flagged('deliverables', DeliverablesPanel, { props: () => ({ mission: office.mission?.id ?? null }), on: { agent: toAgent(c, 'deliverables') } }),
    flagged('board', BoardPanel, {
      on: {
        agent: toAgent(c, 'board'),
        workshop: () => (setPanel('board', false), setPanel('workshop', true)),
        deliverables: open('deliverables'),
        report: () => (setPanel('board', false), (c.reportOf.value = office.mission?.id ?? null)),
      },
    }),
    held('report', MissionEnd, c.reportOf, 'mission', { on: { deliverables: open('deliverables'), archive: report('archive'), workshop: report('workshop'), prove: () => ((proveOf.value = c.reportOf.value), report('prove')(), (proveRequest = requestOf('prove'))) } }),
    // The objections at acceptance (ADR-0060), opened from the board.
    flagged('objections', ObjectionsPanel),
    // Prove the delivery (ADR-0058), between the review and the acceptance.
    flagged('prove', ProvePanel, { props: () => ({ mission: (requestOf('prove') === proveRequest ? proveOf.value : null) ?? office.mission?.id ?? null }) }),
    held('profile', AgentProfile, c.profileOf, 'agent', {
      on: {
        settings: () => (c.settingsFor.value = c.profileOf.value),
        follow: () => c.selectAgent(c.profileOf.value!),
        suspend: () => c.suspend(c.profileOf.value!),
        resume: () => c.resume(c.profileOf.value!),
        dismiss: () => c.dismiss(c.profileOf.value!),
      },
    }),
    held('requests', AgentRequests, c.requestsOf, 'agent', { on: { profile: () => ((c.profileOf.value = c.requestsOf.value), (c.requestsOf.value = null)) } }),
    held('machine', MachinePanel, c.outputsOf, 'machine'),
    held('agent-settings', AgentSettings, c.settingsFor, 'agent'),
    // One file of a mission (ADR-0051), opened from the deliverables or the workshop: their child (panelLayout.ts).
    held('viewer', FileViewerPanel, viewing, 'file'),
  ]
}

/**
 * Every panel of the HUD, in the order App.vue mounts them (later ones above earlier ones). A new panel adds one line
 * here: a registry action's panel with {@link flagged}, a panel about one thing with {@link held}.
 */
export function panelTable(c: PanelContext): PanelMount[] {
  return [...workPanels(c), ...resultPanels(c)]
}

/** The order Escape closes panels in, before the last one opened (state/openPanels.ts). */
const ESCAPE_ORDER = [
  'viewer', 'report', 'prove', 'objections', 'agent-settings', 'hire', 'core', 'settings', 'progress', 'resume', 'caps', 'workflows', 'costs', 'sandbox', 'skills',
  'requests', 'profile', 'machine', 'deliverables', 'board', 'archive', 'usage', 'console', 'callMeeting', 'meeting', 'workshop', 'desk',
]

/** Closes the first open panel in Escape's order; false when none of them is open. */
export function closeOnEscape(table: PanelMount[]): boolean {
  for (const id of ESCAPE_ORDER) {
    const p = table.find((m) => m.id === id)
    if (p?.close && p.shown() && !isPinned(id)) {
      p.close()
      return true
    }
  }
  return false
}

/**
 * Escape closes the "while you were away" summary (docked, always mounted) last, once no panel is left to close.
 * False when it is not on screen.
 */
export function closeAwayOnEscape(table: PanelMount[]): boolean {
  const p = table.find((m) => m.id === 'away')
  if (!p?.close || !p.escapable?.()) return false
  p.close()
  return true
}

/** The listeners a mounted panel gets: its own, and `close` when it can be closed. */
export function listenersOf(p: PanelMount): Record<string, Listener> {
  return p.close ? { ...p.on, close: p.close } : { ...p.on }
}
