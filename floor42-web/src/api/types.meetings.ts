// Mirrors of the daemon's API views, part 3 (see types.ts).

import type { AttendeeView, MeetingNoteView } from './types.tickets'

export interface MeetingView {
  id: string
  mission: string
  missionTitle: string
  agenda: string | null
  calledAt: string
  /** Null while the meeting is open. */
  closedAt: string | null
  open: boolean
  attendees: AttendeeView[]
  notes: MeetingNoteView[]
  /** The table it sits at (ADR-0049). */
  place: MeetingPlace
  /** What its turns were charged against its cap: Claude Code's estimates, and a turn's budget when it gave none (an upper bound). Never measured. */
  spentUsdCharged: number
  /** Each "Ask for their view", oldest first. */
  rounds: RoundView[]
}

export type MeetingPlace = 'TABLE' | 'CONFERENCE'

/** Where a turn of speech stands: waiting for its speaker, speaking, heard, or not heard (and why). */
export type TurnState = 'WAITING' | 'SPEAKING' | 'DONE' | 'NOT_HEARD'

/** What a turn's cost is: the session's estimate by Claude Code, or the budget charged when it gave none. */
export type CostBasis = 'ESTIMATED_BY_CLAUDE_CODE' | 'BUDGET_CHARGED'

/** One speaker's turn of a round (ADR-0049). */
export interface TurnView {
  attendee: string
  name: string
  role: string
  state: TurnState
  /** Null unless DONE. */
  answer: string | null
  /** Null unless NOT_HEARD. */
  why: string | null
  /** Null when nothing is charged; see `costBasis`. */
  costUsd: number | null
  costBasis: CostBasis | null
  /** When the turn was recorded; null while WAITING or SPEAKING. */
  at: string | null
}

/** One "Ask for their view": the speakers in their order, the meeting's cap from then on. */
export interface RoundView {
  number: number
  /** Null when the agenda says what is asked. */
  question: string | null
  capUsd: number
  askedAt: string
  turns: TurnView[]
}

/** "Ask for their view" (ADR-0049): paid, each speaker one short read-only session, in this order. */
export interface NewViews {
  speakers: string[]
  capUsd: number | null
  question: string | null
}

/** Who the panel proposes for a meeting about a mission: the agents working on it now. */
export interface MeetingInvitation {
  mission: string
  suggested: string[]
}

export interface NewMeeting {
  mission: string
  attendees: string[]
  agenda: string | null
  /** Null for the meeting table. */
  place: MeetingPlace | null
}

export type MeetingNoteUse = 'NOTE_ONLY' | 'ALSO_DIRECTIVE'

export interface NewMeetingNote {
  attendee: string | null
  text: string
  use: MeetingNoteUse
}

/** One participant's say; `skipped` says why it did not speak. */
export interface RetroLine {
  agent: string
  wentWell: string[]
  wentBadly: string[]
  text: string | null
  skipped: string | null
  at: string
}

export interface RetroView {
  ticket: string
  chair: string
  participants: string[]
  /** Participants who have not spoken yet. */
  pending: string[]
  /** What Floor42 gave every participant, from the journal. */
  facts: string
  contributions: RetroLine[]
  conclusions: string[]
  text: string | null
  /** Why it ended without the chair's conclusion. */
  reason: string | null
  concluded: boolean
  lessons: LessonView[]
}

/** A lesson drawn by a retrospective; only an accepted one reaches later briefs. `audience` null means everyone. */
export interface LessonView {
  id: string
  mission: string
  audience: 'DEVELOPER' | 'REVIEWER' | 'RESEARCHER' | 'PLANNER' | 'ANALYST' | null
  text: string
  status: 'PROPOSED' | 'ACCEPTED' | 'REJECTED' | 'RETIRED'
  reason: string | null
  proposedBy: string | null
  proposedAt: string
}

