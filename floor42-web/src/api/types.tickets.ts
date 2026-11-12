// Mirrors of the daemon's API views, part 2 (see types.ts).

import type { AgentStatus, Origin, ReviewView, SessionView } from './types.office'
import type { CheckRunView, CommitteeSummaryView, CommitteeView, MeetingView, RetroView, ScoreLine } from './types.meetings'
import type { RuleBy } from './types.settings'
import type { MissionWorkflowView } from './types.workflows'

export interface TicketView {
  id: string
  title: string
  description: string
  priority: string
  status: 'PROPOSED' | 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE' | 'CANCELLED'
  assignee: string | null
  dependsOn: string[]
  acceptanceCriteria: string[]
  deliverable: string | null
  reviews: ReviewView[]
  directives: string[]
  consultations: ConsultationView[]
  /** As journaled: created, first started, done or dropped (null while open). */
  createdAt: string
  startedAt: string | null
  endedAt: string | null
  /** What the ticket is for, recorded at creation (PLANNING: the planner's ticket), never read from its title. */
  kind: string
  /** The open question of yours that holds the ticket: nothing starts on it until you answer. Null when none. */
  waitingFor: string | null
  /** What its last report says the environment lacks: a directive alone will not provide it. Null when none. */
  environmentBlocker: EnvironmentBlocker | null
  /** Graph mode (ADR-0062): what the plan settled for the node and what its tests showed. Null for any other ticket. */
  node: NodeView | null
  /** Graph mode: a done node one of whose dependencies was reopened since. Shown, never acted on. */
  toRecheck: boolean
}

/** One run of a graph node's test by Floor42 (ADR-0062). */
export interface NodeTestView {
  command: string
  outcome: 'passed' | 'failed' | 'not run'
  exitCode: number | null
  durationMs: number
  at: string
}

/** The risk, tier and test commands settled for a graph node, and the runs of those tests. */
export interface NodeView {
  risk: 'low' | 'high'
  tier: 'light' | 'standard'
  tests: string[]
  runs: NodeTestView[]
}

/** A step a report says its environment prevents: what is missing, and the step (null when the report names none). */
export interface EnvironmentBlocker {
  missing: string
  step: string | null
}

/** Help a reviewer asked for (ADR-0018); answeredBy is null when Floor42 answered itself. */
export interface ConsultationView {
  round: number
  reviewer: string
  kind: 'CONSULT' | 'RESEARCH'
  role: string | null
  question: string
  answer: string | null
  answeredBy: string | null
}

export interface ActionView {
  id: string
  ticket: string | null
  agent: string
  category: string
  tool: string
  summary: string
  stage: string
  ran: boolean
  decisionBasis: string | null
  denialReason: string | null
  authorizedByOffice: boolean
  result: string | null
  requestedAt: string
  updatedAt: string
  /** How long Claude Code last said the call had been running, in seconds (its heartbeat). */
  runningSeconds: number | null
  /** The basis Floor42 wrote, as a code and values (DecisionBasis.java); null for any other text, shown as recorded. */
  decisionBasisCode: string | null
  decisionBasisParams: Record<string, string> | null
}

export interface QuestionView {
  id: string
  mission: string
  kind: 'OPEN' | 'ACTION_APPROVAL' | 'REVIEW_ESCALATION' | 'BUDGET_REACHED' | 'COMMITTEE_ARBITRATION' | 'PLAN_LIMIT' | 'STAFFING' | 'CLARIFICATION'
  askedBy: string
  subject: string
  context: string | null
  options: string[]
  recommendation: string | null
  criticality: string
  blocking: boolean
  ticket: string | null
  action: string | null
  status: 'OPEN' | 'ANSWERED' | 'WITHDRAWN'
  answer: string | null
  askedAt: string
  /** What Floor42 meant by the subject and context it wrote (ADR-0021, lot 3); null for an agent's own question. */
  phraseCode: string | null
  phraseParams: Record<string, string> | null
  /**
   * For an agent's open question: its earlier refused calls whose refusal still decides. Send one's `question` as
   * `askAgain` with the answer to be asked again for that call; nothing is allowed by it. Empty otherwise.
   */
  askAgain: AskAgainView[]
  /** The context Floor42 wrote, as a code and values (an approval's scope); null for any other context. */
  contextCode: string | null
  contextParams: Record<string, string> | null
  /** For an open approval question: the rule that makes the call ask (`basis.<code>`), and who recorded it. */
  causeCode: string | null
  causeParams: Record<string, string> | null
  causeBy: RuleBy | null
  /** For an open ruling offering to implement: what its option says writing will be, and what it would be now. */
  writesAsked: ImplementWrites | null
  writesNow: ImplementWrites | null
  /** Asking the agent back instead of answering (ADR-0059). */
  askBack: AskBackView
}

