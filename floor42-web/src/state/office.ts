import { refusalText } from '../ui/refusal'
import { acceptMission } from './acceptMission'
import { THEME_IDS, type ThemeId } from '../scene/palette'
import { reactive } from 'vue'
import { ApiError, api } from '../api/client'
import { EventStream, type StreamState } from '../api/eventStream'
import { eventText } from '../i18n/events'
import { t, type Key } from '../i18n'
import { boardChoice, boardMission, keepBoardChoice } from '../ui/recent'
import type {
  CommitteePanel,
  AgentView,
  EventView,
  MeetingView,
  MissionSummary,
  MissionView,
  QuestionView,
  Status,
  ToolDecision,
  ToolSettingView,
  NoteView,
  LessonView,
  SettingView,
  UsageView,
} from '../api/types'

export interface LogLine {
  id: string
  at: string
  origin: EventView['origin']
  /** The line's code and values: the log writes it through eventText in the screen's language. */
  code: string | null
  params: Record<string, string> | null
  agent: string | null
  question: string | null
  important: boolean
}

export interface Toast {
  id: string
  text: string
  question: string | null
  /** A panel the toast offers to open (state/openPanels.ts), instead of a question to answer. */
  panel?: string | null
  /** A page the toast offers to go to (the Keycloak sign-in), with its label. */
  link?: { href: string; label: string } | null
  /** An incident: announced at once (role alert) instead of politely. */
  error?: boolean
}

/**
 * Client-side state: a copy of server projections, never a second source of truth. Events trigger reloads; the only
 * thing derived from events alone is the visual log and the scene's momentary reactions.
 */
export const office = reactive({
  status: null as Status | null,
  needsLogin: false,
  error: null as string | null,
  stream: 'connecting' as StreamState,
  agents: [] as AgentView[],
  missions: [] as MissionSummary[],
  mission: null as MissionView | null,
  questions: [] as QuestionView[],
  toolSettings: [] as ToolSettingView[],
  log: [] as LogLine[],
  usage: null as UsageView | null,
  notes: [] as NoteView[],
  /** The director's meetings, newest first (ADR-0047, 0049): the scene seats the open ones' attendees. */
  meetings: [] as MeetingView[],
  lessons: [] as LessonView[],
  toasts: [] as Toast[],
  /** The decade the office is drawn in: a company setting (OFFICE_THEME). */
  theme: '1990s' as ThemeId,
  /** The mission the director put on the board in this browser; null for the automatic choice. */
  boardChoice: boardChoice(),
})

type EventListener = (event: EventView) => void
const listeners: EventListener[] = []
let stream: EventStream | null = null
let reloadTimer: number | undefined
/** The last reload failed: the error on screen is the daemon being unreachable, not a refusal. */
let reloadFailed = false

/** Subscribes to live events (the scene uses this to animate). */
/** Subscribes to live events; returns the unsubscribe function. */
export function onLiveEvent(listener: EventListener): () => void {
  listeners.push(listener)
  return () => {
    const i = listeners.indexOf(listener)
    if (i >= 0) listeners.splice(i, 1)
  }
}

export async function start(): Promise<void> {
  try {
    office.status = await api.status()
  } catch (e) {
    handle(e)
  }
  await reload()
  // Without a session the stream would be refused: wait for the director to log in (the login page reloads the app).
  if (office.needsLogin) return
  await history()
  stream = new EventStream(receive, (s) => (office.stream = s), () => void Promise.all([reload(), history()]))
  stream.open()
}

/**
 * Puts another mission on the board (the "switch mission" control), or back to the automatic choice with null. A view
 * of this browser: nothing is journaled, nothing starts.
 */
export const putOnBoard = (id: string | null): Promise<void> => (choose(id), reload())
/** The board's choice, kept for this browser; a created mission is chosen too, even over a board left empty (pilot). */
const choose = (id: string | null) => ((office.boardChoice = id), keepBoardChoice(id))

/**
 * Each reload takes a number; only the latest one writes. Reloads overlap (a burst of events, a switch of mission while
 * the previous one loads): without it a late answer for M-1 would overwrite the board after the switch to M-2.
 */
let generation = 0

