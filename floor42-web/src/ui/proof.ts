import type { ProofStepResult, ProofStepView, ProofView, ProofWorktree } from '../api/proof'
import { t, tr } from '../i18n'
import { stamp } from './when'

/** The sections of the screen "Prove the delivery" (ADR-0058), in their order. */
export const PROVE_SECTIONS = ['recipe', 'run', 'yours', 'verdict'] as const
export type ProveSectionId = (typeof PROVE_SECTIONS)[number]

/** The key of one step over every worktree: agent and index. */
export const stepKey = (agent: string, index: number) => `${agent}#${index}`

/** A step whose command must not be offered to copy: cut, or holding characters that paste other than they read. */
export const noCopy = (s: ProofStepView) => s.problems.includes('COMMAND_TOO_LONG') || s.problems.includes('HIDDEN_CHARACTER')

/** Floor42's measure of one step, from a run on the recipe and code as they are now; null when there is none. */
export function resultOf(w: ProofWorktree, index: number): ProofStepResult | null {
  if (!w.lastRun || w.lastRun.stale) return null
  return w.lastRun.steps.find((s) => s.index === index) ?? null
}

/** What a step must show, in the screen's language. */
export function expected(s: ProofStepView): string {
  const parts: string[] = []
  if (s.expectExit !== null) parts.push(t('prove.expectExit', { code: s.expectExit }))
  for (const text of s.expectOutput) parts.push(t('prove.expectOutput', { text }))
  return parts.join(' · ')
}

/** Problem codes of a recipe, in the screen's language. */
export const problemsText = (codes: string[]) => codes.map((p) => tr(`prove.problem.${p}`, p)).join(', ')

/** The director's verdict with its date. */
export const verdictLine = (v: NonNullable<ProofView['verdict']>) =>
  t('prove.verdictGiven', { time: stamp(v.at), verdict: t(v.verdict === 'PROVEN' ? 'prove.verdictProven' : 'prove.verdictNotProven') })

/** Why a step goes to the director, or what keeps it from running. */
export function stepNote(s: ProofStepView): string {
  if (s.problems.length) return problemsText(s.problems)
  return s.reason ? tr(`prove.reason.${s.reason}`, s.reason) : t('prove.byFloor42')
}

/** Where the proof stands, in words: "passed" is never said when Floor42 had nothing to run. */
export function statusText(v: ProofView): string {
  const floor42 = v.worktrees.some((w) => w.steps.some((s) => s.runner === 'FLOOR42'))
  return v.status === 'PASSED' && !floor42 ? t('prove.status.NOTHING_FOR_FLOOR42') : t(`prove.status.${v.status}`)
}

/** The director's steps over every worktree, in order. */
export function directorSteps(v: ProofView): { worktree: ProofWorktree; step: ProofStepView }[] {
  return v.worktrees.flatMap((w) => w.steps.filter((s) => s.runner === 'DIRECTOR').map((step) => ({ worktree: w, step })))
}

/** What the director recorded of one of their steps, on the recipe and code as they are now. */
export const witnessOf = (w: ProofWorktree, index: number) => w.witnesses.find((x) => x.step === index) ?? null

/** One line per reason the delivery is not proven: what the acceptance asks about. Empty when proven. */
export function proofLines(v: ProofView): string[] {
  const lines: string[] = []
  for (const w of v.worktrees) {
    if (w.state === 'MISSING') lines.push(t('prove.line.missing', { agent: w.agent }))
    else if (w.state === 'FOUND' && !w.head) lines.push(t('prove.line.codeUnknown', { agent: w.agent }))
    else if (w.state === 'UNKNOWN' || w.problems.length || w.steps.some((s) => s.problems.length))
      lines.push(t('prove.line.unusable', { agent: w.agent }))
    else if (w.lastRun && !w.lastRun.stale && w.lastRun.steps.some((s) => s.outcome === 'FAILED'))
      lines.push(t('prove.line.failed', { agent: w.agent }))
    else if (w.steps.some((s) => s.runner === 'FLOOR42') && (!w.lastRun || w.lastRun.stale))
      lines.push(t('prove.line.notRun', { agent: w.agent }))
  }
  if (!v.worktrees.length) return lines
  if (!v.verdict?.current) lines.push(t('prove.line.noVerdict'))
  else if (v.verdict.verdict !== 'PROVEN') lines.push(t('prove.line.notProven'))
  return lines
}
