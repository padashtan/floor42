import { describe, expect, it } from 'vitest'
import type { CoreDismissalEntry } from '../api/types'
import { confirmed, groupPreview, reasonKey, tally } from './coreDismissal'

const entry = (id: string, fate: CoreDismissalEntry['fate'], code: string | null = null): CoreDismissalEntry => ({
  id,
  name: id[0].toUpperCase() + id.slice(1),
  role: 'Developer',
  fate,
  code,
  reason: code ? `server says ${code}` : null,
  handsBack: [],
})

describe('groupPreview', () => {
  const groups = groupPreview([
    entry('zoe', 'LEAVES'),
    entry('chris', 'BLOCKED', 'AGENT_BUSY'),
    entry('kora', 'STAYS'),
    entry('ada', 'LEAVES'),
    entry('galen', 'BLOCKED', 'AGENT_NEEDED'),
  ])

  it('sorts every agent into leaving, staying or blocked, by name', () => {
    expect(groups.leaving.map((e) => e.id)).toEqual(['ada', 'zoe'])
    expect(groups.staying.map((e) => e.id)).toEqual(['kora'])
    expect(groups.blocked.map((e) => e.id)).toEqual(['chris', 'galen'])
  })

  it('confirms only those shown as leaving: never a core member nor one at work', () => {
    expect(confirmed(groups)).toEqual(['ada', 'zoe'])
  })

  it('gives nothing to confirm when everyone stays or is blocked', () => {
    expect(confirmed(groupPreview([entry('kora', 'STAYS'), entry('chris', 'BLOCKED', 'AGENT_BUSY')]))).toEqual([])
  })
})

describe('reasonKey', () => {
  it('reuses the single dismissal sentences', () => {
    expect(reasonKey('AGENT_BUSY')).toBe('dismiss.refused.AGENT_BUSY')
    expect(reasonKey('AGENT_NEEDED')).toBe('dismiss.refused.AGENT_NEEDED')
    expect(reasonKey('CORE')).toBe('core.skippedCore')
  })

  it('leaves an unknown code to the daemon text', () => {
    expect(reasonKey('SOMETHING_NEW')).toBeNull()
    expect(reasonKey(null)).toBeNull()
  })
})

describe('tally', () => {
  it('counts each outcome', () => {
    const o = (result: 'DISMISSED' | 'REFUSED' | 'SKIPPED') => ({ id: 'x', name: 'X', result, code: null, message: null, handsBack: [] })
    expect(tally([o('DISMISSED'), o('DISMISSED'), o('REFUSED'), o('SKIPPED')])).toEqual({ dismissed: 2, refused: 1, skipped: 1 })
  })
})
