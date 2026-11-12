// Mirrors of the daemon's API views, part 9 (see types.ts).

import type { DirectorSessionView } from './types.plugins'

export type TokenScope = 'read' | 'missions:write' | 'all'

export interface ScriptTokenRequest {
  scope: TokenScope
  days: number
  label: string | null
}

/** A script token just issued: `token` is shown this once and never again. */
export interface IssuedScriptToken {
  session: DirectorSessionView
  token: string
}

/** Where Floor42's sample repository is: in its data folder, never one of the director's (ADR-0046). */
export interface SampleView {
  path: string
}

/** The guided first mission: the director's title and objective, played on the sample repository. */
export interface PracticeRequest {
  title: string
  objective: string
}

/** Where a rehearsal stands: its script runs, it reached its end, or it stopped and nothing resumes it (ADR-0045). */
export interface PracticeView {
  mission: string
  state: 'PLAYING' | 'DONE' | 'STOPPED'
}

// workflows (ADR-0050)

export type WorkflowStepKind = 'plan' | 'approval' | 'committee' | 'produce' | 'review' | 'check' | 'task' | 'accept' | 'retro'

/** A step by its schema name; maxTickets for the plan step only, check for a director's check only (ADR-0052). */
export interface StepView {
  step: WorkflowStepKind
  maxTickets: number | null
  check: CheckView | null
  task: TaskView | null
}

/** A director's task (schema version 3, ADR-0061): once per mission, a ticket of its own, run by a developer, a reviewer or a researcher. */
export interface TaskView {
  key: string
  title: string
  does: string
  why: string
  handsOver: string
  slot: 'beforeProduction' | 'beforeAcceptance'
  runBy: 'developer' | 'reviewer' | 'researcher'
  reviewed: boolean
}

/** A director's check (schema version 2): its texts are the director's, run by a reviewer, after the review. */
export interface CheckView {
  key: string
  title: string
  does: string
  why: string
  handsOver: string
  slot: 'checks'
  runBy: 'reviewer'
  blocks: boolean
}

export interface WorkflowView {
  name: string
  steps: StepView[]
  /** `loop`, or `graph` when the planner draws the mission's task graph (ADR-0062). */
  mode: string
}

/** pinned is false for a mission journaled before 1.9.0 or simulated; workflow is null when this build cannot read it. */
export interface MissionWorkflowView {
  pinned: boolean
  workflow: WorkflowView | null
  schemaVersion: number | null
  refusal: string | null
  /** The saved version the pin is a copy of (ADR-0052); null for a copy of none. */
  follows: FollowsView | null
}

export interface FollowsView {
  name: string
  version: number
  /** The name was removed after the pin: that version belongs to a workflow that is gone. */
  removed: boolean
}

/** One of Floor42's starting points; the screen translates its title by key. */
export interface TemplateView {
  key: string
  revision: number
  workflow: WorkflowView
}

/** One save of a company workflow; a save this build cannot read has no workflow and cannot be restored. */
export interface VersionView {
  version: number
  savedAt: string
  schemaVersion: number
  workflow: WorkflowView | null
  refusal: string | null
}

export interface CompanyWorkflowView {
  name: string
  schemaVersion: number
  workflow: WorkflowView | null
  refusal: string | null
  version: number
  versions: VersionView[]
}

/** The export file (ADR-0052). */
export interface WorkflowFileView {
  format: string
  formatVersion: number
  schemaVersion: number
  document: { name: string; steps: unknown[] }
}

/** One problem of an imported file: the code the screen writes a sentence for, and what it is about when plain. */
export interface ImportProblemView {
  code: string
  subject: string | null
}

export interface ImportView {
  workflow: WorkflowView | null
  problems: ImportProblemView[]
}

export interface WorkflowProposalView {
  number: number
  mission: string
  by: string
  at: string
  workflow: WorkflowView | null
  refusal: string | null
  status: 'OPEN' | 'ADOPTED' | 'DISMISSED'
}

export interface WorkflowsView {
  builtIn: WorkflowView
  templates: TemplateView[]
  workflows: CompanyWorkflowView[]
  proposals: WorkflowProposalView[]
  /** The highest workflow schema version this daemon saves now: a version is read one release before it is written (ADR-0052). */
  writesSchema: number
}
