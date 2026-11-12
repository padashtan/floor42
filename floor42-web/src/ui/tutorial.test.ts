import { describe, expect, it } from 'vitest'
import { en } from '../i18n/en'
import { fr } from '../i18n/fr'
import {
  canGoOn,
  IDLE,
  type Observation,
  type ObservedMission,
  parseProgress,
  type Progress,
  reduce,
  STEP_IDS,
  STEPS,
  type StepId,
} from './tutorial'

const closed = { desk: false, workshop: false, deliverables: false, report: false, costs: false, sandbox: false }

function seen(over: Partial<Observation> = {}): Observation {
  return { open: closed, shown: false, sample: null, mission: null, decided: false, ...over }
}

function mission(over: Partial<ObservedMission> = {}): ObservedMission {
  return { id: 'M-1', status: 'AWAITING_APPROVAL', capDecided: false, questions: [], ...over }
}

const at = (step: StepId, m: string | null = 'M-1'): Progress => ({ kind: 'running', step, mission: m, visited: [] })
const stepOf = (p: Progress) => (p.kind === 'running' ? p.step : p.kind)
const observe = (p: Progress, o: Observation) => reduce(p, { kind: 'observed', observation: o })

describe('the tutorial steps', () => {
  it('are thirteen, in the order of a first mission, each written in English and French', () => {
    expect(STEP_IDS).toHaveLength(13)
    expect(STEPS.map((s) => s.id)).toEqual(STEP_IDS)
    for (const s of STEPS) {
      for (const key of [s.title, s.body]) {
        expect((en as Record<string, string>)[key], key).toBeTruthy()
        expect((fr as Record<string, string>)[key], key).toBeTruthy()
      }
      expect(s.targets.length, `${s.id} points at something`).toBeGreaterThan(0)
    }
  })

  it('starts at the first step, resumes where it was skipped, and restarts from the beginning', () => {
    const started = reduce(IDLE, { kind: 'start' })
    expect(started).toEqual(at('signIn', null))
    const skipped = reduce(at('approve'), { kind: 'skip' })
    expect(skipped).toEqual({ kind: 'skipped', step: 'approve', mission: 'M-1' })
    expect(reduce(skipped, { kind: 'start' })).toEqual(at('approve'))
    expect(reduce(at('costs'), { kind: 'start' })).toEqual(at('costs'))
    expect(reduce(at('costs'), { kind: 'restart' })).toEqual(at('signIn', null))
    expect(reduce({ kind: 'finished' }, { kind: 'start' })).toEqual(at('signIn', null))
  })

  it('goes on from a read step only once its element is on screen, by the director pressing Next', () => {
    const p = at('signIn', null)
    expect(reduce(p, { kind: 'next' }, seen())).toBe(p)
    expect(reduce(p, { kind: 'next' }, seen({ shown: true }))).toEqual(at('openDesk', null))
    expect(observe(p, seen({ shown: true })), 'reading is never assumed').toBe(p)
  })

  it('never lets Next jump over a step that waits for the director', () => {
    const p = at('approve')
    expect(canGoOn(p, seen({ shown: true }))).toBe(false)
    expect(reduce(p, { kind: 'next' }, seen({ shown: true }))).toBe(p)
  })

  it('waits for the desk, the sample and the practice mission', () => {
    expect(stepOf(observe(at('openDesk', null), seen()))).toBe('openDesk')
    expect(stepOf(observe(at('openDesk', null), seen({ open: { ...closed, desk: true } })))).toBe('sample')
    expect(stepOf(observe(at('sample', null), seen({ sample: '/data/tutorial/sample-repository' })))).toBe('objective')
    const started = reduce(at('objective', null), { kind: 'practiceStarted', mission: 'M-7' })
    expect(started).toEqual(at('objective', 'M-7'))
    expect(stepOf(observe(started, seen({ mission: mission({ id: 'M-7' }) })))).toBe('plan')
  })

  it('waits for the approval with its cap decided', () => {
    const p = at('approve')
    expect(stepOf(observe(p, seen({ mission: mission() })))).toBe('approve')
    expect(stepOf(observe(p, seen({ mission: mission({ status: 'IN_PROGRESS', capDecided: true }) })))).toBe('question')
  })

  it('tells the agent question from the write approval', () => {
    const approval = { kind: 'ACTION_APPROVAL', status: 'OPEN' }
    const open = mission({ status: 'IN_PROGRESS', questions: [{ kind: 'OPEN', status: 'OPEN' }] })
    expect(stepOf(observe(at('question'), seen({ mission: open })))).toBe('question')
    const answered = mission({ status: 'IN_PROGRESS', questions: [{ kind: 'OPEN', status: 'ANSWERED' }, approval] })
    expect(stepOf(observe(at('question'), seen({ mission: answered })))).toBe('allowWrite')
    expect(stepOf(observe(at('allowWrite'), seen({ mission: answered })))).toBe('allowWrite')
    const allowed = mission({ questions: [{ kind: 'ACTION_APPROVAL', status: 'ANSWERED' }] })
    expect(stepOf(observe(at('allowWrite'), seen({ mission: allowed })))).toBe('workshop')
  })

  it('waits for a decision in the workshop', () => {
    expect(stepOf(observe(at('workshop'), seen()))).toBe('workshop')
    expect(stepOf(observe(at('workshop'), seen({ decided: true })))).toBe('deliverables')
  })

  it('remembers each panel seen, and offers Next once the deliverables and the report were both open', () => {
    let p = observe(at('deliverables'), seen({ open: { ...closed, deliverables: true } }))
    expect(p).toMatchObject({ visited: ['deliverables'] })
    expect(canGoOn(p, seen())).toBe(false)
    p = observe(p, seen({ open: { ...closed, report: true } }))
    expect(canGoOn(p, seen())).toBe(true)
    expect(stepOf(reduce(p, { kind: 'next' }, seen()))).toBe('costs')
  })

  it('finishes after the going-real checklist', () => {
    expect(reduce(at('goingReal'), { kind: 'next' }, seen({ shown: true }))).toEqual({ kind: 'finished' })
  })

  it('goes back to a new practice mission when the rehearsal is lost, and only then', () => {
    expect(reduce(at('workshop', 'M-4'), { kind: 'practiceLost' })).toEqual(at('sample', null))
    expect(reduce(at('signIn', null), { kind: 'practiceLost' })).toEqual(at('signIn', null))
  })

  it('ignores what does not apply', () => {
    expect(reduce(IDLE, { kind: 'next' }, seen({ shown: true }))).toBe(IDLE)
    expect(reduce(IDLE, { kind: 'skip' })).toBe(IDLE)
    expect(observe(IDLE, seen({ open: { ...closed, desk: true } }))).toBe(IDLE)
    expect(reduce({ kind: 'finished' }, { kind: 'practiceStarted', mission: 'M-2' })).toEqual({ kind: 'finished' })
  })
})

describe('the stored progress', () => {
  it('reads back what was kept, the panels seen excepted', () => {
    const kept = JSON.stringify({ kind: 'running', step: 'workshop', mission: 'M-3', visited: ['report'] })
    expect(parseProgress(kept)).toEqual(at('workshop', 'M-3'))
    expect(parseProgress(JSON.stringify({ kind: 'skipped', step: 'plan', mission: null }))).toEqual({
      kind: 'skipped',
      step: 'plan',
      mission: null,
    })
    expect(parseProgress('{"kind":"finished"}')).toEqual({ kind: 'finished' })
  })

  it('reads anything unknown or broken as a tutorial never started', () => {
    for (const raw of [null, '', 'nope', '{}', '{"kind":"running","step":"gone"}', '{"kind":"other","step":"plan"}']) {
      expect(parseProgress(raw), String(raw)).toEqual(IDLE)
    }
  })
})
