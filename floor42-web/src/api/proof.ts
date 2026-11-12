// "Prove the delivery" (ADR-0058): the views of /api/missions/{id}/proof and its calls. Kept apart from types.ts and
// client.ts, which are at their size ceiling; contract.ts checks these types against openapi.json all the same.
import { request } from './request'

/** One step as the producer wrote it in PROVE.md (their words), and who runs it. */
export interface ProofStepView {
  index: number
  title: string
  command: string
  expectExit: number | null
  expectOutput: string[]
  runner: 'FLOOR42' | 'DIRECTOR'
  /** Why the director runs it: MARKED_FOR_DIRECTOR, NEEDS_DOCKER, NEEDS_DESKTOP, AFTER_DIRECTOR_STEP, SANDBOX_UNAVAILABLE. */
  reason: string | null
  /** Codes that keep the step from running (HIDDEN_CHARACTER and COMMAND_TOO_LONG: no copy button). */
  problems: string[]
}

/** One step's measure by Floor42 in the sandbox. */
export interface ProofStepResult {
  index: number
  command: string
  outcome: 'PASSED' | 'FAILED' | 'NOT_RUN'
  failure: string | null
  exitCode: number | null
  timedOut: boolean
  durationMs: number
  output: string
}

/** Floor42's last journaled run of a recipe, bound to the recipe and the code it ran on. */
export interface ProofRunView {
  at: string
  digest: string
  head: string
  changesDigest: string
  /** The recipe, the commit or the uncommitted work changed since: this run proves nothing about them. */
  stale: boolean
  steps: ProofStepResult[]
}

/** What the director recorded of one of their steps, on the current recipe and code. */
export interface ProofWitnessView {
  step: number
  works: boolean
  seen: string
  at: string
}

export interface ProofWorktree {
  agent: string
  path: string
  state: 'FOUND' | 'MISSING' | 'UNKNOWN'
  digest: string | null
  head: string | null
  changesDigest: string | null
  problems: string[]
  steps: ProofStepView[]
  lastRun: ProofRunView | null
  witnesses: ProofWitnessView[]
  /** Names this recipe and code: a report carries it back, refused (PROOF_STALE) when it changed. */
  target: string | null
  /** What the last copy for a run left out, `REASON: path` (kept in the daemon's memory). */
  skipped: string[]
  skippedCount: number
}

/** The director's last verdict; `current` when it is about the recipes and code as they are now. */
export interface ProofVerdictView {
  verdict: 'PROVEN' | 'NOT_PROVEN'
  note: string
  at: string
  current: boolean
}

export interface ProofView {
  mission: string
  origin: string
  sandboxWorks: boolean
  running: boolean
  runError: string | null
  status: 'NO_RECIPE' | 'NOT_RUN' | 'FAILED' | 'PASSED'
  directorSteps: number
  worktrees: ProofWorktree[]
  verdict: ProofVerdictView | null
  /** Names every recipe and code: a verdict carries it back. */
  targets: string
  /** The proof state the director accepted the mission in, when not proven. */
  unprovenAccepted: string | null
}

/** What an acceptance asks about: `state` null when proven; the token goes back as `proofAcknowledged`. */
export interface ProofAcceptanceView {
  state: string | null
  token: string | null
}

export interface SendBackRequest {
  agent: string
  note: string
}

export interface WitnessRequest {
  agent: string
  step: number
  works: boolean
  seen: string
  target: string | null
}

export interface VerdictRequest {
  verdict: 'PROVEN' | 'NOT_PROVEN'
  note: string
  targets: string
}

export interface SentBack {
  ticket: string
  agent: string
}

const path = (mission: string) => `/api/missions/${encodeURIComponent(mission)}/proof`

export const proofApi = {
  proof: (mission: string) => request<ProofView>('GET', path(mission)),
  acceptance: (mission: string) => request<ProofAcceptanceView>('GET', `${path(mission)}/acceptance`),
  /** Starts Floor42's steps, on a copy, in the sandbox only, offline; answers at once. Director steps never run. */
  run: (mission: string) => request<ProofView>('POST', `${path(mission)}/run`),
  witness: (mission: string, body: WitnessRequest) => request<ProofView>('POST', `${path(mission)}/witness`, body),
  verdict: (mission: string, body: VerdictRequest) => request<ProofView>('POST', `${path(mission)}/verdict`, body),
  /** The producer's ticket goes back to work with the evidence and the director's words. */
  sendBack: (mission: string, body: SendBackRequest) => request<SentBack>('POST', `${path(mission)}/send-back`, body),
}