/** Shows the mission the director chose on the board, else the most recent active one, or the most recent one. */
async function reload(): Promise<void> {
  const mine = ++generation
  const stale = () => mine !== generation
  try {
    // Independent reads go out together; the board's mission needs the list first.
    const [agents, missions, questions, usage, notes, lessons, settings, meetings] = await Promise.all([
      api.agents(),
      api.missions(),
      api.openQuestions(),
      api.usage(),
      api.notes(),
      api.lessons(),
      api.settings(),
      // The meetings are the scene's and the meeting panel's: a failed read of them keeps the rest of the office (the
      // panel shows its own read's error).
      api.meetings().catch(() => null),
    ])
    if (stale()) return
    const current = boardMission(missions, office.boardChoice)
    const open = current !== null && current.status !== 'COMPLETED' && current.status !== 'CANCELLED'
    const [mission, toolSettings] = await Promise.all([
      current ? api.mission(current.id) : Promise.resolve(null),
      open && current ? api.toolSettings(current.id) : Promise.resolve([] as ToolSettingView[]),
    ])
    if (stale()) return
    // Written together, from one generation: the screen never mixes two reloads.
    office.agents = agents
    if (meetings) office.meetings = meetings
    office.usage = usage
    office.notes = notes
    office.lessons = lessons
    const theme = settings.find((s) => s.key === 'OFFICE_THEME')?.value
    if (theme && THEME_IDS.includes(theme as ThemeId)) office.theme = theme as ThemeId
    office.missions = missions
    office.questions = questions
    office.mission = mission
    office.toolSettings = toolSettings
    office.needsLogin = false
    // Only a failed reload is cleared by a good one: a refused command stays on screen until the director closes it.
    if (reloadFailed) office.error = null
    reloadFailed = false
  } catch (e) {
    // A later reload is under way: its outcome, not this one's, goes on screen.
    if (stale()) return
    handle(e)
    reloadFailed = true
  }
}

/** The log starts with what already happened, without replaying toasts or scene reactions for it. */
async function history(): Promise<void> {
  try {
    for (const e of await api.events()) office.log.unshift(line(e))
    office.log.splice(80)
  } catch (e) {
    handle(e)
  }
}

function line(event: EventView): LogLine {
  return {
    id: event.eventId,
    at: event.occurredAt,
    origin: event.origin,
    code: event.code ?? null,
    params: event.params ?? null,
    agent: event.agent,
    question: event.question,
    important: event.type === 'QuestionAsked' || event.type === 'IncidentRaised' || event.type === 'ActionDenied',
  }
}

function receive(event: EventView): void {
  // The stream may resend an event the history already showed.
  if (office.log.some((l) => l.id === event.eventId)) return
  office.log.unshift(line(event))
  office.log.splice(80)
  if (event.type === 'QuestionAsked' && event.question) {
    notify(eventText(event.code, event.params), event.question)
  }
  // A question answered (here or elsewhere) or withdrawn no longer waits: its "Answer" toast goes with it.
  if ((event.type === 'QuestionAnswered' || event.type === 'QuestionWithdrawn') && event.question) {
    office.toasts = office.toasts.filter((t) => t.question !== event.question)
  }
  if (event.type === 'IncidentRaised') notify(eventText(event.code, event.params), null, { error: true })
  listeners.forEach((l) => l(event))
  // Events often come in bursts (one command = several events): reload once per burst.
  window.clearTimeout(reloadTimer)
  reloadTimer = window.setTimeout(() => void reload(), 120)
}

function notify(text: string, question: string | null, offer: Pick<Toast, 'panel' | 'link' | 'error'> = {}): void {
  const id = `${Date.now()}-${Math.random()}`
  office.toasts.push({ id, text, question, ...offer })
  window.setTimeout(() => dismiss(id), offer.link ? 30_000 : 12_000)
}

/** A toast that offers to open a panel: what happened must not take the place of what the director is typing. */
export const notifyOpen = (text: string, panel: string): void => notify(text, null, { panel })

export function dismiss(id: string): void {
  const i = office.toasts.findIndex((t) => t.id === id)
  if (i >= 0) office.toasts.splice(i, 1)
}

