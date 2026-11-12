import { describe, expect, it } from 'vitest'
import type { InstalledItem, SuggestionItem } from '../api/types'
import { NO_FILTER, counts, filterSuggestions, groupInstalled, localText } from './catalogue'

const item = (over: Partial<InstalledItem>): InstalledItem => ({
  kind: 'SKILL',
  level: 'USER',
  plugin: null,
  name: 'x',
  description: null,
  tools: [],
  model: null,
  version: null,
  path: 'skills/x/SKILL.md',
  problem: null,
  state: 'UNKNOWN',
  reason: 'noSession',
  ...over,
})

const suggestion = (over: Partial<SuggestionItem>): SuggestionItem => ({
  id: 'a',
  kind: 'plugin',
  name: 'a',
  source: 'https://github.com/anthropics/claude-plugins-official',
  brings: { en: 'Brings things', fr: 'Apporte des choses' },
  risks: { en: 'Risky', fr: 'Risqué' },
  install: ['/plugin install a@m'],
  license: 'Apache-2.0',
  installed: false,
  state: 'UNKNOWN',
  reason: 'afterSession',
  ...over,
})

const items: InstalledItem[] = [
  item({ name: 'zeta', level: 'PLUGIN', plugin: 'tidy', path: 'skills/zeta/SKILL.md' }),
  item({ name: 'tidy', kind: 'PLUGIN', level: 'PLUGIN', plugin: 'tidy', path: 'cache/m/tidy/1.0' }),
  item({ name: 'reviewer', kind: 'AGENT', level: 'USER', path: 'agents/reviewer.md', state: 'NOT_AVAILABLE' }),
  item({ name: 'db-migration', description: 'Versioned migrations', state: 'NOT_AVAILABLE' }),
  item({ name: 'release', level: 'PROJECT', path: '.claude/skills/release/SKILL.md' }),
  item({ name: 'alpha', level: 'PLUGIN', plugin: 'alpha-pack', kind: 'AGENT' }),
]

describe('skills catalogue', () => {
  it('groups by source: user, project, then plugins by name, a plugin line first', () => {
    const groups = groupInstalled(items)
    expect(groups.map((g) => g.key)).toEqual(['user', 'project', 'plugin:alpha-pack', 'plugin:tidy'])
    expect(groups[0].items.map((i) => i.name)).toEqual(['db-migration', 'reviewer'])
    expect(groups[3].items.map((i) => i.name)).toEqual(['tidy', 'zeta'])
    expect(groups[3].plugin).toBe('tidy')
  })

  it('filters by kind, state and text, and drops empty groups', () => {
    expect(groupInstalled(items, { ...NO_FILTER, kind: 'AGENT' }).map((g) => g.key)).toEqual([
      'user',
      'plugin:alpha-pack',
    ])
    expect(groupInstalled(items, { ...NO_FILTER, reach: 'NOT_AVAILABLE' }).flatMap((g) => g.items.map((i) => i.name)))
      .toEqual(['db-migration', 'reviewer'])
    // The description and the path are searched too, case-insensitively.
    expect(groupInstalled(items, { ...NO_FILTER, text: 'VERSIONED' })[0].items[0].name).toBe('db-migration')
    expect(groupInstalled(items, { ...NO_FILTER, text: '.claude/skills' })[0].key).toBe('project')
    // A plugin's skill found by its plugin's name keeps its group.
    expect(groupInstalled(items, { ...NO_FILTER, text: 'tidy', kind: 'SKILL' })[0].items.map((i) => i.name)).toEqual([
      'zeta',
    ])
    expect(groupInstalled(items, { ...NO_FILTER, text: 'nothing like it' })).toEqual([])
  })

  it('counts every state, filtered or not', () => {
    expect(counts(items)).toEqual({ AVAILABLE: 0, NOT_AVAILABLE: 2, UNKNOWN: 4 })
    expect(counts([])).toEqual({ AVAILABLE: 0, NOT_AVAILABLE: 0, UNKNOWN: 0 })
  })

  it('filters suggestions by kind and by what they bring', () => {
    const list = [suggestion({ id: 'p' }), suggestion({ id: 'm', kind: 'mcp', state: 'NOT_AVAILABLE', brings: { en: 'Browser' } })]
    expect(filterSuggestions(list, { ...NO_FILTER, kind: 'MCP' }).map((s) => s.id)).toEqual(['m'])
    expect(filterSuggestions(list, { ...NO_FILTER, text: 'browser' }).map((s) => s.id)).toEqual(['m'])
    expect(filterSuggestions(list, { ...NO_FILTER, reach: 'UNKNOWN' }).map((s) => s.id)).toEqual(['p'])
    expect(filterSuggestions(list, NO_FILTER)).toHaveLength(2)
  })

  it('shows a catalogue text in the screen language, else English', () => {
    expect(localText({ en: 'Hello', fr: 'Bonjour' }, 'fr')).toBe('Bonjour')
    expect(localText({ en: 'Hello' }, 'fr')).toBe('Hello')
    expect(localText({}, 'fr')).toBe('')
  })
})
