// Mirrors of the daemon's API views, part 8 (see types.ts).

import type { Origin } from './types.office'
import type { Contents, PluginAction, PluginTarget, PluginsView } from './types.reports'
import type { TokenScope } from './types.workflows'

/** What would run, shown in the confirmation dialog; nothing ran yet. */
export interface PluginPreview {
  target: PluginTarget
  action: PluginAction
  plugin: string
  marketplace: string
  source: string | null
  publisher: string | null
  version: string | null
  license: string | null
  description: string | null
  contents: Contents | null
  warnings: string[]
  commands: string[]
  selectedBy: string[]
  refused: string | null
  refusal: string | null
  digest: string
}

export interface Step {
  command: string
  exitCode: number | null
  timedOut: boolean
  output: string
}

/** Read from disk after the commands: never inferred from an exit code. */
export interface PluginOutcome {
  outcome: 'INSTALLED' | 'NOT_INSTALLED' | 'REMOVED' | 'STILL_INSTALLED'
  present: boolean
  version: string | null
  commandsOk: boolean
  steps: Step[]
  view: PluginsView
}

export interface AssetChoice {
  id: string
  target: PluginTarget
  plugin: string
  marketplace: string
  kind: 'SKILL' | 'AGENT'
  name: string
  description: string | null
  givable: boolean
  reason: string | null
  selected: boolean
}

export interface MissionAssetsView {
  mission: string
  selected: string[]
  missing: string[]
  choices: AssetChoice[]
  skillTool: boolean
  agentTool: boolean
}

export interface AssetSelection {
  items: string[]
}
/**
 * The director's own Claude Code in a terminal (ADR-0041): not an agent, not governed by Floor42. `costUsd` is what
 * Claude Code last reported through its status line, its own estimate; null while it reported nothing.
 */
export interface TerminalView {
  id: string
  project: string
  openedAt: string
  running: boolean
  costUsd: number | null
  costAt: string | null
  model: string | null
  exitCode: number | null
}

export interface TerminalsView {
  available: boolean
  reason: string | null
  terminals: TerminalView[]
}

export interface OpenTerminal {
  project: string
  columns: number | null
  rows: number | null
}

/** `ticket` opens `/ws/terminal/{id}?ticket=...` once, within a minute. */
export interface TerminalAccess {
  terminal: TerminalView
  ticket: string
}

// cost-overview
/**
 * One figure and its bases: the amount as Claude Code estimates it (never a bill), tokens as it reports them, sessions as
 * Floor42 counts them. `sessionsWithoutCost` above zero means the amount is a lower bound.
 */
export interface CostLine {
  key: string
  name: string | null
  usdEstimated: number
  costBasis: string
  inputTokens: number
  outputTokens: number
  tokensBasis: string
  sessions: number
  sessionsWithoutCost: number
  /** The caps of the sessions that reported no cost: an upper bound, never added to the estimate; null when none. */
  usdBound: number | null
  sessionsOpen: number
  sessionsBasis: string
}

/** A mission's total with its cap; `budgetUsd` null with `capDecided` means "no cap" chosen. */
export interface MissionCost {
  mission: string
  title: string
  origin: Origin
  status: string
  budgetUsd: number | null
  capDecided: boolean
  total: CostLine
  /** Archived by the director (ADR-0054): what it spent stays counted, marked. */
  archived: boolean
}

export interface MissionCosts {
  mission: MissionCost
  agents: CostLine[]
  tickets: CostLine[]
}

/** Today, this week and this month across real missions, in `zone`; then each mission. */
export interface CostsOverview {
  zone: string
  at: string
  today: CostLine
  week: CostLine
  month: CostLine
  missions: MissionCost[]
}

/** The cap decided with a plan's approval: a budget, or `noCap` true for none. */
export interface PlanApproval {
  budgetUsd: number | null
  noCap: boolean | null
  resources?: string[] | null
}

/** A journal row replay left out at startup (ADR-0042); its payload is never shown. */
export interface UnreadableEventView {
  sequence: number
  eventId: string | null
  type: string
  mission: string | null
  /** NEWER_VERSION: written by a newer Floor42, upgrade to read it. */
  reason: 'CORRUPT' | 'NEWER_VERSION' | 'NOT_APPLICABLE'
  detail: string
}

