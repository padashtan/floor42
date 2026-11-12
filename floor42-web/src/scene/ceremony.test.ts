import { describe, expect, it } from 'vitest'
import { CHEER_SECONDS, GATHER_SECONDS, ceremonyPlan, dueSteps, participantsOf } from './ceremony'

const base = { participants: ['bobi', 'margaret-2'], seats: 6, calm: false, night: false }

describe('the end-of-mission ceremony', () => {
  it('gathers at the table, cheers once everyone could arrive, then lets everyone go', () => {
    const plan = ceremonyPlan(base)
    expect(plan.map((s) => [s.at, s.kind])).toEqual([
      [0, 'gather'],
      [GATHER_SECONDS, 'cheer'],
      [GATHER_SECONDS + CHEER_SECONDS, 'disperse'],
    ])
    expect(plan.every((s) => s.agents.join() === 'bobi,margaret-2')).toBe(true)
  })

  it('does nothing for show under reduced motion, at night, or when nobody worked', () => {
    expect(ceremonyPlan({ ...base, calm: true })).toEqual([])
    expect(ceremonyPlan({ ...base, night: true })).toEqual([])
    expect(ceremonyPlan({ ...base, participants: [] })).toEqual([])
  })

  it('gathers at most the table seats, once each', () => {
    const plan = ceremonyPlan({ ...base, participants: ['a', 'b', 'a', 'c', 'd'], seats: 3 })
    expect(plan[0].agents).toEqual(['a', 'b', 'c'])
  })

  it('plays each step once, frame by frame', () => {
    const plan = ceremonyPlan(base)
    expect(dueSteps(plan, -1, 0).map((s) => s.kind)).toEqual(['gather'])
    expect(dueSteps(plan, 0, 5)).toEqual([])
    expect(dueSteps(plan, 11.9, 12.1).map((s) => s.kind)).toEqual(['cheer'])
    expect(dueSteps(plan, 12.1, 30).map((s) => s.kind)).toEqual(['disperse'])
    expect(dueSteps(plan, 30, 60)).toEqual([])
  })

  it('takes who worked from the mission: sessions first seen first, then producers and reviewers', () => {
    const who = participantsOf({
      tickets: [
        { assignee: 'lucie', reviews: [] },
        { assignee: 'bobi', reviews: [{ reviewer: 'margaret-2' }] },
      ],
      sessions: [
        { agent: 'bobi', startedAt: '2026-10-20T09:49:02Z' },
        { agent: 'galen', startedAt: '2026-10-20T09:40:38Z' },
      ],
    })
    expect(who).toEqual(['galen', 'bobi', 'lucie', 'margaret-2'])
  })
})