/**
 * `rounds`: how many times the director asked back on this question's thread; `possible`: an agent's open question
 * still under the cap (its session may be gone: the route answers 409 SESSION_GONE); `text`: the director's words when
 * this question was asked back, else null.
 */
export interface AskBackView {
  rounds: number
  possible: boolean
  text: string | null
}

/** A question the director asked back, counted in the end-of-mission report (`round`: which ask-back on its thread). */
export interface ReportAskedBack {
  question: string
  ticket: string | null
  agent: string
  subject: string
  round: number
}

/** What writing will be on an implementation ticket: Write and Edit agree, or not (MIXED). */
export type ImplementWrites = 'ALLOW' | 'ASK' | 'DENY' | 'MIXED'

/** A refused call whose remembered refusal the director may lift while answering an agent's question. */
export interface AskAgainView {
  question: string
  action: string
  tool: string
  summary: string
}

export interface IncidentView {
  id: string
  criticality: string
  description: string
  action: string | null
  raisedAt: string
  /** The description Floor42 wrote, as a code and values (RecordedReason.java); null for any other text. */
  descriptionCode: string | null
  descriptionParams: Record<string, string> | null
}

export interface MissionView {
  id: string
  title: string
  objective: string
  status: string
  origin: Origin
  workspace: string | null
  outcome: string | null
  permissions: string[]
  tickets: TicketView[]
  actions: ActionView[]
  questions: QuestionView[]
  incidents: IncidentView[]
  sessions: SessionView[]
  checkCommand: string | null
  checksAutomatic: boolean
  /** Compared with the runtime's cost estimates; null means no limit. */
  budgetUsd: number | null
  /** True once a budget was set or "no cap" chosen, both journaled: a real plan is approved only then. */
  capDecided: boolean
  /** The costs Claude Code estimated; a session that reported none adds nothing here. */
  spentUsdEstimated: number
  /**
   * The caps of the sessions that ended without reporting a cost: an upper bound on what they spent, never an
   * estimate; the budget counts it on top of `spentUsdEstimated`. Null when nothing is bounded (never 0).
   */
  spentUsdBound: number | null
  /** How many sessions count for their cap in `spentUsdBound`. */
  sessionsBounded: number
  checkRuns: CheckRunView[]
  scorecard: ScoreLine[]
  committee: CommitteeView | null
  /** Every committee of the mission, oldest first; the last one is `committee` (ADR-0046). */
  committees: CommitteeSummaryView[]
  /** The end-of-mission retrospective (ADR-0020), once the mission has ended and someone worked on it. */
  retro: RetroView | null
  /** The ticket of the planner's staffing review while it runs; null otherwise. */
  staffingReview: string | null
  /** The director's meeting open about this mission (ADR-0047); null when none is. */
  meeting: MeetingView | null
  /** The workflow the mission follows, pinned at its creation (ADR-0050), or why this build does not run it. */
  workflow: MissionWorkflowView
  /** Where the accepted deliverables land (ADR-0051); null for a mission created without a folder. */
  delivery: DeliveryHomeView | null
  /** Put away by the director once closed (ADR-0054): out of the lists, restorable. */
  archived: boolean
  /** When the mission was completed or abandoned; null while it runs. */
  closedAt: string | null
}

/** The folder chosen at creation for the accepted deliverables, and the copy once made (ADR-0051). */
export interface DeliveryHomeView {
  destination: string
  copy: DeliveryCopyView | null
}

export type DeliveryOutcome = 'WRITTEN' | 'KEPT_BOTH' | 'SAME' | 'LEFT_OUT'

export interface DeliveryCopyView {
  /** The mission's folder in the destination: `<destination>/<mission>`. */
  folder: string
  files: DeliveredCopyFile[]
  /** Files past the journaled list, left out. */
  leftOut: number
  /** A listing of the mission was cut at its cap: some files were not considered. */
  truncated: boolean
  at: string
}

export interface DeliveredCopyFile {
  agent: string
  path: string
  writtenAs: string | null
  outcome: DeliveryOutcome
  reason: string | null
}

// director-meeting (ADR-0047)

/** What the director said at a meeting; `attendee` null when said to everyone. */
export interface MeetingNoteView {
  attendee: string | null
  text: string
  at: string
}

/** An attendee as the table sees it: `finishing` while its session still ends the call under way. */
export interface AttendeeView {
  id: string
  name: string
  status: AgentStatus
  finishing: boolean
}
