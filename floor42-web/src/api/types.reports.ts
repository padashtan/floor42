// Mirrors of the daemon's API views, part 7 (see types.ts).

import type { EnvironmentBlocker, MissionView, ReportAskedBack, TicketView } from './types.tickets'
import type { ResumableMission } from './types.delivery'

/** An agent's latest session on an open ticket that ended before its report. */
export interface ResumableSession {
  session: string
  mission: string
  ticket: string
  ticketTitle: string
  agent: string
  agentName: string
  startedAt: string
  endedAt: string | null
  outcome: string | null
  interrupted: boolean
  lastAction: string | null
  retryQuestion: string | null
  /** Continuing it with Claude Code's --resume is safe now (same worktree, tools, model; transcript there). */
  continuable: boolean
  /** Why not, as a code (`ok` when it can). */
  continuity: string
  continuedFrom: string | null
}

export interface ResumeView {
  missions: ResumableMission[]
  sessions: ResumableSession[]
}

export type ResumeMode = 'FRESH' | 'CONTINUE'
// mission-end (ADR-0039): the end-of-mission report, read from the journal and git, never stored.
/** How certain a figure is: Floor42 measured it, Claude Code estimated it, or an agent reported it. */
export type Basis = 'MEASURED' | 'ESTIMATED' | 'REPORTED' | 'SIMULATED'

export interface Measure {
  /** null when nothing was measured or estimated: never shown as zero. */
  value: number | null
  basis: Basis
}

/**
 * The mission's one upper bound: the caps of the sessions that ended without reporting a cost and, in the totals, the
 * budgets of the meeting turns charged for want of an estimate, each counted once (ADR-0049). Shown apart from the
 * estimate and never added to it.
 */
export interface CostBound {
  /** UPPER_BOUND: an upper bound, never an estimate nor a measure; null when there is none. */
  costUsd: { value: number | null; basis: 'UPPER_BOUND' }
  sessions: number
}

export interface ReportReview {
  reviewer: string
  verdict: string
  firstLine: string | null
}

export interface ReportTicket {
  id: string
  title: string
  kind: 'WORK' | 'DECISION' | 'RETRO' | 'STAFFING'
  status: TicketView['status']
  producer: string | null
  reviews: ReportReview[]
  rejections: number
  producerVerdict: string | null
  directives: number
  sessions: number
  failedSessions: number
  workMs: Measure
  elapsedMs: Measure
  costUsd: Measure
  costBound: CostBound
  commit: string | null
  reopenTo: string | null
  reopenRefusedCode: string | null
  reopenRefused: string | null
  environmentBlocker: EnvironmentBlocker | null
}

export interface ReportTotals {
  done: number
  dropped: number
  open: number
  sessions: number
  failedSessions: number
  elapsedMs: Measure
  workMs: Measure
  costUsd: Measure
  costBound: CostBound
  budgetUsd: number | null
}

export interface ReportReviews {
  reviews: number
  validated: number
  withReservations: number
  rejected: number
  unverifiable: number
}

export interface ReportCheck {
  workspace: string
  command: string
  exitCode: number | null
  timedOut: boolean
  durationMs: number
  trigger: string
  at: string
  passed: boolean
}

export interface ReportLesson {
  id: string
  audience: string | null
  text: string
  status: string
}

export interface ReportRetro {
  state: 'NONE' | 'RUNNING' | 'CONCLUDED' | 'CLOSED'
  chair: string | null
  conclusions: string[]
  reason: string | null
  lessons: ReportLesson[]
}

export interface ReportOutput {
  agent: string
  path: string
  branch: string
  readable: boolean
  accepted: number
  uncommitted: string[]
  uncommittedCount: number
  ignored: string[]
  ignoredCount: number
  mergeCommand: string | null
}

export interface ReportWarning {
  code: string
  ticket: string | null
  agent: string | null
  path: string | null
  count: number | null
  text: string | null
}

export type NextStepKind = 'CLOSE' | 'REOPEN' | 'PULL_REQUEST' | 'MERGE' | 'FOLLOW_UP' | 'ARCHIVE'

export interface NextStep {
  kind: NextStepKind
  available: boolean
  code: string | null
  message: string | null
}

export interface FollowUp {
  title: string
  objective: string
  repository: string | null
}

export interface MissionReport {
  mission: string
  title: string
  status: MissionView['status']
  origin: string
  outcome: string | null
  repository: string | null
  projectBranch: string | null
  readAt: string
  totals: ReportTotals
  tickets: ReportTicket[]
  reviews: ReportReviews
  checks: ReportCheck[]
  retro: ReportRetro
  outputs: ReportOutput[]
  warnings: ReportWarning[]
  next: NextStep[]
  followUp: FollowUp
  objections: import('./objections').ReportObjectionRound[]
  askedBack: ReportAskedBack[]
}

// --- plugins (ADR-0040) ----------------------------------------------------------------------------------------------

export type PluginTarget = 'FLOOR42' | 'CLAUDE'
export type PluginAction = 'INSTALL' | 'UNINSTALL'

/** What a plugin holds by its standard layout; `known` false when only the marketplace entry could be read. */
export interface Contents {
  skills: string[]
  agents: string[]
  commands: string[]
  hooks: boolean
  mcpServers: boolean
  lspServers: boolean
  known: boolean
}

export interface PluginEntry {
  name: string
  description: string | null
  publisher: string | null
  version: string | null
  category: string | null
  sourceKind: string
  installedIn: PluginTarget[]
}

export interface MarketplaceView {
  name: string
  source: string | null
  owner: string | null
  knownIn: PluginTarget[]
  official: boolean
  listedFrom: 'floor42' | 'claude' | 'fetched' | 'none'
  listedAt: string | null
  refreshable: boolean
  plugins: PluginEntry[]
}

export interface InstalledPlugin {
  target: PluginTarget
  plugin: string
  marketplace: string
  version: string | null
  scope: string
  installedAt: string | null
  contents: Contents | null
  problem: string | null
}

export interface CliStatus {
  available: boolean
  version: string | null
  detail: string | null
}

export interface PluginsView {
  readAt: string
  cli: CliStatus
  floor42Folder: string
  claudeFolder: string
  marketplaces: MarketplaceView[]
  installed: InstalledPlugin[]
}

export interface PluginRequest {
  target: PluginTarget
  action: PluginAction
  plugin: string
  marketplace: string
  digest: string | null
}
