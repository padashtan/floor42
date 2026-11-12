// Mirrors of the daemon's API views, part 4 (see types.ts).

import type { Origin } from './types.office'
import type { ToolDecision } from './types.meetings'

/** One line of the director's permission table. */
export interface ToolSettingView {
  tool: string
  category: string
  decision: ToolDecision
  source: string
  warning: string | null
  remembered: RememberedView[]
  /** Who recorded the line in force; null when no line exists. */
  lineBy: RuleBy | null
  /** The ticket, agent and command rules for this tool, which outrank the line where they apply. */
  rules: RuleView[]
}

/** Who recorded a permission rule: the director, Floor42 on the director's behalf, or a committee's read-only table. */
export type RuleBy = 'DIRECTOR' | 'FLOOR42' | 'COMMITTEE_TABLE'

/** A rule beyond a tool's line, as a phrase (`rulefor.<code>`) and who recorded it. */
export interface RuleView {
  code: string
  params: Record<string, string>
  by: RuleBy
}

/** A line of the company's default permission table, which new work missions start from (D3). */
export interface DefaultPermissionView {
  tool: string
  category: string
  decision: ToolDecision
  /** Floor42's own line, which a reset goes back to. */
  builtIn: ToolDecision
  changed: boolean
}

/** A remembered answer to an approval; `command` is set for the shell, whose approvals are per exact command. */
export interface RememberedView {
  question: string
  agent: string
  command: string | null
  answer: 'ALLOW' | 'DENY'
}

/**
 * A usage window of the plan as Claude Code last reported it (measured). `current` is false once the window has reset:
 * the reading then tells nothing. `expectedIncrease` is Floor42's estimate of what one session adds, null when unknown.
 */
export interface UsageWindowView {
  window: string
  label: string
  utilization: number
  resetsAt: string
  observedAt: string
  current: boolean
  status: string | null
  expectedIncrease: number | null
}

/** Estimated cost and provided tokens of real agent sessions, per day (UTC) or per mission. */
export interface UsageLine {
  key: string
  title: string | null
  sessions: number
  costUsdEstimated: number
  inputTokens: number
  outputTokens: number
  /** A mission the director archived (ADR-0054): still counted. False for a day. */
  archived: boolean
}

export interface UsageView {
  windows: UsageWindowView[]
  threshold: number
  days: UsageLine[]
  missions: UsageLine[]
  accountUsageUrl: string
  /** The office is closed: the plan's quota is spent. Null in the daytime. */
  night: NightView | null
}

/** Why the office closed for the night, and when the quota resets (reported by Claude Code). */
export interface NightView {
  window: string
  label: string
  resumesAt: string
  reason: string
  /** Why, for the screen's catalogue: quotaUsed or waitingReset. */
  code: string
}

/** When a retrospective's next turn runs, if it waits for the plan's usage window to reset. */
/** A developer who takes no new ticket until the director accepts or rejects the finished ticket its worktree holds. */
export interface HeldDeveloper {
  agent: string
  ticket: string
}

export interface RetroWait {
  until: string | null
}

/** One line of the director's improvement checklist. */
export interface NoteView {
  id: string
  text: string
  addedAt: string
  done: boolean
  doneAt: string | null
  /** The ticket the director made of it, null if none. */
  ticket: string | null
  /** The commit that implemented it, null while unknown. */
  commit: string | null
}

/** One call opened in the API console: its bodies as the daemon read and wrote them, capped; null when none. */
export interface CallDetail {
  call: ApiCall
  requestBody: string | null
  responseBody: string | null
  truncated: boolean
}

/** One call to the daemon, as the API console shows it. A token in a query is masked; bodies come with CallDetail. */
export interface ApiCall {
  sequence: number
  at: string
  method: string
  path: string
  status: number
  durationMs: number
  caller: 'UI' | 'script' | 'agent hook' | 'anonymous'
}

export interface EventView {
  eventId: string
  occurredAt: string
  origin: Origin
  actor: string
  mission: string | null
  type: string
  /** The line's message code (event type, plus a variant) for the screen's catalogue (ADR-0021, lot 3). */
  code: string
  /** The values the translated line is filled with. */
  params: Record<string, string>
  agent: string | null
  ticket: string | null
  question: string | null
  action: string | null
  tool: string | null
  category: string | null
  summary: string | null
  stage: string | null
  /** True only for the event recording a tool really running: the only thing allowed to light a machine. */
  ran: boolean
}

/** One pending change of an agent's workspace, relative to its last commit. Counts are null for binary files. */
export interface ChangeView {
  path: string
  status: 'ADDED' | 'MODIFIED' | 'DELETED' | 'RENAMED'
  added: number | null
  removed: number | null
}

export interface WorkspaceView {
  agent: string
  path: string
  branch: string
  baseCommit: string
  head: string
  changes: ChangeView[]
  editedByDirector: string[]
  /** New build and cache artefacts (__pycache__, node_modules...) an acceptance leaves out, listed apart. */
  leftOut: string[]
}

export interface InterventionView {
  kind: 'DIRECTOR_EDIT' | 'DIRECTIVE' | 'PATCH_ACCEPTED' | 'PATCH_REJECTED'
  at: string
  author: string
  workspace: string | null
  ticket: string | null
  subject: string
  detail: string | null
  files: string[]
}

export interface WorkshopView {
  mission: string
  workspaces: WorkspaceView[]
  interventions: InterventionView[]
}

export interface FileView {
  path: string
  content: string | null
  binary: boolean
}

/** Counts over real missions only; costs are the runtime's estimates. */
export interface ProfileRecord {
  missions: number
  sessions: number
  failedSessions: number
  costUsdEstimated: number | null
  inputTokensProvided: number
  outputTokensProvided: number
  ticketsAssigned: number
  ticketsDone: number
  verdictsReceived: Record<string, number>
  verdictsGiven: Record<string, number>
  actions: Record<string, number>
  denials: Record<string, number>
  consultationsAnswered: number
  /** Time of its ended sessions, measured by Floor42. */
  workMs: number
}

export interface ProfileFact {
  kind: 'STRENGTH' | 'WATCH' | 'INFO'
  text: string
  basis: string
  /** Lets the screen write the fact in its language (ADR-0021, lot 3). */
  code: string | null
  params: Record<string, string> | null
}

export interface ProfileTicket {
  mission: string
  ticket: string
  title: string
  status: string
  lastVerdict: string | null
  /** This agent's session time on the ticket, measured by Floor42. */
  workMs: number
}

/** Declared when hired, measured from the journal. */
export interface AgentProfileView {
  id: string
  name: string
  role: string
  provider: string
  model: string
  effort: string | null
  suspended: boolean
  status: string
  skills: string[]
  duties: string[]
  focus: string | null
  modelTrait: string | null
  record: ProfileRecord
  facts: ProfileFact[]
  tickets: ProfileTicket[]
}

/** What git says of a repository right now (W2), read when asked. */
export interface RepositoryFacts {
  branch: string
  commit: string | null
  subject: string | null
  committedAt: string | null
  uncommitted: number
}
