import { describe, expect, it } from 'vitest'
import { goLabel } from './todo'
import { levelsAbove, levelsBelow, trustRow } from './trust'

const agent = (over: Partial<Parameters<typeof trustRow>[0]>) => ({
  id: 'ada',
  name: 'Ada',
  trust: 'PROBATION' as const,
  trustSince: '2026-11-16T09:00:00Z',
  acceptedPatches: 5,
  raiseProposed: true,
  ...over,
})

describe('the trust levels on screen', () => {
  it('lists where a click may narrow or widen, nearest first', () => {
    expect(levelsBelow('STANDARD')).toEqual(['PROBATION', 'SHADOW'])
    expect(levelsBelow('SHADOW')).toEqual([])
    expect(levelsAbove('SHADOW')).toEqual(['PROBATION', 'STANDARD'])
    expect(levelsAbove('STANDARD')).toEqual([])
  })

  it('proposes a raise as a decision row only when the daemon says the record earned it', () => {
    const row = trustRow(agent({}))!
    expect(row.category).toBe('decisions')
    expect(row.go).toEqual({ panel: 'profile', agent: 'ada' })
    expect(row.text).toContain('Ada')
    expect(goLabel(row)).toBe('todo.go.profile')
    expect(trustRow(agent({ raiseProposed: false }))).toBeNull()
    expect(trustRow(agent({ trust: 'STANDARD' }))).toBeNull()
  })
})
