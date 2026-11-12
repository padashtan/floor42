import type { Key } from '../i18n'

/**
 * The guided first mission (ADR-0045): thirteen steps over the real screen, each pointing at the real element, saying
 * in a sentence or two what it is for, and waiting for the director's own action. It never acts for them. The mission
 * it walks through is simulated on Floor42's sample repository: no model is called. Pure: a typed state machine fed with
 * what the page shows, so every step is tested without a browser; components/TutorialOverlay.vue draws it.
 */
export const STEP_IDS = [
  'signIn',
  'openDesk',
  'sample',
  'objective',
  'plan',
  'permissions',
  'approve',
  'question',
  'allowWrite',
  'workshop',
  'deliverables',
  'costs',
  'goingReal',
] as const
export type StepId = (typeof STEP_IDS)[number]

/** The panels a step may wait for: the registry's (state/openPanels.ts), and the end-of-mission report. */
export type PanelName = 'desk' | 'workshop' | 'deliverables' | 'report' | 'costs' | 'sandbox'

/** One ticket or question of the rehearsal, as the mission view gives it. */
export interface ObservedQuestion {
  kind: string
  status: string
}

/** The rehearsal as the board shows it; null while it does not exist or another mission is on the board. */
export interface ObservedMission {
  id: string
  status: string
  capDecided: boolean
  questions: readonly ObservedQuestion[]
}

/** What the page shows now: the overlay reads it from the real state, the machine decides from it. */
export interface Observation {
  open: Readonly<Record<PanelName, boolean>>
  /** The step's own element is on screen (its first target). */
  shown: boolean
  /** The sample repository the practice form prepared, if any. */
  sample: string | null
  mission: ObservedMission | null
  /** A change of the rehearsal was accepted or rejected in the workshop. */
  decided: boolean
}

/** How a step ends: read then Next, a state the director reaches, or panels to open before Next. */
export type Completion =
  | { kind: 'read' }
  | { kind: 'until'; done: (o: Observation) => boolean }
  | { kind: 'visit'; panels: readonly PanelName[] }

export interface Step {
  id: StepId
  /** CSS selectors of the element to point at, the step's own first, then where to go to reach it. */
  targets: readonly string[]
  completion: Completion
  title: Key
  body: Key
}

const answered = (kind: (k: string) => boolean) => (o: Observation) =>
  !!o.mission?.questions.some((q) => kind(q.kind) && q.status !== 'OPEN')

const desk = '.bar-nav button.desk'
const deskTab = (tab: string) => `.desk-panel .tabs button[data-tab="${tab}"]`

function step(id: StepId, targets: readonly string[], completion: Completion): Step {
  return { id, targets, completion, title: `tutorial.${id}.title` as Key, body: `tutorial.${id}.body` as Key }
}

export const STEPS: readonly Step[] = [
  step('signIn', ['.bar-nav button[data-menu="system"]'], { kind: 'read' }),
  step('openDesk', [desk], { kind: 'until', done: (o) => o.open.desk }),
  step('sample', ['.practice-mission .sample', deskTab('new'), desk], { kind: 'until', done: (o) => o.sample !== null }),
  step('objective', ['.practice-mission form', deskTab('new'), desk], { kind: 'until', done: (o) => o.mission !== null }),
  step('plan', ['.desk-panel .plan', deskTab('mission'), desk], { kind: 'read' }),
  step('permissions', ['.desk-panel table.permissions', deskTab('permissions'), desk], { kind: 'read' }),
  step('approve', ['.desk-panel .plan-cap', deskTab('mission'), desk], {
    kind: 'until',
    done: (o) => !!o.mission && o.mission.status !== 'AWAITING_APPROVAL' && o.mission.capDecided,
  }),
  step('question', ['.desk-panel .question', deskTab('decide'), desk], {
    kind: 'until',
    done: answered((k) => k !== 'ACTION_APPROVAL'),
  }),
  step('allowWrite', ['.desk-panel .question', deskTab('decide'), desk], {
    kind: 'until',
    done: answered((k) => k === 'ACTION_APPROVAL'),
  }),
  step('workshop', ['.panel.workshop', '.bar-nav button[data-menu="work"]'], { kind: 'until', done: (o) => o.decided }),
  step('deliverables', ['.deliverables-panel', '.mission-end', '.bar-nav button[data-menu="work"]'], {
    kind: 'visit',
    panels: ['deliverables', 'report'],
  }),
  step('costs', ['.costs-panel tr.simulated', '.bar-nav button[data-menu="system"]'], { kind: 'read' }),
  step('goingReal', ['.tutorial-card .checklist'], { kind: 'read' }),
]

