// Mirrors of the daemon's API views (floor42-server dev.floor42.server.api.Views and EventView).
// Hand-written for now; ADR-0002 plans to generate them from the OpenAPI document.

export type Origin = 'SIMULATED' | 'REAL'

export type AgentStatus =
  | 'AVAILABLE'
  | 'ASSIGNED'
  | 'WORKING'
  | 'REVIEWING'
  | 'IN_COMMITTEE'
  | 'IN_RETRO'
  /** Called to the table by the director (ADR-0047): no new step until the meeting closes. */
  | 'IN_MEETING'
  | 'WAITING_FOR_AUTHORIZATION'
  | 'EXECUTING'
  | 'WAITING_FOR_DIRECTOR'
  | 'PAUSED'

/** One line of the progress panel: what it is, and what the screen needs to word it and settle it. */
export interface ProgressItem {
  kind:
    | 'SESSION'
    | 'SILENT'
    | 'QUESTION'
    | 'APPROVE_PLAN'
    | 'APPROVE_TICKET'
    | 'WORKSHOP'
    | 'CLOSE_MISSION'
    | 'STUCK'
    | 'SESSION_RISK'
  ticket: string | null
  agent: string | null
  question: string | null
  text: string | null
  status: string | null
  count: number | null
  /** For what waits for the director: when the wait began. For a session: its last sign. */
  since: string | null
  runningSeconds: number | null
  /** When Floor42 withdraws an agent's ask_director question unanswered; null otherwise. */
  deadline: string | null
}

/** The dispatcher's own reading of a mission: STUCK only when it would start a task and has not, for the setting. */
export interface StallView {
  state: 'RUNNING' | 'STUCK' | 'DUE' | 'WAITING' | 'IDLE'
  reason:
    | 'WOULD_START'
    | 'QUESTION'
    | 'HELD_PATCH'
    | 'COMMITTEE_DIRECTOR'
    | 'PROPOSED'
    | 'RESTING'
    | 'AGENT_BUSY'
    | 'NOTHING'
    | 'NOT_DISPATCHED'
    | 'WORKFLOW_UNREADABLE'
    | 'NO_STEP_TAKES_IT'
    | 'NEEDS_SECOND_REVIEWER'
    | 'SANDBOX_MEASURE'
    | null
  work: string | null
  ticket: string | null
  agent: string | null
  /** The mission the awaited agent works on (AGENT_BUSY). */
  elsewhere: string | null
  since: string | null
  until: string | null
}

/** Where a mission stands: what runs, what waits for the director, whether it is stuck (Floor42's reading). */
export interface MissionProgress {
  mission: string
  title: string
  status: string
  ticketsDone: number
  ticketsAll: number
  running: ProgressItem[]
  forYou: ProgressItem[]
  stuck: ProgressItem[]
  stall: StallView
}

/** One line of the "while you were away" summary. */
export interface AwayLine {
  kind: string
  mission: string | null
  ticket: string | null
  agent: string | null
  text: string | null
  at: string | null
}

/** What happened after the sequence the director acknowledged, and what waits now (real events only). */
export interface AwayView {
  since: number
  until: number
  more: boolean
  delivered: AwayLine[]
  merged: AwayLine[]
  waiting: AwayLine[]
  blocked: AwayLine[]
  sessions: number
  failedSessions: number
  /** Claude Code's estimate, summed over the sessions that reported one; null when none did. */
  costUsdEstimated: number | null
}

export interface UnfamiliarLine {
  kind: string
  shape: string
}

/** The commit the daemon was built from; null fields when built without a git checkout (a Docker image). */
export interface Build {
  commit: string | null
  commitFull: string | null
  commitTime: string | null
  /** The checkout had changes not committed when the daemon was built. */
  dirty: boolean | null
}

export interface Status {
  version: string
  build: Build
  /** REAL when live agent sessions are available. */
  mode: Origin
  /**
   * unfamiliar: kinds of stream line Claude Code sent that Floor42 does not know (its protocol changed), each with its
   * shape (keys and value types, never the values); a kind the director accepted is no longer listed.
   */
  runtime: { available: boolean; version: string | null; reason: string | null; unfamiliar: UnfamiliarLine[] }
  /** The shell sandbox (WSL2): working is measured, null while it is off. */
  sandbox: { enabled: boolean; working: boolean | null }
  /** The daemon's own process: nonDumpable is asked of the kernel, null where it cannot be (Windows, macOS). */
  process: { nonDumpableRequested: boolean; nonDumpable: boolean | null; attachDisabled: boolean | null }
}

