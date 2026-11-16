import { describe, expect, it } from 'vitest'
import { en as mainEn } from '../i18n/en'
import { identityEn } from '../i18n/identity.en'
import type { Key } from '../i18n'
import { ACTIONS } from './actions'
import { COMMANDS, paletteItems, searchPalette } from './palette'

const en: Record<string, string> = { ...mainEn, ...identityEn }
const t = (key: Key, params: Record<string, string | number> = {}) =>
  en[key].replace(/\{(\w+)\}/g, (all, name: string) => (name in params ? String(params[name]) : all))

const items = paletteItems({
  rooms: [
    { id: 'room-director', name: 'Director' },
    { id: 'room-compute', name: 'Compute' },
  ],
  agents: [
    { id: 'a-geoffrey', name: 'Geoffrey', role: 'planner' },
    { id: 'a-chris', name: 'Chris', role: 'developer' },
  ],
  missions: [
    { id: 'M-6', title: 'Audit the governance', status: 'COMPLETED' },
    { id: 'M-7', title: 'Audit the scene', status: 'IN_PROGRESS' },
  ],
  t,
  room: (id, name) => (id in en ? en[id as Key] : name),
})

describe('command palette', () => {
  it('offers every action of the registry, with its shortcut', () => {
    const actions = items.filter((i) => i.kind === 'action')
    expect(actions.map((i) => i.id)).toEqual(ACTIONS.map((a) => a.id))
    expect(actions.find((i) => i.id === 'board')?.key).toBe('B')
    expect(actions.find((i) => i.id === 'docs')?.href).toBe('/q/swagger-ui')
  })

  it('offers the tutorial’s restart and skip, which the bar has no button for', () => {
    expect(items.filter((i) => i.kind === 'command').map((i) => i.id)).toEqual(COMMANDS.map((c) => c.id))
    expect(searchPalette(items, 'restart the tutorial')[0]).toMatchObject({ kind: 'command', id: 'tutorialRestart' })
    expect(searchPalette(items, 'skip the tutorial')[0]).toMatchObject({ kind: 'command', id: 'tutorialSkip' })
  })

  it('gives the first rooms their number key', () => {
    expect(items.filter((i) => i.kind === 'room').map((i) => [i.id, i.key])).toEqual([
      ['0', '1'],
      ['1', '2'],
    ])
  })

  it('finds an agent by name, to open the profile', () => {
    const [first] = searchPalette(items, 'geof')
    expect(first).toMatchObject({ kind: 'agent', id: 'a-geoffrey' })
    expect(first.detail).toBe('planner: open the profile')
  })

  it('finds a mission by id or by title, latest first', () => {
    expect(searchPalette(items, 'M-6')[0]).toMatchObject({ kind: 'mission', id: 'M-6' })
    expect(searchPalette(items, 'audit').map((i) => i.id)).toEqual(['M-7', 'M-6'])
  })

  it('finds a panel by a loose spelling', () => {
    expect(searchPalette(items, 'wrkshp')[0]).toMatchObject({ kind: 'action', id: 'workshop' })
    expect(searchPalette(items, 'sandbox')[0]).toMatchObject({ kind: 'action', id: 'sandbox' })
  })

  it('lists everything without a query, actions first', () => {
    expect(searchPalette(items, '', Infinity)).toHaveLength(items.length)
    expect(searchPalette(items, '')[0].kind).toBe('action')
    expect(searchPalette(items, 'qqqqqq')).toEqual([])
  })
})