/** Where the director is in the tutorial, kept per browser. */
export type Progress =
  | { kind: 'idle' }
  | { kind: 'running'; step: StepId; mission: string | null; visited: readonly PanelName[] }
  | { kind: 'skipped'; step: StepId; mission: string | null }
  | { kind: 'finished' }

export type TutorialEvent =
  | { kind: 'start' }
  | { kind: 'restart' }
  | { kind: 'skip' }
  | { kind: 'next' }
  | { kind: 'practiceStarted'; mission: string }
  /** The rehearsal stopped for good (a restart, a refusal, patience): back to preparing a new one. */
  | { kind: 'practiceLost' }
  | { kind: 'observed'; observation: Observation }

export const IDLE: Progress = { kind: 'idle' }

export function stepOf(id: StepId): Step {
  return STEPS.find((s) => s.id === id)!
}

export function indexOf(id: StepId): number {
  return STEP_IDS.indexOf(id)
}

function running(step: StepId, mission: string | null): Progress {
  return { kind: 'running', step, mission, visited: [] }
}

/** The step after this one, or the end. */
function advance(p: Extract<Progress, { kind: 'running' }>): Progress {
  const next = STEP_IDS[indexOf(p.step) + 1]
  return next ? running(next, p.mission) : { kind: 'finished' }
}

/** Whether Next may be pressed now: a read step once its element is on screen, a visit once every panel was seen. */
export function canGoOn(p: Progress, o: Observation): boolean {
  if (p.kind !== 'running') return false
  const c = stepOf(p.step).completion
  if (c.kind === 'read') return o.shown
  if (c.kind === 'visit') return c.panels.every((panel) => p.visited.includes(panel) || o.open[panel])
  return false
}

function observed(p: Extract<Progress, { kind: 'running' }>, o: Observation): Progress {
  const c = stepOf(p.step).completion
  if (c.kind === 'until') return c.done(o) ? advance(p) : p
  if (c.kind !== 'visit') return p
  const seen = c.panels.filter((panel) => o.open[panel] && !p.visited.includes(panel))
  return seen.length ? { ...p, visited: [...p.visited, ...seen] } : p
}

/** The machine: one event, the next progress. Events that do not apply leave the progress as it is. */
export function reduce(p: Progress, e: TutorialEvent, o?: Observation): Progress {
  switch (e.kind) {
    case 'restart':
      return running(STEP_IDS[0], null)
    case 'start':
      if (p.kind === 'skipped') return running(p.step, p.mission)
      return p.kind === 'running' ? p : running(STEP_IDS[0], null)
    case 'skip':
      return p.kind === 'running' ? { kind: 'skipped', step: p.step, mission: p.mission } : p
    case 'practiceStarted':
      return p.kind === 'running' ? { ...p, mission: e.mission } : p
    case 'practiceLost':
      return p.kind === 'running' && p.mission !== null ? running('sample', null) : p
    case 'next':
      return p.kind === 'running' && o && canGoOn(p, o) ? advance(p) : p
    case 'observed':
      return p.kind === 'running' ? observed(p, e.observation) : p
  }
}

/** Reads a stored progress; anything unreadable or from another version is a tutorial never started. */
export function parseProgress(raw: string | null): Progress {
  try {
    const v = raw ? (JSON.parse(raw) as Partial<Record<string, unknown>>) : null
    if (!v || typeof v.kind !== 'string') return IDLE
    if (v.kind === 'finished') return { kind: 'finished' }
    const step = STEP_IDS.find((s) => s === v.step)
    const mission = typeof v.mission === 'string' ? v.mission : null
    if (!step) return IDLE
    if (v.kind === 'skipped') return { kind: 'skipped', step, mission }
    return v.kind === 'running' ? running(step, mission) : IDLE
  } catch {
    return IDLE
  }
}