/** Tokens are provided by the runtime; the cost is its own estimate. */
export interface SessionView {
  id: string
  agent: string
  ticket: string
  model: string
  open: boolean
  failed: boolean | null
  turns: number | null
  inputTokensProvided: number | null
  outputTokensProvided: number | null
  costUsdEstimated: number | null
  startedAt: string
  endedAt: string | null
  /** completed, failed, interrupted (left open by the previous daemon); null while open. */
  outcome: string | null
  /**
   * The cap the session ran under, when it ended without reporting a cost: the budget counts it as an upper bound
   * (never as a measure); null when a cost was reported, and for a simulated session.
   */
  costUsdBound: number | null
}

export interface AgentView {
  id: string
  name: string
  role: string
  provider: string
  model: string
  effort: string | null
  status: AgentStatus
  suspended: boolean
  /** DEVELOPER, REVIEWER, RESEARCHER or PLANNER; null for committee-only members (ADR-0019). */
  workRole: string | null
  /** A session of the agent is open: it cannot be dismissed until it ends. */
  atWork: boolean
  /** A chair of Floor42's committees (Galen, Lucie): never dismissed, nobody can be hired in their place. */
  stays: boolean
  /** Starred by the director as a core member: "dismiss everyone except the core members" keeps it. */
  core: boolean
}

/** One agent in the preview of "dismiss everyone except the core members". */
export interface CoreDismissalEntry {
  id: string
  name: string
  role: string
  /** LEAVES, STAYS (a core member) or BLOCKED (cannot leave now: code and reason say why). */
  fate: 'LEAVES' | 'STAYS' | 'BLOCKED'
  /** AGENT_BUSY or AGENT_NEEDED, as a single dismissal would be refused; null otherwise. */
  code: string | null
  reason: string | null
  /** Tickets it had started, which go back to the queue if it leaves. */
  handsBack: string[]
}

export interface CoreDismissalPreview {
  agents: CoreDismissalEntry[]
}

/** What happened to one confirmed agent. */
export interface CoreDismissalOutcome {
  id: string
  name: string
  result: 'DISMISSED' | 'REFUSED' | 'SKIPPED'
  code: string | null
  message: string | null
  handsBack: string[]
}

export interface CoreDismissalReport {
  outcomes: CoreDismissalOutcome[]
}

export interface MissionSummary {
  id: string
  title: string
  status: string
  origin: Origin
  tickets: number
  ticketsDone: number
  openQuestions: number
  /** Put away by the director once closed (ADR-0054): listed only among the archives. */
  archived: boolean
  /** Who worked on it (tickets, reviews, sessions): they stay at their desks while it is in progress. */
  agents: string[]
}

/** Whether the director can convene a closed mission's retrospective now (ADR-0054), and why not. */
export interface RetroOffer {
  available: boolean
  code: string | null
}

/** One mission of "Archive all closed missions": archived by this request, or the refusal code that left it out. */
export interface ArchiveOutcome {
  mission: string
  archived: boolean
  code: string | null
}

/** A reservation Floor42 kept: it cites a criterion of the ticket and says what to change (ADR-0053). */
export interface ReviewReservationView {
  criterion: number
  criterionText: string
  gap: string
  change: string
}

/** A remark that blocks nothing; why is NOTE, NO_CRITERION, UNKNOWN_CRITERION or NO_CHANGE. */
export interface ReviewNoteView {
  text: string
  why: string
}

/** What Floor42 kept of a review; null for a review recorded before 1.13.0, whose verdict keeps its meaning. */
export interface ReviewItemsView {
  declared: string
  reservations: ReviewReservationView[]
  notes: ReviewNoteView[]
}

export interface ReviewView {
  reviewer: string
  verdict: string
  findings: string
  items: ReviewItemsView | null
}