export interface BackupView {
  name: string
  kind: 'STARTUP' | 'DAILY' | 'MANUAL' | 'BEFORE_RESTORE' | 'DISPLACED'
  sizeBytes: number
  createdAt: string
  /** Null when the copy cannot be read. */
  events: number | null
}

export interface RestoreOutcomeView {
  restored: boolean
  backup: string
  events: number | null
  keptAs: string | null
  reason: string | null
  /** When the stop that applied it ran. */
  at: string
  /**
   * Reported by a file the last stop left, which a process of the daemon's OS user could write: never a proof. For a
   * restore reported done, whether the journal and the copies are consistent with it; null for one reported not applied.
   */
  matchesJournal: boolean | null
}

/**
 * The journal does not continue what the last run saw, or nothing says what that run saw (detection only, ADR-0044
 * amended). MISSING: no record beside a journal holding events; FIRST_CHECK: the same on the first check since a
 * Floor42 before 1.8.0 (a notice); UNREADABLE: a record that cannot be read; ANCHOR_FAILED: no check could be made.
 */
export interface ContinuityBreakView {
  reason: 'SHORTER' | 'DIVERGED' | 'MISSING' | 'FIRST_CHECK' | 'UNREADABLE' | 'ANCHOR_FAILED'
  /** What the last run saw; null for MISSING, FIRST_CHECK, UNREADABLE and ANCHOR_FAILED, as are the other recorded fields. */
  recordedEvents: number | null
  recordedLastSequence: number | null
  recordedAt: string | null
  /** null for ANCHOR_FAILED: nothing was measured. */
  foundEvents: number | null
  foundLastSequence: number | null
  /** A copy of the journal the last run held, kept by its stop when the file was replaced under it. */
  displaced: string | null
  /** REAL: compared on the files of the data folder, never simulated. */
  origin: Origin
}

/** A file that asked this start to restore the journal: refused, never applied (ADR-0042, amended). */
export interface RefusedRestoreView {
  backup: string
  modifiedAt: string | null
  keptAs: string | null
}

/** Another process holds the journal file: appends fail after SQLite's busy timeout. */
export interface JournalLockedView {
  since: string
  detail: string
}

export interface JournalAlertsView {
  refusedRestore: RefusedRestoreView | null
  locked: JournalLockedView | null
  continuity: ContinuityBreakView | null
  /** Configuration files read from outside Floor42's own files (QUARKUS_CONFIG_LOCATIONS). */
  configFiles: string[]
}

export interface BackupsView {
  folder: string
  enabled: boolean
  disabledReason: string | null
  /** OFF, IN_AGENT_WORKTREES or IN_GIT_WORKTREE: the code of the reason, null when copies are made. */
  disabledCode: string | null
  /** The folder the code speaks of, null for OFF. */
  disabledPath: string | null
  startupKeep: number
  dailyKeep: number
  manualKeep: number
  lastError: string | null
  /** The copy this daemon restores when it stops (in memory only). */
  pendingRestore: string | null
  /** What the last stop did with the restore the director prepared. */
  lastRestore: RestoreOutcomeView | null
  refusedRestore: RefusedRestoreView | null
  /** The heaviest copy this daemon restores from memory; a heavier one is restored by hand. */
  restoreLimitBytes: number
  backups: BackupView[]
}

/** One of the director's open sessions (ADR-0044): a browser or a script token. Never a secret. */
export interface DirectorSessionView {
  id: string
  kind: 'BROWSER' | 'SCRIPT'
  scope: TokenScope
  created: string
  lastUsed: string
  /** Null for a browser: it ends after its idle or absolute limit instead. */
  expires: string | null
  /** When a browser session ends unless used; null for a script token. */
  idleUntil: string | null
  userAgent: string | null
  label: string | null
  /** The session this very request came with. */
  current: boolean
  /** IAM mode is on and this browser session was opened with the console link: it cannot delegate. */
  breakGlass: boolean
  /** When this browser's Keycloak sign-in stops being usable for approvals; null outside IAM mode or without one. */
  keycloakUntil: string | null
}
