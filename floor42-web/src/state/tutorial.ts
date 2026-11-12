import { reactive } from 'vue'
import { type Observation, parseProgress, type Progress, reduce, type TutorialEvent } from '../ui/tutorial'
import { setPanel } from './openPanels'

/**
 * The guided first mission's progress in this browser (ADR-0045): kept in localStorage so it resumes after a reload,
 * offered once, skipped or restarted from the palette and the settings. Screen state only: the mission it walks
 * through is the server's, simulated.
 */
export const PROGRESS_KEY = 'floor42.tutorial'
export const OFFERED_KEY = 'floor42.tutorial.offered'

function storage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export const tutorial = reactive({
  progress: parseProgress(storage()?.getItem(PROGRESS_KEY) ?? null) as Progress,
  offered: storage()?.getItem(OFFERED_KEY) === '1',
  /** The sample repository the practice form prepared in this page. */
  sample: null as string | null,
  /** A change of the rehearsal was accepted or rejected in the workshop, seen live in this page. */
  decided: false,
})

function keep(): void {
  storage()?.setItem(PROGRESS_KEY, JSON.stringify(tutorial.progress))
}

/** Feeds the machine; `o` is what the page shows, needed by Next. */
export function send(e: TutorialEvent, o?: Observation): void {
  const before = tutorial.progress
  const after = reduce(before, e, o)
  if (after === before) return
  tutorial.progress = after
  keep()
}

/** The offer is answered either way: it is not shown again in this browser. */
export function markOffered(): void {
  tutorial.offered = true
  storage()?.setItem(OFFERED_KEY, '1')
}

/** Starts the tutorial, or resumes it where it was left, and shows it. */
export function startTutorial(): void {
  markOffered()
  send({ kind: 'start' })
  setPanel('tutorial', true)
}

export function restartTutorial(): void {
  markOffered()
  tutorial.sample = null
  tutorial.decided = false
  send({ kind: 'restart' })
  setPanel('tutorial', true)
}

export function skipTutorial(): void {
  markOffered()
  send({ kind: 'skip' })
  setPanel('tutorial', false)
}

/** Shown once, on the first signed-in page of this browser, while no tutorial was ever started. */
export function shouldOffer(signedIn: boolean): boolean {
  return signedIn && !tutorial.offered && tutorial.progress.kind === 'idle'
}