/** @param askAgain the remembered refusal (its approval question) the director wants to be asked again for */
export async function answer(questionId: string, choice: string, askAgain: string | null = null): Promise<void> {
  try {
    await api.answer(questionId, choice, askAgain)
    await reload()
  } catch (e) {
    handle(e)
  }
}

/** Runs a director command, then reloads; returns false (and shows the refusal) when the daemon refused it. */
async function command(run: () => Promise<unknown>): Promise<boolean> {
  try {
    await run()
    await reload()
    return true
  } catch (e) {
    handle(e)
    return false
  }
}

export const createMission = (
  title: string,
  objective: string,
  repository: string,
  committee: string[] = [],
  plan = false,
  panel: CommitteePanel = 'architecture',
  workflow: string | null = null,
  deliverTo: string | null = null,
  key?: string, mode: 'loop' | 'graph' = 'loop',
) => command(async () => choose((await api.createMission(title, objective, repository, committee, plan, panel, workflow, deliverTo, key, mode)).id))
export const contributeInPerson = (
  mission: string,
  committee: number,
  text: string,
  proposal: string | null,
  position: string | null,
  criticalObjection: string | null,
) => command(() => api.contributeInPerson(mission, committee, text, proposal, position, criticalObjection))
export const hireAgent = (name: string, role: string, workRole: string, model: string, effort: string) =>
  command(() => api.hireAgent(name, role, workRole, model, effort))
/** `value` null goes back to the daemon's default; the panel reads the settings again afterwards. */
export const changeSetting = (key: SettingView['key'], value: string | null) =>
  command(() => api.changeSetting(key, value))
/**
 * The same call for the settings panel, which shows a refusal beside the value instead of the banner: resolves to the
 * daemon's error, or null once saved and reloaded. A lost sign-in still asks to sign in again.
 */
export async function trySetting(key: SettingView['key'], value: string | null): Promise<unknown> {
  try {
    await api.changeSetting(key, value)
    await reload()
    return null
  } catch (e) {
    if (e instanceof ApiError && e.needsLogin) handle(e)
    return e
  }
}
/** A refused dismissal says why, and what the director can do, in the screen's language. */
export async function dismissAgent(agent: string, name: string, reason: string): Promise<boolean> {
  try {
    await api.dismissAgent(agent, reason)
    await reload()
    return true
  } catch (e) {
    if (e instanceof ApiError && DISMISS_REFUSALS.includes(e.code)) {
      office.error = t(`dismiss.refused.${e.code}` as Key, { name })
      return false
    }
    handle(e)
    return false
  }
}

const DISMISS_REFUSALS = ['AGENT_BUSY', 'AGENT_NEEDED', 'ALREADY_DISMISSED']
/** The director stars an agent as a core member, or takes the star away. */
export const markCore = (agent: string, core: boolean) => command(() => api.markCore(agent, core))
/** Dismisses the agents the director confirmed from the preview; the report per agent, or null when refused. */
export const dismissAllButCore = (agents: string[], reason: string) =>
  workshopCall(() => api.dismissAllButCore(agents, reason))
export const suspendAgent = (agent: string, reason: string) => command(() => api.suspendAgent(agent, reason))
export const quarantineSession = (mission: string, agent: string) => command(() => api.quarantine(mission, agent))
export const resumeAgent = (agent: string) => command(() => api.resumeAgent(agent))
export const reconfigureAgent = (agent: string, model: string, effort: string | null) =>
  command(() => api.reconfigureAgent(agent, model, effort))
export const approveTickets = (mission: string, tickets: string[], risks?: Record<string, 'low' | 'high'>) =>
  command(() => api.approveTickets(mission, tickets, risks))
/** Graph mode (ADR-0062): one paid planner session proposes new nodes. */
export const extendGraph = (mission: string, goal: string) => command(() => api.extendGraph(mission, goal))
export const editTicket = (ticket: string, title: string, description: string, criteria: string[]) =>
  command(() => api.editTicket(ticket, title, description, criteria))
