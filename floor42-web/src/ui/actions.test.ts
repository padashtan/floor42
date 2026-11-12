import { describe, expect, it } from 'vitest'
import { en as mainEn } from '../i18n/en'
import { identityEn } from '../i18n/identity.en'

const en: Record<string, string> = { ...mainEn, ...identityEn }
import { ACTIONS, byKey, GROUPS, grouped } from './actions'

describe('action registry', () => {
  it('names every action once, with labels that exist', () => {
    const ids = ACTIONS.map((a) => a.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const a of ACTIONS) {
      expect(en[a.label], a.id).toBeTruthy()
      if (a.title) expect(en[a.title], a.id).toBeTruthy()
    }
  })

  it('keeps every shortcut the bar had, each on one action', () => {
    const keys = ACTIONS.flatMap((a) => (a.key ? [a.key] : []))
    expect(new Set(keys).size).toBe(keys.length)
    expect(Object.fromEntries(ACTIONS.filter((a) => a.key).map((a) => [a.key, a.id]))).toEqual({
      D: 'desk',
      B: 'board',
      P: 'progress',
      W: 'workshop',
      M: 'meeting',
      L: 'library',
      C: 'console',
      U: 'usage',
    })
    expect(byKey('b')?.id).toBe('board')
    expect(byKey('B')?.id).toBe('board')
    expect(byKey('x')).toBeUndefined()
  })

  it('keeps only desk, board and progress as buttons of their own', () => {
    expect(grouped().direct.map((a) => a.id)).toEqual(['desk', 'board', 'progress'])
  })

  it('puts every action in exactly one menu, by use', () => {
    const { menus } = grouped()
    expect(menus.map((m) => m.group)).toEqual([...GROUPS])
    expect(menus.flatMap((m) => m.items.map((a) => a.id)).sort()).toEqual(ACTIONS.map((a) => a.id).sort())
    const of = (group: string) => menus.find((m) => m.group === group)!.items.map((a) => a.id)
    expect(of('work')).toEqual(['desk', 'board', 'progress', 'resume', 'workshop', 'library', 'meeting', 'callMeeting', 'workflows', 'deliverables', 'prove', 'archive', 'tutorial'])
    expect(of('team')).toEqual(['hire', 'core'])
    expect(of('system')).toEqual(['console', 'docs', 'costs', 'caps', 'sandbox', 'skills', 'terminal', 'sessions', 'identities', 'usage', 'settings'])
  })

  it('takes a new panel from one line: in its menu, never a new button of the bar', () => {
    const more = [...ACTIONS, { id: 'marketplace', group: 'system' as const, label: 'top.apiTitle' as const }]
    const g = grouped(more)
    expect(g.direct).toHaveLength(3)
    expect(g.menus.find((m) => m.group === 'system')!.items.at(-1)?.id).toBe('marketplace')
  })
})