/** A company-wide setting: the value in force, the daemon's default, and whether the director changed it. */
export interface SettingView {
  key: 'PARALLEL_SESSIONS' | 'PLAN_LIMIT_THRESHOLD' | 'RETRO_MODEL' | 'AGENT_LANGUAGE' | 'DIRECTOR_PROFILE'
    | 'SESSION_BUDGET_USD'
    | 'OFFICE_THEME'
  | 'SHELL_SANDBOX'
  | 'SANDBOX_NETWORK'
  | 'ACCEPTED_STREAM_LINES'
  | 'STALL_MINUTES'
  | 'MISSION_BUDGET_USD'
  value: string
  defaultValue: string
  changed: boolean
  /** The accepted values; empty when any value in range is accepted. */
  options: string[]
}

export interface MemberView {
  agent: string
  role: string
  chair: boolean
  /** Still owes a contribution in the current step. */
  pending: boolean
}

/** A persisted committee contribution; summary fields are null when the member did not state them. */
export interface ContributionView {
  member: string
  role: string
  round: number
  kind: 'ANALYSIS' | 'REBUTTAL' | 'SYNTHESIS'
  text: string
  proposal: string | null
  position: string | null
  criticalObjection: string | null
  at: string
  /** Written by the director in person, in the seat of their double. */
  byDirector: boolean
  /** What the member could not settle without the director (its QUESTIONS line). */
  questions: string[]
}

/** An architecture committee, or an idea committee convened before a new project (ADR-0024). */
export type CommitteePanel = 'architecture' | 'idea'

export interface CommitteeView {
  topic: string
  ticket: string
  stage:
    | 'ANALYSIS'
    | 'DEBATE'
    | 'SYNTHESIS'
    | 'CLARIFICATION'
    | 'AWAITING_ANSWERS'
    | 'AWAITING_DIRECTOR'
    | 'ADOPTED'
    | 'REJECTED'
  round: number
  members: MemberView[]
  contributions: ContributionView[]
  constraints: string[]
  standingCriticalObjections: ContributionView[]
  implement: boolean
  panel: CommitteePanel
  /** The chair only writes the record: a neutral moderator. */
  moderated: boolean
  maxDebateRounds: number
  /** The member the director may speak for in person, or null. */
  directorSeat: string | null
  /** The member every debater stands behind, read from their closing lines; null when they do not all agree. */
  agreement: string | null
  debateRounds: number
  /** The business analyst taking the members' questions to the director, or null. */
  analyst: string | null
  /** The business analyst's latest note: what it gathered, settled itself, asks. */
  clarificationNote: string | null
  clarifications: number
  /** The committee's number in the mission, from 1 (ADR-0046). */
  number: number
}

/** One committee of the mission's history, in a line. */
export interface CommitteeSummaryView {
  number: number
  topic: string
  ticket: string
  stage: CommitteeView['stage']
  /** The latest decision record's recommendation, or null before one or when not stated. */
  recommendation: string | null
  implement: boolean
}

export interface RoleView {
  key: string
  name: string
  title: string
  model: string
  lens: string
  panels: CommitteePanel[]
  /** A seat the director defined. */
  custom: boolean
  /** Its member has left the company: the seat cannot be chosen. */
  vacant: boolean
}

/** A committee seat of the director's own: its key and its member's id are made from the name. */
export interface NewSeatRequest {
  name: string
  title: string
  lens: string
  model: string
  effort: string
  panels: CommitteePanel[]
}

/** A run of the director's checks; exit code, duration and output are measured by Floor42. */
export interface CheckRunView {
  workspace: string
  command: string
  exitCode: number | null
  timedOut: boolean
  passed: boolean
  durationMs: number
  output: string
  head: string
  trigger: string
  at: string
}

/** One result of a mission, with how it was obtained (measured, provided, estimated, reported, unavailable). */
export interface ScoreLine {
  label: string
  value: string
  basis: string
  /** Lets the screen write the value in its language (ADR-0021, lot 3); null when the value is data as recorded. */
  code: string | null
  params: Record<string, string> | null
}

export type ToolDecision = 'ALLOW' | 'ASK' | 'ASK_ALWAYS' | 'DENY'