export const dropTicket = (ticket: string, reason: string) => command(() => api.dropTicket(ticket, reason))
export const addNote = (text: string) => command(() => api.addNote(text))
export const checkNote = (id: string, done: boolean) => command(() => api.checkNote(id, done))
export const removeNote = (id: string) => command(() => api.removeNote(id))
export const noteToTicket = (id: string, mission: string) => command(() => api.noteToTicket(id, mission))
export const closeNote = (id: string, commit: string | null) => command(() => api.closeNote(id, commit))
export const reviewStaffing = (mission: string) => command(() => api.reviewStaffing(mission))
export const closeRetro = (mission: string) => command(() => api.closeRetro(mission))
export const acceptLesson = (id: string, text: string) => command(() => api.acceptLesson(id, text))
export const rejectLesson = (id: string, reason: string) => command(() => api.rejectLesson(id, reason))
export const retireLesson = (id: string, reason: string) => command(() => api.retireLesson(id, reason))
export const forgetApproval = (question: string) => command(() => api.forgetApproval(question))
export const sendBackCommittee = (mission: string, committee: number, constraint: string) =>
  command(() => api.sendBackCommittee(mission, committee, constraint))
/** `budgetUsd` null with `noCap` false keeps the budget set (refused for a real plan without one); `resources`: ticked ones. */
export const approvePlan = (mission: string, budgetUsd: number | null = null, noCap = false, resources: string[] = []) =>
  command(() => api.approve(mission, { budgetUsd, noCap: noCap || null, ...(resources.length ? { resources } : {}) }))
export const resumeMission = (mission: string) => command(() => api.resumeMission(mission))
export const rejectPlan = (mission: string, reason: string) => command(() => api.reject(mission, reason))
/** Accepts the mission, after the director's yes to any work left uncommitted (ADR-0055). */
export async function completeMission(mission: string, summary: string, withIgnored = false): Promise<boolean> {
  const outcome = await workshopCall(() => acceptMission(mission, summary, withIgnored), false)
  // The worktrees kept changing between the check and the acceptance: said, never a silent no.
  if (outcome === 'changing') office.error = t('uncommitted.keptChanging')
  return outcome === 'accepted' && (await workshopCall(reload, false)) !== null
}
export const cancelMission = (mission: string, reason: string) => command(() => api.cancel(mission, reason))
/** Puts a closed mission away (ADR-0054): it leaves the board and the lists, restorable from the desk's Archives. */
export const archiveMission = (mission: string) => command(() => api.archiveMission(mission))
export const unarchiveMission = (mission: string) => command(() => api.unarchiveMission(mission))
/** The director convenes a closed mission's retrospective: only on this call do its paid sessions start. */
export const conveneRetro = (mission: string) => command(() => api.conveneRetro(mission))
/** Archives the closed missions the director confirmed: the outcome of each, or null when the daemon refused. */
export const archiveMissions = (missions: string[]) => workshopCall(() => api.archiveMissions(missions))
export const configureChecks = (mission: string, cmd: string, automatic: boolean) =>
  command(() => api.configureChecks(mission, cmd, automatic))
export const setBudget = (mission: string, usd: number | null) => command(() => api.setBudget(mission, usd))

/** The director changes one line of the permission table of the mission on the board. */
export async function setToolDecision(tool: string, decision: ToolDecision): Promise<void> {
  const mission = office.mission?.id
  if (mission) await workshopCall(() => api.setToolSetting(mission, tool, decision))
}

export const startDemo = () => command(() => api.startLiveDemo())

/**
 * A workshop call: its result, or null once the refusal is shown. The company is reloaded after a change, since a
 * directive or an acceptance changes tickets and the trace.
 */
export async function workshopCall<T>(run: () => Promise<T>, changes = true): Promise<T | null> {
  try {
    const result = await run()
    if (changes) await reload()
    return result
  } catch (e) {
    handle(e)
    return null
  }
}

function handle(e: unknown): void {
  if (e instanceof ApiError && e.needsLogin) {
    // The session expired: what the director typed stays, to be sent again once signed in. Only an explicit sign-out
    // (SessionsPanel) throws the drafts away.
    office.needsLogin = true
    return
  }
  // IAM mode and a console-link session: the refusal comes with the way to sign in through Keycloak, then approve again.
  if (e instanceof ApiError && e.code === 'DELEGATION_NEEDS_SIGN_IN') return notify(t('iam.signInToApprove'), null, { link: { href: '/auth/keycloak', label: t('iam.login.keycloak') } })
  office.error = e instanceof ApiError ? refusalText(e.code, e.message) : String(e)
}
