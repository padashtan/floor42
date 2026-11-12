// Mirrors of the daemon's API views, part 6 (see types.ts).

import type { ReviewItemsView } from './types.office'

/**
 * A live session's context in use (W18), from /ws/context or GET /api/sessions/{id}/context; in memory, never journaled.
 * `usedTokens` is measured by Claude Code (input, cache writes and cache reads of the last request); `windowTokens` is
 * the model's documented window, null for a model Floor42 does not know: a share of it is an estimate.
 */
export interface ContextReading {
  session: string
  agent: string
  mission: string
  model: string
  usedTokens: number
  windowTokens: number | null
  at: string
}

/** Replay export (ADR-0035): how many replacements of one kind the file needed. */
export interface MaskCount {
  kind: 'SECRET' | 'PATH' | 'TOKEN' | 'OUTPUT_CLIPPED' | 'TEXT_CLIPPED' | 'SETTING_VALUE' | 'DIRECTOR_FILE'
  count: number
}

/** One place masked or left out; `event` 0 is the mission itself. */
export interface MaskedItem {
  event: number
  type: string
  field: string
  kind: MaskCount['kind']
}

/** What the replay file will hold, read before the director confirms; the export must name its `digest`. */
export interface ReplayExportPreview {
  mission: string
  title: string
  origin: 'REAL' | 'SIMULATED'
  version: string
  events: number
  real: number
  simulated: number
  agents: string[]
  tickets: number
  outputs: number
  costs: number
  masked: MaskCount[]
  details: MaskedItem[]
  detailsTruncated: boolean
  digest: string
}

/** The director's confirmation of a preview, with the player's words in the screen's language. */
export interface ReplayExportRequest {
  digest: string
  language: 'en' | 'fr'
  labels: Record<string, string>
}

/** Whether GitHub's command line is installed and signed in (ADR-0036). Floor42 never signs in for the director. */
export interface GhStatus {
  available: boolean
  authenticated: boolean
  detail: string
  /** The command for the director to run in a terminal of theirs, when gh is not ready. */
  fix: string | null
}

/** One check GitHub reports on a pull request; bucket as gh gives it: pass, fail, pending, skipping, cancel. */
export interface PullRequestCheck {
  name: string
  bucket: string
  state: string
}

/** A pull request Floor42 opened (or found) for a mission, with the last checks the director asked for. */
export interface PullRequestView {
  number: number
  url: string
  workspace: string | null
  branch: string
  base: string
  remote: string | null
  /** Found on GitHub after an error or a timeout, rather than from gh pr create's answer. */
  found: boolean
  openedAt: string | null
  checks: PullRequestCheck[]
  checksAt: string | null
}

/** What a pull request would be: shown in the confirmation dialog before anything is pushed. */
export interface PullRequestPreview {
  mission: string
  workspace: string | null
  branch: string
  head: string | null
  commits: number
  remote: string | null
  remoteUrl: string | null
  repository: string | null
  base: string | null
  title: string
  body: string
  gh: GhStatus
  /** Why Floor42 will not open it now (a stable code), null when it can. */
  refused: string | null
  refusal: string | null
  existing: PullRequestView | null
  pending: number
}

/** What the director confirmed; Floor42 pushes only if it still matches its own preview. */
export interface PullRequestRequest {
  workspace: string | null
  remote: string
  branch: string
  base: string
  title: string
  body: string
  remoteUrl: string | null
}

export interface PullRequestOutcome {
  outcome: 'OPENED' | 'FOUND' | 'FAILED' | 'UNKNOWN'
  /** Whether the branch is on the remote at its commit; null when Floor42 could not tell. */
  pushed: boolean | null
  pullRequest: PullRequestView | null
  detail: string | null
  retry: boolean
}

// uncommitted work at acceptance (ADR-0055)
/** One worktree as measured: CLEAN, DIRTY or UNKNOWN (never clean when git did not answer). */
export interface UncommittedWorktreeState {
  agent: string
  path: string
  state: 'CLEAN' | 'DIRTY' | 'UNKNOWN'
  /** Why UNKNOWN: GIT_FAILED, WORKTREE_MISSING or NOT_A_MISSION_WORKTREE; null otherwise. */
  reason: string | null
  count: number
  /** The first files not committed. */
  paths: string[]
}

/** The token names this exact measure: an acceptance that leaves the work as it is sends it back. */
export interface UncommittedCheck {
  clean: boolean
  token: string
  worktrees: UncommittedWorktreeState[]
}

// deliverables (ADR-0037)
export interface DeliveredReview {
  reviewer: string
  verdict: string
  findings: string | null
  items: ReviewItemsView | null
}

export interface DeliveredTicket {
  id: string
  title: string
  status: string
  assignee: string | null
  /** What the assignee submitted; null before any submission. */
  deliverable: string | null
  /** The last review's verdict, null before any review. */
  verdict: string | null
  reviews: DeliveredReview[]
}

export type DeliveredStatus = 'A' | 'M' | 'D' | 'R' | '?' | '!'
export type DeliveredKind = 'TEXT' | 'IMAGE' | 'PDF' | 'OTHER'

export interface DeliveredFile {
  path: string
  from: string | null
  status: DeliveredStatus
  size: number | null
  /** Short hash of the last commit that touched it; null when not committed. */
  commit: string | null
  committed: boolean
  ignored: boolean
  /** A link: listed, never read. */
  link: boolean
  kind: DeliveredKind
}

/** One of the director's own pending files, as the viewer needs it before reading it (ADR-0051). */
export interface DirectorFileView {
  file: DeliveredFile
  maxViewText: number
  maxViewBytes: number
}

export interface DeliveredCommit {
  hash: string
  subject: string
  author: string
  at: string
  inProject: boolean
}

export interface DeliveredBranch {
  agent: string
  branch: string | null
  worktree: string | null
  worktreePresent: boolean
  /** In Office's former data folder (before 1.5.0). */
  legacy: boolean
  start: string | null
  tip: string | null
  merged: 'NOTHING' | 'MERGED' | 'PARTIAL' | 'NOT_MERGED' | 'UNKNOWN'
  notInProject: number
  commits: DeliveredCommit[]
  committed: DeliveredFile[]
  uncommitted: DeliveredFile[]
  ignoredHidden: number
  truncated: boolean
  tickets: string[]
}

export interface DeliveredPullRequest {
  number: number
  url: string
  agent: string | null
  branch: string
  base: string
  openedAt: string | null
}

export interface DeliverablesView {
  mission: string
  title: string
  status: string
  origin: string
  repository: string | null
  available: boolean
  projectBranch: string | null
  projectHead: string | null
  ignoredShown: boolean
  tickets: DeliveredTicket[]
  branches: DeliveredBranch[]
  pullRequests: DeliveredPullRequest[]
  maxViewText: number
  maxViewBytes: number
  maxZipBytes: number
}

export interface OpenFolderRequest {
  agent: string
}
/** A mission not closed, with what "Resume" does through an existing route (ADR-0038). */
export interface ResumableMission {
  id: string
  title: string
  origin: 'REAL' | 'SIMULATED'
  status: string
  workspace: string | null
  lastActivity: string | null
  state: 'RUNNING' | 'OPEN' | 'SUSPENDED' | 'INTERRUPTED' | 'WAITING' | 'IDLE'
  action: 'RESUME_MISSION' | 'RETRY' | 'RESUME_AGENTS' | 'ANSWER' | 'APPROVE' | 'NUDGE' | 'NONE'
  doing: string | null
  doingTitle: string | null
  doingStatus: string | null
  retryQuestion: string | null
  openQuestions: number
  suspendedAgents: string[]
}
